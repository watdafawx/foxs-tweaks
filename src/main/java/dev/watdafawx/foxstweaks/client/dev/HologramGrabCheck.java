package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.client.HologramClient;
import dev.watdafawx.foxstweaks.client.HologramEntities;
import dev.watdafawx.foxstweaks.client.HologramGrab;
import dev.watdafawx.foxstweaks.client.HologramScreen;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.Holograms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Dev-only end-to-end check of Hologram Grab Mode ("Grab from world") and entity-data rendering.
 *
 * <p>Run with {@code build.bat runHologramGrabCheck} on rig.
 * Report: {@code run-hologram-grab/foxstweaks-hologram-grab-check.txt}.
 */
public final class HologramGrabCheck {
    private static final Logger LOG = LogUtils.getLogger();

    private static final BlockPos CHEST_POS = new BlockPos(-3, -60, 1);
    private static final BlockPos STAIRS_POS = new BlockPos(-1, -60, 1);
    private static final Vec3 SHEEP_POS = new Vec3(1.5, -60.0, 1.5);
    private static final Vec3 VILLAGER_POS = new Vec3(3.5, -60.0, 1.5);
    private static final Vec3 ZOMBIE_POS = new Vec3(5.5, -60.0, 1.5);

    private record Step(int waitTicks, Runnable run) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static int failures;
    private static boolean worldRequested;
    private static int tick;
    private static int nextAt;
    private static int index;

    private static String hologramId = "";
    private static HologramScreen editorRef;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(HologramGrabCheck::onTick);
        scenarios();
    }

    private static void scenarios() {
        // ---- Setup world: chest with items, oak stairs facing east, red sheep, librarian villager, baby zombie ----
        add(40, () -> {
            Minecraft mc = Minecraft.getInstance();
            try {
                mc.getWindow().setWindowed(1920, 1080);
            } catch (Throwable ignored) {
            }
            mc.options.guiScale().set(3);
            mc.resizeDisplay();

            onServer(player -> {
                var level = player.serverLevel();
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, player.server);
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, player.server);
                level.setDayTime(6000L);

                // Chest with items
                level.setBlockAndUpdate(CHEST_POS,
                        Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
                if (level.getBlockEntity(CHEST_POS) instanceof ChestBlockEntity chest) {
                    chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
                    chest.setItem(1, new ItemStack(Items.EMERALD, 12));
                    chest.setChanged();
                }

                // Oak stairs facing east
                level.setBlockAndUpdate(STAIRS_POS,
                        Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));

                // Red sheep (NoAI)
                Sheep sheep = EntityType.SHEEP.create(level);
                if (sheep != null) {
                    sheep.setPos(SHEEP_POS.x, SHEEP_POS.y, SHEEP_POS.z);
                    sheep.setColor(DyeColor.RED);
                    sheep.setNoAi(true);
                    sheep.setSilent(true);
                    sheep.setInvulnerable(true);
                    sheep.setYRot(0.0F);
                    sheep.setYHeadRot(0.0F);
                    sheep.yBodyRot = 0.0F;
                    level.addFreshEntity(sheep);
                }

                // Librarian villager (NoAI)
                Villager villager = EntityType.VILLAGER.create(level);
                if (villager != null) {
                    villager.setPos(VILLAGER_POS.x, VILLAGER_POS.y, VILLAGER_POS.z);
                    villager.setVillagerData(villager.getVillagerData()
                            .setProfession(VillagerProfession.LIBRARIAN)
                            .setLevel(2));
                    villager.setNoAi(true);
                    villager.setSilent(true);
                    villager.setInvulnerable(true);
                    villager.setYRot(0.0F);
                    villager.setYHeadRot(0.0F);
                    villager.yBodyRot = 0.0F;
                    level.addFreshEntity(villager);
                }

                // Overhead shade so baby zombie never catches fire in noon sunlight
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++)
                        level.setBlockAndUpdate(new BlockPos(5 + dx, -48, 1 + dz), Blocks.STONE.defaultBlockState());
                }

                // Baby zombie (NoAI)
                Zombie zombie = EntityType.ZOMBIE.create(level);
                if (zombie != null) {
                    zombie.setPos(ZOMBIE_POS.x, ZOMBIE_POS.y, ZOMBIE_POS.z);
                    zombie.setBaby(true);
                    zombie.setNoAi(true);
                    zombie.setSilent(true);
                    zombie.setInvulnerable(true);
                    zombie.setYRot(0.0F);
                    zombie.setYHeadRot(0.0F);
                    zombie.yBodyRot = 0.0F;
                    level.addFreshEntity(zombie);
                }

                // Stand facing south so the hologram is created at z=9 behind our target row.
                player.teleportTo(level, 0.5, -60.0, 6.5, 0.0F, 0.0F);
                return null;
            });
        });

        // ---- Create hologram through real command and open real editor ----
        add(15, () -> {
            hologramId = onServer(player -> {
                try {
                    player.server.getCommands().getDispatcher()
                            .execute("foxstweaks hologram add minecraft:quartz_block",
                                    player.createCommandSourceStack().withPermission(4));
                } catch (Exception e) {
                    LOG.error("add command failed", e);
                }
                List<HologramSpec> all = Holograms.list(player.serverLevel());
                return all.isEmpty() ? "" : all.get(0).id();
            });
            check("command created base hologram", !hologramId.isEmpty(), "id=" + hologramId);
        });

        add(10, () -> onServer(player -> {
            player.server.getCommands().performPrefixedCommand(
                    player.createCommandSourceStack().withPermission(4),
                    "foxstweaks hologram edit " + hologramId);
            return null;
        }));

        add(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            check("editor opened", mc.screen instanceof HologramScreen, String.valueOf(mc.screen));
            if (!(mc.screen instanceof HologramScreen screen))
                return;
            editorRef = screen;
            check("editor exposes Grab button", screen.devHasButton("Grab"), "no Grab button");
            check("clicking Grab enters grab mode", screen.devClick("Grab") && HologramGrab.isActive(),
                    "active=" + HologramGrab.isActive() + " screen=" + mc.screen);
            check("grab mode hides editor screen", mc.screen == null, String.valueOf(mc.screen));
            check("grab mode preserves live preview", HologramClient.previewSpec() != null, "preview null");
        });

        // ---- Aim at chest, screenshot grab HUD, single-click grab ----
        add(6, () -> moveAndAim(new Vec3(-2.5, -60.0, 4.5), Vec3.atCenterOf(CHEST_POS)));

        add(8, () -> {
            check("aiming at chest hits chest block",
                    HologramGrab.currentHit() instanceof BlockHitResult bhr && bhr.getBlockPos().equals(CHEST_POS),
                    String.valueOf(HologramGrab.currentHit()));
            shoot("grab-hud-chest.png");
            check("single-click grab dispatches request", HologramGrab.triggerGrab(false), "not dispatched");
        });

        add(12, () -> {
            Minecraft mc = Minecraft.getInstance();
            check("single-click grab exits grab mode and restores same editor",
                    !HologramGrab.isActive() && mc.screen == editorRef,
                    "active=" + HologramGrab.isActive() + " screen=" + mc.screen);
            if (editorRef == null)
                return;
            List<HologramLayer> layers = editorRef.current().layers();
            check("chest added as layer 2", layers.size() == 2, "count=" + layers.size());
            if (layers.size() >= 2) {
                HologramLayer chestLayer = layers.get(1);
                check("chest layer has right kind and id",
                        chestLayer.kind() == HologramLayer.Kind.BLOCK && chestLayer.id().equals("minecraft:chest"),
                        chestLayer.kind() + " " + chestLayer.id());
                check("chest layer captured BE data with items",
                        !chestLayer.data().isEmpty() && chestLayer.data().contains("Items", Tag.TAG_LIST)
                                && !chestLayer.data().getList("Items", Tag.TAG_COMPOUND).isEmpty(),
                        "keys=" + chestLayer.data().getAllKeys());
            }
            boolean chestIntact = onServer(player -> {
                var level = player.serverLevel();
                return level.getBlockState(CHEST_POS).is(Blocks.CHEST)
                        && level.getBlockEntity(CHEST_POS) instanceof ChestBlockEntity c
                        && c.getItem(0).is(Items.DIAMOND) && c.getItem(0).getCount() == 5;
            });
            check("chest block was NOT broken", chestIntact, "block modified or broken");
        });

        // ---- Esc adds nothing (even after Shift-grabbing) ----
        add(6, () -> {
            if (editorRef == null)
                return;
            check("re-enter grab mode for Esc test", editorRef.devClick("Grab") && HologramGrab.isActive(),
                    "active=" + HologramGrab.isActive());
            moveAndAim(new Vec3(-0.5, -60.0, 4.5), Vec3.atCenterOf(STAIRS_POS));
        });

        add(8, () -> {
            check("Shift-grab stairs before Esc", HologramGrab.triggerGrab(true), "not dispatched");
        });

        add(10, () -> {
            check("Shift-grab queued 1 entry and stayed in grab mode",
                    HologramGrab.isActive() && HologramGrab.grabbedCount() == 1,
                    "active=" + HologramGrab.isActive() + " grabbed=" + HologramGrab.grabbedCount());
            HologramGrab.handleKey(GLFW.GLFW_KEY_ESCAPE);
        });

        add(6, () -> {
            Minecraft mc = Minecraft.getInstance();
            check("Esc returned to same editor with nothing added",
                    !HologramGrab.isActive() && mc.screen == editorRef && editorRef.current().layers().size() == 2,
                    "active=" + HologramGrab.isActive() + " layers=" + (editorRef == null ? -1 : editorRef.current().layers().size()));
        });

        // ---- Shift-grab 2 targets (oak stairs + red sheep) + Enter adds both; screenshot sheep HUD ----
        add(6, () -> {
            if (editorRef == null)
                return;
            editorRef.devClick("Grab");
            moveAndAim(new Vec3(-0.5, -60.0, 4.5), Vec3.atCenterOf(STAIRS_POS));
        });

        add(8, () -> {
            check("Shift-grab oak stairs", HologramGrab.triggerGrab(true), "not dispatched");
        });

        add(10, () -> {
            check("stairs queued in grab list (1)", HologramGrab.isActive() && HologramGrab.grabbedCount() == 1,
                    "count=" + HologramGrab.grabbedCount());
            moveAndAim(new Vec3(1.5, -60.0, 4.5), SHEEP_POS.add(0.0, 0.75, 0.0));
        });

        add(8, () -> {
            check("aiming at red sheep hits sheep entity",
                    HologramGrab.currentHit() instanceof EntityHitResult ehr && ehr.getEntity() instanceof Sheep,
                    String.valueOf(HologramGrab.currentHit()));
            shoot("grab-hud-sheep.png");
            check("Shift-grab red sheep", HologramGrab.triggerGrab(true), "not dispatched");
        });

        add(10, () -> {
            check("red sheep queued in grab list (2)", HologramGrab.isActive() && HologramGrab.grabbedCount() == 2,
                    "count=" + HologramGrab.grabbedCount());
            HologramGrab.handleKey(GLFW.GLFW_KEY_ENTER);
        });

        add(8, () -> {
            Minecraft mc = Minecraft.getInstance();
            check("Enter returned to editor with 2 new layers (total 4)",
                    !HologramGrab.isActive() && mc.screen == editorRef && editorRef.current().layers().size() == 4,
                    "layers=" + (editorRef == null ? -1 : editorRef.current().layers().size()));
            if (editorRef == null || editorRef.current().layers().size() < 4)
                return;

            HologramLayer stairsLayer = editorRef.current().layers().get(2);
            check("stairs layer kept id and facing=east",
                    stairsLayer.kind() == HologramLayer.Kind.BLOCK
                            && stairsLayer.id().equals("minecraft:oak_stairs")
                            && "east".equals(stairsLayer.props().get("facing")),
                    stairsLayer.id() + " props=" + stairsLayer.props());
            boolean stairsIntact = onServer(p -> p.serverLevel().getBlockState(STAIRS_POS).is(Blocks.OAK_STAIRS));
            check("oak stairs block was NOT broken", stairsIntact, "broken");

            HologramLayer sheepLayer = editorRef.current().layers().get(3);
            check("sheep layer is ENTITY with Color=14 and stripped UUID/Pos",
                    sheepLayer.kind() == HologramLayer.Kind.ENTITY
                            && sheepLayer.id().equals("minecraft:sheep")
                            && sheepLayer.data().getByte("Color") == (byte) DyeColor.RED.getId()
                            && !sheepLayer.data().contains("UUID")
                            && !sheepLayer.data().contains("Pos"),
                    "id=" + sheepLayer.id() + " color=" + sheepLayer.data().getByte("Color")
                            + " keys=" + sheepLayer.data().getAllKeys());
        });

        // ---- Keys 1/2/3 toggles + Shift-grab librarian villager and baby zombie + Enter ----
        add(6, () -> {
            if (editorRef == null)
                return;
            editorRef.devClick("Grab");
            HologramGrab.handleKey(GLFW.GLFW_KEY_1);
            HologramGrab.handleKey(GLFW.GLFW_KEY_2);
            HologramGrab.handleKey(GLFW.GLFW_KEY_3);
            boolean toggledOff = !HologramGrab.takeBlock() && !HologramGrab.takeState() && !HologramGrab.takeContents();
            HologramGrab.handleKey(GLFW.GLFW_KEY_1);
            HologramGrab.handleKey(GLFW.GLFW_KEY_2);
            HologramGrab.handleKey(GLFW.GLFW_KEY_3);
            boolean toggledBack = HologramGrab.takeBlock() && HologramGrab.takeState() && HologramGrab.takeContents();
            check("keys 1/2/3 toggle Block/State/Contents", toggledOff && toggledBack,
                    "off=" + toggledOff + " back=" + toggledBack);

            moveAndAim(new Vec3(3.5, -60.0, 4.5), VILLAGER_POS.add(0.0, 1.0, 0.0));
        });

        add(8, () -> {
            check("aiming at librarian villager hits villager",
                    HologramGrab.currentHit() instanceof EntityHitResult ehr && ehr.getEntity() instanceof Villager,
                    String.valueOf(HologramGrab.currentHit()));
            check("Shift-grab librarian villager", HologramGrab.triggerGrab(true), "not dispatched");
        });

        add(10, () -> {
            moveAndAim(new Vec3(5.5, -60.0, 4.5), ZOMBIE_POS.add(0.0, 0.5, 0.0));
        });

        add(8, () -> {
            check("aiming at baby zombie hits zombie",
                    HologramGrab.currentHit() instanceof EntityHitResult ehr && ehr.getEntity() instanceof Zombie,
                    String.valueOf(HologramGrab.currentHit()));
            check("Shift-grab baby zombie", HologramGrab.triggerGrab(true), "not dispatched");
        });

        add(10, () -> {
            check("villager + baby zombie both queued (2)", HologramGrab.grabbedCount() == 2,
                    "count=" + HologramGrab.grabbedCount());
            HologramGrab.handleKey(GLFW.GLFW_KEY_ENTER);
        });

        add(8, () -> {
            if (editorRef == null)
                return;
            List<HologramLayer> layers = editorRef.current().layers();
            check("editor now holds all 6 layers", layers.size() == 6, "count=" + layers.size());
            if (layers.size() < 6)
                return;

            HologramLayer sheepLayer = layers.get(3);
            HologramLayer villagerLayer = layers.get(4);
            HologramLayer zombieLayer = layers.get(5);

            CompoundTag vData = villagerLayer.data().getCompound("VillagerData");
            check("villager layer has profession=librarian and stripped Offers/Gossips",
                    villagerLayer.id().equals("minecraft:villager")
                            && "minecraft:librarian".equals(vData.getString("profession"))
                            && !villagerLayer.data().contains("Offers")
                            && !villagerLayer.data().contains("Gossips"),
                    "vData=" + vData + " keys=" + villagerLayer.data().getAllKeys());

            check("zombie layer has IsBaby=true",
                    zombieLayer.id().equals("minecraft:zombie") && zombieLayer.data().getBoolean("IsBaby"),
                    "isBaby=" + zombieLayer.data().getBoolean("IsBaby") + " keys=" + zombieLayer.data().getAllKeys());

            // Verify HologramEntities loads and caches data properly
            Entity eSheep1 = HologramEntities.get(sheepLayer, 0, 0, 0, 0, 1.0F);
            Entity eSheep2 = HologramEntities.get(sheepLayer, 0, 0, 0, 0, 2.0F);
            Entity ePlainSheep = HologramEntities.get(HologramLayer.entity("minecraft:sheep", 0.0F), 0, 0, 0, 0, 1.0F);
            check("HologramEntities builds red sheep from layer data",
                    eSheep1 instanceof Sheep s && s.getColor() == DyeColor.RED,
                    eSheep1 instanceof Sheep s ? "color=" + s.getColor() : String.valueOf(eSheep1));
            check("HologramEntities reuses cached instance without reallocating",
                    eSheep1 != null && eSheep1 == eSheep2 && eSheep1 != ePlainSheep,
                    "same=" + (eSheep1 == eSheep2) + " distinctFromPlain=" + (eSheep1 != ePlainSheep));

            Entity eVillager = HologramEntities.get(villagerLayer, 0, 0, 0, 0, 1.0F);
            check("HologramEntities builds librarian villager from layer data",
                    eVillager instanceof Villager v
                            && v.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN,
                    eVillager instanceof Villager v ? "prof=" + v.getVillagerData().getProfession() : String.valueOf(eVillager));

            Entity eZombie = HologramEntities.get(zombieLayer, 0, 0, 0, 0, 1.0F);
            check("HologramEntities builds baby zombie from layer data",
                    eZombie instanceof Zombie z && z.isBaby(),
                    eZombie instanceof Zombie z ? "baby=" + z.isBaby() : String.valueOf(eZombie));

            // Save through real Done button
            check("Done button saves and closes editor", editorRef.devClick("Done"), "no Done click");
        });

        // ---- Verify server persisted all grabbed layers ----
        add(12, () -> {
            HologramSpec saved = onServer(player -> Holograms.get(player.serverLevel(), hologramId));
            check("server spec saved with all 6 layers",
                    saved != null && saved.layers().size() == 6,
                    saved == null ? "null" : "layers=" + saved.layers().size());
            if (saved == null || saved.layers().size() < 6)
                return;

            HologramLayer sChest = saved.layers().get(1);
            HologramLayer sStairs = saved.layers().get(2);
            HologramLayer sSheep = saved.layers().get(3);
            HologramLayer sVillager = saved.layers().get(4);
            HologramLayer sZombie = saved.layers().get(5);

            check("server kept chest BE items",
                    sChest.id().equals("minecraft:chest") && sChest.data().contains("Items", Tag.TAG_LIST),
                    sChest.id() + " " + sChest.data().getAllKeys());
            check("server kept stairs facing=east",
                    sStairs.id().equals("minecraft:oak_stairs") && "east".equals(sStairs.props().get("facing")),
                    sStairs.props().toString());
            check("server kept sheep Color=14",
                    sSheep.id().equals("minecraft:sheep") && sSheep.data().getByte("Color") == 14,
                    "color=" + sSheep.data().getByte("Color"));
            check("server kept villager librarian",
                    sVillager.id().equals("minecraft:villager")
                            && "minecraft:librarian".equals(sVillager.data().getCompound("VillagerData").getString("profession")),
                    sVillager.data().getCompound("VillagerData").toString());
            check("server kept baby zombie",
                    sZombie.id().equals("minecraft:zombie") && sZombie.data().getBoolean("IsBaby"),
                    "isBaby=" + sZombie.data().getBoolean("IsBaby"));

            // Arrange the 3 grabbed mobs + chest & stairs side-by-side on the hologram for a clear world screenshot.
            onServer(player -> {
                HologramSpec cur = Holograms.get(player.serverLevel(), hologramId);
                if (cur == null || cur.layers().size() < 6)
                    return null;
                List<HologramLayer> display = new ArrayList<>();
                // Lay out: chest (-3), stairs (-1.5), red sheep (0), librarian (+1.5), baby zombie (+3)
                display.add(cur.layers().get(1).withOffset(-3.0F, 0.0F, 0.0F));
                display.add(cur.layers().get(2).withOffset(-1.5F, 0.0F, 0.0F));
                display.add(cur.layers().get(3).withOffset(0.0F, 0.0F, 0.0F));
                display.add(cur.layers().get(4).withOffset(1.6F, 0.0F, 0.0F));
                display.add(cur.layers().get(5).withOffset(3.0F, 0.0F, 0.0F));
                HologramSpec showcase = cur.at(cur.id(), new BlockPos(0, -60, 12), cur.owner(), cur.ownerName())
                        .withLayers(display);
                Holograms.save(player.serverLevel(), showcase);
                player.teleportTo(player.serverLevel(), 0.5, -60.0, 17.5, 180.0F, 4.0F);
                return null;
            });
        });

        add(15, () -> {
            // Aim slightly down at the hologram row at (0.5, -59.2, 12.5)
            aimOnly(new Vec3(0.5, -59.2, 12.5));
        });

        add(8, () -> {
            shoot("grab-world-mobs.png");
        });

        add(6, () -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath()
                        .resolve("foxstweaks-hologram-grab-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    private static void moveAndAim(Vec3 standPos, Vec3 lookTarget) {
        Vec3 eye = standPos.add(0.0, 1.62, 0.0);
        Vec3 d = lookTarget.subtract(eye);
        double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0));
        float pitch = Mth.clamp((float) (-Math.toDegrees(Math.atan2(d.y, horiz))), -89.0F, 89.0F);
        onServer(player -> {
            player.teleportTo(player.serverLevel(), standPos.x, standPos.y, standPos.z, yaw, pitch);
            return null;
        });
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.setPos(standPos.x, standPos.y, standPos.z);
            mc.player.setYRot(yaw);
            mc.player.yRotO = yaw;
            mc.player.setXRot(pitch);
            mc.player.xRotO = pitch;
        }
    }

    private static void aimOnly(Vec3 lookTarget) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 d = lookTarget.subtract(eye);
        double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0));
        float pitch = Mth.clamp((float) (-Math.toDegrees(Math.atan2(d.y, horiz))), -89.0F, 89.0F);
        mc.player.setYRot(yaw);
        mc.player.yRotO = yaw;
        mc.player.setXRot(pitch);
        mc.player.xRotO = pitch;
    }

    private static void shoot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {
        });
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
        LOG.info("HologramGrabCheck {}", line);
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

        tick++;
        if (nextAt == 0)
            nextAt = tick + STEPS.get(0).waitTicks;
        if (tick < nextAt)
            return;

        var step = STEPS.get(index++);
        try {
            step.run.run();
        } catch (Throwable t) {
            LOG.error("HologramGrabCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }
        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-hologramgrabcheck";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(world);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException ignored) {
            }
        }
        var settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.NORMAL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                        .value().createWorldDimensions(),
                new TitleScreen());
    }

    private HologramGrabCheck() {
    }
}
