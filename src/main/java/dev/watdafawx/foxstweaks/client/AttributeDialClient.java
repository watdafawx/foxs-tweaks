package dev.watdafawx.foxstweaks.client;

import java.util.List;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.attribute.AttributeDial;
import dev.watdafawx.foxstweaks.attribute.AttributeDialNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client half of the attribute dial: the table the server last sent, the hotkey, and the button on the inventory screen.
 * The screen itself is {@link AttributeDialScreen}.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public final class AttributeDialClient {
    private static volatile List<AttributeDial.Entry> entries = List.of();
    /** Bumped on every table from the server, so an open screen knows to rebuild its rows. */
    private static volatile int version;

    private AttributeDialClient() {
    }

    public static void receive(List<AttributeDial.Entry> table) {
        entries = List.copyOf(table);
        version++;
    }

    public static List<AttributeDial.Entry> entries() {
        return entries;
    }

    public static int version() {
        return version;
    }

    public static boolean available() {
        Minecraft mc = Minecraft.getInstance();
        return Config.ATTRIBUTE_DIAL.get() && mc.player != null && mc.getConnection() != null
                && mc.getConnection().hasChannel(AttributeDialNetwork.Open.TYPE);
    }

    public static void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (available())
            PacketDistributor.sendToServer(payload);
    }

    public static void open(Screen parent) {
        if (available())
            Minecraft.getInstance().setScreen(new AttributeDialScreen(parent));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        while (FoxsTweaksKeys.OPEN_ATTRIBUTE_DIAL.consumeClick()) {
            if (mc.screen == null)
                open(null);
        }
    }

    /** The same key with the inventory (or any container) open: the dial opens over it and closing returns to it. */
    @SubscribeEvent
    public static void onScreenKey(ScreenEvent.KeyPressed.Pre event) {
        Screen screen = event.getScreen();

        if (!(screen instanceof AbstractContainerScreen<?>) || screen.getFocused() instanceof EditBox)
            return;

        if (FoxsTweaksKeys.OPEN_ATTRIBUTE_DIAL.matches(event.getKeyCode(), event.getScanCode()) && available()) {
            open(screen);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onInventoryInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof InventoryScreen inventory && Config.ATTRIBUTE_DIAL.get())
            event.addListener(new DialButton(inventory));
    }

    /**
     * Small button in the inventory's top-right corner. It follows the inventory every frame because Apothic
     * Attributes' own panel shifts the whole inventory sideways when it opens.
     */
    public static final class DialButton extends AbstractWidget {
        private final InventoryScreen inventory;

        public DialButton(InventoryScreen inventory) {
            super(0, 0, 12, 12, Component.translatable("foxstweaks.dial.button"));
            this.inventory = inventory;
            reposition();
            setTooltip(net.minecraft.client.gui.components.Tooltip.create(getMessage()));
        }

        private void reposition() {
            setX(inventory.getGuiLeft() + inventory.getXSize() - 16);
            setY(inventory.getGuiTop() + 4);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            open(inventory);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            reposition();
            int x = getX(), y = getY();
            boolean hot = isHoveredOrFocused();
            g.fill(x, y, x + 12, y + 12, 0xFF000000);
            g.fill(x + 1, y + 1, x + 11, y + 11, hot ? 0xFFB0B0B0 : 0xFF8B8B8B);
            g.fill(x + 1, y + 1, x + 11, y + 2, 0xFFFFFFFF);
            g.fill(x + 1, y + 1, x + 2, y + 11, 0xFFFFFFFF);
            g.fill(x + 10, y + 1, x + 11, y + 11, 0xFF555555);
            g.fill(x + 1, y + 10, x + 11, y + 11, 0xFF555555);

            // Three slider tracks with a knob each: reads as "dial".
            int[] knob = {7, 3, 6};
            for (int i = 0; i < 3; i++) {
                int ty = y + 3 + i * 3;
                g.fill(x + 2, ty, x + 10, ty + 1, 0xFF373737);
                g.fill(x + knob[i] - 1, ty - 1, x + knob[i] + 1, ty + 2, 0xFFFFB629);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
