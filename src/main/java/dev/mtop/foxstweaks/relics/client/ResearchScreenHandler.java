package dev.mtop.foxstweaks.relics.client;

import dev.mtop.foxstweaks.FoxsTweaks;
import it.hurts.sskirillss.relics.api.relics.IRelicItem;
import it.hurts.sskirillss.relics.client.screen.description.research.AbilityResearchScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Attaches the auto-solve button once Relics has finished laying out its research screen.
 *
 * <p>{@code ScreenEvent.Init.Post} runs after {@code AbilityResearchScreen#init}, which is both
 * where the hint button is added and where the screen's {@code x}/{@code y} origin is worked out,
 * so the button can be positioned relative to it without duplicating any of that maths.
 */
public class ResearchScreenHandler {
    /**
     * Relics puts its hint plate at {@code x + 192, y + 188} and offsets the widget 10px below it.
     * Placing this one exactly one plate-body height lower makes the gold posts of the two plates
     * line up into a single continuous rail, so they read as one two-tier rack.
     */
    private static final int PLATE_X = 192;
    private static final int PLATE_Y = 222;

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbilityResearchScreen screen))
            return;

        // Relics itself returns early from init() in these cases, so there is no hint button to sit
        // under and no constellation to solve.
        if (screen.stack == null || !(screen.stack.getItem() instanceof IRelicItem relic))
            return;

        var abilityData = relic.getRelicData(screen.getMinecraft().player, screen.stack)
                .getAbilitiesData().getAbilityData(screen.ability);

        if (abilityData == null || abilityData.getTemplate() == null)
            return;

        screen.addRenderableWidget(new AutoSolveWidget(screen.x + PLATE_X, screen.y + PLATE_Y, screen));
    }
}
