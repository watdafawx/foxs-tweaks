package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.client.HologramClient;
import dev.watdafawx.foxstweaks.client.HologramPickerScreen;
import dev.watdafawx.foxstweaks.client.HologramScreen;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.Holograms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Dev-only end-to-end check of the hologram asset browser ({@link HologramPickerScreen}): opens it from the
 * real editor with the Browse button, then drives search, colour and shape chips, favourites (and their JSON
 * file), the in-hologram / recent collections, the mod filter, variants, family and swap chips, and the three
 * ways out (Replace, + Add layer, Esc). Screenshots at GUI scale 3 and 2.
 *
 * <p>Run with {@code build.bat runHologramPickerCheck} on rig. Report:
 * {@code foxstweaks-hologram-picker-check.txt}, screenshots in {@code screenshots/}.
 */
public final class HologramPickerCheck {
    private static final Logger LOG = LogUtils.getLogger();

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
    private static HologramScreen editor;
    private static int layerCountBefore;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(HologramPickerCheck::onTick);
        scenarios();
    }

    // ---------------------------------------------------------------- scenarios

    private static void scenarios() {
        add(40, () -> {
            // The first-run tutorial toast would sit on top of every screenshot.
            Minecraft.getInstance().getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            onServer(player -> {
                player.serverLevel().setDayTime(6000);
                player.teleportTo(player.serverLevel(), 0.5, -60.0, 5.5, 180.0F, 0.0F);
                return null;
            });
            // A clean favourites file, so the counts below do not depend on an earlier run.
            try {
                Files.deleteIfExists(HologramPickerScreen.devStoreFile());
            } catch (IOException e) {
                log("could not delete the store: " + e);
            }
            HologramPickerScreen.devForgetStore();
        });

        add(20, () -> {
            String[] outcome = { "" };
            onServer(player -> {
                try {
                    int code = player.server.getCommands().getDispatcher()
                            .execute("foxstweaks hologram add minecraft:oak_stairs",
                                    player.createCommandSourceStack().withPermission(4));
                    outcome[0] = "result=" + code;
                } catch (Exception e) {
                    outcome[0] = e.toString();
                }
                return null;
            });
            check("command accepted", outcome[0].startsWith("result="), outcome[0]);
            hologramId = onServer(player -> {
                List<HologramSpec> all = Holograms.list(player.serverLevel());
                return all.isEmpty() ? "" : all.get(0).id();
            });
            check("command created a hologram", !hologramId.isEmpty(), "id=" + hologramId);
        });

        add(10, () -> check("client received the sync", HologramClient.specs().size() == 1,
                "clientCount=" + HologramClient.specs().size()));

        add(6, () -> onServer(player -> {
            player.server.getCommands().performPrefixedCommand(
                    player.createCommandSourceStack().withPermission(4), "foxstweaks hologram edit " + hologramId);
            return null;
        }));

        add(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            try {
                mc.getWindow().setWindowed(1920, 1080);
            } catch (Throwable ignored) {
            }
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
        });

        add(20, () -> {
            Minecraft mc = Minecraft.getInstance();
            check("editor opened", mc.screen instanceof HologramScreen, String.valueOf(mc.screen));
            if (mc.screen instanceof HologramScreen screen) {
                editor = screen;
                screen.devTab(0);
                layerCountBefore = screen.devLayerCount();
                check("hologram starts with one layer", layerCountBefore == 1, "layers=" + layerCountBefore);
            }
        });

        // ---- Browse button ----
        add(6, () -> {
            check("editor has a Browse button", editor.devHasButton("Browse"), "no button");
            check("Browse button opens the picker", editor.devClick("Browse"), "click not handled");
        });

        add(14, () -> {
            HologramPickerScreen p = picker();
            check("picker screen is current", p != null, String.valueOf(Minecraft.getInstance().screen));
            if (p == null)
                return;
            check("Esc / camera: studio camera still active over the picker",
                    dev.watdafawx.foxstweaks.client.HologramCamera.isActive()
                            || HologramClient.previewAnchored(),
                    "camera inactive");
            check("tabs present", p.devHasHit("tab:BLOCK") && p.devHasHit("tab:ENTITY") && p.devHasHit("tab:PARTICLE"),
                    p.devLabels("tab:").toString());
            check("collections present", p.devHasHit("col:FAVOURITES") && p.devHasHit("col:RECENT")
                    && p.devHasHit("col:IN_HOLOGRAM"), p.devLabels("col:").toString());
            check("opens on blocks", p.devKind().equals("BLOCK"), p.devKind());
            check("current layer is preselected", p.devSelectedId().equals("minecraft:oak_stairs"), p.devSelectedId());
            check("block list is big", p.devResultCount() > 800, "results=" + p.devResultCount());
            check("grid has columns", p.devGridCols() >= 4, "cols=" + p.devGridCols());
            check("centre column stays usable", p.devFreeWidth() >= 200, "midW=" + p.devFreeWidth());
            check("hue chips present", p.devLabels("hue:").size() >= 13, p.devLabels("hue:").toString());
            check("shape chips present", p.devLabels("shape:").size() == 4, p.devLabels("shape:").toString());
        });
        add(8, () -> shoot("picker-s3-main.png"));

        // ---- search ----
        add(4, () -> picker().devSetSearch("stairs"));
        add(8, () -> {
            HologramPickerScreen p = picker();
            List<String> ids = p.devResultIds(500);
            boolean all = !ids.isEmpty() && ids.stream().allMatch(id -> id.contains("stairs"));
            check("search 'stairs' narrows the grid", all && p.devResultCount() > 20 && p.devResultCount() < 100,
                    "results=" + p.devResultCount() + " first=" + (ids.isEmpty() ? "-" : ids.get(0)));
            check("search keeps a valid selection", !p.devSelectedId().isEmpty(), p.devSelectedId());
        });
        // ---- ranking: the exact name beats a longer name that merely contains the words ----
        add(4, () -> picker().devSetSearch("oak stairs"));
        add(8, () -> {
            HologramPickerScreen p = picker();
            List<String> ids = p.devResultIds(500);
            List<String> names = p.devResultNames(500);
            int exact = ids.indexOf("minecraft:oak_stairs");
            int dark = ids.indexOf("minecraft:dark_oak_stairs");
            check("'oak stairs' ranks Oak Stairs first", !ids.isEmpty() && ids.get(0).equals("minecraft:oak_stairs"),
                    "first=" + (names.isEmpty() ? "-" : names.get(0)));
            check("'oak stairs' ranks Oak Stairs above Dark Oak Stairs", exact >= 0 && dark >= 0 && exact < dark,
                    "oak=" + exact + " dark=" + dark);
        });
        add(4, () -> picker().devSetSearch("birch"));
        add(8, () -> {
            HologramPickerScreen p = picker();
            List<String> names = p.devResultNames(8);
            // Names that start with the word come before ones that only contain it ("Stripped Birch Log").
            boolean startsFirst = true;
            boolean seenOther = false;
            for (String name : p.devResultNames(500)) {
                boolean starts = name.toLowerCase().startsWith("birch");
                if (starts && seenOther)
                    startsFirst = false;
                if (!starts)
                    seenOther = true;
            }
            check("'birch' lists names starting with it before the rest", startsFirst, names.toString());
        });
        // ---- duplicate display names (standing vs wall signs) ----
        add(4, () -> {
            check("no block name repeats inside one mod", HologramPickerScreen.devDuplicateBlockNames().isEmpty(),
                    HologramPickerScreen.devDuplicateBlockNames().toString());
            check("standing sign keeps its plain name", "Acacia Sign".equals(HologramPickerScreen.devNameOf("minecraft:acacia_sign")),
                    HologramPickerScreen.devNameOf("minecraft:acacia_sign"));
            check("wall sign says so", "Acacia Sign (wall)".equals(HologramPickerScreen.devNameOf("minecraft:acacia_wall_sign")),
                    HologramPickerScreen.devNameOf("minecraft:acacia_wall_sign"));
            check("hanging sign keeps its plain name",
                    "Acacia Hanging Sign".equals(HologramPickerScreen.devNameOf("minecraft:acacia_hanging_sign")),
                    HologramPickerScreen.devNameOf("minecraft:acacia_hanging_sign"));
            check("wall hanging sign says so",
                    "Acacia Hanging Sign (wall)".equals(HologramPickerScreen.devNameOf("minecraft:acacia_wall_hanging_sign")),
                    HologramPickerScreen.devNameOf("minecraft:acacia_wall_hanging_sign"));
            picker().devSetSearch("");
        });
        add(4, () -> picker().devSetSearch("diamnod"));
        add(8, () -> {
            HologramPickerScreen p = picker();
            check("fuzzy search finds diamond blocks", p.devResultIds(500).stream().anyMatch(id -> id.contains("diamond")),
                    "results=" + p.devResultIds(8));
            p.devSetSearch("");
        });

        // ---- favourites, persisted to disk ----
        add(8, () -> {
            HologramPickerScreen p = picker();
            p.devSelect("minecraft:birch_stairs");
            check("select birch_stairs", p.devSelectedId().equals("minecraft:birch_stairs"), p.devSelectedId());
            check("favourite button pressed", p.devClick("btn:fav"), "no btn:fav hit");
            check("favourite is remembered",
                    HologramPickerScreen.devIsFavourite(HologramLayer.Kind.BLOCK, "minecraft:birch_stairs"), "not fav");
            String disk = "";
            try {
                disk = Files.exists(HologramPickerScreen.devStoreFile())
                        ? Files.readString(HologramPickerScreen.devStoreFile()) : "<no file>";
            } catch (IOException e) {
                disk = e.toString();
            }
            check("favourite written to the json file", disk.contains("minecraft:birch_stairs"), disk);
            HologramPickerScreen.devForgetStore();
            check("favourite survives a reload from disk",
                    HologramPickerScreen.devIsFavourite(HologramLayer.Kind.BLOCK, "minecraft:birch_stairs"),
                    "lost after forget");
            check("favourites collection clicked", p.devClick("col:FAVOURITES"), "no hit");
        });
        add(8, () -> {
            HologramPickerScreen p = picker();
            List<String> ids = p.devResultIds(50);
            check("Favourites lists exactly the favourite", ids.equals(List.of("minecraft:birch_stairs")), ids.toString());
            p.devClick("col:IN_HOLOGRAM");
        });
        add(8, () -> {
            HologramPickerScreen p = picker();
            List<String> ids = p.devResultIds(50);
            check("In this hologram lists the layer's block", ids.equals(List.of("minecraft:oak_stairs")), ids.toString());
            p.devClick("col:RECENT");
        });
        add(8, () -> {
            HologramPickerScreen p = picker();
            check("Recent starts empty", p.devResultCount() == 0, "results=" + p.devResultCount());
            p.devClick("col:ALL");
        });

        // ---- mod filter ----
        add(8, () -> {
            HologramPickerScreen p = picker();
            check("all blocks are back", p.devResultCount() > 800, "results=" + p.devResultCount());
            check("mod dropdown opens", p.devClick("mod") && p.devDropdownOpen(), "open=" + p.devDropdownOpen());
        });
        add(8, () -> {
            HologramPickerScreen p = picker();
            check("dropdown lists minecraft", p.devModOptions().contains("minecraft"), p.devModOptions().toString());
            check("dropdown option clickable", p.devHasHit("modopt:minecraft"), p.devLabels("modopt:").toString());
            shoot("picker-s3-mod-dropdown.png");
        });
        add(6, () -> {
            HologramPickerScreen p = picker();
            p.devClick("modopt:minecraft");
            check("dropdown closes after a pick", !p.devDropdownOpen(), "still open");
        });

        // ---- colour chip ----
        add(6, () -> check("red chip pressed", picker().devClick("hue:red"), "no hue:red hit"));
        poll(10, 120, () -> picker().devColourDone(), () -> check("colour pass finished", true, ""));
        add(8, () -> {
            HologramPickerScreen p = picker();
            check("colour pass finished before the timeout", p.devColourDone(), "percent=" + p.devColourPercent());
            List<String> ids = p.devResultIds(2000);
            boolean allRed = !ids.isEmpty() && ids.stream().allMatch(id -> p.devHueOf(id).contains("red"));
            check("red chip keeps only red blocks", allRed && ids.size() < 400, "n=" + ids.size()
                    + " sample=" + ids.subList(0, Math.min(6, ids.size())));
            check("red wool is red", ids.contains("minecraft:red_wool"), "n=" + ids.size());
            check("blue wool is blue", p.devHueOf("minecraft:blue_wool").contains("blue"), p.devHueOf("minecraft:blue_wool").toString());
            check("lime wool is green", p.devHueOf("minecraft:lime_wool").contains("green"), p.devHueOf("minecraft:lime_wool").toString());
            check("snow is white", p.devHueOf("minecraft:snow_block").contains("white"), p.devHueOf("minecraft:snow_block").toString());
            check("coal block is black", p.devHueOf("minecraft:coal_block").contains("black"), p.devHueOf("minecraft:coal_block").toString());
        });
        add(8, () -> shoot("picker-s3-hue-red.png"));
        add(4, () -> picker().devClick("hue:all"));

        // ---- shape chip ----
        add(8, () -> {
            HologramPickerScreen p = picker();
            check("hue reset restores the list", p.devResultCount() > 800, "results=" + p.devResultCount());
            check("slabs/stairs chip pressed", p.devClick("shape:2"), "no hit");
        });
        add(8, () -> {
            HologramPickerScreen p = picker();
            List<String> ids = p.devResultIds(2000);
            boolean ok = !ids.isEmpty() && ids.stream().allMatch(id -> id.endsWith("_slab") || id.endsWith("_stairs"));
            check("slabs/stairs chip keeps only slabs and stairs", ok, "n=" + ids.size()
                    + " sample=" + ids.subList(0, Math.min(6, ids.size())));
            p.devClick("shape:2");
            p.devClick("shape:8");
        });
        add(8, () -> {
            HologramPickerScreen p = picker();
            List<String> ids = p.devResultIds(2000);
            check("Has light chip finds torches and lanterns",
                    ids.contains("minecraft:glowstone") && ids.contains("minecraft:lantern") && ids.size() < 150,
                    "n=" + ids.size());
            p.devClick("shape:8");
        });

        // ---- variants ----
        add(8, () -> {
            HologramPickerScreen p = picker();
            check("filters cleared", p.devResultCount() > 800, "results=" + p.devResultCount());
            p.devSelect("minecraft:oak_stairs");
        });
        add(10, () -> {
            HologramPickerScreen p = picker();
            check("oak_stairs selected", p.devSelectedId().equals("minecraft:oak_stairs"), p.devSelectedId());
            List<String> facing = p.devLabels("prop:facing");
            check("facing control present", !facing.isEmpty(), "labels=" + p.devLabels("prop:"));
            log("variant labels: " + p.devLabels("prop:"));
            String east = facing.stream().filter(l -> l.endsWith("=east")).findFirst().orElse("");
            check("compass has an east button", !east.isEmpty(), facing.toString());
            if (!east.isEmpty())
                p.devClick(east);
            check("facing east lands in the props", "east".equals(p.devProps().get("facing")), p.devProps().toString());
            String top = p.devLabels("prop:half").stream().filter(l -> l.endsWith("=top")).findFirst().orElse("");
            check("half has a top segment", !top.isEmpty(), p.devLabels("prop:half").toString());
            if (!top.isEmpty())
                p.devClick(top);
            check("half top lands in the props", "top".equals(p.devProps().get("half")), p.devProps().toString());
            // Setting a value back to the default drops the override.
            String north = p.devLabels("prop:facing").stream().filter(l -> l.endsWith("=north")).findFirst().orElse("");
            if (!north.isEmpty()) {
                p.devClick(north);
                check("default value is not stored", !p.devProps().containsKey("facing"), p.devProps().toString());
                p.devClick(east);
            }
            check("all-variants grid present", p.devHasHit("var:0") && p.devLabels("var:").size() >= 2,
                    p.devLabels("var:").toString());
            Map<String, String> before = p.devProps();
            List<String> vars = p.devLabels("var:");
            if (vars.size() >= 3) {
                p.devClick(vars.get(2));
                check("clicking a variant thumbnail changes the props", !p.devProps().equals(before),
                        before + " -> " + p.devProps());
            }
            p.devClick(east);
            p.devClick(top);
        });
        add(8, () -> {
            HologramPickerScreen p = picker();
            check("right panel scrolls", p.devRightMax() > 0, "rightMax=" + p.devRightMax());
            p.devScrollRight(p.devRightMax());
        });
        add(8, () -> shoot("picker-s3-variants.png"));
        add(4, () -> picker().devScrollRight(-100000));

        // ---- family and swap (the swap chips sit under the variants, so they only exist once scrolled to) ----
        add(8, () -> {
            HologramPickerScreen p = picker();
            check("family chips are in the first screenful", p.devLabels("fam:").size() >= 3, p.devLabels("fam:").toString());
            p.devScrollRight(p.devRightMax());
        });
        add(8, () -> {
            HologramPickerScreen p = picker();
            List<String> swap = p.devLabels("swap:").stream().filter(l -> !l.equals("swap:more")).toList();
            check("swap chips for oak_stairs", swap.size() >= 3, swap.toString());
            if (swap.isEmpty())
                return;
            p.devClick(swap.get(0));
            String id = p.devSelectedId();
            check("swap picks another stairs block", id.endsWith("_stairs") && !id.equals("minecraft:oak_stairs"), id);
            check("swap carries the matching properties", "east".equals(p.devProps().get("facing"))
                    && "top".equals(p.devProps().get("half")), p.devProps().toString());
            p.devScrollRight(-100000);
        });
        add(8, () -> {
            HologramPickerScreen p = picker();
            String id = p.devSelectedId();
            List<String> fam = p.devLabels("fam:").stream().filter(l -> !l.equals("fam:more")).toList();
            check("family chips follow the new block", fam.size() >= 3, fam.toString());
            if (!fam.isEmpty()) {
                p.devClick(fam.get(0));
                check("family chip picks a block of the same material", !p.devSelectedId().equals(id),
                        id + " -> " + p.devSelectedId());
            }
            // A fresh pick starts from the defaults, so set the two overrides again by hand.
            p.devSelect("minecraft:oak_stairs");
        });

        // ---- Replace ----
        add(8, () -> {
            HologramPickerScreen p = picker();
            p.devScrollRight(-100000);
            for (String suffix : new String[] { "=east", "=top" }) {
                for (String label : p.devLabels("prop:")) {
                    if (label.endsWith(suffix))
                        p.devClick(label);
                }
            }
        });
        add(8, () -> {
            HologramPickerScreen p = picker();
            check("oak_stairs reselected with its props", p.devSelectedId().equals("minecraft:oak_stairs")
                    && "top".equals(p.devProps().get("half")) && "east".equals(p.devProps().get("facing")),
                    p.devSelectedId() + " " + p.devProps());
            check("Replace pressed", p.devClick("btn:replace"), "no btn:replace hit");
        });
        add(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            check("Replace returns to the same editor instance", mc.screen == editor, String.valueOf(mc.screen));
            HologramLayer l0 = editor.current().layers().get(0);
            check("Replace keeps one layer", editor.devLayerCount() == 1, "layers=" + editor.devLayerCount());
            check("Replace wrote the chosen props", l0.id().equals("minecraft:oak_stairs")
                    && "top".equals(l0.props().get("half")) && "east".equals(l0.props().get("facing")),
                    l0.id() + " " + l0.props());
            check("editor preview shows the pick", HologramClient.specs().stream().anyMatch(spec -> spec.id().equals(hologramId)
                    && "top".equals(spec.layers().get(0).props().get("half"))), "preview=" + HologramClient.specs());
        });

        // ---- B hotkey, + Add layer ----
        add(4, () -> editor.devKey(GLFW.GLFW_KEY_B));
        add(12, () -> {
            check("B opens the picker", picker() != null, String.valueOf(Minecraft.getInstance().screen));
            HologramPickerScreen p = picker();
            if (p == null)
                return;
            p.devSelect("minecraft:stone_bricks");
            check("stone_bricks selected", p.devSelectedId().equals("minecraft:stone_bricks"), p.devSelectedId());
            check("+ Add layer pressed", p.devClick("btn:add"), "no btn:add hit");
        });
        add(10, () -> {
            check("+ Add layer returns to the editor", Minecraft.getInstance().screen == editor,
                    String.valueOf(Minecraft.getInstance().screen));
            check("+ Add layer adds a layer", editor.devLayerCount() == 2, "layers=" + editor.devLayerCount());
            HologramSpec spec = editor.current();
            check("new layer is the stone bricks", spec.layers().get(1).id().equals("minecraft:stone_bricks"),
                    spec.layers().get(1).id());
            check("first layer untouched", spec.layers().get(0).id().equals("minecraft:oak_stairs"),
                    spec.layers().get(0).id());
        });

        // ---- Shift+click (the same call the tile's shift-click makes) ----
        add(4, () -> editor.devClick("Browse"));
        add(12, () -> {
            HologramPickerScreen p = picker();
            check("picker reopened", p != null, String.valueOf(Minecraft.getInstance().screen));
            if (p == null)
                return;
            p.devSelect("minecraft:granite");
            p.devApply(true);
        });
        add(10, () -> {
            check("Shift add returns to the editor", Minecraft.getInstance().screen == editor,
                    String.valueOf(Minecraft.getInstance().screen));
            HologramSpec spec = editor.current();
            check("Shift add makes a third layer", spec.layers().size() == 3, "layers=" + spec.layers().size());
            check("third layer is granite", spec.layers().size() == 3 && spec.layers().get(2).id().equals("minecraft:granite"),
                    spec.layers().toString());
        });

        // ---- Esc returns unchanged ----
        add(4, () -> editor.devClick("Browse"));
        add(12, () -> {
            HologramPickerScreen p = picker();
            if (p == null) {
                check("picker reopened for Esc", false, String.valueOf(Minecraft.getInstance().screen));
                return;
            }
            p.devSelect("minecraft:diamond_block");
            p.devClick("tab:ENTITY");
            p.devKey(GLFW.GLFW_KEY_ESCAPE);
        });
        add(10, () -> {
            check("Esc returns to the editor", Minecraft.getInstance().screen == editor,
                    String.valueOf(Minecraft.getInstance().screen));
            HologramSpec spec = editor.current();
            check("Esc changed nothing", spec.layers().size() == 3 && spec.layers().get(0).id().equals("minecraft:oak_stairs")
                    && spec.layers().get(2).id().equals("minecraft:granite"), spec.layers().toString());
        });

        // ---- entity ----
        add(4, () -> editor.devClick("Browse"));
        add(12, () -> {
            HologramPickerScreen p = picker();
            if (p == null) {
                check("picker reopened for entities", false, String.valueOf(Minecraft.getInstance().screen));
                return;
            }
            check("entity tab pressed", p.devClick("tab:ENTITY"), "no tab:ENTITY hit");
        });
        add(10, () -> {
            HologramPickerScreen p = picker();
            check("entity list has entries", p.devKind().equals("ENTITY") && p.devResultCount() > 20,
                    p.devKind() + " results=" + p.devResultCount());
            p.devSelect("minecraft:zombie");
        });
        add(14, () -> {
            HologramPickerScreen p = picker();
            check("zombie selected", p.devSelectedId().equals("minecraft:zombie"), p.devSelectedId());
            shoot("picker-s3-entity.png");
        });
        add(8, () -> check("+ Add layer pressed for the entity", picker().devClick("btn:add"), "no hit"));
        add(10, () -> {
            HologramSpec spec = editor.current();
            HologramLayer last = spec.layers().get(spec.layers().size() - 1);
            check("entity layer added", spec.layers().size() == 4 && last.kind() == HologramLayer.Kind.ENTITY
                    && last.id().equals("minecraft:zombie"), spec.layers().size() + " " + last.kind() + " " + last.id());
        });

        // ---- particle ----
        add(4, () -> editor.devClick("Browse"));
        add(12, () -> {
            HologramPickerScreen p = picker();
            if (p == null) {
                check("picker reopened for particles", false, String.valueOf(Minecraft.getInstance().screen));
                return;
            }
            check("particle tab pressed", p.devClick("tab:PARTICLE"), "no tab:PARTICLE hit");
        });
        add(10, () -> {
            HologramPickerScreen p = picker();
            check("particle list has entries", p.devKind().equals("PARTICLE") && p.devResultCount() > 10,
                    p.devKind() + " results=" + p.devResultCount());
            p.devSelect("minecraft:flame");
        });
        add(14, () -> {
            HologramPickerScreen p = picker();
            check("flame selected", p.devSelectedId().equals("minecraft:flame"), p.devSelectedId());
            shoot("picker-s3-particle.png");
            p.devClick("btn:add");
        });
        add(10, () -> {
            HologramSpec spec = editor.current();
            HologramLayer last = spec.layers().get(spec.layers().size() - 1);
            check("particle layer added", spec.layers().size() == 5 && last.kind() == HologramLayer.Kind.PARTICLE
                    && last.id().equals("minecraft:flame"), spec.layers().size() + " " + last.kind() + " " + last.id());
        });

        // ---- recent ----
        add(4, () -> editor.devClick("Browse"));
        add(12, () -> {
            HologramPickerScreen p = picker();
            if (p == null) {
                check("picker reopened for recent", false, String.valueOf(Minecraft.getInstance().screen));
                return;
            }
            // The picker opens on the selected layer's kind (the flame), so go back to blocks first.
            p.devClick("tab:BLOCK");
            p.devClick("col:RECENT");
        });
        add(8, () -> {
            HologramPickerScreen p = picker();
            List<String> ids = p.devResultIds(50);
            check("Recent lists the blocks applied this session",
                    ids.contains("minecraft:stone_bricks") && ids.contains("minecraft:granite")
                            && ids.contains("minecraft:oak_stairs"), ids.toString());
            check("Recent is newest first", !ids.isEmpty() && ids.get(0).equals("minecraft:granite"), ids.toString());
            shoot("picker-s3-recent.png");
        });
        add(6, () -> picker().devKey(GLFW.GLFW_KEY_ESCAPE));
        add(8, () -> check("Esc closes the recent view", Minecraft.getInstance().screen == editor,
                String.valueOf(Minecraft.getInstance().screen)));

        // ---- GUI scale 2 ----
        add(4, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.guiScale().set(2);
            mc.resizeDisplay();
        });
        add(14, () -> {
            check("editor survives a rescale", Minecraft.getInstance().screen == editor,
                    String.valueOf(Minecraft.getInstance().screen));
            editor.devClick("Browse");
        });
        add(14, () -> {
            HologramPickerScreen p = picker();
            if (p == null) {
                check("picker opened at scale 2", false, String.valueOf(Minecraft.getInstance().screen));
                return;
            }
            p.devClick("tab:BLOCK");
        });
        add(8, () -> {
            HologramPickerScreen p = picker();
            check("scale 2: grid has columns", p.devGridCols() >= 5, "cols=" + p.devGridCols());
            check("scale 2: centre column stays usable", p.devFreeWidth() >= 300, "midW=" + p.devFreeWidth());
            p.devSelect("minecraft:oak_stairs");
        });
        add(14, () -> shoot("picker-s2-main.png"));
        add(4, () -> {
            HologramPickerScreen p = picker();
            if (p != null)
                p.devScrollRight(p.devRightMax());
        });
        add(10, () -> shoot("picker-s2-variants.png"));
        add(4, () -> {
            HologramPickerScreen p = picker();
            if (p != null) {
                p.devScrollRight(-100000);
                p.devClick("tab:ENTITY");
            }
        });
        add(12, () -> {
            HologramPickerScreen p = picker();
            if (p != null)
                p.devSelect("minecraft:creeper");
        });
        add(14, () -> shoot("picker-s2-entity.png"));
        add(6, () -> picker().devKey(GLFW.GLFW_KEY_ESCAPE));

        // ---- done ----
        add(10, () -> {
            check("no exception in the picker", true, "");
            check("editor still current at the end", Minecraft.getInstance().screen == editor,
                    String.valueOf(Minecraft.getInstance().screen));
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            Minecraft mc = Minecraft.getInstance();
            try {
                Files.write(mc.gameDirectory.toPath().resolve("foxstweaks-hologram-picker-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("could not write the report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    // ---------------------------------------------------------------- harness

    private static HologramPickerScreen picker() {
        return Minecraft.getInstance().screen instanceof HologramPickerScreen p ? p : null;
    }

    private static void shoot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {
        });
    }

    private static void add(int waitTicks, Runnable run) {
        STEPS.add(new Step(waitTicks, run));
    }

    /** Re-runs itself every {@code waitTicks} until {@code cond} holds or {@code tries} ran out, then {@code then}. */
    private static void poll(int waitTicks, int tries, BooleanSupplier cond, Runnable then) {
        int[] left = { tries };
        add(waitTicks, () -> {
            if (!cond.getAsBoolean() && --left[0] > 0) {
                index--;
                return;
            }
            then.run();
        });
    }

    private static <T> T onServer(Function<ServerPlayer, T> fn) {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        return server.submit(() -> fn.apply(server.getPlayerList().getPlayer(mc.player.getUUID()))).join();
    }

    private static void log(String line) {
        LOG.info("HologramPickerCheck {}", line);
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
            LOG.error("HologramPickerCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }
        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-hologrampickercheck";
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

    private HologramPickerCheck() {
    }
}
