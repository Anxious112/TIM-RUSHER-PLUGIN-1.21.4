package com.example.addon.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ClickType;

/**
 * Meteor's InvUtils (find/swap/move helpers) has no RusherHack equivalent, so
 * ported modules use these raw click-slot helpers instead. Container-slot ids
 * are for the default player inventory menu (container id 0):
 *   armor 5-8 (helmet, chestplate, leggings, boots), main 9-35, hotbar 36-44, offhand 45.
 * "Inventory index" here matches net.minecraft.world.entity.player.Inventory's
 * own indexing: hotbar 0-8, main 9-35, armor 36-39 (boots, leggings, chestplate,
 * helmet), offhand 40 -- i.e. the convention InventoryUtils.findItem returns.
 */
public final class InvUtils {
    private InvUtils() {}

    public static final int ARMOR_HELMET_SLOT    = 5;
    public static final int ARMOR_CHESTPLATE_SLOT = 6;
    public static final int ARMOR_LEGGINGS_SLOT  = 7;
    public static final int ARMOR_BOOTS_SLOT     = 8;
    public static final int OFFHAND_SLOT         = 45;

    /** Converts a player-inventory index (hotbar 0-8, main 9-35) to a menu/container slot id. */
    public static int toContainerSlot(int inventoryIndex) {
        if (inventoryIndex >= 0 && inventoryIndex <= 8) return 36 + inventoryIndex;
        return inventoryIndex; // main inventory (9-35) is unchanged
    }

    /** Swaps the contents of two container slots via two vanilla PICKUP clicks. */
    public static void swapContainerSlots(int slotA, int slotB) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;
        mc.gameMode.handleInventoryMouseClick(0, slotA, 0, ClickType.PICKUP, mc.player);
        mc.gameMode.handleInventoryMouseClick(0, slotB, 0, ClickType.PICKUP, mc.player);
        mc.gameMode.handleInventoryMouseClick(0, slotA, 0, ClickType.PICKUP, mc.player);
    }

    /** Moves an item from one container slot into another, swapping whatever was already there. */
    public static void moveToSlot(int fromContainerSlot, int toContainerSlot) {
        swapContainerSlots(fromContainerSlot, toContainerSlot);
    }
}
