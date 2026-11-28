package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.client.*;
import dev.watdafawx.foxstweaks.hologram.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/** Real dispatcher/model check, plus backward seek and independent-instance checks. */
public final class HologramEntityAnimCheck {
    private static final Logger LOG = LogUtils.getLogger();
    private record Step(int waitTicks, Runnable run) {}
    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static int failures, tick, nextAt, index;
    private static boolean worldRequested;
    private static String mob;
    private static HologramEffect.Type animation;
    private static double seconds;
    private static List<Float> pose;
    private static Throwable renderFailure;
    private static String renderedMob;
    private static HologramEffect.Type renderedAnimation;
    private static double renderedSeconds;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(HologramEntityAnimCheck::onTick);
        add(40, () -> Minecraft.getInstance().setScreen(new View()));
        for (String name : List.of("zombie", "skeleton", "wolf", "horse", "bee", "parrot", "bat", "chicken",
                "phantom", "allay", "warden", "camel", "frog", "axolotl", "cod", "squid", "guardian",
                "armadillo", "breeze", "sniffer", "iron_golem", "villager")) {
            for (var type : HologramEntityLife.offered("minecraft:" + name)) {
                final List<List<Float>> samples = new ArrayList<>();
                add(1, () -> { mob = name; animation = type; seconds = 0.15; });
                add(3, () -> { samples.add(pose); seconds = 0.7; });
                add(3, () -> { samples.add(pose); seconds = 0.15; });
                add(3, () -> {
                    check(name + " / " + type + " renders", renderFailure == null && pose != null, String.valueOf(renderFailure));
                    check(name + " / " + type + " backward seek deterministic", Objects.equals(samples.get(0), pose), "pose differs");
                    boolean changed = !Objects.equals(samples.get(0), samples.get(1));
                    log("POSE " + name + " / " + type + " = " + (changed ? "works" : "no model-part change (renderer transform/flash may still change)"));
                    if (type == HologramEffect.Type.WALK && List.of("zombie", "skeleton", "wolf", "horse", "camel", "warden", "armadillo", "sniffer", "iron_golem", "villager").contains(name))
                        check(name + " walk changes model", changed, "frozen limbs");
                    if (type == HologramEffect.Type.FLY || type == HologramEffect.Type.SWIM || type == HologramEffect.Type.CROAK || type == HologramEffect.Type.ROAR || type == HologramEffect.Type.ROLL || type == HologramEffect.Type.SNIFF)
                        check(name + " " + type + " changes model", changed, "frozen model");
                    if (type == HologramEffect.Type.WALK) {
                        var mc = Minecraft.getInstance();
                        Screenshot.grab(mc.gameDirectory, "entity-" + name + ".png", mc.getMainRenderTarget(), m -> {});
                    }
                });
            }
        }
        for (String name : List.of("parrot", "chicken", "zombie")) {
            final List<List<Float>> seam = new ArrayList<>();
            add(1, () -> {
                mob = name;
                animation = name.equals("zombie") ? HologramEffect.Type.WALK : HologramEffect.Type.FLY;
                seconds = 0.2499;
            });
            add(3, () -> { seam.add(pose); seconds = 0.2501; });
            add(3, () -> {
                float difference = 0;
                for (int i = 0; i < pose.size(); i++)
                    difference = Math.max(difference, Math.abs(pose.get(i) - seam.getFirst().get(i)));
                check(name + " smooth across tick boundary", difference < 0.05F, "max pose delta=" + difference);
            });
        }
        add(1, () -> {
            var layer = HologramLayer.entity("minecraft:zombie", 0).withUid("mob");
            var a = HologramEntities.get("one", layer, 0, 0, 0, 0, 1);
            var b = HologramEntities.get("two", layer, 0, 0, 0, 0, 1);
            check("independent same-type entities and ids", a != b && a.getId() != b.getId(), "shared controller key");

            // Mob state checks
            var sheep = (net.minecraft.world.entity.animal.Sheep) HologramEntities.get("st-sheep",
                    HologramLayer.entity("minecraft:sheep", 0).withUid("s")
                            .withProps(Map.of("color", "pink", "sheared", "true")),
                    0, 0, 0, 0, 1);
            check("sheep state (pink + sheared)", sheep != null
                    && sheep.getColor() == net.minecraft.world.item.DyeColor.PINK && sheep.isSheared(),
                    String.valueOf(sheep));

            var creeper = (net.minecraft.world.entity.monster.Creeper) HologramEntities.get("st-creeper",
                    HologramLayer.entity("minecraft:creeper", 0).withUid("c")
                            .withProps(Map.of("powered", "true")),
                    0, 0, 0, 0, 1);
            check("creeper state (charged)", creeper != null && creeper.isPowered(), String.valueOf(creeper));

            var wolf = (net.minecraft.world.entity.animal.Wolf) HologramEntities.get("st-wolf",
                    HologramLayer.entity("minecraft:wolf", 0).withUid("w")
                            .withProps(Map.of("baby", "true", "tamed", "true", "sitting", "true", "angry", "true")),
                    0, 0, 0, 0, 1);
            check("wolf state (baby+tamed+sitting+angry)", wolf != null
                    && wolf.isBaby() && wolf.isTame() && wolf.isInSittingPose() && wolf.isAngry(),
                    String.valueOf(wolf));

            var fox = (net.minecraft.world.entity.animal.Fox) HologramEntities.get("st-fox",
                    HologramLayer.entity("minecraft:fox", 0).withUid("f")
                            .withProps(Map.of("variant", "snow", "sleeping", "true")),
                    0, 0, 0, 0, 1);
            check("fox state (snow + sleeping)", fox != null
                    && fox.getVariant() == net.minecraft.world.entity.animal.Fox.Type.SNOW && fox.isSleeping(),
                    String.valueOf(fox));

            var slime = (net.minecraft.world.entity.monster.Slime) HologramEntities.get("st-slime",
                    HologramLayer.entity("minecraft:slime", 0).withUid("sl")
                            .withProps(Map.of("size", "4")),
                    0, 0, 0, 0, 1);
            check("slime state (size 4)", slime != null && slime.getSize() == 4,
                    slime == null ? "null" : "size=" + slime.getSize());

            var golem = (net.minecraft.world.entity.animal.IronGolem) HologramEntities.get("st-golem",
                    HologramLayer.entity("minecraft:iron_golem", 0).withUid("g")
                            .withProps(Map.of("cracks", "high")),
                    0, 0, 0, 0, 1);
            check("iron_golem state (high cracks)", golem != null
                    && golem.getCrackiness() == net.minecraft.world.entity.Crackiness.Level.HIGH,
                    golem == null ? "null" : String.valueOf(golem.getCrackiness()));

            // Dynamic AnimationState discovery & clip playback checks
            check("warden discovers sonic_boom clip",
                    HologramEntityStates.animations("minecraft:warden").contains("sonic_boom"),
                    String.valueOf(HologramEntityStates.animations("minecraft:warden")));
            check("breeze discovers shoot clip",
                    HologramEntityStates.animations("minecraft:breeze").contains("shoot"),
                    String.valueOf(HologramEntityStates.animations("minecraft:breeze")));
            check("camel discovers dash clip",
                    HologramEntityStates.animations("minecraft:camel").contains("dash"),
                    String.valueOf(HologramEntityStates.animations("minecraft:camel")));

            // Path auto-walk check
            var walker = (LivingEntity) HologramEntities.get("st-walk",
                    HologramLayer.entity("minecraft:zombie", 0).withUid("wk"),
                    0, 0, 0, 0, 1);
            HologramEntities.applyAnimations(walker, List.of(), List.of(), Map.of(), true, 1.5);
            check("path auto-walk drives walkAnimation", walker != null && walker.walkAnimation.speed() > 0.5F,
                    walker == null ? "null" : "speed=" + walker.walkAnimation.speed());

            log("GeckoLib/AzureLib: NOT VERIFIED (no mod fixture)");
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks-hologram-entity-anim-check.txt"), REPORT);
            } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            Minecraft.getInstance().stop();
        });
    }

    private static final class View extends Screen {
        View() { super(Component.literal("Entity animations")); }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void render(GuiGraphics g, int mx, int my, float partial) {
            g.fill(0, 0, width, height, 0xff202838);
            if (mob == null)
                return;
            g.drawString(font, mob + " / " + animation + " / " + seconds, 20, 20, 0xffffff);
            try {
                var mc = Minecraft.getInstance();
                var layer = HologramLayer.entity("minecraft:" + mob, 0).withUid("check");
                var entity = (LivingEntity) HologramEntities.get("check", layer, 0, 0, 0, 0, (float) seconds);
                HologramEntities.applyAnimations(entity, List.of(HologramEffect.of(animation)), List.of(), seconds);
                var dispatcher = mc.getEntityRenderDispatcher();
                dispatcher.prepare(mc.level, mc.gameRenderer.getMainCamera(), null);
                dispatcher.setRenderShadow(false);
                g.pose().pushPose();
                g.pose().translate(width / 2.0, height * 0.8, 100);
                g.pose().scale(65, -65, 65);
                dispatcher.render(entity, 0, 0, 0, 0, (float) (seconds * 20 % 1), g.pose(), g.bufferSource(), 0xf000f0);
                g.flush();
                g.pose().popPose();
                dispatcher.setRenderShadow(true);
                Object model = ((LivingEntityRenderer<?, ?>) dispatcher.getRenderer(entity)).getModel();
                List<Float> values = new ArrayList<>();
                Set<ModelPart> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Class<?> c = model.getClass(); c != null; c = c.getSuperclass()) {
                    for (var f : c.getDeclaredFields()) {
                        if (f.getType() != ModelPart.class)
                            continue;
                        f.setAccessible(true);
                        ModelPart root = (ModelPart) f.get(model);
                        if (root != null)
                            root.getAllParts().forEach(p -> {
                                if (seen.add(p))
                                    Collections.addAll(values, p.xRot, p.yRot, p.zRot, p.x, p.y, p.z);
                            });
                    }
                }
                pose = values;
                renderFailure = null;
                renderedMob = mob;
                renderedAnimation = animation;
                renderedSeconds = seconds;
            } catch (Throwable t) {
                renderFailure = t;
                pose = null;
            }
        }
    }
    // ---------------------------------------------------------------- harness

    private static void add(int waitTicks, Runnable run) {
        STEPS.add(new Step(waitTicks, run));
    }

    private static <T> T onServer(Function<ServerPlayer, T> fn) {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        return server.submit(() -> fn.apply(server.getPlayerList().getPlayer(mc.player.getUUID()))).join();
    }

    private static void log(String line) {
        LOG.info("HologramEntityAnimCheck {}", line);
        REPORT.add(line);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok)
            failures++;
        log((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    private static void onTick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);

        if (!worldRequested && mc.getOverlay() == null
                && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
            worldRequested = true;
            createWorld(mc);
        }

        if (mc.player == null || mc.getSingleplayerServer() == null || index >= STEPS.size())
            return;

        // Catch-up client ticks can run several steps without a single intervening render.
        if (mob != null && renderFailure == null && (!mob.equals(renderedMob)
                || animation != renderedAnimation || seconds != renderedSeconds))
            return;

        tick++;
        if (nextAt == 0)
            nextAt = tick + STEPS.get(0).waitTicks;
        if (tick < nextAt)
            return;

        var step = STEPS.get(index++);
        try {
            step.run.run();
        } catch (Throwable t) {
            LOG.error("HologramEntityAnimCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }
        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-entityanimcheck";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(world);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException ignored) {
            }
        }
        var settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                        .value().createWorldDimensions(),
                new TitleScreen());
    }

    private HologramEntityAnimCheck() {
    }
}
