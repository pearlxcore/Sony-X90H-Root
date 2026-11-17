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
