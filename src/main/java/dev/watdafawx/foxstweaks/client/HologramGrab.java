package dev.watdafawx.foxstweaks.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.watdafawx.foxstweaks.ServerConfig;
import dev.watdafawx.foxstweaks.hologram.HologramGrabPayload;
import dev.watdafawx.foxstweaks.hologram.HologramGrabReplyPayload;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramNetwork;
import dev.watdafawx.foxstweaks.hologram.HologramStates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.MushroomCow;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.TropicalFish;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * World eyedropper ("Grab from world") for {@link HologramScreen}.
 *
 * <p>Hides the editor while keeping the exact same {@link HologramScreen} instance and its live
 * preview alive, lets the player look around freely, highlights the looked-at block or mob in cyan,
 * requests authoritative state/NBT from the server, and returns the grabbed layer(s) to the editor.
 */
public final class HologramGrab {

    /** Grab mode: single block/mob or box-selection structure. */
    public enum GrabMode {
        SINGLE,
        STRUCTURE
    }

    private record GrabbedEntry(String label, HologramLayer layer) {
    }

    public static final int CYAN = 0xFF3EE6FF;
    private static final float CYAN_R = 0x3E / 255.0F;
    private static final float CYAN_G = 0xE6 / 255.0F;
    private static final float CYAN_B = 1.0F;
    private static final int BG = 0xDC0D1117;
    private static final int SURFACE = 0xFF161C24;
    private static final int BORDER = 0x55FFFFFF;
    private static final int TEXT = 0xFFE8EDF5;
    private static final int MUTED = 0xFF8B97A8;
    private static final int WARN = 0xFFFF6B7A;
    private static final int ON_BG = 0x383EE6FF;

    private static final double REACH = 64.0;
    private static final List<String> PRIORITY_PROPS = List.of(
            "facing", "half", "shape", "type", "axis", "open", "lit", "powered",
            "state", "waterlogged", "age", "level", "part", "hinge", "attached"
    );

    private static boolean active;
    @Nullable
    private static HologramScreen editor;
    /** The screen to come back to: the Studio itself, or the Creator that drives it. */
    @Nullable
    private static net.minecraft.client.gui.screens.Screen returnScreen;
    private static GrabMode mode = GrabMode.SINGLE;
    private static boolean takeBlock = true;
    private static boolean takeState = true;
    private static boolean takeContents = true;

    private static final List<GrabbedEntry> grabbed = new ArrayList<>();
    @Nullable
    private static HitResult currentHit;

    // Structure selection state (GrabMode.STRUCTURE)
    @Nullable
    private static BlockPos corner1;
    @Nullable
    private static BlockPos corner2;
    @Nullable
    private static BlockPos cachedCountC1;
    @Nullable
    private static BlockPos cachedCountC2;
    private static int cachedNonAirCount;
    private static int cachedBeCount;
    private static boolean cachedCountCapped;

    private static int nextRequestId = 1;
    private static int pendingRequestId = -1;
    private static boolean pendingFinishAfterReply;

    private static String statusText = "";
    private static int statusColor = CYAN;
    private static long statusUntilMs;

    private HologramGrab() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(HologramGrab::onClientTick);
        NeoForge.EVENT_BUS.addListener(HologramGrab::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(HologramGrab::onRenderGui);
        NeoForge.EVENT_BUS.addListener(HologramGrab::onInteraction);
        NeoForge.EVENT_BUS.addListener(HologramGrab::onKey);
        NeoForge.EVENT_BUS.addListener(HologramGrab::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(HologramGrab::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(HologramGrab::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(HologramGrab::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(HologramGrab::onEntityInteractSpecific);
    }

    public static void begin(HologramScreen sourceEditor) {
        begin(sourceEditor, sourceEditor);
    }

    /** Starts grab mode for {@code sourceEditor}; Enter/Esc/click reopen {@code returnTo} (the Creator, say). */
    public static void begin(HologramScreen sourceEditor, net.minecraft.client.gui.screens.Screen returnTo) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;
        editor = sourceEditor;
        returnScreen = returnTo;
        active = true;
        mode = GrabMode.SINGLE;
        grabbed.clear();
        currentHit = null;
        clearStructureCorners();
        pendingRequestId = -1;
        pendingFinishAfterReply = false;
        statusText = "";
        statusUntilMs = 0L;
        HologramCamera.end();
        mc.setScreen(null);
        // Prevent the mouse click that pressed the Grab button from leaking into an attack/grab.
        mc.options.keyAttack.setDown(false);
        while (mc.options.keyAttack.consumeClick()) {
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static GrabMode mode() {
        return mode;
    }

    public static boolean takeBlock() {
        return takeBlock;
    }

    public static boolean takeState() {
        return takeState;
    }

    public static boolean takeContents() {
        return takeContents;
    }

    public static int grabbedCount() {
        return grabbed.size();
    }

    @Nullable
    public static HitResult currentHit() {
        return currentHit;
    }

    @Nullable
    public static BlockPos corner1() {
        return corner1;
    }

    @Nullable
    public static BlockPos corner2() {
        return corner2;
    }

    public static String lastStatus() {
        return statusText;
    }

    private static void clearStructureCorners() {
        corner1 = null;
        corner2 = null;
        cachedCountC1 = null;
        cachedCountC2 = null;
        cachedNonAirCount = 0;
        cachedBeCount = 0;
        cachedCountCapped = false;
    }

    /** Returns to the suspended editor with all grabbed layers added (Enter or single-click). */
    public static void finishAndReturn() {
        if (!active)
            return;
        HologramScreen target = editor;
        var back = returnScreen != null ? returnScreen : target;
        List<HologramLayer> result = new ArrayList<>(grabbed.size());
        for (GrabbedEntry e : grabbed)
            result.add(e.layer());
        resetState();

        Minecraft mc = Minecraft.getInstance();
        if (target != null && mc.level != null && mc.player != null) {
            mc.setScreen(back);
            if (!result.isEmpty())
                target.addGrabbedLayers(result);
        }
    }

    /** Returns to the suspended editor without adding anything (Esc). */
    public static void cancelAndReturn() {
        if (!active)
            return;
        var back = returnScreen != null ? returnScreen : editor;
        resetState();

        Minecraft mc = Minecraft.getInstance();
        if (back != null && mc.level != null && mc.player != null)
            mc.setScreen(back);
    }

    /** Clears grab mode on level unload or death without forcing the editor back open. */
    public static void clear() {
        resetState();
    }

    private static void resetState() {
        active = false;
        editor = null;
        returnScreen = null;
        grabbed.clear();
        currentHit = null;
        clearStructureCorners();
        pendingRequestId = -1;
        pendingFinishAfterReply = false;
        statusText = "";
        statusUntilMs = 0L;
    }

    private static void setStatus(String msg, int color) {
        statusText = msg;
        statusColor = color;
        statusUntilMs = System.currentTimeMillis() + 2600L;
    }

    // ---------------------------------------------------------------- grabbing

    /**
     * Triggers a grab of whatever the player is currently aiming at.
     * Used by both real left-clicks and dev checks.
     *
     * @param keepOpen true for Shift+click (add to list and stay in grab mode), false for click (grab & return)
     * @return true if a grab request was dispatched to the server
     */
    public static boolean triggerGrab(boolean keepOpen) {
        if (!active)
            return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return false;

        HitResult hit = pickTarget(mc, 1.0F);
        currentHit = hit;

        if (mode == GrabMode.STRUCTURE) {
            return triggerStructureClick(hit, keepOpen);
        }

        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            setStatus("Aim at a block or mob", MUTED);
            return false;
        }

        if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            if (entity instanceof Player) {
                setStatus("Players cannot be grabbed", WARN);
                return false;
            }
            int reqId = nextRequestId++;
            pendingRequestId = reqId;
            pendingFinishAfterReply = !keepOpen;
            HologramNetwork.sendGrab(HologramGrabPayload.forEntity(
                    reqId, entity.getId(), takeBlock, takeState, takeContents));
            return true;
        }

        if (hit instanceof BlockHitResult blockHit) {
            if (!takeBlock) {
                setStatus("Block grab is off (press 1)", WARN);
                return false;
            }
            int reqId = nextRequestId++;
            pendingRequestId = reqId;
            pendingFinishAfterReply = !keepOpen;
            HologramNetwork.sendGrab(HologramGrabPayload.forBlock(
                    reqId, blockHit.getBlockPos(), takeBlock, takeState, takeContents));
            return true;
        }

        return false;
    }

    private static boolean triggerStructureClick(@Nullable HitResult hit, boolean keepOpen) {
        if (corner1 != null && corner2 != null) {
            return confirmStructureGrab(keepOpen);
        }
        if (!(hit instanceof BlockHitResult blockHit)) {
            setStatus("Aim at a block to set corner", MUTED);
            return false;
        }
        BlockPos pos = blockHit.getBlockPos().immutable();
        if (corner1 == null) {
            corner1 = pos;
            corner2 = null;
            invalidateBoxCount();
            setStatus("Corner 1 set — click second corner", CYAN);
            return true;
        }
        corner2 = pos;
        invalidateBoxCount();
        int w = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int h = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int d = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        int maxDim = ServerConfig.hologramStructureMaxDimension();
        if (w > maxDim || h > maxDim || d > maxDim) {
            setStatus("Box " + w + "x" + h + "x" + d + " exceeds max " + maxDim + " per side", WARN);
        } else {
            setStatus("Box " + w + "x" + h + "x" + d + " — Enter/Click to grab, R-click to reset", CYAN);
        }
        return true;
    }

    private static boolean confirmStructureGrab(boolean keepOpen) {
        if (corner1 == null)
            return false;
        BlockPos c2 = corner2;
        if (c2 == null) {
            if (currentHit instanceof BlockHitResult blockHit)
                c2 = blockHit.getBlockPos().immutable();
            else
                return false;
        }
        int reqId = nextRequestId++;
        pendingRequestId = reqId;
        pendingFinishAfterReply = !keepOpen;
        HologramNetwork.sendGrab(HologramGrabPayload.forStructure(
                reqId, corner1, c2, takeState, takeContents));
        return true;
    }

    private static void invalidateBoxCount() {
        cachedCountC1 = null;
        cachedCountC2 = null;
    }

    private static void updateBoxCount(Minecraft mc, BlockPos c1, BlockPos c2) {
        if (c1.equals(cachedCountC1) && c2.equals(cachedCountC2))
            return;
        cachedCountC1 = c1.immutable();
        cachedCountC2 = c2.immutable();
        cachedNonAirCount = 0;
        cachedBeCount = 0;
        cachedCountCapped = false;
        if (mc.level == null)
            return;

        int minX = Math.min(c1.getX(), c2.getX());
        int minY = Math.min(c1.getY(), c2.getY());
        int minZ = Math.min(c1.getZ(), c2.getZ());
        int maxX = Math.max(c1.getX(), c2.getX());
        int maxY = Math.max(c1.getY(), c2.getY());
        int maxZ = Math.max(c1.getZ(), c2.getZ());
        int w = maxX - minX + 1;
        int h = maxY - minY + 1;
        int d = maxZ - minZ + 1;
        int maxDim = ServerConfig.hologramStructureMaxDimension();
        if (w > maxDim || h > maxDim || d > maxDim || (long) w * h * d > 131_072L) {
            cachedCountCapped = true;
            return;
        }

        int maxBlocks = ServerConfig.hologramStructureMaxBlocks();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int nonAir = 0;
        int bes = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    p.set(x, y, z);
                    BlockState st = mc.level.getBlockState(p);
                    if (st.isAir())
                        continue;
                    nonAir++;
                    if (mc.level.getBlockEntity(p) != null)
                        bes++;
                    if (nonAir > maxBlocks) {
                        cachedNonAirCount = nonAir;
                        cachedBeCount = bes;
                        cachedCountCapped = true;
                        return;
                    }
                }
            }
        }
        cachedNonAirCount = nonAir;
        cachedBeCount = bes;
    }

    public static void acceptReply(HologramGrabReplyPayload reply) {
        if (!active)
            return;
        if (reply.requestId() != pendingRequestId)
            return;
        boolean finish = pendingFinishAfterReply;
        pendingRequestId = -1;
        pendingFinishAfterReply = false;

        if (!reply.ok() || reply.layers().isEmpty()) {
            setStatus(reply.message().isEmpty() ? "Could not grab target" : reply.message(), WARN);
            return;
        }

        if (mode == GrabMode.STRUCTURE)
            clearStructureCorners();

        for (HologramLayer layer : reply.layers()) {
            String label = reply.message().isEmpty() ? layer.shortName() : reply.message();
            grabbed.add(new GrabbedEntry(label, layer));
        }

        if (finish) {
            finishAndReturn();
        } else {
            String label = reply.message().isEmpty() ? "layer" : reply.message();
            setStatus("Grabbed " + label + " (" + grabbed.size() + ")", CYAN);
        }
    }

    // ---------------------------------------------------------------- input handling

    private static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!active)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null)
            return;

        event.setCanceled(true);
        event.setSwingHand(false);

        if (event.isAttack()) {
            mc.options.keyAttack.setDown(false);
            boolean shift = Screen.hasShiftDown() || (mc.player != null && mc.player.isShiftKeyDown());
            triggerGrab(shift);
        } else if (event.isUseItem()) {
            mc.options.keyUse.setDown(false);
            if (mode == GrabMode.STRUCTURE) {
                if (corner2 != null) {
                    corner2 = null;
                    invalidateBoxCount();
                    setStatus("Corner 2 cleared — click second corner", MUTED);
                } else if (corner1 != null) {
                    clearStructureCorners();
                    setStatus("Selection cleared", MUTED);
                }
            }
        }
    }

    private static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (active && event.getLevel().isClientSide())
            event.setCanceled(true);
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (active && event.getLevel().isClientSide())
            event.setCanceled(true);
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (active && event.getLevel().isClientSide())
            event.setCanceled(true);
    }

    private static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (active && event.getLevel().isClientSide())
            event.setCanceled(true);
    }

    private static void onKey(InputEvent.Key event) {
        if (!active || event.getAction() != GLFW.GLFW_PRESS)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null)
            return;
        handleKey(event.getKey());
    }

    /** Handles 1/2/3 take toggles, 4/Tab mode switch, Up/Down height nudge, Enter (commit), and Esc (cancel). */
    public static void handleKey(int keyCode) {
        if (!active)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (keyCode == GLFW.GLFW_KEY_1) {
            takeBlock = !takeBlock;
            drainHotbarKey(mc, 0);
        } else if (keyCode == GLFW.GLFW_KEY_2) {
            takeState = !takeState;
            drainHotbarKey(mc, 1);
        } else if (keyCode == GLFW.GLFW_KEY_3) {
            takeContents = !takeContents;
            drainHotbarKey(mc, 2);
        } else if (keyCode == GLFW.GLFW_KEY_4 || keyCode == GLFW.GLFW_KEY_TAB) {
            mode = (mode == GrabMode.SINGLE) ? GrabMode.STRUCTURE : GrabMode.SINGLE;
            clearStructureCorners();
            if (keyCode == GLFW.GLFW_KEY_4)
                drainHotbarKey(mc, 3);
            setStatus(mode == GrabMode.STRUCTURE
                    ? "Structure mode — click two corners"
                    : "Single mode — click a block or mob", CYAN);
        } else if (mode == GrabMode.STRUCTURE && corner1 != null && corner2 != null
                && (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN)) {
            int dy = keyCode == GLFW.GLFW_KEY_UP ? 1 : -1;
            if (corner2.getY() >= corner1.getY())
                corner2 = corner2.above(dy);
            else
                corner1 = corner1.above(dy);
            invalidateBoxCount();
        } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (mode == GrabMode.STRUCTURE && corner1 != null && (corner2 != null || grabbed.isEmpty())) {
                confirmStructureGrab(false);
            } else {
                finishAndReturn();
            }
        } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            cancelAndReturn();
        }
    }

    private static void drainHotbarKey(Minecraft mc, int slot) {
        if (slot >= 0 && slot < mc.options.keyHotbarSlots.length) {
            while (mc.options.keyHotbarSlots[slot].consumeClick()) {
            }
        }
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (!active)
            return;
        if (event.getNewScreen() instanceof PauseScreen) {
            var back = returnScreen != null ? returnScreen : editor;
            resetState();
            if (back != null)
                event.setNewScreen(back);
        } else if (event.getNewScreen() instanceof DeathScreen) {
            resetState();
        }
    }

    // ---------------------------------------------------------------- tick & raycast

    private static void onClientTick(ClientTickEvent.Post event) {
        if (!active)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            resetState();
            return;
        }
        if (mc.gameMode != null && mc.gameMode.isDestroying())
            mc.gameMode.stopDestroyBlock();
        if (mc.screen == null)
            currentHit = pickTarget(mc, 1.0F);
    }

    @Nullable
    public static HitResult pickTarget(Minecraft mc, float partialTick) {
        if (mc.level == null || mc.player == null)
            return null;
        Vec3 eye = mc.player.getEyePosition(partialTick);
        Vec3 look = mc.player.getViewVector(partialTick);
        Vec3 end = eye.add(look.scale(REACH));

        BlockHitResult blockHit = mc.level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (mode == GrabMode.STRUCTURE) {
            return blockHit.getType() == HitResult.Type.BLOCK ? blockHit : null;
        }
        double maxEntityDistSq = blockHit.getType() == HitResult.Type.BLOCK
                ? eye.distanceToSqr(blockHit.getLocation())
                : REACH * REACH;

        AABB searchBox = mc.player.getBoundingBox().expandTowards(look.scale(REACH)).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                mc.player, eye, end, searchBox,
                e -> !e.isSpectator() && e.isPickable() && e != mc.player,
                maxEntityDistSq);
        if (entityHit != null)
            return entityHit;
        if (blockHit.getType() == HitResult.Type.BLOCK)
            return blockHit;
        return null;
    }

    // ---------------------------------------------------------------- world cyan highlight

    private static void onRenderLevel(RenderLevelStageEvent event) {
        if (!active || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null)
            return;

        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        HitResult hit = pickTarget(mc, partial);
        currentHit = hit;

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffer.getBuffer(RenderType.lines());

        if (mode == GrabMode.STRUCTURE) {
            renderStructureOutline(mc, pose, buffer, lines, cam, hit);
            return;
        }

        if (hit == null || hit.getType() == HitResult.Type.MISS)
            return;

        if (hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            BlockState state = mc.level.getBlockState(pos);
            if (state.isAir())
                return;
            VoxelShape shape = state.getShape(mc.level, pos, CollisionContext.of(mc.player));
            if (shape.isEmpty())
                shape = Shapes.block();
            float r = takeBlock ? CYAN_R : 0.65F;
            float g = takeBlock ? CYAN_G : 0.65F;
            float b = takeBlock ? CYAN_B : 0.65F;
            drawVoxelShape(pose, lines, shape, pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z,
                    r, g, b, 0.95F);
            buffer.endBatch(RenderType.lines());
        } else if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            double dx = Mth.lerp(partial, entity.xOld, entity.getX()) - entity.getX();
            double dy = Mth.lerp(partial, entity.yOld, entity.getY()) - entity.getY();
            double dz = Mth.lerp(partial, entity.zOld, entity.getZ()) - entity.getZ();
            AABB box = entity.getBoundingBox().move(dx - cam.x, dy - cam.y, dz - cam.z).inflate(0.02);
            boolean blocked = entity instanceof Player;
            float r = blocked ? 1.0F : CYAN_R;
            float g = blocked ? 0.37F : CYAN_G;
            float b = blocked ? 0.42F : CYAN_B;
            LevelRenderer.renderLineBox(pose, lines, box, r, g, b, 0.95F);
            buffer.endBatch(RenderType.lines());
        }
    }

    private static void renderStructureOutline(Minecraft mc, PoseStack pose, MultiBufferSource.BufferSource buffer,
                                               VertexConsumer lines, Vec3 cam, @Nullable HitResult hit) {
        BlockPos hoverPos = hit instanceof BlockHitResult bhr ? bhr.getBlockPos() : null;
        if (corner1 == null) {
            if (hoverPos != null) {
                AABB single = new AABB(hoverPos).move(-cam.x, -cam.y, -cam.z).inflate(0.004);
                LevelRenderer.renderLineBox(pose, lines, single, CYAN_R, CYAN_G, CYAN_B, 0.95F);
                buffer.endBatch(RenderType.lines());
            }
            return;
        }

        BlockPos c2 = corner2 != null ? corner2 : (hoverPos != null ? hoverPos : corner1);
        updateBoxCount(mc, corner1, c2);

        int minX = Math.min(corner1.getX(), c2.getX());
        int minY = Math.min(corner1.getY(), c2.getY());
        int minZ = Math.min(corner1.getZ(), c2.getZ());
        int maxX = Math.max(corner1.getX(), c2.getX());
        int maxY = Math.max(corner1.getY(), c2.getY());
        int maxZ = Math.max(corner1.getZ(), c2.getZ());
        int maxDim = ServerConfig.hologramStructureMaxDimension();
        boolean over = (maxX - minX + 1) > maxDim || (maxY - minY + 1) > maxDim || (maxZ - minZ + 1) > maxDim
                || cachedCountCapped;

        float r = over ? 1.0F : CYAN_R;
        float g = over ? 0.37F : CYAN_G;
        float b = over ? 0.42F : CYAN_B;

        AABB box = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0)
                .move(-cam.x, -cam.y, -cam.z).inflate(0.008);
        LevelRenderer.renderLineBox(pose, lines, box, r, g, b, 0.95F);

        // Highlight anchored corner 1 slightly brighter so the player can orient the drag.
        AABB c1Box = new AABB(corner1).move(-cam.x, -cam.y, -cam.z).inflate(0.014);
        LevelRenderer.renderLineBox(pose, lines, c1Box, 1.0F, 1.0F, 1.0F, 0.75F);
        buffer.endBatch(RenderType.lines());
    }

    private static void drawVoxelShape(PoseStack pose, VertexConsumer consumer, VoxelShape shape,
                                       double ox, double oy, double oz,
                                       float r, float g, float b, float a) {
        PoseStack.Pose last = pose.last();
        shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
            float dx = (float) (x2 - x1);
            float dy = (float) (y2 - y1);
            float dz = (float) (z2 - z1);
            float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0E-5F)
                return;
            dx /= len;
            dy /= len;
            dz /= len;
            consumer.addVertex(last, (float) (x1 + ox), (float) (y1 + oy), (float) (z1 + oz))
                    .setColor(r, g, b, a)
                    .setNormal(last, dx, dy, dz);
            consumer.addVertex(last, (float) (x2 + ox), (float) (y2 + oy), (float) (z2 + oz))
                    .setColor(r, g, b, a)
                    .setNormal(last, dx, dy, dz);
        });
    }

    // ---------------------------------------------------------------- HUD overlay

    private static void onRenderGui(RenderGuiEvent.Post event) {
        if (!active)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null)
            return;

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int w = g.guiWidth();
        int h = g.guiHeight();

        renderTakeBar(g, font, w);
        renderStatusToast(g, font, w);
        renderGrabbedList(g, font);
        renderCrosshairInfo(g, font, mc, w, h);
        renderBottomHint(g, font, mc, w, h);
    }

    private static void renderStatusToast(GuiGraphics g, Font font, int screenW) {
        if (statusText.isEmpty() || System.currentTimeMillis() >= statusUntilMs)
            return;
        int sw = font.width(statusText) + 14;
        int sx = (screenW - sw) / 2;
        int sy = 30;
        g.fill(sx, sy, sx + sw, sy + 14, BG);
        g.renderOutline(sx, sy, sw, 14, statusColor);
        g.drawString(font, statusText, sx + 7, sy + 3, statusColor, false);
    }

    private static void renderTakeBar(GuiGraphics g, Font font, int screenW) {
        boolean struct = mode == GrabMode.STRUCTURE;
        String badge = struct ? "STRUCTURE" : "GRAB";
        String p1 = "[1] Block " + (struct || takeBlock ? "\u2713" : "\u2717");
        String p2 = "[2] State " + (takeState ? "\u2713" : "\u2717");
        String p3 = "[3] Contents " + (takeContents ? "\u2713" : "\u2717");
        String p4 = "[4] " + (struct ? "Structure \u2713" : "Single");

        int badgeW = font.width(badge) + 10;
        int w1 = font.width(p1) + 10;
        int w2 = font.width(p2) + 10;
        int w3 = font.width(p3) + 10;
        int w4 = font.width(p4) + 10;
        int totalW = badgeW + 6 + w1 + 4 + w2 + 4 + w3 + 4 + w4 + 12;

        int x = (screenW - totalW) / 2;
        int y = 8;
        int barH = 18;
        g.fill(x, y, x + totalW, y + barH, BG);
        g.renderOutline(x, y, totalW, barH, 0x883EE6FF);

        int cx = x + 6;
        g.fill(cx, y + 3, cx + badgeW, y + barH - 3, ON_BG);
        g.drawString(font, badge, cx + 5, y + 5, CYAN, false);
        cx += badgeW + 6;

        cx = drawTogglePill(g, font, cx, y + 2, w1, barH - 4, p1, struct || takeBlock) + 4;
        cx = drawTogglePill(g, font, cx, y + 2, w2, barH - 4, p2, takeState) + 4;
        cx = drawTogglePill(g, font, cx, y + 2, w3, barH - 4, p3, takeContents) + 4;
        drawTogglePill(g, font, cx, y + 2, w4, barH - 4, p4, struct);
    }

    private static int drawTogglePill(GuiGraphics g, Font font, int x, int y, int w, int h,
                                      String label, boolean on) {
        g.fill(x, y, x + w, y + h, on ? ON_BG : SURFACE);
        g.renderOutline(x, y, w, h, on ? CYAN : 0x33FFFFFF);
        g.drawString(font, label, x + 5, y + (h - 8) / 2, on ? TEXT : MUTED, false);
        return x + w;
    }

    private static void renderGrabbedList(GuiGraphics g, Font font) {
        int x = 8;
        int y = 30;
        int maxShown = 8;
        int shown = Math.min(grabbed.size(), maxShown);
        boolean overflow = grabbed.size() > maxShown;

        String header = "Grabbed (" + grabbed.size() + ")";
        int panelW = Math.max(126, font.width(header) + 14);
        List<String> lines = new ArrayList<>();
        if (grabbed.isEmpty()) {
            lines.add("Shift-click to add more");
        } else {
            for (int i = 0; i < shown; i++) {
                GrabbedEntry e = grabbed.get(i);
                String tag = switch (e.layer().kind()) {
                    case ENTITY -> "[E] ";
                    case STRUCTURE -> "[S] ";
                    case IMAGE -> "[I] ";
                    case MODEL -> "[M] ";
                    case PARTICLE -> "[P] ";
                    case BLOCK -> "[B] ";
                };
                lines.add((i + 1) + ". " + tag + e.label());
            }
            if (overflow)
                lines.add("+" + (grabbed.size() - maxShown) + " more...");
        }
        for (String line : lines)
            panelW = Math.min(200, Math.max(panelW, font.width(line) + 14));

        int panelH = 18 + lines.size() * 11 + 4;
        g.fill(x, y, x + panelW, y + panelH, BG);
        g.renderOutline(x, y, panelW, panelH, BORDER);
        g.drawString(font, header, x + 6, y + 5, CYAN, false);

        int ly = y + 17;
        for (int i = 0; i < lines.size(); i++) {
            String clipped = font.plainSubstrByWidth(lines.get(i), panelW - 12);
            int color = grabbed.isEmpty() || (overflow && i == lines.size() - 1) ? MUTED : TEXT;
            g.drawString(font, clipped, x + 6, ly, color, false);
            ly += 11;
        }
    }

    private static void renderCrosshairInfo(GuiGraphics g, Font font, Minecraft mc, int screenW, int screenH) {
        if (mode == GrabMode.STRUCTURE && corner1 != null) {
            renderStructureInfo(g, font, mc, screenW, screenH);
            return;
        }
        HitResult hit = currentHit;
        if (hit == null || hit.getType() == HitResult.Type.MISS)
            return;

        String title;
        String badge;
        int badgeColor = CYAN;
        ItemStack icon = ItemStack.EMPTY;
        List<InfoLine> details = new ArrayList<>();

        if (hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            BlockState state = mc.level.getBlockState(pos);
            if (state.isAir())
                return;
            title = state.getBlock().getName().getString();
            badge = "Block";
            if (state.getBlock().asItem() != Items.AIR)
                icon = new ItemStack(state.getBlock().asItem());

            if (!takeBlock) {
                details.add(new InfoLine("Block grab off [1]", WARN));
            } else {
                List<String> propPairs = collectNotableProps(state);
                if (!propPairs.isEmpty()) {
                    if (takeState) {
                        addWrappedLines(details, propPairs, " \u00B7 ", font, 204, TEXT);
                    } else {
                        details.add(new InfoLine("Default state [2]", MUTED));
                    }
                }

                BlockEntity be = mc.level.getBlockEntity(pos);
                if (be != null) {
                    if (!takeContents) {
                        details.add(new InfoLine("Contents skipped [3]", MUTED));
                    } else if (be instanceof Container container) {
                        int count = countItems(container);
                        if (count > 0)
                            details.add(new InfoLine("Contents: " + count + (count == 1 ? " item" : " items"), CYAN));
                        else
                            details.add(new InfoLine("Container (data captured on grab)", CYAN));
                    } else {
                        details.add(new InfoLine("Block entity data", CYAN));
                    }
                }
            }
        } else if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            title = entity.getType().getDescription().getString();
            if (entity instanceof Player) {
                badge = "Player";
                badgeColor = WARN;
                details.add(new InfoLine("Players cannot be grabbed", WARN));
            } else {
                badge = "Mob";
                if (entity.hasCustomName()) {
                    Component custom = entity.getCustomName();
                    if (custom != null)
                        details.add(new InfoLine("Name: " + custom.getString(), CYAN));
                }
                List<String> traits = describeEntityTraits(entity);
                if (!traits.isEmpty()) {
                    details.add(new InfoLine(String.join(" \u00B7 ", traits), takeState ? TEXT : MUTED));
                }
                if (entity instanceof LivingEntity living) {
                    int gear = countGear(living);
                    if (gear > 0) {
                        details.add(new InfoLine(
                                takeContents ? ("Gear: " + gear + (gear == 1 ? " item" : " items")) : "Gear skipped [3]",
                                takeContents ? CYAN : MUTED));
                    }
                }
            }
        } else {
            return;
        }

        int iconPad = icon.isEmpty() ? 0 : 20;
        int headerW = iconPad + font.width(title) + 8 + font.width(badge) + 8;
        int bodyW = 0;
        for (InfoLine line : details)
            bodyW = Math.max(bodyW, font.width(line.text()));
        int boxW = Mth.clamp(Math.max(headerW, bodyW) + 12, 108, 220);
        int headerH = icon.isEmpty() ? 14 : 18;
        int boxH = 6 + headerH + details.size() * 11 + 4;

        int x = Mth.clamp(screenW / 2 + 14, 8, Math.max(8, screenW - boxW - 8));
        int y = Mth.clamp(screenH / 2 - boxH / 2, 32, Math.max(32, screenH - boxH - 48));

        g.fill(x, y, x + boxW, y + boxH, BG);
        g.renderOutline(x, y, boxW, boxH, badgeColor == WARN ? WARN : 0xAA3EE6FF);

        int tx = x + 6;
        int ty = y + 5;
        if (!icon.isEmpty()) {
            g.renderItem(icon, tx, y + 4);
            tx += 20;
            ty = y + 8;
        }
        int badgeW = font.width(badge) + 6;
        int titleMax = Math.max(24, boxW - (tx - x) - badgeW - 8);
        g.drawString(font, font.plainSubstrByWidth(title, titleMax), tx, ty, TEXT, false);

        int bx = x + boxW - badgeW - 4;
        g.fill(bx, ty - 2, bx + badgeW, ty + 9, badgeColor == WARN ? 0x33FF6B7A : ON_BG);
        g.drawString(font, badge, bx + 3, ty, badgeColor, false);

        int ly = y + 6 + headerH;
        for (InfoLine line : details) {
            g.drawString(font, font.plainSubstrByWidth(line.text(), boxW - 12), x + 6, ly, line.color(), false);
            ly += 11;
        }
    }

    private static void renderStructureInfo(GuiGraphics g, Font font, Minecraft mc, int screenW, int screenH) {
        if (corner1 == null)
            return;
        BlockPos hoverPos = currentHit instanceof BlockHitResult bhr ? bhr.getBlockPos() : null;
        BlockPos c2 = corner2 != null ? corner2 : (hoverPos != null ? hoverPos : corner1);
        updateBoxCount(mc, corner1, c2);

        int w = Math.abs(corner1.getX() - c2.getX()) + 1;
        int h = Math.abs(corner1.getY() - c2.getY()) + 1;
        int d = Math.abs(corner1.getZ() - c2.getZ()) + 1;
        int maxDim = ServerConfig.hologramStructureMaxDimension();
        int maxBlocks = ServerConfig.hologramStructureMaxBlocks();
        boolean dimOver = w > maxDim || h > maxDim || d > maxDim;
        boolean countOver = cachedCountCapped || cachedNonAirCount > maxBlocks;
        boolean over = dimOver || countOver;

        String title = "Structure " + w + "x" + h + "x" + d;
        String badge = corner2 != null ? "Locked" : "Box";
        int badgeColor = over ? WARN : CYAN;

        List<InfoLine> details = new ArrayList<>();
        if (dimOver) {
            details.add(new InfoLine("Exceeds max " + maxDim + " per side", WARN));
        } else if (countOver) {
            details.add(new InfoLine("Exceeds max " + maxBlocks + " blocks", WARN));
        } else {
            String countStr = cachedNonAirCount + (cachedNonAirCount == 1 ? " block" : " blocks");
            if (cachedBeCount > 0 && takeContents)
                countStr += " \u00B7 " + cachedBeCount + " BE";
            details.add(new InfoLine(countStr, cachedNonAirCount == 0 ? MUTED : CYAN));
        }
        details.add(new InfoLine(corner2 != null ? "Enter/Click grab \u00B7 Up/Down H" : "Click 2nd corner", MUTED));

        int headerW = font.width(title) + 8 + font.width(badge) + 8;
        int bodyW = 0;
        for (InfoLine line : details)
            bodyW = Math.max(bodyW, font.width(line.text()));
        int boxW = Mth.clamp(Math.max(headerW, bodyW) + 12, 128, 224);
        int headerH = 14;
        int boxH = 6 + headerH + details.size() * 11 + 4;

        int x = Mth.clamp(screenW / 2 + 14, 8, Math.max(8, screenW - boxW - 8));
        int y = Mth.clamp(screenH / 2 - boxH / 2, 32, Math.max(32, screenH - boxH - 48));

        g.fill(x, y, x + boxW, y + boxH, BG);
        g.renderOutline(x, y, boxW, boxH, over ? WARN : 0xAA3EE6FF);

        g.drawString(font, title, x + 6, y + 5, TEXT, false);
        int badgeW = font.width(badge) + 6;
        int bx = x + boxW - badgeW - 4;
        g.fill(bx, y + 3, bx + badgeW, y + 14, over ? 0x33FF6B7A : ON_BG);
        g.drawString(font, badge, bx + 3, y + 5, badgeColor, false);

        int ly = y + 6 + headerH;
        for (InfoLine line : details) {
            g.drawString(font, font.plainSubstrByWidth(line.text(), boxW - 12), x + 6, ly, line.color(), false);
            ly += 11;
        }
    }

    private static void renderBottomHint(GuiGraphics g, Font font, Minecraft mc, int screenW, int screenH) {
        boolean survivalHud = mc.gameMode != null && mc.gameMode.canHurtPlayer();
        int hintY = survivalHud ? screenH - 64 : screenH - 40;

        String hint;
        if (mode == GrabMode.STRUCTURE) {
            if (corner1 == null)
                hint = "Click 1st corner \u00B7 [4]/Tab single mode \u00B7 Esc back";
            else if (corner2 == null)
                hint = "Click 2nd corner \u00B7 R-click reset \u00B7 Esc back";
            else
                hint = "Enter/Click grab box \u00B7 Up/Down height \u00B7 R-click reset \u00B7 Esc back";
        } else {
            hint = "Click grab \u00B7 Shift-click grab more \u00B7 [4]/Tab structure \u00B7 Enter/Esc back";
        }
        int hw = font.width(hint) + 14;
        int hx = (screenW - hw) / 2;
        g.fill(hx, hintY, hx + hw, hintY + 14, BG);
        g.renderOutline(hx, hintY, hw, 14, BORDER);
        g.drawString(font, hint, hx + 7, hintY + 3, TEXT, false);
    }

    private record InfoLine(String text, int color) {
    }

    private static void addWrappedLines(List<InfoLine> out, List<String> tokens, String sep,
                                        Font font, int maxW, int color) {
        StringBuilder current = new StringBuilder();
        for (String tok : tokens) {
            if (current.isEmpty()) {
                current.append(tok);
            } else if (font.width(current + sep + tok) <= maxW) {
                current.append(sep).append(tok);
            } else {
                out.add(new InfoLine(current.toString(), color));
                current.setLength(0);
                current.append(tok);
            }
        }
        if (!current.isEmpty())
            out.add(new InfoLine(current.toString(), color));
    }

    private static List<String> collectNotableProps(BlockState state) {
        if (state.getProperties().isEmpty())
            return List.of();
        List<Property<?>> all = HologramStates.properties(state);
        List<String> out = new ArrayList<>(3);
        for (String preferred : PRIORITY_PROPS) {
            if (out.size() >= 3)
                break;
            for (Property<?> prop : all) {
                if (prop.getName().equals(preferred)) {
                    String val = HologramStates.valueOf(state, prop);
                    if ("waterlogged".equals(preferred) && "false".equals(val))
                        break;
                    out.add(prop.getName() + "=" + val);
                    break;
                }
            }
        }
        if (out.isEmpty()) {
            for (int i = 0; i < Math.min(3, all.size()); i++) {
                Property<?> prop = all.get(i);
                out.add(prop.getName() + "=" + HologramStates.valueOf(state, prop));
            }
        }
        return out;
    }

    private static int countItems(Container container) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty())
                total += stack.getCount();
        }
        return total;
    }

    private static int countGear(LivingEntity living) {
        int count = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!living.getItemBySlot(slot).isEmpty())
                count++;
        }
        return count;
    }

    private static List<String> describeEntityTraits(Entity entity) {
        List<String> traits = new ArrayList<>();
        if (entity instanceof LivingEntity living && living.isBaby())
            traits.add("Baby");

        if (entity instanceof Sheep sheep) {
            traits.add(prettyName(sheep.getColor().getName()) + " Wool");
            if (sheep.isSheared())
                traits.add("Sheared");
        } else if (entity instanceof Villager villager) {
            var data = villager.getVillagerData();
            traits.add(prettyName(data.getProfession().name()));
            traits.add("Lv." + data.getLevel());
        } else if (entity instanceof ZombieVillager zv) {
            var data = zv.getVillagerData();
            traits.add(prettyName(data.getProfession().name()));
        } else if (entity instanceof Cat cat) {
            cat.getVariant().unwrapKey().ifPresent(k -> traits.add(prettyName(k.location().getPath())));
        } else if (entity instanceof Wolf wolf) {
            wolf.getVariant().unwrapKey().ifPresent(k -> traits.add(prettyName(k.location().getPath())));
        } else if (entity instanceof Horse horse) {
            traits.add(prettyName(horse.getVariant().getSerializedName()));
        } else if (entity instanceof Fox fox) {
            traits.add(prettyName(fox.getVariant().getSerializedName()));
        } else if (entity instanceof Axolotl axolotl) {
            traits.add(prettyName(axolotl.getVariant().getName()));
        } else if (entity instanceof Parrot parrot) {
            traits.add(prettyName(parrot.getVariant().getSerializedName()));
        } else if (entity instanceof Rabbit rabbit) {
            traits.add(prettyName(rabbit.getVariant().getSerializedName()));
        } else if (entity instanceof Frog frog) {
            frog.getVariant().unwrapKey().ifPresent(k -> traits.add(prettyName(k.location().getPath())));
        } else if (entity instanceof Llama llama) {
            traits.add(prettyName(llama.getVariant().getSerializedName()));
        } else if (entity instanceof Panda panda) {
            traits.add(prettyName(panda.getMainGene().getSerializedName()));
        } else if (entity instanceof MushroomCow cow) {
            traits.add(prettyName(cow.getVariant().getSerializedName()));
        } else if (entity instanceof TropicalFish fish) {
            traits.add(prettyName(fish.getVariant().getSerializedName()));
        }
        return traits;
    }

    private static String prettyName(String raw) {
        if (raw == null || raw.isEmpty())
            return "";
        int colon = raw.indexOf(':');
        String s = colon >= 0 ? raw.substring(colon + 1) : raw;
        String[] parts = s.replace('_', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty())
                continue;
            if (!sb.isEmpty())
                sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1)
                sb.append(p.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
