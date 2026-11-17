import android.graphics.*;
import android.view.Surface;

/** x90shot validation pattern (2026-11-16): bounded full-screen opaque Surface,
 * 1920x1080 logical (HWC scales 2x to the 3840x2160 panel), 8x8-logical cells =
 * 16x16 native pixels, colour = 3-bit hash of (cell column, cell row) mapped to
 * pure R/G/B corners. Removes itself after SECONDS (2..1800). No video/PQ/display
 * settings are touched. Expected colour of native pixel (X,Y):
 *   cx=X/16, cy=Y/16, h=(cx*73856093)^(cy*19349663)^0x1b873593, h^=h>>>13, bits=h&7
 *   R=bit0 G=bit1 B=bit2. */
public class X90ShotPattern {
 static Class<?> sc,tc;static Object control;
 static void op(Object t,String n,Class<?>[] types,Object...args)throws Exception{tc.getMethod(n,types).invoke(t,args);}
 public static void main(String[] args)throws Exception{
  int seconds=Integer.parseInt(args[0]);if(seconds<2||seconds>1800)throw new IllegalArgumentException("seconds 2..1800");
  Surface s=null;
  try{
   sc=Class.forName("android.view.SurfaceControl");tc=Class.forName("android.view.SurfaceControl$Transaction");Class<?> bc=Class.forName("android.view.SurfaceControl$Builder");Object b=bc.getConstructor().newInstance();
   bc.getMethod("setName",String.class).invoke(b,"X90Shot-Pattern");bc.getMethod("setBufferSize",int.class,int.class).invoke(b,1920,1080);bc.getMethod("setFormat",int.class).invoke(b,1);bc.getMethod("setOpaque",boolean.class).invoke(b,true);control=bc.getMethod("build").invoke(b);
   s=Surface.class.getConstructor(sc).newInstance(control);Canvas c=s.lockCanvas(null);Paint p=new Paint();p.setAntiAlias(false);
   for(int cy=0;cy<135;cy++)for(int cx=0;cx<240;cx++){
    int h=(cx*73856093)^(cy*19349663)^0x1b873593;h^=h>>>13;int bits=h&7;
    p.setColor(Color.rgb((bits&1)!=0?255:0,(bits&2)!=0?255:0,(bits&4)!=0?255:0));
    c.drawRect(cx*8,cy*8,cx*8+8,cy*8+8,p);
   }
   s.unlockCanvasAndPost(c);
   Object t=tc.getConstructor().newInstance();op(t,"setLayerStack",new Class<?>[]{sc,int.class},control,0);op(t,"setLayer",new Class<?>[]{sc,int.class},control,2000000);op(t,"show",new Class<?>[]{sc},control);op(t,"apply",new Class<?>[]{});
   System.out.println("X90SHOT_PATTERN_READY logical=1920x1080 cell=8 native_cell=16 seed=0x1b873593 seconds="+seconds);System.out.flush();
   long end=System.currentTimeMillis()+seconds*1000L;
   while(System.currentTimeMillis()<end){if(new java.io.File("/data/local/tests/x90shot/pattern-stop").exists())break;Thread.sleep(200);}
  }finally{
   if(control!=null){Object t=tc.getConstructor().newInstance();op(t,"remove",new Class<?>[]{sc},control);op(t,"apply",new Class<?>[]{});sc.getMethod("release").invoke(control);System.out.println("X90SHOT_PATTERN_REMOVED");}
   if(s!=null)s.release();
  }
 }
}
