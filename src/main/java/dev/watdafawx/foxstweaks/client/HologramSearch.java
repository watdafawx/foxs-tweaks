package dev.watdafawx.foxstweaks.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JEI / AE2 / EMI-style query parser and matcher for the hologram picker:
 *
 * <ul>
 * <li>{@code @mod} - matches mod id or mod display name (e.g. {@code @adastra}, {@code @applied})
 * <li>{@code #text} - matches item tooltip text (cached lazily on first {@code #} search)
 * <li>{@code $tag} - matches block or entity tag id (e.g. {@code $logs}, {@code $ores})
 * <li>{@code &id} - matches registry id only
 * <li>{@code -term} - excludes matches
 * <li>{@code |} - OR between query branches; spaces are AND; {@code "..."} groups spaces
 * </ul>
 */
final class HologramSearch {
    private static Map<String, String> MOD_NAMES;
    private static final Map<Block, String> BLOCK_TOOLTIPS = new HashMap<>();
    private static final Map<Block, String> BLOCK_TAGS = new HashMap<>();
    private static final Map<EntityType<?>, String> ENTITY_TAGS = new HashMap<>();

    private enum Prefix {
        ANY, MOD, TOOLTIP, TAG, ID
    }

    private record Token(Prefix prefix, String value, boolean negated) {
    }

    private final List<List<Token>> branches;

    private HologramSearch(List<List<Token>> branches) {
        this.branches = branches;
    }

    static HologramSearch parse(String raw) {
        if (raw == null || raw.isBlank())
            return new HologramSearch(List.of());

        List<List<Token>> branches = new ArrayList<>();
        for (String part : raw.split("\\|")) {
            List<Token> tokens = parseTokens(part);
            if (!tokens.isEmpty())
                branches.add(tokens);
        }
        return new HologramSearch(branches);
    }

    boolean isEmpty() {
        return this.branches.isEmpty();
    }

    private static List<Token> parseTokens(String input) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int len = input.length();
        while (i < len) {
            while (i < len && Character.isWhitespace(input.charAt(i)))
                i++;
            if (i >= len)
                break;

            boolean negated = false;
            if (input.charAt(i) == '-' && i + 1 < len && !Character.isWhitespace(input.charAt(i + 1))) {
                negated = true;
                i++;
            }

            Prefix prefix = Prefix.ANY;
            if (i < len) {
                char c = input.charAt(i);
                if (c == '@') {
                    prefix = Prefix.MOD;
                    i++;
                } else if (c == '#') {
                    prefix = Prefix.TOOLTIP;
                    i++;
                } else if (c == '$') {
                    prefix = Prefix.TAG;
                    i++;
                } else if (c == '&') {
                    prefix = Prefix.ID;
                    i++;
                }
            }

            StringBuilder sb = new StringBuilder();
            if (i < len && input.charAt(i) == '"') {
                i++;
                while (i < len && input.charAt(i) != '"')
                    sb.append(input.charAt(i++));
                if (i < len && input.charAt(i) == '"')
                    i++;
            } else {
                while (i < len && !Character.isWhitespace(input.charAt(i)))
                    sb.append(input.charAt(i++));
            }

            String val = sb.toString().toLowerCase(Locale.ROOT);
            if (!val.isEmpty())
                out.add(new Token(prefix, val, negated));
        }
        return out;
    }

    boolean matchesBlock(Block block) {
        if (this.branches.isEmpty())
            return true;
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        String id = key.toString().toLowerCase(Locale.ROOT);
        String ns = key.getNamespace().toLowerCase(Locale.ROOT);
        String modName = modName(ns);
        String name = block.getName().getString().toLowerCase(Locale.ROOT);

        for (List<Token> branch : this.branches) {
            boolean allOk = true;
            for (Token tok : branch) {
                boolean hit = switch (tok.prefix) {
                    case MOD -> ns.contains(tok.value) || modName.contains(tok.value);
                    case TOOLTIP -> blockTooltip(block).contains(tok.value);
                    case TAG -> blockTags(block).contains(tok.value);
                    case ID -> id.contains(tok.value);
                    case ANY -> name.contains(tok.value) || id.contains(tok.value) || modName.contains(tok.value);
                };
                if (tok.negated ? hit : !hit) {
                    allOk = false;
                    break;
                }
            }
            if (allOk)
                return true;
        }
        return false;
    }

    boolean matchesEntity(EntityType<?> type, String id, String displayName) {
        if (this.branches.isEmpty())
            return true;
        String idLower = id.toLowerCase(Locale.ROOT);
        int colon = idLower.indexOf(':');
        String ns = colon >= 0 ? idLower.substring(0, colon) : "minecraft";
        String modName = modName(ns);
        String nameLower = displayName.toLowerCase(Locale.ROOT);

        for (List<Token> branch : this.branches) {
            boolean allOk = true;
            for (Token tok : branch) {
                boolean hit = switch (tok.prefix) {
                    case MOD -> ns.contains(tok.value) || modName.contains(tok.value);
                    case TOOLTIP -> nameLower.contains(tok.value) || idLower.contains(tok.value);
                    case TAG -> entityTags(type).contains(tok.value);
                    case ID -> idLower.contains(tok.value);
                    case ANY -> nameLower.contains(tok.value) || idLower.contains(tok.value) || modName.contains(tok.value);
                };
                if (tok.negated ? hit : !hit) {
                    allOk = false;
                    break;
                }
            }
            if (allOk)
                return true;
        }
        return false;
    }

    boolean matchesParticle(String id) {
        if (this.branches.isEmpty())
            return true;
        String idLower = id.toLowerCase(Locale.ROOT);
        int colon = idLower.indexOf(':');
        String ns = colon >= 0 ? idLower.substring(0, colon) : "minecraft";
        String modName = modName(ns);

        for (List<Token> branch : this.branches) {
            boolean allOk = true;
            for (Token tok : branch) {
                boolean hit = switch (tok.prefix) {
                    case MOD -> ns.contains(tok.value) || modName.contains(tok.value);
                    case TAG -> false;
                    case TOOLTIP, ID, ANY -> idLower.contains(tok.value) || modName.contains(tok.value);
                };
                if (tok.negated ? hit : !hit) {
                    allOk = false;
                    break;
                }
            }
            if (allOk)
                return true;
        }
        return false;
    }

    private static String modName(String modId) {
        if (MOD_NAMES == null) {
            Map<String, String> map = new HashMap<>();
            ModList.get().forEachModContainer((id, container) ->
                    map.put(id.toLowerCase(Locale.ROOT), container.getModInfo().getDisplayName().toLowerCase(Locale.ROOT)));
            MOD_NAMES = map;
        }
        return MOD_NAMES.getOrDefault(modId, modId);
    }

    private static String blockTooltip(Block block) {
        return BLOCK_TOOLTIPS.computeIfAbsent(block, b -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                ItemStack stack = new ItemStack(b.asItem());
                if (stack.isEmpty())
                    return "";
                List<Component> lines = stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player,
                        TooltipFlag.Default.NORMAL);
                StringBuilder sb = new StringBuilder();
                for (Component line : lines)
                    sb.append(line.getString().toLowerCase(Locale.ROOT)).append('\n');
                return sb.toString();
            } catch (Throwable t) {
                return "";
            }
        });
    }

    private static String blockTags(Block block) {
        return BLOCK_TAGS.computeIfAbsent(block, b -> {
            StringBuilder sb = new StringBuilder();
            BuiltInRegistries.BLOCK.wrapAsHolder(b).tags()
                    .forEach(tag -> sb.append(tag.location().toString().toLowerCase(Locale.ROOT)).append(' '));
            return sb.toString();
        });
    }

    private static String entityTags(EntityType<?> type) {
        return ENTITY_TAGS.computeIfAbsent(type, t -> {
            StringBuilder sb = new StringBuilder();
            BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(t).tags()
                    .forEach(tag -> sb.append(tag.location().toString().toLowerCase(Locale.ROOT)).append(' '));
            return sb.toString();
        });
    }
}
