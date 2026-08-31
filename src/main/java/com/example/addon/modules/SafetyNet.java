package com.example.addon.modules;

import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.setting.BindSetting;
import org.rusherhack.core.bind.key.NullKey;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import com.example.addon.Tim;

public class SafetyNet extends ToggleableModule {

    public enum DimensionMode {
        Overworld, End, Both
    }

    public enum WarnSound {
        Pling, Bell, Anvil, Basedrum, Chime, Hat;

        public SoundEvent getSoundEvent() {
            return switch (this) {
                case Pling    -> SoundEvents.NOTE_BLOCK_PLING.value();
                case Bell     -> SoundEvents.NOTE_BLOCK_BELL.value();
                case Anvil    -> SoundEvents.ANVIL_LAND;
                case Basedrum -> SoundEvents.NOTE_BLOCK_BASEDRUM.value();
                case Chime    -> SoundEvents.NOTE_BLOCK_CHIME.value();
                case Hat      -> SoundEvents.NOTE_BLOCK_HAT.value();
            };
        }
    }

    private final EnumSetting<DimensionMode> dimension = new EnumSetting<>("dimension", "Which dimension(s) to protect you in.", DimensionMode.End);

    private final BooleanSetting perDimensionThresholds = new BooleanSetting("per-dimension-thresholds",
        "Use separate warn and disconnect Y levels for the Overworld and End instead of shared values.", false)
        .setVisibility(() -> dimension.getValue() == DimensionMode.Both);

    private final BooleanSetting warnEnabled = new BooleanSetting("warn-enabled", "Play a sound and show a title warning when below the warning Y level.", true);

    private final NumberSetting<Integer> warnY = new NumberSetting<>("warn-y-level",
        "Y level at which the warning ping triggers. Used when sharing thresholds for both dimensions.", 0, -128, 320)
        .setVisibility(() -> dimension.getValue() == DimensionMode.Both && !perDimensionThresholds.getValue());

    private final NumberSetting<Integer> warnInterval = new NumberSetting<>("warn-interval",
        "How often the warning repeats while below the Y level, in ticks (20 = 1 second). Set to 0 to warn only once.", 40, 0, 200);

    private final EnumSetting<WarnSound> warnSound = new EnumSetting<>("warn-sound", "The sound played when the warning triggers.", WarnSound.Pling);

    private final NumberSetting<Double> warnVolume = new NumberSetting<>("warn-volume", "Volume of the warning ping sound (0.0 = silent, 1.0 = full volume).", 1.0, 0.0, 1.0);

    private final BooleanSetting disconnectEnabled = new BooleanSetting("disconnect-enabled", "Automatically disconnect when below the disconnect Y level.", true);

    private final NumberSetting<Integer> disconnectY = new NumberSetting<>("disconnect-y-level",
        "Y level at which auto-disconnect triggers. Used when sharing thresholds for both dimensions.", -10, -128, 320)
        .setVisibility(() -> dimension.getValue() == DimensionMode.Both && !perDimensionThresholds.getValue());

    private final NumberSetting<Integer> overworldWarnY = new NumberSetting<>("overworld-warn-y-level", "Overworld Y level at which the warning triggers.", -60, -128, 320)
        .setVisibility(() -> dimension.getValue() == DimensionMode.Overworld || (dimension.getValue() == DimensionMode.Both && perDimensionThresholds.getValue()));

    private final NumberSetting<Integer> overworldDisconnectY = new NumberSetting<>("overworld-disconnect-y-level", "Overworld Y level at which auto-disconnect triggers.", -70, -128, 320)
        .setVisibility(() -> dimension.getValue() == DimensionMode.Overworld || (dimension.getValue() == DimensionMode.Both && perDimensionThresholds.getValue()));

    private final NumberSetting<Integer> endWarnY = new NumberSetting<>("end-warn-y-level", "End Y level at which the warning triggers.", 0, -128, 320)
        .setVisibility(() -> dimension.getValue() == DimensionMode.End || (dimension.getValue() == DimensionMode.Both && perDimensionThresholds.getValue()));

    private final NumberSetting<Integer> endDisconnectY = new NumberSetting<>("end-disconnect-y-level", "End Y level at which auto-disconnect triggers.", -10, -128, 320)
        .setVisibility(() -> dimension.getValue() == DimensionMode.End || (dimension.getValue() == DimensionMode.Both && perDimensionThresholds.getValue()));

    private final BooleanSetting graceEnabled = new BooleanSetting("grace-enabled",
        "Wait a set number of ticks below the threshold before firing warnings or disconnect. Prevents false triggers from lag spikes or brief knockback.", false);

    private final NumberSetting<Integer> graceTicks = new NumberSetting<>("grace-ticks",
        "How many consecutive ticks the player must be below the threshold before actions fire (20 = 1 second).", 10, 1, 100)
        .setVisibility(graceEnabled::getValue);

    private final BindSetting chorusEscapeKey = new BindSetting("chorus-escape-key",
        "Eats a chorus fruit to escape the void. Ignores warnings/disconnects while active and disables module on landing.", NullKey.INSTANCE);

    private boolean hasDisconnected;
    private int     warnTickCounter;
    private int     graceTickCounter;

    private boolean chorusEscapeActive;
    private boolean hasTriggeredEat;
    private boolean wasChorusPressed;

    public SafetyNet() {
        super("safety-net", "Protects you from the void by warning, disconnecting, or chorus teleporting at low Y levels.", Tim.CATEGORY);
        this.registerSettings(
            dimension, perDimensionThresholds,
            warnEnabled, warnY, warnInterval, warnSound, warnVolume,
            disconnectEnabled, disconnectY,
            overworldWarnY, overworldDisconnectY, endWarnY, endDisconnectY,
            graceEnabled, graceTicks, chorusEscapeKey
        );
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        if (mc.options != null) {
            mc.options.keyUse.setDown(false);
        }
        resetState();
    }

    private void resetState() {
        hasDisconnected    = false;
        warnTickCounter    = 0;
        graceTickCounter   = 0;
        chorusEscapeActive = false;
        hasTriggeredEat    = false;
        wasChorusPressed   = false;
    }

    private void resetCounters() {
        warnTickCounter  = 0;
        graceTickCounter = 0;
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        handleChorusEscapeKeybind();
        if (chorusEscapeActive) return;

        if (!isInValidDimension()) {
            resetCounters();
            return;
        }

        int effectiveWarnY = getEffectiveWarnY();
        int effectiveDisconnectY = getEffectiveDisconnectY();

        validateThresholds(effectiveWarnY, effectiveDisconnectY);

        double playerY = mc.player.getY();
        boolean belowDisconnect = disconnectEnabled.getValue() && playerY < effectiveDisconnectY;
        boolean belowWarn = warnEnabled.getValue() && playerY < effectiveWarnY;
        boolean inDanger = belowDisconnect || belowWarn;

        if (!handleGracePeriod(inDanger)) return;

        if (belowDisconnect) {
            if (!hasDisconnected) {
                hasDisconnected = true;
                executeDisconnect(playerY, effectiveDisconnectY);
            }
            return;
        } else {
            hasDisconnected = false;
        }

        if (belowWarn) {
            warnTickCounter++;
            int interval = warnInterval.getValue();
            boolean shouldWarn = (interval == 0) ? (warnTickCounter == 1) : (warnTickCounter % interval == 1);

            if (shouldWarn) {
                executeWarning(playerY, effectiveWarnY);
            }
        } else {
            warnTickCounter = 0;
        }
    }

    private void handleChorusEscapeKeybind() {
        boolean chorusPressed = chorusEscapeKey.getValue().isKeyDown();
        if (chorusPressed && !wasChorusPressed && !chorusEscapeActive) {
            chorusEscapeActive = true;
            hasTriggeredEat = false;
        }
        wasChorusPressed = chorusPressed;

        if (!chorusEscapeActive) return;

        if (!hasTriggeredEat) {
            if (!selectHotbarItem(Items.CHORUS_FRUIT)) {
                this.sendNotification(NotificationType.WARNING, "No Chorus Fruit found in hotbar! Falling back to normal Safety Net logic.");
                chorusEscapeActive = false;
            } else {
                mc.options.keyUse.setDown(true);
                hasTriggeredEat = true;
            }
        } else {
            if (!mc.player.isUsingItem()) {
                mc.options.keyUse.setDown(false);
                this.sendNotification(NotificationType.INFO, "Chorus escape successful. Disabling Safety Net.");
                toggle();
            }
        }
    }

    private boolean isInValidDimension() {
        boolean inEnd = mc.level.dimension().equals(Level.END);
        boolean inOverworld = mc.level.dimension().equals(Level.OVERWORLD);
        DimensionMode mode = dimension.getValue();

        if (mode == DimensionMode.Overworld && !inOverworld) return false;
        if (mode == DimensionMode.End && !inEnd) return false;
        if (mode == DimensionMode.Both && !inEnd && !inOverworld) return false;

        return true;
    }

    private int getEffectiveWarnY() {
        DimensionMode mode = dimension.getValue();
        boolean inEnd = mc.level.dimension().equals(Level.END);

        if (mode == DimensionMode.Overworld) return overworldWarnY.getValue();
        if (mode == DimensionMode.End) return endWarnY.getValue();

        if (perDimensionThresholds.getValue()) {
            return inEnd ? endWarnY.getValue() : overworldWarnY.getValue();
        }
        return warnY.getValue();
    }

    private int getEffectiveDisconnectY() {
        DimensionMode mode = dimension.getValue();
        boolean inEnd = mc.level.dimension().equals(Level.END);

        if (mode == DimensionMode.Overworld) return overworldDisconnectY.getValue();
        if (mode == DimensionMode.End) return endDisconnectY.getValue();

        if (perDimensionThresholds.getValue()) {
            return inEnd ? endDisconnectY.getValue() : overworldDisconnectY.getValue();
        }
        return disconnectY.getValue();
    }

    private void validateThresholds(int warnY, int disconnectY) {
        if (warnEnabled.getValue() && disconnectEnabled.getValue() && warnY <= disconnectY) {
            this.sendNotification(NotificationType.WARNING, "Safety Net config issue: warn-y-level (" + warnY
                + ") is not above disconnect-y-level (" + disconnectY
                + "). You may not receive warnings before being disconnected!");
        }
    }

    private boolean handleGracePeriod(boolean inDanger) {
        if (inDanger && graceEnabled.getValue()) {
            graceTickCounter++;
            if (graceTickCounter < graceTicks.getValue()) {
                return false;
            }
        } else if (!inDanger) {
            graceTickCounter = 0;
        }
        return true;
    }

    private void executeDisconnect(double playerY, int targetDisconnectY) {
        mc.gui.setTitle(Component.literal("§c§lSAFETY NET DISCONNECT"));
        mc.gui.setSubtitle(Component.literal(
            "§eY: " + String.format("%.1f", playerY) + " §7is below §c" + targetDisconnectY
        ));

        this.sendNotification(NotificationType.ERROR, "Disconnected — Y " + String.format("%.1f", playerY)
            + " is below safe threshold (" + targetDisconnectY + ").");

        mc.disconnect();
    }

    private void executeWarning(double playerY, int targetWarnY) {
        mc.gui.setTitle(Component.literal("§e§l⚠ VOID WARNING"));
        mc.gui.setSubtitle(Component.literal(
            "§fY: §c" + String.format("%.1f", playerY) + "  §f| Safe above: §a" + targetWarnY
        ));

        this.sendNotification(NotificationType.WARNING, "Safety Net: Below Y " + targetWarnY
            + "! Current Y: " + String.format("%.1f", playerY));

        mc.player.playSound(
            warnSound.getValue().getSoundEvent(),
            warnVolume.getValue().floatValue(),
            2.0f
        );
    }

    private boolean selectHotbarItem(Item item) {
        if (mc.player == null) return false;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) {
                mc.player.getInventory().selected = i;
                return true;
            }
        }
        return false;
    }
}
