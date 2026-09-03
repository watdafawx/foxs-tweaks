package dev.mtop.foxstweaks.relics.client;

import com.google.common.collect.Lists;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.mtop.foxstweaks.FoxsTweaks;
import it.hurts.sskirillss.relics.api.relics.IRelicItem;
import it.hurts.sskirillss.relics.client.screen.base.IHoverableWidget;
import it.hurts.sskirillss.relics.client.screen.base.ITickingWidget;
import it.hurts.sskirillss.relics.client.screen.description.general.widgets.base.AbstractDescriptionWidget;
import it.hurts.sskirillss.relics.client.screen.description.misc.DescriptionTextures;
import it.hurts.sskirillss.relics.client.screen.description.misc.DescriptionUtils;
import it.hurts.sskirillss.relics.client.screen.description.research.AbilityResearchScreen;
import it.hurts.sskirillss.relics.client.screen.description.research.particles.ResearchParticleData;
import it.hurts.sskirillss.relics.client.screen.utils.ParticleStorage;
import it.hurts.sskirillss.relics.utils.data.AnimationData;
import it.hurts.sskirillss.relics.utils.data.GUIRenderer;
import it.hurts.sskirillss.relics.utils.data.SpriteAnchor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.RandomSource;

import java.awt.Color;
import java.util.List;

/**
 * A second shelf directly under the hint button. It reuses the plate, hover outline and star
 * sprite that Relics already ships rather than near-identical copies, so the two controls read
 * as a matching pair instead of a bolted-on extra.
 */
public class AutoSolveWidget extends AbstractDescriptionWidget implements IHoverableWidget, ITickingWidget {
    /** The same twinkling star drawn for the constellation nodes: 8 frames of 17x17. */
    private static final ResourceLocation STAR =
            ResourceLocation.fromNamespaceAndPath(FoxsTweaks.RELICS, "textures/gui/description/research/star.png");

    private static final int STAR_SIZE = 17;
    private static final int STAR_SHEET_HEIGHT = 136;

    /** Ticks to ignore further presses for, so a double click cannot send the pattern twice. */
    private static final int PRESS_COOLDOWN = 10;

    private final AbilityResearchScreen screen;

    private int readyAtTick;

    public AutoSolveWidget(int x, int y, AbilityResearchScreen screen) {
        super(x, y, 87, 22);

        this.screen = screen;
    }

    @Override
    public boolean isLocked() {
        return !ResearchSolver.isSolvable(screen);
    }

    @Override
    public void onPress() {
        LocalPlayer player = minecraft.player;

        // The server applies each link as it arrives and only syncs back afterwards, so a second
        // press before that sync would plan against stale links and send the whole pattern again.
        if (player == null || player.tickCount < readyAtTick || isLocked())
            return;

        ResearchSolver.apply(screen);

        readyAtTick = player.tickCount + PRESS_COOLDOWN;
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        LocalPlayer player = minecraft.player;

        if (player == null || screen.stack == null || !(screen.stack.getItem() instanceof IRelicItem))
            return;

        PoseStack poseStack = guiGraphics.pose();

        poseStack.pushPose();

        GUIRenderer.begin(DescriptionTextures.HINT_BACKGROUND, poseStack)
                .anchor(SpriteAnchor.TOP_LEFT)
                .pos(getX(), getY() - 10)
                .end();

        float time = player.tickCount + partialTick;

        // Dimmed once the constellation is done, mirroring the hint button's burnt-out bulb;
        // otherwise it brightens and breathes under the cursor like the glowing one.
        float brightness = isLocked()
                ? 0.4F
                : isHovered() ? (float) (1.15F + (Math.sin(time * 0.25F) * 0.15F)) : 1F;

        RenderSystem.enableBlend();

        GUIRenderer.begin(STAR, poseStack)
                .anchor(SpriteAnchor.TOP_LEFT)
                .pos(getX() + 33, getY() - 3)
                .texSize(STAR_SIZE, STAR_SHEET_HEIGHT)
                .patternSize(STAR_SIZE, STAR_SIZE)
                .color(brightness, brightness, brightness, 1F)
                .animation(AnimationData.construct(STAR_SHEET_HEIGHT, STAR_SIZE, 2))
                .end();

        RenderSystem.disableBlend();

        if (isHovered())
            GUIRenderer.begin(DescriptionTextures.HINT_OUTLINE, poseStack)
                    .anchor(SpriteAnchor.TOP_LEFT)
                    .pos(getX() - 1, getY() - 6)
                    .end();

        poseStack.popPose();
    }

    @Override
    public void onTick() {
        LocalPlayer player = minecraft.player;

        if (player == null || !isHovered() || isLocked())
            return;

        RandomSource random = player.getRandom();

        if (player.tickCount % 5 == 0)
            ParticleStorage.addParticle(screen, new ResearchParticleData(
                    new Color(100 + random.nextInt(150), random.nextInt(25), 200 + random.nextInt(50)),
                    getX() + 34 + random.nextInt(16), getY() - 3 + random.nextInt(16),
                    1F + (random.nextFloat() * 0.25F), 20 + random.nextInt(40), 0.01F));
    }

    @Override
    public void onHovered(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (screen.stack == null || !(screen.stack.getItem() instanceof IRelicItem))
            return;

        PoseStack poseStack = guiGraphics.pose();

        List<FormattedCharSequence> tooltip = Lists.newArrayList();

        int maxWidth = 150;
        int renderWidth = 0;

        List<MutableComponent> entries = Lists.newArrayList(
                Component.translatable("foxstweaks.research.autosolve.description")
                        .withStyle(ChatFormatting.BOLD).withStyle(ChatFormatting.UNDERLINE),
                Component.literal(" ")
        );

        if (isLocked())
            entries.add(Component.translatable("foxstweaks.research.autosolve.locked"));
        else {
            ResearchSolver.Plan plan = ResearchSolver.plan(screen);

            entries.add(Component.translatable("foxstweaks.research.autosolve.draw", plan.toAdd().size())
                    .withColor(DescriptionUtils.POSITIVE_COLOR(true)));

            if (!plan.toRemove().isEmpty())
                entries.add(Component.translatable("foxstweaks.research.autosolve.clear", plan.toRemove().size())
                        .withColor(DescriptionUtils.NEGATIVE_COLOR(true)));

            entries.add(Component.literal(" "));
            entries.add(Component.literal("▶ ").append(Component.translatable("foxstweaks.research.autosolve.free")));
        }

        for (MutableComponent entry : entries) {
            int entryWidth = (minecraft.font.width(entry) / 2);

            if (entryWidth > renderWidth)
                renderWidth = Math.min(entryWidth + 2, maxWidth);

            tooltip.addAll(minecraft.font.split(entry, maxWidth * 2));
        }

        poseStack.pushPose();

        poseStack.translate(0F, 0F, 100);

        DescriptionUtils.drawTooltipBackground(guiGraphics, renderWidth, tooltip.size() * 5, mouseX - 9 - (renderWidth / 2), mouseY);

        poseStack.scale(0.5F, 0.5F, 0.5F);

        int yOff = 0;

        for (FormattedCharSequence entry : tooltip) {
            guiGraphics.drawString(minecraft.font, entry, ((mouseX - renderWidth / 2) + 1) * 2, ((mouseY + yOff + 9) * 2), DescriptionUtils.TEXT_COLOR, false);

            yOff += 5;
        }

        poseStack.popPose();
    }

    @Override
    public void playDownSound(SoundManager handler) {
        // Relics' research widgets are silent on click; the feedback comes from the link sounds.
    }
}
