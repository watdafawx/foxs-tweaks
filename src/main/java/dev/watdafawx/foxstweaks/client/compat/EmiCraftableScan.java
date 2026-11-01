package dev.watdafawx.foxstweaks.client.compat;

/**
 * Set while EMI rebuilds its craftables list ({@code EmiPlayerInventory#getCraftables}), so the AE2
 * quick-fill mixin leaves that bulk scan on EMI's cheap inventory check. Client thread only.
 */
public final class EmiCraftableScan {
    public static boolean active;

    private EmiCraftableScan() {}
}
