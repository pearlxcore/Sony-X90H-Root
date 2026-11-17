import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** SD live JPEG bridge. Android's native JPEG codec, no vendor codec allocation.
 * Capture remains in the separately owned native client. Latest-frame queue
 * prevents slow network/encoding from stalling that client's capture loop. */
public final class X90LiveJpeg {
    static volatile boolean stop;
    static volatile Socket input,output;
    static volatile ServerSocket server;
    static final AtomicReference<Throwable> failure=new AtomicReference<>();
    static final AtomicLong received=new AtomicLong(),dropped=new AtomicLong();
    static final byte[] yTable=new byte[256],cTable=new byte[256];
    static {for(int i=0;i<256;i++){
        yTable[i]=(byte)Math.max(0,Math.min(255,((i-16)*255+109)/219));
        cTable[i]=(byte)Math.max(0,Math.min(255,128+Math.round((i-128)*255f/224f)));
    }}
    static void closeAll(){
        stop=true;
        try{if(input!=null)input.close();}catch(Exception ignored){}
        try{if(output!=null)output.close();}catch(Exception ignored){}
        try{if(server!=null)server.close();}catch(Exception ignored){}
    }
    static final class Frame {
        final byte[] pixels,header;
        Frame(byte[] p,byte[] h){pixels=p;header=h;}
    }
    static final class JpegBuffer extends ByteArrayOutputStream {
        JpegBuffer(){super(256*1024);}
        void send(OutputStream out)throws IOException{out.write(buf,0,count);}
    }
    static int u32(byte[] b,int o){return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(o);}
    static byte[] read(DataInputStream in,int count)throws IOException{byte[] b=new byte[count];in.readFully(b);return b;}
    static void convert(byte[] pixels,int stride,int height){
        int ySize=stride*height;
        for(int i=0;i<ySize;i++)pixels[i]=yTable[pixels[i]&255];
        for(int i=ySize;i<pixels.length;i+=2){byte u=cTable[pixels[i]&255];pixels[i]=cTable[pixels[i+1]&255];pixels[i+1]=u;}
    }
    public static void main(String[] args){
        int result=1;
        try{
            if(args.length!=4)throw new IllegalArgumentException("rawPort outputPort seconds quality");
            final int rawPort=Integer.parseInt(args[0]),port=Integer.parseInt(args[1]);
            final int seconds=Integer.parseInt(args[2]),quality=Integer.parseInt(args[3]);
            if(rawPort<1024||rawPort>65535||port<1024||port>65535||seconds<2||seconds>600||quality<50||quality>95)throw new IllegalArgumentException("Bounds");
            Thread watchdog=new Thread(()->{try{Thread.sleep((seconds+20L)*1000);}catch(InterruptedException ignored){}closeAll();},"x90-jpeg-deadline");
            watchdog.setDaemon(true);watchdog.start();
            server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),1);server.setSoTimeout(12000);
            output=server.accept();server.close();output.setTcpNoDelay(true);output.setSendBufferSize(131072);
            long until=System.nanoTime()+5000000000L;
            while(input==null&&System.nanoTime()<until&&!stop){
                Socket attempt=new Socket();
                try{attempt.connect(new InetSocketAddress("127.0.0.1",rawPort),300);input=attempt;}
                catch(IOException e){attempt.close();Thread.sleep(30);}
            }
            if(input==null)throw new IOException("Native capture listener unavailable");
            input.setTcpNoDelay(true);input.setSoTimeout(3000);
            final DataInputStream in=new DataInputStream(new BufferedInputStream(input.getInputStream(),524288));
            final OutputStream out=new BufferedOutputStream(output.getOutputStream(),131072);
            byte[] header=read(in,32);
            if(!new String(header,0,8,"US-ASCII").equals("X90LIVE1")||u32(header,8)!=1||u32(header,28)!=3)throw new IOException("Raw protocol");
            final int width=u32(header,12),height=u32(header,16),stride=u32(header,20),size=stride*height*3/2;
            if(width<2||width>stride||stride>720||height<2||height>480||(width&1)!=0||(height&1)!=0)throw new IOException("Geometry");
            System.arraycopy("X90JPEG1".getBytes("US-ASCII"),0,header,0,8);
            out.write(header);out.flush();
            final ArrayBlockingQueue<byte[]> free=new ArrayBlockingQueue<>(5);
            final ArrayBlockingQueue<Frame> ready=new ArrayBlockingQueue<>(3);
            for(int i=0;i<5;i++)free.add(new byte[size]);
            Thread reader=new Thread(()->{
                try{
                    while(!stop){
                        byte[] meta=read(in,32);
                        if(u32(meta,20)!=size||u32(meta,16)>2)throw new IOException("Frame bounds");
                        byte[] pixels=free.poll(2,TimeUnit.SECONDS);if(pixels==null)throw new IOException("Buffer ownership stalled");
                        in.readFully(pixels);received.incrementAndGet();
                        Frame next=new Frame(pixels,meta);
                        if(!ready.offer(next)){
                            Frame old=ready.poll();if(old!=null){free.add(old.pixels);dropped.incrementAndGet();}
                            if(!ready.offer(next))throw new IOException("Queue ownership");
                        }
                    }
                }catch(EOFException expected){}
                 catch(Throwable e){if(!stop)failure.compareAndSet(null,e);}
                finally{stop=true;}
            },"x90-raw-reader");
            reader.setDaemon(true);reader.start();
            JpegBuffer jpeg=new JpegBuffer();Rect rect=new Rect(0,0,width,height);
            int encoded=0;long totalEncode=0,maxEncode=0,totalSend=0,first=0,last=0;
            while(!stop||!ready.isEmpty()){
                Frame frame=ready.poll(100,TimeUnit.MILLISECONDS);if(frame==null)continue;
                try{
                    long begin=System.nanoTime();convert(frame.pixels,stride,height);
                    jpeg.reset();
                    if(!new YuvImage(frame.pixels,ImageFormat.NV21,width,height,new int[]{stride,stride}).compressToJpeg(rect,quality,jpeg))throw new IOException("JPEG failed");
                    long packed=System.nanoTime(),elapsed=packed-begin;
                    totalEncode+=elapsed;maxEncode=Math.max(maxEncode,elapsed);
                    ByteBuffer meta=ByteBuffer.wrap(frame.header).order(ByteOrder.LITTLE_ENDIAN);
                    long stamp=meta.getLong(8);if(first==0)first=stamp;last=stamp;
                    meta.putInt(20,jpeg.size());meta.putLong(24,packed);
                    out.write(frame.header);jpeg.send(out);out.flush();totalSend+=System.nanoTime()-packed;encoded++;
                }finally{free.add(frame.pixels);}
            }
            reader.join(1000);
            if(failure.get()!=null)throw new IOException("Reader",failure.get());
            System.out.println("LIVE_JPEG_OK received="+received+" dropped="+dropped+" encoded="+encoded+" width="+width+" height="+height+" quality="+quality+
                " fps="+(encoded>1?(encoded-1)*1e9/(last-first):0)+" mean_encode_ms="+(encoded>0?totalEncode/1e6/encoded:0)+" max_encode_ms="+maxEncode/1e6+" mean_send_ms="+(encoded>0?totalSend/1e6/encoded:0));
            result=0;
        }catch(Throwable error){error.printStackTrace();}
        finally{closeAll();}
        System.out.println("LIVE_JPEG_EXIT="+result);System.exit(result);
    }
}
