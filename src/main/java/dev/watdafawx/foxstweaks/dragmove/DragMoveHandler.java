package dev.watdafawx.foxstweaks.dragmove;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.client.FoxsTweaksKeys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Screen events for {@link DragMover}: hold the drag key and drag the left mouse button over slots to select them,
 * release to move them to the other side of the container; the paste key (with the drag key held) drops the stash.
 * Everything is cancelled while it applies, so vanilla's own click / quick-craft drag never sees those events.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public final class DragMoveHandler {
    private static final int SELECT = 0x805080FF;
    private static final int STASHED = 0x6040FF60;

    private static boolean applies(Object screen) {
        return Config.enabled(Config.DRAG_MOVE) && screen instanceof AbstractContainerScreen<?> s && DragMover.supported(s) && held();
    }

    public static boolean held() {
        return DragMover.forceHeld || down(FoxsTweaksKeys.DRAG_HOLD);
    }

    /** {@link KeyMapping#isDown()} stays false while a screen is open, so the device is polled directly. */
    private static boolean down(KeyMapping mapping) {
        InputConstants.Key key = mapping.getKey();

        if (key.getValue() == InputConstants.UNKNOWN.getValue())
            return false;

        long window = Minecraft.getInstance().getWindow().getWindow();

        return switch (key.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(window, key.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
            default -> false;
        };
    }

    @SubscribeEvent
    public static void onPress(ScreenEvent.MouseButtonPressed.Pre e) {
        if (e.getButton() != 0 || !applies(e.getScreen()))
            return;

        DragMover.begin((AbstractContainerScreen<?>) e.getScreen(), e.getMouseX(), e.getMouseY());
        e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onDrag(ScreenEvent.MouseDragged.Pre e) {
        if (!DragMover.dragging() || !(e.getScreen() instanceof AbstractContainerScreen<?> s))
            return;

        DragMover.touch(s, e.getMouseX(), e.getMouseY());
        e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRelease(ScreenEvent.MouseButtonReleased.Pre e) {
        if (e.getButton() != 0 || !DragMover.dragging() || !(e.getScreen() instanceof AbstractContainerScreen<?> s))
            return;

        DragMover.touch(s, e.getMouseX(), e.getMouseY());
        DragMover.commit(s);
        e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onKey(ScreenEvent.KeyPressed.Pre e) {
        // Typing "v" into a search box must stay typing: the paste key only works with the drag key held anyway.
        if (!applies(e.getScreen()) || e.getScreen().getFocused() instanceof EditBox || !FoxsTweaksKeys.DRAG_PASTE.matches(e.getKeyCode(), e.getScanCode()))
            return;

        DragMover.paste((AbstractContainerScreen<?>) e.getScreen());
        e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post e) {
        if (Config.enabled(Config.DRAG_MOVE))
            DragMover.tick(Minecraft.getInstance(), Config.DRAG_MOVE_CLICKS_PER_TICK.get());
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        DragMover.clearStash();
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post e) {
        if (!Config.enabled(Config.DRAG_MOVE) || !(e.getScreen() instanceof AbstractContainerScreen<?> screen) || !DragMover.supported(screen))
            return;

        GuiGraphics g = e.getGuiGraphics();

        for (int index : DragMover.selected()) {
            if (index < screen.getMenu().slots.size())
                tint(g, screen, screen.getMenu().slots.get(index), SELECT);
        }

        // Stashed stacks stay marked in the inventory so you can see what the paste key will drop.
        for (DragMover.Entry entry : DragMover.STASH)
            for (Slot s : screen.getMenu().slots)
                if (DragMover.isPlayerSlot(s) && s.getContainerSlot() == entry.invIndex())
                    tint(g, screen, s, STASHED);

        if (DragMover.STASH.isEmpty())
            return;

        // "Virtual hand": the stash drawn small beside the cursor.
        g.pose().pushPose();
        g.pose().translate(e.getMouseX() + 10, e.getMouseY() - 6, 400);
        g.pose().scale(0.6f, 0.6f, 1f);

        int shown = Math.min(DragMover.STASH.size(), 8);

        for (int i = 0; i < shown; i++)
            g.renderItem(DragMover.STASH.get(i).stack(), i * 14, 0);

        if (DragMover.STASH.size() > shown)
            g.drawString(Minecraft.getInstance().font, "+" + (DragMover.STASH.size() - shown), shown * 14, 4, 0xFFFFFF);

        g.pose().popPose();
        RenderSystem.enableDepthTest();
    }

    private static void tint(GuiGraphics g, AbstractContainerScreen<?> screen, Slot s, int color) {
        int x = screen.getGuiLeft() + s.x;
        int y = screen.getGuiTop() + s.y;

        g.fill(x, y, x + 16, y + 16, color);
    }

    private DragMoveHandler() {
    }
}
