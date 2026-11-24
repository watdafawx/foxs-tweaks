package dev.watdafawx.foxstweaks.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * Colour-tints whatever is drawn through it, by multiplying each vertex colour.
 *
 * <p>This is how the {@code TINT} effect works: block models are drawn with white vertices, so scaling
 * their colour is a full tint that costs one multiply per vertex and needs no shader, no second pass and
 * no render-type swap. Alpha is passed through untouched - most block render types do not blend, so
 * fading via alpha would do nothing (which is why FLICKER hides frames instead).
 */
final class TintedBuffer implements MultiBufferSource {
    private final MultiBufferSource delegate;
    private final float red;
    private final float green;
    private final float blue;

    /**
     * @param color    packed RGB to tint toward
     * @param strength 0 = untouched, 1 = fully {@code color}
     */
    TintedBuffer(MultiBufferSource delegate, int color, float strength) {
        this.delegate = delegate;
        float mix = Math.max(0.0F, Math.min(1.0F, strength));
        this.red = 1.0F - mix + mix * ((color >> 16 & 0xFF) / 255.0F);
        this.green = 1.0F - mix + mix * ((color >> 8 & 0xFF) / 255.0F);
        this.blue = 1.0F - mix + mix * ((color & 0xFF) / 255.0F);
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        return new TintedVertex(this.delegate.getBuffer(type), this.red, this.green, this.blue);
    }

    private record TintedVertex(VertexConsumer delegate, float red, float green, float blue)
            implements VertexConsumer {

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            this.delegate.setColor(Math.round(r * this.red), Math.round(g * this.green),
                    Math.round(b * this.blue), a);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            this.delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            this.delegate.setNormal(x, y, z);
            return this;
        }
    }
}
