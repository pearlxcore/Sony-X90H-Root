#version 310 es
precision highp float;
precision highp int;
uniform sampler2D ytex,uvtex,history1,history2,history3,futuretex;
uniform vec2 sourceSize;
uniform float strideSize;
uniform int parity,historyValid,futureValid,cut,lumaOnly,diMode;
uniform float motionThr,edgeGain;
out vec4 color;
// diMode 0 = guarded edge + history blend + 3-field median (default),
// 1 = guarded edge only (no temporal), 2 = plain line average (bob).
// Guarded-edge spatial candidate, then a 3-field median with the previous and
// next fields. The median is only used when the engine has an adjacent future
// field and the frame is not a global cut. Otherwise the spatial path runs.
// Values are kept in 0..255 code units so the cost thresholds match the
// offline reference (evaluate_field_motion.py / di_real_compare.py).
ivec2 bound(){return ivec2(sourceSize)-1;}
float code(sampler2D image,ivec2 p){
 return texelFetch(image,clamp(p,ivec2(0),bound()),0).r*255.0;
}
float motionAt(ivec2 p){
 return max(abs(code(ytex,p)-code(history2,p)),abs(code(history1,p)-code(history3,p)));
}
float costDir(ivec2 a,ivec2 b,int d){
 float s=0.0;
 for(int k=-1;k<=1;k++)s+=abs(code(ytex,a+ivec2(k-d,0))-code(ytex,b+ivec2(k+d,0)));
 return s+float(abs(d))*3.0;
}
float valueDir(ivec2 a,ivec2 b,int d){
 return (code(ytex,a-ivec2(d,0))+code(ytex,b+ivec2(d,0)))*0.5;
}
float median3(float a,float b,float c){
 return max(min(a,b),min(max(a,b),c));
}
void main(){
 int row=int(sourceSize.y)*2-1-int(gl_FragCoord.y);
 ivec2 p=ivec2(int(gl_FragCoord.x),row/2);
 float value=code(ytex,p);
 if(row%2!=parity){
  ivec2 a=p-ivec2(0,parity==1?1:0),b=p+ivec2(0,parity==0?1:0);
  float average=(code(ytex,a)+code(ytex,b))*0.5;
  value=average;
  if(diMode!=2){
   float c0=costDir(a,b,0),c1=costDir(a,b,-1),c2=costDir(a,b,1),
         c3=costDir(a,b,-2),c4=costDir(a,b,2);
   float bestC=c0,bestV=valueDir(a,b,0);int bi=0;
   if(c1<bestC){bestC=c1;bestV=valueDir(a,b,-1);bi=1;}
   if(c2<bestC){bestC=c2;bestV=valueDir(a,b,1);bi=2;}
   if(c3<bestC){bestC=c3;bestV=valueDir(a,b,-2);bi=3;}
   if(c4<bestC){bestC=c4;bestV=valueDir(a,b,2);bi=4;}
   float secondC=1e9;
   if(bi!=0)secondC=min(secondC,c0);
   if(bi!=1)secondC=min(secondC,c1);
   if(bi!=2)secondC=min(secondC,c2);
   if(bi!=3)secondC=min(secondC,c3);
   if(bi!=4)secondC=min(secondC,c4);
   float strength=clamp((c0-bestC-4.0)/24.0,0.0,1.0)*clamp((secondC-bestC-3.0)/18.0,0.0,1.0);
   value=mix(average,bestV,strength*edgeGain);
  }
  float gate=1.0;
  if((diMode==0||diMode==3)&&historyValid!=0){
   float motion=motionAt(p);
   motion=max(motion,motionAt(p+ivec2(1,0)));
   motion=max(motion,motionAt(p-ivec2(1,0)));
   motion=max(motion,motionAt(p+ivec2(0,1)));
   motion=max(motion,motionAt(p-ivec2(0,1)));
   // motionThr=12 reproduces the fixed (12-motion)/10 blend.
   gate=clamp((motionThr-motion)/(motionThr*0.8333333),0.0,1.0);
   value=mix(value,code(history1,p),gate);
  }
  // diMode 3 = motion-gated: weave/median only where static (gate high),
  // spatial/bob where moving (gate low) -> stable detail AND smooth 60.
  if((diMode==0||diMode==3)&&futureValid!=0&&cut==0){
   float med=median3(value,code(history1,p),code(futuretex,p));
   value=(diMode==3)?mix(value,med,gate):med;
  }
 }
 if(lumaOnly!=0){color=vec4(vec3(clamp(value,0.0,255.0)/255.0),1.0);return;}
 float y=1.16438356*(clamp(value,0.0,255.0)/255.0-16.0/255.0);
 // Chroma is co-sited with even luma columns: +0.5 px makes the even column
 // land on a chroma texel centre (measured on the archived source-5 fields).
 vec2 uv=texture(uvtex,vec2((gl_FragCoord.x+0.5)/strideSize,
                          (float(row)+.5)/(sourceSize.y*2.0))).rg-vec2(128.0/255.0);
 color=vec4(clamp(vec3(y+1.59602678*uv.y,y-.39176229*uv.x-.81296764*uv.y,
                       y+2.01723214*uv.x),0.0,1.0),1.0);
}
