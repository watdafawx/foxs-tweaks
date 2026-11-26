package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One decorative hologram: a world-anchored root containing {@link HologramGroup}s (for linked
 * transform, animation & FX) and first-class {@link HologramLayer}s (blocks, entities, particles),
 * with no collision, no light, no block entity and no chunk ticket of its own.
 */
public record HologramSpec(
        String id,
        BlockPos pos,
        float offsetX,
        float offsetY,
        float offsetZ,
        float yaw,
        float pitch,
        float roll,
        float scale,
        boolean enabled,
        int renderDistance,
        @Nullable UUID owner,
        String ownerName,
        float appearRadius,
        float appearSeconds,
        int appearStyle,
        List<HologramGroup> groups,
        List<HologramLayer> layers,
        @Nullable HologramTimeline timeline
) {
    public static final int AXIS_Y = 0;
    public static final int AXIS_X = 1;
    public static final int AXIS_Z = 2;

    public static final int REVEAL_RISE = 0;
    public static final int REVEAL_POP = 1;
    public static final int REVEAL_UNFOLD = 2;
    public static final int REVEAL_SPIN = 3;
    public static final int REVEAL_DROP = 4;
    public static final int REVEAL_GLITCH = 5;
    public static final int REVEAL_COUNT = 6;
    public static final int MAX_EFFECTS = 12;

    /** Clamps live in the canonical constructor so a hand-written packet cannot produce garbage. */
    public HologramSpec {
        offsetX = Mth.clamp(offsetX, -16.0F, 16.0F);
        offsetY = Mth.clamp(offsetY, -16.0F, 16.0F);
        offsetZ = Mth.clamp(offsetZ, -16.0F, 16.0F);
        yaw = Mth.wrapDegrees(yaw);
        pitch = Mth.wrapDegrees(pitch);
        roll = Mth.wrapDegrees(roll);
        scale = Mth.clamp(scale, 0.25F, 8.0F);
        renderDistance = Mth.clamp(renderDistance, 8, 512);
        appearRadius = Mth.clamp(appearRadius, 0.0F, 64.0F);
        appearSeconds = Mth.clamp(appearSeconds, 0.0F, 10.0F);
        appearStyle = Mth.clamp(appearStyle, 0, REVEAL_COUNT - 1);
        groups = groups.size() > HologramGroup.MAX_GROUPS
                ? List.copyOf(groups.subList(0, HologramGroup.MAX_GROUPS))
                : List.copyOf(groups);
        layers = withUids(layers.size() > HologramLayer.MAX_LAYERS
                ? layers.subList(0, HologramLayer.MAX_LAYERS)
                : layers);
        if (timeline != null)
            timeline = timeline.boundTo(groups, layers);
    }

    /** The pre-timeline shape. */
    public HologramSpec(String id, BlockPos pos, float offsetX, float offsetY, float offsetZ, float yaw, float pitch,
                        float roll, float scale, boolean enabled, int renderDistance, @Nullable UUID owner,
                        String ownerName, float appearRadius, float appearSeconds, int appearStyle,
                        List<HologramGroup> groups, List<HologramLayer> layers) {
        this(id, pos, offsetX, offsetY, offsetZ, yaw, pitch, roll, scale, enabled, renderDistance, owner, ownerName,
                appearRadius, appearSeconds, appearStyle, groups, layers, null);
    }

    /**
     * Gives every layer a unique {@link HologramLayer#uid()}: missing ones (old saves, fresh layers) and the second
     * of two equal ones (a duplicated layer) get the smallest free {@code lN}. Deterministic, so the same list always
     * gets the same ids.
     */
    private static List<HologramLayer> withUids(List<HologramLayer> layers) {
        java.util.Set<String> used = new java.util.HashSet<>();
        boolean ok = true;
        for (HologramLayer l : layers) {
            if (l.uid().isEmpty() || !used.add(l.uid())) {
                ok = false;
                break;
            }
        }
        if (ok)
            return List.copyOf(layers);
        used.clear();
        for (HologramLayer l : layers)
            if (!l.uid().isEmpty())
                used.add(l.uid());
        java.util.Set<String> taken = new java.util.HashSet<>();
        List<HologramLayer> out = new ArrayList<>(layers.size());
        int next = 1;
        for (HologramLayer l : layers) {
            if (!l.uid().isEmpty() && taken.add(l.uid())) {
                out.add(l);
                continue;
            }
            while (used.contains("l" + next))
                next++;
            String uid = "l" + next;
            used.add(uid);
            taken.add(uid);
            out.add(l.withUid(uid));
        }
        return List.copyOf(out);
    }

    public HologramSpec withTimeline(@Nullable HologramTimeline timeline) {
        return new HologramSpec(this.id, this.pos, this.offsetX, this.offsetY, this.offsetZ,
                this.yaw, this.pitch, this.roll, this.scale,
                this.enabled, this.renderDistance, this.owner, this.ownerName,
                this.appearRadius, this.appearSeconds, this.appearStyle, this.groups, this.layers, timeline);
    }

    @Nullable
    public HologramLayer layerByUid(String uid) {
        for (HologramLayer l : this.layers)
            if (l.uid().equals(uid))
                return l;
        return null;
    }

    public static HologramSpec create(String id, BlockPos pos, String block, @Nullable UUID owner, String ownerName) {
        HologramLayer first = HologramLayer.block(block, Map.of(), new CompoundTag());
        return new HologramSpec(id, pos, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                true, 48, owner, ownerName, 0.0F, 0.6F, REVEAL_RISE, List.of(), List.of(first));
    }

    /** Same hologram, re-identified and moved; every presentation field comes along. */
    public HologramSpec at(String newId, BlockPos newPos, @Nullable UUID newOwner, String newOwnerName) {
        return new HologramSpec(newId, newPos, this.offsetX, this.offsetY, this.offsetZ,
                this.yaw, this.pitch, this.roll, this.scale,
                this.enabled, this.renderDistance, newOwner, newOwnerName,
                this.appearRadius, this.appearSeconds, this.appearStyle, this.groups, this.layers, this.timeline);
    }

    public HologramSpec withRenderDistance(int distance) {
        return new HologramSpec(this.id, this.pos, this.offsetX, this.offsetY, this.offsetZ,
                this.yaw, this.pitch, this.roll, this.scale,
                this.enabled, distance, this.owner, this.ownerName,
                this.appearRadius, this.appearSeconds, this.appearStyle, this.groups, this.layers, this.timeline);
    }

    public HologramSpec withGroups(List<HologramGroup> groups) {
        return new HologramSpec(this.id, this.pos, this.offsetX, this.offsetY, this.offsetZ,
                this.yaw, this.pitch, this.roll, this.scale,
                this.enabled, this.renderDistance, this.owner, this.ownerName,
                this.appearRadius, this.appearSeconds, this.appearStyle, groups, this.layers, this.timeline);
    }

    public HologramSpec withLayers(List<HologramLayer> layers) {
        return new HologramSpec(this.id, this.pos, this.offsetX, this.offsetY, this.offsetZ,
                this.yaw, this.pitch, this.roll, this.scale,
                this.enabled, this.renderDistance, this.owner, this.ownerName,
                this.appearRadius, this.appearSeconds, this.appearStyle, this.groups, layers, this.timeline);
    }

    /** Replaces layer 0's props/data/effects helpers for single-layer call sites. */
    public HologramSpec withLayer(int index, HologramLayer layer) {
        if (index < 0 || index >= this.layers.size())
            return this;
        List<HologramLayer> next = new ArrayList<>(this.layers);
        next.set(index, layer);
        return withLayers(next);
    }

    @Nullable
    public HologramLayer primaryLayer() {
        return this.layers.isEmpty() ? null : this.layers.get(0);
    }

    /** Registry id of the first layer (used for command labels, clipboard, and id generation). */
    public String primaryId() {
        return this.layers.isEmpty() ? "hologram" : this.layers.get(0).id();
    }

    @Nullable
    public HologramGroup groupById(String groupId) {
        if (groupId == null || groupId.isEmpty())
            return null;
        for (HologramGroup g : this.groups) {
            if (g.id().equals(groupId))
                return g;
        }
        return null;
    }

    /** Builds the static world-space transform matrix for the root (anchor + root offset/rot/scale). */
    public Matrix4f rootMatrix() {
        return new Matrix4f()
                .translate(this.pos.getX() + this.offsetX + 0.5F,
                        this.pos.getY() + this.offsetY,
                        this.pos.getZ() + this.offsetZ + 0.5F)
                .rotateY((float) Math.toRadians(this.yaw))
                .rotateX((float) Math.toRadians(this.pitch))
                .rotateZ((float) Math.toRadians(this.roll))
                .scale(this.scale);
    }

    /** Builds the static local matrix of a group inside the root frame. */
    public static Matrix4f groupMatrix(HologramGroup g) {
        return new Matrix4f()
                .translate(g.offsetX(), g.offsetY(), g.offsetZ())
                .rotateY((float) Math.toRadians(g.yaw()))
                .rotateX((float) Math.toRadians(g.pitch()))
                .rotateZ((float) Math.toRadians(g.roll()))
                .scale(g.scale());
    }

    /** Builds the static local matrix of a layer inside its parent frame (group or root). */
    public static Matrix4f layerMatrix(HologramLayer l) {
        return new Matrix4f()
                .translate(l.offsetX(), l.offsetY(), l.offsetZ())
                .rotateY((float) Math.toRadians(l.yaw()))
                .rotateX((float) Math.toRadians(l.pitch()))
                .rotateZ((float) Math.toRadians(l.roll()))
                .scale(l.kind() == HologramLayer.Kind.PARTICLE ? 1.0F : l.scale());
    }

    /** World-space bounding box covering all visible layers (respecting group & root transforms). */
    public AABB box() {
        Matrix4f root = rootMatrix();
        AABB bounds = null;
        Vector3f tmp = new Vector3f();

        for (HologramLayer layer : this.layers) {
            if (!layer.visible())
                continue;
            HologramGroup grp = groupById(layer.groupId());
            if (grp != null && !grp.visible())
                continue;

            Matrix4f m = new Matrix4f(root);
            if (grp != null)
                m.mul(groupMatrix(grp));
            m.mul(layerMatrix(layer));

            float halfX;
            float halfZ;
            float minY;
            float maxY;
            if (layer.kind() == HologramLayer.Kind.STRUCTURE) {
                int[] dims = HologramStructure.dimensions(layer.data());
                halfX = dims[0] * 0.5F + 0.05F;
                halfZ = dims[2] * 0.5F + 0.05F;
                minY = -0.05F;
                maxY = dims[1] + 0.05F;
            } else if (layer.kind() == HologramLayer.Kind.IMAGE) {
                // Bottom-centred quad. A billboard can face any way, so its box is the sphere around its centre.
                HologramImage img = layer.image();
                float w = img.width();
                float h = img.heightFor(layer.id());
                if (img.billboard() == HologramImage.Billboard.FIXED) {
                    halfX = w * 0.5F + 0.05F;
                    halfZ = 0.05F;
                    minY = -0.05F;
                    maxY = h + 0.05F;
                } else {
                    float r = (float) Math.sqrt(w * w + h * h) * 0.5F + 0.05F;
                    halfX = r;
                    halfZ = r;
                    minY = h * 0.5F - r;
                    maxY = h * 0.5F + r;
                }
            } else if (layer.kind() == HologramLayer.Kind.PARTICLE) {
                halfX = Math.max(0.35F, layer.scale());
                halfZ = halfX;
                minY = -halfX;
                maxY = halfX;
            } else {
                halfX = 0.55F;
                halfZ = 0.55F;
                minY = -0.05F;
                maxY = 1.05F;
            }

            double minX = Double.POSITIVE_INFINITY, minYd = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxYd = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
            for (int cx = 0; cx < 2; cx++) {
                for (int cy = 0; cy < 2; cy++) {
                    for (int cz = 0; cz < 2; cz++) {
                        tmp.set(cx == 0 ? -halfX : halfX,
                                cy == 0 ? minY : maxY,
                                cz == 0 ? -halfZ : halfZ);
                        m.transformPosition(tmp);
                        if (tmp.x < minX) minX = tmp.x;
                        if (tmp.y < minYd) minYd = tmp.y;
                        if (tmp.z < minZ) minZ = tmp.z;
                        if (tmp.x > maxX) maxX = tmp.x;
                        if (tmp.y > maxYd) maxYd = tmp.y;
                        if (tmp.z > maxZ) maxZ = tmp.z;
                    }
                }
            }
            AABB layerBox = new AABB(minX, minYd, minZ, maxX, maxYd, maxZ);
            bounds = bounds == null ? layerBox : bounds.minmax(layerBox);
        }

        if (bounds == null) {
            double cx = this.pos.getX() + this.offsetX + 0.5;
            double cy = this.pos.getY() + this.offsetY + 0.5;
            double cz = this.pos.getZ() + this.offsetZ + 0.5;
            return new AABB(cx - 0.5, cy - 0.5, cz - 0.5, cx + 0.5, cy + 0.5, cz + 0.5);
        }
        return bounds;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putByte("v", (byte) 2);
        tag.putString("id", this.id);
        tag.putLong("pos", this.pos.asLong());
        if (this.offsetX != 0.0F)
            tag.putFloat("offsetX", this.offsetX);
        if (this.offsetY != 0.0F)
            tag.putFloat("offsetY", this.offsetY);
        if (this.offsetZ != 0.0F)
            tag.putFloat("offsetZ", this.offsetZ);
        if (this.yaw != 0.0F)
            tag.putFloat("yaw", this.yaw);
        if (this.pitch != 0.0F)
            tag.putFloat("pitch", this.pitch);
        if (this.roll != 0.0F)
            tag.putFloat("roll", this.roll);
        if (this.scale != 1.0F)
            tag.putFloat("scale", this.scale);
        tag.putBoolean("enabled", this.enabled);
        tag.putInt("renderDistance", this.renderDistance);
        if (this.owner != null)
            tag.putUUID("owner", this.owner);
        tag.putString("ownerName", this.ownerName);
        tag.putFloat("appearRadius", this.appearRadius);
        tag.putFloat("appearSeconds", this.appearSeconds);
        if (this.appearStyle != REVEAL_RISE)
            tag.putByte("appearStyle", (byte) this.appearStyle);
        if (!this.groups.isEmpty())
            tag.put("groups", HologramGroup.listToTag(this.groups));
        tag.put("layers", HologramLayer.listToTag(this.layers));
        if (this.timeline != null)
            tag.put("timeline", this.timeline.toTag());
        return tag;
    }

    @Nullable
    public static HologramSpec fromTag(CompoundTag tag) {
        String id = tag.getString("id");
        if (id.isEmpty())
            return null;

        UUID owner = tag.contains("owner") ? tag.getUUID("owner") : null;
        BlockPos pos = BlockPos.of(tag.getLong("pos"));
        float ox = tag.getFloat("offsetX");
        float oy = tag.getFloat("offsetY");
        float oz = tag.getFloat("offsetZ");
        boolean enabled = !tag.contains("enabled") || tag.getBoolean("enabled");
        int renderDist = tag.contains("renderDistance") ? tag.getInt("renderDistance") : 48;
        String ownerName = tag.getString("ownerName");
        float appearRadius = tag.getFloat("appearRadius");
        float appearSeconds = tag.contains("appearSeconds") ? tag.getFloat("appearSeconds") : 0.6F;
        int appearStyle = tag.getByte("appearStyle");

        // v2 unified format
        if (tag.contains("layers", Tag.TAG_LIST)) {
            List<HologramGroup> groups = tag.contains("groups", Tag.TAG_LIST)
                    ? HologramGroup.listFromTag(tag.getList("groups", Tag.TAG_COMPOUND))
                    : List.of();
            List<HologramLayer> layers = HologramLayer.listFromTag(tag.getList("layers", Tag.TAG_COMPOUND));
            if (layers.isEmpty())
                return null;
            float scale = tag.contains("scale") ? tag.getFloat("scale") : 1.0F;
            HologramTimeline timeline = tag.contains("timeline", Tag.TAG_COMPOUND)
                    ? HologramTimeline.fromTag(tag.getCompound("timeline"))
                    : null;
            return new HologramSpec(id, pos, ox, oy, oz,
                    tag.getFloat("yaw"), tag.getFloat("pitch"), tag.getFloat("roll"), scale,
                    enabled, renderDist, owner, ownerName, appearRadius, appearSeconds, appearStyle,
                    groups, layers, timeline);
        }

        // Legacy v1 migration: base block + parts[] -> unified layers (+ group if multi-part)
        String block = tag.getString("block");
        ListTag legacyParts = tag.contains("parts", Tag.TAG_LIST) ? tag.getList("parts", Tag.TAG_COMPOUND) : new ListTag();
        if (block.isEmpty() && legacyParts.isEmpty())
            return null;

        Map<String, String> props = new LinkedHashMap<>();
        CompoundTag rawProps = tag.getCompound("props");
        for (String key : rawProps.getAllKeys())
            props.put(key, rawProps.getString(key));

        List<HologramEffect> effects = tag.contains("effects")
                ? HologramEffect.listFromTag(tag.getList("effects", Tag.TAG_COMPOUND))
                : List.of();
        CompoundTag beData = tag.contains("blockEntity") ? tag.getCompound("blockEntity") : new CompoundTag();

        float legacyScale = tag.contains("scale") ? tag.getFloat("scale") : 1.0F;
        float legacyYaw = tag.getFloat("yaw");
        float legacyPitch = tag.getFloat("pitch");
        float legacyRoll = tag.getFloat("roll");
        float legacySpin = tag.getFloat("spin");
        int legacySpinAxis = tag.getByte("spinAxis");
        float legacyHover = tag.getFloat("hover");
        float legacyHoverPeriod = tag.contains("hoverPeriod") ? tag.getFloat("hoverPeriod") : 4.0F;
        boolean legacyFullbright = tag.getBoolean("fullbright");

        List<HologramGroup> groups = new ArrayList<>();
        List<HologramLayer> layers = new ArrayList<>();
        boolean hasBase = !block.isEmpty() && !"minecraft:air".equals(block);

        if (legacyParts.isEmpty()) {
            // Simple single-block legacy hologram: migrate all transform/anim/FX cleanly onto Layer 0
            if (!hasBase)
                return null;
            layers.add(new HologramLayer(HologramLayer.Kind.BLOCK, block, props, beData, "", true,
                    0.0F, 0.0F, 0.0F, legacyYaw, legacyPitch, legacyRoll, legacyScale,
                    legacySpin, legacySpinAxis, legacyHover, legacyHoverPeriod, legacyFullbright,
                    effects, 10.0F, 0.01F));
            return new HologramSpec(id, pos, ox, oy, oz, 0.0F, 0.0F, 0.0F, 1.0F,
                    enabled, renderDist, owner, ownerName, appearRadius, appearSeconds, appearStyle,
                    groups, layers);
        }

        // Legacy multi-part: put linked anim/FX into Group 1 so everything keeps moving together
        String grpId = "";
        boolean needsGroup = legacySpin != 0.0F || legacyHover != 0.0F || legacyFullbright || !effects.isEmpty()
                || legacyYaw != 0.0F || legacyPitch != 0.0F || legacyRoll != 0.0F;
        if (needsGroup) {
            grpId = "g1";
            groups.add(new HologramGroup(grpId, "Group 1", true,
                    0.0F, 0.0F, 0.0F, legacyYaw, legacyPitch, legacyRoll, 1.0F,
                    legacySpin, legacySpinAxis, legacyHover, legacyHoverPeriod, legacyFullbright, effects));
        }

        if (hasBase) {
            layers.add(new HologramLayer(HologramLayer.Kind.BLOCK, block, props, beData, grpId, true,
                    0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, legacyScale,
                    0.0F, AXIS_Y, 0.0F, 4.0F, false, List.of(), 10.0F, 0.01F));
        }

        float partScaleMult = hasBase ? 1.0F : legacyScale;
        for (int i = 0; i < legacyParts.size() && layers.size() < HologramLayer.MAX_LAYERS; i++) {
            CompoundTag pt = legacyParts.getCompound(i);
            HologramLayer.Kind kind;
            try {
                kind = HologramLayer.Kind.valueOf(pt.getString("kind"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            String pid = pt.getString("id");
            if (pid.isEmpty())
                continue;
            Map<String, String> pProps = new LinkedHashMap<>();
            CompoundTag rawP = pt.getCompound("props");
            for (String k : rawP.getAllKeys())
                pProps.put(k, rawP.getString(k));
            float pScale = (pt.contains("scale") ? pt.getFloat("scale") : 1.0F) * partScaleMult;
            layers.add(new HologramLayer(kind, pid, pProps, new CompoundTag(), grpId, true,
                    pt.getFloat("dx"), pt.getFloat("dy"), pt.getFloat("dz"),
                    pt.getFloat("yaw"), 0.0F, 0.0F, pScale,
                    0.0F, AXIS_Y, 0.0F, 4.0F, false, List.of(), 10.0F, 0.01F));
        }

        if (layers.isEmpty())
            return null;

        return new HologramSpec(id, pos, ox, oy, oz, 0.0F, 0.0F, 0.0F, 1.0F,
                enabled, renderDist, owner, ownerName, appearRadius, appearSeconds, appearStyle,
                groups, layers);
    }

    public static boolean looksValid(Tag tag) {
        return tag instanceof CompoundTag compound && !compound.getString("id").isEmpty()
                && (!compound.getList("layers", Tag.TAG_COMPOUND).isEmpty()
                || !compound.getString("block").isEmpty()
                || !compound.getList("parts", Tag.TAG_COMPOUND).isEmpty());
    }
}
