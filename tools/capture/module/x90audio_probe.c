/* Exact driver, read-only DSP upload0 ownership check. No DSP writes.
 * Returns-EAGAIN intentionally so module never remains loaded.
 */
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/errno.h>
MODULE_LICENSE("GPL");
static int text_lo,text_hi,probe_tag;
module_param(text_lo,int,0);module_param(text_hi,int,0);
module_param(probe_tag,int,0);
static int __init audio_init(void){
    unsigned long text=((unsigned long)(unsigned int)text_hi<<32)|(unsigned int)text_lo;
    volatile unsigned int *code=(volatile unsigned int*)(text+0x359d44);
    unsigned int (*read_reg)(int);
    unsigned int enable,channels;
    if(text<0xffffff8000000000ul || (text&3))return -EINVAL;
    if(code[0]!=0xa9be7bfd || code[1]!=0xa9014ff4 || code[2]!=0x910003fd)return -EINVAL;
    read_reg=(unsigned int(*)(int))(text+0x359d44);
    enable=read_reg(0x164);channels=read_reg(0x163);
    printk(KERN_INFO "X90AUDIO_UPLOAD0 enabled=%u channels=%u blocks=%u blockbytes=%u probe_tag=%d\n",
        enable,channels,read_reg(0x161),read_reg(0x162),probe_tag);
    return -EAGAIN;
}
static void __exit audio_exit(void){}
module_init(audio_init);module_exit(audio_exit);
