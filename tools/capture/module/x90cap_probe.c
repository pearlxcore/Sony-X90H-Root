/* READ-ONLY exact-firmware capture-state probe. No exported device/API,
 * threads, allocations, hardware calls or writes. Deliberate -EAGAIN means
 * the module is discarded immediately after printing the snapshot.
 */
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/errno.h>
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("X90 read-only capture lifecycle snapshot");
static int bss_lo,bss_hi;
module_param(bss_lo,int,0);
module_param(bss_hi,int,0);
static int __init x90cap_init(void){
    unsigned long base=((unsigned long)(unsigned int)bss_hi<<32)|(unsigned int)bss_lo;
    volatile unsigned char *p=(volatile unsigned char*)base;
    if(base<0xffffff8000000000ul || (base&7))return -EINVAL;
    printk(KERN_INFO "X90CAP_STATE initialized=%u running=%u vrm_flags=%08x\n",
        p[0x4bfbfe],p[0x4bfbff],*(volatile unsigned int*)(p+0x16651c));
    printk(KERN_INFO "X90CAP_STATE source=%u module=%u field=%u width=%u height=%u\n",
        *(volatile unsigned int*)(p+0x166894),*(volatile unsigned int*)(p+0x16689c),
        *(volatile unsigned int*)(p+0x1668a0),*(volatile unsigned int*)(p+0x1668a4),
        *(volatile unsigned int*)(p+0x1668a8));
    return -EAGAIN;
}
static void __exit x90cap_exit(void){}
module_init(x90cap_init);
module_exit(x90cap_exit);
