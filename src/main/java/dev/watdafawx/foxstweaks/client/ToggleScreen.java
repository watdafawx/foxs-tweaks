package dev.watdafawx.foxstweaks.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.client.compat.ApothicRows;
import dev.watdafawx.foxstweaks.enchant.EnchantToggle;
import dev.watdafawx.foxstweaks.enchant.ToggleEntryPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.core.Holder;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The item's own tooltip, made clickable: a vanilla-styled tooltip box with a strip of the six
 * equipment slots on top, the enchantments packed two to a line, the affixes one per line, and the
 * gem sockets underneath (read-only). Click a line to switch it on or off.
 *
 * <p>The box sizes itself to its content, so names are never cut short unless the screen itself is
 * too small. When the item is too tall for the screen only the affix block scrolls (mouse wheel).
 * Rows come from the item's data - {@link EnchantToggle} and {@code client.compat.ApothicRows} -
 * never from reading tooltip text back.
 *
 * <p>Toggling sends {@link ToggleEntryPayload}; the server is authoritative. The client stack is also
 * changed locally so the row flips this frame instead of after the slot sync. No Apotheosis type is
 * referenced here, only {@code ApothicRows}, and only after the mod is confirmed loaded.
 */
public class ToggleScreen extends Screen {
    /** A toggleable enchantment or affix. {@code locked} rows cannot be switched on (incompatible with an active enchantment). */
    public record Row(boolean affix, ResourceLocation id, Component label, List<Component> tip, boolean enabled, boolean locked) {
    }

    private record Hit(int x, int y, int w, int h, Row row) {
    }

    private record SlotHit(int x, int y, EquipmentSlot slot, ItemStack stack, boolean usable, int off) {
    }

    private record GemHit(int x, int y, ItemStack stack) {
    }

    static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND };

    private static final int SLOT = 18, SLOT_GAP = 2, ROW = 12, GLYPH = 12, HDR = 11;

    private static final int TEXT = 0xFFFFFFFF, GREY = 0xFF9A9A9A, DIM = 0xFF666666, HEADER = 0xFF7A7A7A, AMBER = 0xFFFFB020;

    private static Boolean apotheosisLoaded;

    private EquipmentSlot slot;
    private int affixScroll;

    // Layout, rebuilt by layout() from the live item every frame.
    private int cx, cy, cw, ch;
    private int yName, yEnchHdr, yAffHdr, yGemHdr, yHint;
    private int visibleAff;
    private Component nameText = Component.empty();
    private List<Row> enchRows = List.of(), affRows = List.of();
    private List<ItemStack> gems = List.of();
    private final List<Hit> hits = new ArrayList<>();
    private final List<SlotHit> slotHits = new ArrayList<>();
    private final List<GemHit> gemHits = new ArrayList<>();

    /** Dev harness only: pretend the cursor is here (GUI coordinates). A real cursor cannot be moved on an unfocused window. */
    public int[] forcedMouse;

    /** The screen this one was opened over (the inventory, say). It stays visible behind the box and is returned to on close. */
    private final Screen parent;

    public ToggleScreen(EquipmentSlot preferred) {
        this(preferred, null);
    }

    public ToggleScreen(EquipmentSlot preferred, Screen parent) {
        super(Component.translatable("foxstweaks.toggle.title"));

        this.slot = preferred;
        this.parent = parent;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    public static boolean canOpen() {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || mc.level == null)
            return false;

        for (EquipmentSlot s : SLOTS) {
            if (!rowsFor(mc.player.getItemBySlot(s)).isEmpty())
                return true;
        }

        return false;
    }

    /** The slot to open on: the preferred one if it has something to toggle, else the first that does. */
    public static EquipmentSlot firstUsable(EquipmentSlot preferred) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || !rowsFor(mc.player.getItemBySlot(preferred)).isEmpty())
            return preferred;

        for (EquipmentSlot s : SLOTS) {
            if (!rowsFor(mc.player.getItemBySlot(s)).isEmpty())
                return s;
        }

        return preferred;
    }

    private static boolean affixesEnabled() {
        if (apotheosisLoaded == null)
            apotheosisLoaded = ModList.get().isLoaded(FoxsTweaks.APOTHEOSIS);

        return apotheosisLoaded && Config.AFFIX_TOGGLE_GUI.get();
    }

    /** Enchantment rows then affix rows for a stack; empty when nothing on it can be toggled. */
    static List<Row> rowsFor(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        List<Row> rows = new ArrayList<>();

        if (stack.isEmpty() || mc.level == null)
            return rows;

        if (Config.ENCHANT_TOGGLE.get()) {
            var live = EnchantmentHelper.getEnchantmentsForCrafting(stack).keySet();

            for (EnchantToggle.Row row : EnchantToggle.list(stack, mc.level.registryAccess())) {
                Holder<Enchantment> holder = row.enchantment();
                boolean locked = !row.enabled() && !EnchantmentHelper.isEnchantmentCompatible(live, holder);
                Component name = Enchantment.getFullname(holder, row.level());
                List<Component> tip = new ArrayList<>();

                tip.add(name);

                if (locked)
                    tip.add(Component.translatable("foxstweaks.toggle.incompatible").withStyle(ChatFormatting.RED));

                rows.add(new Row(false, row.id(), name, tip, row.enabled(), locked));
            }
        }

        if (affixesEnabled())
            rows.addAll(ApothicRows.rows(stack));

        return rows;
    }

    private ItemStack stack(EquipmentSlot s) {
        return minecraft != null && minecraft.player != null ? minecraft.player.getItemBySlot(s) : ItemStack.EMPTY;
    }

    @Override
    protected void init() {
        if (parent != null)
            parent.resize(minecraft, width, height);

        layout();
    }

    // ---------------------------------------------------------------------------------------------
    // Layout

    private int labelWidth(Row row) {
        return font.width(row.label) + GLYPH + 4;
    }

    private void layout() {
        ItemStack cur = stack(slot);
        List<Row> all = rowsFor(cur);

        enchRows = all.stream().filter(r -> !r.affix()).toList();
        affRows = all.stream().filter(Row::affix).toList();
        gems = affixesEnabled() ? ApothicRows.gems(cur) : List.of();
        nameText = cur.isEmpty() ? title : cur.getHoverName();

        int stripW = SLOTS.length * SLOT + (SLOTS.length - 1) * SLOT_GAP;
        int gridW = 0, affW = 0;

        for (Row r : enchRows)
            gridW = Math.max(gridW, labelWidth(r));

        for (Row r : affRows)
            affW = Math.max(affW, labelWidth(r));

        int maxW = Math.max(160, Math.min(360, width * 6 / 10));

        cw = Math.min(Math.max(Math.max(stripW, 120), Math.max(font.width(nameText), Math.max(gridW * 2, affW))), maxW);

        int enchLines = (enchRows.size() + 1) / 2;
        int enchH = enchRows.isEmpty() ? 0 : HDR + enchLines * ROW + 3;
        int perGemRow = Math.max(1, cw / SLOT);
        int gemLines = gems.isEmpty() ? 0 : (gems.size() + perGemRow - 1) / perGemRow;
        int gemH = gems.isEmpty() ? 0 : HDR + gemLines * SLOT + 3;
        int fixed = SLOT + 4 + 12;
        int hintH = 12;
        int affHdr = affRows.isEmpty() ? 0 : HDR;
        int room = height - 28 - (fixed + enchH + gemH + hintH + affHdr);

        visibleAff = affRows.isEmpty() ? 0 : Math.max(Math.min(3, affRows.size()), Math.min(affRows.size(), room / ROW));
        affixScroll = Math.max(0, Math.min(affixScroll, affRows.size() - visibleAff));

        int affH = affRows.isEmpty() ? 0 : affHdr + visibleAff * ROW + 3;

        ch = fixed + enchH + affH + gemH + hintH;
        cx = (width - cw) / 2;
        cy = Math.max(8, (height - ch) / 2);

        yName = cy + SLOT + 4;
        yEnchHdr = yName + 12;
        yAffHdr = yEnchHdr + enchH;
        yGemHdr = yAffHdr + affH;
        yHint = yGemHdr + gemH;

        hits.clear();
        slotHits.clear();
        gemHits.clear();

        for (int i = 0; i < SLOTS.length; i++) {
            ItemStack item = stack(SLOTS[i]);
            List<Row> rows = rowsFor(item);
            int off = (int) rows.stream().filter(r -> !r.enabled()).count();

            slotHits.add(new SlotHit(cx + i * (SLOT + SLOT_GAP), cy, SLOTS[i], item, !rows.isEmpty(), off));
        }

        int cell = cw / 2;

        for (int i = 0; i < enchRows.size(); i++)
            hits.add(new Hit(cx + (i % 2) * cell, yEnchHdr + HDR + (i / 2) * ROW, i % 2 == 0 ? cell : cw - cell, ROW, enchRows.get(i)));

        for (int k = 0; k < visibleAff && affixScroll + k < affRows.size(); k++)
            hits.add(new Hit(cx, yAffHdr + HDR + k * ROW, cw, ROW, affRows.get(affixScroll + k)));

        for (int i = 0; i < gems.size(); i++)
            gemHits.add(new GemHit(cx + (i % perGemRow) * SLOT, yGemHdr + HDR + (i / perGemRow) * SLOT, gems.get(i)));
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Empty on purpose: a small popup over live gameplay (isPauseScreen() is false), so no blur or dim.
    }

    private FormattedCharSequence fit(Component text, int maxW) {
        if (font.width(text) <= maxW)
            return text.getVisualOrderText();

        FormattedText head = font.substrByWidth(text, maxW - font.width("..."));

        return Language.getInstance().getVisualOrder(FormattedText.composite(head, FormattedText.of("...", text.getStyle())));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (parent != null) {
            parent.render(g, -1, -1, partialTick);
            // Calm the inventory behind so the box is what you look at.
            g.fill(0, 0, width, height, 0x60000000);
        }

        super.render(g, mouseX, mouseY, partialTick);

        if (forcedMouse != null) {
            mouseX = forcedMouse[0];
            mouseY = forcedMouse[1];
        }

        layout();

        // Above everything the screen behind drew, items included, exactly like a vanilla tooltip.
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);

        TooltipRenderUtil.renderTooltipBackground(g, cx, cy, cw, ch, 0);

        List<Component> hoverTip = null;
        int besideY = -1;

        // Slot strip.
        for (SlotHit s : slotHits) {
            g.fill(s.x, s.y, s.x + SLOT, s.y + SLOT, 0xFF373737);
            g.fill(s.x + 1, s.y + 1, s.x + SLOT, s.y + SLOT, 0xFFFFFFFF);
            g.fill(s.x + 1, s.y + 1, s.x + SLOT - 1, s.y + SLOT - 1, 0xFF8B8B8B);

            if (s.slot == slot)
                g.renderOutline(s.x - 1, s.y - 1, SLOT + 2, SLOT + 2, 0xFFFFD479);

            if (!s.stack.isEmpty()) {
                g.renderItem(s.stack, s.x + 1, s.y + 1);

                if (!s.usable)
                    g.fill(s.x + 1, s.y + 1, s.x + SLOT - 1, s.y + SLOT - 1, 0x99555555);
            } else {
                ResourceLocation ghost = switch (s.slot) {
                    case HEAD -> InventoryMenu.EMPTY_ARMOR_SLOT_HELMET;
                    case CHEST -> InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE;
                    case LEGS -> InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS;
                    case FEET -> InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS;
                    case OFFHAND -> InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD;
                    default -> null;
                };

                if (ghost != null)
                    g.blit(s.x + 1, s.y + 1, 0, 16, 16, minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(ghost));
            }

            // How many entries on that item are switched off, so a glance at the strip tells the story.
            if (s.off > 0) {
                g.pose().pushPose();
                g.pose().translate(0, 0, 200);
                g.drawString(font, String.valueOf(s.off), s.x + SLOT - 1 - font.width(String.valueOf(s.off)), s.y + SLOT - 8, AMBER, true);
                g.pose().popPose();
            }

            if (inside(mouseX, mouseY, s.x, s.y, SLOT, SLOT)) {
                g.fill(s.x + 1, s.y + 1, s.x + SLOT - 1, s.y + SLOT - 1, 0x80FFFFFF);
                hoverTip = List.of(Component.translatable("foxstweaks.toggle.slot." + s.slot.getName()));
            }
        }

        // Item name.
        g.drawString(font, fit(nameText, cw), cx, yName, TEXT, true);

        if (enchRows.isEmpty() && affRows.isEmpty())
            g.drawString(font, Component.translatable("foxstweaks.toggle.empty"), cx, yEnchHdr, GREY, true);

        if (!enchRows.isEmpty())
            header(g, "foxstweaks.toggle.enchantments", yEnchHdr, enchRows);

        if (!affRows.isEmpty())
            header(g, "foxstweaks.toggle.affixes", yAffHdr, affRows);

        // Rows.
        for (Hit h : hits) {
            Row row = h.row;
            boolean hovered = inside(mouseX, mouseY, h.x, h.y, h.w, h.h);

            if (hovered && !row.locked)
                g.fill(h.x - 2, h.y, h.x + h.w, h.y + h.h, 0x30FFFFFF);

            int color = row.locked ? DIM : 0xFFFFFFFF;

            g.drawString(font, row.enabled ? ToggleIcons.on() : ToggleIcons.off(), h.x, h.y + 2, row.locked ? DIM : 0xFFFFFFFF, false);

            Component label = row.enabled ? row.label
                    : row.label.copy().withStyle(row.locked ? ChatFormatting.DARK_GRAY : ChatFormatting.GRAY);
            int maxW = h.w - GLYPH - 3;
            boolean cut = font.width(label) > maxW;

            g.drawString(font, fit(label, maxW), h.x + GLYPH, h.y + 2, color, false);

            if (hovered) {
                List<Component> tip = new ArrayList<>(row.tip);

                if (cut)
                    tip.add(0, row.label);

                if (!row.locked)
                    tip.add(Component.translatable(row.enabled ? "foxstweaks.toggle.click_off" : "foxstweaks.toggle.click_on")
                            .withStyle(ChatFormatting.DARK_GRAY));

                hoverTip = tip;
                besideY = h.y;
            }
        }

        // Affix scrollbar, in the box's padding.
        if (affRows.size() > visibleAff) {
            int track = visibleAff * ROW;
            int thumb = Math.max(8, track * visibleAff / affRows.size());
            int ty = yAffHdr + HDR + (track - thumb) * affixScroll / (affRows.size() - visibleAff);

            g.fill(cx + cw + 1, yAffHdr + HDR, cx + cw + 3, yAffHdr + HDR + track, 0xFF2B2B2B);
            g.fill(cx + cw + 1, ty, cx + cw + 3, ty + thumb, 0xFFB0B0B0);
        }

        // Gem sockets (read-only).
        if (!gems.isEmpty()) {
            long filled = gems.stream().filter(st -> !st.isEmpty()).count();

            g.drawString(font, Component.translatable("foxstweaks.toggle.sockets").getString().toUpperCase(Locale.ROOT), cx, yGemHdr + 1, HEADER, false);

            String count = filled + "/" + gems.size();

            g.drawString(font, count, cx + cw - font.width(count), yGemHdr + 1, HEADER, false);

            for (GemHit gem : gemHits) {
                g.fill(gem.x, gem.y, gem.x + SLOT, gem.y + SLOT, 0xFF373737);
                g.fill(gem.x + 1, gem.y + 1, gem.x + SLOT, gem.y + SLOT, 0xFFFFFFFF);
                g.fill(gem.x + 1, gem.y + 1, gem.x + SLOT - 1, gem.y + SLOT - 1, 0xFF3B3B3B);

                if (!gem.stack.isEmpty())
                    g.renderItem(gem.stack, gem.x + 1, gem.y + 1);

                if (!gem.stack.isEmpty() && inside(mouseX, mouseY, gem.x, gem.y, SLOT, SLOT)) {
                    g.fill(gem.x + 1, gem.y + 1, gem.x + SLOT - 1, gem.y + SLOT - 1, 0x60FFFFFF);
                    hoverTip = Screen.getTooltipFromItem(minecraft, gem.stack);
                    besideY = -1;
                }
            }
        }

        g.drawString(font, fit(Component.translatable("foxstweaks.toggle.hint"), cw), cx, yHint + 3, HEADER, false);

        g.pose().popPose();

        if (hoverTip != null) {
            if (besideY < 0) {
                g.renderComponentTooltip(font, hoverTip, mouseX, mouseY);
            } else {
                // Beside the box on the hovered line so it never covers the list; on the left when the right has no room.
                int by = besideY;
                List<FormattedCharSequence> lines = hoverTip.stream().map(Component::getVisualOrderText).toList();

                g.renderTooltip(font, lines, (sw, sh, mx, my, tw, th) -> {
                    int x = cx + cw + 8 + tw <= sw - 2 ? cx + cw + 8 : Math.max(2, cx - 8 - tw);
                    int y = Math.max(2, Math.min(by - 4, sh - th - 2));

                    return new org.joml.Vector2i(x, y);
                }, mouseX, mouseY);
            }
        }
    }

    private void header(GuiGraphics g, String key, int y, List<Row> rows) {
        long on = rows.stream().filter(Row::enabled).count();
        String count = on + "/" + rows.size() + " " + Component.translatable("foxstweaks.toggle.on").getString();

        g.drawString(font, Component.translatable(key).getString().toUpperCase(Locale.ROOT), cx, y + 1, HEADER, false);
        g.drawString(font, count, cx + cw - font.width(count), y + 1, on < rows.size() ? AMBER : HEADER, false);
    }

    // ---------------------------------------------------------------------------------------------
    // Input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        layout();

        for (SlotHit s : slotHits) {
            if (inside(mouseX, mouseY, s.x, s.y, SLOT, SLOT)) {
                if (s.usable) {
                    slot = s.slot;
                    affixScroll = 0;
                }

                return true;
            }
        }

        for (Hit h : hits) {
            if (inside(mouseX, mouseY, h.x, h.y, h.w, h.h)) {
                if (!h.row.locked)
                    toggle(h.row);

                return true;
            }
        }

        // A click outside the box closes it, like dismissing a popup.
        if (!inside(mouseX, mouseY, cx - 4, cy - 4, cw + 8, ch + 8))
            onClose();

        return true;
    }

    private void toggle(Row row) {
        boolean enable = !row.enabled;
        ItemStack live = stack(slot);

        if (row.affix)
            ApothicRows.setLocal(live, row.id, enable);
        else if (minecraft != null && minecraft.level != null)
            EnchantToggle.setEnabled(live, minecraft.level.registryAccess(), row.id, enable);

        PacketDistributor.sendToServer(new ToggleEntryPayload(slot, row.affix, row.id, enable));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        affixScroll = Math.max(0, Math.min(affixScroll - (int) Math.signum(scrollY), Math.max(0, affRows.size() - visibleAff)));

        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // The key that opened the screen, or the inventory key, closes it again.
        if (FoxsTweaksKeys.TOGGLE_AFFIXES.matches(keyCode, scanCode) || minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();

            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Dev harness hooks

    /** Scroll so the row for {@code id} is on screen. */
    public void reveal(ResourceLocation id) {
        layout();

        for (int i = 0; i < affRows.size(); i++) {
            if (affRows.get(i).id.equals(id) && (i < affixScroll || i >= affixScroll + visibleAff))
                affixScroll = Math.max(0, Math.min(i - 1, affRows.size() - visibleAff));
        }

        layout();
    }

    /** Where a row is, in GUI coordinates, or null when not on screen. */
    public int[] centerOfRow(ResourceLocation id) {
        layout();

        for (Hit h : hits) {
            if (h.row.id.equals(id))
                return new int[] { h.x + h.w / 2, h.y + h.h / 2 };
        }

        return null;
    }

    public int[] centerOfTab(EquipmentSlot s) {
        layout();

        for (SlotHit h : slotHits) {
            if (h.slot == s)
                return new int[] { h.x + SLOT / 2, h.y + SLOT / 2 };
        }

        return null;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
