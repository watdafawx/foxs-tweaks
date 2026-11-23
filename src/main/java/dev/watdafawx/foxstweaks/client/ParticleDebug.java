package dev.watdafawx.foxstweaks.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Client-only {@code /foxstweaks debug particles [seconds]} command: logs the exact particle ID
 * and calling class/method for every particle spawned within 3 blocks of the player, so mystery
 * particles in large modpacks can be identified in seconds.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public final class ParticleDebug {
    private static final Logger LOG = LogUtils.getLogger();

    private static volatile boolean active;
    private static volatile long endTimeMs;
    private static final Set<String> SEEN = new HashSet<>();

    private ParticleDebug() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal(FoxsTweaks.MODID)
                        .then(Commands.literal("debug")
                                .then(Commands.literal("particles")
                                        .executes(ctx -> arm(3))
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 15))
                                                .executes(ctx -> arm(IntegerArgumentType.getInteger(ctx, "seconds")))))));
    }

    private static int arm(int seconds) {
        Minecraft mc = Minecraft.getInstance();
        SEEN.clear();
        endTimeMs = System.currentTimeMillis() + seconds * 1000L;
        active = true;

        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[Fox's Tweaks] Logging particles within 3 blocks for "
                    + seconds + "s (also written to latest.log)...").withStyle(ChatFormatting.GOLD), false);

            var visibleEffects = mc.player.getActiveEffects().stream()
                    .filter(MobEffectInstance::isVisible)
                    .map(e -> e.getEffect().getRegisteredName())
                    .toList();
            if (!visibleEffects.isEmpty()) {
                String effLine = "Visible MobEffects on you: " + String.join(", ", visibleEffects);
                LOG.info("[ParticleDebug] {}", effLine);
                mc.player.displayClientMessage(Component.literal("  " + effLine).withStyle(ChatFormatting.YELLOW), false);
            }
        }
        return 1;
    }

    /** Called from {@code ParticleEngineMixin} on {@code ParticleEngine#createParticle}. */
    public static void recordOptions(ParticleOptions options, double x, double y, double z) {
        if (!active || options == null)
            return;

        if (System.currentTimeMillis() > endTimeMs) {
            active = false;
            return;
        }

        if (!nearPlayer(x, y, z))
            return;

        ResourceLocation id = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
        String particleId = id != null ? id.toString() : options.getClass().getName();
        emit(particleId, options.getClass().getName(), x, y, z);
    }

    /** Called from {@code ParticleEngineMixin} on direct {@code ParticleEngine#add(Particle)} calls. */
    public static void recordDirect(Particle particle) {
        if (!active || particle == null)
            return;

        if (System.currentTimeMillis() > endTimeMs) {
            active = false;
            return;
        }

        // Skip particles that came through ParticleEngine#createParticle (already logged above).
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().equals("net.minecraft.client.particle.ParticleEngine")
                    && frame.getMethodName().equals("createParticle")) {
                return;
            }
        }

        Vec3 pos = particle.getBoundingBox().getCenter();
        if (!nearPlayer(pos.x, pos.y, pos.z))
            return;

        emit("direct:" + particle.getClass().getSimpleName(), particle.getClass().getName(), pos.x, pos.y, pos.z);
    }

    private static boolean nearPlayer(double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return false;
        double dx = x - mc.player.getX();
        double dy = y - (mc.player.getY() + 1.0);
        double dz = z - mc.player.getZ();
        return dx * dx + dy * dy + dz * dz <= 9.0;
    }

    private static void emit(String particleId, String className, double x, double y, double z) {
        String caller = findCaller();
        String key = particleId + " <- " + caller;
        if (!SEEN.add(key))
            return;

        LOG.info("[ParticleDebug] {} (class={}) at ({}, {}, {})", key, className,
                String.format("%.1f", x), String.format("%.1f", y), String.format("%.1f", z));

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("  " + particleId).withStyle(ChatFormatting.AQUA)
                            .append(Component.literal(" <- ").withStyle(ChatFormatting.DARK_GRAY))
                            .append(Component.literal(caller).withStyle(ChatFormatting.WHITE)),
                    false);
        }
    }

    private static String findCaller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            if (cls.startsWith("java.") || cls.startsWith("dev.watdafawx.foxstweaks."))
                continue;
            if (cls.equals("net.minecraft.client.particle.ParticleEngine")
                    || cls.equals("net.minecraft.client.renderer.LevelRenderer")
                    || cls.equals("net.minecraft.world.level.Level")
                    || cls.equals("net.minecraft.client.multiplayer.ClientLevel"))
                continue;
            if (cls.equals("net.minecraft.client.multiplayer.ClientPacketListener")) {
                return "Server S2C packet (" + frame.getMethodName() + ")";
            }
            return cls + "#" + frame.getMethodName() + ":" + frame.getLineNumber();
        }
        return "unknown";
    }
}
