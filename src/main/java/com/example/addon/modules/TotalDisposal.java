package com.example.addon.modules;

import com.example.addon.Tim;

import org.lwjgl.glfw.GLFW;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.setting.BindSetting;
import org.rusherhack.core.bind.key.NullKey;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.EnumSetting;

import net.minecraft.world.inventory.ClickType;

public class TotalDisposal extends ToggleableModule {
    public enum ModifierKey {
        Control,
        Shift
    }

    // Menu slot ids for the vanilla player-inventory container (id 0): armor(5-8), main(9-35), hotbar(36-44), offhand(45).
    private static final int[] ALL_SLOTS;
    static {
        ALL_SLOTS = new int[41];
        int idx = 0;
        for (int s = 5; s <= 45; s++) ALL_SLOTS[idx++] = s;
    }

    private final BindSetting dropKey = new BindSetting("drop-key", "Key to drop the entire inventory.", NullKey.INSTANCE);
    private final BindSetting killKey = new BindSetting("kill-key", "Key to send the /kill command.", NullKey.INSTANCE);
    private final EnumSetting<ModifierKey> modifier = new EnumSetting<>("modifier", "The required modifier key that must be held.", ModifierKey.Control);

    private boolean wasDropPressed = false;
    private boolean wasKillPressed = false;

    public TotalDisposal() {
        super("total-disposal", "Drop items and /kill via dedicated key combinations.", Tim.CATEGORY);
        this.registerSettings(dropKey, killKey, modifier);
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.screen != null) return; // Prevent triggering while in chat or menus

        boolean dropPressed = dropKey.getValue().isKeyDown();
        boolean killPressed = killKey.getValue().isKeyDown();

        if (checkModifiers()) {
            if (dropPressed && !wasDropPressed) {
                executeDrop();
            }
            if (killPressed && !wasKillPressed) {
                executeKill();
            }
        }

        wasDropPressed = dropPressed;
        wasKillPressed = killPressed;
    }

    private boolean checkModifiers() {
        long window = mc.getWindow().getWindow();
        return switch (modifier.getValue()) {
            case Control -> GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
            case Shift -> GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        };
    }

    private void executeDrop() {
        if (mc.player == null || mc.gameMode == null) return;
        for (int slot : ALL_SLOTS) {
            mc.gameMode.handleInventoryMouseClick(0, slot, 1, ClickType.THROW, mc.player);
        }
        this.sendNotification(NotificationType.INFO, "Inventory dropped.");
    }

    private void executeKill() {
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendCommand("kill");
            this.sendNotification(NotificationType.INFO, "Sent /kill command.");
        }
    }
}
