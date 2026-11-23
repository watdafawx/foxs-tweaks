package dev.watdafawx.foxstweaks.client.dev;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dev-only check of the settings screen being organised into sections rather than one flat list.
 *
 * <p>NeoForge's config screen builds its rows as list entries, not as screen buttons, so the assertions read
 * the config specs directly: which top-level sections exist, that every value lives inside one, and that each
 * section and key resolves to a real label (the repo's "every key needs a {@code foxstweaks.configuration.<key>}
 * label" rule) instead of showing the raw key. It also opens the real screen and screenshots it, because a spec
 * that looks right can still break the screen at runtime.
 *
 * <p>Run with {@code build.bat runConfigCheck} (game dir {@code run-config/}, no extra mods). Report:
 * {@code run-config/foxstweaks-config-check.txt}; screenshots {@code config-sections.png}.
 */
public final class ConfigScreenCheck {
    private static final Logger LOG = LogUtils.getLogger();

    private static final List<String> COMMON_SECTIONS = List.of(
            "relics", "world", "apotheosis", "storage", "performance", "integrations", "misc");
    private static final List<String> SERVER_SECTIONS = List.of("portal", "performance", "spawnAnchors");

    private record Step(int waitTicks, Runnable run) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static int failures;
    private static boolean screenRequested;
    private static int tick;
    private static int nextAt;
    private static int index;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(ConfigScreenCheck::onTick);
        scenarios();
    }

    private static void scenarios() {
        add(20, () -> {
            ModList.get().getModContainerById(FoxsTweaks.MODID)
                    .ifPresent(container -> Minecraft.getInstance().setScreen(new ConfigurationScreen(container, null)));
            screenRequested = true;
        });

        add(45, () -> {
            var mc = Minecraft.getInstance();
            Screenshot.grab(mc.gameDirectory, "config-sections.png", mc.getMainRenderTarget(), msg -> {
            });
            check("config screen opens without crashing", mc.screen instanceof ConfigurationScreen,
                    String.valueOf(mc.screen));
        });

        add(4, () -> {
            // Which top-level sections the spec actually has, and where each value sits.
            Map<String, Set<String>> sections = sections(Config.SPEC.getValues().valueMap());
            check("common config has the expected sections", sections.keySet().equals(new LinkedHashSet<>(COMMON_SECTIONS)),
                    "got " + sections.keySet());

            Map<String, Set<String>> serverSections = sections(ServerConfig.SPEC.getValues().valueMap());
            check("server config has the expected sections",
                    serverSections.keySet().equals(new LinkedHashSet<>(SERVER_SECTIONS)),
                    "got " + serverSections.keySet());

            List<String> missing = new ArrayList<>();
            for (String section : sections.keySet()) {
                if (!translated("foxstweaks.configuration." + section))
                    missing.add("section " + section);
                for (String key : sections.get(section)) {
                    if (!translated("foxstweaks.configuration." + key))
                        missing.add(section + "." + key);
                }
            }
            for (String section : serverSections.keySet()) {
                if (!translated("foxstweaks.configuration." + section))
                    missing.add("server section " + section);
                for (String key : serverSections.get(section)) {
                    if (!translated("foxstweaks.configuration." + key))
                        missing.add("server " + section + "." + key);
                }
            }
            check("every section and key has a real label", missing.isEmpty(), "untranslated: " + missing);
            log("common sections: " + sections);
            log("server sections: " + serverSections);
        });

        add(6, () -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks-config-check.txt"), REPORT);
            }
            catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    /** Top-level section name -> the keys directly inside it. */
    private static Map<String, Set<String>> sections(Map<String, Object> spec) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : spec.entrySet()) {
            if (entry.getValue() instanceof UnmodifiableConfig sub)
                out.put(entry.getKey(), sub.valueMap().keySet());
            else
                out.computeIfAbsent("(ungrouped)", k -> new LinkedHashSet<>()).add(entry.getKey());
        }
        return out;
    }

    /** False when the label is missing, i.e. the screen would show the raw key. */
    private static boolean translated(String key) {
        return !Component.translatable(key).getString().equals(key);
    }

    private static void add(int waitTicks, Runnable run) {
        STEPS.add(new Step(waitTicks, run));
    }

    private static void log(String line) {
        LOG.info("ConfigScreenCheck {}", line);
        REPORT.add(line);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok)
            failures++;
        log((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    private static void onTick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);

        if (!screenRequested && mc.getOverlay() == null
                && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
            tick++;
            if (tick < nextAt)
                return;
        }

        tick++;
        if (nextAt == 0)
            nextAt = tick + STEPS.get(0).waitTicks;
        if (tick < nextAt || index >= STEPS.size())
            return;

        var step = STEPS.get(index++);
        try {
            step.run.run();
        }
        catch (Throwable t) {
            LOG.error("ConfigScreenCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }
        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private ConfigScreenCheck() {
    }
}
