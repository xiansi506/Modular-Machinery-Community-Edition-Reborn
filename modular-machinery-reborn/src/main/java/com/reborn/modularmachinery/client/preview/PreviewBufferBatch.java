package com.reborn.modularmachinery.client.preview;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.MultiBufferSource;

/** A preview never queues vertices into the caller's shared GUI buffer. */
final class PreviewBufferBatch implements AutoCloseable {
    private static BufferBuilder shared;
    private final BufferBuilder builder;
    final MultiBufferSource.BufferSource source;

    PreviewBufferBatch() { this(sharedBuilder()); }
    PreviewBufferBatch(BufferBuilder builder) {
        this.builder = builder;
        this.source = MultiBufferSource.immediate(builder);
    }
    private static BufferBuilder sharedBuilder() {
        if (shared == null) shared = new BufferBuilder(262144);
        return shared;
    }
    void submit() { source.endBatch(); }
    @Override public void close() {
        try {
            // discard() only resets byte offsets in 1.20.1; it does not leave building mode.
            if (builder.building()) {
                var abandoned = builder.endOrDiscardIfEmpty();
                if (abandoned != null) abandoned.release();
            }
        } finally {
            builder.discard();
            // Do not reuse a malformed third-party batch that could not even be ended.
            if (builder.building() && shared == builder) shared = null;
        }
    }
}
