package dev.watdafawx.foxstweaks.client.dev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.watdafawx.foxstweaks.dragmove.DragMover;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Dev-only check of Drag Move ({@code dragmove/}). {@code build.bat runDragMoveCheck}, game dir {@code run-dragmove/},
 * no extra mods. Flat survival world, a real barrel and chest opened through the server (real menus, real screens),
 * and the drag is driven by posting the same {@code ScreenEvent}s the game posts for mouse input at the pixel centres
 * of real slots. Every result is read back from the <b>server</b> inventory/container (independent oracle) and the
 * client menu is diffed against it. Writes {@code run-dragmove/foxstweaks-dragmove-check.txt} + screenshots, quits.
 */
public final class DragMoveCheck {
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
    private static BlockPos barrel;
    private static BlockPos chest;
    private static Map<Item, Integer> before;
    private static final int[] expectedCount = new int[27];

    public static void install(String mode) {
        NeoForge.EVENT_BUS.addListener(DragMoveCheck::onTick);

        if (mode.equals("ae2"))
            ae2Scenarios();
        else
            scenarios();
    }

    // ---------------------------------------------------------------------------------------------
    // AE2: a real powered network (creative energy cell + drive holding a 1k cell + ME terminal on a cable). Reached only by
    // reflection, so the default check needs no AE2. Oracle: what the drive's cell holds, read through AE2's own cell API.

    private static BlockPos ae2Ground;
    private static BlockPos ae2Drive;

    private static net.minecraft.resources.ResourceLocation rl(String id) {
        return net.minecraft.resources.ResourceLocation.parse(id);
    }

    private static void ae2Scenarios() {
        add(60, () -> {
            var mc = Minecraft.getInstance();

            ae2Ground = mc.player.blockPosition().below().east(2);
            mc.player.connection.sendCommand("give @s ae2:fluix_glass_cable");
        });
        add(20, () -> {
            var mc = Minecraft.getInstance();

            // A part alone has no cable core and joins nothing; the glass cable is what links it to the cell and the drive.
            mc.player.getInventory().selected = 0;
            mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(ae2Ground).add(0, 0.5, 0), net.minecraft.core.Direction.UP, ae2Ground, false));
        });
        // The server's selected hotbar slot never changes here, so each item is given after the last was used up: always slot 0.
        add(10, () -> Minecraft.getInstance().player.connection.sendCommand("give @s ae2:terminal"));
        add(10, () -> {
            var mc = Minecraft.getInstance();
            BlockPos host = ae2Ground.above();

            mc.player.getInventory().selected = 0;
            mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(host.getX() + 0.5, host.getY() + 0.1, host.getZ() + 0.5), net.minecraft.core.Direction.UP, host, false));
        });
        // The drive is placed the way a player does, so it joins the cable's grid (a server setBlock left it on a grid of its own).
        add(10, () -> Minecraft.getInstance().player.connection.sendCommand("give @s ae2:drive"));
        add(10, () -> {
            var mc = Minecraft.getInstance();

            mc.player.getInventory().selected = 0;
            mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(ae2Ground.south()).add(0, 0.5, 0), net.minecraft.core.Direction.UP, ae2Ground.south(), false));
        });
        add(20, () -> {
            BlockPos host = ae2Ground.above();

            check("AE2: cable bus placed", !Minecraft.getInstance().level.getBlockState(host).isAir(), String.valueOf(Minecraft.getInstance().level.getBlockState(host)));
            log("info: host " + Minecraft.getInstance().level.getBlockState(host) + " be=" + Minecraft.getInstance().level.getBlockEntity(host));

            try {
                Object be = Minecraft.getInstance().level.getBlockEntity(host);

                for (var d : net.minecraft.core.Direction.values())
                    log("info: part on " + d + " = " + be.getClass().getMethod("getPart", net.minecraft.core.Direction.class).invoke(be, d));
                log("info: part at centre = " + be.getClass().getMethod("getPart", net.minecraft.core.Direction.class).invoke(be, (Object) null));
            } catch (Throwable t) {
                log("info: could not list parts: " + t);
            }

            shot("dragmove-ae2-0-host.png");
            onServer(p -> {
                ae2Drive = host.south();
                p.serverLevel().setBlock(host.north(), BuiltInRegistries.BLOCK.get(rl("ae2:creative_energy_cell")).defaultBlockState(), 3);
                check("AE2: drive placed", p.serverLevel().getBlockState(ae2Drive).is(BuiltInRegistries.BLOCK.get(rl("ae2:drive"))), String.valueOf(p.serverLevel().getBlockState(ae2Drive)));

                try {
                    Object drive = p.serverLevel().getBlockEntity(ae2Drive);
                    Object inv = drive.getClass().getMethod("getInternalInventory").invoke(drive);
                    Class<?> internal = Class.forName("appeng.api.inventories.InternalInventory");

                    // insertItem fires the drive's change event, so the grid learns about the cell; setItemDirect does not.
                    Object left = internal.getMethod("insertItem", int.class, ItemStack.class, boolean.class).invoke(inv, 0, new ItemStack(BuiltInRegistries.ITEM.get(rl("ae2:item_storage_cell_1k"))), false);

                    check("AE2: cell accepted by the drive", ((ItemStack) left).isEmpty(), "left over " + left);
                } catch (ReflectiveOperationException ex) {
                    check("AE2: cell inserted into the drive", false, ex.toString());
                }

                p.getInventory().clearContent();
                p.getInventory().setItem(12, new ItemStack(Items.DIAMOND, 3));
                p.getInventory().setItem(13, new ItemStack(Items.IRON_INGOT, 10));
                p.getInventory().setItem(21, new ItemStack(Items.GOLD_INGOT, 64));
                p.getInventory().setItem(22, new ItemStack(Items.COBBLESTONE, 5));
                return null;
            });
        });
        // Blocks set by the server only create their grid node a tick later, after the cable already looked: poke the neighbours again.
        add(20, () -> onServer(p -> {
            BlockPos host = ae2Ground.above();
            var ds = p.serverLevel().getBlockState(ae2Drive);

            log("info: drive state " + ds);

            // The drive's front face (the cell slots) does not join a cable: turn it away from the cable bus.
            for (var prop : ds.getProperties())
                if (prop.getName().equals("facing") && prop.getValueClass() == net.minecraft.core.Direction.class)
                    p.serverLevel().setBlock(ae2Drive, ds.setValue((net.minecraft.world.level.block.state.properties.Property) prop, net.minecraft.core.Direction.SOUTH), 3);

            for (BlockPos q : new BlockPos[] {host.north(), ae2Drive}) {
                p.serverLevel().updateNeighborsAt(q, p.serverLevel().getBlockState(q).getBlock());
                p.serverLevel().sendBlockUpdated(q, p.serverLevel().getBlockState(q), p.serverLevel().getBlockState(q), 3);
            }

            return null;
        }));
        add(100, () -> {
            var mc = Minecraft.getInstance();
            BlockPos host = ae2Ground.above();

            mc.player.getInventory().selected = 5;
            mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(host.getX() + 0.5, host.getY() + 0.9, host.getZ() + 0.5), net.minecraft.core.Direction.UP, host, false));
        });
        add(30, () -> {
            var s = screen();

            check("AE2: ME terminal screen open", s != null && s.getMenu().getClass().getName().startsWith("appeng."), s == null ? "no screen" : s.getMenu().getClass().getName());

            if (s == null)
                return;

            int others = 0;

            for (Slot sl : s.getMenu().slots)
                if (!(sl.container instanceof net.minecraft.world.entity.player.Inventory))
                    others++;

            log("info: terminal menu has " + others + " non-player slots, " + s.getMenu().slots.size() + " total");

            for (Slot sl : s.getMenu().slots)
                if (!(sl.container instanceof net.minecraft.world.entity.player.Inventory))
                    log("info: slot " + sl.index + " " + sl.getClass().getName() + " container=" + sl.container.getClass().getSimpleName() + " active=" + sl.isActive()
                            + " mayPlace(diamond)=" + sl.mayPlace(new ItemStack(Items.DIAMOND, 3)) + " has=" + sl.hasItem() + " xy=" + sl.x + "," + sl.y);
            shot("dragmove-ae2-1-terminal.png");
            DragMover.forceHeld = true;
            DragMover.clearStash();

            // Control, no mod involved: a plain shift-click on the cobblestone. If this does not reach the cell either, the
            // test network is at fault, not Drag Move.
            for (Slot sl : s.getMenu().slots)
                if (DragMover.isPlayerSlot(sl) && sl.getItem().is(Items.COBBLESTONE))
                    Minecraft.getInstance().gameMode.handleInventoryMouseClick(s.getMenu().containerId, sl.index, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, Minecraft.getInstance().player);

            // The two stacks to drag, found by what they hold (AE2 lays its slots out its own way).
            List<Integer> picks = new ArrayList<>();

            for (Slot sl : s.getMenu().slots)
                if (DragMover.isPlayerSlot(sl) && sl.hasItem() && (sl.getItem().is(Items.DIAMOND) || sl.getItem().is(Items.IRON_INGOT)))
                    picks.add(sl.index);

            check("AE2: found the 2 stacks to drag in the terminal's inventory", picks.size() == 2, picks.toString());

            if (picks.size() == 2) {
                press(picks.get(0));
                dragTo(picks.get(1));
                release(picks.get(1));
            }
        });
        add(60, () -> {
            onServer(p -> {
                check("AE2: diamonds left the inventory", p.getInventory().countItem(Items.DIAMOND) == 0, "still " + p.getInventory().countItem(Items.DIAMOND));
                check("AE2: iron left the inventory", p.getInventory().countItem(Items.IRON_INGOT) == 0, "still " + p.getInventory().countItem(Items.IRON_INGOT));
                check("AE2: undragged gold stayed", p.getInventory().countItem(Items.GOLD_INGOT) == 64, "gold " + p.getInventory().countItem(Items.GOLD_INGOT));

                try {
                    Object be = p.serverLevel().getBlockEntity(ae2Ground.above());
                    Object part = be.getClass().getMethod("getPart", net.minecraft.core.Direction.class).invoke(be, net.minecraft.core.Direction.UP);
                    Object node = part.getClass().getMethod("getGridNode").invoke(part);
                    Object grid = node.getClass().getMethod("getGrid").invoke(node);

                    log("info: terminal node online=" + node.getClass().getMethod("isOnline").invoke(node) + " active=" + node.getClass().getMethod("isActive").invoke(node)
                            + " grid size=" + grid.getClass().getMethod("size").invoke(grid));

                    Object drive = p.serverLevel().getBlockEntity(ae2Drive);
                    Object main = drive.getClass().getMethod("getMainNode").invoke(drive);

                    log("info: drive node active=" + main.getClass().getMethod("isActive").invoke(main) + " online=" + main.getClass().getMethod("isOnline").invoke(main));

                    Object dn = main.getClass().getMethod("getNode").invoke(main);

                    log("info: drive gridnode=" + dn);

                    if (dn != null) {
                        Object dg = dn.getClass().getMethod("getGrid").invoke(dn);

                        log("info: drive grid size=" + dg.getClass().getMethod("size").invoke(dg) + " same grid=" + (dg == grid) + " connections=" + dn.getClass().getMethod("getConnections").invoke(dn));
                    }

                    Object energy = grid.getClass().getMethod("getEnergyService").invoke(grid);

                    log("info: grid power stored=" + energy.getClass().getMethod("getStoredPower").invoke(energy) + " idle=" + energy.getClass().getMethod("getIdlePowerUsage").invoke(energy));
                } catch (Throwable t) {
                    log("info: grid diag failed: " + t);
                }

                log("info: control shift-click: inventory cobblestone " + p.getInventory().countItem(Items.COBBLESTONE) + ", cell cobblestone " + cellCount(p, Items.COBBLESTONE));

                long diamonds = cellCount(p, Items.DIAMOND);
                long iron = cellCount(p, Items.IRON_INGOT);

                check("AE2: the drive's cell holds the 3 diamonds", diamonds == 3, "cell has " + diamonds);
                check("AE2: the drive's cell holds the 10 iron", iron == 10, "cell has " + iron);
                return null;
            });
            check("AE2: cursor empty", Minecraft.getInstance().player.containerMenu.getCarried().isEmpty(), "carrying " + Minecraft.getInstance().player.containerMenu.getCarried());
            check("AE2: client menu matches server", diff().isEmpty(), String.join("; ", diff()));
            shot("dragmove-ae2-2-after.png");
        });
        add(10, () -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks-dragmove-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    /** How many of an item the drive's cell holds, through AE2's own cell API (reflection only). */
    private static long cellCount(ServerPlayer p, Item item) {
        try {
            Object drive = p.serverLevel().getBlockEntity(ae2Drive);

            if (drive == null) {
                LOG.error("cell read: no block entity at {}", ae2Drive);

                return -3;
            }

            Object inv = drive.getClass().getMethod("getInternalInventory").invoke(drive);
            ItemStack cell = (ItemStack) Class.forName("appeng.api.inventories.InternalInventory").getMethod("getStackInSlot", int.class).invoke(inv, 0);
            Class<?> save = Class.forName("appeng.api.storage.cells.ISaveProvider");
            Object storage = Class.forName("appeng.api.storage.StorageCells").getMethod("getCellInventory", ItemStack.class, save).invoke(null, cell, null);

            if (storage == null) {
                LOG.error("cell read: no cell inventory for {}", cell);

                return -2;
            }

            Class<?> counterClass = Class.forName("appeng.api.stacks.KeyCounter");
            Object counter = counterClass.getConstructor().newInstance();

            Class.forName("appeng.api.storage.MEStorage").getMethod("getAvailableStacks", counterClass).invoke(storage, counter);

            Object key = Class.forName("appeng.api.stacks.AEItemKey").getMethod("of", net.minecraft.world.level.ItemLike.class).invoke(null, item);

            return (Long) counterClass.getMethod("get", Class.forName("appeng.api.stacks.AEKey")).invoke(counter, key);
        } catch (ReflectiveOperationException ex) {
            LOG.error("cell read", ex);

            return -1;
        }
    }

    private static void scenarios() {
        add(60, () -> onServer(p -> {
            p.setGameMode(GameType.SURVIVAL);
            barrel = p.blockPosition().east(2);
            chest = p.blockPosition().south(2);
            p.serverLevel().setBlock(barrel, Blocks.BARREL.defaultBlockState(), 3);
            p.serverLevel().setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            return null;
        }));

        // ---- A: sparse barrel -> inventory keeps positions (inventory index = 9 + barrel slot), unselected slot untouched
        add(5, () -> {
            DragMover.forceHeld = true;
            DragMover.clearStash();
            onServer(p -> {
                p.getInventory().clearContent();
                Container b = container(p, barrel);
                b.clearContent();
                b.setItem(3, new ItemStack(Items.DIAMOND, 3));
                b.setItem(4, new ItemStack(Items.IRON_INGOT, 10));
                b.setItem(12, new ItemStack(Items.GOLD_INGOT, 64));
                b.setItem(20, new ItemStack(Items.COBBLESTONE, 5)); // not dragged over
                b.setItem(26, new ItemStack(Items.EMERALD, 1));
                before = totals(p, b);
                p.openMenu((MenuProvider) b);
                return null;
            });
        });
        add(15, () -> {
            check("A: barrel opened as a real container screen", screen() != null && screen().getMenu().slots.size() == 63, String.valueOf(screen()));
            press(3);
            dragTo(4);
            dragTo(13); // empty slot on the way: must be ignored
            dragTo(12);
            dragTo(26);
        });
        add(4, () -> shot("dragmove-1-selecting.png"));
        add(1, () -> release(26));
        add(30, () -> {
            onServer(p -> {
                Container b = container(p, barrel);

                check("A: diamond landed at inventory 12 (row 0, col 3)", is(p.getInventory().getItem(12), Items.DIAMOND, 3), p.getInventory().getItem(12).toString());
                check("A: iron landed at inventory 13", is(p.getInventory().getItem(13), Items.IRON_INGOT, 10), p.getInventory().getItem(13).toString());
                check("A: gold landed at inventory 21 (row 1, col 3)", is(p.getInventory().getItem(21), Items.GOLD_INGOT, 64), p.getInventory().getItem(21).toString());
                check("A: emerald landed at inventory 35 (row 2, col 8)", is(p.getInventory().getItem(35), Items.EMERALD, 1), p.getInventory().getItem(35).toString());
                check("A: dragged barrel slots are empty", b.getItem(3).isEmpty() && b.getItem(4).isEmpty() && b.getItem(12).isEmpty() && b.getItem(26).isEmpty(), "still holds items");
                check("A: undragged slot 20 untouched", is(b.getItem(20), Items.COBBLESTONE, 5), b.getItem(20).toString());
                check("A: nothing created or lost", totals(p, b).equals(before), totals(p, b) + " vs " + before);
                return null;
            });
            check("A: 4 stacks stashed", DragMover.STASH.size() == 4, "stash " + DragMover.STASH.size());
            check("A: client menu matches server", diff().isEmpty(), String.join("; ", diff()));
        });

        // ---- C: close, open a chest, paste: same positions, inventory emptied
        add(5, () -> {
            Minecraft.getInstance().player.closeContainer();
            onServer(p -> {
                container(p, chest).clearContent();
                before = totals(p, container(p, chest));
                p.openMenu((MenuProvider) container(p, chest));
                return null;
            });
        });
        add(15, () -> {
            check("C: stash survives closing the barrel", DragMover.STASH.size() == 4, "stash " + DragMover.STASH.size());
            check("C: chest screen open", screen() != null, "no screen");
        });
        add(4, () -> shot("dragmove-2-stash-in-chest.png"));
        add(1, () -> {
            var ev = new ScreenEvent.KeyPressed.Pre(Minecraft.getInstance().screen, GLFW.GLFW_KEY_V, 0, 0);

            NeoForge.EVENT_BUS.post(ev);
            check("C: paste key consumed", ev.isCanceled(), "not cancelled");
        });
        add(30, () -> {
            onServer(p -> {
                Container c = container(p, chest);

                check("C: diamond pasted to chest slot 3 (its barrel position)", is(c.getItem(3), Items.DIAMOND, 3), c.getItem(3).toString());
                check("C: iron pasted to chest slot 4", is(c.getItem(4), Items.IRON_INGOT, 10), c.getItem(4).toString());
                check("C: gold pasted to chest slot 12", is(c.getItem(12), Items.GOLD_INGOT, 64), c.getItem(12).toString());
                check("C: emerald pasted to chest slot 26", is(c.getItem(26), Items.EMERALD, 1), c.getItem(26).toString());
                check("C: inventory emptied", p.getInventory().getItem(12).isEmpty() && p.getInventory().getItem(13).isEmpty() && p.getInventory().getItem(21).isEmpty() && p.getInventory().getItem(35).isEmpty(), "items left in inventory");
                return null;
            });
            check("C: stash cleared", DragMover.STASH.isEmpty(), "stash " + DragMover.STASH.size());
            check("C: client menu matches server", diff().isEmpty(), String.join("; ", diff()));
        });
        add(4, () -> shot("dragmove-3-pasted.png"));

        // ---- J: take out, then put the same stacks straight back by dragging: the stash must not keep green ghosts
        add(5, () -> {
            Minecraft.getInstance().player.closeContainer();
            DragMover.clearStash();
            onServer(p -> {
                p.getInventory().clearContent();
                Container b = container(p, barrel);
                b.clearContent();
                b.setItem(3, new ItemStack(Items.DIAMOND, 3));
                b.setItem(4, new ItemStack(Items.IRON_INGOT, 10));
                p.openMenu((MenuProvider) b);
                return null;
            });
        });
        add(15, () -> {
            press(3);
            dragTo(4);
            release(4);
        });
        add(30, () -> check("J: 2 stacks stashed after taking out", DragMover.STASH.size() == 2, "stash " + DragMover.STASH.size()));
        add(5, () -> {
            press(30); // inventory 12 and 13 in a 27-slot container menu
            dragTo(31);
            release(31);
        });
        add(40, () -> {
            onServer(p -> {
                Container b = container(p, barrel);

                check("J: stacks are back in the barrel", is(b.getItem(3), Items.DIAMOND, 3) && is(b.getItem(4), Items.IRON_INGOT, 10), b.getItem(3) + " " + b.getItem(4));
                return null;
            });
            check("J: stash forgets stacks that left the inventory", DragMover.STASH.isEmpty(), "stash " + DragMover.STASH.size());
        });

        // ---- B: the position is taken -> first free slot
        add(5, () -> {
            Minecraft.getInstance().player.closeContainer();
            DragMover.clearStash();
            onServer(p -> {
                p.getInventory().clearContent();
                p.getInventory().setItem(9, new ItemStack(Items.DIRT, 7));
                Container b = container(p, barrel);
                b.clearContent();
                b.setItem(0, new ItemStack(Items.DIAMOND, 2));
                before = totals(p, b);
                p.openMenu((MenuProvider) b);
                return null;
            });
        });
        add(15, () -> {
            press(0);
            release(0);
        });
        add(20, () -> {
            onServer(p -> {
                check("B: occupied position keeps its dirt", is(p.getInventory().getItem(9), Items.DIRT, 7), p.getInventory().getItem(9).toString());
                check("B: diamond went to the first free slot (inventory 10)", is(p.getInventory().getItem(10), Items.DIAMOND, 2), p.getInventory().getItem(10).toString());
                check("B: barrel slot emptied", container(p, barrel).getItem(0).isEmpty(), "still there");
                check("B: nothing created or lost", totals(p, container(p, barrel)).equals(before), totals(p, container(p, barrel)) + " vs " + before);
                return null;
            });
            check("B: client menu matches server", diff().isEmpty(), String.join("; ", diff()));
        });

        // ---- D: inventory full -> nothing moves, nothing lost
        add(5, () -> {
            Minecraft.getInstance().player.closeContainer();
            DragMover.clearStash();
            onServer(p -> {
                for (int i = 0; i < 36; i++)
                    p.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 1));
                Container b = container(p, barrel);
                b.clearContent();
                b.setItem(5, new ItemStack(Items.DIAMOND, 4));
                before = totals(p, b);
                p.openMenu((MenuProvider) b);
                return null;
            });
        });
        add(15, () -> {
            press(5);
            release(5);
        });
        add(20, () -> {
            onServer(p -> {
                check("D: diamond stays in the barrel when the inventory is full", is(container(p, barrel).getItem(5), Items.DIAMOND, 4), container(p, barrel).getItem(5).toString());
                check("D: nothing created or lost", totals(p, container(p, barrel)).equals(before), totals(p, container(p, barrel)) + " vs " + before);
                return null;
            });
            check("D: no phantom stash entry", DragMover.STASH.isEmpty(), "stash " + DragMover.STASH.size());
            check("D: cursor empty afterwards", Minecraft.getInstance().player.containerMenu.getCarried().isEmpty(), "carrying " + Minecraft.getInstance().player.containerMenu.getCarried());
            check("D: client menu matches server", diff().isEmpty(), String.join("; ", diff()));
        });

        // ---- E: inventory -> container (the other direction), same position
        add(5, () -> {
            Minecraft.getInstance().player.closeContainer();
            DragMover.clearStash();
            onServer(p -> {
                p.getInventory().clearContent();
                p.getInventory().setItem(12, new ItemStack(Items.DIAMOND, 3));
                Container b = container(p, barrel);
                b.clearContent();
                before = totals(p, b);
                p.openMenu((MenuProvider) b);
                return null;
            });
        });
        add(15, () -> {
            press(27 + (12 - 9)); // ChestMenu: inventory rows start at menu slot 27
            release(27 + (12 - 9));
        });
        add(20, () -> {
            onServer(p -> {
                check("E: inventory 12 went to barrel slot 3", is(container(p, barrel).getItem(3), Items.DIAMOND, 3), container(p, barrel).getItem(3).toString());
                check("E: inventory slot emptied", p.getInventory().getItem(12).isEmpty(), p.getInventory().getItem(12).toString());
                check("E: nothing created or lost", totals(p, container(p, barrel)).equals(before), totals(p, container(p, barrel)) + " vs " + before);
                return null;
            });
            check("E: inventory -> container is not stashed", DragMover.STASH.isEmpty(), "stash " + DragMover.STASH.size());
            check("E: client menu matches server", diff().isEmpty(), String.join("; ", diff()));
        });

        // ---- I: whole barrel in one drag (27 stacks, several ticks)
        add(5, () -> {
            Minecraft.getInstance().player.closeContainer();
            DragMover.clearStash();
            onServer(p -> {
                p.getInventory().clearContent();
                Container b = container(p, barrel);
                b.clearContent();
                Item[] items = { Items.COAL, Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.EMERALD, Items.REDSTONE, Items.LAPIS_LAZULI, Items.QUARTZ, Items.COPPER_INGOT,
                        Items.NETHERITE_INGOT, Items.APPLE, Items.BREAD, Items.CARROT, Items.POTATO, Items.STICK, Items.STRING, Items.FEATHER, Items.FLINT, Items.BONE,
                        Items.GUNPOWDER, Items.SLIME_BALL, Items.ENDER_PEARL, Items.BLAZE_ROD, Items.GLOWSTONE_DUST, Items.SUGAR, Items.PAPER, Items.BOOK };

                for (int i = 0; i < 27; i++) {
                    // Ender pearls stack to 16: the container clamps a bigger count, so expect what it really holds.
                    b.setItem(i, new ItemStack(items[i], Math.min(i + 1, items[i].getDefaultMaxStackSize())));
                    expectedCount[i] = b.getItem(i).getCount();
                }

                before = totals(p, b);
                p.openMenu((MenuProvider) b);
                return null;
            });
        });
        add(15, () -> {
            press(0);

            for (int i = 1; i < 27; i++)
                dragTo(i);

            release(26);
        });
        add(60, () -> {
            onServer(p -> {
                boolean ok = true;
                String why = "";

                for (int i = 0; i < 27; i++) {
                    ItemStack got = p.getInventory().getItem(9 + i);

                    if (got.getCount() != expectedCount[i]) {
                        ok = false;
                        why += " inv" + (9 + i) + "=" + got;
                    }
                }

                check("I: all 27 stacks landed in their mirrored inventory slots", ok, why);
                check("I: barrel empty", container(p, barrel).isEmpty(), "barrel not empty");
                check("I: nothing created or lost", totals(p, container(p, barrel)).equals(before), totals(p, container(p, barrel)) + " vs " + before);
                return null;
            });
            check("I: 27 stacks stashed", DragMover.STASH.size() == 27, "stash " + DragMover.STASH.size());
            check("I: client menu matches server", diff().isEmpty(), String.join("; ", diff()));
        });
        add(4, () -> shot("dragmove-4-full-barrel.png"));

        // ---- G: key not held -> the event is left alone, nothing moves
        add(5, () -> {
            Minecraft.getInstance().player.closeContainer();
            DragMover.clearStash();
            DragMover.forceHeld = false;
            onServer(p -> {
                p.getInventory().clearContent();
                Container b = container(p, barrel);
                b.clearContent();
                b.setItem(0, new ItemStack(Items.DIAMOND, 1));
                p.openMenu((MenuProvider) b);
                return null;
            });
        });
        add(15, () -> {
            var ev = new ScreenEvent.MouseButtonPressed.Pre(Minecraft.getInstance().screen, centerX(0), centerY(0), 0);

            NeoForge.EVENT_BUS.post(ev);
            check("G: without the key the click is not taken over", !ev.isCanceled() && !DragMover.dragging(), "cancelled=" + ev.isCanceled());
            onServer(p -> {
                check("G: nothing moved", is(container(p, barrel).getItem(0), Items.DIAMOND, 1), container(p, barrel).getItem(0).toString());
                return null;
            });
            DragMover.forceHeld = true;
        });

        add(10, () -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks-dragmove-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Driving the mouse: the same events the game posts, at real slot centres.

    private static AbstractContainerScreen<?> screen() {
        return Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> s ? s : null;
    }

    private static double centerX(int menuSlot) {
        Slot s = screen().getMenu().slots.get(menuSlot);

        return screen().getGuiLeft() + s.x + 8;
    }

    private static double centerY(int menuSlot) {
        Slot s = screen().getMenu().slots.get(menuSlot);

        return screen().getGuiTop() + s.y + 8;
    }

    private static void press(int menuSlot) {
        var ev = new ScreenEvent.MouseButtonPressed.Pre(screen(), centerX(menuSlot), centerY(menuSlot), 0);

        NeoForge.EVENT_BUS.post(ev);
        check("press on slot " + menuSlot + " is taken over", ev.isCanceled() && DragMover.dragging(), "cancelled=" + ev.isCanceled());
    }

    private static void dragTo(int menuSlot) {
        NeoForge.EVENT_BUS.post(new ScreenEvent.MouseDragged.Pre(screen(), centerX(menuSlot), centerY(menuSlot), 0, 18, 0));
    }

    private static void release(int menuSlot) {
        var ev = new ScreenEvent.MouseButtonReleased.Pre(screen(), centerX(menuSlot), centerY(menuSlot), 0);

        NeoForge.EVENT_BUS.post(ev);
        check("release is taken over", ev.isCanceled(), "not cancelled");
    }

    // ---------------------------------------------------------------------------------------------
    // Oracles

    private static boolean is(ItemStack stack, Item item, int count) {
        return stack.is(item) && stack.getCount() == count;
    }

    private static Container container(ServerPlayer p, BlockPos pos) {
        return (Container) p.serverLevel().getBlockEntity(pos);
    }

    /** Every item and its count over the player's inventory and the container: conservation oracle. */
    private static Map<Item, Integer> totals(ServerPlayer p, Container c) {
        Map<Item, Integer> out = new HashMap<>();

        for (int i = 0; i < p.getInventory().getContainerSize(); i++)
            add(out, p.getInventory().getItem(i));

        for (int i = 0; i < c.getContainerSize(); i++)
            add(out, c.getItem(i));

        return out;
    }

    private static void add(Map<Item, Integer> m, ItemStack s) {
        if (!s.isEmpty())
            m.merge(s.getItem(), s.getCount(), Integer::sum);
    }

    /** Client menu vs the server's, slot by slot: a desync/ghost item is a persistent mismatch. */
    private static List<String> diff() {
        var mc = Minecraft.getInstance();

        return onServer(p -> {
            List<String> out = new ArrayList<>();
            var server = p.containerMenu;
            var client = mc.player.containerMenu;

            for (int i = 0; i < server.slots.size() && i < client.slots.size(); i++)
                if (!ItemStack.matches(server.getSlot(i).getItem(), client.getSlot(i).getItem()))
                    out.add("slot " + i + " server " + server.getSlot(i).getItem() + " client " + client.getSlot(i).getItem());

            return out;
        });
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
        LOG.info("DragMoveCheck {}", line);
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
            LOG.error("DragMoveCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }

        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-dragmove";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(world);

        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
            }
        }

        var settings = new LevelSettings(world, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);

        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    private DragMoveCheck() {
    }
}
