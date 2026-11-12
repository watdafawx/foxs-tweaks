package dev.watdafawx.foxstweaks.client.dev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.client.ToggleScreen;
import dev.watdafawx.foxstweaks.compat.ApothicAffixToggle;
import dev.watdafawx.foxstweaks.compat.ApothicTestGear;
import dev.watdafawx.foxstweaks.enchant.EnchantToggle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Dev-only: flat world, a sword (with Apotheosis affixes when installed), a pickaxe in the offhand
 * and a helmet, then opens the toggle screen, hovers and clicks real rows and tabs, and writes
 * {@code run-toggle/foxstweaks-toggle-check.txt} plus screenshots. {@code build.bat runToggleShot};
 * put Apotheosis, ApothicAttributes and Placebo in {@code run-toggle/mods} to cover affixes too.
 */
public final class ToggleShot {
    private static final Logger LOG = LogUtils.getLogger();

    private record Step(int waitTicks, Runnable run) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static final ResourceLocation MENDING = Enchantments.MENDING.location();
    private static final ResourceLocation SILK = Enchantments.SILK_TOUCH.location();
    private static int failures;
    private static boolean worldRequested;
    private static int tick;
    private static int nextAt;
    private static int index;
    private static ResourceLocation affixId;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(ToggleShot::onTick);
        scenarios();
    }

    private static void scenarios() {
        boolean apotheosis = ModList.get().isLoaded(FoxsTweaks.APOTHEOSIS);

        add(60, () -> {
            if (apotheosis)
                onServer(p -> {
                    // Without a world tier Apotheosis shows gear as "Unidentified" and hides every affix line.
                    p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4),
                            "apoth set_world_tier " + p.getGameProfile().getName() + " haven");
                    return null;
                });
        });
        add(20, () -> log("setup: " + onServer(p -> give(p, apotheosis))));
        add(20, () -> {
            Minecraft mc = Minecraft.getInstance();

            check("items reached the client", !mc.player.getMainHandItem().isEmpty() && !mc.player.getOffhandItem().isEmpty(), "client hand empty");
            mc.setScreen(new ToggleScreen(EquipmentSlot.MAINHAND));
        });
        add(10, () -> shot("toggle-1-open.png"));
        add(2, () -> hoverRow(MENDING));
        add(4, () -> shot("toggle-2-hover-mending.png"));
        add(2, () -> clickRow(MENDING));
        add(20, () -> {
            check("mending off on the server", onServer(p -> level(p.getMainHandItem(), p, MENDING)) == 0, "still on");
            check("mending remembered at level 1", onServer(p -> EnchantToggle.disabledOf(p.getMainHandItem()).entries().getOrDefault(MENDING, 0)) == 1, "not stored");
        });
        add(6, () -> shot("toggle-3-mending-off.png"));

        if (apotheosis) {
            add(2, () -> clickRow(affixId));
            add(20, () -> check("affix off on the server", onServer(p -> ApothicAffixToggle.list(p.getMainHandItem()).stream()
                    .anyMatch(r -> r.instance().getAffix().id().equals(affixId) && !r.enabled())), "affix still on"));
            add(2, () -> hoverRow(affixId));
            add(6, () -> shot("toggle-4-affix-off.png"));
        }

        add(2, () -> clickTab(EquipmentSlot.OFFHAND));
        add(6, () -> shot("toggle-5-offhand-pickaxe.png"));
        add(2, () -> clickRow(SILK));
        add(20, () -> {
            check("silk touch off on the pickaxe", onServer(p -> level(p.getOffhandItem(), p, SILK)) == 0, "still on");
            hoverRow(SILK);
        });
        add(6, () -> shot("toggle-6-silk-off.png"));
        add(2, () -> clickTab(EquipmentSlot.HEAD));
        add(6, () -> shot("toggle-7-helmet.png"));

        // GUI scales: the same endgame sword at each, on a bigger window so the result matches a real game.
        add(2, () -> {
            try {
                Minecraft.getInstance().getWindow().setWindowed(1920, 1080);
            } catch (Throwable t) {
                log("could not resize window: " + t);
            }
        });

        for (int scale : new int[] { 2, 3, 4 }) {
            add(10, () -> {
                var mc = Minecraft.getInstance();

                mc.options.guiScale().set(scale);
                mc.resizeDisplay();
                mc.setScreen(new ToggleScreen(EquipmentSlot.MAINHAND));
            });
            add(10, () -> shot("toggle-8-scale" + scale + ".png"));
        }

        // Over the inventory: the inventory stays visible behind, and closing returns to it.
        add(10, () -> {
            var mc = Minecraft.getInstance();

            mc.options.guiScale().set(3);
            mc.resizeDisplay();
            mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
        });
        add(6, () -> {
            var mc = Minecraft.getInstance();

            mc.setScreen(new ToggleScreen(EquipmentSlot.MAINHAND, mc.screen));
        });
        add(10, () -> shot("toggle-9-over-inventory.png"));
        add(2, () -> {
            var mc = Minecraft.getInstance();

            mc.screen.onClose();
            check("closing returns to the inventory", mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>, String.valueOf(mc.screen));
        });

        add(10, () -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks-toggle-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    private static ItemStack enchanted(ServerPlayer p, ItemStack stack, List<ResourceKey<Enchantment>> keys) {
        var enchants = p.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);

        for (var key : keys)
            stack.enchant(enchants.getHolderOrThrow(key), key == Enchantments.SHARPNESS || key == Enchantments.EFFICIENCY || key == Enchantments.PROTECTION ? 4 : key == Enchantments.MENDING || key == Enchantments.SILK_TOUCH || key == Enchantments.AQUA_AFFINITY ? 1 : 3);

        return stack;
    }

    private static String give(ServerPlayer p, boolean apotheosis) {
        p.getInventory().clearContent();

        ItemStack sword = enchanted(p, new ItemStack(Items.NETHERITE_SWORD),
                List.of(Enchantments.SHARPNESS, Enchantments.LOOTING, Enchantments.UNBREAKING, Enchantments.MENDING, Enchantments.FIRE_ASPECT,
                        Enchantments.SWEEPING_EDGE, Enchantments.KNOCKBACK, Enchantments.SMITE, Enchantments.BANE_OF_ARTHROPODS));
        String note = "sword";

        if (apotheosis) {
            var result = ApothicTestGear.decorate(p, sword, 3);

            sword = result.stack();
            note += ", " + result.note();
            affixId = ApothicAffixToggle.list(sword).stream().findFirst().map(r -> r.instance().getAffix().id()).orElse(null);
        }

        p.getInventory().setItem(p.getInventory().selected, sword);
        p.setItemSlot(EquipmentSlot.OFFHAND, enchanted(p, new ItemStack(Items.DIAMOND_PICKAXE),
                List.of(Enchantments.EFFICIENCY, Enchantments.UNBREAKING, Enchantments.MENDING, Enchantments.SILK_TOUCH)));
        p.setItemSlot(EquipmentSlot.HEAD, enchanted(p, new ItemStack(Items.NETHERITE_HELMET),
                List.of(Enchantments.PROTECTION, Enchantments.RESPIRATION, Enchantments.AQUA_AFFINITY, Enchantments.THORNS)));

        return note + (affixId == null ? "" : ", first affix " + affixId);
    }

    private static int level(ItemStack stack, ServerPlayer p, ResourceLocation id) {
        var holder = p.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolder(id);

        return holder.map(h -> EnchantmentHelper.getEnchantmentsForCrafting(stack).getLevel(h)).orElse(0);
    }

    private static ToggleScreen screen() {
        return Minecraft.getInstance().screen instanceof ToggleScreen s ? s : null;
    }

    private static void hoverRow(ResourceLocation id) {
        ToggleScreen s = screen();

        if (s != null)
            s.reveal(id);

        int[] c = s == null ? null : s.centerOfRow(id);

        check("screen shows a row for " + id, c != null, s == null ? "screen closed" : "row not listed");

        if (c != null)
            s.forcedMouse = c;
    }

    private static void clickRow(ResourceLocation id) {
        ToggleScreen s = screen();

        if (s != null)
            s.reveal(id);

        int[] c = s == null ? null : s.centerOfRow(id);

        check("row to click for " + id, c != null, "row not listed");

        if (c != null)
            s.mouseClicked(c[0], c[1], 0);
    }

    private static void clickTab(EquipmentSlot slot) {
        ToggleScreen s = screen();
        int[] c = s == null ? null : s.centerOfTab(slot);

        check("tab for " + slot, c != null, "screen closed");

        if (c != null) {
            s.mouseClicked(c[0], c[1], 0);
            s.forcedMouse = new int[] { 0, 0 };
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static void add(int waitTicks, Runnable run) {
        STEPS.add(new Step(waitTicks, run));
    }

    private static <T> T onServer(Function<ServerPlayer, T> fn) {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();

        return server.submit(() -> fn.apply(server.getPlayerList().getPlayer(mc.player.getUUID()))).join();
    }

    private static void log(String line) {
        LOG.info("ToggleCheck {}", line);
        REPORT.add(line);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok)
            failures++;

        log((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    private static void shot(String name) {
        var mc = Minecraft.getInstance();

        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> {
        });
    }

    private static void onTick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;

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
            LOG.error("ToggleCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }

        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-toggleshot";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(world);

        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
            }
        }

        var settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);

        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    private ToggleShot() {
    }
}
