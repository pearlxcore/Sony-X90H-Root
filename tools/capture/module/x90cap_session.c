/* Exact-firmware RAM-only capture cleanup helper. No firmware/storage/TEE API.
 * Snapshot idle software state, then call the shipped VDPStop only AFTER
 * userspace has stopped DMA and disconnected its completion subscriber.
 * This is a research helper, not a general kernel-memory interface.
 */
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/errno.h>
#include <linux/delay.h>
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("X90 bounded capture-session cleanup");
static int bss_lo,bss_hi,text_lo,text_hi,source=3;
module_param(bss_lo,int,0);module_param(bss_hi,int,0);
module_param(text_lo,int,0);module_param(text_hi,int,0);
module_param(source,int,0);
static volatile unsigned char *p;
static void (*stop_vdp)(void);
static unsigned int saved_cache[12],saved_vrm;
static unsigned int saved_capture[5],saved_drop[3];
static unsigned long saved_callback;
static unsigned char saved_init,saved_running,saved_started,saved_vss,saved_mux[2];
static int ready;
#define U32(offset) (*(volatile unsigned int*)(p+(offset)))
static void snapshot(const char *phase){
    printk(KERN_INFO "X90CAP_SESSION %s initialized=%u running=%u started=%u vss=%u vrm=%08x source=%u module=%u field=%u width=%u height=%u mux=%u osd=%u\n",
        phase,p[0x4bfbfe],p[0x4bfbff],p[0x15fda0],p[0x160b44],U32(0x16651c),
        U32(0x166894),U32(0x16689c),U32(0x1668a0),U32(0x1668a4),U32(0x1668a8),
        p[0x1667e0],p[0x1667e1]);
}
static int cleanup_capture(void){
    int i;
    if(!ready)return -ENODEV;
    snapshot("PRE_CLEANUP");
    if(p[0x4bfbff] || p[0x15fda0])return -EBUSY;
    if(!p[0x4bfbfe] && !(U32(0x16651c)&2)){
        printk(KERN_INFO "X90CAP_SESSION ALREADY_IDLE no_writes=1\n");return 0;
    }
    if(p[0x4bfbfe]!=1 || U32(0x166894)!=(unsigned int)source ||
       !(U32(0x16651c)&2) || U32(0x16689c)!=2 ||
       U32(0x1668a4)!=720 || U32(0x1668a8)!=480)return -EINVAL;
    /* Stock function takes the vendor semaphore, releases VRM app2,
     * disables ready mode/scaler/VENC, resets input9 and disables mon-out.
     */
    stop_vdp();
    msleep(50);
    if(U32(0x16651c)&2)return -EBUSY;
    for(i=0;i<12;i++)U32(0x166890+i*4)=saved_cache[i];
    for(i=0;i<3;i++)U32(0x160b50+i*4)=saved_drop[i];
    *(volatile unsigned long*)(p+0x15fdb8)=saved_callback;
    for(i=0;i<5;i++)U32(0x4bfbec+i*4)=saved_capture[i];
    p[0x1667e0]=saved_mux[0];p[0x1667e1]=saved_mux[1];
    p[0x15fda0]=saved_started;p[0x160b44]=saved_vss;
    p[0x4bfbff]=saved_running;p[0x4bfbfe]=saved_init;
    snapshot("RESTORED");
    for(i=0;i<12;i++)if(U32(0x166890+i*4)!=saved_cache[i])return -EIO;
    for(i=0;i<5;i++)if(U32(0x4bfbec+i*4)!=saved_capture[i])return -EIO;
    printk(KERN_INFO "X90CAP_SESSION SAVED_CAPTURE_AND_CACHE_MATCH\n");
    if(U32(0x16651c)!=saved_vrm)return -EAGAIN; /* Do not overwrite another app's flags. */
    return 0;
}
static int command_set(const char *v,const struct kernel_param *kp){
    int action,rc;
    struct kernel_param local=*kp;
    local.arg=&action;
    rc=param_ops_int.set(v,&local);
    if(rc)return rc;
    if(action==1)return cleanup_capture();
    if(action==2&&ready){snapshot("CHECK");return 0;}
    return -EINVAL;
}
static const struct kernel_param_ops command_ops={.set=command_set};
module_param_cb(command,&command_ops,NULL,0200);
static int __init session_init(void){
    unsigned long b=((unsigned long)(unsigned int)bss_hi<<32)|(unsigned int)bss_lo;
    unsigned long t=((unsigned long)(unsigned int)text_hi<<32)|(unsigned int)text_lo;
    volatile unsigned int *code;
    int i;
    if(b<0xffffff8000000000ul || t<0xffffff8000000000ul || (b&7) || (t&3) ||
       (source!=3&&source!=5))return -EINVAL;
    p=(volatile unsigned char*)b;
    code=(volatile unsigned int*)(t+0x260b00);
    if(code[0]!=0xa9be7bfd || code[1]!=0xf9000bf3 || code[2]!=0x910003fd)return -EINVAL;
    if(p[0x4bfbfe] || p[0x4bfbff] || p[0x15fda0] || (U32(0x16651c)&2) ||
       U32(0x166894)!=9 || U32(0x16689c)!=2)return -EBUSY;
    saved_vrm=U32(0x16651c);
    for(i=0;i<12;i++)saved_cache[i]=U32(0x166890+i*4);
    for(i=0;i<3;i++)saved_drop[i]=U32(0x160b50+i*4);
    for(i=0;i<5;i++)saved_capture[i]=U32(0x4bfbec+i*4);
    saved_callback=*(volatile unsigned long*)(p+0x15fdb8);
    saved_init=p[0x4bfbfe];saved_running=p[0x4bfbff];saved_started=p[0x15fda0];saved_vss=p[0x160b44];
    saved_mux[0]=p[0x1667e0];saved_mux[1]=p[0x1667e1];
    stop_vdp=(void(*)(void))(t+0x260b00);
    ready=1;snapshot("BASELINE");return 0;
}
static void __exit session_exit(void){
    snapshot("UNLOAD");
    if(p[0x4bfbfe]!=saved_init || p[0x4bfbff]!=saved_running || (U32(0x16651c)&2))
        printk(KERN_ERR "X90CAP_SESSION ERROR_UNLOAD_BEFORE_CLEANUP\n");
    ready=0;
}
module_init(session_init);module_exit(session_exit);
