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
