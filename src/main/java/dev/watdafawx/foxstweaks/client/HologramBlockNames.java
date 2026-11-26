package dev.watdafawx.foxstweaks.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Display names for the block browsers (in game and web) where two blocks of one mod would read the same.
 *
 * <p>The standing and wall variants of a sign, banner, torch or head share one name because their wall block
 * overrides its description. Both are worth having as holograms (a wall sign is the flat plate, a standing one
 * has the post), so neither is hidden: the wall one reads "Acacia Sign (wall)". Any other clash inside one mod
 * gets the registry path, e.g. "Chest (trapped_chest)". Clashes across mods are left alone: the tile already
 * shows which mod it is from.
 */
public final class HologramBlockNames {
    private HologramBlockNames() {
    }

    /**
     * @param namesById registry id ({@code minecraft:acacia_wall_sign}) to display name, for every block
     * @return the display names that changed; ids that were already unique are not in it
     */
    public static Map<String, String> disambiguate(Map<String, String> namesById) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        namesById.forEach((id, name) ->
                groups.computeIfAbsent(namespaceOf(id) + "|" + name.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(id));

        Map<String, String> changed = new HashMap<>();
        for (List<String> group : groups.values()) {
            if (group.size() < 2)
                continue;

            long walls = group.stream().filter(HologramBlockNames::isWallVariant).count();
            long standing = group.size() - walls;
            for (String id : group) {
                String tag;
                if (isWallVariant(id))
                    tag = walls == 1 ? "wall" : pathOf(id);
                else if (standing == 1)
                    continue;
                else
                    tag = pathOf(id);
                changed.put(id, namesById.get(id) + " (" + tag + ")");
            }
        }
        return changed;
    }

    private static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    private static String pathOf(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    private static boolean isWallVariant(String id) {
        String path = pathOf(id);
        return path.startsWith("wall_") || path.contains("_wall_");
    }
}
