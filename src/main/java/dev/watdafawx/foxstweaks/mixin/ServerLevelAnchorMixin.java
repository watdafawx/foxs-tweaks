package dev.watdafawx.foxstweaks.mixin;

import dev.watdafawx.foxstweaks.anchor.SpawnAnchors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/**
 * Lets vanilla and modded spawners ({@code BaseSpawner#isNearPlayer}, Apotheosis spawners, etc.)
 * activate when a virtual spawn anchor is within range.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelAnchorMixin {
    public boolean hasNearbyAlivePlayer(double x, double y, double z, double distance) {
        ServerLevel self = (ServerLevel) (Object) this;
        List<? extends Player> players = self.players();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            if (EntitySelector.NO_SPECTATORS.test(p) && EntitySelector.LIVING_ENTITY_STILL_ALIVE.test(p)) {
                double d = p.distanceToSqr(x, y, z);
                if (distance < 0.0 || d < distance * distance) {
                    return true;
                }
            }
        }
        return SpawnAnchors.hasAnchorWithin(self, x, y, z, distance);
    }
}
