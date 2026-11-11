package dev.watdafawx.foxstweaks.relics.client;

import com.google.common.collect.Lists;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.FoxsTweaksComponents;
import dev.watdafawx.foxstweaks.relics.AutoLevelPayload;
import it.hurts.sskirillss.relics.api.relics.IRelicItem;
import it.hurts.sskirillss.relics.client.screen.base.IHoverableWidget;
import it.hurts.sskirillss.relics.client.screen.description.ability.AbilityDescriptionScreen;
import it.hurts.sskirillss.relics.client.screen.description.general.widgets.base.AbstractDescriptionWidget;
import it.hurts.sskirillss.relics.client.screen.description.misc.DescriptionUtils;
import it.hurts.sskirillss.relics.utils.data.AnimationData;
import it.hurts.sskirillss.relics.utils.data.GUIRenderer;
import it.hurts.sskirillss.relics.utils.data.SpriteAnchor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Toggle under Relics' upgrade / reroll / reset column: marks the open ability as the one this
 * relic levels up by itself (see {@code AutoLevelHandler}). Reads the selected ability live, so it
 * follows the card the player picks without being rebuilt.
 */
public class AutoLevelWidget extends AbstractDescriptionWidget implements IHoverableWidget {
    private static final ResourceLocation STAR =
            ResourceLocation.fromNamespaceAndPath(FoxsTweaks.RELICS, "textures/gui/description/research/star.png");

    private static final int STAR_SIZE = 17;
    private static final int STAR_SHEET_HEIGHT = 136;

    private final AbilityDescriptionScreen screen;

    public AutoLevelWidget(int x, int y, AbilityDescriptionScreen screen) {
        super(x, y, 14, 14);

        this.screen = screen;
    }

    private String ability() {
        return screen.getSelectedAbility();
    }

    private String chosen() {
        return screen.stack == null ? null : screen.stack.get(FoxsTweaksComponents.AUTO_LEVEL_ABILITY.get());
    }

    private boolean upgradable() {
        if (screen.stack == null || ability() == null || !(screen.stack.getItem() instanceof IRelicItem relic)
                || minecraft.player == null)
            return false;

        var data = relic.getRelicData(minecraft.player, screen.stack).getAbilitiesData().getAbilityData(ability());

        return data != null && data.getTemplate() != null && data.canBeUpgraded();
    }

    @Override
    public boolean isLocked() {
        return !upgradable();
    }

    @Override
    public void onPress() {
        if (isLocked())
            return;

        boolean on = ability().equals(chosen());

        PacketDistributor.sendToServer(new AutoLevelPayload(screen.getContainer(), screen.getSlot(), on ? "" : ability()));
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (isLocked())
            return;

        boolean on = ability().equals(chosen());
        PoseStack poseStack = guiGraphics.pose();

        int border = isHovered() ? 0xFFF2C14E : on ? 0xFFB8892B : 0xFF5A4A2A;

        guiGraphics.fill(getX(), getY(), getX() + 14, getY() + 14, border);
        guiGraphics.fill(getX() + 1, getY() + 1, getX() + 13, getY() + 13, 0xFF15101F);

        float b = on ? 1.2F : isHovered() ? 0.8F : 0.45F;

        poseStack.pushPose();
        RenderSystem.enableBlend();

        GUIRenderer.begin(STAR, poseStack)
                .anchor(SpriteAnchor.TOP_LEFT)
                .pos(getX() - 1, getY() - 1)
                .texSize(STAR_SIZE, STAR_SHEET_HEIGHT)
                .patternSize(STAR_SIZE, STAR_SIZE)
                .color(b, b, b, 1F)
                .animation(AnimationData.construct(STAR_SHEET_HEIGHT, STAR_SIZE, on ? 2 : 0))
                .end();

        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    @Override
    public void onHovered(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (isLocked())
            return;

        boolean on = ability().equals(chosen());
        String other = chosen();

        List<MutableComponent> entries = Lists.newArrayList(
                Component.translatable("foxstweaks.autolevel.title")
                        .withStyle(ChatFormatting.BOLD).withStyle(ChatFormatting.UNDERLINE),
                Component.literal(" "),
                Component.translatable(on ? "foxstweaks.autolevel.on" : "foxstweaks.autolevel.off")
                        .withColor(DescriptionUtils.POSITIVE_COLOR(true)),
                Component.translatable("foxstweaks.autolevel.detail"));

        if (!on && other != null)
            entries.add(Component.translatable("foxstweaks.autolevel.other", other)
                    .withColor(DescriptionUtils.NEGATIVE_COLOR(true)));

        int maxWidth = 150;
        int renderWidth = 0;
        List<FormattedCharSequence> tooltip = Lists.newArrayList();

        for (MutableComponent entry : entries) {
            int entryWidth = minecraft.font.width(entry) / 2;

            if (entryWidth > renderWidth)
                renderWidth = Math.min(entryWidth + 2, maxWidth);

            tooltip.addAll(minecraft.font.split(entry, maxWidth * 2));
        }

        PoseStack poseStack = guiGraphics.pose();

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
    public void playDownSound(net.minecraft.client.sounds.SoundManager handler) {
        handler.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
