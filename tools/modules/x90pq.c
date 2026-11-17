/* x90pq.ko v3 - X90 picture-setting store instrument (CRC-safe build).
 *
 * Symbols used (all with ground-truth CRCs from LOADED device modules):
 *   module_layout, kallsyms_lookup_name, param_ops_uint, printk
 *
 * Interface (sysfs, root only):
 *   /sys/module/x90pq/parameters/b00..b27   uint fields of the 0x70-byte struct
 *   /sys/module/x90pq/parameters/action     write 1=set(from b*) 2=restore 3=respin 4=dump
 *
 * On insmod: resolves BEPQ_AP_Get/Set_Pic_Setting via kallsyms_lookup_name,
 * snapshots the store, prints hex to dmesg.
 */
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/kallsyms.h>
#include <linux/moduleparam.h>

#define PIC_LEN  0x70
#define NWORDS   (PIC_LEN / 4)

typedef void (*pic_fn)(unsigned int *);

static pic_fn pq_get, pq_set;
static unsigned int cur[NWORDS];
static unsigned int snap[NWORDS];
static int snap_valid;
static unsigned int op_count;
static unsigned int action_val;

/* 28 uint parameters b00..b27 */
#define DECL(n) static unsigned int b##n;
DECL(0)  DECL(1)  DECL(2)  DECL(3)  DECL(4)  DECL(5)  DECL(6)  DECL(7)
DECL(8)  DECL(9)  DECL(10) DECL(11) DECL(12) DECL(13) DECL(14) DECL(15)
DECL(16) DECL(17) DECL(18) DECL(19) DECL(20) DECL(21) DECL(22) DECL(23)
DECL(24) DECL(25) DECL(26) DECL(27)

#define MP(n) module_param(b##n, uint, 0644);
MP(0)  MP(1)  MP(2)  MP(3)  MP(4)  MP(5)  MP(6)  MP(7)
MP(8)  MP(9)  MP(10) MP(11) MP(12) MP(13) MP(14) MP(15)
MP(16) MP(17) MP(18) MP(19) MP(20) MP(21) MP(22) MP(23)
MP(24) MP(25) MP(26) MP(27)

#define GATHER(n) cur[n] = b##n;
#define SCATTER(n) b##n = cur[n];

static void dump_hex(const char *tag, const unsigned char *p)
{
    char line[2 * PIC_LEN + 2];
    int i, o = 0;
    static const char hx[] = "0123456789abcdef";
    for (i = 0; i < PIC_LEN; i++) {
        line[o++] = hx[p[i] >> 4];
        line[o++] = hx[p[i] & 0xf];
    }
    line[o] = '\0';
    printk(KERN_INFO "x90pq: %-10s %s\n", tag, line);
}

static void do_get(void)
{
    if (pq_get)
        pq_get(cur);
}

static void do_set(unsigned int *w)
{
    if (pq_set)
        pq_set(w);
}

/* action callback: reuses param_ops_uint's own set/get (no new symbols) */
static int act_set(const char *val, const struct kernel_param *kp)
{
    const struct kernel_param_ops *base = &param_ops_uint;
    int r, a, i;

    r = base->set(val, kp);
    if (r)
        return r;
    a = action_val;

    if (a == 1) {                       /* set: gather b* -> Set -> dump */
        GATHER(0)  GATHER(1)  GATHER(2)  GATHER(3)  GATHER(4)  GATHER(5)  GATHER(6)  GATHER(7)
        GATHER(8)  GATHER(9)  GATHER(10) GATHER(11) GATHER(12) GATHER(13) GATHER(14) GATHER(15)
        GATHER(16) GATHER(17) GATHER(18) GATHER(19) GATHER(20) GATHER(21) GATHER(22) GATHER(23)
        GATHER(24) GATHER(25) GATHER(26) GATHER(27)
        do_set(cur);
        do_get();
        dump_hex("after-set", (const unsigned char *)cur);
        op_count++;
        printk(KERN_INFO "x90pq: SET applied (op %u)\n", op_count);
    } else if (a == 2) {                /* restore snapshot */
        if (snap_valid) {
            do_set(snap);
            do_get();
            dump_hex("restored", (const unsigned char *)cur);
            op_count++;
            printk(KERN_INFO "x90pq: RESTORED (op %u)\n", op_count);
        } else {
            printk(KERN_INFO "x90pq: no snapshot\n");
        }
    } else if (a == 3) {                /* re-snapshot */
        do_get();
        for (i = 0; i < NWORDS; i++)
            snap[i] = cur[i];
        snap_valid = 1;
        dump_hex("resnapshot", (const unsigned char *)snap);
        printk(KERN_INFO "x90pq: re-snapshot done\n");
    } else if (a == 4) {                /* dump current */
        do_get();
        dump_hex("cur", (const unsigned char *)cur);
    } else if (a == 5) {                /* scatter current -> b* (export state) */
        do_get();
        SCATTER(0)  SCATTER(1)  SCATTER(2)  SCATTER(3)  SCATTER(4)  SCATTER(5)  SCATTER(6)  SCATTER(7)
        SCATTER(8)  SCATTER(9)  SCATTER(10) SCATTER(11) SCATTER(12) SCATTER(13) SCATTER(14) SCATTER(15)
        SCATTER(16) SCATTER(17) SCATTER(18) SCATTER(19) SCATTER(20) SCATTER(21) SCATTER(22) SCATTER(23)
        SCATTER(24) SCATTER(25) SCATTER(26) SCATTER(27)
        for (i = 0; i < NWORDS; i++)
            printk(KERN_INFO "x90pq: b%02d=%u\n", i, cur[i]);
    } else {
        printk(KERN_INFO "x90pq: action %u ignored\n", a);
    }
    return 0;
}

static const struct kernel_param_ops act_ops = {
    .set = act_set,
    .get = NULL,            /* write-only; kernel auto-clears read bits */
};
module_param_cb(action, &act_ops, &action_val, 0644);

static int __init x90pq_init(void)
{
    int i;

    pq_get = (pic_fn)kallsyms_lookup_name("BEPQ_AP_Get_Pic_Setting");
    pq_set = (pic_fn)kallsyms_lookup_name("BEPQ_AP_Set_Pic_Setting");
    printk(KERN_INFO "x90pq: get=%pS set=%pS\n", pq_get, pq_set);
    if (!pq_get || !pq_set) {
        printk(KERN_ERR "x90pq: BEPQ symbols not found\n");
        return -ENOENT;
    }

    do_get();
    for (i = 0; i < NWORDS; i++)
        snap[i] = cur[i];
    snap_valid = 1;
    dump_hex("snapshot", (const unsigned char *)snap);
    printk(KERN_INFO "x90pq: ready (params b00..b27, action; snapshot taken)\n");
    return 0;
}

static void __exit x90pq_exit(void)
{
    printk(KERN_INFO "x90pq: unloaded (store untouched, ops=%u)\n", op_count);
}

module_init(x90pq_init);
module_exit(x90pq_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("X90 BEPQ pic-setting store instrument v3");
