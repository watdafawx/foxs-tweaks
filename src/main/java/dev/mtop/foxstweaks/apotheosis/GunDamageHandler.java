package dev.mtop.foxstweaks.apotheosis;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.FoxsTweaks;
import dev.mtop.foxstweaks.compat.ApothicRarityLookup;
import dev.mtop.foxstweaks.pointblank.compat.PointBlankGunLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Scales a player's bullet damage by the Apothic Ascension rarity of the gun they are holding.
 *
 * <p>A gun's damage comes from its gun pack (TACZ) or its own base stat (Point Blank) and only gains
 * a few flat points from affixes, so it never grows with rarity the way Ascension's melee gear does -
 * and Ascension's mobs are tuned for that. The multiplier runs from 1x up to
 * {@code gunDamageMaxMultiplier} along {@link RarityPatcher#CURVE}, the same curve the affix
 * extrapolation uses.
 *
 * <p>TACZ tags its damage types, so a hit is matched by {@link #BULLETS} / {@link #ARMOR_PIERCING_PARTS}.
 * Point Blank does not: {@code HurtingItem#hurtEntity} deals damage as plain
 * {@code player.damageSources().playerAttack(player)}, so a hit is recognised the way Apothic-PB's own
 * {@code GunDamageHandler} does it - by the shooter's main hand holding a {@code GunItem} - and the
 * shooter's own splash damage from an explosive launcher is excluded the same way it is there.
 *
 * <p>Safe on a pack without Apotheosis or Ascension: no Apotheosis type appears here (the lookup
 * lives in {@link ApothicRarityLookup}, the Point Blank one in {@link PointBlankGunLookup}), and
 * neither is reached until Ascension and the matching gun mod are both known to be loaded.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID)
public final class GunDamageHandler {
    /** TACZ's bullet types, which Ragnarok extends with its fire/ice ones. */
    private static final TagKey<DamageType> BULLETS = tag("tacz:bullets");

    /**
     * The part of a bullet that skips armor. TACZ splits one hit into a normal and an armor-ignoring
     * damage event, and Ragnarok's armor-piercing type is not in {@link #BULLETS}, so both must match
     * or half of the hit would go unscaled. It also holds a mob type, hence the player check below.
     */
    private static final TagKey<DamageType> ARMOR_PIERCING_PARTS = tag("apotheosis_modern_ragnarok:bugfix/armor_piercing_parts");

    private static Boolean ascensionLoaded;
    private static Boolean pointBlankLoaded;

    private GunDamageHandler() {
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!Config.GUN_DAMAGE_SCALING.get() || !isAscensionLoaded())
            return;

        var source = event.getSource();
        if (!(source.getEntity() instanceof Player player))
            return;

        boolean taczBullet = source.is(BULLETS) || source.is(ARMOR_PIERCING_PARTS);
        boolean pointBlankShot = !taczBullet && isPointBlankLoaded() && event.getEntity() != player
                && PointBlankGunLookup.isGun(player.getMainHandItem());
        if (!taczBullet && !pointBlankShot)
            return;

        double multiplier = multiplier(rarity(player.getMainHandItem()), Config.GUN_DAMAGE_MAX_MULTIPLIER.get());
        if (multiplier > 1)
            event.setAmount((float) (event.getAmount() * multiplier));
    }

    /**
     * 1 for anything that is not an Ascension rarity; otherwise 1 up to {@code max}, spaced by
     * {@link RarityPatcher#CURVE}.
     */
    static double multiplier(ResourceLocation rarity, double max) {
        if (rarity == null || !rarity.getNamespace().equals("apothic_ascension"))
            return 1;

        for (int i = 0; i < RarityPatcher.ASCENSION.length; i++) {
            if (RarityPatcher.ASCENSION[i].equals(rarity.getPath()))
                return 1 + (max - 1) * RarityPatcher.CURVE[i];
        }

        return 1;
    }

    private static ResourceLocation rarity(ItemStack stack) {
        return stack.isEmpty() ? null : ApothicRarityLookup.rarityOf(stack);
    }

    private static boolean isAscensionLoaded() {
        if (ascensionLoaded == null) {
            var mods = ModList.get();
            ascensionLoaded = mods.isLoaded(FoxsTweaks.APOTHEOSIS) && mods.isLoaded("apothic_ascension");
        }

        return ascensionLoaded;
    }

    private static boolean isPointBlankLoaded() {
        if (pointBlankLoaded == null)
            pointBlankLoaded = ModList.get().isLoaded(FoxsTweaks.POINT_BLANK);

        return pointBlankLoaded;
    }

    private static TagKey<DamageType> tag(String id) {
        return TagKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse(id));
    }
}
