package dev.watdafawx.foxstweaks.dragmove;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Client-only logic for "drag over slots to move them". Nothing here invents a packet: every move is two ordinary
 * PICKUP clicks ({@code handleInventoryMouseClick}), so the server validates it like a hand move and the mod works on
 * any server.
 *
 * <p>Two sides of a container screen: the <b>player side</b> (main inventory + hotbar, container slots 0-35) and the
 * <b>other side</b> (everything else that is a real, takeable slot: chest, barrel, AE2 pattern slots...). A selected
 * slot on one side moves to the other side. The target is the slot at the same <i>position</i>, measured from each
 * side's top-left slot in GUI pixels, so rows and columns survive the trip; when that slot is taken (or cannot hold
 * the stack) the first free slot is used instead.
 *
 * <p>Stash: stacks taken from a container land in the player's inventory and are remembered (inventory index + the
 * position they came from). {@link #paste} in another container puts them back at the same positions. The stack
 * itself stays in real inventory slots because a client cannot create items and the cursor cannot carry past a
 * screen close.
 */
public final class DragMover {
    /** A stack waiting in the player's inventory, and the position (pixels from the side's top-left) it came from. */
    public record Entry(int invIndex, ItemStack stack, int dx, int dy) {
    }

    private record Op(int containerId, int slot, boolean toPlayer, int dx, int dy, Entry fromStash) {
    }

    /** Dev checks: pretend the hold key is down (a real key cannot be pressed on an unfocused window). */
    public static boolean forceHeld;

    public static final List<Entry> STASH = new ArrayList<>();

    private static final Set<Integer> SELECTED = new LinkedHashSet<>();
    private static final Deque<Op> QUEUE = new ArrayDeque<>();
    private static boolean dragging;
    private static int selectionContainer = -1;
    private static int moved;
    private static int left;
    private static int quick;

    // ---------------------------------------------------------------------------------------------
    // Which screens and slots count

    public static boolean supported(AbstractContainerScreen<?> screen) {
        // The player's own screen has a crafting grid as its "other side" and creative's menu is fake; neither is a container.
        return !(screen instanceof CreativeModeInventoryScreen) && !(screen.getMenu() instanceof InventoryMenu);
    }

    public static boolean isPlayerSlot(Slot s) {
        return s.container instanceof Inventory && s.getContainerSlot() < 36;
    }

    private static boolean isOtherSlot(Slot s) {
        return !(s.container instanceof Inventory) && !(s instanceof ResultSlot) && s.isActive();
    }

    private static List<Slot> group(AbstractContainerMenu menu, boolean player) {
        List<Slot> out = new ArrayList<>();

        for (Slot s : menu.slots)
            if (player ? isPlayerSlot(s) : isOtherSlot(s))
                out.add(s);

        return out;
    }

    private static int[] origin(List<Slot> group) {
        int x = Integer.MAX_VALUE;
        int y = Integer.MAX_VALUE;

        for (Slot s : group) {
            x = Math.min(x, s.x);
            y = Math.min(y, s.y);
        }

        return new int[] { x, y };
    }

    public static Slot slotAt(AbstractContainerScreen<?> screen, double mx, double my) {
        for (Slot s : screen.getMenu().slots) {
            int sx = screen.getGuiLeft() + s.x;
            int sy = screen.getGuiTop() + s.y;

            if (s.isActive() && mx >= sx && mx < sx + 16 && my >= sy && my < sy + 16)
                return s;
        }

        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Selecting

    public static boolean dragging() {
        return dragging;
    }

    public static Set<Integer> selected() {
        return SELECTED;
    }

    public static void begin(AbstractContainerScreen<?> screen, double mx, double my) {
        SELECTED.clear();
        dragging = true;
        selectionContainer = screen.getMenu().containerId;
        touch(screen, mx, my);
    }

    public static void touch(AbstractContainerScreen<?> screen, double mx, double my) {
        if (screen.getMenu().containerId != selectionContainer)
            return;

        Slot s = slotAt(screen, mx, my);
        Player p = Minecraft.getInstance().player;

        // Only real stacks you could take by hand. Empty slots under the cursor are simply skipped.
        if (s != null && s.hasItem() && p != null && s.mayPickup(p) && (isPlayerSlot(s) || isOtherSlot(s)))
            SELECTED.add(s.index);
    }

    /** Ends the drag: turns the selection into queued moves. Returns how many were queued. */
    public static int commit(AbstractContainerScreen<?> screen) {
        dragging = false;

        AbstractContainerMenu menu = screen.getMenu();
        int count = 0;

        if (menu.containerId == selectionContainer) {
            List<Slot> players = group(menu, true);
            List<Slot> others = group(menu, false);
            int[] po = origin(players);
            int[] oo = origin(others);

            for (int index : SELECTED) {
                Slot s = menu.slots.get(index);
                boolean fromPlayer = isPlayerSlot(s);

                int[] o = fromPlayer ? po : oo;

                QUEUE.add(new Op(menu.containerId, index, !fromPlayer, s.x - o[0], s.y - o[1], null));
                count++;
            }
        }

        SELECTED.clear();
        selectionContainer = -1;

        return count;
    }

    /** Puts every stashed stack into the open container at the position it came from. */
    public static int paste(AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();

        if (STASH.isEmpty() || group(menu, false).isEmpty())
            return 0;

        int count = 0;

        for (Entry e : new ArrayList<>(STASH)) {
            Slot src = inventorySlot(menu, e);

            if (src == null) {
                STASH.remove(e); // moved or used since: nothing left to paste for it

                continue;
            }

            QUEUE.add(new Op(menu.containerId, src.index, false, e.dx, e.dy, e));
            count++;
        }

        return count;
    }

    /** The slot still holding the stashed stack: its own inventory slot, else any inventory slot with an identical stack. */
    private static Slot inventorySlot(AbstractContainerMenu menu, Entry e) {
        Slot fallback = null;

        for (Slot s : menu.slots) {
            if (!isPlayerSlot(s) || !ItemStack.matches(s.getItem(), e.stack))
                continue;

            if (s.getContainerSlot() == e.invIndex)
                return s;

            if (fallback == null)
                fallback = s;
        }

        return fallback;
    }

    /** Forgets stashed stacks that are no longer in the inventory (put back, used, dropped, moved by hand). */
    public static void pruneStash(AbstractContainerMenu menu) {
        if (!QUEUE.isEmpty())
            return;

        STASH.removeIf(e -> inventorySlot(menu, e) == null);
    }

    public static boolean busy() {
        return !QUEUE.isEmpty();
    }

    public static void clearStash() {
        STASH.clear();
        QUEUE.clear();
        SELECTED.clear();
        dragging = false;
    }

    // ---------------------------------------------------------------------------------------------
    // Moving (a few clicks per tick, so a big selection does not look like a click flood to anti-cheat)

    public static void tick(Minecraft mc, int perTick) {
        if (QUEUE.isEmpty() || mc.player == null || mc.gameMode == null)
            return;

        if (!(mc.screen instanceof AbstractContainerScreen<?> screen) || screen.getMenu().containerId != QUEUE.peek().containerId) {
            QUEUE.clear(); // the container closed under us; whatever already moved is in the stash

            return;
        }

        for (int i = 0; i < perTick && !QUEUE.isEmpty(); i++)
            run(mc, screen.getMenu(), QUEUE.poll());

        if (QUEUE.isEmpty() && (moved > 0 || left > 0 || quick > 0)) {
            mc.gui.setOverlayMessage(left > 0 ? Component.translatable("message.foxstweaks.drag_move.partial", moved + quick, left)
                    : quick > 0 ? Component.translatable("message.foxstweaks.drag_move.quick", moved, quick)
                            : Component.translatable("message.foxstweaks.drag_move.done", moved), false);
            moved = 0;
            left = 0;
            quick = 0;
        }
    }

    private static void run(Minecraft mc, AbstractContainerMenu menu, Op op) {
        Player p = mc.player;
        Slot src = menu.slots.get(op.slot);

        if (op.fromStash != null) {
            src = inventorySlot(menu, op.fromStash);

            if (src == null) {
                STASH.remove(op.fromStash);

                return;
            }
        }

        ItemStack stack = src.getItem();

        if (stack.isEmpty() || !src.mayPickup(p))
            return;

        List<Slot> group = group(menu, op.toPlayer);
        Slot target = pickTarget(group, origin(group), op.dx, op.dy, stack);

        if (target == null) {
            // No free slot takes it. Shift-click is what a menu does for "put this away": it merges into partial
            // stacks, and in an AE2 terminal it is the only way into the network (its storage is not slots).
            // Menus that do nothing on shift-click leave the stack where it is; a menu that answers only from the
            // server (AE2) does not show the result yet, so this is counted apart rather than judged.
            int before = stack.getCount();

            mc.gameMode.handleInventoryMouseClick(menu.containerId, src.index, 0, ClickType.QUICK_MOVE, p);

            if (!isRemoteMenu(menu) && src.hasItem() && src.getItem().getCount() >= before)
                left++;
            else
                quick++;

            return;
        }

        int srcIndex = src.index;
        ItemStack before = stack.copy();

        click(mc, menu, srcIndex);
        click(mc, menu, target.index);

        if (!menu.getCarried().isEmpty())
            click(mc, menu, srcIndex); // the target took only part of it: hand the rest back

        if (menu.slots.get(srcIndex).hasItem()) {
            left++;

            return;
        }

        moved++;

        if (op.fromStash != null)
            STASH.remove(op.fromStash);
        else if (op.toPlayer && isPlayerSlot(target))
            STASH.add(new Entry(target.getContainerSlot(), before, op.dx, op.dy));
    }

    /** AE2 menus move stacks only on the server, so the client slot looks unchanged straight after a shift-click. */
    private static boolean isRemoteMenu(AbstractContainerMenu menu) {
        return menu.getClass().getName().startsWith("appeng.");
    }

    private static Slot pickTarget(List<Slot> group, int[] origin, int dx, int dy, ItemStack stack) {
        for (Slot s : group)
            if (s.x - origin[0] == dx && s.y - origin[1] == dy && fits(s, stack))
                return s;

        for (Slot s : group)
            if (fits(s, stack))
                return s;

        return null;
    }

    private static boolean fits(Slot s, ItemStack stack) {
        return !s.hasItem() && s.mayPlace(stack) && s.getMaxStackSize(stack) >= stack.getCount();
    }

    private static void click(Minecraft mc, AbstractContainerMenu menu, int slot) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.PICKUP, mc.player);
    }

    private DragMover() {
    }
}
