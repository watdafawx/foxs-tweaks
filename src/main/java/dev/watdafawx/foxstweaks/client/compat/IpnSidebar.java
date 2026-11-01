package dev.watdafawx.foxstweaks.client.compat;

import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftblibrary.api.sidebar.SidebarButton;
import dev.ftb.mods.ftblibrary.api.sidebar.SidebarButtonCreatedEvent;
import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.client.compat.IpnBridge.Flag;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.ArrayList;
import java.util.List;

/**
 * Inventory Profiles Next buttons in FTB Library's sidebar, next to the FTB icons. The buttons themselves
 * are plain FTB sidebar JSON ({@code assets/foxstweaks/sidebar_buttons/ipn_*.json}), so FTB draws them in
 * its own style, keeps them in its grid at every GUI scale, and lets the player move or hide them in its
 * sidebar edit mode. Clicks come back here through FTB's {@code static_method:} click action.
 *
 * <ul>
 *   <li>Editor button - click: IPN's per-screen editor. Shift: toggle "ignore" for this screen.
 *       Ctrl: for this container. Alt: "player side only" for this screen. Carries the state marker.</li>
 *   <li>Settings button - click: IPN's general config.</li>
 * </ul>
 *
 * <p>Only loaded when both FTB Library and IPN are present ({@link #init} is called behind that check).
 */
public final class IpnSidebar {
    private static final ResourceLocation EDITOR = ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "ipn_editor");
    private static final ResourceLocation SETTINGS = ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "ipn_settings");

    private IpnSidebar() {
    }

    public static void init(IEventBus modBus) {
        SidebarButtonCreatedEvent.EVENT.register(event -> {
            SidebarButton button = event.getButton();
            ResourceLocation id = button.getId();

            if (!id.equals(EDITOR) && !id.equals(SETTINGS))
                return;

            button.addVisibilityCondition(() -> Config.enabled(Config.IPN_SIDEBAR_BUTTONS) && screen() != null);

            if (id.equals(EDITOR)) {
                button.setTooltipOverride(IpnSidebar::editorTooltip);
                button.addOverlayRender(IpnSidebar::renderState);
            } else {
                button.setTooltipOverride(() -> List.of(Component.literal("IPN Settings")));
            }
        });

        if (!FMLEnvironment.production)
            modBus.addListener(IpnSidebar::devInitOrder);
    }

    // Called by FTB Library: "static_method:dev.watdafawx.foxstweaks.client.compat.IpnSidebar:clickEditor".
    public static void clickEditor() {
        AbstractContainerScreen<?> screen = screen();
        if (screen == null)
            return;

        Flag flag = Screen.hasShiftDown() ? Flag.SCREEN_IGNORE
                : Screen.hasControlDown() ? Flag.CONTAINER_IGNORE
                : Screen.hasAltDown() ? Flag.PLAYER_SIDE_ONLY
                : null;

        if (flag == null) {
            IpnBridge.openEditor(screen);
            return;
        }

        boolean on = IpnBridge.toggle(screen, flag);
        String what = switch (flag) {
            case SCREEN_IGNORE -> "ignore screen " + screen.getClass().getSimpleName();
            case CONTAINER_IGNORE -> "ignore container " + screen.getMenu().getClass().getSimpleName();
            case PLAYER_SIDE_ONLY -> "player side only for " + screen.getClass().getSimpleName();
        };

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
            mc.player.displayClientMessage(Component.literal("IPN: " + what + " ").append(onOff(on)), true);

        // Re-run init next frame so IPN adds or drops its sort/profile buttons right away. Deferred because
        // we are inside the sidebar button's own click handler, and init rebuilds every widget.
        mc.tell(() -> {
            if (mc.screen == screen)
                screen.init(mc, screen.width, screen.height);
        });
    }

    // Called by FTB Library: "static_method:dev.watdafawx.foxstweaks.client.compat.IpnSidebar:clickSettings".
    public static void clickSettings() {
        AbstractContainerScreen<?> screen = screen();
        if (screen != null)
            IpnBridge.openSettings(screen);
    }

    private static AbstractContainerScreen<?> screen() {
        return Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> acs ? acs : null;
    }

    private static List<Component> editorTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("IPN Editor"));
        AbstractContainerScreen<?> screen = screen();

        if (screen == null)
            return lines;

        lines.add(line("Shift: ignore screen", IpnBridge.get(screen, Flag.SCREEN_IGNORE)));
        lines.add(line("Ctrl: ignore container", IpnBridge.get(screen, Flag.CONTAINER_IGNORE)));
        lines.add(line("Alt: player side only", IpnBridge.get(screen, Flag.PLAYER_SIDE_ONLY)));
        lines.add(Component.literal(screen.getClass().getSimpleName() + " / " + screen.getMenu().getClass().getSimpleName())
                .withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private static Component line(String label, boolean on) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY).append(onOff(on));
    }

    private static Component onOff(boolean on) {
        return on ? Component.literal("ON").withStyle(ChatFormatting.RED) : Component.literal("off").withStyle(ChatFormatting.DARK_GRAY);
    }

    /** Small corner dot on the editor icon: red = IPN ignores this screen or container, yellow = player side only. */
    private static void renderState(GuiGraphics g, net.minecraft.client.gui.Font font, int size) {
        AbstractContainerScreen<?> screen = screen();
        if (screen == null)
            return;

        int color;
        if (IpnBridge.get(screen, Flag.SCREEN_IGNORE) || IpnBridge.get(screen, Flag.CONTAINER_IGNORE))
            color = 0xFFE03C3C;
        else if (IpnBridge.get(screen, Flag.PLAYER_SIDE_ONLY))
            color = 0xFFE8C040;
        else
            return;

        int x = size - 6;
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        g.fill(x, 0, x + 6, 6, 0xFF202024);
        g.fill(x + 1, 1, x + 5, 5, color);
        g.pose().popPose();
    }

    /**
     * Dev-run only. With a near-empty mod list the client loads so fast that IPN's own screen-render hook
     * reaches {@code Features} before IPN's init has touched {@code ConfigScreenSettings}; their static
     * initialisers are cyclic and IPN crashes with a null {@code ENABLE_PROFILES$delegate}. Real packs
     * never hit this ordering, so only the dev client pre-initialises the classes in the safe order.
     */
    private static void devInitOrder(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                Class.forName("org.anti_ad.mc.ipnext.config.ConfigScreenSettings", true, IpnSidebar.class.getClassLoader());
            } catch (Throwable t) {
                LogUtils.getLogger().warn("IPN pre-init failed", t);
            }
        });
    }
}
