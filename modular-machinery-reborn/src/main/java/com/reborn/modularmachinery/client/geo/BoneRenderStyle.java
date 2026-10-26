package com.reborn.modularmachinery.client.geo;

/** The old renderer propagated both flags through children; names are case sensitive. */
public record BoneRenderStyle(boolean emissive, boolean transparent) {
    public static final BoneRenderStyle NORMAL = new BoneRenderStyle(false, false);
    public BoneRenderStyle child(String name) {
        return new BoneRenderStyle(emissive || name.startsWith("emissive") || name.startsWith("bloom"),
                transparent || name.startsWith("transparent") || name.startsWith("emissive_transparent")
                        || name.startsWith("bloom_transparent"));
    }
}
