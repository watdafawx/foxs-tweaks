package dev.watdafawx.foxstweaks.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Average colour of every block, bucketed into the hue chips of the asset browser.
 *
 * <p>The colour is the mean of the block's particle sprite (the sprite vanilla breaks it into), multiplied by
 * the block's own tint when it has one (grass, leaves), with the map colour as the fallback for a block whose
 * sprite is missing. Work is done a few blocks at a time ({@link #step}) so opening the browser never stalls;
 * sprites are cached by name because most blocks share one with their stairs, slabs and walls.
 */
final class HologramBlockColors {
    enum Hue {
        RED("red", 0xE0453A), ORANGE("orange", 0xE8872B), YELLOW("yellow", 0xEBCB3A), GREEN("green", 0x4CB84F),
        CYAN("cyan", 0x3FD0D8), BLUE("blue", 0x3E6FE0), PURPLE("purple", 0x8E52D6), PINK("pink", 0xE56FB8),
        WHITE("white", 0xF0F0F0), GREY("grey", 0x8A8F98), BLACK("black", 0x15171B), BROWN("brown", 0x7B5230);

        final String label;
        final int swatch;

        Hue(String label, int swatch) {
            this.label = label;
            this.swatch = 0xFF000000 | swatch;
        }

        @Nullable
        static Hue byLabel(String label) {
            for (Hue hue : values()) {
                if (hue.label.equals(label))
                    return hue;
            }
            return null;
        }
    }

    private static final Map<Block, Hue> HUES = new HashMap<>();
    private static final Map<ResourceLocation, Integer> SPRITE_RGB = new HashMap<>();
    private static List<Block> queue = List.of();
    private static int cursor;

    /** The bucket, or null while the block has not been looked at yet. */
    @Nullable
    static Hue hueOf(Block block) {
        return HUES.get(block);
    }

    /** Hands the work list over. The same list again keeps the progress made so far. */
    static void queue(List<Block> blocks) {
        if (blocks == queue)
            return;
        queue = blocks;
        cursor = 0;
        while (cursor < queue.size() && HUES.containsKey(queue.get(cursor)))
            cursor++;
    }

    static boolean done() {
        return cursor >= queue.size();
    }

    static float progress() {
        return queue.isEmpty() ? 1.0F : Math.min(1.0F, (float) cursor / queue.size());
    }

    /** Colours as many queued blocks as fit in the time budget. Returns how many were done. */
    static int step(long budgetNanos) {
        long end = System.nanoTime() + budgetNanos;
        int done = 0;
        while (cursor < queue.size() && System.nanoTime() < end) {
            Block block = queue.get(cursor++);
            if (!HUES.containsKey(block)) {
                HUES.put(block, bucket(averageRgb(block)));
                done++;
            }
        }
        return done;
    }

    private static int averageRgb(Block block) {
        BlockState state = block.defaultBlockState();
        try {
            Minecraft mc = Minecraft.getInstance();
            TextureAtlasSprite sprite = mc.getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
            ResourceLocation name = sprite.contents().name();
            if (!"missingno".equals(name.getPath())) {
                int rgb = SPRITE_RGB.computeIfAbsent(name, key -> spriteMean(sprite));
                if (rgb >= 0)
                    return tint(rgb, state);
            }
        } catch (Throwable ignored) {
        }
        return block.defaultMapColor().col;
    }

    private static int tint(int rgb, BlockState state) {
        try {
            int tint = Minecraft.getInstance().getBlockColors().getColor(state, null, null, 0);
            if (tint == -1)
                return rgb;
            int r = ((rgb >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
            int g = ((rgb >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
            int b = (rgb & 0xFF) * (tint & 0xFF) / 255;
            return (r << 16) | (g << 8) | b;
        } catch (Throwable ignored) {
            return rgb;
        }
    }

    /** Mean of the first animation frame's opaque pixels, or -1 when there are none. */
    private static int spriteMean(TextureAtlasSprite sprite) {
        try {
            NativeImage image = sprite.contents().getOriginalImage();
            int w = Math.min(sprite.contents().width(), image.getWidth());
            int h = Math.min(sprite.contents().height(), image.getHeight());
            long r = 0;
            long g = 0;
            long b = 0;
            long n = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int abgr = image.getPixelRGBA(x, y);
                    if (((abgr >> 24) & 0xFF) < 128)
                        continue;
                    r += abgr & 0xFF;
                    g += (abgr >> 8) & 0xFF;
                    b += (abgr >> 16) & 0xFF;
                    n++;
                }
            }
            if (n == 0)
                return -1;
            return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        } catch (Throwable t) {
            return -1;
        }
    }

    static Hue bucket(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255.0F;
        float g = ((rgb >> 8) & 0xFF) / 255.0F;
        float b = (rgb & 0xFF) / 255.0F;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float v = max;
        float s = max <= 0.0F ? 0.0F : delta / max;

        if (v < 0.17F)
            return Hue.BLACK;
        if (s < 0.14F)
            return v > 0.78F ? Hue.WHITE : (v < 0.24F ? Hue.BLACK : Hue.GREY);

        float h;
        if (delta == 0.0F)
            h = 0.0F;
        else if (max == r)
            h = 60.0F * Mth.positiveModulo((g - b) / delta, 6.0F);
        else if (max == g)
            h = 60.0F * ((b - r) / delta + 2.0F);
        else
            h = 60.0F * ((r - g) / delta + 4.0F);

        if (h >= 10.0F && h < 50.0F && v < 0.62F && s > 0.22F)
            return Hue.BROWN;
        if (h < 15.0F || h >= 345.0F)
            return Hue.RED;
        if (h < 42.0F)
            return Hue.ORANGE;
        if (h < 70.0F)
            return Hue.YELLOW;
        if (h < 165.0F)
            return Hue.GREEN;
        if (h < 200.0F)
            return Hue.CYAN;
        if (h < 255.0F)
            return Hue.BLUE;
        if (h < 290.0F)
            return Hue.PURPLE;
        return Hue.PINK;
    }

    private HologramBlockColors() {
    }
}
