#version 150
uniform sampler2D DiffuseSampler;
uniform sampler2D BloomSampler;
uniform float Strength;
in vec2 texCoord;
out vec4 fragColor;
void main() {
    vec4 scene = texture(DiffuseSampler, texCoord);
    fragColor = vec4(scene.rgb + texture(BloomSampler, texCoord).rgb * Strength, scene.a);
}
