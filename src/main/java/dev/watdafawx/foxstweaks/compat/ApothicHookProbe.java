package dev.watdafawx.foxstweaks.compat;

import dev.shadowsoffire.apotheosis.Apoth;
import dev.shadowsoffire.apotheosis.tiers.WorldTier;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks that the Apotheosis internals the anchor tier support hooks still exist.
 *
 * <p>Those hooks are {@code require = 0}, so an Apotheosis update that renames or moves a target makes them
 * silently stop applying: the game keeps running and anchors quietly stop carrying a tier. This probe turns
 * that silence into a log line and a message to operators on join, and names the part that moved.
 *
 * <p>It verifies declarations, not bytecode: a method that kept its name and signature but lost the
 * {@code getNearestPlayer} call inside it would still look fine here. The dev check
 * ({@code runAnchorTierCheck}) is what proves the behaviour end to end.
 */
public final class ApothicHookProbe {

    private record Target(String className, String methodName, Class<?> parameter) {
    }

    private static final List<Target> TARGETS = List.of(
            new Target("dev.shadowsoffire.apotheosis.mobs.ApothMobEvents", "finalizeMobSpawns", FinalizeSpawnEvent.class),
            new Target("dev.shadowsoffire.apotheosis.mobs.ApothMobEvents", "delayedEliteMobs", EntityJoinLevelEvent.class),
            new Target("dev.shadowsoffire.apotheosis.mobs.util.SurfaceType", "skyOrSameVerticalSlice", null));

    /** Everything that no longer fits this Apotheosis build; empty when the hooks should apply. */
    public static List<String> problems() {
        List<String> out = new ArrayList<>();
        for (Target target : TARGETS) {
            Class<?> type;
            try {
                type = Class.forName(target.className());
            }
            catch (ClassNotFoundException | LinkageError ex) {
                out.add(target.className() + " is gone");
                continue;
            }

            if (!hasMethod(type, target.methodName(), target.parameter()))
                out.add(target.className() + "#" + target.methodName() + " changed or moved");
        }

        try {
            // Resolved at compile time: a renamed field throws NoSuchFieldError right here.
            if (Apoth.Attachments.WORLD_TIER == null)
                out.add("apotheosis:world_tier attachment is missing");
        }
        catch (LinkageError | RuntimeException ex) {
            out.add("the world_tier attachment moved (" + ex.getClass().getSimpleName() + ")");
        }

        try {
            if (WorldTier.values().length == 0)
                out.add("the WorldTier enum is empty");
        }
        catch (LinkageError | RuntimeException ex) {
            out.add("WorldTier changed (" + ex.getClass().getSimpleName() + ")");
        }

        return out;
    }

    private static boolean hasMethod(Class<?> type, String name, Class<?> parameter) {
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name))
                continue;
            if (parameter == null || (method.getParameterCount() == 1 && method.getParameterTypes()[0] == parameter))
                return true;
        }
        return false;
    }

    private ApothicHookProbe() {
    }
}
