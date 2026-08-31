package com.example.addon.modules;

import com.example.addon.Tim;

import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.setting.BindSetting;
import org.rusherhack.core.bind.key.IKey;
import org.rusherhack.core.bind.key.NullKey;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.setting.StringSetting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Penpal extends ToggleableModule {
    public enum Modifier {
        NONE,
        SHIFT,
        CONTROL,
        ALT
    }

    public enum MessageType {
        CUSTOM,
        RANDOM
    }

    private static final String SUFFIX_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private final Random random = new Random();
    private String lastBotCmd = "";

    private static class PendingMessage {
        String target;
        String msg;
        long executeAt;

        PendingMessage(String target, String msg, long executeAt) {
            this.target = target;
            this.msg = msg;
            this.executeAt = executeAt;
        }
    }
    private final List<PendingMessage> messageQueue = new ArrayList<>();

    private final StringSetting randomPool = new StringSetting("random-pool",
        "Comma-separated list of commands to randomly pick from for RANDOM.", "tp, pearl, teleport");

    private final BooleanSetting humanDelay = new BooleanSetting("human-like-delay",
        "Adds a randomized 50-150ms delay before sending to bypass anti-spam packet timing.", true);

    private final NumberSetting<Integer> globalCooldown = new NumberSetting<>("global-cooldown-ms",
        "Prevents triggering any slot again for this many milliseconds after a message is sent.", 500, 0, 2000);

    private final BooleanSetting chatFeedback = new BooleanSetting("chat-feedback",
        "Shows a message when a message is sent.", true);

    // --- Message 1 ---
    private final StringSetting target1 = new StringSetting("target-user-1", "The username to send the message to.", "");
    private final EnumSetting<MessageType> type1 = new EnumSetting<>("message-type-1", "RANDOM randomly picks from the pool. CUSTOM uses your specific text.", MessageType.RANDOM);
    private final StringSetting message1 = new StringSetting("custom-message-1", "The message to send. Only used if Message Type is set to CUSTOM.", "")
        .setVisibility(() -> type1.getValue() == MessageType.CUSTOM);
    private final EnumSetting<Modifier> modifier1 = new EnumSetting<>("modifier-1", "The modifier key to hold.", Modifier.NONE);
    private final BindSetting key1 = new BindSetting("trigger-key-1", "The key to press to send the message.", NullKey.INSTANCE);

    // --- Message 2 ---
    private final StringSetting target2 = new StringSetting("target-user-2", "The username to send the message to.", "");
    private final EnumSetting<MessageType> type2 = new EnumSetting<>("message-type-2", "RANDOM randomly picks from the pool. CUSTOM uses your specific text.", MessageType.RANDOM);
    private final StringSetting message2 = new StringSetting("custom-message-2", "The message to send. Only used if Message Type is set to CUSTOM.", "")
        .setVisibility(() -> type2.getValue() == MessageType.CUSTOM);
    private final EnumSetting<Modifier> modifier2 = new EnumSetting<>("modifier-2", "The modifier key to hold.", Modifier.NONE);
    private final BindSetting key2 = new BindSetting("trigger-key-2", "The key to press to send the message.", NullKey.INSTANCE);

    // --- Message 3 ---
    private final StringSetting target3 = new StringSetting("target-user-3", "The username to send the message to.", "");
    private final EnumSetting<MessageType> type3 = new EnumSetting<>("message-type-3", "RANDOM randomly picks from the pool. CUSTOM uses your specific text.", MessageType.RANDOM);
    private final StringSetting message3 = new StringSetting("custom-message-3", "The message to send. Only used if Message Type is set to CUSTOM.", "")
        .setVisibility(() -> type3.getValue() == MessageType.CUSTOM);
    private final EnumSetting<Modifier> modifier3 = new EnumSetting<>("modifier-3", "The modifier key to hold.", Modifier.NONE);
    private final BindSetting key3 = new BindSetting("trigger-key-3", "The key to press to send the message.", NullKey.INSTANCE);

    // --- Message 4 ---
    private final StringSetting target4 = new StringSetting("target-user-4", "The username to send the message to.", "");
    private final EnumSetting<MessageType> type4 = new EnumSetting<>("message-type-4", "RANDOM randomly picks from the pool. CUSTOM uses your specific text.", MessageType.RANDOM);
    private final StringSetting message4 = new StringSetting("custom-message-4", "The message to send. Only used if Message Type is set to CUSTOM.", "")
        .setVisibility(() -> type4.getValue() == MessageType.CUSTOM);
    private final EnumSetting<Modifier> modifier4 = new EnumSetting<>("modifier-4", "The modifier key to hold.", Modifier.NONE);
    private final BindSetting key4 = new BindSetting("trigger-key-4", "The key to press to send the message.", NullKey.INSTANCE);

    private final boolean[] wasPressed = new boolean[5]; // Index 1 to 4
    private long lastSentTime = 0;

    public Penpal() {
        super("penpal", "Quickly message custom users or bots via /msg using custom modifier keys and binds.", Tim.CATEGORY);
        this.registerSettings(
            randomPool, humanDelay, globalCooldown, chatFeedback,
            target1, type1, message1, modifier1, key1,
            target2, type2, message2, modifier2, key2,
            target3, type3, message3, modifier3, key3,
            target4, type4, message4, modifier4, key4
        );
    }

    @Override
    public void onEnable() {
        for (int i = 0; i < wasPressed.length; i++) {
            wasPressed[i] = false;
        }
        lastBotCmd = "";
        lastSentTime = 0;
        messageQueue.clear();
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (!messageQueue.isEmpty()) {
            long currentTime = System.currentTimeMillis();
            messageQueue.removeIf(pending -> {
                if (currentTime >= pending.executeAt) {
                    executeSend(pending.target, pending.msg);
                    return true;
                }
                return false;
            });
        }

        checkSlot(1, target1, type1, message1, modifier1, key1);
        checkSlot(2, target2, type2, message2, modifier2, key2);
        checkSlot(3, target3, type3, message3, modifier3, key3);
        checkSlot(4, target4, type4, message4, modifier4, key4);
    }

    private void checkSlot(int id, StringSetting targetSetting, EnumSetting<MessageType> typeSetting, StringSetting customMsgSetting, EnumSetting<Modifier> modSetting, BindSetting keySetting) {
        if (targetSetting.getValue().isBlank()) {
            wasPressed[id] = false;
            return;
        }

        boolean pressed = isPressed(keySetting.getValue(), modSetting.getValue());
        if (pressed && !wasPressed[id]) {
            if (System.currentTimeMillis() - lastSentTime >= globalCooldown.getValue()) {
                String finalMessage;
                if (typeSetting.getValue() == MessageType.CUSTOM) {
                    finalMessage = customMsgSetting.getValue();
                } else {
                    finalMessage = getRandomBotCommand();
                }

                finalMessage += " " + generateAntiSpamSuffix();

                long delay = humanDelay.getValue() ? 50 + random.nextInt(101) : 0;
                long executeAt = System.currentTimeMillis() + delay;

                messageQueue.add(new PendingMessage(targetSetting.getValue(), finalMessage, executeAt));
                lastSentTime = System.currentTimeMillis();
            }
        }
        wasPressed[id] = pressed;
    }

    private String getRandomBotCommand() {
        String[] pool = randomPool.getValue().split(",");
        List<String> valid = new ArrayList<>();

        for (String s : pool) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty() && !trimmed.equals(lastBotCmd)) {
                valid.add(trimmed);
            }
        }

        if (valid.isEmpty()) {
            for (String s : pool) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) {
                    lastBotCmd = trimmed;
                    return trimmed;
                }
            }
            return "tp";
        }

        String cmd = valid.get(random.nextInt(valid.size()));
        lastBotCmd = cmd;
        return cmd;
    }

    private String generateAntiSpamSuffix() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(SUFFIX_CHARS.charAt(random.nextInt(SUFFIX_CHARS.length())));
        }
        return sb.toString();
    }

    private void executeSend(String target, String msg) {
        if (mc.player == null || mc.player.connection == null) return;
        if (target.isEmpty() || msg.isEmpty()) return;

        target = target.trim();
        mc.player.connection.sendCommand("msg " + target + " " + msg);
        if (chatFeedback.getValue()) {
            this.sendNotification(NotificationType.INFO, "Sent message to " + target + ".");
        }
    }

    private boolean isPressed(IKey key, Modifier modifier) {
        if (!key.isKeyDown()) return false;
        if (mc.screen != null) return false;

        long handle = mc.getWindow().getWindow();
        boolean shiftDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        boolean ctrlDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        boolean altDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;

        return switch (modifier) {
            case NONE -> !shiftDown && !ctrlDown && !altDown;
            case SHIFT -> shiftDown && !ctrlDown && !altDown;
            case CONTROL -> ctrlDown && !shiftDown && !altDown;
            case ALT -> altDown && !shiftDown && !ctrlDown;
        };
    }
}
