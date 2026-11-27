package dev.watdafawx.foxstweaks.attribute;

import java.util.List;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.client.AttributeDialClient;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Channel of the attribute dial (see {@link AttributeDial}). Optional on both sides: a client without Fox's Tweaks can
 * join, and a client with it simply finds the screen empty on a server without it ({@code hasChannel} is checked first).
 *
 * <ul>
 *   <li>{@link Open}: the screen was opened, send the current table.</li>
 *   <li>{@link Set}: dial one attribute (ratio &gt;= 1 clears it); the server answers with the table.</li>
 *   <li>{@link ResetAll}: clear every dial.</li>
 *   <li>{@link State}: the table, to the client. One row per attribute the player has.</li>
 * </ul>
 * The client only ever asks; the server clamps and applies.
 */
public final class AttributeDialNetwork {
    private AttributeDialNetwork() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, path);
    }

    public record Open() implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(id("dial_open"));
        public static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.unit(new Open());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ResetAll() implements CustomPacketPayload {
        public static final Type<ResetAll> TYPE = new Type<>(id("dial_reset"));
        public static final StreamCodec<ByteBuf, ResetAll> CODEC = StreamCodec.unit(new ResetAll());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Set(ResourceLocation attribute, double ratio) implements CustomPacketPayload {
        public static final Type<Set> TYPE = new Type<>(id("dial_set"));
        public static final StreamCodec<ByteBuf, Set> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Set::attribute,
                ByteBufCodecs.DOUBLE, Set::ratio,
                Set::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record State(List<AttributeDial.Entry> entries) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(id("dial_state"));
        private static final StreamCodec<ByteBuf, AttributeDial.Entry> ENTRY = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, AttributeDial.Entry::id,
                ByteBufCodecs.DOUBLE, AttributeDial.Entry::base,
                ByteBufCodecs.DOUBLE, AttributeDial.Entry::gear,
                ByteBufCodecs.DOUBLE, AttributeDial.Entry::ratio,
                ByteBufCodecs.DOUBLE, AttributeDial.Entry::value,
                AttributeDial.Entry::new);
        public static final StreamCodec<ByteBuf, State> CODEC = ENTRY.apply(ByteBufCodecs.list()).map(State::new, State::entries);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();

        registrar.playToServer(Open.TYPE, Open.CODEC, (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && Config.ATTRIBUTE_DIAL.get())
                send(player);
        }));

        registrar.playToServer(Set.TYPE, Set.CODEC, (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && Config.ATTRIBUTE_DIAL.get() && !player.isSpectator()) {
                AttributeDial.set(player, payload.attribute(), payload.ratio());
                send(player);
            }
        }));

        registrar.playToServer(ResetAll.TYPE, ResetAll.CODEC, (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && Config.ATTRIBUTE_DIAL.get()) {
                AttributeDial.resetAll(player);
                send(player);
            }
        }));

        registrar.playToClient(State.TYPE, State.CODEC, (payload, context) ->
                context.enqueueWork(() -> AttributeDialClient.receive(payload.entries())));
    }

    public static void send(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new State(AttributeDial.snapshot(player)));
    }
}
