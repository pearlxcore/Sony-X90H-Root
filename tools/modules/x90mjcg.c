/* x90mjcg: X1 2026-11-16 hybrid-latency probe. Calls the STOCK dtv_driver vDrvMJCGameMode(char on)
 * (.text+0x27823c, prologue checked) on request, so MEMC/MJC can be put in its game (short-buffer) mode
 * while the picture mode (and therefore the deinterlacer/NR route) stays Standard.
 * No register writes of our own. permit=0 -> load/verify only. Write 1/0 to /sys/module/x90mjcg/parameters/set.
 * Restore path: write 0, then let Sony re-run its own config (picture-mode round trip) or reboot. */
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
MODULE_LICENSE("GPL");
static int text_lo,text_hi,permit,last=-1,ready;
module_param(text_lo,int,0);module_param(text_hi,int,0);module_param(permit,int,0);
module_param(last,int,0444);module_param(ready,int,0444);
typedef void (*mjcg_fn)(char);
static mjcg_fn f_mjcg;
static int set_set(const char *v,const struct kernel_param *kp){
 (void)kp;
 if(!ready||permit!=1)return -EPERM;
 if(v[0]!='0'&&v[0]!='1')return -EINVAL;
 f_mjcg(v[0]=='1');last=v[0]-'0';
 printk(KERN_INFO "X90MJCG set %d\n",last);
 return 0;
}
static const struct kernel_param_ops set_ops={.set=set_set};
module_param_cb(set,&set_ops,NULL,0200);
static int __init m_init(void){
 static const u32 pro[4]={0xa9be7bfd,0xa9014ff4,0x910003fd,0x12001c13};
 unsigned long text=((unsigned long)(u32)text_hi<<32)|(u32)text_lo;const u32 *p;int i;
 if(text<0xffffff8000000000ul||(text&3))return -EINVAL;
 f_mjcg=(mjcg_fn)(text+0x27823c);p=(const u32*)f_mjcg;
 for(i=0;i<4;i++)if(p[i]!=pro[i]){printk(KERN_ERR "X90MJCG prologue mismatch %d %08x\n",i,p[i]);return -ENODEV;}
 ready=1;printk(KERN_INFO "X90MJCG ready permit=%d\n",permit);return 0;
}
static void __exit m_exit(void){printk(KERN_INFO "X90MJCG exit last=%d\n",last);}
module_init(m_init);module_exit(m_exit);
