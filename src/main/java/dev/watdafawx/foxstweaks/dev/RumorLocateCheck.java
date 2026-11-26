package dev.watdafawx.foxstweaks.dev;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.watdafawx.foxstweaks.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Dev-only (system property {@code foxstweaks.rumorCheck}): calls Taverns &amp; Adventurers' private
 * {@code RumorService.locate} by reflection and checks {@code AdventurersGuildRumorLocateMixin} - a normal search
 * still answers, a 1 ms wait gives up and refuses the next search while the worker is busy, and searches work again
 * afterwards. Report: run-rumor/foxstweaks-rumor-check.txt. Touches no Taverns type at compile time.
 */
@EventBusSubscriber(modid = "foxstweaks")
public final class RumorLocateCheck {
    private RumorLocateCheck() {}

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (FMLEnvironment.production || !Boolean.getBoolean("foxstweaks.rumorCheck"))
            return;
        var out = new ArrayList<String>();
        try {
            run(event.getServer().getLevel(Level.OVERWORLD), out);
        } catch (Throwable t) {
            out.add("FAIL exception " + t);
            t.printStackTrace();
        }
        try {
            Files.write(Path.of("foxstweaks-rumor-check.txt"), out);
        } catch (Exception ignored) {
        }
        event.getServer().halt(false);
    }

    private static void run(ServerLevel level, List<String> out) throws Exception {
        Class<?> c = Class.forName("com.theeclipsedspire.adventurersguild.expedition.RumorService", true, ServerLevel.class.getClassLoader());
        Method locate = c.getDeclaredMethod("locate", ServerLevel.class, List.class, BlockPos.class);
        locate.setAccessible(true);
        var ids = List.of("minecraft:village_plains", "minecraft:village_desert", "minecraft:village_savanna", "minecraft:village_snowy", "minecraft:village_taiga");
        var origin = BlockPos.ZERO;

        Config.TAVERNS_LOCATE_WAIT_MS.set(0);
        long t0 = System.nanoTime();
        Object baseline = locate.invoke(null, level, ids, new BlockPos(5000, 64, 5000));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        out.add("INFO original path (wait 0) found " + baseline + " in " + ms + " ms");

        Config.TAVERNS_LOCATE_WAIT_MS.set(15000);
        t0 = System.nanoTime();
        Object normal = locate.invoke(null, level, ids, new BlockPos(-5000, 64, -5000));
        ms = (System.nanoTime() - t0) / 1_000_000;
        out.add((normal != null ? "PASS" : "FAIL") + " worker path found " + normal + " in " + ms + " ms");

        Config.TAVERNS_LOCATE_WAIT_MS.set(1);
        // A far, uncached origin so the search is not instant.
        var far = new BlockPos(900_000, 64, 900_000);
        t0 = System.nanoTime();
        Object slow = locate.invoke(null, level, ids, far);
        ms = (System.nanoTime() - t0) / 1_000_000;
        out.add((slow == null && ms < 1000 ? "PASS" : "FAIL") + " 1 ms wait gave up: result " + slow + ", tick waited " + ms + " ms");

        t0 = System.nanoTime();
        Object refused = locate.invoke(null, level, ids, far);
        ms = (System.nanoTime() - t0) / 1_000_000;
        out.add((refused == null && ms < 200 ? "PASS" : "INFO") + " follow-up while worker busy: result " + refused + " in " + ms + " ms");

        Config.TAVERNS_LOCATE_WAIT_MS.set(4000);
        Object again = null;
        for (int i = 0; i < 60 && again == null; i++) {
            long until = System.nanoTime() + 500_000_000L;
            level.getServer().managedBlock(() -> {
                level.getChunkSource().pollTask();
                return System.nanoTime() > until;
            });
            again = locate.invoke(null, level, ids, origin);
        }
        out.add((again != null ? "PASS" : "FAIL") + " searches work again after the stuck one ends: " + again);

        Config.TAVERNS_LOCATE_WAIT_MS.set(0);
        Object off = locate.invoke(null, level, ids, origin);
        out.add((off != null ? "PASS" : "FAIL") + " wait 0 = original path: " + off);
    }
}
