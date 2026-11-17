//@@ fu-pre-block.frag
#version 310 es
// fastup prepass A: 2x2 block min/max of the source, stored at index q+1 (texture is (w+1)x(h+1)),
// so the upscale pass can fetch block(q) for q in [-1, w-1] with the original edge clamping.
precision highp float;
precision highp int;
uniform sampler2D src;
layout(location=0) out vec4 bmin;
layout(location=1) out vec4 bmax;
void main(){
 ivec2 q=ivec2(gl_FragCoord.xy)-ivec2(1);ivec2 mx=textureSize(src,0)-1;
 vec3 a=texelFetch(src,clamp(q,ivec2(0),mx),0).rgb;
 vec3 b=texelFetch(src,clamp(q+ivec2(1,0),ivec2(0),mx),0).rgb;
 vec3 d=texelFetch(src,clamp(q+ivec2(0,1),ivec2(0),mx),0).rgb;
 vec3 z=texelFetch(src,clamp(q+ivec2(1),ivec2(0),mx),0).rgb;
 bmin=vec4(min(min(a,b),min(d,z)),1.0);
 bmax=vec4(max(max(a,b),max(d,z)),1.0);
}
//@@ fu-pre-cross.frag
#version 310 es
// fastup prepass B: 4-neighbour cross min/max and blur at every source texel (same as the
// original sharpen block, which evaluates them at ip=ivec2(p)).
precision highp float;
precision highp int;
uniform sampler2D src;
layout(location=0) out vec4 cmin;
layout(location=1) out vec4 cmax;
layout(location=2) out vec4 blur;
void main(){
 ivec2 ip=ivec2(gl_FragCoord.xy);ivec2 mx=textureSize(src,0)-1;
 vec3 l=texelFetch(src,clamp(ip-ivec2(1,0),ivec2(0),mx),0).rgb;
 vec3 r=texelFetch(src,clamp(ip+ivec2(1,0),ivec2(0),mx),0).rgb;
 vec3 u=texelFetch(src,clamp(ip+ivec2(0,1),ivec2(0),mx),0).rgb;
 vec3 v=texelFetch(src,clamp(ip-ivec2(0,1),ivec2(0),mx),0).rgb;
 cmin=vec4(min(min(l,r),min(u,v)),1.0);
 cmax=vec4(max(max(l,r),max(u,v)),1.0);
 blur=vec4((l+r+u+v)*0.25,1.0);
}
//@@ fu-h.frag
#version 310 es
// fastup horizontal pass (runtime-tunable): catmullrom-clamped.frag's x weights and 3 bilinear x
// taps at every source row centre. Output contentWidth x srcHeight RGBA16F.
// zx = horizontal zoom factor (1.0 = none); set by the engine from the "zoom" setting. Identity-safe:
// the engine always uploads zx (default 1.0), so an unset uniform never blanks the picture.
precision highp float;
uniform sampler2D src;uniform float outW;uniform float zx;out vec4 color;
void main(){
 vec2 size=vec2(textureSize(src,0));
 float p=((gl_FragCoord.x/outW-0.5)/zx+0.5)*size.x;
 float p1=floor(p-0.5)+0.5;float f=p-p1;
 float w0=f*(-0.5+f*(1.0-0.5*f));float w1=1.0+f*f*(-2.5+1.5*f);
 float w2=f*(0.5+f*(2.0-1.5*f));float w3=f*f*(-0.5+0.5*f);
 float w12=w1+w2;
 float y=(floor(gl_FragCoord.y)+0.5)/size.y;
 vec3 c=texture(src,vec2((p1-1.0)/size.x,y)).rgb*w0
 +texture(src,vec2((p1+w2/w12)/size.x,y)).rgb*w12
 +texture(src,vec2((p1+2.0)/size.x,y)).rgb*w3;
 color=vec4(c,1.0);
}
//@@ fu-v.frag
#version 310 es
// fastup vertical pass (runtime-tunable): catmullrom-clamped.frag's y weights on the horizontal-pass
// texture, then the 2x2-block clamp and clamped unsharp, then an OPT-IN output transform.
//   zoom  = (zx,zy) magnify about the content centre (1.0 = none)
//   out   = clamp(lift + gain * max(colour,0)^gamma, 0, 1)   -- per channel
// Defaults (zoom=(1,1), lift=0, gain=1, gamma=1) are exact identity. The engine uploads all of these
// every frame from the x90-engine-settings keys (zoom/tone/bright/warm/gamma), so an unset uniform
// never blanks the picture.
precision highp float;
precision highp int;
uniform sampler2D htex,bmin,bmax,cmin,cmax,blur;
uniform vec2 outsize,size;uniform float sharpen,cx;
uniform vec2 zoom;uniform vec3 lift,gain,gamma;out vec4 color;
void main(){
 vec2 fc=gl_FragCoord.xy-vec2(cx,0.0);
 vec2 p=((fc/outsize-0.5)/zoom+0.5)*size;
 float p1=floor(p.y-0.5)+0.5;float f=p.y-p1;
 float w0=f*(-0.5+f*(1.0-0.5*f));float w1=1.0+f*f*(-2.5+1.5*f);
 float w2=f*(0.5+f*(2.0-1.5*f));float w3=f*f*(-0.5+0.5*f);
 float w12=w1+w2;
 float u=fc.x/outsize.x;
 vec3 c=texture(htex,vec2(u,(p1-1.0)/size.y)).rgb*w0
 +texture(htex,vec2(u,(p1+w2/w12)/size.y)).rgb*w12
 +texture(htex,vec2(u,(p1+2.0)/size.y)).rgb*w3;
 ivec2 q=ivec2(floor(p-0.5));ivec2 mb=textureSize(bmin,0)-1;
 ivec2 qb=clamp(q+ivec2(1),ivec2(0),mb);
 vec3 result=clamp(c,texelFetch(bmin,qb,0).rgb,texelFetch(bmax,qb,0).rgb);
 if(sharpen>0.0){
  ivec2 mx=textureSize(cmin,0)-1;ivec2 ip=clamp(ivec2(p),ivec2(0),mx);
  vec3 bl=texelFetch(blur,ip,0).rgb;
  result=clamp(result+sharpen*1.2*(result-bl),texelFetch(cmin,ip,0).rgb,texelFetch(cmax,ip,0).rgb);
 }
 result=clamp(lift+gain*pow(max(result,vec3(0.0)),gamma),0.0,1.0);
 color=vec4(result,1.0);
}
