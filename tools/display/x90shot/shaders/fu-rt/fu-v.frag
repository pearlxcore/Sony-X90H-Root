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
