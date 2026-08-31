package com.example.addon.modules;

import org.lwjgl.glfw.GLFW;

import com.example.addon.Tim;
import com.example.addon.utils.InvUtils;

import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.setting.BindSetting;
import org.rusherhack.client.api.utils.InventoryUtils;
import org.rusherhack.core.bind.key.NullKey;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

public class ElytraAssistant extends ToggleableModule {

    public enum MiddleClickAction {
        None,
        Rocket,
        Pearl
    }

    public enum WarningSound {
        Anvil,
        WitherSpawn,
        CreeperPrimed,
        ExperienceOrb,
        Bell,
        NoteBassDrum;

        public SoundEvent toSoundEvent() {
            return switch (this) {
                case Anvil         -> SoundEvents.ANVIL_LAND;
                case WitherSpawn   -> SoundEvents.WITHER_SPAWN;
                case CreeperPrimed -> SoundEvents.CREEPER_PRIMED;
                case ExperienceOrb -> SoundEvents.EXPERIENCE_ORB_PICKUP;
                case Bell          -> SoundEvents.BELL_BLOCK;
                case NoteBassDrum  -> SoundEvents.NOTE_BLOCK_BASEDRUM.value();
            };
        }
    }

    private final BooleanSetting autoReplace = new BooleanSetting("auto-replace", "Automatically replace elytra when durability is low.", true);

    private final NumberSetting<Integer> durabilityThreshold = new NumberSetting<>("durability-threshold", "Minimum durability before replacing.", 10, 1, 100)
        .setVisibility(autoReplace::getValue);

    private final EnumSetting<WarningSound> warningSoundType = new EnumSetting<>("warning-sound", "Sound played when no replacement elytra is available.", WarningSound.Anvil)
        .setVisibility(autoReplace::getValue);

    private final NumberSetting<Double> warningSoundVolume = new NumberSetting<>("warning-volume", "Volume of the warning sound.", 1.0, 0.1, 2.0)
        .setVisibility(autoReplace::getValue);

    private final BindSetting toggleKey = new BindSetting("toggle-key", "Key to toggle auto replace.", NullKey.INSTANCE);

    private final EnumSetting<MiddleClickAction> middleClickAction = new EnumSetting<>("action", "Item to use when middle clicking.", MiddleClickAction.None);

    public final BooleanSetting silentRocket = new BooleanSetting("silent-rocket", "Prevents hand swing animation when using rockets.", true);

    private final BooleanSetting rocketReplenishEnabled = new BooleanSetting("rocket-replenish", "Enables the rocket replenish keybind.", true);

    private final BooleanSetting useSelectedSlot = new BooleanSetting("use-selected-slot", "Replenishes the currently selected hotbar slot instead of a specific one.", false)
        .setVisibility(rocketReplenishEnabled::getValue);

    private final NumberSetting<Integer> targetSlot = new NumberSetting<>("target-slot", "The specific hotbar slot to replenish (1-9).", 8, 1, 9)
        .setVisibility(() -> rocketReplenishEnabled.getValue() && !useSelectedSlot.getValue());

    private final BindSetting rocketReplenishKey = new BindSetting("replenish-key", "Replenishes the target hotbar slot's item to its max stack size from the main inventory.", NullKey.INSTANCE);

    public final BooleanSetting antiAfk = new BooleanSetting("anti-afk", "Prevents AFK kick by swinging hand periodically.", false);

    private static final int AFK_INTERVAL_TICKS = 300;
    private static final int AFK_RANDOMNESS_TICKS = 120;
    private static final int MIDDLE_CLICK_COOLDOWN = 5;

    private boolean noReplacementWarned = false;
    private boolean wasMiddlePressed = false;
    private int middleClickCooldown = 0;
    private int antiAfkTimer = 0;
    private boolean wasReplenishPressed = false;

    public ElytraAssistant() {
        super("elytra-assistant", "Smart elytra and rocket management.", Tim.CATEGORY);
        this.registerSettings(
            autoReplace, durabilityThreshold, warningSoundType, warningSoundVolume, toggleKey,
            middleClickAction, silentRocket,
            rocketReplenishEnabled, useSelectedSlot, targetSlot, rocketReplenishKey,
            antiAfk
        );
    }

    @Override
    public void onEnable() {
        noReplacementWarned = false;
        wasMiddlePressed = false;
        middleClickCooldown = 0;
        antiAfkTimer = 0;
        wasReplenishPressed = false;
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        handleToggleKey();
        handleMiddleClick();
        handleReplenishKey();
        handleAntiAfk();
        handleAutoReplace();
    }

    private void handleToggleKey() {
        boolean pressed = toggleKey.getValue().isKeyDown();
        // Edge-triggered handling isn't tracked separately here since this key is rarely spammed;
        // acceptable simplification vs. the original's dedicated action-callback keybind.
        if (pressed && mc.screen == null) {
            boolean enabled = !autoReplace.getValue();
            autoReplace.setValue(enabled);
            this.sendNotification(NotificationType.INFO, "Auto Replace " + (enabled ? "enabled" : "disabled") + ".");
        }
    }

    private void handleReplenishKey() {
        boolean pressed = rocketReplenishKey.getValue().isKeyDown();
        if (pressed && !wasReplenishPressed && mc.screen == null && rocketReplenishEnabled.getValue()) {
            handleRocketReplenish();
        }
        wasReplenishPressed = pressed;
    }

    private void handleAutoReplace() {
        if (!autoReplace.getValue()) return;
        // TODO: skip when Mendbot is active, once Mendbot is ported (RusherHack getFeature("mendbot")).

        ItemStack chestplate = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chestplate.is(Items.ELYTRA)) return;

        int remainingDurability = getRemainingDurability(chestplate);
        if (remainingDurability > durabilityThreshold.getValue()) {
            noReplacementWarned = false;
            return;
        }

        int replacementSlot = findBestReplacementElytra();
        if (replacementSlot != -1) {
            equipElytraSilently(replacementSlot);
            this.sendNotification(NotificationType.WARNING, "Elytra durability low! Replaced with fresh elytra.");
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            noReplacementWarned = false;
        } else if (!noReplacementWarned) {
            this.sendNotification(NotificationType.WARNING, "No replacement elytra available!");
            playWarningSound();
            noReplacementWarned = true;
        }
    }

    private int findBestReplacementElytra() {
        int bestSlot = -1;
        int bestDurability = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!isUsableElytra(stack)) continue;

            int durability = getRemainingDurability(stack);
            if (durability > bestDurability) {
                bestSlot = i;
                bestDurability = durability;
            }
        }

        return bestSlot;
    }

    private boolean isUsableElytra(ItemStack stack) {
        return !stack.isEmpty()
            && stack.is(Items.ELYTRA)
            && getRemainingDurability(stack) > durabilityThreshold.getValue();
    }

    private int getRemainingDurability(ItemStack elytra) {
        return elytra.getMaxDamage() - elytra.getDamageValue();
    }

    private void handleMiddleClick() {
        if (middleClickCooldown > 0) middleClickCooldown--;

        if (middleClickAction.getValue() == MiddleClickAction.None) return;
        if (mc.screen != null) return;

        boolean isPressed = GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;

        if (isPressed && !wasMiddlePressed && middleClickCooldown == 0) {
            executeMiddleClickAction();
            wasMiddlePressed = true;
            middleClickCooldown = MIDDLE_CLICK_COOLDOWN;
        } else if (!isPressed) {
            wasMiddlePressed = false;
        }
    }

    private void executeMiddleClickAction() {
        MiddleClickAction action = middleClickAction.getValue();

        if (mc.player.onGround()) return;

        Item target = switch (action) {
            case Rocket -> Items.FIREWORK_ROCKET;
            case Pearl  -> Items.ENDER_PEARL;
            default     -> null;
        };

        if (target == null) return;
        useItemFromHotbar(target);
    }

    private void useItemFromHotbar(Item item) {
        int slot = InventoryUtils.findItemHotbar(item);
        if (slot == -1) return;

        int previousSlot = mc.player.getInventory().selected;
        mc.player.getInventory().selected = slot;
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.getInventory().selected = previousSlot;
    }

    private void handleRocketReplenish() {
        int selectedSlot = useSelectedSlot.getValue()
            ? mc.player.getInventory().selected
            : targetSlot.getValue() - 1;

        ItemStack targetStack = mc.player.getInventory().getItem(selectedSlot);
        Item targetItem = Items.FIREWORK_ROCKET;

        if (!targetStack.isEmpty() && targetStack.getItem() != targetItem) {
            this.sendNotification(NotificationType.INFO, "Target slot has a different item — cannot replenish.");
            return;
        }

        if (!mc.player.containerMenu.getCarried().isEmpty()) {
            this.sendNotification(NotificationType.INFO, "Cursor has an item — cannot replenish right now.");
            return;
        }

        int maxCount = targetItem.getDefaultMaxStackSize();
        int currentCount = targetStack.getCount();
        int needed = maxCount - currentCount;

        if (needed <= 0) {
            this.sendNotification(NotificationType.INFO, "Stack is already full (" + maxCount + ").");
            return;
        }

        int hotbarContainerSlot = InvUtils.toContainerSlot(selectedSlot);

        for (int i = 9; i < 36 && needed > 0; i++) {
            ItemStack sourceStack = mc.player.getInventory().getItem(i);
            if (sourceStack.isEmpty()) continue;
            if (sourceStack.getItem() != targetItem) continue;

            int available = sourceStack.getCount();
            InvUtils.moveToSlot(i, hotbarContainerSlot);

            needed -= Math.min(needed, available);
        }

        int finalCount = maxCount - needed;

        if (needed > 0) {
            this.sendNotification(NotificationType.INFO, "Replenished " + new ItemStack(targetItem).getHoverName().getString()
                + " to " + finalCount + " (not enough items in inventory).");
        } else {
            this.sendNotification(NotificationType.INFO, "Replenished " + new ItemStack(targetItem).getHoverName().getString()
                + " to " + maxCount + ".");
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }

    private void handleAntiAfk() {
        if (!antiAfk.getValue()) return;

        if (antiAfkTimer <= 0) {
            mc.player.swing(InteractionHand.MAIN_HAND);
            antiAfkTimer = calculateNextSwingDelay();
        } else {
            antiAfkTimer--;
        }
    }

    private int calculateNextSwingDelay() {
        int base = AFK_INTERVAL_TICKS;
        int variance = (int) (Math.random() * AFK_RANDOMNESS_TICKS * 2) - AFK_RANDOMNESS_TICKS;
        return Math.max(1, base + variance);
    }

    private void equipElytraSilently(int inventorySlot) {
        InvUtils.moveToSlot(InvUtils.toContainerSlot(inventorySlot), InvUtils.ARMOR_CHESTPLATE_SLOT);
    }

    private void playWarningSound() {
        mc.player.playSound(
            warningSoundType.getValue().toSoundEvent(),
            warningSoundVolume.getValue().floatValue(),
            1.0f
        );
    }

    public boolean shouldPreventRocketUse() {
        return isToggled() && mc.player.onGround();
    }

    public boolean shouldSilentRocket() {
        return isToggled() && silentRocket.getValue();
    }

    public boolean isAutoReplaceEnabled() {
        return isToggled() && autoReplace.getValue();
    }
}
