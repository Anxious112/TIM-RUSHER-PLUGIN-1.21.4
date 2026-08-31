package com.example.addon.modules;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.Tim;
import com.example.addon.utils.InvUtils;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.utils.InventoryUtils;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.setting.StringSetting;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;

public class Mendbot extends ToggleableModule {
    public enum MendTarget { Elytra, Tools, Armour, All }
    public enum MendSource { Bottles, Mining, Leveling }

    public enum MiningPreset {
        All_Materials, Overworld_Set, Nether_Set, Ancient_Debris, Nether_Quartz,
        Iron, Gold, Diamond, Copper, Coal, Lapis, Redstone, Emerald;

        @Override
        public String toString() {
            return name().replace("_", " ");
        }
    }

    private enum MiningState { SEARCHING, EQUIPPING, REPAIRING, PAUSED, FINISHED }

    private static final List<String> OVERWORLD_ORES = List.of("iron_ore", "gold_ore", "copper_ore", "coal_ore", "diamond_ore", "lapis_ore", "redstone_ore", "emerald_ore");
    private static final List<String> NETHER_ORES = List.of("nether_quartz_ore", "ancient_debris", "nether_gold_ore");

    // --- Core Settings ---
    private final EnumSetting<MendSource> mendSource = new EnumSetting<>("mend-source", "How to get XP (Bottles, Mining, or Leveling).", MendSource.Bottles);
    private final EnumSetting<MendTarget> mendTarget = new EnumSetting<>("mend-target", "What to repair.", MendTarget.Elytra)
        .setVisibility(() -> mendSource.getValue() != MendSource.Leveling);

    // --- Leveling Settings ---
    private final NumberSetting<Integer> targetLevel = new NumberSetting<>("target-level", "The XP level to reach.", 30, 1, 21863)
        .setVisibility(() -> mendSource.getValue() == MendSource.Leveling);
    private final NumberSetting<Integer> minBottleSlots = new NumberSetting<>("min-bottle-slots", "Minimum hotbar slots with XP bottles before resuming. 0 = only pause when completely out.", 0, 0, 9)
        .setVisibility(() -> mendSource.getValue() == MendSource.Leveling);

    // --- Smart Mining Settings ---
    private final BooleanSetting useSmartMining = new BooleanSetting("use-smart-mining", "Automatically selects ores based on dimension (Nether/Overworld).", true)
        .setVisibility(() -> mendSource.getValue() == MendSource.Mining);
    private final EnumSetting<MiningPreset> miningPreset = new EnumSetting<>("mining-preset", "Select the mining target.", MiningPreset.All_Materials)
        .setVisibility(() -> mendSource.getValue() == MendSource.Mining && useSmartMining.getValue());

    // --- Baritone Settings ---
    private final StringSetting baritoneStartCommand = new StringSetting("baritone-start", "Manual command to run (Only used if Smart Mining is off).", "#mine nether_quartz_ore")
        .setVisibility(() -> mendSource.getValue() == MendSource.Mining && !useSmartMining.getValue());
    private final StringSetting baritonePauseCommand = new StringSetting("baritone-pause", "Command to pause Baritone before swapping items.", "#pause")
        .setVisibility(() -> mendSource.getValue() == MendSource.Mining);
    private final StringSetting baritoneResumeCommand = new StringSetting("baritone-resume", "Command to resume Baritone after swapping items.", "#resume")
        .setVisibility(() -> mendSource.getValue() == MendSource.Mining);
    private final StringSetting baritoneStopCommand = new StringSetting("baritone-stop", "Command to run when stopping Mining Mode.", "#stop")
        .setVisibility(() -> mendSource.getValue() == MendSource.Mining);
    private final NumberSetting<Integer> swapDelay = new NumberSetting<>("swap-delay", "Ticks to wait after pausing before swapping items.", 10, 0, 40)
        .setVisibility(() -> mendSource.getValue() == MendSource.Mining);
    private final NumberSetting<Integer> actionDelay = new NumberSetting<>("action-delay", "Ticks to wait after resuming and swapping (Fixes kicking).", 5, 0, 20)
        .setVisibility(() -> mendSource.getValue() == MendSource.Mining);

    // --- Safety Settings ---
    private final BooleanSetting lowHealthDisable = new BooleanSetting("low-health-disable", "Automatically disable the module if your health drops.", true);
    private final NumberSetting<Integer> healthThreshold = new NumberSetting<>("health-threshold", "The health value to disable at.", 6, 1, 20)
        .setVisibility(lowHealthDisable::getValue);
    private final BooleanSetting goldenHelmet = new BooleanSetting("golden-helmet", "Equips a golden helmet for safety (e.g. piglin bartering).", false)
        .setVisibility(() -> mendSource.getValue() == MendSource.Mining);

    // --- Bottle Settings ---
    private final NumberSetting<Integer> packetsPerBurst = new NumberSetting<>("packets-per-burst", "How many XP bottles to throw per burst.", 3, 1, 10)
        .setVisibility(() -> mendSource.getValue() == MendSource.Bottles || mendSource.getValue() == MendSource.Leveling);
    private final NumberSetting<Integer> burstDelay = new NumberSetting<>("burst-delay", "Ticks to wait between bursts.", 4, 0, 20)
        .setVisibility(() -> mendSource.getValue() == MendSource.Bottles || mendSource.getValue() == MendSource.Leveling);
    private final BooleanSetting autoDisable = new BooleanSetting("auto-disable", "Disable module when finished or out of XP.", true);

    // Fields
    private int mendTimer = 0;
    private ItemStack savedHelmet = ItemStack.EMPTY;
    private boolean isPaused = false;

    private MiningState miningState = MiningState.SEARCHING;
    private int currentRepairSlot = -1;
    private EquipmentSlot targetEquipSlot = null;
    private boolean targetIsOffhand = false;
    private int swapTimer = 0;
    private boolean startCommandSent = false;

    public Mendbot() {
        super("mendbot", "Automatically mends items using XP bottles or Mining.", Tim.CATEGORY);
        this.registerSettings(
            mendSource, mendTarget, targetLevel, minBottleSlots, useSmartMining, miningPreset,
            baritoneStartCommand, baritonePauseCommand, baritoneResumeCommand, baritoneStopCommand,
            swapDelay, actionDelay, lowHealthDisable, healthThreshold, goldenHelmet,
            packetsPerBurst, burstDelay, autoDisable
        );
    }

    // ── container-slot helpers (menu id 0: armor 5-8, main 9-35, hotbar 36-44, offhand 45) ──
    private static int armorContainer(int meteorIdx) { return 8 - meteorIdx; } // 0=boots→8 .. 3=helmet→5
    private static void swap(int fromContainer, int toContainer) { InvUtils.swapContainerSlots(fromContainer, toContainer); }

    @Override
    public void onEnable() {
        mendTimer = 0;
        startCommandSent = false;
        isPaused = false;

        if (mendSource.getValue() == MendSource.Mining) {
            miningState = MiningState.SEARCHING;
            currentRepairSlot = -1;
            targetEquipSlot = null;
            targetIsOffhand = false;
            swapTimer = 0;
        }

        if (mc.player != null) {
            savedHelmet = mc.player.getItemBySlot(EquipmentSlot.HEAD).copy();
        }
    }

    @Override
    public void onDisable() {
        if (mc.player != null) {
            if (mc.player.getItemBySlot(EquipmentSlot.HEAD).is(Items.GOLDEN_HELMET)) {
                restoreHelmet(savedHelmet);
            }
            if (mendSource.getValue() == MendSource.Mining && !baritoneStopCommand.getValue().isEmpty()) {
                mc.player.connection.sendChat(baritoneStopCommand.getValue());
            }
        }
    }

    private int findItemSlot(Item item) {
        if (mc.player == null) return -1;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            if (mc.player.getInventory().getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isHotbar(int slot) { return slot >= 0 && slot < 9; }

    private int countHotbarBottles() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.EXPERIENCE_BOTTLE)) {
                count++;
            }
        }
        return count;
    }

    @Subscribe
    private void onTick(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        if (goldenHelmet.getValue() && mendSource.getValue() == MendSource.Mining && mc.player.tickCount % 10 == 0) {
            if (!mc.player.getItemBySlot(EquipmentSlot.HEAD).is(Items.GOLDEN_HELMET)) {
                int goldHelmSlot = findItemSlot(Items.GOLDEN_HELMET);
                if (goldHelmSlot != -1) swap(InvUtils.toContainerSlot(goldHelmSlot), armorContainer(3));
            }
        }

        if (!startCommandSent && mendSource.getValue() == MendSource.Mining) {
            String cmd = useSmartMining.getValue() ? getSmartOreCommand() : baritoneStartCommand.getValue();
            if (cmd != null && !cmd.isEmpty()) {
                sendNotification(NotificationType.INFO, "Starting Baritone: " + cmd);
                mc.player.connection.sendChat(cmd);
                startCommandSent = true;
            }
        }

        if (lowHealthDisable.getValue() && mc.player.getHealth() <= healthThreshold.getValue()) {
            sendNotification(NotificationType.ERROR, "Low health detected! Disabling...");
            toggle();
            return;
        }

        if (mendSource.getValue() == MendSource.Leveling) {
            handleLeveling();
            return;
        }

        if (mendSource.getValue() == MendSource.Mining) {
            handleMiningMendingStateMachine();
            return;
        }

        if (mendTimer > 0) { mendTimer--; return; }

        int xpSlot = findItemSlot(Items.EXPERIENCE_BOTTLE);
        if (xpSlot == -1) {
            sendNotification(NotificationType.INFO, "No more XP bottles — stopping.");
            if (autoDisable.getValue()) toggle();
            return;
        }

        boolean finished = false;
        switch (mendTarget.getValue()) {
            case Elytra -> finished = !handleElytraMending();
            case Tools -> finished = !handleToolMending();
            case Armour -> finished = !handleArmourMending();
            case All -> finished = !handleElytraMending() && !handleToolMending() && !handleArmourMending();
        }

        if (finished) {
            sendNotification(NotificationType.INFO, "Mending complete.");
            if (autoDisable.getValue()) toggle();
        }
    }

    // --- Leveling Logic ---
    private void handleLeveling() {
        if (mc.player.experienceLevel >= targetLevel.getValue()) {
            sendNotification(NotificationType.INFO, "Target level " + targetLevel.getValue() + " reached!");
            if (autoDisable.getValue()) toggle();
            return;
        }

        int hotbarBottles = countHotbarBottles();
        int anyBottleSlot = findItemSlot(Items.EXPERIENCE_BOTTLE);

        boolean shouldPause = false;
        if (anyBottleSlot == -1) {
            shouldPause = true;
        } else if (minBottleSlots.getValue() > 0 && hotbarBottles < minBottleSlots.getValue()) {
            shouldPause = true;
        }

        if (shouldPause) {
            if (!isPaused) {
                if (anyBottleSlot == -1) {
                    sendNotification(NotificationType.WARNING, "No XP bottles detected — pausing...");
                } else {
                    sendNotification(NotificationType.WARNING, "Waiting for XP bottles... (" + hotbarBottles + "/" + minBottleSlots.getValue() + " hotbar slots)");
                }
                isPaused = true;
            }
            return;
        }

        if (isPaused) {
            sendNotification(NotificationType.INFO, "XP bottles available — resuming.");
            isPaused = false;
        }

        if (mendTimer > 0) { mendTimer--; return; }

        throwXpBottles();
    }

    // --- Smart Mining Logic ---
    private String getSmartOreCommand() {
        StringBuilder sb = new StringBuilder("#mine ");
        boolean first = true;

        MiningPreset preset = miningPreset.getValue();
        List<String> targetOres;

        switch (preset) {
            case All_Materials -> {
                targetOres = new ArrayList<>(OVERWORLD_ORES);
                targetOres.addAll(NETHER_ORES);
            }
            case Overworld_Set -> targetOres = OVERWORLD_ORES;
            case Nether_Set -> targetOres = NETHER_ORES;
            default -> {
                String oreName = getOreName(preset);
                if (oreName != null) {
                    sb.append(oreName);
                    return sb.toString();
                } else {
                    return "#mine";
                }
            }
        }

        for (String ore : targetOres) {
            if (!first) sb.append(",");
            sb.append(ore);
            first = false;
        }

        return sb.toString();
    }

    private String getOreName(MiningPreset preset) {
        return switch (preset) {
            case Iron -> "iron_ore";
            case Gold -> "gold_ore";
            case Copper -> "copper_ore";
            case Coal -> "coal_ore";
            case Diamond -> "diamond_ore";
            case Lapis -> "lapis_ore";
            case Redstone -> "redstone_ore";
            case Emerald -> "emerald_ore";
            case Ancient_Debris -> "ancient_debris";
            case Nether_Quartz -> "nether_quartz_ore";
            default -> null;
        };
    }

    // --- State Machine ---
    private void handleMiningMendingStateMachine() {
        switch (miningState) {
            case SEARCHING -> {
                int foundSlot = findNextDamagedItem();
                if (foundSlot == -1) {
                    if (baritoneStopCommand.getValue() != null && !baritoneStopCommand.getValue().isEmpty()) {
                        sendNotification(NotificationType.INFO, "All items repaired. Stopping Baritone.");
                        mc.player.connection.sendChat(baritoneStopCommand.getValue());
                    }
                    miningState = MiningState.FINISHED;
                    if (autoDisable.getValue()) toggle();
                    return;
                }

                ItemStack stack = mc.player.getInventory().getItem(foundSlot);
                if (stack.isEmpty()) {
                    return;
                }

                targetEquipSlot = getTargetEquipmentSlot(stack);
                targetIsOffhand = (targetEquipSlot == null && isTool(stack));

                if (targetEquipSlot == EquipmentSlot.HEAD && goldenHelmet.getValue()) {
                    return;
                }

                equipItem(foundSlot, targetEquipSlot, targetIsOffhand);
                currentRepairSlot = foundSlot;
                miningState = MiningState.EQUIPPING;
                swapTimer = 4;
            }
            case EQUIPPING -> {
                if (swapTimer > 0) {
                    swapTimer--;
                    return;
                }

                ItemStack equipped = targetIsOffhand ?
                    mc.player.getOffhandItem() :
                    (targetEquipSlot != null ? mc.player.getItemBySlot(targetEquipSlot) : ItemStack.EMPTY);

                if (!equipped.isEmpty() && equipped.isDamaged()) {
                    miningState = MiningState.REPAIRING;
                    sendNotification(NotificationType.INFO, "Repairing: " + equipped.getHoverName().getString());
                } else if (equipped.isEmpty()) {
                    miningState = MiningState.SEARCHING;
                } else {
                    miningState = MiningState.REPAIRING;
                }
            }
            case REPAIRING -> {
                ItemStack equipped = targetIsOffhand ?
                    mc.player.getOffhandItem() :
                    (targetEquipSlot != null ? mc.player.getItemBySlot(targetEquipSlot) : ItemStack.EMPTY);

                if (equipped.isEmpty() || !equipped.isDamaged()) {
                    sendNotification(NotificationType.INFO, "Item repaired. Pausing to swap.");
                    if (!baritonePauseCommand.getValue().isEmpty()) {
                        mc.player.connection.sendChat(baritonePauseCommand.getValue());
                    }
                    swapTimer = swapDelay.getValue();
                    miningState = MiningState.PAUSED;
                }
            }
            case PAUSED -> {
                if (swapTimer > 0) {
                    swapTimer--;
                    return;
                }

                ItemStack equipped = targetIsOffhand ?
                    mc.player.getOffhandItem() :
                    (targetEquipSlot != null ? mc.player.getItemBySlot(targetEquipSlot) : ItemStack.EMPTY);

                if (!equipped.isEmpty()) {
                    int emptySlot = mc.player.getInventory().getFreeSlot();
                    if (targetIsOffhand) {
                        if (emptySlot != -1) swap(InvUtils.OFFHAND_SLOT, InvUtils.toContainerSlot(emptySlot));
                        else swap(InvUtils.OFFHAND_SLOT, 36);
                    } else if (targetEquipSlot != null) {
                        int armorIdx = armorSlotIndex(targetEquipSlot);
                        if (emptySlot != -1) swap(armorContainer(armorIdx), InvUtils.toContainerSlot(emptySlot));
                        else swap(armorContainer(armorIdx), 36);
                    }
                }

                currentRepairSlot = -1;
                targetEquipSlot = null;
                targetIsOffhand = false;

                if (!baritoneResumeCommand.getValue().isEmpty()) {
                    mc.player.connection.sendChat(baritoneResumeCommand.getValue());
                }
                miningState = MiningState.SEARCHING;
                swapTimer = actionDelay.getValue();
            }
            case FINISHED -> { /* Do nothing */ }
        }
    }

    private int findNextDamagedItem() {
        boolean doElytra = (mendTarget.getValue() == MendTarget.All || mendTarget.getValue() == MendTarget.Elytra);
        boolean doTools = (mendTarget.getValue() == MendTarget.All || mendTarget.getValue() == MendTarget.Tools);
        boolean doArmour = (mendTarget.getValue() == MendTarget.All || mendTarget.getValue() == MendTarget.Armour);

        if (mc.player == null) return -1;

        if (doElytra) {
            int slot = findDamagedItem(stack -> stack.is(Items.ELYTRA));
            if (slot != -1) return slot;
        }

        if (doTools) {
            int slot = findDamagedItem(this::isTool);
            if (slot != -1) return slot;
        }

        if (doArmour) {
            int slot = findDamagedItem(stack -> {
                if (stack.getItem() instanceof ArmorItem && !stack.is(Items.ELYTRA)) {
                    if (goldenHelmet.getValue()) {
                        Equippable eq = stack.get(DataComponents.EQUIPPABLE);
                        return eq != null && eq.slot() != EquipmentSlot.HEAD;
                    }
                    return true;
                }
                return false;
            });
            if (slot != -1) return slot;
        }

        return -1;
    }

    private int findDamagedItem(java.util.function.Predicate<ItemStack> predicate) {
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (predicate.test(stack) && stack.isDamaged()) {
                return i;
            }
        }
        return -1;
    }

    private EquipmentSlot getTargetEquipmentSlot(ItemStack stack) {
        if (stack.is(Items.ELYTRA)) {
            return EquipmentSlot.CHEST;
        }
        if (stack.getItem() instanceof ArmorItem) {
            Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
            if (equippable != null) {
                return equippable.slot();
            }
        }
        return null;
    }

    private int armorSlotIndex(EquipmentSlot slot) {
        return switch (slot) {
            case FEET -> 0;
            case LEGS -> 1;
            case CHEST -> 2;
            case HEAD -> 3;
            default -> -1;
        };
    }

    private void equipItem(int fromSlot, EquipmentSlot slot, boolean offhand) {
        if (offhand) {
            ItemStack offHand = mc.player.getOffhandItem();
            if (!offHand.isEmpty()) {
                int emptySlot = mc.player.getInventory().getFreeSlot();
                if (emptySlot != -1) swap(InvUtils.OFFHAND_SLOT, InvUtils.toContainerSlot(emptySlot));
                else swap(InvUtils.OFFHAND_SLOT, 36);
            }
            swap(InvUtils.toContainerSlot(fromSlot), InvUtils.OFFHAND_SLOT);
        } else if (slot != null) {
            int armorIdx = armorSlotIndex(slot);
            ItemStack currentArmor = mc.player.getItemBySlot(slot);
            if (!currentArmor.isEmpty()) {
                int emptySlot = mc.player.getInventory().getFreeSlot();
                if (emptySlot != -1) swap(armorContainer(armorIdx), InvUtils.toContainerSlot(emptySlot));
                else swap(armorContainer(armorIdx), 36);
            }
            swap(InvUtils.toContainerSlot(fromSlot), armorContainer(armorIdx));
        }
    }

    // --- Safety ---
    private void restoreHelmet(ItemStack original) {
        ItemStack current = mc.player.getItemBySlot(EquipmentSlot.HEAD);
        if (ItemStack.isSameItemSameComponents(current, original)) return;
        if (!current.isEmpty()) {
            int empty = mc.player.getInventory().getFreeSlot();
            if (empty != -1) swap(armorContainer(3), InvUtils.toContainerSlot(empty));
            else {
                int same = findItemSlot(current.getItem());
                if (same != -1 && !isHotbar(same)) swap(armorContainer(3), InvUtils.toContainerSlot(same));
                else swap(armorContainer(3), 36);
            }
        }
        if (!original.isEmpty()) {
            int saved = -1;
            for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
                if (ItemStack.isSameItemSameComponents(mc.player.getInventory().getItem(i), original)) { saved = i; break; }
            }
            if (saved != -1) swap(InvUtils.toContainerSlot(saved), armorContainer(3));
        }
    }

    // --- Bottle Logic ---
    private boolean handleElytraMending() {
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA) || !chest.isDamaged()) {
            int elytra = findDamagedItem(stack -> stack.is(Items.ELYTRA));
            if (elytra != -1) { swap(InvUtils.toContainerSlot(elytra), armorContainer(2)); return true; }
            else return false;
        }
        throwXpBottles();
        return true;
    }

    private boolean handleToolMending() {
        ItemStack offHand = mc.player.getOffhandItem();
        if (isTool(offHand)) {
            if (offHand.isDamaged()) { throwXpBottles(); return true; }
            else { int slot = mc.player.getInventory().getFreeSlot(); if (slot != -1) { swap(InvUtils.OFFHAND_SLOT, InvUtils.toContainerSlot(slot)); return true; } }
        }
        int damaged = findDamagedItem(this::isTool);
        if (damaged != -1) { swap(InvUtils.toContainerSlot(damaged), InvUtils.OFFHAND_SLOT); return true; }
        return false;
    }

    private boolean handleArmourMending() {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (stack.getItem() instanceof ArmorItem && !stack.is(Items.ELYTRA) && stack.isDamaged()) { throwXpBottles(); return true; }
        }
        int damaged = findDamagedItem(stack -> stack.getItem() instanceof ArmorItem && !stack.is(Items.ELYTRA));
        if (damaged != -1) {
            ItemStack stack = mc.player.getInventory().getItem(damaged);
            Equippable eq = stack.get(DataComponents.EQUIPPABLE);
            if (eq != null) {
                EquipmentSlot s = eq.slot();
                ItemStack eqd = mc.player.getItemBySlot(s);
                if (eqd.isEmpty() || !eqd.isDamaged()) { swap(InvUtils.toContainerSlot(damaged), armorContainer(armorSlotIndex(s))); return true; }
            }
        }
        return false;
    }

    private boolean isTool(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Item i = stack.getItem();
        return i instanceof net.minecraft.world.item.PickaxeItem || i instanceof net.minecraft.world.item.SwordItem
            || i instanceof net.minecraft.world.item.AxeItem || i instanceof net.minecraft.world.item.ShovelItem
            || i == Items.BOW || i == Items.FLINT_AND_STEEL || i == Items.SHIELD || i == Items.TRIDENT || i == Items.FISHING_ROD;
    }

    private void throwXpBottles() {
        float yaw = mc.player.getYRot() + (float) (Math.random() * 0.2 - 0.1);
        float pitch = 90 + (float) (Math.random() * 0.2 - 0.1);
        RusherHackAPI.getRotationManager().updateRotation(yaw, pitch);

        int xp = findItemSlot(Items.EXPERIENCE_BOTTLE);
        if (xp != -1) {
            if (isHotbar(xp)) {
                int prevSel = InventoryUtils.getSelectedHotbarSlot();
                InventoryUtils.setHotbarSlot(xp);
                for (int i = 0; i < packetsPerBurst.getValue(); i++) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                InventoryUtils.setHotbarSlot(prevSel);
            } else {
                int empty = mc.player.getInventory().getFreeSlot();
                if (empty != -1 && isHotbar(empty)) {
                    swap(InvUtils.toContainerSlot(xp), 36 + empty);
                    int prevSel = InventoryUtils.getSelectedHotbarSlot();
                    InventoryUtils.setHotbarSlot(empty);
                    for (int i = 0; i < packetsPerBurst.getValue(); i++) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    InventoryUtils.setHotbarSlot(prevSel);
                    swap(36 + empty, InvUtils.toContainerSlot(xp));
                } else {
                    int prev = mc.player.getInventory().selected;
                    swap(InvUtils.toContainerSlot(xp), 36 + prev);
                    for (int i = 0; i < packetsPerBurst.getValue(); i++) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    swap(36 + prev, InvUtils.toContainerSlot(xp));
                }
            }
        }
        mendTimer = burstDelay.getValue();
    }
}
