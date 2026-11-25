package dev.watdafawx.foxstweaks.hologram;

import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side handler for "Grab from world": validates reach/loaded/config, captures block state
 * + block entity data or sanitized entity NBT, and replies with ready-to-add {@link HologramLayer}s.
 */
public final class HologramGrabber {
    public static final double MAX_RANGE = 64.0;
    private static final double MAX_RANGE_SQ = MAX_RANGE * MAX_RANGE;
    private static final int MAX_ATTRIBUTES_BYTES = 2048;

    // Light token-bucket rate limiter per player: allows fast Shift-grab bursts while stopping spam.
    private static final int MAX_TOKENS = 8;
    private static final long REFILL_MS = 100L;
    private static final Map<UUID, RateState> RATE_LIMIT = new HashMap<>();

    private static final String[] ALWAYS_STRIP_ENTITY_KEYS = {
            "UUID", "Pos", "Motion", "Rotation", "FallDistance", "Fire", "Air", "OnGround",
            "PortalCooldown", "Leash", "leash", "Passengers", "Brain",
            "ActiveEffects", "active_effects", "Offers", "Gossips",
            "Health", "HurtTime", "HurtByTimestamp", "DeathTime", "AbsorptionAmount", "FallFlying",
            "SleepingX", "SleepingY", "SleepingZ",
            "HomePosX", "HomePosY", "HomePosZ", "BoundX", "BoundY", "BoundZ",
            "LastRestock", "LastGossipDecay", "RestocksToday", "Xp", "FoodLevel",
            "AssignProfessionWhenSpawned", "LoveCause", "InLove", "ForcedAge",
            "AngryAt", "PersistenceRequired", "CanPickUpLoot",
            "ArmorDropChances", "HandDropChances", "body_armor_drop_chance",
            "DeathLootTable", "DeathLootTableSeed"
    };

    private static final String[] EQUIPMENT_KEYS = {
            "HandItems", "ArmorItems", "body_armor_item", "SaddleItem", "DecorItem"
    };

    private static final String[] HIDDEN_INVENTORY_KEYS = {
            "Inventory", "Items"
    };

    private static final class RateState {
        int tokens = MAX_TOKENS;
        long lastMs;
    }

    private HologramGrabber() {
    }

    public static void handle(ServerPlayer player, HologramGrabPayload payload) {
        if (!ServerConfig.hologramsEnabled()) {
            reply(player, HologramGrabReplyPayload.error(payload.requestId(), "Holograms are disabled"));
            return;
        }
        if (!allowRequest(player.getUUID())) {
            reply(player, HologramGrabReplyPayload.error(payload.requestId(), "Grabbing too fast"));
            return;
        }

        ServerLevel level = player.serverLevel();
        switch (payload.targetKind()) {
            case BLOCK -> handleBlock(player, level, payload);
            case ENTITY -> handleEntity(player, level, payload);
        }
    }

    private static void handleBlock(ServerPlayer player, ServerLevel level, HologramGrabPayload payload) {
        if (!payload.takeBlock()) {
            reply(player, HologramGrabReplyPayload.error(payload.requestId(), "Block grab is off"));
            return;
        }
        BlockPos pos = payload.blockPos();
        if (!level.isLoaded(pos)) {
            reply(player, HologramGrabReplyPayload.error(payload.requestId(), "Block is not loaded"));
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_RANGE_SQ) {
            reply(player, HologramGrabReplyPayload.error(payload.requestId(), "Target is too far away"));
            return;
        }

        BlockState state = level.getBlockState(pos);
        String blockId = HologramStates.nameOf(state);
        if (blockId.isEmpty()) {
            reply(player, HologramGrabReplyPayload.error(payload.requestId(), "Nothing to grab"));
            return;
        }

        Map<String, String> props = Map.of();
        if (payload.takeState() && !state.getProperties().isEmpty()) {
            Map<String, String> collected = new LinkedHashMap<>();
            for (var property : HologramStates.properties(state))
                collected.put(property.getName(), HologramStates.valueOf(state, property));
            props = collected;
        }

        CompoundTag beData = new CompoundTag();
        if (payload.takeContents()) {
            CompoundTag captured = Holograms.capture(level, pos);
            if (captured != null)
                beData = captured;
        }

        HologramLayer layer = HologramLayer.block(blockId, props, beData);
        String label = state.getBlock().getName().getString();
        reply(player, HologramGrabReplyPayload.success(payload.requestId(), label, List.of(layer)));
    }

    private static void handleEntity(ServerPlayer player, ServerLevel level, HologramGrabPayload payload) {
        Entity entity = level.getEntity(payload.entityId());
        if (entity == null || !entity.isAlive() || entity.isRemoved()) {
            reply(player, HologramGrabReplyPayload.error(payload.requestId(), "Entity not found"));
            return;
        }
        if (entity instanceof Player) {
            reply(player, HologramGrabReplyPayload.error(payload.requestId(), "Players cannot be grabbed"));
            return;
        }
        if (!level.isLoaded(entity.blockPosition())) {
            reply(player, HologramGrabReplyPayload.error(payload.requestId(), "Entity is not loaded"));
            return;
        }
        if (player.distanceToSqr(entity.position()) > MAX_RANGE_SQ) {
            reply(player, HologramGrabReplyPayload.error(payload.requestId(), "Target is too far away"));
            return;
        }

        ResourceLocation typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (typeKey == null) {
            reply(player, HologramGrabReplyPayload.error(payload.requestId(), "Unknown entity type"));
            return;
        }

        CompoundTag data = payload.takeState()
                ? captureEntityData(entity, payload.takeContents())
                : new CompoundTag();
        HologramLayer layer = HologramLayer.entity(typeKey.toString(), 0.0F).withData(data);
        String label = entity.getDisplayName().getString();
        reply(player, HologramGrabReplyPayload.success(payload.requestId(), label, List.of(layer)));
    }

    /**
     * Serializes an entity's visual/variant state via {@code saveWithoutId}, stripping world/AI/trade
     * state and enforcing {@link Holograms#MAX_DATA_BYTES}.
     */
    public static CompoundTag captureEntityData(Entity entity, boolean keepEquipment) {
        CompoundTag tag = new CompoundTag();
        try {
            entity.saveWithoutId(tag);
        } catch (Throwable t) {
            return new CompoundTag();
        }
        return sanitizeEntityTag(tag, keepEquipment);
    }

    static CompoundTag sanitizeEntityTag(CompoundTag tag, boolean keepEquipment) {
        for (String key : ALWAYS_STRIP_ENTITY_KEYS)
            tag.remove(key);

        // Keep attributes only if compact (preserves e.g. minecraft:generic.scale).
        stripIfOversized(tag, "attributes", MAX_ATTRIBUTES_BYTES);
        stripIfOversized(tag, "Attributes", MAX_ATTRIBUTES_BYTES);

        if (!keepEquipment) {
            for (String key : EQUIPMENT_KEYS)
                tag.remove(key);
            for (String key : HIDDEN_INVENTORY_KEYS)
                tag.remove(key);
        }

        // Progressively trim optional heavy keys if over the wire/save cap.
        if (tag.sizeInBytes() > Holograms.MAX_DATA_BYTES) {
            for (String key : HIDDEN_INVENTORY_KEYS)
                tag.remove(key);
        }
        if (tag.sizeInBytes() > Holograms.MAX_DATA_BYTES) {
            tag.remove("attributes");
            tag.remove("Attributes");
        }
        if (tag.sizeInBytes() > Holograms.MAX_DATA_BYTES) {
            tag.remove("neoforge:attachments");
            tag.remove("NeoForgeData");
            tag.remove("ForgeData");
        }
        if (tag.sizeInBytes() > Holograms.MAX_DATA_BYTES) {
            for (String key : EQUIPMENT_KEYS)
                tag.remove(key);
        }
        if (tag.sizeInBytes() > Holograms.MAX_DATA_BYTES) {
            List<String> keys = new ArrayList<>(tag.getAllKeys());
            keys.sort(Comparator.comparingInt((String k) -> {
                Tag sub = tag.get(k);
                return sub == null ? 0 : sub.sizeInBytes();
            }).reversed());
            for (String k : keys) {
                if (tag.sizeInBytes() <= Holograms.MAX_DATA_BYTES)
                    break;
                tag.remove(k);
            }
        }
        return tag.sizeInBytes() > Holograms.MAX_DATA_BYTES ? new CompoundTag() : tag;
    }

    private static void stripIfOversized(CompoundTag tag, String key, int limitBytes) {
        Tag sub = tag.get(key);
        if (sub != null && sub.sizeInBytes() > limitBytes)
            tag.remove(key);
    }

    private static synchronized boolean allowRequest(UUID uuid) {
        long now = System.currentTimeMillis();
        if (RATE_LIMIT.size() > 64)
            RATE_LIMIT.entrySet().removeIf(e -> now - e.getValue().lastMs > 60_000L);

        RateState state = RATE_LIMIT.computeIfAbsent(uuid, k -> {
            RateState s = new RateState();
            s.lastMs = now;
            return s;
        });
        long elapsed = Math.max(0L, now - state.lastMs);
        int gained = (int) (elapsed / REFILL_MS);
        if (gained > 0) {
            state.tokens = Math.min(MAX_TOKENS, state.tokens + gained);
            state.lastMs += gained * REFILL_MS;
        }
        if (state.tokens <= 0)
            return false;
        state.tokens--;
        return true;
    }

    private static void reply(ServerPlayer player, HologramGrabReplyPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }
}
