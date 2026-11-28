package dev.watdafawx.foxstweaks.client.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.watdafawx.foxstweaks.ServerConfig;
import dev.watdafawx.foxstweaks.hologram.HologramAudioMod;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramGroup;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.asset.AssetFrames;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.HologramStates;
import dev.watdafawx.foxstweaks.hologram.HologramTimeline;
import dev.watdafawx.foxstweaks.hologram.Holograms;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Hologram spec <-> protocol JSON (docs/hologram-web-protocol.md). Reading is the trust boundary: the browser is
 * treated exactly like a hand-written packet. Unknown registry ids, unknown block properties or values, bad
 * group references, non-finite numbers, wrong types and over-long lists are refused with a message; numbers
 * are clamped to the same ranges the records' own constructors use. Identity (id, position, owner) is never
 * read: it always comes from the editor's own spec.
 *
 * <p>A layer's block entity / entity NBT never travels back from the browser. The browser only sees an opaque
 * {@code data.key}; on the way back that key is looked up in what this session sent, so data can follow its layer
 * through reordering and duplication but cannot be invented.
 */
final class HologramWebJson {
    private static final Pattern GROUP_ID = Pattern.compile("[A-Za-z0-9_-]{1,24}");
    private static final int MAX_NAME = 32;
    private static final int MAX_PROPS = 32;
    private static final int SNBT_PREVIEW_BYTES = 4096;

    private HologramWebJson() {
    }

    // ---------------------------------------------------------------- write

    /** {@code dataKeys} receives key -> tag for every layer that carries data, so an edit can refer back to it. */
    static JsonObject write(HologramSpec spec, int maxRenderDistance, Map<String, CompoundTag> dataKeys,
                            Function<String, String> snbtCache) {
        JsonObject o = new JsonObject();
        o.addProperty("id", spec.id());
        JsonArray pos = new JsonArray();
        pos.add(spec.pos().getX());
        pos.add(spec.pos().getY());
        pos.add(spec.pos().getZ());
        o.add("pos", pos);
        o.addProperty("owner", spec.ownerName());

        JsonObject root = new JsonObject();
        root.add("offset", vec(spec.offsetX(), spec.offsetY(), spec.offsetZ()));
        root.add("rotation", vec(spec.yaw(), spec.pitch(), spec.roll()));
        root.addProperty("scale", spec.scale());
        o.add("root", root);

        o.addProperty("renderDistance", spec.renderDistance());
        o.addProperty("maxRenderDistance", maxRenderDistance);

        JsonObject reveal = new JsonObject();
        reveal.addProperty("radius", spec.appearRadius());
        reveal.addProperty("seconds", spec.appearSeconds());
        reveal.addProperty("style", spec.appearStyle());
        o.add("reveal", reveal);

        JsonArray groups = new JsonArray();
        for (HologramGroup g : spec.groups()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", g.id());
            j.addProperty("name", g.name());
            j.addProperty("visible", g.visible());
            transform(j, g.offsetX(), g.offsetY(), g.offsetZ(), g.yaw(), g.pitch(), g.roll(), g.scale());
            motion(j, g.spin(), g.spinAxis(), g.hover(), g.hoverPeriod(), g.fullbright());
            j.add("effects", effects(g.effects()));
            groups.add(j);
        }
        o.add("groups", groups);

        JsonArray layers = new JsonArray();
        for (HologramLayer l : spec.layers()) {
            JsonObject j = new JsonObject();
            j.addProperty("uid", l.uid());
            j.addProperty("kind", l.kind().name());
            j.addProperty("id", l.id());
            JsonObject props = new JsonObject();
            l.props().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> props.addProperty(e.getKey(), e.getValue()));
            j.add("props", props);
            j.addProperty("group", l.groupId());
            j.addProperty("visible", l.visible());
            transform(j, l.offsetX(), l.offsetY(), l.offsetZ(), l.yaw(), l.pitch(), l.roll(), l.scale());
            motion(j, l.spin(), l.spinAxis(), l.hover(), l.hoverPeriod(), l.fullbright());
            j.addProperty("particleRate", l.particleRate());
            j.addProperty("particleSpeed", l.particleSpeed());
            j.add("effects", effects(l.effects()));
            if (!l.data().isEmpty()) {
                CompoundTag data = l.data();
                int size = data.sizeInBytes();
                String key = Integer.toHexString(data.hashCode()) + "-" + Integer.toHexString(size);
                dataKeys.put(key, data);
                JsonObject d = new JsonObject();
                d.addProperty("key", key);
                d.addProperty("bytes", size);
                if (size <= SNBT_PREVIEW_BYTES)
                    d.addProperty("snbt", snbtCache.apply(key));
                j.add("data", d);
            }
            layers.add(j);
        }
        o.add("layers", layers);
        if (spec.timeline() != null)
            o.add("timeline", timeline(spec.timeline()));
        if (spec.attach() != null) {
            JsonObject a = new JsonObject();
            a.addProperty("uuid", spec.attach().playerUuid().toString());
            a.addProperty("name", spec.attach().playerName());
            a.addProperty("follow", spec.attach().follow().name());
            a.addProperty("hideFirstPerson", spec.attach().hideFirstPerson());
            a.addProperty("scaleWithPlayer", spec.attach().scaleWithPlayer());
            o.add("attach", a);
        }
        return o;
    }

    static JsonObject timeline(HologramTimeline tl) {
        JsonObject t = new JsonObject();
        t.addProperty("length", tl.length());
        t.addProperty("mode", tl.mode().name());
        t.addProperty("start", tl.start().name());
        JsonArray tracks = new JsonArray();
        for (HologramTimeline.Track tr : tl.tracks()) {
            JsonObject j = new JsonObject();
            j.addProperty("target", tr.target().name());
            j.addProperty("id", tr.id());
            j.addProperty("prop", tr.prop().name());
            if (tr.prop() == HologramTimeline.Prop.EFFECT)
                j.addProperty("effect", tr.effect());
            JsonArray keys = new JsonArray();
            for (HologramTimeline.Key k : tr.keys()) {
                JsonObject kj = new JsonObject();
                kj.addProperty("t", k.time());
                if (tr.prop() == HologramTimeline.Prop.TINT)
                    kj.addProperty("v", (int) k.value());
                else
                    kj.addProperty("v", k.value());
                kj.addProperty("ease", k.ease().name());
                if (k.ease() == HologramTimeline.Ease.CUBIC) {
                    JsonArray b = new JsonArray();
                    b.add(k.x1());
                    b.add(k.y1());
                    b.add(k.x2());
                    b.add(k.y2());
                    kj.add("bezier", b);
                }
                keys.add(kj);
            }
            j.add("keys", keys);
            tracks.add(j);
        }
        t.add("tracks", tracks);
        if (!tl.audio().isEmpty()) {
            JsonArray audio = new JsonArray();
            for (HologramTimeline.AudioLink link : tl.audio()) {
                JsonObject a = audioMod(link.mod());
                a.addProperty("target", link.target().name());
                a.addProperty("id", link.id());
                audio.add(a);
            }
            t.add("audio", audio);
        }
        return t;
    }

    /** One audio modulation; {@code min}/{@code max} only when the range is clamped. */
    static JsonObject audioMod(HologramAudioMod m) {
        JsonObject j = new JsonObject();
        j.addProperty("param", m.param().name());
        j.addProperty("source", m.source().name());
        j.addProperty("signal", m.signal().name());
        j.addProperty("amount", m.amount());
        j.addProperty("sensitivity", m.sensitivity());
        j.addProperty("attack", m.attack());
        j.addProperty("release", m.release());
        j.addProperty("threshold", m.threshold());
        if (!Float.isNaN(m.min()))
            j.addProperty("min", m.min());
        if (!Float.isNaN(m.max()))
            j.addProperty("max", m.max());
        return j;
    }

    private static void transform(JsonObject j, float x, float y, float z, float yaw, float pitch, float roll, float scale) {
        j.add("offset", vec(x, y, z));
        j.add("rotation", vec(yaw, pitch, roll));
        j.addProperty("scale", scale);
    }

    private static void motion(JsonObject j, float spin, int spinAxis, float hover, float hoverPeriod, boolean fullbright) {
        j.addProperty("spin", spin);
        j.addProperty("spinAxis", spinAxis);
        j.addProperty("hover", hover);
        j.addProperty("hoverPeriod", hoverPeriod);
        j.addProperty("fullbright", fullbright);
    }

    private static JsonArray vec(float x, float y, float z) {
        JsonArray a = new JsonArray();
        a.add(new JsonPrimitive(x));
        a.add(new JsonPrimitive(y));
        a.add(new JsonPrimitive(z));
        return a;
    }

    static JsonArray effects(List<HologramEffect> effects) {
        JsonArray a = new JsonArray();
        for (HologramEffect e : effects)
            a.add(effect(e));
        return a;
    }

    static JsonObject effect(HologramEffect e) {
        JsonObject j = new JsonObject();
        j.addProperty("type", e.type().name());
        j.addProperty("strength", e.strength());
        j.addProperty("speed", e.speed());
        j.addProperty("color", e.color());
        j.addProperty("color2", e.color2());
        j.addProperty("axis", e.axis());
        j.addProperty("phase", e.phase());
        j.addProperty("trigger", e.trigger().name());
        if (e.hasAudio()) {
            JsonArray audio = new JsonArray();
            for (HologramAudioMod m : e.audio())
                audio.add(audioMod(m));
            j.add("audio", audio);
        }
        return j;
    }

    // ---------------------------------------------------------------- read (untrusted)

    static final class Invalid extends Exception {
        Invalid(String message) {
            super(message);
        }
    }

    /** A browser spec turned into one the editor may apply, keeping {@code base}'s identity. */
    static HologramSpec read(JsonObject o, HologramSpec base, int maxRenderDistance, Map<String, CompoundTag> dataKeys)
            throws Invalid {
        JsonObject root = obj(o, "root");
        float[] off = vec3(root, "offset", -16, 16);
        float[] rot = vec3(root, "rotation", -360, 360);
        float scale = num(root, "scale", 1.0F, 0.25F, 8.0F);
        int renderDistance = Math.round(num(o, "renderDistance", base.renderDistance(), 8, maxRenderDistance));
        JsonObject reveal = obj(o, "reveal");
        float radius = num(reveal, "radius", 0, 0, 64);
        float seconds = num(reveal, "seconds", 0.6F, 0, 10);
        int style = Math.round(num(reveal, "style", 0, 0, HologramSpec.REVEAL_COUNT - 1));

        JsonArray groupsJson = arr(o, "groups", HologramGroup.MAX_GROUPS);
        List<HologramGroup> groups = new ArrayList<>();
        Set<String> groupIds = new HashSet<>();
        for (JsonElement el : groupsJson) {
            JsonObject g = asObj(el, "group");
            String id = str(g, "id", 24);
            if (!GROUP_ID.matcher(id).matches())
                throw new Invalid("bad group id '" + id + "'");
            if (!groupIds.add(id))
                throw new Invalid("duplicate group id '" + id + "'");
            String name = cleanName(str(g, "name", MAX_NAME * 4));
            float[] go = vec3(g, "offset", -16, 16);
            float[] gr = vec3(g, "rotation", -360, 360);
            groups.add(new HologramGroup(id, name.isEmpty() ? id : name, bool(g, "visible", true),
                    go[0], go[1], go[2], gr[0], gr[1], gr[2], num(g, "scale", 1, 0.1F, 8),
                    num(g, "spin", 0, -360, 360), axis(g, "spinAxis"),
                    num(g, "hover", 0, 0, 4), num(g, "hoverPeriod", 4, 0.5F, 120),
                    bool(g, "fullbright", false), readEffects(g)));
        }

        JsonArray layersJson = arr(o, "layers", HologramLayer.MAX_LAYERS);
        if (layersJson.isEmpty())
            throw new Invalid("a hologram needs at least one layer");
        List<HologramLayer> layers = new ArrayList<>();
        Set<String> layerUids = new HashSet<>();
        for (JsonElement el : layersJson) {
            JsonObject l = asObj(el, "layer");
            String uid = l.has("uid") && !l.get("uid").isJsonNull() ? str(l, "uid", 24) : "";
            if (!uid.isEmpty() && !GROUP_ID.matcher(uid).matches())
                throw new Invalid("bad layer uid '" + uid + "'");
            // A repeated uid (a layer copied as JSON, a v1 page duplicating one) is not an error: the first layer
            // keeps it, so tracks stay where they were, and the spec gives the copy a fresh one.
            if (!uid.isEmpty() && !layerUids.add(uid))
                uid = "";
            HologramLayer.Kind kind;
            try {
                kind = HologramLayer.Kind.valueOf(str(l, "kind", 16));
            } catch (IllegalArgumentException e) {
                throw new Invalid("unknown layer kind");
            }
            String id = str(l, "id", 512);
            // A structure layer's id is its display name ("Structure 5x4x6"), not a registry id; an image layer's
            // is an asset hash. Neither is looked up in a registry.
            boolean structure = kind == HologramLayer.Kind.STRUCTURE;
            boolean image = kind == HologramLayer.Kind.IMAGE || kind == HologramLayer.Kind.MODEL;
            ResourceLocation key = structure || image ? null : ResourceLocation.tryParse(id);
            if (key == null && !structure && !image)
                throw new Invalid("bad id '" + id + "'");
            if (image && !AssetFrames.validId(id))
                throw new Invalid("bad asset id '" + id + "'");
            Map<String, String> props = new LinkedHashMap<>();
            switch (kind) {
                case BLOCK -> {
                    Block block = BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
                    if (block == null)
                        throw new Invalid("unknown block '" + id + "'");
                    JsonObject p = l.has("props") ? asObj(l.get("props"), "props") : new JsonObject();
                    if (p.size() > MAX_PROPS)
                        throw new Invalid("too many properties");
                    for (var e : p.entrySet()) {
                        Property<?> prop = HologramStates.property(block.defaultBlockState(), e.getKey());
                        if (prop == null)
                            throw new Invalid(id + " has no property '" + e.getKey() + "'");
                        String value = asStr(e.getValue(), "property value", 64);
                        if (prop.getValue(value).isEmpty())
                            throw new Invalid(e.getKey() + "=" + value + " is not valid for " + id);
                        props.put(e.getKey(), value);
                    }
                }
                case ENTITY -> {
                    if (!BuiltInRegistries.ENTITY_TYPE.containsKey(key))
                        throw new Invalid("unknown entity '" + id + "'");
                    if (l.has("props") && l.get("props").isJsonObject()) {
                        JsonObject p = l.getAsJsonObject("props");
                        if (p.has("effect"))
                            props.put("effect", asStr(p.get("effect"), "entity effect", 128));
                        if (p.has("invisible"))
                            props.put("invisible", asStr(p.get("invisible"), "entity invisible", 8));
                    }
                }
                case PARTICLE -> {
                    if (!BuiltInRegistries.PARTICLE_TYPE.containsKey(key))
                        throw new Invalid("unknown or unsupported particle '" + id + "'");
                }
                case IMAGE, MODEL -> {
                    // Image settings are plain strings; the layer clamps them (HologramImage#normalize), and the
                    // server checks that the asset exists when the hologram is saved.
                    JsonObject p = l.has("props") ? asObj(l.get("props"), "props") : new JsonObject();
                    if (p.size() > MAX_PROPS)
                        throw new Invalid("too many properties");
                    for (var e : p.entrySet())
                        props.put(e.getKey(), asStr(e.getValue(), "asset setting", 64));
                }
                case STRUCTURE -> {
                }
            }
            String group = l.has("group") && !l.get("group").isJsonNull() ? str(l, "group", 24) : "";
            if (!group.isEmpty() && !groupIds.contains(group))
                throw new Invalid("layer refers to missing group '" + group + "'");

            CompoundTag data = new CompoundTag();
            if (l.has("data") && !l.get("data").isJsonNull()) {
                JsonObject d = asObj(l.get("data"), "data");
                String dataKey = str(d, "key", 64);
                CompoundTag known = dataKeys.get(dataKey);
                int cap = structure ? Holograms.maxStructureDataBytes() : Holograms.MAX_DATA_BYTES;
                if (known != null && known.sizeInBytes() <= cap)
                    data = known.copy();
            }
            // The blocks of a structure live only in its data; the browser cannot invent or drop them.
            if (structure && data.isEmpty())
                throw new Invalid("structure layer '" + id + "' lost its data");

            float[] lo = vec3(l, "offset", -16, 16);
            float[] lr = vec3(l, "rotation", -360, 360);
            layers.add(new HologramLayer(kind, structure || image ? id : key.toString(), props, data, group, bool(l, "visible", true),
                    lo[0], lo[1], lo[2], lr[0], lr[1], lr[2],
                    num(l, "scale", kind == HologramLayer.Kind.PARTICLE ? 0.35F : 1.0F, 0.05F, 8),
                    num(l, "spin", 0, -360, 360), axis(l, "spinAxis"),
                    num(l, "hover", 0, 0, 4), num(l, "hoverPeriod", 4, 0.5F, 120),
                    bool(l, "fullbright", false), readEffects(l),
                    num(l, "particleRate", 10, 1, 40), num(l, "particleSpeed", 0.01F, -0.5F, 0.5F), uid));
        }

        HologramTimeline timeline = o.has("timeline") && !o.get("timeline").isJsonNull()
                ? readTimeline(asObj(o.get("timeline"), "timeline"), groups, layers)
                : null;
        dev.watdafawx.foxstweaks.hologram.HologramAttach attach = null;
        if (o.has("attach") && !o.get("attach").isJsonNull()) {
            JsonObject a = asObj(o.get("attach"), "attach");
            String rawUuid = a.has("uuid") && !a.get("uuid").isJsonNull() ? str(a, "uuid", 64) : "self";
            String name = a.has("name") && !a.get("name").isJsonNull() ? str(a, "name", 64) : "";
            var follow = dev.watdafawx.foxstweaks.hologram.HologramAttach.Follow.parse(
                    a.has("follow") && !a.get("follow").isJsonNull() ? str(a, "follow", 16) : "BODY_YAW");
            boolean hideFp = bool(a, "hideFirstPerson", true);
            boolean scaleWithPlayer = bool(a, "scaleWithPlayer", true);
            java.util.UUID uuid = null;
            if ("self".equalsIgnoreCase(rawUuid) || rawUuid.isEmpty()) {
                var player = net.minecraft.client.Minecraft.getInstance().player;
                if (player != null) {
                    uuid = player.getUUID();
                    name = player.getGameProfile().getName();
                }
            } else {
                try {
                    uuid = java.util.UUID.fromString(rawUuid);
                } catch (IllegalArgumentException e) {
                    throw new Invalid("bad attach uuid");
                }
            }
            if (uuid != null)
                attach = new dev.watdafawx.foxstweaks.hologram.HologramAttach(uuid, name, follow, hideFp, scaleWithPlayer);
        }

        HologramSpec spec = new HologramSpec(base.id(), base.pos(), off[0], off[1], off[2], rot[0], rot[1], rot[2],
                scale, base.enabled(), renderDistance, base.owner(), base.ownerName(), radius, seconds, style, groups,
                layers, timeline, attach, base.path());
        String audioCaps = HologramAudioMod.checkCaps(spec, ServerConfig.hologramAudioMaxPerValue(),
                ServerConfig.hologramAudioMaxPerHologram());
        if (audioCaps != null)
            throw new Invalid(audioCaps);
        return spec;
    }

    /**
     * Strict: an unknown enum, a track whose target is not in this spec, an effect index past the target's effect
     * list, two tracks animating the same thing, or more tracks/keys than the server allows are all refused, so
     * the browser hears about it instead of the save quietly dropping work.
     */
    static HologramTimeline readTimeline(JsonObject t, List<HologramGroup> groups, List<HologramLayer> layers)
            throws Invalid {
        float length = num(t, "length", 4.0F, HologramTimeline.MIN_LENGTH, HologramTimeline.MAX_LENGTH);
        HologramTimeline.Mode mode = enumOf(HologramTimeline.Mode.class, t, "mode", HologramTimeline.Mode.LOOP);
        HologramEffect.Trigger start = enumOf(HologramEffect.Trigger.class, t, "start", HologramEffect.Trigger.ALWAYS);
        JsonArray tracksJson = arr(t, "tracks", HologramTimeline.HARD_MAX_TRACKS);
        List<HologramTimeline.Track> tracks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int keyTotal = 0;
        for (JsonElement el : tracksJson) {
            JsonObject j = asObj(el, "track");
            HologramTimeline.TargetKind target = enumOf(HologramTimeline.TargetKind.class, j, "target", null);
            HologramTimeline.Prop prop = enumOf(HologramTimeline.Prop.class, j, "prop", null);
            if (target == null || prop == null)
                throw new Invalid("track needs a target and a prop");
            String id = target == HologramTimeline.TargetKind.ROOT ? "" : str(j, "id", 24);
            List<HologramEffect> fx = null;
            if (target == HologramTimeline.TargetKind.GROUP) {
                HologramGroup g = groups.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
                if (g == null)
                    throw new Invalid("track refers to missing group '" + id + "'");
                fx = g.effects();
            } else if (target == HologramTimeline.TargetKind.LAYER) {
                HologramLayer l = layers.stream().filter(x -> x.uid().equals(id)).findFirst().orElse(null);
                if (l == null)
                    throw new Invalid("track refers to missing layer '" + id + "'");
                fx = l.effects();
            }
            int effect = 0;
            if (prop == HologramTimeline.Prop.EFFECT) {
                if (fx == null)
                    throw new Invalid("the root has no effects to animate");
                effect = Math.round(num(j, "effect", 0, 0, HologramSpec.MAX_EFFECTS - 1));
                if (effect >= fx.size())
                    throw new Invalid("track animates effect #" + effect + " but '" + id + "' has " + fx.size());
            }
            if (!seen.add(target + ":" + id + ":" + prop + ":" + effect))
                throw new Invalid("two tracks animate " + target.name().toLowerCase(java.util.Locale.ROOT)
                        + (id.isEmpty() ? "" : " '" + id + "'") + " " + prop);
            JsonArray keysJson = arr(j, "keys", HologramTimeline.HARD_MAX_KEYS);
            keyTotal += keysJson.size();
            if (keyTotal > HologramTimeline.HARD_MAX_KEYS)
                throw new Invalid("too many keyframes");
            List<HologramTimeline.Key> keys = new ArrayList<>(keysJson.size());
            for (JsonElement kel : keysJson) {
                JsonObject k = asObj(kel, "key");
                float time = num(k, "t", 0, 0, length);
                float value = num(k, "v", 0, prop.min(), prop.max());
                HologramTimeline.Ease ease = enumOf(HologramTimeline.Ease.class, k, "ease", HologramTimeline.Ease.LINEAR);
                if (ease == HologramTimeline.Ease.CUBIC) {
                    if (!k.has("bezier") || !k.get("bezier").isJsonArray() || k.getAsJsonArray("bezier").size() != 4)
                        throw new Invalid("a cubic key needs bezier [x1, y1, x2, y2]");
                    JsonArray b = k.getAsJsonArray("bezier");
                    keys.add(HologramTimeline.Key.cubic(time, value, clampNum(b.get(0), "bezier", 0, 1),
                            clampNum(b.get(1), "bezier", -2, 3), clampNum(b.get(2), "bezier", 0, 1),
                            clampNum(b.get(3), "bezier", -2, 3)));
                } else {
                    keys.add(HologramTimeline.Key.of(time, value, ease));
                }
            }
            tracks.add(new HologramTimeline.Track(target, id, prop, effect, keys));
        }
        List<HologramTimeline.AudioLink> audio = new ArrayList<>();
        if (t.has("audio") && !t.get("audio").isJsonNull()) {
            for (JsonElement el : arr(t, "audio", HologramTimeline.HARD_MAX_AUDIO)) {
                JsonObject j = asObj(el, "audio modulation");
                HologramTimeline.TargetKind target = enumOf(HologramTimeline.TargetKind.class, j, "target", null);
                if (target == null)
                    throw new Invalid("audio modulation needs a target");
                String id = target == HologramTimeline.TargetKind.ROOT ? "" : str(j, "id", 24);
                if (target == HologramTimeline.TargetKind.GROUP && groups.stream().noneMatch(x -> x.id().equals(id)))
                    throw new Invalid("audio modulation refers to missing group '" + id + "'");
                if (target == HologramTimeline.TargetKind.LAYER && layers.stream().noneMatch(x -> x.uid().equals(id)))
                    throw new Invalid("audio modulation refers to missing layer '" + id + "'");
                HologramAudioMod m = readAudioMod(j);
                if (!m.param().isPropParam())
                    throw new Invalid(m.param().name() + " is an effect value, not a " + target.name().toLowerCase(java.util.Locale.ROOT) + " value");
                if (m.param() == HologramAudioMod.Param.RATE && target != HologramTimeline.TargetKind.ROOT)
                    throw new Invalid("only the root has a playback rate");
                audio.add(new HologramTimeline.AudioLink(target, id, m));
            }
        }
        HologramTimeline tl = new HologramTimeline(length, mode, start, tracks, audio);
        String caps = tl.checkCaps(ServerConfig.hologramTimelineMaxTracks(), ServerConfig.hologramTimelineMaxKeys());
        if (caps != null)
            throw new Invalid(caps);
        return tl;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, JsonObject o, String key, E def) throws Invalid {
        if (!o.has(key) || o.get(key).isJsonNull())
            return def;
        String raw = str(o, key, 24);
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new Invalid("unknown " + key + " '" + raw + "'");
        }
    }

    /** Strict like the rest: unknown enums are refused, numbers are clamped by the record. */
    private static HologramAudioMod readAudioMod(JsonObject j) throws Invalid {
        HologramAudioMod.Param param = enumOf(HologramAudioMod.Param.class, j, "param", null);
        HologramAudioMod.Signal signal = enumOf(HologramAudioMod.Signal.class, j, "signal", HologramAudioMod.Signal.LEVEL);
        HologramAudioMod.Source source = enumOf(HologramAudioMod.Source.class, j, "source", HologramAudioMod.Source.AUTO);
        if (param == null)
            throw new Invalid("audio modulation needs a param");
        float lim = param.maxAmount();
        boolean hasMin = j.has("min") && !j.get("min").isJsonNull();
        boolean hasMax = j.has("max") && !j.get("max").isJsonNull();
        return new HologramAudioMod(param, source, signal, num(j, "amount", 0, -lim, lim),
                num(j, "sensitivity", 1, 0.1F, 8), num(j, "attack", 0.03F, 0, 2), num(j, "release", 0.2F, 0, 4),
                num(j, "threshold", 0, 0, 1), hasMin ? num(j, "min", 0, -1000, 1000) : Float.NaN,
                hasMax ? num(j, "max", 0, -1000, 1000) : Float.NaN);
    }

    private static List<HologramEffect> readEffects(JsonObject parent) throws Invalid {
        JsonArray a = arr(parent, "effects", HologramSpec.MAX_EFFECTS);
        List<HologramEffect> out = new ArrayList<>();
        for (JsonElement el : a) {
            JsonObject e = asObj(el, "effect");
            HologramEffect.Type type;
            HologramEffect.Trigger trigger;
            try {
                type = HologramEffect.Type.valueOf(str(e, "type", 24));
            } catch (IllegalArgumentException ex) {
                throw new Invalid("unknown effect type");
            }
            try {
                trigger = e.has("trigger") ? HologramEffect.Trigger.valueOf(str(e, "trigger", 24))
                        : HologramEffect.Trigger.ALWAYS;
            } catch (IllegalArgumentException ex) {
                throw new Invalid("unknown trigger");
            }
            List<HologramAudioMod> audio = new ArrayList<>();
            if (e.has("audio") && !e.get("audio").isJsonNull()) {
                for (JsonElement ael : arr(e, "audio", HologramAudioMod.HARD_MAX_PER_VALUE * 4)) {
                    HologramAudioMod m = readAudioMod(asObj(ael, "audio modulation"));
                    if (!m.param().isEffectParam())
                        throw new Invalid(m.param().name() + " is not an effect value");
                    audio.add(m);
                }
            }
            HologramEffect def = HologramEffect.of(type);
            float min = type.isFx() ? 0.0F : type.minAmount();
            float max = type.isFx() ? 2.0F : type.maxAmount();
            out.add(new HologramEffect(type,
                    num(e, "strength", def.strength(), min, max),
                    num(e, "speed", def.speed(), 0.05F, 20),
                    color(e, "color", def.color()),
                    e.has("axis") ? axis(e, "axis") : def.axis(),
                    num(e, "phase", 0, 0, 1),
                    trigger,
                    color(e, "color2", HologramEffect.DEFAULT_COLOR2)).withAudio(audio));
        }
        return out;
    }

    private static String cleanName(String raw) {
        StringBuilder sb = new StringBuilder();
        raw.codePoints().filter(cp -> !Character.isISOControl(cp) && cp != '§')
                .limit(MAX_NAME).forEach(sb::appendCodePoint);
        return sb.toString().trim();
    }

    // ---- typed accessors: wrong type or non-finite = Invalid, missing = default ----

    private static JsonObject obj(JsonObject o, String key) throws Invalid {
        if (!o.has(key))
            return new JsonObject();
        return asObj(o.get(key), key);
    }

    private static JsonObject asObj(JsonElement el, String what) throws Invalid {
        if (el == null || !el.isJsonObject())
            throw new Invalid(what + " must be an object");
        return el.getAsJsonObject();
    }

    private static JsonArray arr(JsonObject o, String key, int max) throws Invalid {
        if (!o.has(key))
            return new JsonArray();
        JsonElement el = o.get(key);
        if (!el.isJsonArray())
            throw new Invalid(key + " must be an array");
        if (el.getAsJsonArray().size() > max)
            throw new Invalid(key + " has more than " + max + " entries");
        return el.getAsJsonArray();
    }

    private static String str(JsonObject o, String key, int maxLen) throws Invalid {
        if (!o.has(key))
            throw new Invalid("missing " + key);
        return asStr(o.get(key), key, maxLen);
    }

    private static String asStr(JsonElement el, String what, int maxLen) throws Invalid {
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString())
            throw new Invalid(what + " must be a string");
        String s = el.getAsString();
        if (s.length() > maxLen)
            throw new Invalid(what + " is too long");
        return s;
    }

    private static boolean bool(JsonObject o, String key, boolean def) throws Invalid {
        if (!o.has(key))
            return def;
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean())
            throw new Invalid(key + " must be true or false");
        return el.getAsBoolean();
    }

    private static float num(JsonObject o, String key, float def, float min, float max) throws Invalid {
        if (!o.has(key))
            return def;
        return clampNum(o.get(key), key, min, max);
    }

    private static float clampNum(JsonElement el, String what, float min, float max) throws Invalid {
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber())
            throw new Invalid(what + " must be a number");
        double d = el.getAsDouble();
        if (!Double.isFinite(d))
            throw new Invalid(what + " must be finite");
        return (float) Math.max(min, Math.min(max, d));
    }

    private static float[] vec3(JsonObject o, String key, float min, float max) throws Invalid {
        if (!o.has(key))
            return new float[3];
        JsonElement el = o.get(key);
        if (!el.isJsonArray() || el.getAsJsonArray().size() != 3)
            throw new Invalid(key + " must be [x, y, z]");
        JsonArray a = el.getAsJsonArray();
        return new float[] { clampNum(a.get(0), key, min, max), clampNum(a.get(1), key, min, max),
                clampNum(a.get(2), key, min, max) };
    }

    private static int axis(JsonObject o, String key) throws Invalid {
        return Math.round(num(o, key, HologramSpec.AXIS_Y, 0, 2));
    }

    private static int color(JsonObject o, String key, int def) throws Invalid {
        if (!o.has(key))
            return def;
        return Math.round(clampNum(o.get(key), key, 0, 0xFFFFFF)) & 0xFFFFFF;
    }
}
