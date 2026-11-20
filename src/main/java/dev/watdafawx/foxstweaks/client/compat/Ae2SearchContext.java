package dev.watdafawx.foxstweaks.client.compat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.me.common.Repo;
import appeng.menu.me.common.GridInventoryEntry;
import dev.watdafawx.foxstweaks.client.search.SearchContext;
import net.minecraft.client.gui.screens.Screen;

/**
 * Feeds {@link SearchContext} from an AE2 terminal's client repo: every network entry with stock goes to the
 * "stored" list, every craftable entry with none to the "craftable" list. The only class importing AE2 for
 * search; reached only with AE2 loaded. The repo is a protected field, read reflectively once.
 */
public final class Ae2SearchContext {
    private static Field repoField;
    private static boolean broken;
    /** Repo and size we last read, plus when: the network pushes updates constantly, so rebuild at most once a second. */
    private static Repo lastRepo;
    private static int lastSize = -1;
    private static long lastRead;

    private Ae2SearchContext() {
    }

    public static void refresh(Screen screen) {
        if (!(screen instanceof MEStorageScreen<?> terminal) || broken) {
            if (lastRepo != null) {
                lastRepo = null;
                lastSize = -1;
                SearchContext.set(List.of(), List.of());
            }
            return;
        }
        Repo repo = repoOf(terminal);
        if (repo == null) {
            return;
        }
        var entries = repo.getAllEntries();
        long now = System.currentTimeMillis();
        if (repo == lastRepo && entries.size() == lastSize || repo == lastRepo && now - lastRead < 1000) {
            return;
        }
        lastRepo = repo;
        lastSize = entries.size();
        lastRead = now;
        List<String> stored = new ArrayList<>();
        List<String> craftable = new ArrayList<>();
        for (GridInventoryEntry entry : entries) {
            if (entry.getWhat() == null) {
                continue;
            }
            if (entry.getStoredAmount() > 0) {
                stored.add(entry.getWhat().getDisplayName().getString());
            } else if (entry.isCraftable()) {
                craftable.add(entry.getWhat().getDisplayName().getString());
            }
        }
        SearchContext.set(stored, craftable);
    }

    private static Repo repoOf(MEStorageScreen<?> terminal) {
        try {
            if (repoField == null) {
                repoField = MEStorageScreen.class.getDeclaredField("repo");
                repoField.setAccessible(true);
            }
            return (Repo) repoField.get(terminal);
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            return null;
        }
    }
}
