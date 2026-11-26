package dev.watdafawx.foxstweaks.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.watdafawx.foxstweaks.hologram.HologramAnim;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Single-pass vertex color processor for hologram visual FX: combines TINT, RAINBOW, PULSE,
 * GLITCH chromatic flash, and sweeping horizontal SCANLINE bands in one vertex pass with zero
 * extra draw calls and full shader-pack compatibility.
 */
final class TintedBuffer implements MultiBufferSource {
    private final MultiBufferSource delegate;
    private final float red;
    private final float green;
    private final float blue;
    private final float scanStrength;
    private final float scanPhase;
    @org.jetbrains.annotations.Nullable
    private final Gradient gradient;

    /**
     * A bottom-to-top colour ramp in the vertex space the buffer sees (camera-relative world y). The ends
     * are measured once per draw from the pose, so a rotated layer still ramps along world up, which is
     * what a viewer reads as "bottom" and "top".
     */
    record Gradient(float bottomY, float topY, float mix, int bottom, int top) {
    }

    TintedBuffer(MultiBufferSource delegate, float red, float green, float blue,
                 float scanStrength, float scanPhase, @org.jetbrains.annotations.Nullable Gradient gradient) {
        this.delegate = delegate;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.scanStrength = scanStrength;
        this.scanPhase = scanPhase;
        this.gradient = gradient;
    }

    static MultiBufferSource wrap(MultiBufferSource delegate, List<HologramEffect> effects, double seconds) {
        return wrap(delegate, effects, seconds, 0.0F, 1.0F);
    }

    /** True when any per-vertex Y-dependent FX (SCANLINE or GRADIENT) is present in {@code effects}. */
    static boolean hasPerVertexFx(List<HologramEffect> effects) {
        for (int i = 0; i < effects.size(); i++) {
            HologramEffect fx = effects.get(i);
            if (fx.type() == HologramEffect.Type.SCANLINE)
                return true;
            if (fx.type() == HologramEffect.Type.GRADIENT && fx.strength() > 0.001F)
                return true;
        }
        return false;
    }

    /**
     * Multiplies uniform RGB FX (TINT, RAINBOW, PULSE, GLITCH flash) from {@code effects} into
     * {@code outRgb[0..2]}. Used by the cached structure VBO path via {@code RenderSystem.setShaderColor}.
     */
    static void applyUniformColor(List<HologramEffect> effects, double seconds, float[] outRgb) {
        if (effects.isEmpty())
            return;

        HologramEffect tint = HologramAnim.tint(effects);
        if (tint != null) {
            float mix = Mth.clamp(tint.strength(), 0.0F, 1.0F);
            outRgb[0] *= 1.0F - mix + mix * ((tint.color() >> 16 & 0xFF) / 255.0F);
            outRgb[1] *= 1.0F - mix + mix * ((tint.color() >> 8 & 0xFF) / 255.0F);
            outRgb[2] *= 1.0F - mix + mix * ((tint.color() & 0xFF) / 255.0F);
        }

        int rainbow = HologramAnim.rainbowColor(effects, seconds);
        if (rainbow >= 0) {
            outRgb[0] *= ((rainbow >> 16) & 0xFF) / 255.0F;
            outRgb[1] *= ((rainbow >> 8) & 0xFF) / 255.0F;
            outRgb[2] *= (rainbow & 0xFF) / 255.0F;
        }

        float pulse = HologramAnim.pulseMultiplier(effects, seconds);
        if (Math.abs(pulse - 1.0F) > 0.005F) {
            outRgb[0] *= pulse;
            outRgb[1] *= pulse;
            outRgb[2] *= pulse;
        }

        HologramEffect glitch = HologramAnim.firstOf(effects, HologramEffect.Type.GLITCH);
        if (glitch != null) {
            float burst = HologramAnim.glitchBurst(seconds, glitch.speed());
            if (burst > 0.1F) {
                boolean cyan = HologramAnim.wave(seconds, glitch.speed() * 17.0F, 0.41) > 0.0F;
                float m = Mth.clamp(burst * glitch.strength() * 0.7F, 0.0F, 0.85F);
                outRgb[0] *= cyan ? (1.0F - 0.65F * m) : (1.0F + 0.35F * m);
                outRgb[1] *= cyan ? (1.0F + 0.25F * m) : (1.0F - 0.75F * m);
                outRgb[2] *= 1.0F + 0.35F * m;
            }
        }
    }

    /**
     * Wraps {@code delegate} when any vertex-colour FX is active, otherwise returns {@code delegate}.
     * {@code bottomY}/{@code topY} are the drawn content's vertical extent in vertex space, read only by GRADIENT.
     */
    static MultiBufferSource wrap(MultiBufferSource delegate, List<HologramEffect> effects, double seconds,
                                  float bottomY, float topY) {
        if (effects.isEmpty())
            return delegate;

        float r = 1.0F;
        float g = 1.0F;
        float b = 1.0F;
        boolean needed = false;

        HologramEffect tint = HologramAnim.tint(effects);
        if (tint != null) {
            float mix = Mth.clamp(tint.strength(), 0.0F, 1.0F);
            r *= 1.0F - mix + mix * ((tint.color() >> 16 & 0xFF) / 255.0F);
            g *= 1.0F - mix + mix * ((tint.color() >> 8 & 0xFF) / 255.0F);
            b *= 1.0F - mix + mix * ((tint.color() & 0xFF) / 255.0F);
            needed = true;
        }

        int rainbow = HologramAnim.rainbowColor(effects, seconds);
        if (rainbow >= 0) {
            r *= ((rainbow >> 16) & 0xFF) / 255.0F;
            g *= ((rainbow >> 8) & 0xFF) / 255.0F;
            b *= (rainbow & 0xFF) / 255.0F;
            needed = true;
        }

        float pulse = HologramAnim.pulseMultiplier(effects, seconds);
        if (Math.abs(pulse - 1.0F) > 0.005F) {
            r *= pulse;
            g *= pulse;
            b *= pulse;
            needed = true;
        }

        HologramEffect glitch = HologramAnim.firstOf(effects, HologramEffect.Type.GLITCH);
        if (glitch != null) {
            float burst = HologramAnim.glitchBurst(seconds, glitch.speed());
            if (burst > 0.1F) {
                // Brief cyan/magenta cyber-glitch flash during burst spikes.
                boolean cyan = HologramAnim.wave(seconds, glitch.speed() * 17.0F, 0.41) > 0.0F;
                float m = Mth.clamp(burst * glitch.strength() * 0.7F, 0.0F, 0.85F);
                r *= cyan ? (1.0F - 0.65F * m) : (1.0F + 0.35F * m);
                g *= cyan ? (1.0F + 0.25F * m) : (1.0F - 0.75F * m);
                b *= 1.0F + 0.35F * m;
                needed = true;
            }
        }

        float scanStrength = 0.0F;
        float scanPhase = 0.0F;
        HologramEffect scan = HologramAnim.firstOf(effects, HologramEffect.Type.SCANLINE);
        if (scan != null) {
            scanStrength = Mth.clamp(scan.strength() * 0.42F, 0.0F, 0.75F);
            scanPhase = (float) ((seconds * scan.speed()) % 1.0) * Mth.TWO_PI;
            needed = true;
        }

        Gradient gradient = null;
        HologramEffect grad = HologramAnim.firstOf(effects, HologramEffect.Type.GRADIENT);
        if (grad != null && grad.strength() > 0.001F) {
            float lo = Math.min(bottomY, topY);
            float hi = Math.max(bottomY, topY);
            if (hi - lo < 0.01F)
                hi = lo + 0.01F;
            gradient = new Gradient(lo, hi, Mth.clamp(grad.strength(), 0.0F, 1.0F), grad.color(), grad.color2());
            needed = true;
        }

        return needed ? new TintedBuffer(delegate, r, g, b, scanStrength, scanPhase, gradient) : delegate;
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        return new TintedVertex(this.delegate.getBuffer(type),
                this.red, this.green, this.blue, this.scanStrength, this.scanPhase, this.gradient);
    }

    private static final class TintedVertex implements VertexConsumer {
        private final VertexConsumer delegate;
        private final float red, green, blue;
        private final float scanStrength, scanPhase;
        @org.jetbrains.annotations.Nullable
        private final Gradient gradient;
        private float lastY;

        TintedVertex(VertexConsumer delegate, float red, float green, float blue,
                     float scanStrength, float scanPhase, @org.jetbrains.annotations.Nullable Gradient gradient) {
            this.delegate = delegate;
            this.gradient = gradient;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.scanStrength = scanStrength;
            this.scanPhase = scanPhase;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.lastY = y;
            this.delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            float band = 1.0F;
            if (this.scanStrength > 0.0F) {
                float wave = Mth.sin(this.lastY * 14.0F - this.scanPhase);
                band = 1.0F - this.scanStrength * (0.5F - 0.5F * wave);
            }
            float gr = 1.0F, gg = 1.0F, gb = 1.0F;
            Gradient grad = this.gradient;
            if (grad != null) {
                float t = Mth.clamp((this.lastY - grad.bottomY()) / (grad.topY() - grad.bottomY()), 0.0F, 1.0F);
                float m = grad.mix();
                gr = 1.0F - m + m * Mth.lerp(t, (grad.bottom() >> 16 & 0xFF), (grad.top() >> 16 & 0xFF)) / 255.0F;
                gg = 1.0F - m + m * Mth.lerp(t, (grad.bottom() >> 8 & 0xFF), (grad.top() >> 8 & 0xFF)) / 255.0F;
                gb = 1.0F - m + m * Mth.lerp(t, (grad.bottom() & 0xFF), (grad.top() & 0xFF)) / 255.0F;
            }
            int cr = Mth.clamp(Math.round(r * this.red * gr * band), 0, 255);
            int cg = Mth.clamp(Math.round(g * this.green * gg * band), 0, 255);
            int cb = Mth.clamp(Math.round(b * this.blue * gb * band), 0, 255);
            this.delegate.setColor(cr, cg, cb, a);
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
