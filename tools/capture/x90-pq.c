/* Firmware-specific MediaTek PQ client. Public vendor functions, no raw ioctls. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <limits.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <errno.h>
#include <time.h>

typedef int (*get_fn)(int,const char*,int*);
typedef int (*set_fn)(int,const char*,int,int);
static get_fn get_value, get_range;
static set_fn set_value;
static int (*source_id)(void);
static int (*get_signal)(int,unsigned char*);
static const char *keys[]={
 "g_video__pic_mode","g_video__sharpness","g_video__reality_creation",
 "g_video__resolution","g_video__motion_flow","g_video__moti_fl_smoothnes",
 "g_video__moti_fl_clearness","g_video__cine_motion","g_video__noise_reduction",
 "g_video__mpeg_noise_reduction","g_video__screen_mode","g_video__screen_auto_wide",
 "g_video__adv.contrast_enhancer","g_video__black_corrector","g_video__xdr",
 "g_video__led_dynamic_ctl","g_video__colour","g_video__contrast","g_video__brightness",
 "g_custom_base__light_sensor"};
#define NKEYS ((int)(sizeof(keys)/sizeof(keys[0])))
#ifndef STATE_DIR
#define STATE_DIR "/data/local/tests"
#endif
#ifndef WRAPPER_LIB
#define WRAPPER_LIB "/vendor/lib/vendor.mediatek_common.tv.mtktvapi@1.0-cwrapper.so"
#endif
#define JOURNAL STATE_DIR "/x90-pq-pending.txt"
#define LOCK STATE_DIR "/x90-pq.lock"
#define CANCEL STATE_DIR "/x90-pq-cancel"
#define DISPLAY_LOCK STATE_DIR "/x90-display-operation"
static int display_lock_owned;
static void cleanup(void){if(display_lock_owned)rmdir(DISPLAY_LOCK);}
static int lookup(const char *key){ for(int i=0;i<NKEYS;i++)if(!strcmp(key,keys[i]))return i;return -1; }
static int mutable(int i){return i==1||i==2||i==3||i==4||i==5||i==6||i==7||i==8||i==9||i==10||i==11;}
/* MtkTvConfigType.ACFG_INPUT_GRP_HDMI4=5. Never use the foreground-dependent -1 bank. */
#define INPUT_GROUP 5
static int query(int i,int *v){*v=INT_MIN;return get_value(INPUT_GROUP,keys[i],v);}
static int foreground_tv(void){
#ifdef HOST_TEST
 return 1;
#else
 FILE*f=popen("runcon u:r:shell:s0 dumpsys activity activities 2>/dev/null | grep mResumedActivity","r");
 if(!f)return 0;char line[1024];int ok=0;
 while(fgets(line,sizeof line,f))if(strstr(line,"com.sony.dtv.tvx/"))ok=1;
 pclose(f);return ok;
#endif
}
static int context(void){int s=source_id();if(s!=5||!foreground_tv()){printf("ERROR HDMI4_TV_FOREGROUND_REQUIRED source=%d\n",s);return 0;}return 1;}
static int signal_ready(void){unsigned char present=0;int rc=get_signal(5,&present);printf("SIGNAL status=%d present=%u\n",rc,present);return rc==0&&present==1;}
static int typed_480i(void){
#ifdef HOST_TEST
 const char*v=getenv("FORMAT_480I");return !v||strcmp(v,"0");
#else
 FILE*f=popen("runcon u:r:sony_platform_app:s0:c512,c768 env -u LD_LIBRARY_PATH CLASSPATH=/data/local/tests/x90-signal.dex:/vendor/framework/com.mediatek.twoworlds.tv.jar timeout 8 app_process /system/bin SignalRead 2>&1","r");
 if(!f)return 0;char line[256];int ok=0;
 while(fgets(line,sizeof line,f)){printf("FORMAT %s",line);if(!strcmp(line,"VERIFIED_480I\n"))ok=1;}
 int status=pclose(f);return status==0&&ok;
#endif
}
static int save(int i,int v){
 int fd=open(JOURNAL ".tmp",O_WRONLY|O_CREAT|O_TRUNC,0600); if(fd<0)return 0;
 char b[200];int n=snprintf(b,sizeof b,"%s %d\n",keys[i],v);
 int ok=write(fd,b,n)==n&&fsync(fd)==0;close(fd);
 return ok&&rename(JOURNAL ".tmp",JOURNAL)==0;
}
static int restore(void){
 FILE*f=fopen(JOURNAL,"r");if(!f){if(errno==ENOENT){puts("NO_PENDING_CHANGE");return 0;}return 2;}
 char key[120];int old;int ok=fscanf(f,"%119s %d",key,&old)==2;fclose(f);
 int i=lookup(key);if(!ok||i<0||!mutable(i)){puts("ERROR INVALID_JOURNAL");return 2;}
 if(!context()){puts("RESTORE_PENDING return_to_HDMI4_then_restore");return 2;}
 int current;
 if(query(i,&current)==0&&current==old){unlink(JOURNAL);puts("RESTORED_ALREADY_ORIGINAL");return 0;}
 if(!signal_ready()||!typed_480i()){puts("RESTORE_PENDING HDMI4_480i_signal_required");return 2;}
 int rc=set_value(INPUT_GROUP,key,old,3),now; int qr=query(i,&now);
 printf("RESTORE key=%s value=%d status=%d query_status=%d actual=%d\n",key,old,rc,qr,now);
 if(rc<0||qr!=0||now!=old){puts("ERROR RESTORE_FAILED journal_retained");return 2;}
 unlink(JOURNAL);puts("RESTORED");return 0;
}
int main(int argc,char**argv){
 setvbuf(stdout,NULL,_IOLBF,0);
 void*h=dlopen(WRAPPER_LIB,RTLD_NOW);
 if(!h){printf("ERROR dlopen %s\n",dlerror());return 2;}
 get_value=(get_fn)dlsym(h,"a_mtktvapi_config_get_value");
 get_range=(get_fn)dlsym(h,"a_mtktvapi_config_get_minmaxvalue");
 set_value=(set_fn)dlsym(h,"a_mtktvapi_config_set_value");
 source_id=(int(*)(void))dlsym(h,"a_mtktvapi_inputsource_get_currentsourceid");
 get_signal=(int(*)(int,unsigned char*))dlsym(h,"a_mtktvapi_inputsource_get_device_signal_bysourceid");
 if(!get_value||!get_range||!set_value||!source_id||!get_signal){puts("ERROR missing_symbol");return 2;}
 const char*op=argc>1?argv[1]:"snapshot";
 if(!strcmp(op,"snapshot")){
   if(!context())return 2;
   printf("SOURCE %d\n",source_id());int failed=0;
   signal_ready();
   if(access(JOURNAL,F_OK)==0)puts("PENDING_CHANGE snapshot_is_not_original");
   for(int i=0;i<NKEYS;i++){int v,r=INT_MIN,rc=query(i,&v),rr=get_range(INPUT_GROUP,keys[i],&r);
    printf("PQ %s status=%d value=%d range_status=%d min=%d max=%d\n",keys[i],rc,v,rr,(short)(r&65535),(unsigned)r>>16);
    if(rc!=0)failed=1;
   }puts(failed?"SNAPSHOT_FAILED":"SNAPSHOT_OK");return failed?2:0;
 }
 if(strcmp(op,"trial")&&strcmp(op,"restore")){puts("ERROR use snapshot|trial key value seconds|restore");return 2;}
 int lock=open(LOCK,O_CREAT|O_RDWR,0600);if(lock<0||flock(lock,LOCK_EX|LOCK_NB)){puts("ERROR BUSY");return 2;}
 if(mkdir(DISPLAY_LOCK,0700)){puts("ERROR DISPLAY_OPERATION_BUSY");return 2;}
 display_lock_owned=1;atexit(cleanup);
 if(!strcmp(op,"restore"))return restore();
 if(argc!=5){puts("ERROR invalid_arguments");return 2;}
 int i=lookup(argv[2]);char*end;long v=strtol(argv[3],&end,10);if(*end||v<0||v>100){puts("ERROR invalid_value");return 2;}
 long seconds=strtol(argv[4],&end,10);if(*end||seconds<5||seconds>120){puts("ERROR seconds_5_to_120");return 2;}
 if(i<0||!mutable(i)){puts("ERROR key_not_writable");return 2;}
 if(access(JOURNAL,F_OK)==0){puts("ERROR PENDING_RESTORE");return 2;}
 unlink(CANCEL);
 if(!context()||!signal_ready()){puts("ERROR ACTIVE_HDMI4_SIGNAL_REQUIRED");return 2;}
 if(!typed_480i()){puts("ERROR VERIFIED_480I_REQUIRED");return 2;}
 int old,range=0,rc=query(i,&old),rr=get_range(INPUT_GROUP,keys[i],&range);
 int min=(short)(range&65535),max=(unsigned)range>>16;
 if(rc!=0||rr!=0||v<min||v>max||min<0||max>100){puts("ERROR query_or_range_failed");return 2;}
 if(v==old){puts("UNCHANGED");return 0;}
 if(!save(i,old)){puts("ERROR backup_failed");return 2;}
 /* Recheck active input after creating the on-device recovery journal. */
 if(!context()||!signal_ready()||!typed_480i())return restore();
 rc=set_value(INPUT_GROUP,keys[i],(int)v,3);int now;int qr=query(i,&now);
 printf("TRIAL key=%s before=%d requested=%ld status=%d query_status=%d actual=%d seconds=%ld\n",keys[i],old,v,rc,qr,now,seconds);
 if(rc<0||qr!=0||now!=v){restore();return 2;}
 /* The device owns the timer; losing ADB or the Toolbox UI cannot cancel it. */
 for(int elapsed=0;elapsed<seconds;elapsed++){
   struct timespec delay={1,0};while(nanosleep(&delay,&delay)&&errno==EINTR){}
   if(access(CANCEL,F_OK)==0)break;
 }
 unlink(CANCEL);
 return restore();
}
