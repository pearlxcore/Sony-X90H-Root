import android.opengl.*;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Surface;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/** Research-only root/shell SurfaceControl renderer with a one-field-delayed
 * vt_guarded deinterlacer. Field i is drawn when field i+1 arrives, using
 * previous=i-1 and next=i+1. No video/PQ setters. Bounded lifetime and
 * finally-remove own Surface. */
public class X90FieldEngine {
 static native int timingInit(long display,long surface,String path);
 static native long timingNext();
 static native void timingSubmitted(long id,long sequence,long source,long swap);
 static native void timingFinish();
 static native int timingAttach(long surface);
 static native long timingNextOn(long surface);
 static native void timingSubmittedOn(long id,long sequence,long source,long swap,long surface);
 static native boolean timingScheduleOn(long surface,long timestamp);
 static native long timingLatch(long id);
 static native long timingLatchOn(long surface,long id);
 static native long timingAnchor();
 static boolean timing,forceCoalesce,packedDiagnostic;
 static volatile boolean stopping;
 static volatile Throwable readerError;
 static volatile long received,dropped;
 static class Frame { ByteBuffer pixels; long sequence,completed; int parity; boolean stable; FieldHistory.Snapshot history; Frame(int n){pixels=ByteBuffer.allocateDirect(n);history=new FieldHistory.Snapshot(n*2/3);} }
 static final ArrayBlockingQueue<Frame> free=new ArrayBlockingQueue<>(6), latest=new ArrayBlockingQueue<>(1);
 static Socket socket;
 static Class<?> sc,tc;static Object control;static Surface surface;
 static EGLDisplay display=EGL14.EGL_NO_DISPLAY;static EGLContext context=EGL14.EGL_NO_CONTEXT;static EGLSurface window=EGL14.EGL_NO_SURFACE;
 static int width,height,stride,outWidth,outHeight,contentWidth,contentHeight,contentX;
 static int convert,upscale,ytexHandle,uvtexHandle,rgbtex,fbo,vao,presentProg,frameFbo,frameTex;
 static int[] historyTextures;
 static long adaptiveFrames,spatialFrames,vtFrames,cutFrames;
 static int diMode,locDi,locMotion,locEdge,locSharp;static float motionThr=12f,edgeGain=1f,sharpen=0f;
 /* 2026-11-17 opt-in output transform (fastup path): zoom + tone (gain/lift/gamma), live from settings.
    Defaults are exact identity so di=0..3 behaviour is unchanged when the keys are absent. */
 static float zoomX=1f,zoomY=1f;static int toneMode;static float toneBright=1f,toneWarm=0f,toneGammaAdj=1f;
 static final float[] toneLift={0f,0f,0f},toneGain={1f,1f,1f},toneGamma={1f,1f,1f};
 /* Sony game_plus night-scene curve measured by x90shot (ENGINE-FASTPATH-TUNING-2026-11-16): Sony=f(engine). */
 static final float[] P_LIFT={-0.0169f,-0.0035f,-0.0188f},P_GAIN={1.0438f,0.9801f,0.8601f},P_GAMMA={0.94f,0.98f,0.94f};
 static float clampf(float v,float lo,float hi){return v<lo?lo:v>hi?hi:v;}
 static int swapMode=1,swapGapMs,sharedMode,frameTarget,rateHint;
static long vsyncAnchor,gridAnchor,lastFidA,lastFidB;static long schedRequests,schedOk,schedFail,latchMiss;static long periodNs=8333333L;
static int[] frameTexs,frameFbos;
static Class<?> sc2;static Object control2;static Surface surface2;static EGLSurface window2=EGL14.EGL_NO_SURFACE;static int layerShow;
static ArrayBlockingQueue<Integer> texReady,texSpare;
static Thread presenterThread,vsyncThread;
static EGLContext presentContext=EGL14.EGL_NO_CONTEXT;static EGLSurface pbuffer=EGL14.EGL_NO_SURFACE;static int presentVao;
static volatile long vsyncNs,vsyncTicks;static int presSlot=-1;static long presRepeats,presNewFrame,presMissed;
static int midProg,midFbo,prevTex,prevFbo;static boolean prevValid;static volatile boolean midProofRequest;static long midFrames;static double midMae;
static double midTakeSum,midBlendSum,midCopySum,midFinishSum;
static int mixProg;static long presMidCount,presRealNew;
static int motionProg,motionFbo,motionTex,motionW,motionH,warpProg;static int[] motionTexs;static int lastRenderSlot=-1;static volatile boolean motionProofRequest;static int pmotionFbo=-1,coarseTex=-1,coarseFbo=-1,refineProg,coarseW,coarseH,medianTex=-1,medianFbo=-1,medianProg;
static int pairTex,pairFbo,pairProg,qw,qh;static boolean[] slotMid;
static long motionFrames;static double motionEstSum;
static final long[] slotSeq=new long[8];static long pairCount,pairBad,pairGapSum,pairGapMin=Long.MAX_VALUE,pairGapMax,pairSkipped;
 static volatile long presents;static volatile double presBlockMax;static volatile double presBlockSum;static volatile long presSkipped;static long swaps,swapFirst,swapLast;static double swap2Sum,swap2Max,draw2Sum,loopSum,preSum,preMax,capSum,cutSum;
 static double pollSum,drawSum,p1Sum,p2Sum;static long iters;
static long chainWaits,chainMiss,chainW0,chainW1;static double chainW0Sum,chainW1Sum,chainWaitMax;
 static long settingsStamp=-1;
 /* 2026-11-15 low-latency option: render each field when it arrives (past-field history only). */
 static final boolean LOW_LATENCY="1".equals(System.getProperty("x90.ll"));
 /* 2026-11-16 render-at-latch: draw on field arrival, present just before the next vsync deadline. */
 static final boolean LATCH="1".equals(System.getProperty("x90.latch"));
 static final long latchMarginNs=Long.getLong("x90.latchM",3L)*1_000_000L;
 static volatile long vsyncPeriodNs=16_666_666L;static long latchNew,latchRepeat,latchSkipPark;
 /* 2026-11-16 split A/B: Sony sideband left, engine right, divider at frac*outWidth. */
 static final float SPLIT=Float.parseFloat(System.getProperty("x90.split","0"));
 static final float SPLITDIV=Float.parseFloat(System.getProperty("x90.splitdiv","2"));
 static int splitPresentProg;
 /* 2026-11-16 split A/B labels: procedural 5x7 text in the present shader; see make_splitlabel.py. */
 static final boolean SPLIT_LABEL=!"0".equals(System.getProperty("x90.splitlabel","1"));
 static final String SPLITL=System.getProperty("x90.splitL","SONY");
 static final String SPLITR=System.getProperty("x90.splitR","X90 CUSTOM");
 static final float LBLDOT=Float.parseFloat(System.getProperty("x90.lbldot","5"));
 static final float LBLMX=24f,LBLMY=24f;
 static final int LBL_MAXLEN=16;
 static final String FONT_CHARS=" ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-";
 static final int[] FONT={0,0,0,0,0,0,0,14,17,17,31,17,17,17,30,17,17,30,17,17,30,14,17,16,16,16,17,14,30,17,17,17,17,17,30,31,16,16,28,16,16,31,31,16,16,28,16,16,16,14,17,16,23,17,17,14,17,17,17,31,17,17,17,14,4,4,4,4,4,14,7,2,2,2,2,18,12,17,18,20,24,20,18,17,16,16,16,16,16,16,31,17,27,21,21,17,17,17,17,17,25,21,19,17,17,14,17,17,17,17,17,14,30,17,17,30,16,16,16,14,17,17,17,21,18,13,30,17,17,30,20,18,17,15,16,16,14,1,1,30,31,4,4,4,4,4,4,17,17,17,17,17,17,14,17,17,17,17,17,10,4,17,17,17,21,21,27,17,17,17,10,4,10,17,17,17,17,10,4,4,4,4,31,1,2,4,8,16,31,14,17,19,21,25,17,14,4,12,4,4,4,4,14,14,17,1,2,4,8,31,31,2,4,2,1,17,14,2,6,10,18,31,2,2,31,16,30,1,1,17,14,14,16,16,30,17,17,14,31,1,2,4,8,8,8,14,17,17,14,17,17,14,14,17,17,15,1,1,14,0,0,0,31,0,0,0};
 static int[] glyphs(String s){int n=Math.min(s.length(),LBL_MAXLEN);int[] g=new int[LBL_MAXLEN];
   for(int i=0;i<n;i++){int idx=FONT_CHARS.indexOf(Character.toUpperCase(s.charAt(i)));g[i]=idx<0?0:idx;}return g;}
 static int glyphLen(String s){return Math.min(s.length(),LBL_MAXLEN);}
 static final String SPLIT_FRAG="#version 310 es\nprecision highp float;\nprecision highp int;\n"
   +"uniform sampler2D src;uniform float splitx;uniform float divw;uniform int lblOn;uniform int outH;\n"
   +"uniform int font[266];uniform int labL[16];uniform int labR[16];uniform int lenL;uniform int lenR;\n" 
   +"uniform float dotp;uniform float mx;uniform float my;out vec4 color;\n"
   +"void main(){float x=gl_FragCoord.x;vec4 base;\n"
   +"if(abs(x-splitx)<=divw){base=vec4(1.0);}else if(x<splitx){base=vec4(0.0);}"
   +"else{base=vec4(texelFetch(src,ivec2(gl_FragCoord.xy),0).rgb,1.0);}\n"
   +"if(lblOn==1){float fyTop=float(outH)-gl_FragCoord.y;\n"
   +"for(int side=0;side<2;side++){float ox=side==0?mx:splitx+mx;int len=side==0?lenL:lenR;\n"
   +"float boxW=float(len)*6.0*dotp;float boxH=7.0*dotp;float lx=x-ox;float ly=fyTop-my;\n"
   +"if(lx>=-2.0*dotp&&lx<boxW+2.0*dotp&&ly>=-2.0*dotp&&ly<boxH+2.0*dotp){bool on=false;\n"
   +"if(lx>=0.0&&lx<boxW&&ly>=0.0&&ly<boxH){int gi=int(lx/(6.0*dotp));float gx=lx-float(gi)*6.0*dotp;\n"
   +"int col=int(gx/dotp);if(col<5){int row=int(ly/dotp);int g=side==0?labL[gi]:labR[gi];\n"
   +"int bits=font[g*7+row];if(((bits>>(4-col))&1)==1)on=true;}}\n"
   +"if(on){base=vec4(1.0);}else if(side==0){base=vec4(0.0,0.0,0.0,0.6);}"
   +"else{base=vec4(texelFetch(src,ivec2(gl_FragCoord.xy),0).rgb*0.35,1.0);}}}}\n"
   +"color=base;}";
 static boolean DIRECT;
 /* 2026-11-15 fastup: prepass (block/cross) + horizontal + vertical upscale; 8 fetches/px instead of 17. */
 /* 2026-11-15 pace: desired present time on a stable 2-vsync grid with hysteresis (single-layer mode). */
 static boolean PACE;static long paceT,paceD=21_000_000L,paceH=1_500_000L,paceP=8_333_333L,paceSlipLate,paceSlipEarly,paceSet;
 static long gridCeil(long t,long anchor,long P){long k=(t-anchor+P-1)/P;if(t<anchor)k=-((anchor-t)/P);return anchor+k*P;}
 static void pacePresent(long sourceNs){long v=vsyncNs;if(v<=0)return;long P=paceP,want=sourceNs+paceD;long target;
  if(paceT==0||want>paceT+6*P||want<paceT-6*P)target=gridCeil(want,v,P);else{target=paceT+2*P;if(want>target+paceH){target+=P;paceSlipLate++;}else if(want<target-P-paceH){target-=P;paceSlipEarly++;}target=v+Math.round((target-v)/(double)P)*P;}
  paceT=target;if(EGLExt.eglPresentationTimeANDROID(display,window,target-P/2))paceSet++;}
 static boolean FASTUP,UPVAL;static int fuPreB,fuPreC,fuH,fuV,fuBmin,fuBmax,fuCmin,fuCmax,fuBlur,fuHtex,fuFboB,fuFboC,fuFboH;
 static int texS(int w,int h,int internal){int[] id={0};GLES30.glGenTextures(1,id,0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,id[0]);GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,1,internal,w,h);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);return id[0];}
 static int fboOf(int... tex)throws IOException{int[] f={0};GLES30.glGenFramebuffers(1,f,0);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,f[0]);int[] bufs=new int[tex.length];for(int i=0;i<tex.length;i++){GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0+i,GLES30.GL_TEXTURE_2D,tex[i],0);bufs[i]=GLES30.GL_COLOR_ATTACHMENT0+i;}GLES30.glDrawBuffers(tex.length,bufs,0);int st=GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);if(st!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw new IOException("fastup FBO "+st);return f[0];}
 static void fuBind(int prog,String name,int unit,int tex){GLES30.glActiveTexture(GLES30.GL_TEXTURE0+unit);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,tex);GLES30.glUniform1i(GLES30.glGetUniformLocation(prog,name),unit);}
 static void fastUpscale(int target){
  GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fuFboB);GLES30.glViewport(0,0,width+1,height*2+1);GLES30.glUseProgram(fuPreB);fuBind(fuPreB,"src",0,rgbtex);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
  if(sharpen>0f){GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fuFboC);GLES30.glViewport(0,0,width,height*2);GLES30.glUseProgram(fuPreC);fuBind(fuPreC,"src",0,rgbtex);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);}
  GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fuFboH);GLES30.glViewport(0,0,contentWidth,height*2);GLES30.glUseProgram(fuH);fuBind(fuH,"src",0,rgbtex);GLES30.glUniform1f(GLES30.glGetUniformLocation(fuH,"outW"),contentWidth);GLES30.glUniform1f(GLES30.glGetUniformLocation(fuH,"zx"),zoomX);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
  GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,target);GLES30.glViewport(0,0,outWidth,outHeight);GLES30.glClearColor(0,0,0,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);GLES30.glViewport(contentX,0,contentWidth,contentHeight);
  GLES30.glUseProgram(fuV);fuBind(fuV,"htex",0,fuHtex);fuBind(fuV,"bmin",1,fuBmin);fuBind(fuV,"bmax",2,fuBmax);fuBind(fuV,"cmin",3,fuCmin);fuBind(fuV,"cmax",4,fuCmax);fuBind(fuV,"blur",5,fuBlur);
  GLES30.glUniform2f(GLES30.glGetUniformLocation(fuV,"outsize"),contentWidth,contentHeight);GLES30.glUniform2f(GLES30.glGetUniformLocation(fuV,"size"),width,height*2);
  GLES30.glUniform1f(GLES30.glGetUniformLocation(fuV,"sharpen"),sharpen);GLES30.glUniform1f(GLES30.glGetUniformLocation(fuV,"cx"),contentX);
  GLES30.glUniform2f(GLES30.glGetUniformLocation(fuV,"zoom"),zoomX,zoomY);GLES30.glUniform3f(GLES30.glGetUniformLocation(fuV,"lift"),toneLift[0],toneLift[1],toneLift[2]);GLES30.glUniform3f(GLES30.glGetUniformLocation(fuV,"gain"),toneGain[0],toneGain[1],toneGain[2]);GLES30.glUniform3f(GLES30.glGetUniformLocation(fuV,"gamma"),toneGamma[0],toneGamma[1],toneGamma[2]);
  GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
 }
 static void validateFastUp(){
  int W=outWidth,H=outHeight;ByteBuffer a=ByteBuffer.allocateDirect(W*H*4),b=ByteBuffer.allocateDirect(W*H*4);
  GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,frameFbo);GLES30.glViewport(0,0,W,H);GLES30.glClearColor(0,0,0,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);GLES30.glViewport(contentX,0,contentWidth,contentHeight);
  GLES30.glUseProgram(upscale);GLES30.glUniform1f(locSharp,sharpen);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,rgbtex);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
  GLES30.glReadPixels(0,0,W,H,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,a);
  fastUpscale(frameFbo);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,frameFbo);GLES30.glReadPixels(0,0,W,H,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,b);
  long n=0,d1=0,d2=0,sum=0;int mx=0;for(int y=0;y<H;y++)for(int x=contentX;x<contentX+contentWidth;x++)for(int c=0;c<3;c++){int i=(y*W+x)*4+c;int d=Math.abs((a.get(i)&255)-(b.get(i)&255));n++;sum+=d;if(d>0)d1++;if(d>1)d2++;if(d>mx)mx=d;}
  System.out.printf(java.util.Locale.US,"ENGINE_FASTUP_VALIDATE samples=%d diff_gt0=%d diff_gt1=%d max=%d mean=%.5f sharpen=%.2f%n",n,d1,d2,mx,sum/(double)n,sharpen);
 }
 static boolean GPUPROF;static int[] gq=new int[4];static int gqSet=-1;static double gDi,gUp;static long gN;
 static void gpBegin(int i){if(GPUPROF)GLES30.glBeginQuery(0x88BF,gq[i]);}
 static void gpEnd(){if(GPUPROF)GLES30.glEndQuery(0x88BF);}
 static boolean[] gqUsed=new boolean[2];
 static void gpCollect(int set){if(!GPUPROF||set<0||!gqUsed[set])return;int[] r={0},a={0};GLES30.glGetQueryObjectuiv(gq[set*2],GLES30.GL_QUERY_RESULT_AVAILABLE,a,0);if(a[0]==0)return;GLES30.glGetQueryObjectuiv(gq[set*2],GLES30.GL_QUERY_RESULT,r,0);double d=(r[0]&0xffffffffL)/1e6;GLES30.glGetQueryObjectuiv(gq[set*2+1],GLES30.GL_QUERY_RESULT,r,0);double u=(r[0]&0xffffffffL)/1e6;gDi+=d;gUp+=u;gN++;}
 static void readSwapEarly(){
  try{String sv=text("/data/local/tests/x90-engine-swap").trim();int comma=sv.indexOf(',');int sm,gap=0;
   if(comma>=0){sm=Integer.parseInt(sv.substring(0,comma));gap=Integer.parseInt(sv.substring(comma+1));}else sm=Integer.parseInt(sv);
   swapMode=Math.max(1,Math.min(37,sm));swapGapMs=Math.max(0,Math.min(50,gap));
  }catch(Exception ignored){}
  System.out.println("ENGINE_SWAP_EARLY swap="+swapMode+" gap="+swapGapMs);
 }
 static String sessionDir;static volatile boolean captureRequest;static volatile boolean presentCaptureRequest;
 static void loadSettings(){
  File f=new File("/data/local/tests/x90-engine-settings");long stamp=f.lastModified();
  if(stamp==settingsStamp)return;
  System.out.println("ENGINE_SETTINGS_STAMP "+settingsStamp+"->"+stamp);
  settingsStamp=stamp;
  int dm=diMode;float mt=motionThr,eg=edgeGain,sh=sharpen;int sm=swapMode;
  int tn=toneMode;float zp=zoomX*100f,zpy=zoomY*100f,br=toneBright,wm=toneWarm,ga=toneGammaAdj;boolean zpySet=false;
  if(f.isFile())try{
   for(String line:java.nio.file.Files.readAllLines(f.toPath())){
    line=line.trim();if(line.isEmpty()||line.startsWith("#"))continue;
    int eq=line.indexOf('=');if(eq<0)continue;
    String k=line.substring(0,eq).trim(),v=line.substring(eq+1).trim();
    if(k.equals("di"))dm=Math.max(0,Math.min(3,Integer.parseInt(v)));
    else if(k.equals("motion"))mt=Math.max(4,Math.min(40,Float.parseFloat(v)));
    else if(k.equals("edge"))eg=Math.max(0,Math.min(100,Float.parseFloat(v)))/100f;
    else if(k.equals("sharp"))sh=Math.max(0,Math.min(100,Float.parseFloat(v)))/100f;
    else if(k.equals("swap"))sm=Math.max(1,Math.min(37,Integer.parseInt(v)));
    else if(k.equals("zoom"))zp=clampf(Float.parseFloat(v),90f,120f);
    else if(k.equals("zoomy")){zpy=clampf(Float.parseFloat(v),90f,120f);zpySet=true;}
    else if(k.equals("tone"))tn=Math.max(0,Math.min(1,Integer.parseInt(v)));
    else if(k.equals("bright"))br=clampf(Float.parseFloat(v),0.5f,1.5f);
    else if(k.equals("warm"))wm=clampf(Float.parseFloat(v),-0.30f,0.30f);
    else if(k.equals("gamma"))ga=clampf(Float.parseFloat(v),0.5f,1.5f);
   }
  }catch(Exception x){System.out.println("ENGINE_SETTINGS_IGNORED "+x);}
  if(!zpySet)zpy=zp;
  if(dm!=diMode||mt!=motionThr||eg!=edgeGain||sh!=sharpen||sm!=swapMode){
   diMode=dm;motionThr=mt;edgeGain=eg;sharpen=sh;swapMode=sm;
   System.out.println("ENGINE_SETTINGS di="+diMode+" motion="+motionThr+" edge="+edgeGain+" sharp="+sharpen+" swap="+swapMode);
  }
  if(tn!=toneMode||zp!=zoomX*100f||zpy!=zoomY*100f||br!=toneBright||wm!=toneWarm||ga!=toneGammaAdj){
   toneMode=tn;zoomX=zp/100f;zoomY=zpy/100f;toneBright=br;toneWarm=wm;toneGammaAdj=ga;
   float[] bl=tn==1?P_LIFT:new float[]{0f,0f,0f},bg=tn==1?P_GAIN:new float[]{1f,1f,1f},bgm=tn==1?P_GAMMA:new float[]{1f,1f,1f};
   for(int c=0;c<3;c++){toneLift[c]=bl[c];toneGain[c]=bg[c]*br;toneGamma[c]=bgm[c]*ga;}
   toneGain[0]=clampf(toneGain[0]+wm,0f,4f);toneGain[2]=clampf(toneGain[2]-wm,0f,4f);
   System.out.printf(java.util.Locale.US,"ENGINE_TONE tone=%d zoom=%.3f,%.3f bright=%.3f warm=%.3f gamma=%.3f lift=%.4f,%.4f,%.4f gain=%.4f,%.4f,%.4f gma=%.3f,%.3f,%.3f%n",
    toneMode,zoomX,zoomY,toneBright,toneWarm,toneGammaAdj,toneLift[0],toneLift[1],toneLift[2],toneGain[0],toneGain[1],toneGain[2],toneGamma[0],toneGamma[1],toneGamma[2]);
  }
 }
 static final java.util.HashSet<Long> checkpoints=new java.util.HashSet<>();
 static String checkpointDir;
 static void loadCheckpoints(String dir)throws IOException{
  File list=new File(dir+"/capture-sequences.txt");if(!list.isFile())return;
  for(String line:java.nio.file.Files.readAllLines(list.toPath())){line=line.trim();if(!line.isEmpty())checkpoints.add(Long.parseLong(line));}
  if(!checkpoints.isEmpty()){checkpointDir=dir;System.out.println("ENGINE_CHECKPOINTS "+checkpoints);}
 }
 static Object transaction()throws Exception{return tc.getConstructor().newInstance();}
 static void op(Object t,String name,Class<?>[] types,Object...args)throws Exception{tc.getMethod(name,types).invoke(t,args);}
 static synchronized void remove(){if(control!=null)try{Object t=transaction();op(t,"remove",new Class<?>[]{sc},control);op(t,"apply",new Class<?>[]{});sc.getMethod("release").invoke(control);control=null;System.out.println("ENGINE_SURFACE_REMOVED");}catch(Throwable x){x.printStackTrace();}}
 static String text(String path)throws Exception{try(InputStream in=new FileInputStream(path);ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] tmp=new byte[8192];for(int n;(n=in.read(tmp))>0;)b.write(tmp,0,n);return new String(b.toByteArray(),"UTF-8");}}
 static int shader(int type,String s){int x=GLES30.glCreateShader(type);GLES30.glShaderSource(x,s);GLES30.glCompileShader(x);int[] ok={0};GLES30.glGetShaderiv(x,GLES30.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new RuntimeException("Shader: "+GLES30.glGetShaderInfoLog(x));return x;}
 static int program(String v,String f){int p=GLES30.glCreateProgram(),vs=shader(GLES30.GL_VERTEX_SHADER,v),fs=shader(GLES30.GL_FRAGMENT_SHADER,f);GLES30.glAttachShader(p,vs);GLES30.glAttachShader(p,fs);GLES30.glLinkProgram(p);int[] ok={0};GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,ok,0);if(ok[0]==0)throw new RuntimeException(GLES30.glGetProgramInfoLog(p));GLES30.glDeleteShader(vs);GLES30.glDeleteShader(fs);return p;}
 static int texture(int w,int h,int format,int internal){int[] id={0};GLES30.glGenTextures(1,id,0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,id[0]);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,internal,w,h,0,format,GLES30.GL_UNSIGNED_BYTE,null);return id[0];}
 static ByteBuffer read(DataInputStream in,int n)throws IOException{byte[] b=new byte[n];in.readFully(b);return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);}
 static Thread reader(final DataInputStream in,final int bytes){Thread t=new Thread(()->{
  byte[] payload=new byte[bytes];long previous=0;FieldHistory sourceHistory=new FieldHistory(bytes*2/3);
  try{while(!stopping){ByteBuffer h=read(in,32);long seq=h.getLong(),time=h.getLong();int ring=h.getInt(),size=h.getInt();long copied=h.getLong();
   if(seq<=previous||time<=0||(ring&255)>2||(ring&~0x303)!=0||size!=bytes||copied<time)throw new IOException("Bad frame metadata");previous=seq;in.readFully(payload);received++;sourceHistory.push(payload,seq,time,(ring&256)!=0?0:1,(ring&512)!=0);
   Frame f=free.poll();if(f==null){f=latest.poll();if(f!=null)dropped++;}
   if(f==null){dropped++;continue;}f.pixels.clear();f.pixels.put(payload);f.pixels.flip();f.sequence=seq;f.completed=time;f.parity=(ring&256)!=0?0:1;f.stable=(ring&512)!=0;sourceHistory.snapshot(f.history);
   Frame old=latest.poll();if(old!=null){dropped++;free.offer(old);}if(!latest.offer(f))throw new IOException("Queue invariant");
  }}catch(Throwable x){if(!stopping)readerError=x;}
 },"x90-latest-frame");t.setDaemon(true);t.start();return t;}
 static void createSecondLayer(int w,int h)throws Exception{
  Class<?> bc=Class.forName("android.view.SurfaceControl$Builder");Object b=bc.getConstructor().newInstance();
  bc.getMethod("setName",String.class).invoke(b,"X90FieldEngine-Research2");bc.getMethod("setBufferSize",int.class,int.class).invoke(b,w,h);bc.getMethod("setFormat",int.class).invoke(b,1);bc.getMethod("setOpaque",boolean.class).invoke(b,false);control2=bc.getMethod("build").invoke(b);
  surface2=(Surface)Surface.class.getConstructor(sc).newInstance(control2);Object t=transaction();op(t,"setLayerStack",new Class<?>[]{sc,int.class},control2,0);op(t,"setLayer",new Class<?>[]{sc,int.class},control2,1000001);op(t,"setPosition",new Class<?>[]{sc,float.class,float.class},control2,0f,0f);op(t,"setAlpha",new Class<?>[]{sc,float.class},control2,0f);op(t,"hide",new Class<?>[]{sc},control2);op(t,"apply",new Class<?>[]{});
  System.out.println("ENGINE_SECOND_LAYER ready");
 }
 static void createSurface(int w,int h)throws Exception{
  sc=Class.forName("android.view.SurfaceControl");tc=Class.forName("android.view.SurfaceControl$Transaction");Class<?> bc=Class.forName("android.view.SurfaceControl$Builder");Object b=bc.getConstructor().newInstance();
  bc.getMethod("setName",String.class).invoke(b,"X90FieldEngine-Research");bc.getMethod("setBufferSize",int.class,int.class).invoke(b,w,h);bc.getMethod("setFormat",int.class).invoke(b,1);bc.getMethod("setOpaque",boolean.class).invoke(b,false);control=bc.getMethod("build").invoke(b);
  surface=(Surface)Surface.class.getConstructor(sc).newInstance(control);
  if(swapMode>=9&&swapMode<=16)createSecondLayer(w,h);Object t=transaction();op(t,"setLayerStack",new Class<?>[]{sc,int.class},control,0);op(t,"setLayer",new Class<?>[]{sc,int.class},control,1000000);op(t,"setPosition",new Class<?>[]{sc,float.class,float.class},control,0f,0f);if(w>1920||h>1080){float sx=1920f/w,sy=1080f/h;op(t,"setMatrix",new Class<?>[]{sc,float.class,float.class,float.class,float.class},control,sx,0f,0f,sy);System.out.println("ENGINE_LAYER_SCALE x="+sx+" y="+sy+" logical=1920x1080");}op(t,"show",new Class<?>[]{sc},control);if(swapMode==7||swapMode==8||swapMode>=17)try{op(t,"setFrameRate",new Class<?>[]{sc,float.class,int.class},control,120f,1);rateHint=120;System.out.println("ENGINE_RATE_HINT 120 fixed_source");}catch(Throwable x){System.out.println("ENGINE_RATE_HINT_UNSUPPORTED "+x);}op(t,"apply",new Class<?>[]{});
  display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] ver=new int[2];if(!EGL14.eglInitialize(display,ver,0,ver,1))throw new IOException("EGL init");int[] attrs={EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT|0x4,EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE};EGLConfig[] configs=new EGLConfig[1];int[] n=new int[1];if(!EGL14.eglChooseConfig(display,attrs,0,configs,0,1,n,0)||n[0]<1)throw new IOException("EGL config");
  context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);window=EGL14.eglCreateWindowSurface(display,configs[0],surface,new int[]{EGL14.EGL_NONE},0);if(!EGL14.eglMakeCurrent(display,window,window,context))throw new IOException("EGL window");
  if(swapMode>=9&&swapMode<=16){window2=EGL14.eglCreateWindowSurface(display,configs[0],surface2,new int[]{EGL14.EGL_NONE},0);if(window2==EGL14.EGL_NO_SURFACE)throw new IOException("EGL window2 "+EGL14.eglGetError());System.out.println("ENGINE_SECOND_WINDOW ready");}
  if(swapMode==5||swapMode==6)try{Method sm2=Surface.class.getMethod("setSharedBufferModeEnabled",boolean.class);sm2.invoke(surface,true);sharedMode=1;}catch(Throwable x){System.out.println("ENGINE_SHARED_UNSUPPORTED "+x);}
  if(swapMode==7||swapMode==8||swapMode>=17)try{Method sf=Surface.class.getMethod("setFrameRate",float.class,int.class);sf.invoke(surface,120f,1);System.out.println("ENGINE_SURFACE_FRAMERATE 120 fixed_source");}catch(Throwable x){System.out.println("ENGINE_SURFACE_FRAMERATE_UNSUPPORTED "+x);}
  if(swapMode==4||swapMode>=17){
   presentContext=EGL14.eglCreateContext(display,configs[0],context,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
   int[] pa={EGL14.EGL_WIDTH,16,EGL14.EGL_HEIGHT,16,EGL14.EGL_NONE};pbuffer=EGL14.eglCreatePbufferSurface(display,configs[0],pa,0);
   if(presentContext==EGL14.EGL_NO_CONTEXT||pbuffer==EGL14.EGL_NO_SURFACE||!EGL14.eglMakeCurrent(display,pbuffer,pbuffer,context))throw new IOException("EGL pbuffer "+EGL14.eglGetError());
   int ns=swapMode==24?7:(swapMode>=17?5:3);texReady=new ArrayBlockingQueue<>(ns);texSpare=new ArrayBlockingQueue<>(ns);for(int i=0;i<ns;i++)texSpare.add(i);
  }
  System.out.println("ENGINE_SURFACE_MODE shared="+sharedMode+" swap="+swapMode);
  System.out.println("ENGINE_SURFACE_READY size="+w+"x"+h+" renderer="+GLES30.glGetString(GLES30.GL_RENDERER));
 }
 static long meanAbsDiff(ByteBuffer a,ByteBuffer b,int bytes){
  int step=16,n=bytes/step;long sum=0;
  for(int i=0;i<n;i++){int p=i*step;sum+=Math.abs((a.get(p)&255)-(b.get(p)&255));}
  return sum/Math.max(1,n);
 }
 static void draw(Frame f,Frame next,boolean futureValid,boolean cut,long rendered)throws IOException{
  int bytes=stride*height*3/2;
  boolean valid=f.history.valid;
  if(f.history.sequence[0]!=f.sequence||f.history.time[0]!=f.completed||f.history.parity[0]!=f.parity)throw new IOException("History publication mismatch");
  if(valid)adaptiveFrames++;else spatialFrames++;
  if(packedDiagnostic&&rendered==120){
   File raw=new File("/data/local/tests/x90-diag-current.nv12");if(!raw.createNewFile())throw new IOException("Diagnostic exists");
   try(FileOutputStream out=new FileOutputStream(raw)){ByteBuffer copy=f.pixels.duplicate();copy.position(0);copy.limit(bytes);while(copy.hasRemaining())out.getChannel().write(copy);}
   File meta=new File("/data/local/tests/x90-diag-metadata.txt");if(!meta.createNewFile())throw new IOException("Metadata exists");
   try(PrintWriter out=new PrintWriter(meta)){out.println("history_valid="+valid);out.println("future_valid="+futureValid);out.println("cut="+cut);for(int h=0;h<4;h++)out.println(h+" "+f.history.sequence[h]+" "+f.history.time[h]+" "+f.history.parity[h]+" "+f.history.stable[h]);}
  }
  f.pixels.position(0);f.pixels.limit(stride*height);
  GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,ytexHandle);
  GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D,0,0,0,stride,height,GLES30.GL_RED,GLES30.GL_UNSIGNED_BYTE,f.pixels);
  for(int h=1;h<=3;h++){ByteBuffer past=f.history.previous[h-1];past.position(0);past.limit(stride*height);GLES30.glActiveTexture(GLES30.GL_TEXTURE0+h+1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,historyTextures[h]);GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D,0,0,0,stride,height,GLES30.GL_RED,GLES30.GL_UNSIGNED_BYTE,past);}
  if(futureValid){next.pixels.position(0);next.pixels.limit(stride*height);GLES30.glActiveTexture(GLES30.GL_TEXTURE0+5);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,historyTextures[4]);GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D,0,0,0,stride,height,GLES30.GL_RED,GLES30.GL_UNSIGNED_BYTE,next.pixels);}
  f.pixels.limit(bytes);f.pixels.position(stride*height);GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,uvtexHandle);GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D,0,0,0,stride/2,height/2,GLES30.GL_RG,GLES30.GL_UNSIGNED_BYTE,f.pixels);
  GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);GLES30.glViewport(0,0,width,height*2);GLES30.glUseProgram(convert);
  GLES30.glUniform1i(GLES30.glGetUniformLocation(convert,"parity"),f.parity);GLES30.glUniform1i(GLES30.glGetUniformLocation(convert,"historyValid"),valid?1:0);GLES30.glUniform1i(GLES30.glGetUniformLocation(convert,"futureValid"),futureValid?1:0);GLES30.glUniform1i(GLES30.glGetUniformLocation(convert,"cut"),cut?1:0);GLES30.glUniform1i(locDi,diMode);GLES30.glUniform1f(locMotion,motionThr);GLES30.glUniform1f(locEdge,edgeGain);
  for(int h=1;h<=3;h++){int unit=h+1;GLES30.glActiveTexture(GLES30.GL_TEXTURE0+unit);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,historyTextures[h]);GLES30.glUniform1i(GLES30.glGetUniformLocation(convert,"history"+h),unit);}
  GLES30.glActiveTexture(GLES30.GL_TEXTURE0+5);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,historyTextures[4]);GLES30.glUniform1i(GLES30.glGetUniformLocation(convert,"futuretex"),5);
  int gs=GPUPROF?(gqSet<=0?1:0):0;if(GPUPROF)gpCollect(gs);gpBegin(gs*2);
  GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
  gpEnd();
  if(checkpointDir!=null&&checkpoints.contains(f.sequence)){
   int lumaOnly=GLES30.glGetUniformLocation(convert,"lumaOnly");
   GLES30.glUniform1i(lumaOnly,1);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
   ByteBuffer px=ByteBuffer.allocateDirect(width*height*2*4);GLES30.glReadPixels(0,0,width,height*2,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,px);
   File rgba=new File(checkpointDir,"diag-"+f.sequence+".rgba");try(FileOutputStream out=new FileOutputStream(rgba)){px.position(0);while(px.hasRemaining())out.getChannel().write(px);}
   try(PrintWriter out=new PrintWriter(new File(checkpointDir,"diag-"+f.sequence+".txt"))){
    out.println("engine=vt seq="+f.sequence+" parity="+f.parity+" history_valid="+valid+" future_valid="+futureValid+" cut="+cut);
    out.println("current "+f.sequence+" "+f.completed+" "+f.parity+" "+f.stable);
    for(int h=0;h<4;h++)out.println("history"+h+" "+f.history.sequence[h]+" "+f.history.time[h]+" "+f.history.parity[h]+" "+f.history.stable[h]);
    if(next!=null)out.println("future "+next.sequence+" "+next.completed+" "+next.parity+" "+next.stable+" used="+futureValid);
   }
   GLES30.glUniform1i(lumaOnly,0);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
   System.out.println("ENGINE_CHECKPOINT_CAPTURED seq="+f.sequence+" future="+futureValid+" cut="+cut);
  }
  if(UPVAL&&FASTUP&&rendered==300)validateFastUp();
  if(FASTUP){fastUpscale(frameTarget);}else{
  GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,frameTarget);GLES30.glViewport(0,0,outWidth,outHeight);GLES30.glClearColor(0,0,0,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);GLES30.glViewport(contentX,0,contentWidth,contentHeight);GLES30.glUseProgram(upscale);GLES30.glUniform1f(locSharp,sharpen);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,rgbtex);gpBegin(gs*2+1);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);gpEnd();if(GPUPROF){gqSet=gs;gqUsed[gs]=true;}
  }
  if(captureRequest){
   captureRequest=false;new File("/data/local/tests/x90-engine-capture-one").delete();
   ByteBuffer px=ByteBuffer.allocateDirect(outWidth*outHeight*4);GLES30.glReadPixels(0,0,outWidth,outHeight,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,px);
   File shot=new File(sessionDir+"/capture-"+rendered+".rgba");try(FileOutputStream out=new FileOutputStream(shot)){px.position(0);while(px.hasRemaining())out.getChannel().write(px);}
   System.out.println("ENGINE_CAPTURE rendered="+rendered+" size="+outWidth+"x"+outHeight+" di="+diMode+" motion="+motionThr+" edge="+edgeGain+" sharp="+sharpen);
  }
 }
 static volatile long presSeq;
 static void presentTex(int tex)throws IOException{presentTex(tex,window);}
 static void presentTex(int tex,EGLSurface surf)throws IOException{
  GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);GLES30.glViewport(0,0,outWidth,outHeight);int pp=SPLIT>0f?splitPresentProg:presentProg;GLES30.glUseProgram(pp);if(SPLIT>0f){GLES30.glUniform1f(GLES30.glGetUniformLocation(pp,"splitx"),SPLIT*outWidth);GLES30.glUniform1f(GLES30.glGetUniformLocation(pp,"divw"),SPLITDIV);}GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,tex);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
  if(!EGL14.eglSwapBuffers(display,surf))throw new IOException("Swap error "+EGL14.eglGetError()+" surf="+surf+" window="+window+" window2="+window2);
 }
 static void parkUntil(long t){long n;while((n=t-System.nanoTime())>0)LockSupport.parkNanos(n>2_000_000L?1_000_000L:n);}
 static void present()throws IOException{if(DIRECT){if(!EGL14.eglSwapBuffers(display,window))throw new IOException("Swap error "+EGL14.eglGetError());return;}presentTex(frameTex);if(GLES30.glGetError()!=GLES30.GL_NO_ERROR)throw new IOException("GL error");}
 static void presentLayer(int which)throws IOException{
  EGLSurface w=which==0?window:window2;
  if(w==EGL14.EGL_NO_SURFACE)throw new IOException("Layer surface missing "+which);
  if(!EGL14.eglMakeCurrent(display,w,w,context))throw new IOException("Layer current "+which+" "+EGL14.eglGetError());
  try{presentTex(frameTex,w);}catch(IOException x){throw new IOException("Layer present "+which,x);}
  if(swapMode==10)return;
  try{Object t=transaction();if(which==0){op(t,"show",new Class<?>[]{sc},control);op(t,"hide",new Class<?>[]{sc},control2);op(t,"setAlpha",new Class<?>[]{sc,float.class},control,1f);}else{op(t,"show",new Class<?>[]{sc},control2);op(t,"hide",new Class<?>[]{sc},control);op(t,"setAlpha",new Class<?>[]{sc,float.class},control2,1f);}op(t,"apply",new Class<?>[]{});}catch(Exception x){throw new IOException("Layer transaction",x);}
  layerShow=which;
 }
 static void presentOnly(int which,int tex)throws IOException{
  EGLSurface w=which==0?window:window2;
  if(w==EGL14.EGL_NO_SURFACE)throw new IOException("Layer surface missing "+which);
  if(!EGL14.eglMakeCurrent(display,w,w,context))throw new IOException("Layer current "+which+" "+EGL14.eglGetError());
  if(swapMode>=11)EGL14.eglSwapInterval(display,0);
  presentTex(tex,w);
 }
 static void flipTo(int which)throws IOException{
  try{Object t=transaction();if(which==0){op(t,"show",new Class<?>[]{sc},control);op(t,"hide",new Class<?>[]{sc},control2);op(t,"setAlpha",new Class<?>[]{sc,float.class},control,1f);}else{op(t,"show",new Class<?>[]{sc},control2);op(t,"hide",new Class<?>[]{sc},control);op(t,"setAlpha",new Class<?>[]{sc,float.class},control2,1f);}op(t,"apply",new Class<?>[]{});}catch(Exception x){throw new IOException("Layer transaction",x);}
  layerShow=which;
 }
 static long waitChain(long surf,long id)throws IOException{
  long t0=System.nanoTime(),deadline=t0+25_000_000L,last=-2;
  while(true){long l=timingLatchOn(surf,id);
   if(l>0){chainWaits++;long w=System.nanoTime()-t0;if(w>chainWaitMax)chainWaitMax=w;return w;}
   last=l;
   if(System.nanoTime()>deadline){chainMiss++;System.out.println("ENGINE_CHAIN_MISS id="+id+" last="+last);return -1;}
   LockSupport.parkNanos(100_000L);
  }
 }
 static void presenter(){
  try{
   if(!EGL14.eglMakeCurrent(display,window,window,presentContext)){System.out.println("ENGINE_PRESENTER_CURRENT_FAILED "+EGL14.eglGetError());return;}
   GLES30.glBindVertexArray(presentVao);
   while(!stopping||!texReady.isEmpty()){
    Integer slot=texReady.poll(100,TimeUnit.MILLISECONDS);if(slot==null)continue;
    long seq=presSeq,id=timing?timingNext():0,t0=System.nanoTime();presentTex(frameTexs[slot]);long t1=System.nanoTime();
    if(timing)timingSubmitted(id,seq,0,t1);presents++;swaps++;swapLast=t1;if(swapFirst==0)swapFirst=t1;double b=(t1-t0)/1e6;presBlockSum+=b;if(b>presBlockMax)presBlockMax=b;
    if(!stopping&&texReady.isEmpty()){
     id=timing?timingNext():0;t0=System.nanoTime();presentTex(frameTexs[slot]);t1=System.nanoTime();
     if(timing)timingSubmitted(id,seq,0,t1);presents++;swaps++;swapLast=t1;b=(t1-t0)/1e6;presBlockSum+=b;if(b>presBlockMax)presBlockMax=b;
    }else presSkipped++;
    texSpare.put(slot);
   }
   EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
  }catch(Throwable x){System.out.println("ENGINE_PRESENTER_ERROR "+x);}
 }
 static void startVsyncClock(){
  Thread t=new Thread(()->{try{
   Looper.prepare();
   Choreographer c=Choreographer.getInstance();
   Choreographer.FrameCallback cb=new Choreographer.FrameCallback(){public void doFrame(long f){long pv=vsyncNs;if(pv>0){long d=f-pv;if(d>4_000_000L&&d<40_000_000L)vsyncPeriodNs=d;}vsyncNs=f;vsyncTicks++;c.postFrameCallback(this);}};
   c.postFrameCallback(cb);Looper.loop();
  }catch(Throwable x){System.out.println("ENGINE_VSYNC_CLOCK_FAILED "+x);}},"x90-vsync");
  t.setDaemon(true);t.start();vsyncThread=t;
 }
 static void motionDraw26(int a,int b){
  if(swapMode>=32){
   GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,coarseFbo);GLES30.glViewport(0,0,coarseW,coarseH);GLES30.glUseProgram(motionProg);
   GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[a]);GLES30.glUniform1i(GLES30.glGetUniformLocation(motionProg,"prev"),0);
   GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[b]);GLES30.glUniform1i(GLES30.glGetUniformLocation(motionProg,"cur"),1);
   GLES30.glUniform2i(GLES30.glGetUniformLocation(motionProg,"sizePx"),outWidth,outHeight);
   GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
   GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,pmotionFbo);GLES30.glViewport(0,0,motionW,motionH);GLES30.glUseProgram(refineProg);
   GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[a]);GLES30.glUniform1i(GLES30.glGetUniformLocation(refineProg,"prev"),0);
   GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[b]);GLES30.glUniform1i(GLES30.glGetUniformLocation(refineProg,"cur"),1);
   GLES30.glActiveTexture(GLES30.GL_TEXTURE2);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,coarseTex);GLES30.glUniform1i(GLES30.glGetUniformLocation(refineProg,"coarse"),2);
   GLES30.glUniform2i(GLES30.glGetUniformLocation(refineProg,"sizePx"),outWidth,outHeight);
   GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
   if(swapMode>=33){
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,medianFbo);GLES30.glViewport(0,0,motionW,motionH);GLES30.glUseProgram(medianProg);
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,motionTex);GLES30.glUniform1i(GLES30.glGetUniformLocation(medianProg,"m"),0);
    GLES30.glUniform2i(GLES30.glGetUniformLocation(medianProg,"motionSize"),motionW,motionH);
    GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
   }
   GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
   return;
  }
  GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,pmotionFbo);GLES30.glViewport(0,0,motionW,motionH);GLES30.glUseProgram(motionProg);
  GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[a]);GLES30.glUniform1i(GLES30.glGetUniformLocation(motionProg,"prev"),0);
  GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[b]);GLES30.glUniform1i(GLES30.glGetUniformLocation(motionProg,"cur"),1);
  GLES30.glUniform2i(GLES30.glGetUniformLocation(motionProg,"sizeQ"),qw,qh);GLES30.glUniform2i(GLES30.glGetUniformLocation(motionProg,"sizePx"),outWidth,outHeight);
  GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
 }
 static void presenter2(){
  try{
   if(!EGL14.eglMakeCurrent(display,window,window,presentContext)){System.out.println("ENGINE_PRESENTER2_CURRENT_FAILED "+EGL14.eglGetError());return;}
   int[] vao={0};GLES30.glGenVertexArrays(1,vao,0);GLES30.glBindVertexArray(vao[0]);
   if(swapMode>=20&&swapMode!=21){int[] pm={0};GLES30.glGenFramebuffers(1,pm,0);pmotionFbo=pm[0];GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,pmotionFbo);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,motionTex,0);System.out.println("ENGINE_PRESENTER_MOTION_FBO status="+GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)+" fbo="+pmotionFbo);}
   if(swapMode>=33&&medianTex>=0){int[] mf={0};GLES30.glGenFramebuffers(1,mf,0);medianFbo=mf[0];GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,medianFbo);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,medianTex,0);System.out.println("ENGINE_MEDIAN_FBO status="+GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)+" fbo="+medianFbo);}
   if(swapMode>=32&&coarseTex>=0){int[] cf={0};GLES30.glGenFramebuffers(1,cf,0);coarseFbo=cf[0];GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,coarseFbo);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,coarseTex,0);System.out.println("ENGINE_COARSE_FBO status="+GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)+" fbo="+coarseFbo);}
   if(swapMode==23||swapMode>=25){
    qw=outWidth/4;qh=outHeight/4;
    if(swapMode<26){
    pairTex=texture(2*qw,qh,GLES30.GL_RGBA,GLES30.GL_RGBA8);
    int[] pp={0};GLES30.glGenFramebuffers(1,pp,0);pairFbo=pp[0];GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,pairFbo);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,pairTex,0);System.out.println("ENGINE_PAIR_FBO size="+(2*qw)+"x"+qh+" status="+GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER));
    }
   }
   long lastTick=vsyncTicks,lastSwap=0;int cur=-1,pendingSlot=-1;boolean showReal=false,motionForPending=false;int dumpFbo=-1;
   System.out.println("ENGINE_PRESENTER2_READY mode="+swapMode+" vsync_clock="+vsyncTicks);
   while(!stopping||!texReady.isEmpty()){
    long tick=vsyncTicks;
    if(tick==lastTick){LockSupport.parkNanos(200_000L);continue;}
    lastTick=tick;
    long id=timing?timingNext():0,t0=System.nanoTime();
    Integer got=null;
    if(swapMode==24){
     if(pendingSlot<0){Integer s=texReady.poll();if(s!=null)pendingSlot=s;}
     if(pendingSlot>=0){
      if(slotMid[pendingSlot]){presentTex(frameTexs[pendingSlot]);presMidCount++;texSpare.offer(pendingSlot);}
      else{int old=cur;cur=pendingSlot;presRealNew++;presentTex(frameTexs[cur]);if(old>=0)texSpare.offer(old);}
      pendingSlot=-1;presNewFrame++;
     }else{presRepeats++;if(cur>=0)presentTex(frameTexs[cur]);}
    }else if(swapMode>=19&&!showReal&&cur>=0&&pendingSlot<0)got=texReady.poll();
    if(swapMode>=19&&showReal&&cur>=0){
     if(swapMode==26&&pendingSlot<0){Integer s=texReady.poll();if(s!=null)pendingSlot=s;}
     if(swapMode==26&&pendingSlot>=0){motionDraw26(cur,pendingSlot);motionForPending=true;}
     presentTex(frameTexs[cur],window);showReal=false;presRealNew++;
    }else if(swapMode>=19&&cur>=0&&(pendingSlot>=0||got!=null)){
     if(got!=null)pendingSlot=got;
     long sa=cur>=0?slotSeq[cur]:0,sb=pendingSlot>=0?slotSeq[pendingSlot]:0;
     {if(sa>0&&sb>0){long gp=sb-sa;pairCount++;pairGapSum+=gp;if(gp!=1)pairBad++;if(gp<pairGapMin)pairGapMin=gp;if(gp>pairGapMax)pairGapMax=gp;if(pairCount%120==0)System.out.printf(java.util.Locale.US,"ENGINE_PAIR_GAP pairs=%d mean=%.3f min=%d max=%d not_adjacent=%.2f%% skipped=%d%n",pairCount,pairGapSum/(double)pairCount,pairGapMin,pairGapMax,pairBad*100.0/pairCount,pairSkipped);}}
     if(sa>0&&sb>0&&sb-sa>4){
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);GLES30.glViewport(0,0,outWidth,outHeight);GLES30.glUseProgram(presentProg);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[pendingSlot]);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
      pairSkipped++;
     }else if(swapMode>=20){
      if(swapMode!=21){
       if(swapMode>=26){
        if(swapMode!=26||!motionForPending)motionDraw26(cur,pendingSlot);
        motionForPending=false;
       }else if(swapMode>=23){
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,pairFbo);GLES30.glViewport(0,0,2*qw,qh);GLES30.glUseProgram(pairProg);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[cur]);GLES30.glUniform1i(GLES30.glGetUniformLocation(pairProg,"a"),0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[pendingSlot]);GLES30.glUniform1i(GLES30.glGetUniformLocation(pairProg,"b"),1);
        GLES30.glUniform2f(GLES30.glGetUniformLocation(pairProg,"outSize"),2*qw,qh);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,pmotionFbo);GLES30.glViewport(0,0,motionW,motionH);GLES30.glUseProgram(motionProg);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,pairTex);GLES30.glUniform1i(GLES30.glGetUniformLocation(motionProg,"pair"),0);
        GLES30.glUniform2i(GLES30.glGetUniformLocation(motionProg,"curOff"),qw,0);
        GLES30.glUniform2i(GLES30.glGetUniformLocation(motionProg,"sizeQ"),qw,qh);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
       }else{
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,pmotionFbo);GLES30.glViewport(0,0,motionW,motionH);GLES30.glUseProgram(motionProg);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[cur]);GLES30.glUniform1i(GLES30.glGetUniformLocation(motionProg,"prev"),0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[pendingSlot]);GLES30.glUniform1i(GLES30.glGetUniformLocation(motionProg,"cur"),1);
        GLES30.glUniform2f(GLES30.glGetUniformLocation(motionProg,"frameSize"),outWidth,outHeight);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
       }
       if(motionProofRequest){
        motionProofRequest=false;new File("/data/local/tests/x90-engine-capture-motion").delete();
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,pmotionFbo);
        ByteBuffer mb=ByteBuffer.allocateDirect(motionW*motionH*4);
        GLES30.glReadPixels(0,0,motionW,motionH,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,mb);
        double mag=0,cf=0;float mxn=0;int n=motionW*motionH;float sc=(swapMode>=23&&swapMode<29)?32f:swapMode>=36?127.5f:128f,of=(swapMode>=23&&swapMode<29)?16f:64f;
        for(int i=0;i<n;i++){int p=i*4;float vx=(mb.get(p)&255)/255f*sc-of,vy=(mb.get(p+1)&255)/255f*sc-of,c=(mb.get(p+2)&255)/255f;mag+=Math.sqrt(vx*vx+vy*vy);cf+=c;if(Math.abs(vx)>mxn)mxn=Math.abs(vx);}
        try(FileOutputStream o=new FileOutputStream(sessionDir+"/motion-"+presSeq+".rgba")){mb.position(0);while(mb.hasRemaining())o.getChannel().write(mb);}
        System.out.printf(java.util.Locale.US,"ENGINE_MOTION_PROOF seq=%d size=%dx%d mean_px=%.3f mean_conf=%.3f max_abs_x=%.1f%n",presSeq,motionW,motionH,mag/n,cf/n,mxn);
       }
      }
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);GLES30.glViewport(0,0,outWidth,outHeight);GLES30.glUseProgram(warpProg);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[cur]);GLES30.glUniform1i(GLES30.glGetUniformLocation(warpProg,"a"),0);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[pendingSlot]);GLES30.glUniform1i(GLES30.glGetUniformLocation(warpProg,"b"),1);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE2);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,swapMode==21?motionTexs[pendingSlot]:swapMode>=33?medianTex:motionTex);GLES30.glUniform1i(GLES30.glGetUniformLocation(warpProg,"m"),2);
      GLES30.glUniform2f(GLES30.glGetUniformLocation(warpProg,"frameSize"),outWidth,outHeight);
      GLES30.glUniform1f(GLES30.glGetUniformLocation(warpProg,"mixT"),0.5f);
      GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
     }else{
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);GLES30.glViewport(0,0,outWidth,outHeight);GLES30.glUseProgram(mixProg);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[cur]);GLES30.glUniform1i(GLES30.glGetUniformLocation(mixProg,"a"),0);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[pendingSlot]);GLES30.glUniform1i(GLES30.glGetUniformLocation(mixProg,"b"),1);
      GLES30.glUniform1f(GLES30.glGetUniformLocation(mixProg,"t"),0.5f);
      GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
     }
     if(presentCaptureRequest){
      presentCaptureRequest=false;
      long seqAt=presSeq;
      ByteBuffer px=ByteBuffer.allocateDirect(outWidth*outHeight*4);
      GLES30.glReadPixels(0,0,outWidth,outHeight,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,px);
      File shot=new File(sessionDir+"/present-mid-"+seqAt+".rgba");try(FileOutputStream out=new FileOutputStream(shot)){px.position(0);while(px.hasRemaining())out.getChannel().write(px);}
      // Same-instant pair dump: the two textures that produced this midpoint,
      // so the motion sign and the blend can be verified against the oracle.
      if(swapMode>=19){
       if(dumpFbo<0){int[] df={0};GLES30.glGenFramebuffers(1,df,0);dumpFbo=df[0];}
       GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,dumpFbo);
       GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,frameTexs[cur],0);
       if(GLES30.GL_FRAMEBUFFER_COMPLETE==GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)){
        String[] tags={"pair-a","pair-b"};int[] slots={cur,pendingSlot};
        for(int k=0;k<2;k++){
         if(k==1)GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,frameTexs[slots[k]],0);
         px.clear();GLES30.glReadPixels(0,0,outWidth,outHeight,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,px);
         File f=new File(sessionDir+"/"+tags[k]+"-"+seqAt+".rgba");try(FileOutputStream out=new FileOutputStream(f)){px.position(0);while(px.hasRemaining())out.getChannel().write(px);}
        }
       }
       GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,0,0);
       GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
       if(swapMode>=20&&swapMode!=21&&swapMode!=24&&pmotionFbo>=0){
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,swapMode>=33&&medianFbo>=0?medianFbo:pmotionFbo);
        ByteBuffer mb=ByteBuffer.allocateDirect(motionW*motionH*4);
        GLES30.glReadPixels(0,0,motionW,motionH,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,mb);
        try(FileOutputStream out=new FileOutputStream(sessionDir+"/motion-"+seqAt+".rgba")){mb.position(0);while(mb.hasRemaining())out.getChannel().write(mb);}
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
       }
      }
      System.out.println("ENGINE_PRESENT_CAPTURE seq="+seqAt+" tag=mid mode="+swapMode+" size="+outWidth+"x"+outHeight+" pair="+cur+","+pendingSlot);
     }
     if(!EGL14.eglSwapBuffers(display,window))throw new IOException("Swap error "+EGL14.eglGetError());
     int old=cur;cur=pendingSlot;pendingSlot=-1;if(old>=0&&!texSpare.offer(old))presSkipped++;presMidCount++;presNewFrame++;showReal=true;
    }else{
     if(pendingSlot<0){Integer s=got!=null?got:texReady.poll();if(s!=null)pendingSlot=s;}
     if(pendingSlot>=0){int old=cur;cur=pendingSlot;pendingSlot=-1;if(old>=0&&!texSpare.offer(old))presSkipped++;presNewFrame++;if(swapMode>=19)presRealNew++;}
     else presRepeats++;
     if(cur<0)continue;
     presentTex(frameTexs[cur],window);
    }
    long t1=System.nanoTime();
    if(timing&&id>0)timingSubmitted(id,presSeq,0,t1);
    presents++;swaps++;if(swapFirst==0)swapFirst=t1;swapLast=t1;
    double b=(t1-t0)/1e6;presBlockSum+=b;if(b>presBlockMax)presBlockMax=b;
    if(lastSwap>0&&t1-lastSwap>periodNs+2_000_000L)presMissed+=(t1-lastSwap)/periodNs-1;
    lastSwap=t1;
   }
   EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
  }catch(Throwable x){System.out.println("ENGINE_PRESENTER2_ERROR "+x);}
 }
 public static void main(String[] args){int result=1;Thread reader=null;
  Runtime.getRuntime().addShutdownHook(new Thread(()->{stopping=true;try{if(socket!=null)socket.close();}catch(Exception ignored){}remove();}));
  try{
   if(args.length<5||args.length>7)throw new IllegalArgumentException("shaderdir port seconds width height");int port=Integer.parseInt(args[1]),seconds=Integer.parseInt(args[2]),ow=Integer.parseInt(args[3]),oh=Integer.parseInt(args[4]);if((seconds!=0&&seconds<2)||seconds>60||ow<640||ow>2560||oh<480||oh>1440||port<1024||port>65535)throw new IllegalArgumentException();sessionDir=args[0];
   long until=System.nanoTime()+5_000_000_000L;
   while(socket==null){try{Socket s=new Socket();s.connect(new InetSocketAddress("127.0.0.1",port),300);s.setSoTimeout(1500);s.setTcpNoDelay(true);socket=s;}catch(IOException x){if(System.nanoTime()>until)throw x;Thread.sleep(100);}}
   DataInputStream in=new DataInputStream(socket.getInputStream());ByteBuffer head=read(in,32);byte[] magic=new byte[8];head.get(magic);int version=head.getInt();width=head.getInt();height=head.getInt();stride=head.getInt();int frames=head.getInt(),source=head.getInt();
   if(!Arrays.equals(magic,new byte[]{'X','9','0','F','L','D','1',0})||version!=1||width<16||width>720||height<16||height>480||stride<width||stride>720||(height&1)!=0||(stride&15)!=0||source!=5||frames!=0||width!=720||height!=240||stride!=720)throw new IOException("Unsupported capture header");
   readSwapEarly();
   int bytes=stride*height*3/2;for(int i=0;i<6;i++)free.add(new Frame(bytes));reader=reader(in,bytes);outWidth=ow;outHeight=oh;createSurface(ow,oh);loadCheckpoints(args[0]);
   if(args.length==7&&(args[6].equals("timing")||args[6].equals("timing-coalesce")||args[6].startsWith("packed-diagnostic"))){forceCoalesce=args[6].equals("timing-coalesce")||args[6].equals("packed-diagnostic-coalesce");packedDiagnostic=args[6].startsWith("packed-diagnostic");System.load(args[0]+"/libengine-timing.so");timing=timingInit(display.getNativeHandle(),window.getNativeHandle(),args[0]+"/egl-timing.csv")!=0;System.out.println("ENGINE_PACING delayed_field=true timing="+timing);if(timing&&window2!=EGL14.EGL_NO_SURFACE){int bits=timingAttach(window2.getNativeHandle());System.out.println("ENGINE_SECOND_TIMING bits="+bits);}if(swapMode>=11){EGL14.eglSwapInterval(display,0);System.out.println("ENGINE_SWAP_INTERVAL 0 mode="+swapMode);}}
   String filterName=args.length>=6?args[5]:"catmullrom-clamped.frag";if(!filterName.equals("catmullrom-clamped.frag"))throw new IllegalArgumentException("filter");String vertex=text(args[0]+"/fullscreen.vert"),filter=text(args[0]+"/"+filterName);
   String yuv=text(args[0]+"/field-di-vt.frag");
   convert=program(vertex,yuv);upscale=program(vertex,filter);ytexHandle=texture(stride,height,GLES30.GL_RED,GLES30.GL_R8);uvtexHandle=texture(stride/2,height/2,GLES30.GL_RG,GLES30.GL_RG8);rgbtex=texture(width,height*2,GLES30.GL_RGBA,GLES30.GL_RGBA8);
   historyTextures=new int[]{0,texture(stride,height,GLES30.GL_RED,GLES30.GL_R8),texture(stride,height,GLES30.GL_RED,GLES30.GL_R8),texture(stride,height,GLES30.GL_RED,GLES30.GL_R8),texture(stride,height,GLES30.GL_RED,GLES30.GL_R8)};
   System.out.println("FIELD_HISTORY source_reader_owned=true previous_luma_uploads=3 future_luma_upload="+(LOW_LATENCY?0:1)+" delayed_field="+(!LOW_LATENCY));
   System.out.println("ENGINE_LOW_LATENCY="+(LOW_LATENCY?1:0));
   int[] fboId={0},vaoId={0};GLES30.glGenFramebuffers(1,fboId,0);fbo=fboId[0];GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,rgbtex,0);if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw new IOException("RGB FBO");GLES30.glGenVertexArrays(1,vaoId,0);vao=vaoId[0];GLES30.glBindVertexArray(vao);GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT,1);GLES30.glDisable(GLES30.GL_DITHER);GLES30.glDisable(GLES30.GL_BLEND);
   GLES30.glUseProgram(convert);GLES30.glUniform1i(GLES30.glGetUniformLocation(convert,"ytex"),0);GLES30.glUniform1i(GLES30.glGetUniformLocation(convert,"uvtex"),1);GLES30.glUniform2f(GLES30.glGetUniformLocation(convert,"sourceSize"),width,height);GLES30.glUniform1f(GLES30.glGetUniformLocation(convert,"strideSize"),stride);
   contentWidth=Math.min(ow,oh*4/3);contentHeight=oh;contentX=(ow-contentWidth)/2;
   String adjusted=filter.replace("gl_FragCoord.xy/outsize","(gl_FragCoord.xy-vec2("+contentX+".0,0.0))/outsize");GLES30.glDeleteProgram(upscale);upscale=program(vertex,adjusted);GLES30.glUseProgram(upscale);GLES30.glUniform2f(GLES30.glGetUniformLocation(upscale,"outsize"),contentWidth,contentHeight);GLES30.glUniform1i(GLES30.glGetUniformLocation(upscale,"src"),0);System.out.println("ENGINE_FILTER "+filterName);
   if("1".equals(System.getProperty("x90.fastup"))){
    java.util.Map<String,String> fus=new java.util.HashMap<>();
    if(new File(args[0]+"/fastup.glsl").isFile()){String all=text(args[0]+"/fastup.glsl");for(String part:all.split("//@@ ")){if(part.trim().isEmpty())continue;int nl=part.indexOf(10);fus.put(part.substring(0,nl).trim(),part.substring(nl+1));}}
    else for(String n:new String[]{"fu-pre-block.frag","fu-pre-cross.frag","fu-h.frag","fu-v.frag"})fus.put(n,text(args[0]+"/"+n));
    for(String n:new String[]{"fu-pre-block.frag","fu-pre-cross.frag","fu-h.frag","fu-v.frag"})if(!fus.containsKey(n))throw new IOException("fastup shader missing "+n);
    fuPreB=program(vertex,fus.get("fu-pre-block.frag"));fuPreC=program(vertex,fus.get("fu-pre-cross.frag"));fuH=program(vertex,fus.get("fu-h.frag"));fuV=program(vertex,fus.get("fu-v.frag"));
    fuBmin=texS(width+1,height*2+1,GLES30.GL_RGBA8);fuBmax=texS(width+1,height*2+1,GLES30.GL_RGBA8);fuCmin=texS(width,height*2,GLES30.GL_RGBA8);fuCmax=texS(width,height*2,GLES30.GL_RGBA8);fuBlur=texS(width,height*2,GLES30.GL_RGBA16F);fuHtex=texS(contentWidth,height*2,GLES30.GL_RGBA16F);
    fuFboB=fboOf(fuBmin,fuBmax);fuFboC=fboOf(fuCmin,fuCmax,fuBlur);fuFboH=fboOf(fuHtex);FASTUP=true;UPVAL="1".equals(System.getProperty("x90.upval"));
    System.out.println("ENGINE_FASTUP ready htex="+contentWidth+"x"+(height*2)+" validate="+UPVAL);
   }
   presentProg=program(vertex,text(args[0]+"/copy.frag"));GLES30.glUseProgram(presentProg);GLES30.glUniform1i(GLES30.glGetUniformLocation(presentProg,"src"),0);
   if(SPLIT>0f){splitPresentProg=program(vertex,SPLIT_FRAG);int sp=splitPresentProg;GLES30.glUseProgram(sp);GLES30.glUniform1i(GLES30.glGetUniformLocation(sp,"src"),0);if(SPLIT_LABEL){GLES30.glUniform1i(GLES30.glGetUniformLocation(sp,"lblOn"),1);GLES30.glUniform1i(GLES30.glGetUniformLocation(sp,"outH"),outHeight);GLES30.glUniform1iv(GLES30.glGetUniformLocation(sp,"font"),FONT.length,FONT,0);GLES30.glUniform1iv(GLES30.glGetUniformLocation(sp,"labL"),LBL_MAXLEN,glyphs(SPLITL),0);GLES30.glUniform1iv(GLES30.glGetUniformLocation(sp,"labR"),LBL_MAXLEN,glyphs(SPLITR),0);GLES30.glUniform1i(GLES30.glGetUniformLocation(sp,"lenL"),glyphLen(SPLITL));GLES30.glUniform1i(GLES30.glGetUniformLocation(sp,"lenR"),glyphLen(SPLITR));GLES30.glUniform1f(GLES30.glGetUniformLocation(sp,"dotp"),LBLDOT);GLES30.glUniform1f(GLES30.glGetUniformLocation(sp,"mx"),LBLMX);GLES30.glUniform1f(GLES30.glGetUniformLocation(sp,"my"),LBLMY);}else{GLES30.glUniform1i(GLES30.glGetUniformLocation(sp,"lblOn"),0);}System.out.println("ENGINE_SPLIT frac="+SPLIT+" divw="+SPLITDIV+" splitx_px="+(SPLIT*outWidth)+" label="+(SPLIT_LABEL?SPLITL+"|"+SPLITR:"off"));}
   frameTex=texture(ow,oh,GLES30.GL_RGBA,GLES30.GL_RGBA8);int[] pf={0};GLES30.glGenFramebuffers(1,pf,0);frameFbo=pf[0];GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,frameFbo);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,frameTex,0);if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw new IOException("Output FBO");frameTarget=frameFbo;System.out.println("ENGINE_OFFSCREEN frame="+ow+"x"+oh);
   if(swapMode>=17){
    int[] mf={0};GLES30.glGenFramebuffers(1,mf,0);midFbo=mf[0];prevTex=texture(ow,oh,GLES30.GL_RGBA,GLES30.GL_RGBA8);int[] qf={0};GLES30.glGenFramebuffers(1,qf,0);prevFbo=qf[0];GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,prevFbo);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,prevTex,0);if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw new IOException("Prev FBO");midProg=program(vertex,text(args[0]+"/mid.frag"));System.out.println("ENGINE_MID_READY blend=0.5 mode="+swapMode);
    if(swapMode>=20){
     motionW=(ow+31)/32;motionH=(oh+31)/32;if(swapMode>=23){motionW=(ow/4+15)/16;motionH=(oh/4+15)/16;}if(swapMode>=26){motionW=(ow/4+31)/32;motionH=(oh/4+31)/32;}if(swapMode>=30){motionW=(ow+63)/64;motionH=(oh+63)/64;}
     int[] nf={0};GLES30.glGenFramebuffers(1,nf,0);motionFbo=nf[0];
     if(swapMode==21){
      int ns=5;motionTexs=new int[ns];
      for(int i=0;i<ns;i++){motionTexs[i]=texture(motionW,motionH,GLES30.GL_RGBA,GLES30.GL_RGBA8);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,motionFbo);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,motionTexs[i],0);if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw new IOException("Motion FBO "+i);GLES30.glClearColor(0f,0f,0f,0f);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);}
     }else{
      motionTex=texture(motionW,motionH,GLES30.GL_RGBA,GLES30.GL_RGBA8);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,motionFbo);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,motionTex,0);if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw new IOException("Motion FBO");
     }
     motionProg=program(vertex,text(args[0]+(swapMode>=32?"/motion-g.frag":swapMode>=31?"/motion-i.frag":swapMode>=30?"/motion-h.frag":swapMode==29?"/motion-g.frag":swapMode==28?"/motion-f.frag":swapMode==27?"/motion-e.frag":swapMode==26?"/motion-d.frag":swapMode==25?"/motion-c.frag":swapMode>=23?"/motion-q.frag":swapMode>=22?"/motion-wide.frag":"/motion.frag")));warpProg=program(vertex,text(args[0]+(swapMode>=36?"/warp-t.frag":swapMode>=35?"/warp-s.frag":swapMode>=34?"/warp-r.frag":swapMode>=23?"/warp-q.frag":"/warp.frag")));if(swapMode>=23)pairProg=program(vertex,text(args[0]+"/pair.frag"));System.out.println("ENGINE_MOTION_READY blocks="+motionW+"x"+motionH+" range="+(swapMode>=37?"coarseTexel+intseed/fine64+-2/med3/a-only+occ16/48/enc127.5":swapMode>=36?"coarse128+fine64+4/2/2/med3/a-only+occ16/48/enc127.5":swapMode>=35?"coarse128+fine64+4/2/1/med3/a-only+occ16/48":swapMode>=34?"coarse128+fine64+4/2/1/med3/a-only":swapMode>=33?"coarse128+fine64+4/2/1/med3":swapMode>=32?"coarse128+fine64+4/2/1/confz":swapMode>=31?"4+9t/1px/64px/span64/confz":swapMode>=30?"4+9t/1px/64px/confz":swapMode==29?"4+9t/1px/aligned/confz":swapMode==28?"4+9t/1px/aligned":swapMode==27?"20q/lean/direct":swapMode==26?"20q/4t/direct":swapMode==25?"20q/4t":swapMode>=23?"56q":swapMode>=22?"56":"6")+" side="+((swapMode==21||swapMode==24)?"render":"presenter")+" mode="+swapMode);
     if(swapMode>=32){coarseW=(ow+127)/128;coarseH=(oh+127)/128;coarseTex=texture(coarseW,coarseH,GLES30.GL_RGBA,GLES30.GL_RGBA8);refineProg=program(vertex,text(args[0]+(swapMode>=37?"/motion-l.frag":swapMode>=36?"/motion-k.frag":"/motion-j.frag")));System.out.println("ENGINE_COARSE_READY size="+coarseW+"x"+coarseH);}
     if(swapMode>=33){medianTex=texture(motionW,motionH,GLES30.GL_RGBA,GLES30.GL_RGBA8);medianProg=program(vertex,text(args[0]+"/median.frag"));System.out.println("ENGINE_MEDIAN_READY size="+motionW+"x"+motionH);}
     if(swapMode>=24){
      qw=ow/4;qh=oh/4;pairTex=texture(2*qw,qh,GLES30.GL_RGBA,GLES30.GL_RGBA8);int[] pfb={0};GLES30.glGenFramebuffers(1,pfb,0);pairFbo=pfb[0];GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,pairFbo);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,pairTex,0);if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw new IOException("Pair FBO");System.out.println("ENGINE_PAIR_FBO size="+(2*qw)+"x"+qh+" side=render");
     }
    }else if(swapMode>=19){mixProg=program(vertex,text(args[0]+"/present-mix.frag"));System.out.println("ENGINE_MIX_READY presenter_side=true");}
   }
   if(swapMode==4||swapMode>=17){
    int ns=swapMode==24?7:(swapMode>=17?5:3);frameTexs=new int[ns];frameFbos=new int[ns];int[] fb3=new int[ns];GLES30.glGenFramebuffers(ns,fb3,0);
    if(swapMode==24)slotMid=new boolean[ns];
    for(int i=0;i<ns;i++){frameTexs[i]=texture(ow,oh,GLES30.GL_RGBA,GLES30.GL_RGBA8);frameFbos[i]=fb3[i];GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,frameFbos[i]);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,frameTexs[i],0);if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw new IOException("Presenter FBO "+i);}
    int[] pv={0};GLES30.glGenVertexArrays(1,pv,0);presentVao=pv[0];
    System.out.println("ENGINE_PRESENTER_READY textures="+ns+" shared_context=true vao="+presentVao);
   }
   locDi=GLES30.glGetUniformLocation(convert,"diMode");locMotion=GLES30.glGetUniformLocation(convert,"motionThr");locEdge=GLES30.glGetUniformLocation(convert,"edgeGain");locSharp=GLES30.glGetUniformLocation(upscale,"sharpen");loadSettings();System.out.println("ENGINE_SETTINGS_INIT di="+diMode+" motion="+motionThr+" edge="+edgeGain+" sharp="+sharpen+" swap="+swapMode);
   if("1".equals(System.getProperty("x90.si0"))&&swapMode<9){if(!EGL14.eglMakeCurrent(display,window,window,context))throw new IOException("si0 current");boolean ok=EGL14.eglSwapInterval(display,0);System.out.println("ENGINE_SWAP_INTERVAL0 single_layer ok="+ok);}
   if("1".equals(System.getProperty("x90.direct"))&&swapMode==1&&!timing){DIRECT=true;frameTarget=0;System.out.println("ENGINE_DIRECT_WINDOW_RENDER copy_pass=off");}
   if("1".equals(System.getProperty("x90.gpuprof"))){String ex=GLES30.glGetString(GLES30.GL_EXTENSIONS);GPUPROF=ex!=null&&ex.contains("GL_EXT_disjoint_timer_query");if(GPUPROF)GLES30.glGenQueries(4,gq,0);System.out.println("ENGINE_GPUPROF available="+GPUPROF);}
   if(false){}
   else if("1".equals(System.getProperty("x90.direct"))&&swapMode==1&&DIRECT){}
   else if("1".equals(System.getProperty("x90.direct"))&&swapMode==1){DIRECT=true;frameTarget=0;System.out.println("ENGINE_DIRECT_WINDOW_RENDER copy_pass=off timing=on");}
   if("1".equals(System.getProperty("x90.pace"))&&swapMode==1){PACE=true;String d=System.getProperty("x90.paceD");if(d!=null)paceD=Long.parseLong(d)*1_000_000L;startVsyncClock();System.out.println("ENGINE_PACE on D_ms="+paceD/1e6+" H_ms="+paceH/1e6);}
   if(swapMode==4){presenterThread=new Thread(()->presenter(),"x90-presenter");presenterThread.setDaemon(true);presenterThread.start();}
   if(swapMode>=17){startVsyncClock();presenterThread=new Thread(()->presenter2(),"x90-presenter2");presenterThread.setDaemon(true);presenterThread.start();}
   if(SPLIT>0f){DIRECT=false;frameTarget=frameFbo;System.out.println("ENGINE_SPLIT_FORCE_COPY frac="+SPLIT);}
   if(LATCH&&swapMode==1){startVsyncClock();System.out.println("ENGINE_LATCH_ENABLED margin_ms="+(latchMarginNs/1e6));}
   long start=System.nanoTime(),deadline=seconds==0?Long.MAX_VALUE:start+seconds*1_000_000_000L,rendered=0,first=0,last=0;double age=0,maxAge=0,draw=0,maxDraw=0;
   long controlChecked=0;boolean surfaceHidden=false;Frame pending=null;int clockBad=0;
   while(!stopping&&System.nanoTime()<deadline){
    if(seconds==0&&new File(args[0]+"/stop").length()>0){System.out.println("ENGINE_USER_STOP");break;}
    if(seconds==0&&System.nanoTime()-controlChecked>250_000_000L){
     File menu=new File("/data/local/tests/x90-engine-menu-visible");
     boolean hide=new File(args[0]+"/ui-hidden").length()>0||(menu.length()>0&&Math.abs(System.currentTimeMillis()-menu.lastModified())<6000);
     loadSettings();
     File swapFile=new File("/data/local/tests/x90-engine-swap");
     if(swapFile.isFile())try{String sv=text("/data/local/tests/x90-engine-swap").trim();int gap=swapGapMs,sm;int comma=sv.indexOf(',');
      if(comma>=0){sm=Math.max(1,Math.min(37,Integer.parseInt(sv.substring(0,comma))));gap=Math.max(0,Math.min(50,Integer.parseInt(sv.substring(comma+1))));}else sm=Math.max(1,Math.min(37,Integer.parseInt(sv)));
      if(sm!=swapMode||gap!=swapGapMs){
      System.out.printf(java.util.Locale.US,"ENGINE_PHASE_END swap=%d gap=%d iters=%d poll_mean_ms=%.3f draw_mean_ms=%.3f draw2_mean_ms=%.3f present1_mean_ms=%.3f present2_mean_ms=%.3f loop_mean_ms=%.3f pre_mean_ms=%.3f pre_max_ms=%.3f cap_mean_ms=%.3f cut_mean_ms=%.3f%n",swapMode,swapGapMs,iters,pollSum/Math.max(1,iters),drawSum/Math.max(1,iters),draw2Sum/Math.max(1,iters),p1Sum/Math.max(1,iters),p2Sum/Math.max(1,iters),loopSum/Math.max(1,iters),preSum/Math.max(1,iters),preMax,capSum/Math.max(1,iters),cutSum/Math.max(1,iters));
      pollSum=0;drawSum=0;draw2Sum=0;p1Sum=0;p2Sum=0;loopSum=0;preSum=0;preMax=0;capSum=0;cutSum=0;iters=0;if(sm<9)try{EGL14.eglMakeCurrent(display,window,window,context);}catch(Throwable ignored){}swapMode=sm;swapGapMs=gap;System.out.println("ENGINE_SWAP_MODE "+sm+" gap="+gap);}}catch(Exception ignored){}
     if(hide!=surfaceHidden){Object tx=transaction();op(tx,hide?"hide":"show",new Class<?>[]{sc},control);op(tx,"apply",new Class<?>[]{});surfaceHidden=hide;if(hide&&pending!=null){free.offer(pending);pending=null;}clockBad=0;}
     controlChecked=System.nanoTime();
    }
    if(surfaceHidden){Frame stale=latest.poll(50,TimeUnit.MILLISECONDS);if(stale!=null){dropped++;free.offer(stale);}continue;}
    if(LATCH&&swapMode==1){
     long iterBeganL=System.nanoTime();
     if(new File("/data/local/tests/x90-engine-capture-one").isFile())captureRequest=true;
     Frame got=latest.poll(pending==null?1500:150,TimeUnit.MILLISECONDS);
     if(got==null){
      if(readerError!=null){if(readerError instanceof EOFException&&rendered>0){System.out.println("ENGINE_STREAM_EOF");break;}throw new IOException("Reader",readerError);}
      if(pending==null)throw new IOException("No input frame");
      if(timing)throw new IOException("Timing drain unsupported");
      draw(pending,null,false,false,rendered);rendered++;free.offer(pending);pending=null;continue;
     }
     if(pending!=null)free.offer(pending);pending=got;latchNew++;
     long dL0=System.nanoTime();draw(pending,null,false,false,rendered);drawSum+=(System.nanoTime()-dL0)/1e6;
     long P=vsyncPeriodNs>4_000_000L?vsyncPeriodNs:16_666_666L,v=vsyncNs;
     if(v>0){long now=System.nanoTime();long k=(now-v)/P+1;long wake=v+k*P-latchMarginNs;if(wake>now)parkUntil(wake);else latchSkipPark++;}
     long frameIdL=timing?timingNext():0;long sL0=System.nanoTime();present();p1Sum+=(System.nanoTime()-sL0)/1e6;
     long nowL=System.nanoTime();if(timing)timingSubmitted(frameIdL,pending.sequence,pending.completed,nowL);
     swaps++;if(swapFirst==0)swapFirst=nowL;swapLast=nowL;presents++;
     double atL=(nowL-pending.completed)/1e6;if(atL<0||atL>5000){if(++clockBad>30)throw new IOException("Clock/latency invalid at="+atL+" received="+received);atL=0;}else clockBad=0;
     age+=atL;maxAge=Math.max(maxAge,atL);{double dtL=(nowL-iterBeganL)/1e6;draw+=dtL;maxDraw=Math.max(maxDraw,dtL);loopSum+=dtL;}if(first==0)first=nowL;last=nowL;rendered++;iters++;
     if(rendered%120==0)System.out.printf(java.util.Locale.US,"ENGINE_LATCH rendered=%d new=%d repeat=%d skippark=%d age_ms=%.2f P_ms=%.3f margin_ms=%.2f%n",rendered,latchNew,latchRepeat,latchSkipPark,atL,P/1e6,latchMarginNs/1e6);
     free.offer(pending);pending=null;continue;
    }
    if(swapMode==6){
     long id=timing?timingNext():0,t0=System.nanoTime();present();long t1=System.nanoTime();if(timing)timingSubmitted(id,0,0,t1);
     swaps++;if(swapFirst==0)swapFirst=t1;swapLast=t1;presents++;double b=(t1-t0)/1e6;presBlockSum+=b;if(b>presBlockMax)presBlockMax=b;iters++;if(iters%120==0)System.out.println("ENGINE_PRESENT_PROGRESS pps="+(iters*1e9/Math.max(1,t1-swapFirst))+" block="+b);
     continue;
    }
    long iterBegan=System.nanoTime();
    if(new File("/data/local/tests/x90-engine-capture-one").isFile())captureRequest=true;
    if(new File("/data/local/tests/x90-engine-capture-mid").isFile())midProofRequest=true;
    if(new File("/data/local/tests/x90-engine-capture-motion").isFile())motionProofRequest=true;
    if(!presentCaptureRequest&&new File("/data/local/tests/x90-engine-capture-present").isFile()){new File("/data/local/tests/x90-engine-capture-present").delete();presentCaptureRequest=true;}
    if(forceCoalesce)Thread.sleep(40);
    long tA=System.nanoTime();capSum+=(tA-iterBegan)/1e6;
    long pollBegan=System.nanoTime();
    Frame incoming=latest.poll(pending==null?1500:150,TimeUnit.MILLISECONDS);
    pollSum+=(System.nanoTime()-pollBegan)/1e6;
    long tB=System.nanoTime();
    if(incoming==null){
     if(readerError!=null){
      if(readerError instanceof EOFException&&rendered>0){System.out.println("ENGINE_STREAM_EOF");break;}
      throw new IOException("Reader",readerError);
     }
     if(pending==null)throw new IOException("No input frame");
     if(timing)throw new IOException("Timing drain unsupported");
     draw(pending,null,false,false,rendered);rendered++;free.offer(pending);pending=null;continue;
    }
    if(LOW_LATENCY){if(pending!=null)free.offer(pending);pending=incoming;incoming=null;}
    else if(pending==null){pending=incoming;continue;}
    boolean adjacent=incoming!=null&&incoming.sequence==pending.sequence+1&&incoming.parity!=pending.parity&&pending.stable&&incoming.stable;
    boolean futureValid=adjacent&&pending.history.valid;
    boolean cut=false;
    if(futureValid){cut=meanAbsDiff(incoming.pixels,pending.history.previous[0],stride*height)>25;}
    {long tC=System.nanoTime();cutSum+=(tC-tB)/1e6;}
    int slot=-1;
    if(swapMode==4||swapMode>=17){try{slot=texSpare.take();}catch(InterruptedException ie){throw new IOException("Presenter interrupted",ie);}frameTarget=frameFbos[slot];}
    long frameId=(timing&&swapMode!=4&&swapMode<9)?timingNext():0;long began=System.nanoTime();{double p=(began-iterBegan)/1e6;preSum+=p;if(p>preMax)preMax=p;}
    if(futureValid)vtFrames++;if(cut)cutFrames++;
    long d0=System.nanoTime();draw(pending,incoming,futureValid,cut,rendered);drawSum+=(System.nanoTime()-d0)/1e6;
    if(swapMode==4){GLES30.glFinish();presSeq=pending.sequence;try{texReady.put(slot);}catch(InterruptedException ie){throw new IOException("Presenter interrupted",ie);}}
    else if(swapMode==9||swapMode==10){long s0=System.nanoTime();presentLayer(0);long s1=System.nanoTime();long fid2=timing?timingNext():0;presentLayer(1);long s2=System.nanoTime();if(timing)timingSubmitted(fid2,pending.sequence,pending.completed,s2);p1Sum+=(s1-s0)/1e6;p2Sum+=(s2-s1)/1e6;{long sn=s2;swaps+=2;if(swapFirst==0)swapFirst=sn;swapLast=sn;}}
    else if(swapMode==11){
     long now0=System.nanoTime();
     if(timing&&lastFidA>0){long l=timingLatch(lastFidA);if(l>0)vsyncAnchor=l;else latchMiss++;}
     long target0=0;
     if(now0-vsyncAnchor>2_000_000_000L)vsyncAnchor=0;
     if(vsyncAnchor>0){long margin=2_000_000L;long ahead=now0+margin-vsyncAnchor;long k=(ahead+periodNs-1)/periodNs;if(ahead<0)k=0;target0=vsyncAnchor+k*periodNs+margin;}
     if(target0>0){schedRequests++;if(timingScheduleOn(window.getNativeHandle(),target0))schedOk++;else schedFail++;}
     long fidA=timing?timingNext():0;
     long s0=System.nanoTime();presentLayer(0);long s1=System.nanoTime();
     long target1=target0>0?target0+periodNs:0;
     if(target1>0){schedRequests++;if(timingScheduleOn(window2.getNativeHandle(),target1))schedOk++;else schedFail++;}
     long fidB=timing?timingNextOn(window2.getNativeHandle()):0;
     presentLayer(1);long s2=System.nanoTime();
     if(timing){if(fidA>0)timingSubmitted(fidA,pending.sequence,pending.completed,s1);if(fidB>0)timingSubmittedOn(fidB,pending.sequence,pending.completed,s2,window2.getNativeHandle());lastFidA=fidA;lastFidB=fidB;}
     p1Sum+=(s1-s0)/1e6;p2Sum+=(s2-s1)/1e6;{long sn=s2;swaps+=2;if(swapFirst==0)swapFirst=sn;swapLast=sn;}
    }
    else if(swapMode==12||swapMode==13){
     boolean twoLayers=swapMode==13;
     long now0=System.nanoTime();
     if(timing){long a=timingAnchor();if(a>0)vsyncAnchor=a;}
     long G=0;
     if(vsyncAnchor>0){long lead=4_500_000L;long ahead=now0+lead-vsyncAnchor;long k=(ahead+periodNs-1)/periodNs;if(ahead<0)k=0;G=vsyncAnchor+k*periodNs;}
     if(G>0)parkUntil(G-2_000_000L);
     long fidA=timing?timingNext():0;
     long s0=System.nanoTime();if(twoLayers)presentLayer(0);else present();long s1=System.nanoTime();
     if(G>0)parkUntil(G+periodNs-2_000_000L);
     long fidB=timing?(twoLayers?timingNextOn(window2.getNativeHandle()):timingNext()):0;
     long s2=System.nanoTime();if(twoLayers)presentLayer(1);else present();long s3=System.nanoTime();
     if(timing){if(fidA>0)timingSubmitted(fidA,pending.sequence,pending.completed,s1);if(fidB>0){if(twoLayers)timingSubmittedOn(fidB,pending.sequence,pending.completed,s3,window2.getNativeHandle());else timingSubmitted(fidB,pending.sequence,pending.completed,s3);}}
     p1Sum+=(s1-s0)/1e6;p2Sum+=(s3-s2)/1e6;{long sn=s3;swaps+=2;if(swapFirst==0)swapFirst=sn;swapLast=sn;}
    }
    else if(swapMode==14){
     long fid0=timing?timingNext():0,s0=System.nanoTime();
     presentOnly(0,frameTex);flipTo(0);
     long w0=timing?waitChain(window.getNativeHandle(),fid0):0;
     long s1=System.nanoTime();
     long fid1=timing?timingNextOn(window2.getNativeHandle()):0;
     presentOnly(1,frameTex);flipTo(1);
     long w1=timing?waitChain(window2.getNativeHandle(),fid1):0;
     long s2=System.nanoTime();
     if(timing){if(fid0>0)timingSubmitted(fid0,pending.sequence,pending.completed,s1);if(fid1>0)timingSubmittedOn(fid1,pending.sequence,pending.completed,s2,window2.getNativeHandle());}
     if(w0>0){chainW0++;chainW0Sum+=w0/1e6;}if(w1>0){chainW1++;chainW1Sum+=w1/1e6;}
     p1Sum+=(s1-s0)/1e6;p2Sum+=(s2-s1)/1e6;{long sn=s2;swaps+=2;if(swapFirst==0)swapFirst=sn;swapLast=sn;}
    }
    else if(swapMode==15){
     long lead=(swapGapMs>0?swapGapMs:7)*1_000_000L;
     if(timing&&(iters&7)==0){long a=timingAnchor();if(a>0)gridAnchor=a;}
     long now0=System.nanoTime(),G=0;
     if(gridAnchor>0){long ahead=now0+lead-gridAnchor;long k=(ahead+periodNs-1)/periodNs;if(ahead<0)k=0;G=gridAnchor+k*periodNs;}
     long fidA=timing?timingNext():0;
     if(G>0)parkUntil(G-lead);
     long s0=System.nanoTime();presentOnly(0,frameTex);flipTo(0);long s1=System.nanoTime();
     long fidB=timing?timingNextOn(window2.getNativeHandle()):0;
     if(G>0)parkUntil(G+periodNs-lead);
     presentOnly(1,frameTex);flipTo(1);long s2=System.nanoTime();
     if(timing){if(fidA>0)timingSubmitted(fidA,pending.sequence,pending.completed,s1);if(fidB>0)timingSubmittedOn(fidB,pending.sequence,pending.completed,s2,window2.getNativeHandle());}
     p1Sum+=(s1-s0)/1e6;p2Sum+=(s2-s1)/1e6;{long sn=s2;swaps+=2;if(swapFirst==0)swapFirst=sn;swapLast=sn;}
    }
    else if(swapMode==16){
     long margin=(swapGapMs>0?swapGapMs:2)*1_000_000L;
     if(timing&&(iters&7)==0){long a=timingAnchor();if(a>0)gridAnchor=a;}
     long now0=System.nanoTime(),G=0;
     if(gridAnchor>0){long k=(now0-gridAnchor)/periodNs+1;if(k<1)k=1;G=gridAnchor+k*periodNs;}
     long fidA=timing?timingNext():0;
     if(G>0)parkUntil(G+margin);
     long s0=System.nanoTime();presentOnly(0,frameTex);flipTo(0);long s1=System.nanoTime();
     long fidB=timing?timingNextOn(window2.getNativeHandle()):0;
     if(G>0)parkUntil(G+periodNs+margin);
     presentOnly(1,frameTex);flipTo(1);long s2=System.nanoTime();
     if(timing){if(fidA>0)timingSubmitted(fidA,pending.sequence,pending.completed,s1);if(fidB>0)timingSubmittedOn(fidB,pending.sequence,pending.completed,s2,window2.getNativeHandle());}
     p1Sum+=(s1-s0)/1e6;p2Sum+=(s2-s1)/1e6;{long sn=s2;swaps+=2;if(swapFirst==0)swapFirst=sn;swapLast=sn;}
    }
    else if(swapMode>=17){
     int midSlot=-1;long m0=System.nanoTime();
     if(swapMode==18&&prevValid){long tk=System.nanoTime();try{midSlot=texSpare.take();}catch(InterruptedException ie){throw new IOException("Presenter interrupted",ie);}midTakeSum+=(System.nanoTime()-tk)/1e6;
      long bl=System.nanoTime();GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,midFbo);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,frameTexs[midSlot],0);
      GLES30.glViewport(0,0,outWidth,outHeight);GLES30.glUseProgram(midProg);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,prevTex);GLES30.glUniform1i(GLES30.glGetUniformLocation(midProg,"prev"),0);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[slot]);GLES30.glUniform1i(GLES30.glGetUniformLocation(midProg,"cur"),1);
      GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);midFrames++;midBlendSum+=(System.nanoTime()-bl)/1e6;
      if(midProofRequest){
       midProofRequest=false;new File("/data/local/tests/x90-engine-capture-mid").delete();
       ByteBuffer real=ByteBuffer.allocateDirect(outWidth*outHeight*4),mid=ByteBuffer.allocateDirect(outWidth*outHeight*4);
       GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,frameFbos[slot]);GLES30.glReadPixels(0,0,outWidth,outHeight,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,real);
       GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,midFbo);GLES30.glReadPixels(0,0,outWidth,outHeight,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,mid);
       long sum=0;int n=real.capacity()/16;for(int i=0;i<n;i++){int p=i*16;sum+=Math.abs((real.get(p)&255)-(mid.get(p)&255));}midMae=sum/(double)Math.max(1,n);
       try(FileOutputStream o1=new FileOutputStream(sessionDir+"/capture-real-"+pending.sequence+".rgba")){real.position(0);while(real.hasRemaining())o1.getChannel().write(real);}
       try(FileOutputStream o2=new FileOutputStream(sessionDir+"/capture-mid-"+pending.sequence+".rgba")){mid.position(0);while(mid.hasRemaining())o2.getChannel().write(mid);}
       System.out.printf(java.util.Locale.US,"ENGINE_MID_PROOF seq=%d mae=%.3f size=%dx%d%n",pending.sequence,midMae,outWidth,outHeight);
      }
     }
     if(swapMode==18){
      long cp=System.nanoTime();GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,prevFbo);GLES30.glViewport(0,0,outWidth,outHeight);GLES30.glUseProgram(presentProg);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[slot]);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);midCopySum+=(System.nanoTime()-cp)/1e6;
     }
     if(swapMode==21&&lastRenderSlot>=0){
      long me=System.nanoTime();GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,motionFbo);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,motionTexs[slot],0);
      GLES30.glViewport(0,0,motionW,motionH);GLES30.glUseProgram(motionProg);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[lastRenderSlot]);GLES30.glUniform1i(GLES30.glGetUniformLocation(motionProg,"prev"),0);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[slot]);GLES30.glUniform1i(GLES30.glGetUniformLocation(motionProg,"cur"),1);
      GLES30.glUniform2f(GLES30.glGetUniformLocation(motionProg,"frameSize"),outWidth,outHeight);
      GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
      motionFrames++;motionEstSum+=(System.nanoTime()-me)/1e6;
     }
     if(swapMode==24&&lastRenderSlot>=0){
      long me=System.nanoTime();
      if(midSlot<0)try{midSlot=texSpare.take();}catch(InterruptedException ie){throw new IOException("Presenter interrupted",ie);}
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,pairFbo);GLES30.glViewport(0,0,2*qw,qh);GLES30.glUseProgram(pairProg);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[lastRenderSlot]);GLES30.glUniform1i(GLES30.glGetUniformLocation(pairProg,"a"),0);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[slot]);GLES30.glUniform1i(GLES30.glGetUniformLocation(pairProg,"b"),1);
      GLES30.glUniform2f(GLES30.glGetUniformLocation(pairProg,"outSize"),2*qw,qh);
      GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,motionFbo);GLES30.glViewport(0,0,motionW,motionH);GLES30.glUseProgram(motionProg);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,pairTex);GLES30.glUniform1i(GLES30.glGetUniformLocation(motionProg,"pair"),0);
      GLES30.glUniform2i(GLES30.glGetUniformLocation(motionProg,"curOff"),qw,0);
      GLES30.glUniform2i(GLES30.glGetUniformLocation(motionProg,"sizeQ"),qw,qh);
      GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,frameFbos[midSlot]);GLES30.glViewport(0,0,outWidth,outHeight);GLES30.glUseProgram(warpProg);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[lastRenderSlot]);GLES30.glUniform1i(GLES30.glGetUniformLocation(warpProg,"a"),0);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frameTexs[slot]);GLES30.glUniform1i(GLES30.glGetUniformLocation(warpProg,"b"),1);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE2);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,motionTex);GLES30.glUniform1i(GLES30.glGetUniformLocation(warpProg,"m"),2);
      GLES30.glUniform2f(GLES30.glGetUniformLocation(warpProg,"frameSize"),outWidth,outHeight);
      GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
      motionFrames++;motionEstSum+=(System.nanoTime()-me)/1e6;
     }
     long fn=System.nanoTime();GLES30.glFinish();midFinishSum+=(System.nanoTime()-fn)/1e6;slotSeq[slot]=pending.sequence;presSeq=pending.sequence;
     if(swapMode==24){
      slotMid[slot]=false;if(midSlot>=0)slotMid[midSlot]=true;
      try{if(midSlot>=0)texReady.put(midSlot);texReady.put(slot);}catch(InterruptedException ie){throw new IOException("Presenter interrupted",ie);}
      midSlot=-1;
     }else try{if(midSlot>=0)texReady.put(midSlot);texReady.put(slot);}catch(InterruptedException ie){throw new IOException("Presenter interrupted",ie);}
     lastRenderSlot=slot;
     prevValid=true;p1Sum+=(System.nanoTime()-m0)/1e6;
    }
    else{if(PACE)pacePresent(pending.completed);long s0=System.nanoTime();present();p1Sum+=(System.nanoTime()-s0)/1e6;{long sn=System.nanoTime();swaps++;if(swapFirst==0)swapFirst=sn;swapLast=sn;}}
    if(swapMode>=2&&swapMode<17&&swapMode!=4&&swapMode!=6&&swapMode!=8&&swapMode!=9&&swapMode!=10&&swapMode!=11&&swapMode!=12&&swapMode!=13&&swapMode!=14&&swapMode!=15&&swapMode!=16){if(swapGapMs>0)LockSupport.parkNanos(swapGapMs*1_000_000L);long b2=System.nanoTime();if(swapMode>=3){long dd0=System.nanoTime();draw(pending,incoming,futureValid,cut,rendered);draw2Sum+=(System.nanoTime()-dd0)/1e6;}long frameId2=timing?timingNext():0;long s2=System.nanoTime();present();p2Sum+=(System.nanoTime()-s2)/1e6;long e2=System.nanoTime();if(timing)timingSubmitted(frameId2,pending.sequence,pending.completed,e2);double d2=(e2-b2)/1e6;swap2Sum+=d2;swap2Max=Math.max(swap2Max,d2);swaps++;swapLast=e2;}
    long now=System.nanoTime();if(timing&&swapMode<9)timingSubmitted(frameId,pending.sequence,pending.completed,now);double dt=(now-began)/1e6,at=(now-pending.completed)/1e6;loopSum+=(now-iterBegan)/1e6;iters++;if(at<0||at>5000){if(++clockBad>30)throw new IOException("Clock/latency invalid at="+at+" received="+received);System.out.println("ENGINE_CLOCK_SKIP at="+at+" received="+received);at=0;}else clockBad=0;age+=at;maxAge=Math.max(maxAge,at);draw+=dt;maxDraw=Math.max(maxDraw,dt);if(first==0)first=now;last=now;rendered++;free.offer(pending);pending=incoming;
    if(rendered%60==0)System.out.printf(java.util.Locale.US,"ENGINE_PROGRESS ms=%d rendered=%d received=%d dropped=%d age_ms=%.3f vt=%d cut=%d swaps=%d swap_mode=%d poll=%.3f draw=%.3f p1=%.3f p2=%.3f%n",System.currentTimeMillis(),rendered,received,dropped,at,vtFrames,cutFrames,swaps,swapMode,pollSum/Math.max(1,iters),drawSum/Math.max(1,iters),p1Sum/Math.max(1,iters),p2Sum/Math.max(1,iters));
   }
   if(GPUPROF)System.out.printf(java.util.Locale.US,"ENGINE_GPU_PROFILE frames=%d di_ms=%.3f upscale_ms=%.3f%n",gN,gDi/Math.max(1,gN),gUp/Math.max(1,gN));
   if(PACE)System.out.println("ENGINE_PACE_RESULT set="+paceSet+" slip_late="+paceSlipLate+" slip_early="+paceSlipEarly+" vsync_ticks="+vsyncTicks);
   System.out.println("FIELD_DI_RESULT vt_guarded_frames="+vtFrames+" cut_frames="+cutFrames+" adaptive_history_frames="+adaptiveFrames+" spatial_fallback_frames="+spatialFrames+" delayed_field=true");
   System.out.printf(java.util.Locale.US,"ENGINE_RESULT rendered=%d received=%d dropped=%d rendered_fps=%.3f mean_submit_ms=%.3f max_submit_ms=%.3f mean_capture_to_swap_ms=%.3f max_capture_to_swap_ms=%.3f output=%dx%d content=%dx%d source=%dx%d final_panel_latency_measured=false%n",rendered,received,dropped,rendered>1?(rendered-1)*1e9/(last-first):0,draw/Math.max(1,rendered),maxDraw,age/Math.max(1,rendered),maxAge,ow,oh,contentWidth,contentHeight,width,height);
   System.out.printf(java.util.Locale.US,"ENGINE_SWAPS swaps=%d swap_fps=%.3f swap_mode=%d second_block_mean_ms=%.3f second_block_max_ms=%.3f%n",swaps,swaps>1?(swaps-1)*1e9/(swapLast-swapFirst):0,swapMode,swap2Sum/Math.max(1,swaps-rendered),swap2Max);
   System.out.printf(java.util.Locale.US,"ENGINE_PRESENTER presents=%d skipped=%d block_mean_ms=%.3f block_max_ms=%.3f%n",presents,presSkipped,presents>0?presBlockSum/presents:0,presBlockMax);
   System.out.printf(java.util.Locale.US,"ENGINE_PRESENTER2 new=%d repeats=%d missed_vsync=%d ticks=%d mid=%d real=%d pair_skipped=%d%n",presNewFrame,presRepeats,presMissed,vsyncTicks,presMidCount,presRealNew,pairSkipped);
   System.out.printf(java.util.Locale.US,"ENGINE_MID frames=%d mae=%.3f take_mean_ms=%.3f blend_mean_ms=%.3f copy_mean_ms=%.3f finish_mean_ms=%.3f%n",midFrames,midMae,midTakeSum/Math.max(1,midFrames),midBlendSum/Math.max(1,midFrames),midCopySum/Math.max(1,midFrames),midFinishSum/Math.max(1,midFrames));
   System.out.printf(java.util.Locale.US,"ENGINE_MOTION frames=%d submit_mean_ms=%.3f%n",motionFrames,motionEstSum/Math.max(1,motionFrames));
   System.out.printf(java.util.Locale.US,"ENGINE_SCHED requests=%d ok=%d fail=%d latch_miss=%d anchor=%d period_ns=%d%n",schedRequests,schedOk,schedFail,latchMiss,vsyncAnchor,periodNs);
   System.out.printf(java.util.Locale.US,"ENGINE_CHAIN waits=%d miss=%d w0_mean_ms=%.3f w1_mean_ms=%.3f w_max_ms=%.3f%n",chainWaits,chainMiss,chainW0>0?chainW0Sum/chainW0:0,chainW1>0?chainW1Sum/chainW1:0,chainWaitMax/1e6);
   System.out.printf(java.util.Locale.US,"ENGINE_BREAKDOWN iters=%d poll_mean_ms=%.3f draw_mean_ms=%.3f draw2_mean_ms=%.3f present1_mean_ms=%.3f present2_mean_ms=%.3f loop_mean_ms=%.3f pre_mean_ms=%.3f pre_max_ms=%.3f%n",iters,pollSum/Math.max(1,iters),drawSum/Math.max(1,iters),draw2Sum/Math.max(1,iters),p1Sum/Math.max(1,iters),p2Sum/Math.max(1,iters),loopSum/Math.max(1,iters),preSum/Math.max(1,iters),preMax);result=0;
  }catch(Throwable x){x.printStackTrace();}
  finally{stopping=true;if(presenterThread!=null){try{presenterThread.join(3000);}catch(InterruptedException ignored){}}if(timing)try{timingFinish();}catch(Throwable ignored){}try{if(socket!=null)socket.close();if(reader!=null)reader.join(2000);}catch(Exception ignored){}if(display!=EGL14.EGL_NO_DISPLAY){EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(pbuffer!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,pbuffer);if(window2!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,window2);if(window!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,window);if(presentContext!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,presentContext);if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);}if(surface2!=null)surface2.release();if(control2!=null)try{Object t=transaction();op(t,"remove",new Class<?>[]{sc},control2);op(t,"apply",new Class<?>[]{});}catch(Exception ignored){}if(surface!=null)surface.release();remove();}
  System.out.println("ENGINE_EXIT="+result);System.exit(result);
 }
}

