package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/**
 * The red X / green check next to a toggleable line. Drawn from a bitmap font
 * ({@code assets/foxstweaks/font/icons.json}) rather than the old "❌" emoji, which the default
 * font does not have: it fell back to a blurry unifont glyph that sat a pixel low and ignored the
 * line's colour.
 */
public final class ToggleIcons {
    private ToggleIcons() {
    }

    public static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "icons");

    /** Private-use characters mapped by the font; also what a tooltip line is matched on. */
    public static final String OFF_CHAR = "";
    public static final String ON_CHAR = "";

    /** Red X followed by a space. White colour so a parent's tint does not recolour the sprite. */
    public static MutableComponent off() {
        return glyph(OFF_CHAR);
    }

    public static MutableComponent on() {
        return glyph(ON_CHAR);
    }

    /**
     * An unstyled root holding the glyph and a gap, so whatever is appended next is a sibling of the
     * glyph and not a child of it - a child would inherit the icon font and draw as missing boxes.
     */
    private static MutableComponent glyph(String character) {
        return Component.empty()
                .append(Component.literal(character).withStyle(Style.EMPTY.withFont(FONT).withColor(0xFFFFFF)))
                .append(Component.literal(" "));
    }

    public static boolean isOffLine(String text) {
        return text.contains(OFF_CHAR);
    }
}
