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
