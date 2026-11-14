package dev.watdafawx.foxstweaks.shrink;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Client -> server: toggle shrinking with a Shrink "Shrinking Device" from anywhere in the sender's inventory, the
 * same as sneak-using it from the hand: needs the same power, costs the same power, uses the device's set scale.
 * Shrink is no compile dependency; its device methods are looked up once by name and the toggle fails soft (logs
 * once, does nothing) if a Shrink update renames them.
 */
public final class ShrinkNetwork {
    public static final String SHRINK = "shrink";
    private static final Logger LOG = LogUtils.getLogger();
    private static final ResourceLocation DEVICE_ID = ResourceLocation.fromNamespaceAndPath(SHRINK, "shrinking_device");

    private static boolean resolved;
    private static Method hasPower;
    private static Method usePower;
    private static Method getScale;
    private static Method createModifier;
    private static ResourceLocation modifierId;

    private ShrinkNetwork() {
    }

    public record ToggleShrinkPayload() implements CustomPacketPayload {
        public static final ToggleShrinkPayload INSTANCE = new ToggleShrinkPayload();
        public static final Type<ToggleShrinkPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "toggle_shrink"));
        public static final StreamCodec<ByteBuf, ToggleShrinkPayload> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .optional()
                .playToServer(ToggleShrinkPayload.TYPE, ToggleShrinkPayload.CODEC, (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player)
                        toggle(player);
                }));
    }

    public static boolean isAvailable() {
        return ModList.get().isLoaded(SHRINK);
    }

    private static void toggle(ServerPlayer player) {
        if (!Config.SHRINK_HOTKEY.get() || !isAvailable() || player.isSpectator())
            return;

        Item device = BuiltInRegistries.ITEM.get(DEVICE_ID);
        ItemStack stack = findDevice(player, device);

        if (stack.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.foxstweaks.shrink.no_device"), true);
            return;
        }

        if (!resolve(device))
            return;

        AttributeInstance scale = player.getAttribute(Attributes.SCALE);

        if (scale == null) {
            player.displayClientMessage(Component.translatable("shrink.message.missing"), false);
            return;
        }

        try {
            if (!(Boolean) hasPower.invoke(device, player, stack)) {
                player.displayClientMessage(Component.translatable("shrink.message.power"), false);
                return;
            }

            // Same test as ShrinkAPI.isEntityShrunk: any scale modifier counts as shrunk.
            if (scale.getModifiers().isEmpty())
                scale.addPermanentModifier((AttributeModifier) createModifier.invoke(null, (Double) getScale.invoke(device, stack)));
            else
                scale.removeModifier(modifierId);

            usePower.invoke(device, player, stack);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.error("Fox's Tweaks: Shrink toggle failed", e);
        }
    }

    /** Main hand, offhand, then the rest of the inventory (hotbar first). */
    private static ItemStack findDevice(Player player, Item device) {
        if (player.getMainHandItem().is(device))
            return player.getMainHandItem();

        if (player.getOffhandItem().is(device))
            return player.getOffhandItem();

        var inventory = player.getInventory();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);

            if (stack.is(device))
                return stack;
        }

        return ItemStack.EMPTY;
    }

    private static boolean resolve(Item device) {
        if (resolved)
            return hasPower != null;

        resolved = true;

        try {
            Class<?> type = device.getClass();
            hasPower = type.getMethod("hasPower", Player.class, ItemStack.class);
            usePower = type.getMethod("usePower", Player.class, ItemStack.class);
            getScale = type.getMethod("getScale", ItemStack.class);
            createModifier = type.getMethod("createModifier", double.class);
            Field id = type.getField("SHRINKING_DEVICE_ID");
            modifierId = (ResourceLocation) id.get(null);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            hasPower = null;
            LOG.error("Fox's Tweaks: Shrink's device API changed, shrink hotkey disabled", e);
            return false;
        }
    }
}
