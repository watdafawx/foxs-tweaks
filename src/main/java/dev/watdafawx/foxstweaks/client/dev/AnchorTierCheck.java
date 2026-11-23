package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Dev-only check that a spawn anchor carries an Apotheosis world tier: mobs standing in it must get
 * that tier's monster augments and, at a high enough tier, invaders (bosses), with no player in the
 * dimension or with the only player 600 blocks away.
 *
 * <p>Runs the exact call Mob Grinding Utils' Dreadful Dirt makes
 * ({@code EventHooks.finalizeMobSpawn(..., MobSpawnType.NATURAL, null)}), then counts what Apotheosis
 * did to the mob: the {@code apotheosis:pinnacle/armor} attribute modifier it adds at Pinnacle, and
 * the {@code apoth.boss} marker it sets on an invader.
 *
 * <p>Run with {@code build.bat runAnchorTierCheck} on the rig, with Apotheosis, Placebo and
 * ApothicAttributes in {@code run-anchor-tier/mods}. Report:
 * {@code run-anchor-tier/foxstweaks-anchortier-check.txt}.
 *
 * <p>Prove it can fail with {@code -Dfoxstweaks.anchorTier.off=true}: every tier assertion below must
 * then report FAIL.
 */
public final class AnchorTierCheck {
    private static final Logger LOG = LogUtils.getLogger();
    private static final BlockPos ANCHOR_POS = new BlockPos(0, -59, 0);
    private static final AABB FARM_BOX = new AABB(-40, -64, -40, 40, -30, 40);
    private static final ResourceLocation PINNACLE_ARMOR = ResourceLocation.parse("apotheosis:pinnacle/armor");
    private static final int SPAWNS = 300;

    private record Step(int waitTicks, Runnable run) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static int failures;
    private static boolean worldRequested;
    private static int tick;
    private static int nextAt;
    private static int index;
    private static boolean apotheosisPresent;
    private static int guiDummyId;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(AnchorTierCheck::onTick);
        scenarios();
    }

    private static void scenarios() {
        add(40, () -> {
            apotheosisPresent = onServer(p -> net.neoforged.fml.ModList.get().isLoaded("apotheosis"));
            if (!apotheosisPresent) {
                log("SKIP: Apotheosis is not installed, so there is no world tier to check.");
                return;
            }

            // Same probe the server runs at start to warn operators instead of failing silently.
            java.util.List<String> problems = dev.watdafawx.foxstweaks.anchor.AnchorTier.probe();
            check("hook probe is clean against Apotheosis "
                    + dev.watdafawx.foxstweaks.anchor.AnchorTier.apotheosisVersion(),
                    problems.isEmpty(), String.join("; ", problems));

            guiDummyId = onServer(p -> {
                ServerLevel level = p.serverLevel();
                level.setDayTime(18000);
                p.server.setDifficulty(Difficulty.NORMAL, true);
                // Roof the farm so the surface rule has to pass on the vertical-slice check, which is
                // the branch that dereferences the nearest player without a null check in Apotheosis.
                for (int x = -24; x <= 24; x++) {
                    for (int z = -24; z <= 24; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, -45, z), Blocks.STONE.defaultBlockState());
                    }
                }

                p.teleportTo(level, 0.5, -59.0, 0.5, 0f, 0f);
                p.server.getCommands().performPrefixedCommand(
                        p.createCommandSourceStack().withPermission(4),
                        "foxstweaks anchor add tierfarm 4");
                p.server.getCommands().performPrefixedCommand(
                        p.createCommandSourceStack().withPermission(4),
                        "foxstweaks anchor tier tierfarm pinnacle");

                // An anchor dummy next to the player, for the GUI tier button (its packet is refused when
                // the player is out of range, so this runs before the move below).
                var dummy = EntityType.ARMOR_STAND.create(level);
                dummy.moveTo(2.5, -59.0, 2.5, 0f, 0f);
                dummy.setInvulnerable(true);
                dev.watdafawx.foxstweaks.anchor.SpawnDummyEvents.initDummy(level, dummy, "tier_gui", true, 1, null);
                level.addFreshEntity(dummy);
                return dummy.getId();
            });

            // Client side must stay on the client thread: setScreen from the server thread races the
            // render thread, which then draws a screen whose "minecraft" field is not set yet.
            var mc = Minecraft.getInstance();
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
            dev.watdafawx.foxstweaks.client.SpawnAnchorScreen.open(
                    new dev.watdafawx.foxstweaks.anchor.OpenAnchorGuiPayload(
                            guiDummyId, true, 1, 4, 2, -59, 2, "minecraft:overworld", "", false, "", false));
        });

        // Tier button: off -> Haven, through the real widget and the real C2S payload.
        add(10, () -> {
            if (!apotheosisPresent)
                return;
            check("gui tier button clicked", clickButton(3) == 1, "tier button not found");
        });

        add(10, () -> {
            if (!apotheosisPresent)
                return;
            String tier = onServer(p -> {
                var anchor = dev.watdafawx.foxstweaks.anchor.SpawnAnchorData.get(p.serverLevel()).get("tier_gui");
                return anchor == null ? "no anchor" : anchor.tier();
            });
            check("gui tier reached the anchor data", "haven".equals(tier), "tier=" + tier);

            onServer(p -> {
                ServerLevel level = p.serverLevel();
                dev.watdafawx.foxstweaks.anchor.SpawnAnchors.remove(level, "tier_gui");
                level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class,
                        new AABB(0, -64, 0, 5, -55, 5)).forEach(net.minecraft.world.entity.Entity::discard);
                // Now move the only player away from the farm for the rest of the run.
                p.teleportTo(level, 600.5, -59.0, 600.5, 0f, 0f);
                return null;
            });
            Minecraft.getInstance().setScreen(null);
        });

        // Tier augments: Pinnacle gives monsters +16 armour under apotheosis:pinnacle/armor.
        add(20, () -> {
            if (!apotheosisPresent)
                return;
            int augmented = onServer(p -> spawnBatch(p.serverLevel(), SPAWNS, true));
            check("anchor's pinnacle tier reached mobs 600 blocks from the only player", augmented > 0,
                    "mobs with " + PINNACLE_ARMOR + " = " + augmented + "/" + SPAWNS);
            log("mobs with the pinnacle augment: " + augmented + "/" + SPAWNS);
        });

        // Invaders: 3% per natural spawn at Pinnacle, so 300 spread-out spawns practically guarantee
        // one. A boss can only be placed where no mob already stands, which is why the batch above
        // fills a floor rather than one block.
        add(10, () -> {
            if (!apotheosisPresent)
                return;
            int bosses = onServer(p -> countBosses(p.serverLevel()));
            check("an invader (boss) spawned at the anchor", bosses > 0, "bosses=" + bosses);
            log("bosses at the anchor: " + bosses);
            diagnostics();
        });

        // Control: the same spawns must do nothing to the tier once the anchor stops carrying one.
        add(10, () -> {
            if (!apotheosisPresent)
                return;
            onServer(p -> {
                p.server.getCommands().performPrefixedCommand(
                        p.createCommandSourceStack().withPermission(4),
                        "foxstweaks anchor tier tierfarm off");
                return null;
            });
            int augmented = onServer(p -> spawnBatch(p.serverLevel(), 100, true));
            check("no tier augments once the anchor carries none", augmented == 0,
                    "mobs with " + PINNACLE_ARMOR + " = " + augmented + "/100");
        });

        // No player in the dimension at all: the early bail-out is the case the anchor exists for, and
        // the surface rule would NPE here without the second hook.
        add(10, () -> {
            if (!apotheosisPresent)
                return;
            int augmented = onServer(p -> {
                ServerLevel overworld = p.serverLevel();
                p.server.getCommands().performPrefixedCommand(
                        p.createCommandSourceStack().withPermission(4),
                        "foxstweaks anchor tier tierfarm pinnacle");

                // Via the command, so the Nether is created on demand rather than guessed at.
                p.server.getCommands().performPrefixedCommand(
                        p.createCommandSourceStack().withPermission(4),
                        "execute in minecraft:the_nether run tp @s 0 100 0");
                return spawnBatch(overworld, 100, true);
            });
            check("anchor tier still applies with no player in the dimension", augmented > 0,
                    "mobs with " + PINNACLE_ARMOR + " = " + augmented + "/100");
        });

        add(10, () -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks-anchortier-check.txt"), REPORT);
            }
            catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Spawns {@code count} zombies the way Dreadful Dirt does and returns how many came out with
     * Apotheosis' pinnacle monster augment.
     */
    private static int spawnBatch(ServerLevel level, int count, boolean atAnchor) {
        int augmented = 0;
        BlockPos at = atAnchor ? ANCHOR_POS : ANCHOR_POS.offset(8, 0, 8);
        for (int i = 0; i < count; i++) {
            Zombie zombie = EntityType.ZOMBIE.create(level);
            if (zombie == null)
                continue;
            // Spread them over a small floor rather than stacking them in one block: a real dreadful
            // dirt farm covers a packed floor, and a pile in a single block blocks Apotheosis' own
            // checkSpawnObstruction for the boss it wants to place there.
            int dx = (i % 16) - 8;
            int dz = ((i / 16) % 16) - 8;
            zombie.moveTo(at.getX() + 0.5 + dx, at.getY() + 1.0, at.getZ() + 0.5 + dz, 0.0F, 0.0F);
            EventHooks.finalizeMobSpawn(zombie, level, level.getCurrentDifficultyAt(at),
                    MobSpawnType.NATURAL, null);
            // Dreadful Dirt adds its own mob even when Apotheosis consumed the spawn event to place a
            // boss, so do the same or the two paths would diverge.
            level.addFreshEntity(zombie);
            if (hasPinnacleArmor(zombie))
                augmented++;
        }
        return augmented;
    }

    private static boolean hasPinnacleArmor(Mob mob) {
        AttributeInstance armor = mob.getAttribute(Attributes.ARMOR);
        if (armor == null)
            return false;
        for (AttributeModifier modifier : armor.getModifiers()) {
            if (PINNACLE_ARMOR.equals(modifier.id()))
                return true;
        }
        return false;
    }

    /**
     * Prints the pieces Apotheosis' invader roll depends on, so a zero-boss result can be explained
     * instead of guessed at.
     */
    private static void diagnostics() {
        onServer(p -> {
            ServerLevel level = p.serverLevel();
            var rules = level.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)
                    .getData(dev.shadowsoffire.apotheosis.Apoth.DataMaps.INVADER_SPAWN_RULES,
                            level.dimensionTypeRegistration().getKey());
            log("invader rules: " + (rules == null ? "MISSING" : rules.spawnChances()));
            log("anchor tier lookups replaced a player: " + dev.watdafawx.foxstweaks.anchor.AnchorTier.replacements());

            var ctx = dev.shadowsoffire.apotheosis.tiers.GenContext.standalone(level.random,
                    dev.shadowsoffire.apotheosis.tiers.WorldTier.PINNACLE, 0.0F, level, ANCHOR_POS);
            var registry = dev.shadowsoffire.apotheosis.mobs.registries.InvaderRegistry.INSTANCE;
            var invader = registry.getRandomItem(ctx);
            if (invader == null) {
                log("invader pool pick at Pinnacle: NULL (pool empty)");
            }
            else {
                // One sample reproduces Apotheosis' own gates, so a future zero-boss result says which
                // one rejected instead of needing another round of guessing.
                Mob boss = invader.createBoss(level, ANCHOR_POS, ctx);
                log("invader sample " + boss.getType().toString().replace("entity.minecraft.", "")
                        + ": conditions=" + invader.basicData().canSpawn(boss, level, MobSpawnType.NATURAL)
                        + " spawnRules=" + boss.checkSpawnRules(level, MobSpawnType.NATURAL)
                        + " obstruction=" + boss.checkSpawnObstruction(level)
                        + " noBlocks=" + level.noCollision(boss)
                        + " noEntities=" + level.isUnobstructed(boss));
                boss.discard();
            }

            int applied = 0;
            for (Mob mob : level.getEntitiesOfClass(Mob.class, FARM_BOX)) {
                if (mob.getData(dev.shadowsoffire.apotheosis.Apoth.Attachments.TIER_AUGMENTS_APPLIED))
                    applied++;
            }
            log("mobs marked tier-augmented: " + applied);
            return null;
        });
    }

    private static int countBosses(ServerLevel level) {        int bosses = 0;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, FARM_BOX)) {
            if (mob.getPersistentData().getBoolean("apoth.boss"))
                bosses++;
        }
        return bosses;
    }

    private static void add(int waitTicks, Runnable run) {
        STEPS.add(new Step(waitTicks, run));
    }

    private static <T> T onServer(Function<ServerPlayer, T> fn) {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        return server.submit(() -> fn.apply(server.getPlayerList().getPlayer(mc.player.getUUID()))).join();
    }

    private static void log(String line) {
        LOG.info("AnchorTierCheck {}", line);
        REPORT.add(line);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok)
            failures++;
        log((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    /** Clicks the {@code index}-th button of the open screen through the screen's own mouse handling. */
    private static int clickButton(int index) {
        var mc = Minecraft.getInstance();
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.Screen screen))
            return 0;

        var buttons = new ArrayList<net.minecraft.client.gui.components.Button>();
        for (var child : screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.Button button)
                buttons.add(button);
        }
        if (index >= buttons.size())
            return 0;

        var button = buttons.get(index);
        return screen.mouseClicked(button.getX() + button.getWidth() / 2.0,
                button.getY() + button.getHeight() / 2.0, 0) ? 1 : 0;
    }

    private static void onTick(ClientTickEvent.Post e) {
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

        tick++;
        if (nextAt == 0)
            nextAt = tick + STEPS.get(0).waitTicks;
        if (tick < nextAt)
            return;

        var step = STEPS.get(index++);
        try {
            step.run.run();
        }
        catch (Throwable t) {
            LOG.error("AnchorTierCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }
        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-anchortier";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(world);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
            catch (IOException ignored) {
            }
        }
        var settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    private AnchorTierCheck() {
    }
}
