package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * Ray-versus-hologram test, shared by the server (which decides what a click did) and the client
 * (which draws the outline).
 *
 * <p>Doing this by hand is the whole reason holograms need no marker entity: nothing in the world
 * exists to click, so the pick is a handful of AABB clips against a list we already have.
 */
public final class HologramPick {

    @Nullable
    public static HologramSpec pick(Vec3 eye, Vec3 look, double reach, List<HologramSpec> specs) {
        Vec3 end = eye.add(look.scale(reach));
        HologramSpec best = null;
        double bestDist = reach * reach;

        for (int i = 0; i < specs.size(); i++) {
            HologramSpec spec = specs.get(i);
            if (!spec.enabled())
                continue;
            Optional<Vec3> hit = spec.box().clip(eye, end);
            if (hit.isEmpty())
                continue;
            double dist = eye.distanceToSqr(hit.get());
            if (dist < bestDist) {
                bestDist = dist;
                best = spec;
            }
        }
        return best;
    }

    private HologramPick() {
    }
}
