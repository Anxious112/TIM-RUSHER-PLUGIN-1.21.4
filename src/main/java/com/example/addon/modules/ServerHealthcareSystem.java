package com.example.addon.modules;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.Tim;
import com.example.addon.utils.InvUtils;

import baritone.api.BaritoneAPI;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.network.EventPacket;
import org.rusherhack.client.api.events.world.EventLoadWorld;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.setting.BindSetting;
import org.rusherhack.client.api.utils.InventoryUtils;
import org.rusherhack.core.bind.key.NullKey;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.setting.StringSetting;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.gui.screens.DeathScreen;

public class ServerHealthcareSystem extends ToggleableModule {

    // ── General ───────────────────────────────────────────────────────────────
    private final EnumSetting<OperationMode> mode = new EnumSetting<>("mode", "Changes the behavior of the module between Default and Quick Respawn modes.", OperationMode.Default);
    private final BooleanSetting autoRespawn = new BooleanSetting("auto-respawn", "Automatically respawns after death.", false);
    private final BooleanSetting autoTotem = new BooleanSetting("auto-totem", "Automatically equips a totem of undying in your offhand.", true)
        .setVisibility(() -> mode.getValue() == OperationMode.Default);

    // ── Auto Armor ────────────────────────────────────────────────────────────
    private final BooleanSetting autoArmor = new BooleanSetting("auto-armor", "Automatically equips the best armor in your inventory.", true)
        .setVisibility(() -> mode.getValue() == OperationMode.Default);
    private final EnumSetting<IgnoredArmorSlot> ignoredArmorSlot = new EnumSetting<>("ignored-armor-slot", "Ignores the selected armor slot when Auto Armor is enabled.", IgnoredArmorSlot.None)
        .setVisibility(() -> mode.getValue() == OperationMode.Default && autoArmor.getValue());
    private final EnumSetting<ChestplateMode> chestplateMode = new EnumSetting<>("chestplate-mode", "How to manage the chest slot. Smart auto-swaps based on movement.", ChestplateMode.Chestplate)
        .setVisibility(() -> mode.getValue() == OperationMode.Default && autoArmor.getValue());
    private final BindSetting switchModeKey = new BindSetting("switch-preference-key", "Cycles chestplate mode: Chestplate -> Elytra -> Smart.", NullKey.INSTANCE)
        .setVisibility(() -> mode.getValue() == OperationMode.Default && autoArmor.getValue());
    private final BindSetting smartToggleKey = new BindSetting("smart-toggle-key", "Quickly toggle smart chestplate on/off.", NullKey.INSTANCE)
        .setVisibility(() -> mode.getValue() == OperationMode.Default && autoArmor.getValue());
    private final BindSetting manualSwapKey = new BindSetting("manual-swap-key", "Manually swap between chestplate and elytra.", NullKey.INSTANCE)
        .setVisibility(() -> mode.getValue() == OperationMode.Default && autoArmor.getValue() && chestplateMode.getValue() == ChestplateMode.Smart);
    private final NumberSetting<Integer> swapCooldownMs = new NumberSetting<>("swap-cooldown-ms", "Milliseconds to wait between swaps to prevent spam.", 400, 0, 3000)
        .setVisibility(() -> mode.getValue() == OperationMode.Default && autoArmor.getValue() && chestplateMode.getValue() == ChestplateMode.Smart);
    private final NumberSetting<Integer> jumpDelayMs = new NumberSetting<>("jump-delay-ms", "Milliseconds after jumping before swapping to elytra. If you land before this expires, the swap is cancelled.", 200, 0, 2000)
        .setVisibility(() -> mode.getValue() == OperationMode.Default && autoArmor.getValue() && chestplateMode.getValue() == ChestplateMode.Smart);
    private final NumberSetting<Double> fallDistanceTrigger = new NumberSetting<>("fall-distance-trigger", "Fall distance that forces an elytra swap (even without jumping).", 3.5, 0.0, 20.0)
        .setVisibility(() -> mode.getValue() == OperationMode.Default && autoArmor.getValue() && chestplateMode.getValue() == ChestplateMode.Smart);
    private final BooleanSetting swapBackOnLand = new BooleanSetting("swap-back-on-land", "Swap back to chestplate when you touch the ground.", true)
        .setVisibility(() -> mode.getValue() == OperationMode.Default && autoArmor.getValue() && chestplateMode.getValue() == ChestplateMode.Smart);
    private final StringSetting ignoredEnchantments = new StringSetting("ignored-enchantments", "Comma-separated enchantment ids (e.g. minecraft:binding_curse). Armor with these is ignored by Auto Armor.", "minecraft:binding_curse")
        .setVisibility(() -> mode.getValue() == OperationMode.Default && autoArmor.getValue());

    // ── Auto Eat ──────────────────────────────────────────────────────────────
    private final BooleanSetting autoEat = new BooleanSetting("auto-eat", "Automatically eats food when conditions are met.", true);
    private final BooleanSetting preferEnchanted = new BooleanSetting("prefer-enchanted", "Prefer enchanted golden apples over regular ones when emergency eating.", false)
        .setVisibility(autoEat::getValue);
    private final NumberSetting<Integer> healthThreshold = new NumberSetting<>("health-threshold", "Health at which auto-eat triggers (out of 20). Set to 0 to disable health-based eating.", 10, 0, 19)
        .setVisibility(autoEat::getValue);
    private final NumberSetting<Integer> hungerLoss = new NumberSetting<>("hunger-loss", "How many TOTAL hunger points must be lost to trigger eating.", 2, 1, 20)
        .setVisibility(autoEat::getValue);
    private final BooleanSetting eatOnFire = new BooleanSetting("eat-on-fire", "Eat gapples when on fire and taking damage to gain fire resistance.", true)
        .setVisibility(autoEat::getValue);
    private final NumberSetting<Integer> eatCooldown = new NumberSetting<>("eat-cooldown", "Ticks to wait after eating before eating again.", 20, 0, 100)
        .setVisibility(autoEat::getValue);
    private final BooleanSetting swapBack = new BooleanSetting("swap-back", "Swap back to original slot after eating.", true)
        .setVisibility(autoEat::getValue);
    private final BooleanSetting pauseInCombat = new BooleanSetting("pause-in-combat", "Don't eat normal food while taking damage (gapples still work).", false)
        .setVisibility(autoEat::getValue);
    private final BooleanSetting skipIfRegen = new BooleanSetting("skip-if-regen", "Doesn't eat golden apples for hunger if you already have regeneration.", false)
        .setVisibility(autoEat::getValue);
    private final BooleanSetting pauseBaritone = new BooleanSetting("pause-baritone", "Pauses Baritone while auto-eating to prevent it from interrupting or skipping meals.", true)
        .setVisibility(autoEat::getValue);

    // ── Safety ────────────────────────────────────────────────────────────────
    private final BooleanSetting disconnectOnTotemPop = new BooleanSetting("disconnect-on-totem-pop", "Disconnects when a totem of undying is consumed.", false)
        .setVisibility(() -> mode.getValue() == OperationMode.Default);
    private final BooleanSetting disconnectOnNoTotems = new BooleanSetting("disconnect-on-no-totems", "Disconnects if totem count reaches zero.", false)
        .setVisibility(() -> mode.getValue() == OperationMode.Default);
    private final BindSetting breakBedHotkey = new BindSetting("break-bed-hotkey", "Hotkey to automatically break the nearest bed when in Quick Respawn mode.", NullKey.INSTANCE)
        .setVisibility(() -> mode.getValue() == OperationMode.QuickRespawn);

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean isEating              = false;
    private boolean ateForFire            = false;
    private boolean tookDamageWhileOnFire = false;
    private int     eatHotbarSlot         = -1;
    private int     eatOriginalHotbarSlot = -1;
    private Item    eatTargetItem         = null;
    private int     eatStartupTicks       = 0;
    private int     eatTicksRemaining     = 0;
    private float   lastHealth            = -1;
    private int     highestHungerSeen     = -1;
    private int     eatCooldownTimer      = 0;
    private boolean tookDamageRecently    = false;
    private int     damageTimer           = 0;
    private int     moveWaitTicks         = 0;

    private BlockPos bedToBreak = null;
    private int breakTickCounter = 0;
    private int bedOriginalHotbarSlot = -1;

    private long    lastSwapTime      = 0;
    private long    jumpTime          = -1;
    private boolean wasOnGround       = true;
    private boolean manualSwapRequested = false;

    private boolean wasSwitchModePressed = false;
    private boolean wasSmartTogglePressed = false;
    private boolean wasManualSwapPressed = false;
    private boolean wasBreakBedPressed = false;

    public ServerHealthcareSystem() {
        super("server-healthcare-system", "SHS — Manages health, safety, tracking, and server monitoring.", Tim.CATEGORY);
        this.registerSettings(
            mode, autoRespawn, autoTotem,
            autoArmor, ignoredArmorSlot, chestplateMode, switchModeKey, smartToggleKey, manualSwapKey,
            swapCooldownMs, jumpDelayMs, fallDistanceTrigger, swapBackOnLand, ignoredEnchantments,
            autoEat, preferEnchanted, healthThreshold, hungerLoss, eatOnFire, eatCooldown, swapBack,
            pauseInCombat, skipIfRegen, pauseBaritone,
            disconnectOnTotemPop, disconnectOnNoTotems, breakBedHotkey
        );

        try {
            BaritoneAPI.getProvider().getPrimaryBaritone().getPathingControlManager().registerProcess(EatProcess.INSTANCE);
        } catch (NoClassDefFoundError | Exception ignored) {
        }
    }

    // container helpers (menu id 0: armor 5-8, main 9-35, hotbar 36-44, offhand 45)
    private static int armorContainer(int meteorIdx) { return 8 - meteorIdx; }
    private static void swapSlots(int from, int to) { InvUtils.swapContainerSlots(from, to); }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    public void onEnable() {
        if (mc.player != null) {
            lastHealth = mc.player.getHealth();
            highestHungerSeen = mc.player.getFoodData().getFoodLevel();
        }
        resetState();
    }

    @Override
    public void onDisable() {
        stopEating();
        lastHealth = -1;
        highestHungerSeen = -1;
        if (bedOriginalHotbarSlot != -1) {
            InventoryUtils.setHotbarSlot(bedOriginalHotbarSlot);
        }
        resetState();
    }

    @Subscribe
    private void onGameJoined(EventLoadWorld event) {
        if (mc.player != null) {
            lastHealth = mc.player.getHealth();
            highestHungerSeen = mc.player.getFoodData().getFoodLevel();
        }
        resetState();
        if (autoTotem.getValue()) tickAutoTotem();
    }

    // ── Public API ────────────────────────────────────────────────────────────
    public boolean isAutoTotemEnabled() { return isToggled() && autoTotem.getValue(); }
    public void setAutoTotem(boolean enabled) { autoTotem.setValue(enabled); }
    public boolean isEating() { return isEating || moveWaitTicks > 0; }
    public boolean pauseBaritoneWhileEating() { return pauseBaritone.getValue(); }

    // ── State Helpers ─────────────────────────────────────────────────────────
    private void resetState() {
        isEating = false;
        ateForFire = false;
        tookDamageWhileOnFire = false;
        eatHotbarSlot = -1;
        eatOriginalHotbarSlot = -1;
        eatTargetItem = null;
        eatStartupTicks = 0;
        eatTicksRemaining = 0;
        eatCooldownTimer = 0;
        tookDamageRecently = false;
        damageTimer = 0;
        bedOriginalHotbarSlot = -1;
        highestHungerSeen = -1;
        moveWaitTicks = 0;

        lastSwapTime = 0;
        jumpTime = -1;
        wasOnGround = true;
        manualSwapRequested = false;
    }

    private void stopEating() {
        if (mc.options != null) mc.options.keyUse.setDown(false);
        isEating = false;
        eatHotbarSlot = -1;
        eatOriginalHotbarSlot = -1;
        eatTargetItem = null;
        eatStartupTicks = 0;
        eatTicksRemaining = 0;
        moveWaitTicks = 0;
    }

    private void finishEating() {
        if (mc.options != null) mc.options.keyUse.setDown(false);

        if (swapBack.getValue() && eatOriginalHotbarSlot != -1 && eatOriginalHotbarSlot != eatHotbarSlot) {
            InventoryUtils.setHotbarSlot(eatOriginalHotbarSlot);
        }

        isEating = false;
        eatHotbarSlot = -1;
        eatOriginalHotbarSlot = -1;
        eatTargetItem = null;
        eatStartupTicks = 0;
        eatTicksRemaining = 0;
        eatCooldownTimer = eatCooldown.getValue();
        highestHungerSeen = -1;
    }

    private void sendUseItemPacket() {
        if (mc.player == null) return;
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    // ── Tick ──────────────────────────────────────────────────────────────────
    @Subscribe
    private void onTick(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        handleHotkeys();

        switch (mode.getValue()) {
            case Default -> {
                tickAutoTotem();
                tickAutoArmor();
                tickSmartChestplate();
                tickAutoEat();
                tickHealthTracking();
                tickAutoRespawn();
            }
            case QuickRespawn -> {
                tickAutoRespawn();
                tickAutoEat();
                tickHealthTracking();
            }
        }
    }

    private void handleHotkeys() {
        if (mc.screen != null) {
            wasSwitchModePressed = switchModeKey.getValue().isKeyDown();
            wasSmartTogglePressed = smartToggleKey.getValue().isKeyDown();
            wasManualSwapPressed = manualSwapKey.getValue().isKeyDown();
            wasBreakBedPressed = breakBedHotkey.getValue().isKeyDown();
            return;
        }

        boolean p;

        p = switchModeKey.getValue().isKeyDown();
        if (p && !wasSwitchModePressed) {
            ChestplateMode next = switch (chestplateMode.getValue()) {
                case Chestplate -> ChestplateMode.Elytra;
                case Elytra -> ChestplateMode.Smart;
                case Smart -> ChestplateMode.Chestplate;
            };
            chestplateMode.setValue(next);
            sendNotification(NotificationType.INFO, "Chestplate mode set to: " + next.name());
        }
        wasSwitchModePressed = p;

        p = smartToggleKey.getValue().isKeyDown();
        if (p && !wasSmartTogglePressed) {
            if (chestplateMode.getValue() == ChestplateMode.Smart) {
                chestplateMode.setValue(ChestplateMode.Chestplate);
                sendNotification(NotificationType.INFO, "Smart Chestplate: OFF");
            } else {
                chestplateMode.setValue(ChestplateMode.Smart);
                sendNotification(NotificationType.INFO, "Smart Chestplate: ON");
            }
        }
        wasSmartTogglePressed = p;

        p = manualSwapKey.getValue().isKeyDown();
        if (p && !wasManualSwapPressed && mc.player != null) {
            manualSwapRequested = true;
        }
        wasManualSwapPressed = p;

        p = breakBedHotkey.getValue().isKeyDown();
        if (p && !wasBreakBedPressed && mode.getValue() == OperationMode.QuickRespawn) {
            BlockPos nearest = findNearestBed();
            if (nearest != null) {
                bedToBreak = nearest;
                breakTickCounter = 0;
                bedOriginalHotbarSlot = mc.player.getInventory().selected;
                sendNotification(NotificationType.INFO, "Initiating bed breaking at " + nearest.toShortString() + "...");
            } else {
                sendNotification(NotificationType.WARNING, "No bed found nearby to break.");
            }
        }
        wasBreakBedPressed = p;
    }

    private void tickHealthTracking() {
        if (mc.player == null) return;

        if (lastHealth == -1) lastHealth = mc.player.getHealth();

        float health = mc.player.getHealth();

        if (health < lastHealth) {
            tookDamageRecently = true;
            damageTimer = 40;
        }

        if (mc.player.isOnFire()) {
            if (health < lastHealth) tookDamageWhileOnFire = true;
        } else {
            ateForFire = false;
            tookDamageWhileOnFire = false;
        }

        lastHealth = health;

        if (tookDamageRecently) {
            damageTimer--;
            if (damageTimer <= 0) {
                tookDamageRecently = false;
                damageTimer = 0;
            }
        }
    }

    private void tickAutoRespawn() {
        if (autoRespawn.getValue() && mc.screen instanceof DeathScreen) {
            mc.player.respawn();
            mc.setScreen(null);
        }
    }

    private void tickQuickRespawnMode() {
        if (bedToBreak != null) {
            if (!(mc.level.getBlockState(bedToBreak).getBlock() instanceof BedBlock)) {
                sendNotification(NotificationType.INFO, "Bed at " + bedToBreak.toShortString() + " broken.");
                bedToBreak = null;
                if (bedOriginalHotbarSlot != -1) {
                    InventoryUtils.setHotbarSlot(bedOriginalHotbarSlot);
                    bedOriginalHotbarSlot = -1;
                }
                return;
            }

            if (mc.player.position().distanceTo(Vec3.atCenterOf(bedToBreak)) > 6.0) {
                sendNotification(NotificationType.WARNING, "Too far from bed, stopping breaking.");
                bedToBreak = null;
                if (bedOriginalHotbarSlot != -1) {
                    InventoryUtils.setHotbarSlot(bedOriginalHotbarSlot);
                    bedOriginalHotbarSlot = -1;
                }
                return;
            }

            int bestToolSlot = findBestTool(bedToBreak);

            if (bestToolSlot != -1 && mc.player.getInventory().selected != bestToolSlot) {
                if (bedOriginalHotbarSlot == -1) {
                    bedOriginalHotbarSlot = mc.player.getInventory().selected;
                }
                InventoryUtils.setHotbarSlot(bestToolSlot);
            }

            RusherHackAPI.getRotationManager().updateRotation(bedToBreak);
            mc.gameMode.continueDestroyBlock(bedToBreak, Direction.UP);
            mc.player.swing(InteractionHand.MAIN_HAND);

            breakTickCounter++;
        }
    }

    private void tickAutoTotem() {
        if (!autoTotem.getValue()) return;

        if (!mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
            int totem = InventoryUtils.findItem(Items.TOTEM_OF_UNDYING, true, false);
            if (totem != -1) {
                swapSlots(InvUtils.toContainerSlot(totem), InvUtils.OFFHAND_SLOT);
            } else if (disconnectOnNoTotems.getValue()) {
                disconnect("[SHS] Disconnected — no totems remaining.");
            }
        }
    }

    private void tickAutoArmor() {
        if (!autoArmor.getValue()) return;

        EquipmentSlot[] slots = { EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD };
        for (int i = 0; i < 4; i++) {
            EquipmentSlot slot = slots[i];

            if (ignoredArmorSlot.getValue() == IgnoredArmorSlot.All) continue;
            if (slot == EquipmentSlot.HEAD && ignoredArmorSlot.getValue() == IgnoredArmorSlot.Helmet) continue;
            if (slot == EquipmentSlot.CHEST && (ignoredArmorSlot.getValue() == IgnoredArmorSlot.Chestplate || chestplateMode.getValue() == ChestplateMode.Smart)) continue;
            if (slot == EquipmentSlot.LEGS && ignoredArmorSlot.getValue() == IgnoredArmorSlot.Leggings) continue;
            if (slot == EquipmentSlot.FEET && ignoredArmorSlot.getValue() == IgnoredArmorSlot.Boots) continue;

            ItemStack current = mc.player.getItemBySlot(slot);
            int bestValue = getArmorValue(current);

            if (slot == EquipmentSlot.CHEST && chestplateMode.getValue() == ChestplateMode.Elytra && current.is(Items.ELYTRA)) {
                bestValue = 1_000_000;
            }

            int bestSlot = -1;
            for (int j = 0; j < 36; j++) {
                ItemStack stack = mc.player.getInventory().getItem(j);
                if (stack.isEmpty()) continue;
                if (hasIgnoredEnchantment(stack)) continue;
                Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
                if (equippable == null || equippable.slot() != slot) continue;

                int value = getArmorValue(stack);
                if (slot == EquipmentSlot.CHEST && chestplateMode.getValue() == ChestplateMode.Elytra && stack.is(Items.ELYTRA)) {
                    value = 1_000_000;
                }

                if (value > bestValue) { bestValue = value; bestSlot = j; }
            }

            if (bestSlot != -1) swapSlots(InvUtils.toContainerSlot(bestSlot), armorContainer(i));
        }
    }

    private void tickSmartChestplate() {
        if (chestplateMode.getValue() != ChestplateMode.Smart || mc.player == null) return;

        long now = System.currentTimeMillis();
        boolean onGround = mc.player.onGround();

        if (wasOnGround && !onGround && mc.player.getY() > mc.player.yo + 0.1) {
            jumpTime = now;
        }

        if (onGround && jumpTime != -1 && (now - jumpTime) < jumpDelayMs.getValue()) {
            jumpTime = -1;
        }

        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        long timeSinceLastSwap = now - lastSwapTime;

        if (manualSwapRequested) {
            manualSwapRequested = false;
            if (timeSinceLastSwap >= swapCooldownMs.getValue()) {
                if (chest.is(Items.ELYTRA)) {
                    swapToChestplate();
                } else {
                    swapToElytra();
                }
                lastSwapTime = now;
            }
            wasOnGround = onGround;
            return;
        }

        boolean shouldElytra = false;

        if (jumpTime != -1 && (now - jumpTime) >= jumpDelayMs.getValue() && !onGround) {
            shouldElytra = true;
            jumpTime = -1;
        }

        if (fallDistanceTrigger.getValue() > 0 && mc.player.fallDistance >= fallDistanceTrigger.getValue()) {
            shouldElytra = true;
        }

        if (shouldElytra && !chest.is(Items.ELYTRA) && timeSinceLastSwap >= swapCooldownMs.getValue()) {
            swapToElytra();
            lastSwapTime = now;
        }

        if (swapBackOnLand.getValue() && onGround && !wasOnGround
                && chest.is(Items.ELYTRA)
                && timeSinceLastSwap >= swapCooldownMs.getValue()) {
            swapToChestplate();
            lastSwapTime = now;
        }

        wasOnGround = onGround;
    }

    private void swapToElytra() {
        int elytra = InventoryUtils.findItem(Items.ELYTRA, true, false);
        if (elytra != -1) {
            swapSlots(InvUtils.toContainerSlot(elytra), armorContainer(2));
            sendNotification(NotificationType.INFO, "Swapped to Elytra.");
        } else {
            sendNotification(NotificationType.WARNING, "No elytra found in inventory.");
        }
    }

    private void swapToChestplate() {
        int bestSlot = -1;
        int bestValue = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || hasIgnoredEnchantment(stack)) continue;
            Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
            if (equippable == null || equippable.slot() != EquipmentSlot.CHEST) continue;
            if (stack.is(Items.ELYTRA)) continue;

            int value = getArmorValue(stack);
            if (value > bestValue) { bestValue = value; bestSlot = i; }
        }

        if (bestSlot != -1) {
            swapSlots(InvUtils.toContainerSlot(bestSlot), armorContainer(2));
            sendNotification(NotificationType.INFO, "Swapped to Chestplate.");
        } else {
            sendNotification(NotificationType.INFO, "No chestplate available to swap to.");
        }
    }

    private void tickAutoEat() {
        if (!autoEat.getValue()) return;

        if (eatCooldownTimer > 0) {
            eatCooldownTimer--;
            return;
        }

        if (moveWaitTicks > 0) {
            moveWaitTicks--;
            if (moveWaitTicks == 0) {
                ItemStack hotbarStack = mc.player.getInventory().getItem(eatHotbarSlot);
                if (eatTargetItem != null && hotbarStack.is(eatTargetItem)) {
                    mc.player.getInventory().selected = eatHotbarSlot;
                    eatTicksRemaining = hotbarStack.getUseDuration(mc.player);
                    eatStartupTicks = 3;
                    mc.options.keyUse.setDown(true);
                    sendUseItemPacket();
                    isEating = true;
                } else {
                    stopEating();
                }
            }
            return;
        }

        if (!isEating) {
            boolean needsHealth = healthThreshold.getValue() > 0 && mc.player.getHealth() <= healthThreshold.getValue();

            int currentHunger = mc.player.getFoodData().getFoodLevel();

            if (highestHungerSeen == -1 || currentHunger > highestHungerSeen) {
                highestHungerSeen = currentHunger;
            }

            boolean needsHunger = highestHungerSeen != -1 && (highestHungerSeen - currentHunger) >= hungerLoss.getValue();

            boolean needsFireEat = eatOnFire.getValue() && mc.player.isOnFire() && tookDamageWhileOnFire && !ateForFire;
            boolean isHealthEmergency = needsHealth || needsFireEat;

            if (pauseInCombat.getValue() && tookDamageRecently && !isHealthEmergency) {
                return;
            }

            if (!needsHealth && !needsHunger && !needsFireEat) return;

            int foodSlot = findBestFood(isHealthEmergency);
            if (foodSlot == -1) return;

            ItemStack foodStack = mc.player.getInventory().getItem(foodSlot);
            eatTargetItem = foodStack.getItem();

            if (skipIfRegen.getValue() && !isHealthEmergency && (foodStack.is(Items.GOLDEN_APPLE) || foodStack.is(Items.ENCHANTED_GOLDEN_APPLE))) {
                if (mc.player.hasEffect(MobEffects.REGENERATION)) {
                    return;
                }
            }

            eatOriginalHotbarSlot = mc.player.getInventory().selected;

            if (foodSlot < 9) {
                eatHotbarSlot = foodSlot;
                mc.player.getInventory().selected = eatHotbarSlot;
                eatTicksRemaining = foodStack.getUseDuration(mc.player);
                eatStartupTicks = 3;
                mc.options.keyUse.setDown(true);
                sendUseItemPacket();
                isEating = true;
            } else {
                eatHotbarSlot = findEmptyHotbarSlot();
                if (eatHotbarSlot == -1) eatHotbarSlot = eatOriginalHotbarSlot;
                swapSlots(InvUtils.toContainerSlot(foodSlot), 36 + eatHotbarSlot);
                moveWaitTicks = 2;
            }

            if (needsFireEat) {
                ateForFire = true;
                tookDamageWhileOnFire = false;
            }

        } else {
            if (eatStartupTicks > 0) {
                eatStartupTicks--;
                mc.player.getInventory().selected = eatHotbarSlot;
                mc.options.keyUse.setDown(true);
                if (eatTicksRemaining > 0) eatTicksRemaining--;
                return;
            }

            ItemStack hotbarStack = mc.player.getInventory().getItem(eatHotbarSlot);
            boolean hotbarHasFood = eatTargetItem != null && hotbarStack.is(eatTargetItem);

            if (!hotbarHasFood) {
                finishEating();
                return;
            }

            if (mc.screen != null) {
                finishEating();
                return;
            }

            mc.player.getInventory().selected = eatHotbarSlot;
            mc.options.keyUse.setDown(true);

            if (!mc.player.isUsingItem() && hotbarHasFood) {
                sendUseItemPacket();
                eatTicksRemaining = hotbarStack.getUseDuration(mc.player);
                return;
            }

            if (eatTicksRemaining > 0) {
                eatTicksRemaining--;
            } else {
                finishEating();
            }
        }
    }

    @Subscribe
    private void onPacketReceive(EventPacket.Receive event) {
        if (mc.player == null || mc.level == null || mode.getValue() != OperationMode.Default || !disconnectOnTotemPop.getValue()) return;

        if (event.getPacket() instanceof ClientboundEntityEventPacket packet) {
            if (packet.getEventId() == 35
                    && packet.getEntity(mc.level) != null
                    && packet.getEntity(mc.level).getId() == mc.player.getId()) {
                disconnect("[SHS] Disconnected on totem pop. " + countTotems() + " totems remaining.");
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void disconnect(String reason) {
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.getConnection().disconnect(Component.literal(reason));
        }
        this.toggle();
    }

    private int findEmptyHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    private int findBestFood(boolean isHealthEmergency) {
        if (isHealthEmergency) {
            int egapple = findBestEnchantedGapple();
            if (egapple != -1) return egapple;

            int gapple = findBestGapple();
            if (gapple != -1) return gapple;

            return findBestNormalFood();
        }

        int food = findBestNormalFood();
        if (food != -1) return food;

        return findBestGapple();
    }

    private int findBestEnchantedGapple() {
        int hotbar = -1;
        int inv = -1;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.ENCHANTED_GOLDEN_APPLE)) {
                if (i < 9) { if (hotbar == -1) hotbar = i; }
                else { if (inv == -1) inv = i; }
            }
        }
        return hotbar != -1 ? hotbar : inv;
    }

    private int findBestNormalFood() {
        int bestSlot = -1;
        int bestValue = -1;

        int hotbarBest = -1;
        int hotbarBestValue = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;

            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) continue;

            int value = (int) (food.nutrition() + food.saturation() * 10);
            if (stack.getMaxStackSize() > 1) value += 100;

            if (i < 9) {
                if (value > hotbarBestValue) {
                    hotbarBestValue = value;
                    hotbarBest = i;
                }
            } else {
                if (value > bestValue) {
                    bestValue = value;
                    bestSlot = i;
                }
            }
        }

        return hotbarBest != -1 ? hotbarBest : bestSlot;
    }

    private int findBestGapple() {
        int hotbarGapple = -1;
        int hotbarEgapple = -1;
        int inventoryEgapple = -1;
        int inventoryGapple = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;

            if (stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                if (i < 9) {
                    if (hotbarEgapple == -1) hotbarEgapple = i;
                } else {
                    if (inventoryEgapple == -1) inventoryEgapple = i;
                }
            } else if (stack.is(Items.GOLDEN_APPLE)) {
                if (i < 9) {
                    if (hotbarGapple == -1) hotbarGapple = i;
                } else {
                    if (inventoryGapple == -1) inventoryGapple = i;
                }
            }
        }

        if (preferEnchanted.getValue()) {
            if (hotbarEgapple != -1) return hotbarEgapple;
            if (inventoryEgapple != -1) return inventoryEgapple;
            if (hotbarGapple != -1) return hotbarGapple;
            return inventoryGapple;
        } else {
            if (hotbarGapple != -1) return hotbarGapple;
            if (inventoryGapple != -1) return inventoryGapple;
            if (hotbarEgapple != -1) return hotbarEgapple;
            return inventoryEgapple;
        }
    }

    private List<String> ignoredEnchantIds() {
        List<String> out = new ArrayList<>();
        for (String part : ignoredEnchantments.getValue().split(",")) {
            String t = part.trim().toLowerCase();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private boolean hasIgnoredEnchantment(ItemStack stack) {
        List<String> ignored = ignoredEnchantIds();
        if (ignored.isEmpty()) return false;
        ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
        if (enchants == null) return false;
        for (Holder<Enchantment> entry : enchants.keySet()) {
            if (entry.unwrapKey().isPresent() && ignored.contains(entry.unwrapKey().get().location().toString())) return true;
        }
        return false;
    }

    private int getArmorValue(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        if (stack.getOrDefault(DataComponents.EQUIPPABLE, null) == null) return -1;

        ItemAttributeModifiers attrs = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, null);
        double armor = 0, toughness = 0;

        if (attrs != null) {
            for (ItemAttributeModifiers.Entry entry : attrs.modifiers()) {
                if (entry == null || entry.attribute() == null || entry.modifier() == null) continue;
                if (entry.attribute().unwrapKey().isEmpty()) continue;
                String id = entry.attribute().unwrapKey().get().location().toString();
                double v = entry.modifier().amount();
                if (id.equals("minecraft:armor") || id.equals("minecraft:generic.armor")) armor += v;
                else if (id.equals("minecraft:armor_toughness") || id.equals("minecraft:generic.armor_toughness")) toughness += v;
            }
        }

        double enchBonus =
              getEnchantmentLevel(stack, "minecraft:protection") * 3.0
            + getEnchantmentLevel(stack, "minecraft:fire_protection") * 1.0
            + getEnchantmentLevel(stack, "minecraft:projectile_protection") * 1.0;

        return (int) (armor * 100 + toughness * 10 + enchBonus);
    }

    private int countTotems() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) count += stack.getCount();
        }
        if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING))
            count += mc.player.getOffhandItem().getCount();
        return count;
    }

    private int getEnchantmentLevel(ItemStack stack, String id) {
        ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
        if (enchants == null) return 0;
        for (Holder<Enchantment> entry : enchants.keySet()) {
            if (entry.unwrapKey().isPresent() && entry.unwrapKey().get().location().toString().equals(id))
                return enchants.getLevel(entry);
        }
        return 0;
    }

    private BlockPos findNearestBed() {
        if (mc.player == null || mc.level == null) return null;

        BlockPos playerPos = mc.player.blockPosition();
        double minDistanceSq = Double.MAX_VALUE;
        BlockPos nearestBed = null;

        for (int x = -5; x <= 5; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -5; z <= 5; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    if (mc.level.getBlockState(pos).getBlock() instanceof BedBlock) {
                        double distanceSq = playerPos.distSqr(pos);
                        if (distanceSq < minDistanceSq) {
                            minDistanceSq = distanceSq;
                            nearestBed = pos.immutable();
                        }
                    }
                }
            }
        }
        return nearestBed;
    }

    private int findBestTool(BlockPos blockPos) {
        if (mc.player == null || mc.level == null) return -1;

        BlockState state = mc.level.getBlockState(blockPos);
        float bestSpeed = 1.0f;
        int bestSlot = -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;

            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) { bestSpeed = speed; bestSlot = i; }
        }
        return bestSlot;
    }

    // ── Enums ─────────────────────────────────────────────────────────────────
    public enum OperationMode { Default, QuickRespawn }
    public enum ChestplateMode { Chestplate, Elytra, Smart }
    public enum IgnoredArmorSlot { None, Helmet, Chestplate, Leggings, Boots, All }

    // ── Baritone Pause Process ───────────────────────────────────────────────
    public static class EatProcess implements IBaritoneProcess {
        public static final EatProcess INSTANCE = new EatProcess();

        @Override
        public boolean isActive() {
            ServerHealthcareSystem shs = (ServerHealthcareSystem) RusherHackAPI.getModuleManager().getFeature("server-healthcare-system").orElse(null);
            return shs != null && shs.isToggled() && shs.isEating() && shs.pauseBaritoneWhileEating();
        }

        @Override
        public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        @Override
        public boolean isTemporary() {
            return false;
        }

        @Override
        public void onLostControl() {}

        @Override
        public String displayName0() {
            return "SHS Auto Eat";
        }
    }
}
