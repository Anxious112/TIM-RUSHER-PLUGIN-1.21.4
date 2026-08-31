package com.example.addon.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import com.example.addon.Tim;
import com.example.addon.mixin.HandledScreenAccessor;
import com.example.addon.utils.InvUtils;

import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.utils.InventoryUtils;
import org.rusherhack.client.api.setting.ItemListSetting;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.setting.StringSetting;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

enum ReplenishMode { Single, Fill, Custom }

public class Inventory101 extends ToggleableModule {

    // ── Presets ──
    private final StringSetting preset1Name = new StringSetting("preset-1-name", "Custom name for Preset 1.", "1");
    private final StringSetting preset2Name = new StringSetting("preset-2-name", "Custom name for Preset 2.", "2");
    private final StringSetting preset1Data = new StringSetting("preset-1-data", "Saved data for inventory preset 1.", "").setVisibility(() -> false);
    private final StringSetting preset2Data = new StringSetting("preset-2-data", "Saved data for inventory preset 2.", "").setVisibility(() -> false);

    // ── Regear ──
    private final BooleanSetting showRegearButton = new BooleanSetting("regear-show-button", "Shows the G (Gear) button in shulker boxes.", true);
    private final NumberSetting<Integer> regearDelay = new NumberSetting<>("regear-delay", "Delay in ticks between armor movement actions.", 2, 1, 20);

    // ── Replenish ──
    private final BooleanSetting showReplenishButton = new BooleanSetting("replenish-show-button", "Shows the R (Replenish) button in shulker boxes.", true);
    private final BooleanSetting replenishEnderChest = new BooleanSetting("ender-chest", "Replenish Ender Chests from shulker boxes.", true);
    private final EnumSetting<ReplenishMode> enderChestMode = new EnumSetting<>("ender-chest-mode", "How many Ender Chests to keep in inventory.", ReplenishMode.Fill).setVisibility(replenishEnderChest::getValue);
    private final NumberSetting<Integer> enderChestCount = new NumberSetting<>("ender-chest-count", "Exact number of Ender Chests to maintain.", 1, 1, 512)
        .setVisibility(() -> replenishEnderChest.getValue() && enderChestMode.getValue() == ReplenishMode.Custom);
    private final BooleanSetting replenishObsidian = new BooleanSetting("obsidian", "Replenish Obsidian from shulker boxes.", true);
    private final EnumSetting<ReplenishMode> obsidianMode = new EnumSetting<>("obsidian-mode", "How many Obsidian to keep in inventory.", ReplenishMode.Fill).setVisibility(replenishObsidian::getValue);
    private final NumberSetting<Integer> obsidianCount = new NumberSetting<>("obsidian-count", "Exact number of Obsidian to maintain.", 64, 1, 512)
        .setVisibility(() -> replenishObsidian.getValue() && obsidianMode.getValue() == ReplenishMode.Custom);
    private final BooleanSetting replenishFireworkRocket = new BooleanSetting("firework-rocket", "Replenish Firework Rockets from shulker boxes.", true);
    private final EnumSetting<ReplenishMode> fireworkRocketMode = new EnumSetting<>("firework-rocket-mode", "How many Firework Rockets to keep in inventory.", ReplenishMode.Fill).setVisibility(replenishFireworkRocket::getValue);
    private final NumberSetting<Integer> fireworkRocketCount = new NumberSetting<>("firework-rocket-count", "Exact number of Firework Rockets to maintain.", 64, 1, 512)
        .setVisibility(() -> replenishFireworkRocket.getValue() && fireworkRocketMode.getValue() == ReplenishMode.Custom);
    private final BooleanSetting replenishEnchantedGoldenApple = new BooleanSetting("enchanted-golden-apple", "Replenish Enchanted Golden Apples from shulker boxes.", true);
    private final EnumSetting<ReplenishMode> enchantedGoldenAppleMode = new EnumSetting<>("enchanted-golden-apple-mode", "How many Enchanted Golden Apples to keep in inventory.", ReplenishMode.Single).setVisibility(replenishEnchantedGoldenApple::getValue);
    private final NumberSetting<Integer> enchantedGoldenAppleCount = new NumberSetting<>("enchanted-golden-apple-count", "Exact number of Enchanted Golden Apples to maintain.", 1, 1, 512)
        .setVisibility(() -> replenishEnchantedGoldenApple.getValue() && enchantedGoldenAppleMode.getValue() == ReplenishMode.Custom);
    private final BooleanSetting replenishTotem = new BooleanSetting("totem-of-undying", "Replenish Totems of Undying from shulker boxes.", true);
    private final EnumSetting<ReplenishMode> totemMode = new EnumSetting<>("totem-mode", "How many Totems of Undying to keep in inventory.", ReplenishMode.Single).setVisibility(replenishTotem::getValue);
    private final NumberSetting<Integer> totemCount = new NumberSetting<>("totem-count", "Exact number of Totems to maintain.", 1, 1, 36)
        .setVisibility(() -> replenishTotem.getValue() && totemMode.getValue() == ReplenishMode.Custom);
    private final BooleanSetting replenishElytra = new BooleanSetting("elytra", "Replenish Elytras from shulker boxes (durability-aware swapping).", true);
    private final EnumSetting<ReplenishMode> elytraMode = new EnumSetting<>("elytra-mode", "How many Elytras to keep in inventory (not counting armor slot).", ReplenishMode.Single).setVisibility(replenishElytra::getValue);
    private final NumberSetting<Integer> elytraCount = new NumberSetting<>("elytra-count", "Exact number of Elytras to maintain (inventory only).", 1, 1, 36)
        .setVisibility(() -> replenishElytra.getValue() && elytraMode.getValue() == ReplenishMode.Custom);
    private final NumberSetting<Integer> elytraThreshold = new NumberSetting<>("elytra-threshold", "Durability threshold to consider an elytra as needing replacement.", 15, 1, 100).setVisibility(replenishElytra::getValue);
    private final BooleanSetting replenishEndCrystal = new BooleanSetting("end-crystal", "Replenish End Crystals from shulker boxes.", true);
    private final EnumSetting<ReplenishMode> endCrystalMode = new EnumSetting<>("end-crystal-mode", "How many End Crystals to keep in inventory.", ReplenishMode.Fill).setVisibility(replenishEndCrystal::getValue);
    private final NumberSetting<Integer> endCrystalCount = new NumberSetting<>("end-crystal-count", "Exact number of End Crystals to maintain.", 16, 1, 512)
        .setVisibility(() -> replenishEndCrystal.getValue() && endCrystalMode.getValue() == ReplenishMode.Custom);
    private final BooleanSetting replenishCustom = new BooleanSetting("custom-items-enabled", "Enable replenishing custom items from shulker boxes.", false);
    private final ItemListSetting customReplenishItems = new ItemListSetting("custom-items", "Additional items to replenish (priority = list order).");
    private final EnumSetting<ReplenishMode> customMode = new EnumSetting<>("custom-mode", "Replenish mode for all custom items.", ReplenishMode.Fill).setVisibility(replenishCustom::getValue);
    private final NumberSetting<Integer> customCount = new NumberSetting<>("custom-count", "Exact number to maintain for each custom item.", 64, 1, 512)
        .setVisibility(() -> replenishCustom.getValue() && customMode.getValue() == ReplenishMode.Custom);
    private final NumberSetting<Integer> replenishDelay = new NumberSetting<>("replenish-delay", "Delay in ticks between movement actions.", 2, 1, 20);

    // ── Organizer ──
    private final BooleanSetting showSortButton = new BooleanSetting("show-sort-button", "Show a sort button in chests.", true);
    private final NumberSetting<Integer> sortDelay = new NumberSetting<>("sort-delay", "Delay in ticks between sort actions.", 2, 1, 20).setVisibility(showSortButton::getValue);
    private final BooleanSetting shiftClickAll = new BooleanSetting("shift-click-all", "When shift-clicking an item, moves all items of the same type from that inventory.", true);

    // ── Cleaner ──
    private final BooleanSetting autoTrash = new BooleanSetting("auto-trash", "Automatically discards whitelisted items.", false);
    private final ItemListSetting trashItems = new ItemListSetting("trash-items", "Items to automatically discard.");
    private final NumberSetting<Integer> trashDelay = new NumberSetting<>("trash-delay", "Delay in ticks between discard actions.", 2, 1, 20).setVisibility(autoTrash::getValue);

    // ── Auto Tool ──
    private final BooleanSetting autoTool = new BooleanSetting("auto-tool", "Automatically swaps to the best tool when breaking blocks.", false);
    private final BooleanSetting silentAutoTool = new BooleanSetting("silent-swap", "Swaps to the tool silently.", true).setVisibility(autoTool::getValue);

    // ── State ──
    private boolean saveMode = false;
    private boolean isRegearing = false;
    private int regearTimer = 0;
    private int regearPresetIndex = 0;
    private boolean isReplenishing = false;
    private int replenishTimer = 0;
    private boolean replenishedForCurrentScreen = false;
    private final Map<Item, Integer> pulledThisSession = new LinkedHashMap<>();
    private boolean isSorting = false;
    private int sortTimer = 0;
    private boolean isInvSorting = false;
    private int invSortTimer = 0;
    private int invSortPreset = 0;
    private boolean isTrashing = false;
    private int trashTimer = 0;
    private boolean trashedForCurrentScreen = false;

    private boolean wasClicking = false;
    private double lastMouseX = -1;
    private double lastMouseY = -1;
    private final Set<Integer> processedInDrag = new HashSet<>();

    private boolean moveAllActionTaken = false;
    private boolean wasBreaking = false;
    private int prevSlotAutoTool = -1;

    public Inventory101() {
        super("inventory-101", "Manages inventory layouts with shulker boxes.", Tim.CATEGORY);
        this.registerSettings(
            preset1Name, preset2Name, preset1Data, preset2Data,
            showRegearButton, regearDelay,
            showReplenishButton, replenishEnderChest, enderChestMode, enderChestCount,
            replenishObsidian, obsidianMode, obsidianCount, replenishFireworkRocket, fireworkRocketMode, fireworkRocketCount,
            replenishEnchantedGoldenApple, enchantedGoldenAppleMode, enchantedGoldenAppleCount,
            replenishTotem, totemMode, totemCount, replenishElytra, elytraMode, elytraCount, elytraThreshold,
            replenishEndCrystal, endCrystalMode, endCrystalCount,
            replenishCustom, customReplenishItems, customMode, customCount, replenishDelay,
            showSortButton, sortDelay, shiftClickAll,
            autoTrash, trashItems, trashDelay, autoTool, silentAutoTool
        );
    }

    @Override
    public void onDisable() {
        isRegearing = false;
        regearTimer = 0;
        regearPresetIndex = 0;
        isReplenishing = false;
        replenishTimer = 0;
        isSorting = false;
        isInvSorting = false;
        isTrashing = false;
        wasClicking = false;
        lastMouseX = -1;
        lastMouseY = -1;
        saveMode = false;
        processedInDrag.clear();
        moveAllActionTaken = false;
        wasBreaking = false;
        prevSlotAutoTool = -1;
        trashTimer = 0;
        trashedForCurrentScreen = false;
        replenishedForCurrentScreen = false;
        pulledThisSession.clear();
    }

    private void info(String msg) { sendNotification(NotificationType.INFO, msg); }
    private void warning(String msg) { sendNotification(NotificationType.WARNING, msg); }
    private void error(String msg) { sendNotification(NotificationType.ERROR, msg); }

    public String getPresetName(int index) {
        return (index == 1) ? preset1Name.getValue() : preset2Name.getValue();
    }

    // ── Public API for HandledScreenMixin ──
    public boolean isRegearButtonEnabled() { return showRegearButton.getValue(); }

    public void startRegearing() {
        if (isBusy()) return;
        isRegearing = true;
        regearTimer = 0;
        regearPresetIndex = 0;
        info("Regearing Essentials...");
    }

    public void toggleSaveMode() {
        if (isBusy()) return;
        saveMode = !saveMode;
        info(saveMode ? "§eSelect a preset slot (1 or 2) to SAVE current layout." : "§7Save mode §ccancelled§7.");
    }

    public boolean isSaveMode() { return saveMode; }

    public boolean isPresetEmpty(int index) {
        String data = (index == 1) ? preset1Data.getValue() : preset2Data.getValue();
        return data == null || data.isEmpty();
    }

    public void handlePreset(int index) {
        if (isBusy() && !saveMode) return;
        if (saveMode) {
            saveInventory(index);
            saveMode = false;
            info("Inventory layout saved to Preset §a" + getPresetName(index) + "§7.");
        } else {
            isRegearing = true;
            regearTimer = 0;
            regearPresetIndex = index;
            info("Loading Preset §6" + getPresetName(index) + "§7...");
        }
    }

    public void clearPresets() {
        preset1Data.setValue("");
        preset2Data.setValue("");
        saveMode = false;
        info("Presets cleared.");
    }

    public void startInvSort(int presetIndex) {
        if (isBusy()) return;
        if (isPresetEmpty(presetIndex)) {
            warning("Preset " + getPresetName(presetIndex) + " is empty.");
            return;
        }
        invSortPreset = presetIndex;
        isInvSorting = true;
        invSortTimer = 0;
        info("Sorting inventory to Preset §6" + getPresetName(presetIndex) + "§7...");
    }

    public boolean isSortButtonEnabled() { return showSortButton.getValue(); }

    public void startSorting() {
        if (isBusy()) return;
        isSorting = true;
        sortTimer = 0;
    }

    public boolean isReplenishButtonEnabled() {
        if (getReplenishWhitelist().isEmpty()) return false;
        return showReplenishButton.getValue();
    }

    public void startReplenishing() {
        if (isBusy()) return;
        if (getReplenishWhitelist().isEmpty()) {
            warning("No items to replenish (whitelist is empty).");
            return;
        }
        pulledThisSession.clear();
        isReplenishing = true;
        replenishTimer = 0;
        info("Restocking whitelisted items...");
    }

    private List<Item> getReplenishWhitelist() {
        List<Item> whitelist = new ArrayList<>();
        if (replenishEnderChest.getValue()) whitelist.add(Items.ENDER_CHEST);
        if (replenishObsidian.getValue()) whitelist.add(Items.OBSIDIAN);
        if (replenishFireworkRocket.getValue()) whitelist.add(Items.FIREWORK_ROCKET);
        if (replenishEnchantedGoldenApple.getValue()) whitelist.add(Items.ENCHANTED_GOLDEN_APPLE);
        if (replenishTotem.getValue()) whitelist.add(Items.TOTEM_OF_UNDYING);
        if (replenishElytra.getValue()) whitelist.add(Items.ELYTRA);
        if (replenishEndCrystal.getValue()) whitelist.add(Items.END_CRYSTAL);
        if (replenishCustom.getValue()) {
            for (Item item : customReplenishItems.getList()) {
                if (!whitelist.contains(item)) whitelist.add(item);
            }
        }
        return whitelist;
    }

    private int getPullLimit(Item item) {
        if (item == Items.ENDER_CHEST) return modeLimit(enderChestMode.getValue(), item, enderChestCount.getValue());
        if (item == Items.OBSIDIAN) return modeLimit(obsidianMode.getValue(), item, obsidianCount.getValue());
        if (item == Items.FIREWORK_ROCKET) return modeLimit(fireworkRocketMode.getValue(), item, fireworkRocketCount.getValue());
        if (item == Items.ENCHANTED_GOLDEN_APPLE) return modeLimit(enchantedGoldenAppleMode.getValue(), item, enchantedGoldenAppleCount.getValue());
        if (item == Items.TOTEM_OF_UNDYING) return modeLimit(totemMode.getValue(), item, totemCount.getValue());
        if (item == Items.ELYTRA) return modeLimit(elytraMode.getValue(), item, elytraCount.getValue());
        if (item == Items.END_CRYSTAL) return modeLimit(endCrystalMode.getValue(), item, endCrystalCount.getValue());
        return modeLimit(customMode.getValue(), item, customCount.getValue());
    }

    private int modeLimit(ReplenishMode mode, Item item, int custom) {
        return switch (mode) {
            case Single -> item.getDefaultMaxStackSize();
            case Fill -> Integer.MAX_VALUE;
            case Custom -> custom;
        };
    }

    // ── Tick Handler ──
    @Subscribe
    private void onTick(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        tickAutoTool();

        if (isRegearing) {
            if (!(mc.screen instanceof ShulkerBoxScreen)) { isRegearing = false; return; }
            if (regearTimer > 0) { regearTimer--; return; }
            if (performRegearStep()) {
                regearTimer = regearDelay.getValue();
            } else {
                boolean wasPresetRegear = regearPresetIndex != 0;
                isRegearing = false;
                info("Regear §acomplete§7.");
                mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                if (!wasPresetRegear) startReplenishing();
            }
            return;
        }

        if (isReplenishing) {
            if (!(mc.screen instanceof ShulkerBoxScreen)) { isReplenishing = false; return; }
            if (replenishTimer > 0) { replenishTimer--; return; }
            if (performReplenishStep()) {
                replenishTimer = replenishDelay.getValue();
            } else {
                info("Restock §acomplete§7.");
                mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                isReplenishing = false;
            }
            return;
        }

        if (isInvSorting) {
            if (!(mc.screen instanceof InventoryScreen)) { isInvSorting = false; return; }
            if (invSortTimer > 0) { invSortTimer--; return; }
            if (performInvSortStep()) {
                invSortTimer = sortDelay.getValue();
            } else {
                isInvSorting = false;
                info(getPresetName(invSortPreset) + " sort §acomplete§7.");
                mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            }
            return;
        }

        if (isSorting) {
            if (!(mc.screen instanceof ContainerScreen)) { isSorting = false; return; }
            if (sortTimer > 0) { sortTimer--; return; }
            if (performSortStep()) {
                sortTimer = sortDelay.getValue();
            } else {
                isSorting = false;
                info("Sorting §acomplete§7.");
            }
            return;
        }

        tickMouseInteractions();
        tickAutoTrash();
        tickAutoDrop();
    }

    // ── Auto Tool ──
    private void tickAutoTool() {
        if (!autoTool.getValue()) return;
        if (mc.gameMode.isDestroying()) {
            HitResult hit = mc.hitResult;
            if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                BlockState state = mc.level.getBlockState(bhr.getBlockPos());
                if (!state.isAir()) {
                    int bestSlot = findBestTool(state);
                    if (bestSlot != -1 && bestSlot != mc.player.getInventory().selected) {
                        if (!wasBreaking) {
                            prevSlotAutoTool = mc.player.getInventory().selected;
                            wasBreaking = true;
                        }
                        InventoryUtils.setHotbarSlot(bestSlot);
                    }
                }
            }
        } else if (wasBreaking) {
            if (silentAutoTool.getValue() && prevSlotAutoTool != -1) {
                InventoryUtils.setHotbarSlot(prevSlotAutoTool);
            }
            wasBreaking = false;
            prevSlotAutoTool = -1;
        }
    }

    // ── Mouse Interactions ──
    private void tickMouseInteractions() {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            if (wasClicking) {
                processedInDrag.clear();
                lastMouseX = -1;
                moveAllActionTaken = false;
            }
            wasClicking = false;
            return;
        }

        long win = mc.getWindow().getWindow();
        boolean isClicking = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean isShift = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                       || GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

        if (isClicking && isShift) {
            if (!wasClicking) {
                if (shiftClickAll.getValue()) {
                    Slot focused = getFocusedSlot(screen);
                    if (focused != null && focused.hasItem()) {
                        moveAllActionTaken = true;
                        Item targetItem = focused.getItem().getItem();
                        boolean clickedInPlayerInventory = focused.container == mc.player.getInventory();
                        for (Slot slot : screen.getMenu().slots) {
                            boolean slotInPlayerInventory = slot.container == mc.player.getInventory();
                            if (slot.hasItem() && slot.getItem().getItem() == targetItem) {
                                if (clickedInPlayerInventory == slotInPlayerInventory) {
                                    click(screen.getMenu().containerId, slot.index, 0, ClickType.QUICK_MOVE);
                                }
                            }
                        }
                    }
                }
                if (!moveAllActionTaken) {
                    processedInDrag.clear();
                    lastMouseX = mc.mouseHandler.xpos();
                    lastMouseY = mc.mouseHandler.ypos();
                    Slot focused = getFocusedSlot(screen);
                    if (focused != null && focused.hasItem() && !processedInDrag.contains(focused.index)) {
                        click(screen.getMenu().containerId, focused.index, 0, ClickType.QUICK_MOVE);
                        processedInDrag.add(focused.index);
                    }
                }
            } else if (!moveAllActionTaken) {
                double mouseX = mc.mouseHandler.xpos();
                double mouseY = mc.mouseHandler.ypos();

                if (lastMouseX != -1) {
                    double deltaX = mouseX - lastMouseX;
                    double deltaY = mouseY - lastMouseY;
                    double dist = Math.sqrt(deltaX * deltaX + deltaY * deltaY);

                    if (dist > 1 && Math.abs(deltaY) < 14) {
                        int steps = (int) Math.ceil(dist / 2.0);
                        for (int i = 0; i <= steps; i++) {
                            double currentX = lastMouseX + (deltaX * i / steps);
                            double currentY = lastMouseY + (deltaY * i / steps);
                            Slot slot = getSlotAt(screen, currentX, currentY);
                            if (slot != null && slot.hasItem() && !processedInDrag.contains(slot.index)) {
                                click(screen.getMenu().containerId, slot.index, 0, ClickType.QUICK_MOVE);
                                processedInDrag.add(slot.index);
                            }
                        }
                    }
                }

                Slot focused = getFocusedSlot(screen);
                if (focused != null && focused.hasItem() && !processedInDrag.contains(focused.index)) {
                    click(screen.getMenu().containerId, focused.index, 0, ClickType.QUICK_MOVE);
                    processedInDrag.add(focused.index);
                }

                lastMouseX = mouseX;
                lastMouseY = mouseY;
            }
            wasClicking = true;
        } else {
            if (wasClicking) {
                processedInDrag.clear();
                lastMouseX = -1;
                moveAllActionTaken = false;
            }
            wasClicking = false;
        }
    }

    // ── Regear Logic ──
    private boolean performRegearStep() {
        if (!(mc.player.containerMenu instanceof ShulkerBoxMenu handler)) return false;
        if (regearPresetIndex == 0) return performGenericRegearStep(handler);

        List<ItemStack> preset = getPreset(regearPresetIndex);
        int[] slotOrder = {
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            36, 37, 38, 39, 40
        };

        for (int presetSlot : slotOrder) {
            ItemStack desired = preset.get(presetSlot);
            if (desired.isEmpty()) continue;
            if (isPresetSlotMatch(presetSlot, desired)) continue;

            if (presetSlot >= 36) {
                for (int j = 0; j < 27; j++) {
                    ItemStack shulkerStack = handler.getSlot(j).getItem();
                    if (shulkerStack.isEmpty() || !isSameItemType(shulkerStack, desired)) continue;
                    if (shulkerStack.is(Items.ELYTRA) && isLowDurabilityElytra(shulkerStack)) continue;
                    quickMove(j);
                    return true;
                }
            } else {
                int targetSlotId = mapInventoryToSlotId(presetSlot);
                if (targetSlotId == -1) continue;

                for (int i = 27; i < 63; i++) {
                    if (i == targetSlotId) continue;
                    ItemStack invStack = handler.getSlot(i).getItem();
                    if (invStack.isEmpty() || !isSameItemType(invStack, desired)) continue;

                    int sourceInvIndex = screenHandlerSlotToInvIndex(i);
                    if (sourceInvIndex >= 0 && sourceInvIndex < 36) {
                        ItemStack sourceDesired = preset.get(sourceInvIndex);
                        if (!sourceDesired.isEmpty() && isSameItemType(invStack, sourceDesired)) continue;
                    }
                    smartMove(i, targetSlotId);
                    return true;
                }

                for (int j = 0; j < 27; j++) {
                    ItemStack shulkerStack = handler.getSlot(j).getItem();
                    if (shulkerStack.isEmpty() || !isSameItemType(shulkerStack, desired)) continue;
                    if (shulkerStack.is(Items.ELYTRA) && isLowDurabilityElytra(shulkerStack)) continue;
                    smartMove(j, targetSlotId);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isPresetSlotMatch(int presetSlot, ItemStack desired) {
        ItemStack current;
        if (presetSlot < 36) current = mc.player.getInventory().getItem(presetSlot);
        else if (presetSlot == 36) current = mc.player.getOffhandItem();
        else {
            EquipmentSlot eqSlot = presetIndexToArmorSlot(presetSlot);
            if (eqSlot == null) return false;
            current = mc.player.getItemBySlot(eqSlot);
        }
        if (!isSameItemType(current, desired)) return false;
        if (current.is(Items.ELYTRA) && isLowDurabilityElytra(current)) return false;
        return true;
    }

    private int screenHandlerSlotToInvIndex(int slotId) {
        if (slotId >= 27 && slotId <= 53) return (slotId - 27) + 9;
        if (slotId >= 54 && slotId <= 62) return slotId - 54;
        return -1;
    }

    private boolean performGenericRegearStep(ShulkerBoxMenu handler) {
        EquipmentSlot[] armorSlots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : armorSlots) {
            ItemStack current = mc.player.getItemBySlot(slot);
            boolean needsEquip = current.isEmpty();
            if (slot == EquipmentSlot.CHEST && !current.isEmpty() && current.is(Items.ELYTRA) && isLowDurabilityElytra(current)) {
                needsEquip = true;
            }
            if (needsEquip) {
                for (int j = 0; j < 27; j++) {
                    ItemStack shulkerStack = handler.getSlot(j).getItem();
                    if (shulkerStack.isEmpty()) continue;
                    Equippable equippable = shulkerStack.get(DataComponents.EQUIPPABLE);
                    if (equippable != null && equippable.slot() == slot) {
                        quickMove(j);
                        return true;
                    }
                    if (slot == EquipmentSlot.CHEST && shulkerStack.is(Items.ELYTRA) && !isLowDurabilityElytra(shulkerStack)) {
                        quickMove(j);
                        return true;
                    }
                }
            }
        }

        ItemStack offhand = mc.player.getOffhandItem();
        if (!offhand.is(Items.TOTEM_OF_UNDYING)) {
            for (int j = 0; j < 27; j++) {
                if (handler.getSlot(j).getItem().is(Items.TOTEM_OF_UNDYING)) {
                    quickMove(j);
                    return true;
                }
            }
        }
        return false;
    }

    private EquipmentSlot presetIndexToArmorSlot(int i) {
        return switch (i) {
            case 37 -> EquipmentSlot.FEET;
            case 38 -> EquipmentSlot.LEGS;
            case 39 -> EquipmentSlot.CHEST;
            case 40 -> EquipmentSlot.HEAD;
            default -> null;
        };
    }

    // ── Replenish Logic ──
    private static class InvItemState {
        int totalCount = 0;
        int partialSlotId = -1;
        int partialCount = 0;
        int maxCount = 64;
        int badElytraSlotId = -1;
    }

    private boolean performReplenishStep() {
        if (!(mc.player.containerMenu instanceof ShulkerBoxMenu handler)) return false;

        List<Item> whitelist = getReplenishWhitelist();
        if (whitelist.isEmpty()) return false;

        Map<Item, List<Integer>> shulkerSlots = new LinkedHashMap<>();
        for (int j = 0; j < 27; j++) {
            ItemStack stack = handler.getSlot(j).getItem();
            if (stack.isEmpty()) continue;
            if (whitelist.contains(stack.getItem())) {
                shulkerSlots.computeIfAbsent(stack.getItem(), k -> new ArrayList<>()).add(j);
            }
        }

        Map<Item, InvItemState> invState = new LinkedHashMap<>();
        boolean hasEmptyInvSlot = false;
        for (int i = 27; i < 63; i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (stack.isEmpty()) {
                hasEmptyInvSlot = true;
                continue;
            }
            Item item = stack.getItem();
            InvItemState state = invState.computeIfAbsent(item, k -> new InvItemState());
            state.maxCount = stack.getMaxStackSize();

            if (isBadElytra(stack)) {
                state.badElytraSlotId = i;
                continue;
            }

            state.totalCount += stack.getCount();
            if (stack.getCount() < stack.getMaxStackSize() && state.partialSlotId == -1) {
                state.partialSlotId = i;
                state.partialCount = stack.getCount();
            }
        }

        if (whitelist.contains(Items.ELYTRA)) {
            if (handleElytraSwaps(handler, shulkerSlots.getOrDefault(Items.ELYTRA, List.of()))) return true;
        }

        for (Item item : whitelist) {
            if (item == Items.ELYTRA) continue;
            List<Integer> slots = shulkerSlots.get(item);
            if (slots == null) continue;
            InvItemState state = invState.get(item);
            if (state == null || state.partialSlotId == -1) continue;

            int pullLimit = getPullLimit(item);
            int alreadyPulled = pulledThisSession.getOrDefault(item, 0);
            if (alreadyPulled >= pullLimit) continue;

            int space = state.maxCount - state.partialCount;
            if (space <= 0) continue;

            for (int shulkerSlot : slots) {
                ItemStack shulkerStack = handler.getSlot(shulkerSlot).getItem();
                if (shulkerStack.isEmpty() || shulkerStack.getItem() != item) continue;

                int itemsMoved = Math.min(shulkerStack.getCount(), space);
                if (shulkerStack.getCount() <= space) {
                    quickMove(shulkerSlot);
                } else {
                    smartMove(shulkerSlot, state.partialSlotId);
                }
                pulledThisSession.merge(item, itemsMoved, Integer::sum);
                return true;
            }
        }

        for (Item item : whitelist) {
            if (item == Items.ELYTRA) continue;
            List<Integer> slots = shulkerSlots.get(item);
            if (slots == null) continue;

            int pullLimit = getPullLimit(item);
            int alreadyPulled = pulledThisSession.getOrDefault(item, 0);
            if (alreadyPulled >= pullLimit) continue;

            boolean hasRoom = hasEmptyInvSlot
                || (invState.get(item) != null && invState.get(item).partialSlotId != -1 && invState.get(item).partialCount < invState.get(item).maxCount);
            if (!hasRoom) continue;

            for (int shulkerSlot : slots) {
                ItemStack shulkerStack = handler.getSlot(shulkerSlot).getItem();
                if (shulkerStack.isEmpty() || shulkerStack.getItem() != item) continue;

                int itemsMoved = shulkerStack.getCount();
                quickMove(shulkerSlot);
                pulledThisSession.merge(item, itemsMoved, Integer::sum);
                return true;
            }
        }

        return false;
    }

    private boolean handleElytraSwaps(ShulkerBoxMenu handler, List<Integer> elytraSlots) {
        int bestShulkerSlot = -1;
        int bestShulkerDurability = -1;
        for (int slot : elytraSlots) {
            ItemStack stack = handler.getSlot(slot).getItem();
            if (!stack.is(Items.ELYTRA)) continue;
            int remaining = stack.getMaxDamage() - stack.getDamageValue();
            if (remaining > bestShulkerDurability) {
                bestShulkerDurability = remaining;
                bestShulkerSlot = slot;
            }
        }

        if (bestShulkerSlot != -1) {
            int worstInvSlot = -1;
            int worstInvDurability = Integer.MAX_VALUE;
            for (int i = 27; i < 63; i++) {
                ItemStack invStack = handler.getSlot(i).getItem();
                if (!invStack.is(Items.ELYTRA)) continue;
                int remaining = invStack.getMaxDamage() - invStack.getDamageValue();
                if (remaining < worstInvDurability) {
                    worstInvDurability = remaining;
                    worstInvSlot = i;
                }
            }
            if (worstInvSlot != -1 && bestShulkerDurability > worstInvDurability) {
                smartMove(bestShulkerSlot, worstInvSlot);
                pulledThisSession.merge(Items.ELYTRA, 1, Integer::sum);
                return true;
            }
        }

        int elytraPullLimit = getPullLimit(Items.ELYTRA);
        int elytrasPulled = pulledThisSession.getOrDefault(Items.ELYTRA, 0);
        if (elytrasPulled < elytraPullLimit) {
            int goodSlot = -1;
            for (int slot : elytraSlots) {
                ItemStack stack = handler.getSlot(slot).getItem();
                if (stack.is(Items.ELYTRA) && !isLowDurabilityElytra(stack)) {
                    goodSlot = slot;
                    break;
                }
            }

            boolean hasAnyElytra = false;
            if (mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) hasAnyElytra = true;
            for (int i = 27; i < 63; i++) {
                if (handler.getSlot(i).getItem().is(Items.ELYTRA)) { hasAnyElytra = true; break; }
            }

            int pullSlot = goodSlot != -1 ? goodSlot : (!hasAnyElytra ? bestShulkerSlot : -1);

            if (pullSlot != -1) {
                for (int i = 27; i < 63; i++) {
                    if (handler.getSlot(i).getItem().isEmpty()) {
                        smartMove(pullSlot, i);
                        pulledThisSession.merge(Items.ELYTRA, 1, Integer::sum);
                        return true;
                    }
                }
            }
        }

        for (int i = 27; i < 63; i++) {
            ItemStack invStack = handler.getSlot(i).getItem();
            if (!invStack.is(Items.ELYTRA) || !isLowDurabilityElytra(invStack)) continue;
            for (int j = 0; j < 27; j++) {
                if (handler.getSlot(j).getItem().isEmpty()) {
                    smartMove(i, j);
                    return true;
                }
            }
            break;
        }

        return false;
    }

    private boolean isBadElytra(ItemStack stack) {
        return stack.is(Items.ELYTRA) && isLowDurabilityElytra(stack);
    }

    // ── Inv Sort ──
    private boolean performInvSortStep() {
        List<ItemStack> preset = getPreset(invSortPreset);
        boolean[] satisfied = new boolean[36];
        boolean[] inventoryClaimed = new boolean[36];

        for (int i = 0; i < 36; i++) {
            ItemStack desired = preset.get(i);
            if (desired.isEmpty()) {
                satisfied[i] = mc.player.getInventory().getItem(i).isEmpty();
                continue;
            }
            if (!inventoryClaimed[i] && isSameItemType(mc.player.getInventory().getItem(i), desired)) {
                satisfied[i] = true;
                inventoryClaimed[i] = true;
            } else {
                for (int j = 0; j < 36; j++) {
                    if (!inventoryClaimed[j] && isSameItemType(mc.player.getInventory().getItem(j), desired)) {
                        inventoryClaimed[j] = true;
                        break;
                    }
                }
            }
        }

        for (int i = 0; i < 36; i++) {
            if (satisfied[i]) continue;
            ItemStack desired = preset.get(i);
            if (desired.isEmpty() || !mc.player.getInventory().getItem(i).isEmpty()) continue;
            for (int j = 0; j < 36; j++) {
                if (j == i || !isSameItemType(mc.player.getInventory().getItem(j), desired) || satisfied[j]) continue;
                movePlayerSlot(j, i);
                return true;
            }
        }

        for (int i = 0; i < 36; i++) {
            if (satisfied[i] || mc.player.getInventory().getItem(i).isEmpty()) continue;
            for (int j = 0; j < 36; j++) {
                if (j == i || !mc.player.getInventory().getItem(j).isEmpty()) continue;
                movePlayerSlot(i, j);
                return true;
            }
        }
        return false;
    }

    // ── Container Sort ──
    private boolean performSortStep() {
        if (!(mc.player.containerMenu instanceof ChestMenu handler)) return false;
        int invSize = handler.getRowCount() * 9;
        List<ItemStack> current = new ArrayList<>();
        for (int i = 0; i < invSize; i++) current.add(handler.getSlot(i).getItem());
        List<ItemStack> sorted = new ArrayList<>(current);
        sorted.sort(new ShulkerColorComparator());
        for (int i = 0; i < invSize; i++) {
            if (!ItemStack.matches(current.get(i), sorted.get(i))) {
                for (int j = i + 1; j < invSize; j++) {
                    if (ItemStack.matches(current.get(j), sorted.get(i))) {
                        smartMove(j, i);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ── Trash ──
    private boolean performTrashStep() {
        if (mc.player.containerMenu == null) return false;
        AbstractContainerMenu handler = mc.player.containerMenu;
        int playerStart = handler.slots.size() - 36;
        for (int i = playerStart; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (!stack.isEmpty() && trashItems.getList().contains(stack.getItem())) {
                click(handler.containerId, i, 1, ClickType.THROW);
                return true;
            }
        }
        return false;
    }

    // ── Utility Helpers ──
    private void smartMove(int from, int to) {
        if (mc.gameMode == null || mc.player == null) return;
        AbstractContainerMenu handler = mc.player.containerMenu;
        if (!handler.getCarried().isEmpty()) return;
        int syncId = handler.containerId;

        ItemStack targetStack = handler.getSlot(to).getItem();
        if (targetStack.isEmpty()) {
            click(syncId, from, 0, ClickType.PICKUP);
            click(syncId, to, 0, ClickType.PICKUP);
        } else {
            click(syncId, from, 0, ClickType.PICKUP);
            click(syncId, to, 0, ClickType.PICKUP);
            click(syncId, from, 0, ClickType.PICKUP);
        }
    }

    private void quickMove(int slot) {
        if (mc.gameMode == null || mc.player == null) return;
        click(mc.player.containerMenu.containerId, slot, 0, ClickType.QUICK_MOVE);
    }

    private void click(int syncId, int slotId, int button, ClickType type) {
        mc.gameMode.handleInventoryMouseClick(syncId, slotId, button, type, mc.player);
    }

    /** Move between player-inventory indices (0-35) via the open menu. */
    private void movePlayerSlot(int fromInv, int toInv) {
        AbstractContainerMenu handler = mc.player.containerMenu;
        if (handler == null) return;
        // In the InventoryMenu the player inventory main slots are 9-44 (9-35 main, 36-44 hotbar).
        int fromId = fromInv < 9 ? 36 + fromInv : fromInv;
        int toId = toInv < 9 ? 36 + toInv : toInv;
        smartMove(fromId, toId);
    }

    private void tickAutoTrash() {
        if (autoTrash.getValue() && !isBusy()) {
            if (isTrashing) {
                if (trashTimer > 0) trashTimer--;
                else if (performTrashStep()) trashTimer = trashDelay.getValue();
                else isTrashing = false;
            }
        }
    }

    private void tickAutoDrop() {
        if (autoTrash.getValue() && mc.screen == null) {
            if (trashTimer > 0) { trashTimer--; }
            else {
                for (int i = 0; i < 36; i++) {
                    ItemStack stack = mc.player.getInventory().getItem(i);
                    if (!stack.isEmpty() && trashItems.getList().contains(stack.getItem())) {
                        int id = i < 9 ? 36 + i : i;
                        click(mc.player.inventoryMenu.containerId, id, 1, ClickType.THROW);
                        trashTimer = trashDelay.getValue();
                        break;
                    }
                }
            }
        }
    }

    private boolean isBusy() {
        return isSorting || isTrashing || isReplenishing || isRegearing || isInvSorting;
    }

    // ── Preset Save / Load ──
    private void saveInventory(int index) {
        CompoundTag nbt = new CompoundTag();
        ListTag list = new ListTag();
        for (int i = 0; i < 36; i++) encodeSlot(list, mc.player.getInventory().getItem(i), i);
        encodeSlot(list, mc.player.getOffhandItem(), 36);
        encodeSlot(list, mc.player.getItemBySlot(EquipmentSlot.FEET), 37);
        encodeSlot(list, mc.player.getItemBySlot(EquipmentSlot.LEGS), 38);
        encodeSlot(list, mc.player.getItemBySlot(EquipmentSlot.CHEST), 39);
        encodeSlot(list, mc.player.getItemBySlot(EquipmentSlot.HEAD), 40);
        nbt.put("Items", list);
        if (index == 1) preset1Data.setValue(nbt.toString());
        else preset2Data.setValue(nbt.toString());
    }

    private void encodeSlot(ListTag list, ItemStack stack, int slot) {
        if (stack.isEmpty()) return;
        CompoundTag itemTag = new CompoundTag();
        itemTag.putInt("Slot", slot);
        Tag encodedItem = ItemStack.CODEC
            .encodeStart(RegistryOps.create(NbtOps.INSTANCE, mc.level.registryAccess()), stack)
            .getOrThrow();
        itemTag.put("item", encodedItem);
        list.add(itemTag);
    }

    private static final int PRESET_SIZE = 41;

    private List<ItemStack> getPreset(int index) {
        String nbtString = (index == 1) ? preset1Data.getValue() : preset2Data.getValue();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < PRESET_SIZE; i++) items.add(ItemStack.EMPTY);
        if (nbtString == null || nbtString.isEmpty()) return items;
        try {
            CompoundTag nbt = TagParser.parseTag(nbtString);
            if (nbt.contains("Items", Tag.TAG_LIST)) {
                ListTag list = nbt.getList("Items", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag itemTag = list.getCompound(i);
                    int slot = itemTag.getInt("Slot");
                    Tag itemNbt = itemTag.get("item");
                    if (slot < PRESET_SIZE && itemNbt != null) {
                        ItemStack.CODEC
                            .parse(RegistryOps.create(NbtOps.INSTANCE, mc.level.registryAccess()), itemNbt)
                            .result()
                            .ifPresent(s -> items.set(slot, s));
                    }
                }
            }
        } catch (Exception e) {
            error("Failed to parse inventory preset: " + e.getMessage());
        }
        return items;
    }

    // ── Slot / Item Helpers ──
    private Slot getSlotAt(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        double scaledMouseX = mouseX * mc.getWindow().getGuiScaledWidth() / (double) mc.getWindow().getWidth();
        double scaledMouseY = mouseY * mc.getWindow().getGuiScaledHeight() / (double) mc.getWindow().getHeight();
        HandledScreenAccessor acc = (HandledScreenAccessor) screen;
        int guiX = acc.getGuiX();
        int guiY = acc.getGuiY();
        for (Slot slot : screen.getMenu().slots) {
            int x = guiX + slot.x, y = guiY + slot.y;
            if (scaledMouseX >= x && scaledMouseX < x + 16 && scaledMouseY >= y && scaledMouseY < y + 16) return slot;
        }
        return null;
    }

    private Slot getFocusedSlot(AbstractContainerScreen<?> screen) {
        Slot hovered = ((HandledScreenAccessor) screen).getHoveredSlot();
        if (hovered != null) return hovered;
        return getSlotAt(screen, mc.mouseHandler.xpos(), mc.mouseHandler.ypos());
    }

    private int mapInventoryToSlotId(int invIndex) {
        if (invIndex >= 0 && invIndex < 9) return 54 + invIndex;
        if (invIndex >= 9 && invIndex < 36) return 27 + (invIndex - 9);
        return -1;
    }

    private int findBestTool(BlockState state) {
        int bestSlot = -1;
        float bestSpeed = 1.0f;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) { bestSpeed = speed; bestSlot = i; }
        }
        return bestSlot;
    }

    private boolean isSameItemType(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.getItem() == b.getItem();
    }

    private boolean isLowDurabilityElytra(ItemStack stack) {
        return stack.is(Items.ELYTRA) && (stack.getMaxDamage() - stack.getDamageValue() < elytraThreshold.getValue());
    }

    private static class ShulkerColorComparator implements Comparator<ItemStack> {
        @Override
        public int compare(ItemStack o1, ItemStack o2) {
            boolean s1 = isShulker(o1), s2 = isShulker(o2);
            if (s1 && !s2) return -1;
            if (!s1 && s2) return 1;
            if (!s1) return 0;
            return Integer.compare(getColorId(o1), getColorId(o2));
        }
        private boolean isShulker(ItemStack stack) {
            return stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
        }
        private int getColorId(ItemStack stack) {
            if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock sb) {
                DyeColor c = sb.getColor();
                return c == null ? 16 : c.getId();
            }
            return 17;
        }
    }
}
