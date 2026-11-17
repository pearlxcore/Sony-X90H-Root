/* x90cust - MTAL drvcust ioctl sender.
 * SET: ioctl(fd, 0x40202112, {u32 id; u64 ptr; u32 len; u32 arg3})  (0x20 bytes)
 * GET: ioctl(fd, 0xC0182113, {u32 id; u64 buf; u64 lenptr})        (0x18 bytes)
 * Usage:
 *   x90cust get <hexid> <declen>
 *   x90cust set <hexid> <declen> <hexdata> [hexarg3]
 */
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <stdlib.h>

#define MTAL_CUST_SET 0x40202112u
#define MTAL_CUST_GET 0xC0182113u

struct set_args {
    unsigned int id;         /* 0x00 */
    unsigned int pad0;       /* 0x04 */
    unsigned long long ptr;  /* 0x08 */
    unsigned int len;        /* 0x10 */
    unsigned int pad1;       /* 0x14 */
    unsigned int arg3;       /* 0x18 */
    unsigned int pad2;       /* 0x1c */
};

struct get_args {
    unsigned int id;             /* 0x00 */
    unsigned int pad0;           /* 0x04 */
    unsigned long long buf;      /* 0x08 */
    unsigned long long lenptr;   /* 0x10 */
};

static int hexval(int c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static void hexdump(const unsigned char *p, unsigned int n)
{
    unsigned int i;
    for (i = 0; i < n; i++) {
        printf("%02x", p[i]);
        if ((i % 32) == 31 || i == n - 1) printf("\n");
    }
}

int main(int argc, char **argv)
{
    int fd;
    if (argc < 2) { fprintf(stderr, "usage: %s get <hexid> <declen> | set <hexid> <declen> <hexdata> [hexarg3]\n", argv[0]); return 2; }

    fd = open("/dev/mtal", O_RDWR);
    if (fd < 0) { fprintf(stderr, "open /dev/mtal: %s\n", strerror(errno)); return 1; }

    if (!strcmp(argv[1], "get")) {
        if (argc < 4) { fprintf(stderr, "get needs <hexid> <declen>\n"); return 2; }
        unsigned int id = (unsigned int)strtoul(argv[2], 0, 16);
        unsigned int len = (unsigned int)strtoul(argv[3], 0, 10);
        unsigned char *buf = calloc(len ? len : 1, 1);
        struct get_args a;
        int r;
        if (!buf) return 1;
        memset(&a, 0, sizeof a);
        a.id = id;
        a.buf = (unsigned long long)(unsigned long)buf;
        a.lenptr = (unsigned long long)(unsigned long)&len;
        errno = 0;
        r = ioctl(fd, MTAL_CUST_GET, &a);
        printf("get id=0x%x ret=%d errno=%d len=%u\n", id, r, errno, len);
        if (r == 0) hexdump(buf, len);
        free(buf);
        return r < 0 ? 1 : 0;
    } else if (!strcmp(argv[1], "set")) {
        if (argc < 5) { fprintf(stderr, "set needs <hexid> <declen> <hexdata> [hexarg3]\n"); return 2; }
        unsigned int id = (unsigned int)strtoul(argv[2], 0, 16);
        unsigned int len = (unsigned int)strtoul(argv[3], 0, 10);
        unsigned int arg3 = (argc > 5) ? (unsigned int)strtoul(argv[5], 0, 16) : 0u;
        const char *hex = argv[4];
        unsigned char *buf = malloc(len ? len : 1);
        struct set_args a;
        unsigned int i;
        int r;
        if (!buf) return 1;
        if ((int)strlen(hex) < (int)(2 * len)) { fprintf(stderr, "hex too short\n"); free(buf); return 2; }
        for (i = 0; i < len; i++) {
            int h = hexval(hex[2 * i]), l = hexval(hex[2 * i + 1]);
            if (h < 0 || l < 0) { fprintf(stderr, "bad hex at %u\n", i); free(buf); return 2; }
            buf[i] = (unsigned char)((h << 4) | l);
        }
        memset(&a, 0, sizeof a);
        a.id = id;
        a.ptr = (unsigned long long)(unsigned long)buf;
        a.len = len;
        a.arg3 = arg3;
        errno = 0;
        r = ioctl(fd, MTAL_CUST_SET, &a);
        printf("set id=0x%x len=%u arg3=0x%x ret=%d errno=%d\n", id, len, arg3, r, errno);
        free(buf);
        return r < 0 ? 1 : 0;
    }
    fprintf(stderr, "unknown mode %s\n", argv[1]);
    close(fd);
    return 2;
}
