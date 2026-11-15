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
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Client -> server actions for a Shrink "Shrinking Device" found in the sender's hands, a Curios slot or inventory:
 * <ul>
 *   <li>{@link ShrinkPayload#toggle} - shrink or unshrink a target (the looked-at living entity within reach, or the
 *       sender), the same as right-clicking it with the device or sneak-using it: same power check and cost, same
 *       scale.</li>
 *   <li>{@link ShrinkPayload#openMenu} - open Shrink's own device menu. It reads and saves the main-hand item;
 *       {@code ShrinkDeviceContainerMixin}, {@code ShrinkScreenMixin} and {@code ShrinkDevicePacketMixin} point
 *       it at {@link #deviceFor} instead.</li>
 * </ul>
 * Shrink is no compile dependency; its members are looked up once by name and everything fails soft (logs once,
 * does nothing) if a Shrink update renames them.
 */
public final class ShrinkNetwork {
    public static final String SHRINK = "shrink";
    public static final ResourceLocation DEVICE_ID = ResourceLocation.fromNamespaceAndPath(SHRINK, "shrinking_device");
    private static final Logger LOG = LogUtils.getLogger();
    private static final int TOGGLE = 0;
    private static final int OPEN_MENU = 1;
    public static final int SELF = -1;

    private static boolean resolved;
    private static Method hasPower;
    private static Method usePower;
    private static Method getScale;
    private static Method writeScale;
    private static Method createModifier;
    private static ResourceLocation modifierId;

    private ShrinkNetwork() {
    }

    public record ShrinkPayload(int action, int target) implements CustomPacketPayload {
        public static final Type<ShrinkPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "shrink"));
        public static final StreamCodec<ByteBuf, ShrinkPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ShrinkPayload::action,
                ByteBufCodecs.VAR_INT, ShrinkPayload::target,
                ShrinkPayload::new);

        /** @param target a living entity's id, or {@link #SELF} */
        public static ShrinkPayload toggle(int target) {
            return new ShrinkPayload(TOGGLE, target);
        }

        public static ShrinkPayload openMenu() {
            return new ShrinkPayload(OPEN_MENU, SELF);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("3")
                .optional()
                .playToServer(ShrinkPayload.TYPE, ShrinkPayload.CODEC, (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player)
                        handle(player, payload);
                }));
    }

    public static boolean isAvailable() {
        return ModList.get().isLoaded(SHRINK);
    }

    private static void handle(ServerPlayer player, ShrinkPayload payload) {
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

        try {
            if (payload.action() == OPEN_MENU) {
                if (!ShrinkMenu.open(player, device))
                    LOG.warn("Fox's Tweaks: Shrink's device is no longer a menu provider");
            }
            else if (payload.action() == TOGGLE)
                toggle(player, device, stack, payload.target());
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.error("Fox's Tweaks: Shrink action failed", e);
        }
    }

    private static void toggle(ServerPlayer player, Item device, ItemStack stack, int targetId) throws ReflectiveOperationException {
        LivingEntity target = player;

        if (targetId != SELF) {
            Entity entity = player.level().getEntity(targetId);

            // Same reach as a right-click on the entity; a stale or far id is ignored, not turned into self.
            if (!(entity instanceof LivingEntity living) || !living.isAlive() || !player.canInteractWithEntity(living, 1.0))
                return;

            target = living;
        }

        AttributeInstance scale = target.getAttribute(Attributes.SCALE);

        if (scale == null) {
            if (target == player)
                player.displayClientMessage(Component.translatable("shrink.message.missing"), false);
            return;
        }

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
    }

    /** Main hand, offhand, Curios slots, then the rest of the inventory (hotbar first). Works on either side. */
    public static ItemStack findDevice(Player player, Item device) {
        if (player.getMainHandItem().is(device))
            return player.getMainHandItem();

        if (player.getOffhandItem().is(device))
            return player.getOffhandItem();

        if (ModList.get().isLoaded(FoxsTweaks.CURIOS)) {
            ItemStack curio = ShrinkCurios.find(player, device);

            if (!curio.isEmpty())
                return curio;
        }

        var inventory = player.getInventory();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);

            if (stack.is(device))
                return stack;
        }

        return ItemStack.EMPTY;
    }

    /**
     * For Shrink's menu, screen and save packet, which use the main-hand item: that item if it is the device, else
     * the device found by {@link #findDevice}, else the main-hand item unchanged.
     */
    public static ItemStack deviceFor(Player player, ItemStack mainHand) {
        if (player == null || !isAvailable())
            return mainHand;

        Item device = BuiltInRegistries.ITEM.get(DEVICE_ID);

        if (mainHand.is(device))
            return mainHand;

        ItemStack found = findDevice(player, device);
        return found.isEmpty() ? mainHand : found;
    }

    /** The device's set scale, or {@code NaN} if Shrink's API can't be reached. */
    public static double readScale(ItemStack stack) {
        Item device = stack.getItem();

        if (!resolve(device))
            return Double.NaN;

        try {
            return (Double) getScale.invoke(device, stack);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Double.NaN;
        }
    }

    private static synchronized boolean resolve(Item device) {
        if (resolved)
            return hasPower != null;

        resolved = true;

        try {
            Class<?> type = device.getClass();
            usePower = type.getMethod("usePower", Player.class, ItemStack.class);
            getScale = type.getMethod("getScale", ItemStack.class);
            writeScale = type.getMethod("writeScale", ItemStack.class, double.class);
            createModifier = type.getMethod("createModifier", double.class);
            Field id = type.getField("SHRINKING_DEVICE_ID");
            modifierId = (ResourceLocation) id.get(null);
            hasPower = type.getMethod("hasPower", Player.class, ItemStack.class);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            hasPower = null;
            LOG.error("Fox's Tweaks: Shrink's device API changed, shrink hotkey disabled", e);
            return false;
        }
    }
}
