package dev.watdafawx.foxstweaks.client.dev;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Dev-only check for the search box assist ({@code EditBoxSearchAssistMixin}). Run by
 * {@code build.bat runSearchAssistCheck} (game dir {@code run-search/}, no extra mods). Needs no world: it builds
 * real {@code EditBox}es on the title screen, types into them and reads what the mixin did. Writes
 * {@code run-search/foxstweaks-search-check.txt} and quits. Never installed in a shipped jar.
 */
public final class SearchAssistCheck {
    private static final List<String> REPORT = new ArrayList<>();
    private static int failures;
    private static boolean done;
    private static int ticks;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(SearchAssistCheck::onTick);
    }

    private static void onTick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        if (done || mc.getOverlay() != null || !(mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)
                || ++ticks < 40) {
            return;
        }
        done = true;
        try {
            run(mc);
        } catch (Throwable t) {
            failures++;
            REPORT.add("FAIL exception: " + t);
            t.printStackTrace();
        }
        REPORT.add(failures == 0 ? "ALL PASS" : failures + " FAILED");
        try {
            Files.write(Path.of(mc.gameDirectory.getPath(), "foxstweaks-search-check.txt"), REPORT);
        } catch (Exception ignored) {
        }
        mc.stop();
    }

    private static void run(Minecraft mc) throws Exception {
        List<String> heard = new ArrayList<>();
        EditBox search = box(mc, "Search items", heard);

        search.setValue("ingto");
        check("misspelling reaches listener corrected", "ingot".equals(last(heard)), "listener got " + last(heard));
        check("box keeps what was typed", "ingto".equals(search.getValue()), search.getValue());
        check("did-you-mean set", "ingot".equals(field(search, "foxstweaks$didYouMean")), "" + field(search, "foxstweaks$didYouMean"));

        search.setValue("iron ing");
        check("completion suggested", "ot".equals(field(search, "suggestion")), "suggestion " + field(search, "suggestion"));
        check("real word needs no correction", field(search, "foxstweaks$didYouMean") == null, "" + field(search, "foxstweaks$didYouMean"));
        check("prefix not corrected for listener", "iron ing".equals(last(heard)), "listener got " + last(heard));
        search.setFocused(true);
        boolean tab = search.keyPressed(258, 0, 0);
        check("Tab takes completion", tab && "iron ingot".equals(search.getValue()), "handled=" + tab + " value=" + search.getValue());

        search.setValue("diamnod pickaxe");
        check("transposed word fixed", "diamond pickaxe".equals(field(search, "foxstweaks$didYouMean")), "" + field(search, "foxstweaks$didYouMean"));
        tab = search.keyPressed(258, 0, 0);
        check("Tab takes correction", tab && "diamond pickaxe".equals(search.getValue()), "handled=" + tab + " value=" + search.getValue());

        search.setValue("@create ingto");
        check("search syntax untouched", "@create ingto".equals(last(heard)), "listener got " + last(heard));
        search.setValue("dia");
        check("word being typed untouched", "dia".equals(last(heard)), "listener got " + last(heard));

        search.setValue("iron @foxst");
        check("@mod completes to the mod id", "weaks".equals(field(search, "suggestion")), "suggestion " + field(search, "suggestion"));
        tab = search.keyPressed(258, 0, 0);
        check("Tab takes @mod completion", tab && "iron @foxstweaks".equals(search.getValue()), "value=" + search.getValue());
        search.setValue("@foxstweks");
        check("misspelled @mod corrected", "@foxstweaks".equals(last(heard)), "listener got " + last(heard));
        search.setValue("@foxstweaks");
        check("known @mod left alone", "@foxstweaks".equals(last(heard)) && field(search, "foxstweaks$didYouMean") == null, "listener got " + last(heard));
        search.setValue("$loot");
        check("$ tooltip search untouched", "$loot".equals(last(heard)) && field(search, "suggestion") == null, "listener got " + last(heard));
        search.setValue("");
        check("empty clears hints", field(search, "foxstweaks$didYouMean") == null && field(search, "suggestion") == null, "");

        heard.clear();
        EditBox name = box(mc, "World name", heard);
        name.setValue("ingto");
        check("non-search box untouched", "ingto".equals(last(heard)) && field(name, "foxstweaks$didYouMean") == null && field(name, "suggestion") == null,
                "listener got " + last(heard));

        heard.clear();
        EditBox filter = box(mc, "Filter", heard);
        filter.setValue("ingto");
        check("'filter' label counts as search", "ingot".equals(last(heard)), "listener got " + last(heard));
    }

    private static EditBox box(Minecraft mc, String label, List<String> heard) {
        EditBox b = new EditBox(mc.font, 0, 0, 200, 16, Component.literal(label));
        b.setResponder(heard::add);
        return b;
    }

    private static String last(List<String> l) {
        return l.isEmpty() ? null : l.get(l.size() - 1);
    }

    private static Object field(EditBox box, String name) throws Exception {
        Field f = EditBox.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(box);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) {
            failures++;
        }
        REPORT.add((ok ? "PASS " : "FAIL ") + name + (ok ? "" : " - " + detail));
    }

    private SearchAssistCheck() {
    }
}
