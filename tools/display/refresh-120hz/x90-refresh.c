#define _FILE_OFFSET_BITS 64
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <time.h>
#include <errno.h>

/* Exact VH1 626241 HWC only; the wrapper also checks its SHA-256.
 * No boot, block device, panel register or vendor filesystem writes.
 * A root-owned helper restores stock instructions on SIGTERM or lost initial
 * verification. Once verified, missed vsync samples never disable120Hz.
 */
#define DIR "/data/local/tests/x90refresh"
#define HELPER "/data/local/tests/x90-refresh"
static volatile sig_atomic_t stopping;
static int memfd = -1;
static uintptr_t addr[2];
static const uint16_t stock[2] = {0x6906, 0x085b};
static const uint16_t patched[2] = {0x2678, 0x461b};

static void stop(int sig) { stopping = sig; }
static double now(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}
static int preserve_path(const char *path) {
    struct stat st;if(lstat(path,&st)){return errno==ENOENT?0:-1;}
    struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);char backup[1024];
    int n=snprintf(backup,sizeof backup,"%s.preserved-%ld-%lld",path,(long)getpid(),(long long)t.tv_sec*1000000000ll+t.tv_nsec);
    if(n<0||(size_t)n>=sizeof backup)return -1;
    if(!lstat(backup,&st)||errno!=ENOENT)return -1;
    return rename(path,backup);
}
static void state(const char *s) {
    if(preserve_path(DIR "/state")){printf("ERROR preserving refresh state\n");return;}
    FILE *f = fopen(DIR "/state", "w");
    if (f) { fprintf(f, "%s\n", s); fclose(f); }
    printf("%s\n", s); fflush(stdout);
}
static int word(int i, uint16_t *v) {
    return pread(memfd, v, 2, addr[i]) == 2 ? 0 : -1;
}
static int change(int i, uint16_t from, uint16_t to) {
    uint16_t v;
    if (word(i, &v) || v != from) return -1;
    if (pwrite(memfd, &to, 2, addr[i]) != 2 || word(i, &v) || v != to) return -1;
    printf("verified %lx %04x -> %04x\n", (unsigned long)addr[i], from, to);
    fflush(stdout); return 0;
}
static int attach(int pid) {
    char path[80], line[1024]; unsigned long start, offset, base = 0;
    snprintf(path, sizeof path, "/proc/%d/maps", pid);
    FILE *f = fopen(path, "r"); if (!f) return -1;
    while (fgets(line, sizeof line, f)) {
        if (strstr(line, "/vendor/lib/hw/hwcomposer.mt5895.so") &&
            sscanf(line, "%lx-%*x %*s %lx", &start, &offset) == 2 && offset == 0) {
            base = start; break;
        }
    }
    fclose(f); if (!base) return -1;
    snprintf(path, sizeof path, "/proc/%d/mem", pid);
    memfd = open(path, O_RDWR); if (memfd < 0) return -1;
    addr[0] = base + 0x17f56; addr[1] = base + 0x1f1a8;
    return 0;
}
static int restore(void) {
    int bad = 0;
    for (int i = 0; i < 2; i++) {
        uint16_t v;
        if (word(i, &v)) { bad = 1; continue; }
        if (v == stock[i]) continue;
        if (v != patched[i] || change(i, patched[i], stock[i])) bad = 1;
    }
    return bad;
}
static int refresh(void) {
    pid_t p = fork();
    if (p == 0) {
        execl("/system/bin/timeout", "timeout", "8", "/system/bin/runcon",
              "u:r:shell:s0", "/system/bin/service", "call", "SurfaceFlinger", "1037", NULL);
        _exit(127);
    }
    if (p < 0) return -1;
    int s; while (waitpid(p, &s, 0) < 0) if (errno != EINTR) return -1;
    return WIFEXITED(s) ? WEXITSTATUS(s) : -1;
}
/* Same real hardware ioctl as HWC, not synthetic vsync. Reject 50/60/100Hz.
 * SIGALRM interrupts an unresponsive driver so rollback can still run. */
static int timing120(int fd) {
    int64_t a = 0, b = 0;
    alarm(2);
    int rc = ioctl(fd, 0x40044620UL, &a);
    if (!rc && !stopping) rc = ioctl(fd, 0x40044620UL, &b);
    alarm(0);
    return !rc && !stopping && b - a > 7800000 && b - a < 8900000;
}
static int daemon_pid(void) {
    int p = 0; FILE *f = fopen(DIR "/pid", "r");
    if (!f) return 0;
    if (fscanf(f, "%d", &p) != 1) p = 0;
    fclose(f); if (p < 2) return 0;
    char path[80], exe[512]; snprintf(path, sizeof path, "/proc/%d/exe", p);
    ssize_t n = readlink(path, exe, sizeof exe - 1);
    if (n < 0) return 0;
    exe[n] = 0;
    return (!strcmp(exe, HELPER) || !strcmp(exe, HELPER " (deleted)")) ? p : 0;
}
int main(int argc, char **argv) {
    setbuf(stdout, NULL);
    if (getuid() != 0 || argc != 3) { puts("ERROR: root and on/off/commit/status PID required"); return 2; }
    mkdir(DIR, 0700); chmod(DIR, 0700);
    struct sigaction sa = {0}; sa.sa_handler = stop;
    sigaction(SIGTERM, &sa, NULL); sigaction(SIGINT, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL); sigaction(SIGALRM, &sa, NULL);
    struct sigaction ignore = {0}; ignore.sa_handler = SIG_IGN;
    sigaction(SIGPIPE, &ignore, NULL);
    int composer = atoi(argv[2]);
    if (composer < 2 || attach(composer)) { puts("ERROR: cannot attach Composer"); return 3; }
    if (!strcmp(argv[1], "status")) {
        uint16_t a = 0, b = 0;
        if (word(0, &a) || word(1, &b)) return 3;
        printf("code=%04x,%04x watchdog=%d\n", a, b, daemon_pid()); return 0;
    }
    if (!strcmp(argv[1], "commit")) {
        if (!daemon_pid()) { puts("ERROR: watchdog missing"); return 4; }
        if(preserve_path(DIR "/committed"))return 4;
        int fd = open(DIR "/committed", O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd < 0) return 4;
        close(fd); puts("120Hz verified; watchdog armed"); return 0;
    }
    if (!strcmp(argv[1], "off")) {
        int p = daemon_pid();
        if (p) {
            kill(p, SIGTERM);
            for (int i = 0; i < 120 && daemon_pid(); i++) usleep(100000);
            if (daemon_pid()) { puts("ERROR: rollback still running"); return 5; }
        }
        int lock = open(DIR "/lock", O_RDWR | O_CREAT, 0600);
        if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB)) return 5;
        if (restore()) { puts("ERROR: unknown instructions; refusing overwrite"); return 6; }
        usleep(100000);
        if (!p && refresh()) { state("ERROR: code restored; display refresh failed"); return 7; }
        state("60Hz restored"); return 0;
    }
    if (strcmp(argv[1], "on")) return 2;
    int lock = open(DIR "/lock", O_RDWR | O_CREAT, 0600);
    if (lock < 0) return 5;
    if (flock(lock, LOCK_EX | LOCK_NB)) {
        if (daemon_pid()) { puts("120Hz watchdog already running"); return 0; }
        puts("ERROR: another refresh operation is running"); return 5;
    }
    uint16_t a = 0, b = 0;
    if (word(0, &a) || word(1, &b) || a != stock[0] || b != stock[1]) {
        puts("ERROR: HWC instructions differ; choose 60Hz to recover first"); return 6;
    }
    int fb = open("/dev/graphics/fb0", O_RDONLY);
    if (fb < 0 || !timing120(fb)) { puts("ERROR: current hardware timing is not 120Hz; stay at60Hz"); return 8; }
    preserve_path(DIR "/committed");
    int pipefd[2]; if (pipe(pipefd)) return 9;
    pid_t child = fork(); if (child < 0) return 9;
    if (child > 0) {
        close(pipefd[1]); close(lock); close(fb); close(memfd);
        char c = 0; int n = read(pipefd[0], &c, 1); close(pipefd[0]);
        return n == 1 && c == 'Y' ? 0 : 10;
    }
    close(pipefd[0]); setsid();
    if(preserve_path(DIR "/watchdog.log"))_exit(10);
    int log = open(DIR "/watchdog.log", O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (log < 0) _exit(10);
    dup2(log, 1); dup2(log, 2); close(log);
    int nullfd = open("/dev/null", O_RDONLY); if (nullfd >= 0) { dup2(nullfd, 0); close(nullfd); }
    if (change(0, stock[0], patched[0]) || change(1, stock[1], patched[1])) {
        restore(); ssize_t sent = write(pipefd[1], "N", 1); (void)sent; _exit(10);
    }
    if(preserve_path(DIR "/pid")){restore();_exit(10);}
    FILE *pf = fopen(DIR "/pid", "w");
    if (!pf) { restore(); ssize_t sent = write(pipefd[1], "N", 1); (void)sent; _exit(10); }
    fprintf(pf, "%d\n", getpid()); fclose(pf);
    state("120Hz awaiting verification");
    if (write(pipefd[1], "Y", 1) != 1) {
        restore(); usleep(100000); refresh(); preserve_path(DIR "/pid"); _exit(10);
    }
    close(pipefd[1]);
    double deadline = now() + 20;
    int committed = 0;
    // Hardware timing was checked before activation. Do not sample it during
    // normal operation: skipped events under load are not a mode change.
    close(fb);
    while (!stopping) {
        usleep(500000);
        if (!committed && access(DIR "/committed", F_OK) == 0) {
            committed = 1; state("120Hz enabled (until60Hz selected or reboot)");
        }
        if (!committed && now() > deadline) { state("Verification timed out; restoring60Hz"); break; }
        uint16_t x, y;
        if (word(0, &x) || word(1, &y)) { state("Composer stopped; stock code reloads on restart"); break; }
        if (x != patched[0] || y != patched[1]) { state("HWC changed; restoring60Hz"); break; }
    }
    int failed = restore();
    usleep(100000);
    refresh();
    state(failed ? "Composer changed; check actual display mode" : "60Hz restored");
    preserve_path(DIR "/committed"); preserve_path(DIR "/pid");
    close(memfd); close(lock);
    return failed;
}
