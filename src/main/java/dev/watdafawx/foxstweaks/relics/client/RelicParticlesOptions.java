package dev.watdafawx.foxstweaks.relics.client;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.relics.RelicParticles;
import dev.watdafawx.foxstweaks.relics.RelicParticlesNetwork;
import it.hurts.sskirillss.relics.api.relics.data.RelicData;
import it.hurts.sskirillss.relics.client.screen.description.base.DescriptionScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Appends a native "Show relic particles" checkbox to Relics' {@code RelicOptionsContainerWidget}
 * (right below "Show visual effects for flawless relics"). Only loaded when Relics' options widget
 * itself classloads, so packs without Relics never touch this class.
 */
public final class RelicParticlesOptions {
    private static final Logger LOG = LogUtils.getLogger();

    private RelicParticlesOptions() {
    }

    public static List<Object> append(List<Object> existing) {
        try {
            Class<?> entryClass = Class.forName(
                    "it.hurts.sskirillss.relics.client.screen.description.relic.widgets.RelicOptionsContainerWidget$OptionEntry");
            Constructor<?> ctor = entryClass.getDeclaredConstructor(
                    Component.class, Function.class, BiConsumer.class, BiConsumer.class);
            ctor.setAccessible(true);

            Component label = Component.translatable("foxstweaks.relic.options.show_particles");
            Function<RelicData, Boolean> getEnabled = data -> !RelicParticles.isHidden(data.getStack());
            BiConsumer<RelicData, Boolean> setEnabled = (data, show) -> {
                if (Config.enabled(Config.RELIC_PARTICLE_TOGGLE)) {
                    RelicParticles.setHidden(data.getStack(), !Boolean.TRUE.equals(show));
                }
            };
            BiConsumer<DescriptionScreen, Boolean> sync = (screen, show) -> {
                if (Config.enabled(Config.RELIC_PARTICLE_TOGGLE)) {
                    RelicParticlesNetwork.send(screen.container, screen.slot, !Boolean.TRUE.equals(show));
                }
            };

            Object entry = ctor.newInstance(label, getEnabled, setEnabled, sync);
            List<Object> out = new ArrayList<>(existing.size() + 1);
            out.addAll(existing);
            out.add(entry);
            return List.copyOf(out);
        } catch (Throwable t) {
            LOG.warn("Could not add 'Show relic particles' option to Relics options widget", t);
            return existing;
        }
    }
}
