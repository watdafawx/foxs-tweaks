package dev.mtop.foxstweaks.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mtop.foxstweaks.FoxsTweaks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

// Bus.MOD is deprecated-for-removal in newer NeoForge, but it is the correct API on 21.1 and
// keeps key registration off the dedicated server without hand-rolling a Dist check.
@SuppressWarnings("removal")
@EventBusSubscriber(modid = FoxsTweaks.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class FoxsTweaksKeys {
    public static final String CATEGORY = "key.categories." + FoxsTweaks.MODID;

    /**
     * Left Control by default, rebindable in Options -> Controls. Deliberately not Shift: Relics
     * claims Left Shift for opening its description screen, and Apotheosis hides affix detail
     * behind the normal tooltip, which is the collision this whole feature exists to sidestep.
     */
    public static final KeyMapping SHOW_AFFIX_INFO = new KeyMapping(
            "key." + FoxsTweaks.MODID + ".show_affix_info",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_CONTROL,
            CATEGORY);

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(SHOW_AFFIX_INFO);
    }

    /**
     * Whether the binding is currently held.
     *
     * <p>{@link KeyMapping#isDown()} stays false while a screen is open, and a tooltip only exists
     * while a screen is open, so the device has to be polled directly.
     */
    public static boolean isShowAffixInfoHeld() {
        InputConstants.Key key = SHOW_AFFIX_INFO.getKey();

        if (key.getValue() == InputConstants.UNKNOWN.getValue())
            return false;

        long window = Minecraft.getInstance().getWindow().getWindow();

        return switch (key.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(window, key.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
            default -> false;
        };
    }
}
