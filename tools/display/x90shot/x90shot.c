/* x90shot v0.2 - native-coordinate STILL capture of the TV's final processed
 * output (what the panel is driven with: Sony video + Android/engine overlays),
 * read through the two stock OSTG pixel monitors (left branch SRE 0x6900, right
 * branch 0xa900). 2026-11-16. v0.1 preserved as x90shot.v0.1.c.
 *
 * NOT a single-frame capture: points are sampled across many refreshes, so the
 * result is only valid for STATIC content.
 *
 * Timing model (validated 2026-11-05, x90pixel_preview v9): the monitor latches
 * its position about 40 scan lines after the PSC line counter reaches that row.
 * So a point at row Y may be set up any time before the counter reaches Y+lead
 * (lead=24, 16 lines of margin) and is fresh once the counter reaches Y+lag
 * (lag=48). v0.2 schedules GREEDILY: after each read it picks the lowest
 * unsampled row of the current column that can still be set up in this refresh,
 * so rows are ~28 lines apart and a scheduling delay only skips rows instead of
 * costing a whole refresh (v0.1 used a fixed 48-line phase grid).
 * A setup that finishes too late, or a refresh that wraps, is handled by waiting
 * for a full pass after the setup before reading.
 *
 * One capture per insmod. Parameters at insmod time; the capture runs in a
 * kernel thread (insmod returns at once); poll parameters/status; the result is
 * written as raw RGB12 (3 x u16 little-endian per pixel, row-major) to
 * /data/local/tests/x90shot/shot-<tag>.bin. rmmod waits for the thread.
 *
 * Touches ONLY the pixel-monitor control/position words (+0xa4/+0xa8 of each
 * bank); both are restored and read back at the end. No PQ, panel, clock, DMA,
 * capture, cursor or TEE operation.
 *
 * Geometry (native 3840x2160): left bank local X = X for X < 1920; right bank
 * local X = X - 1888 for X >= 1920 (right branch starts 32 px before the seam).
 * pair=0: arbitrary rectangle, columns split by bank, banks driven in parallel.
 * pair=1: rectangle in the left half; captures it AND the same rectangle +1920
 *         (output = left image | right image side by side). Whole-screen tiling.
 */
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/io.h>
#include <linux/delay.h>
#include <linux/timekeeping.h>
#include <linux/kthread.h>
#include <linux/vmalloc.h>
#include <linux/fs.h>
#include <linux/uaccess.h>
#include <linux/completion.h>
#include <linux/err.h>
#include <linux/sched.h>
#include <linux/string.h>
MODULE_LICENSE("GPL");

extern void __iomem *tv_sre_reg_base;
extern void __iomem *tv_reg_base;

static int permit, x0, y0, w = 64, h = 64, step = 1, pair, tag, lead = 24, lag = 48, limit_s = 1200;
module_param(permit, int, 0);
module_param(x0, int, 0);
module_param(y0, int, 0);
module_param(w, int, 0);
module_param(h, int, 0);
module_param(step, int, 0);
module_param(pair, int, 0);
module_param(tag, int, 0);
module_param(lead, int, 0);
module_param(lag, int, 0);
module_param(limit_s, int, 0);
static int abort_req;
module_param_named(abort, abort_req, int, 0644);

#define MAXCOL 3840
#define MAXROWW 34 /* 34*64 >= 2160 rows */
static const unsigned int banks[2] = {0x6900, 0xa900};
static unsigned short colx[MAXCOL];
static short lcol[MAXCOL], rcol[MAXCOL];
static unsigned int ow, oh, nl, nr, npairs, nw;
static u64 donebm[MAXROWW];
static unsigned short *buf;
static size_t buflen;
static unsigned int controls[2], positions[2], restored, points, total, wraps, lateset;
static volatile int state; /* 0 idle 1 running 2 done 3 error */
static int result;
static u64 t_start, t_end, deadline;
static struct completion done;
static int started;

static u64 now_ns(void)
{
	struct timespec64 t;
	ktime_get_raw_ts64(&t);
	return (u64)t.tv_sec * 1000000000ull + t.tv_nsec;
}

/* Scan-line counter with the v9 coherence filter (three consecutive coherent reads). */
static unsigned int line(void)
{
	unsigned int a, b, c, n;
	static unsigned int last;
	for (n = 0; n < 32; n++) {
		a = readl(tv_reg_base + 0x5e380) & 65535;
		b = readl(tv_reg_base + 0x5e380) & 65535;
		c = readl(tv_reg_base + 0x5e380) & 65535;
		if (b >= a && b <= a + 1 && c >= b && c <= b + 1) {
			last = c;
			return c;
		}
	}
	return last;
}

static int expired(void)
{
	return now_ns() > deadline || abort_req;
}

/* Wait for the counter to wrap (the next refresh begins). */
static int wait_wrap(void)
{
	unsigned int v = line(), previous;
	do {
		if (expired())
			return -ETIMEDOUT;
		previous = v;
		usleep_range(40, 80);
		v = line();
	} while (v >= previous);
	wraps++;
	return 0;
}

/* Wait until the counter reaches target; a wrap below 'since' also means a full
 * pass (which contains target) has happened since the setup. */
static int wait_line(unsigned int target, unsigned int since)
{
	unsigned int v = line();
	while (v < target && v >= since) {
		if (expired())
			return -ETIMEDOUT;
		/* Short waits spin: on this SoC a 20-40 us sleep wakes ~150 us late
		 * (~40 lines), which v0.2-r1 measured as 33 points per refresh. Only
		 * long waits (> ~300 lines, 1.1 ms) sleep. Every refresh still sleeps
		 * in wait_wrap, so the thread never monopolises a CPU for long. */
		if (target > v + 300)
			usleep_range(200, 300);
		else
			cpu_relax();
		v = line();
	}
	if (v < since)
		wraps++;
	return 0;
}

static void setpos(int i, unsigned int lx, unsigned int y)
{
	void __iomem *b = tv_sre_reg_base + banks[i];
	writel(controls[i] & ~1u, b + 0xa4);
	writel(lx | (y << 16), b + 0xa8);
	writel(controls[i] | 1u, b + 0xa4);
	readl(b + 0xa4); /* flush posted writes before watching the counter */
}

static void readpix(int i, unsigned short *dst)
{
	void __iomem *b = tv_sre_reg_base + banks[i];
	unsigned int v = readl(b + 0xb4);
	dst[0] = v & 4095;
	dst[1] = (v >> 16) & 4095;
	dst[2] = readl(b + 0xb8) & 4095;
}

static void restore_monitors(void)
{
	int i;
	restored = 1;
	for (i = 0; i < 2; i++) {
		void __iomem *b = tv_sre_reg_base + banks[i];
		writel(controls[i] & ~1u, b + 0xa4);
		writel(positions[i], b + 0xa8);
		writel(controls[i], b + 0xa4);
		restored &= readl(b + 0xa4) == controls[i];
		restored &= readl(b + 0xa8) == positions[i];
	}
}

/* Lowest unsampled row index >= r0 in the current column, or -1. */
static int next_undone(int r0)
{
	unsigned int wi;
	if (r0 < 0)
		r0 = 0;
	if ((unsigned int)r0 >= oh)
		return -1;
	for (wi = r0 >> 6; wi < nw; wi++) {
		u64 m = ~donebm[wi];
		if (wi == (unsigned int)(r0 >> 6))
			m &= ~0ull << (r0 & 63);
		if (wi == nw - 1 && (oh & 63))
			m &= (1ull << (oh & 63)) - 1;
		if (m)
			return (int)(wi << 6) + __builtin_ctzll(m);
	}
	return -1;
}

static int write_file(void)
{
	char path[80];
	struct file *f;
	loff_t pos = 0;
	size_t off = 0;
	ssize_t r;
	/* Kernel threads inherit KERNEL_DS from kthreadd, so vfs_write accepts a
	 * kernel buffer without set_fs() (whose atomic flag op is not exported on
	 * this firmware). Refuse rather than guess if that ever is not the case. */
	if (!segment_eq(get_fs(), KERNEL_DS))
		return -EFAULT;
	snprintf(path, sizeof(path), "/data/local/tests/x90shot/shot-%d.bin", tag);
	f = filp_open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
	if (IS_ERR(f))
		return PTR_ERR(f);
	while (off < buflen) {
		size_t n = buflen - off;
		if (n > (1u << 20))
			n = 1u << 20;
		r = vfs_write(f, (const char __user *)((char *)buf + off), n, &pos);
		if (r <= 0)
			break;
		off += r;
	}
	vfs_fsync(f, 0);
	filp_close(f, NULL);
	return off == buflen ? 0 : -EIO;
}

static int capture_thread(void *unused)
{
	unsigned int k;
	int rc = 0;
	nw = (oh + 63) >> 6;
	t_start = now_ns();
	deadline = t_start + (u64)limit_s * 1000000000ull;
	for (k = 0; k < npairs && !rc; k++) {
		int lc = k < nl ? lcol[k] : -1, rc2 = k < nr ? rcol[k] : -1;
		unsigned int remaining = oh;
		memset(donebm, 0, sizeof(donebm));
		while (remaining) {
			unsigned int v, y, s, miny;
			int r;
			if (expired()) {
				rc = -ETIMEDOUT;
				break;
			}
			v = line();
			/* setup must complete before the counter reaches y + lead */
			miny = v + 4 > (unsigned int)lead ? v + 4 - lead : 0;
			r = next_undone(miny <= (unsigned int)y0 ? 0 : (int)((miny - y0 + step - 1) / step));
			if (r < 0) {
				rc = wait_wrap();
				if (rc)
					break;
				continue;
			}
			y = y0 + r * step;
			if (lc >= 0)
				setpos(0, colx[lc], y);
			if (rc2 >= 0)
				setpos(1, colx[rc2] - 1888, y);
			s = line();
			if (s >= y + lead) {
				/* preempted during setup: this pass may have latched the old
				 * position; use the next full pass. */
				lateset++;
				rc = wait_wrap();
				if (!rc)
					rc = wait_line(y + lag, 0);
			} else {
				rc = wait_line(y + lag, s);
			}
			if (rc)
				break;
			if (lc >= 0)
				readpix(0, buf + ((size_t)r * ow + lc) * 3);
			if (rc2 >= 0)
				readpix(1, buf + ((size_t)r * ow + rc2) * 3);
			donebm[r >> 6] |= 1ull << (r & 63);
			remaining--;
			points++;
		}
	}
	restore_monitors();
	t_end = now_ns();
	if (!rc)
		rc = write_file();
	result = rc;
	state = rc ? 3 : 2;
	printk(KERN_INFO "X90SHOT tag=%d done rc=%d points=%u/%u wraps=%u lateset=%u restored=%u ms=%llu single_frame=0\n",
	       tag, rc, points, total, wraps, lateset, restored, (unsigned long long)((t_end - t_start) / 1000000));
	complete_and_exit(&done, 0);
}

static int status_get(char *b, const struct kernel_param *kp)
{
	static const char *names[] = {"idle", "running", "done", "error"};
	u64 el = (state >= 2 ? t_end : now_ns()) - t_start;
	(void)kp;
	return snprintf(b, PAGE_SIZE,
		"state=%s points=%u total=%u ow=%u oh=%u x0=%d y0=%d w=%d h=%d step=%d pair=%d lead=%d lag=%d "
		"wraps=%u lateset=%u elapsed_ms=%llu restored=%u result=%d tag=%d file=/data/local/tests/x90shot/shot-%d.bin "
		"version=0.2 single_frame=false\n",
		names[state & 3], points, total, ow, oh, x0, y0, w, h, step, pair, lead, lag, wraps, lateset,
		(unsigned long long)(t_start ? el / 1000000 : 0), restored, result, tag, tag);
}
static const struct kernel_param_ops status_ops = {.get = status_get};
module_param_cb(status, &status_ops, NULL, 0444);

static int __init s_init(void)
{
	unsigned int i, wo;
	struct task_struct *t;
	if (permit != 1 || !tv_sre_reg_base || !tv_reg_base)
		return -EPERM;
	if (step < 1 || step > 64 || lead < 8 || lead > 36 || lag < lead + 8 || lag > 96 ||
	    limit_s < 10 || limit_s > 3600 || tag < 0)
		return -EINVAL;
	if (x0 < 0 || y0 < 0 || w < 1 || h < 1 || y0 + h > 2160)
		return -EINVAL;
	if (pair ? (x0 + w > 1920) : (x0 + w > 3840))
		return -EINVAL;
	wo = (w + step - 1) / step;
	oh = (h + step - 1) / step;
	ow = pair ? 2 * wo : wo;
	if (ow > MAXCOL || oh > MAXROWW * 64)
		return -EINVAL;
	nl = nr = 0;
	for (i = 0; i < ow; i++) {
		unsigned int x = pair ? (x0 + (i % wo) * step + (i >= wo ? 1920 : 0)) : (x0 + i * step);
		colx[i] = x;
		if (x < 1920)
			lcol[nl++] = i;
		else
			rcol[nr++] = i;
	}
	npairs = nl > nr ? nl : nr;
	total = npairs * oh;
	buflen = (size_t)ow * oh * 6;
	if (buflen > (64u << 20))
		return -E2BIG;
	for (i = 0; i < 2; i++) {
		controls[i] = readl(tv_sre_reg_base + banks[i] + 0xa4);
		positions[i] = readl(tv_sre_reg_base + banks[i] + 0xa8);
		if (controls[i] & 1)
			return -EBUSY; /* another monitor user (e.g. latency rig) is active */
	}
	buf = vzalloc(buflen);
	if (!buf)
		return -ENOMEM;
	init_completion(&done);
	state = 1;
	t = kthread_create(capture_thread, NULL, "x90shot");
	if (IS_ERR(t)) {
		vfree(buf);
		buf = NULL;
		state = 0;
		return PTR_ERR(t);
	}
	started = 1;
	wake_up_process(t);
	printk(KERN_INFO "X90SHOT tag=%d start v0.2 x0=%d y0=%d w=%d h=%d step=%d pair=%d ow=%u oh=%u total=%u lead=%d lag=%d\n",
	       tag, x0, y0, w, h, step, pair, ow, oh, total, lead, lag);
	return 0;
}

static void __exit s_exit(void)
{
	if (started) {
		abort_req = 1;
		while (!READ_ONCE(done.done))
			msleep(5);
	}
	if (buf)
		vfree(buf);
}

module_init(s_init);
module_exit(s_exit);
