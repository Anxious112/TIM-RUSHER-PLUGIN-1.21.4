package com.example.addon.modules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.addon.Tim;
import com.example.addon.utils.GlowingRegistry;
import com.example.addon.utils.RenderUtils;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.client.chat.EventAddChat;
import org.rusherhack.client.api.events.network.EventPacket;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.events.world.EventLoadWorld;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.setting.StringSetting;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;

import java.awt.Color;

public class NeighbourhoodWatch extends ToggleableModule {

    // ── Enums ─────────────────────────────────────────────────────────────────
    public enum PlayerStatus { Friend, Enemy, Proxy, Other }

    public enum TabEvent   { Join, Leave, Both }
    public enum TabFilter  { Friends, Enemies, Proxies, Others, All }
    public enum FilterMode { Censor, AutoIgnore }

    public enum HighlightMode { Wireframe, Spectral }

    public enum DangerSound {
        Off(null),
        WardenRoar(SoundEvents.WARDEN_ROAR),
        DragonGrowl(SoundEvents.ENDER_DRAGON_GROWL),
        RavagerRoar(SoundEvents.RAVAGER_ROAR),
        EndermanStare(SoundEvents.ENDERMAN_STARE),
        ElderGuardianCurse(SoundEvents.ELDER_GUARDIAN_CURSE),
        WitherDeath(SoundEvents.WITHER_DEATH);

        public final SoundEvent event;
        DangerSound(SoundEvent event) { this.event = event; }
    }

    // ── Settings — Safety ────────────────────────────────────────────────────
    private final BooleanSetting disconnectOnPlayer = new BooleanSetting("disconnect-on-player", "Disconnects when another player is detected nearby.", false);
    private final NumberSetting<Integer> playerDetectionRange = new NumberSetting<>("player-detection-range", "Distance within which a player triggers a disconnect.", 32, 1, 128)
        .setVisibility(disconnectOnPlayer::getValue);
    private final BooleanSetting ignoreFriendsOnDisconnect = new BooleanSetting("ignore-friends-on-disconnect", "Does not disconnect if the nearby player is a friend.", true)
        .setVisibility(disconnectOnPlayer::getValue);
    private final BooleanSetting ignoreProxiesOnDisconnect = new BooleanSetting("ignore-proxies-on-disconnect", "Does not disconnect if the nearby player is a proxy.", true)
        .setVisibility(disconnectOnPlayer::getValue);

    // ── Settings — Message Control ───────────────────────────────────────────
    private final EnumSetting<FilterMode> filterMode = new EnumSetting<>("mode", "Censor: replaces matched keywords with XXXX. AutoIgnore: runs /ignorehard on the sender.", FilterMode.Censor);
    private final StringSetting ignoreKeywords = new StringSetting("keywords", "Comma-separated words to act on. Censor mode redacts them; AutoIgnore mode silences the sender. Case-insensitive.", "");

    // ── Settings — Player Tracking ──────────────────────────────────────────
    private final BooleanSetting trackPlayers = new BooleanSetting("track-players", "Highlights and notifies when players enter visual range.", false);
    private final NumberSetting<Integer> trackRange = new NumberSetting<>("track-range", "Distance within which players are tracked.", 128, 1, 256)
        .setVisibility(trackPlayers::getValue);
    private final EnumSetting<TabFilter> trackFilter = new EnumSetting<>("track-filter", "Which player category to highlight and notify for.", TabFilter.Enemies)
        .setVisibility(trackPlayers::getValue);
    private final BooleanSetting notifyChat = new BooleanSetting("notify-chat", "Send a chat message when a player enters range.", true)
        .setVisibility(trackPlayers::getValue);
    private final StringSetting customMessage = new StringSetting("custom-message", "Notification message. Use {player} for name and {status} for relation.", "Warning: {status} {player} is in visual range!")
        .setVisibility(() -> trackPlayers.getValue() && notifyChat.getValue());
    private final EnumSetting<DangerSound> dangerSound = new EnumSetting<>("danger-sound", "Sound played when a matching player enters visual range. Off = silent.", DangerSound.Off)
        .setVisibility(trackPlayers::getValue);
    private final NumberSetting<Double> soundVolume = new NumberSetting<>("sound-volume", "Volume of the danger sound.", 1.0, 0.1, 2.5)
        .setVisibility(() -> trackPlayers.getValue() && dangerSound.getValue() != DangerSound.Off);
    private final NumberSetting<Double> soundPitch = new NumberSetting<>("sound-pitch", "Pitch of the danger sound. 1.0 = normal.", 1.0, 0.5, 2.0)
        .setVisibility(() -> trackPlayers.getValue() && dangerSound.getValue() != DangerSound.Off);

    private final EnumSetting<HighlightMode> highlightMode = new EnumSetting<>("highlight-mode", "Wireframe draws a bounding box outline. Spectral uses the vanilla glow pipeline.", HighlightMode.Wireframe)
        .setVisibility(trackPlayers::getValue);
    private final NumberSetting<Double> outlineScale = new NumberSetting<>("outline-scale", "Scale of the wireframe outline (Wireframe mode only). 1.0 = exact model size.", 1.02, 1.0, 2.0)
        .setVisibility(() -> trackPlayers.getValue() && highlightMode.getValue() == HighlightMode.Wireframe);

    // ── Settings — Friends & Enemies ───────────────────────────────────────
    private final StringSetting friends = new StringSetting("friends", "Comma-separated players treated as friends. Case-insensitive.", "");
    private final ColorSetting friendColor = new ColorSetting("friend-color", "Highlight color for friends.", new Color(0, 255, 0, 255))
        .setVisibility(() -> trackPlayers.getValue() && isFriendCategoryVisible());
    private final StringSetting enemies = new StringSetting("enemies", "Comma-separated players treated as enemies. Case-insensitive.", "");
    private final ColorSetting enemyColor = new ColorSetting("enemy-color", "Highlight color for enemies.", new Color(255, 0, 0, 255))
        .setVisibility(() -> trackPlayers.getValue() && isEnemyCategoryVisible());
    private final StringSetting proxies = new StringSetting("proxies", "Comma-separated players treated as proxies. Case-insensitive.", "");
    private final ColorSetting proxyColor = new ColorSetting("proxy-color", "Highlight color for proxies.", new Color(255, 140, 0, 255))
        .setVisibility(() -> trackPlayers.getValue() && isProxyCategoryVisible());
    private final ColorSetting otherColor = new ColorSetting("other-color", "Highlight color for unknown players.", new Color(139, 0, 0, 255))
        .setVisibility(() -> trackPlayers.getValue() && isOtherCategoryVisible());

    // ── Settings — Tab List Monitoring ─────────────────────────────────────
    private final EnumSetting<TabEvent> tabEvent = new EnumSetting<>("event", "Which tab-list event to notify on.", TabEvent.Both);
    private final EnumSetting<TabFilter> tabFilter = new EnumSetting<>("notify-for", "Which player category triggers a notification.", TabFilter.All);

    // ── State ──────────────────────────────────────────────────────────────
    private final Set<Integer> notifiedPlayers    = new HashSet<>();
    private final Set<Integer> activelyOutlined   = new HashSet<>();
    private final Set<String>  ignoredThisSession = new HashSet<>();
    private final Set<String>  playersInTab       = new HashSet<>();
    private final Set<String>  friendSet          = new HashSet<>();
    private final Set<String>  enemySet           = new HashSet<>();
    private final Set<String>  proxySet           = new HashSet<>();

    private boolean anyPlayerNearby = false;

    public NeighbourhoodWatch() {
        super("neighbourhood-watch", "Manages player tracking, safety, server monitoring, and keyword alerts.", Tim.CATEGORY);
        friends.onChange((Runnable) this::updateFriendEnemySets);
        enemies.onChange((Runnable) this::updateFriendEnemySets);
        proxies.onChange((Runnable) this::updateFriendEnemySets);
        this.registerSettings(
            disconnectOnPlayer, playerDetectionRange, ignoreFriendsOnDisconnect, ignoreProxiesOnDisconnect,
            filterMode, ignoreKeywords,
            trackPlayers, trackRange, trackFilter, notifyChat, customMessage, dangerSound, soundVolume, soundPitch,
            highlightMode, outlineScale,
            friends, friendColor, enemies, enemyColor, proxies, proxyColor, otherColor,
            tabEvent, tabFilter
        );
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────
    @Override
    public void onEnable() {
        resetState();
        updateFriendEnemySets();
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.getListedOnlinePlayers().forEach(entry -> {
                String name = entry.getProfile().getName();
                if (name != null && !name.isEmpty()) playersInTab.add(name);
            });
        }
    }

    @Override
    public void onDisable() {
        clearAllOutlines();
        resetState();
        anyPlayerNearby = false;
    }

    @Subscribe
    private void onGameJoined(EventLoadWorld event) {
        clearAllOutlines();
        resetState();
        anyPlayerNearby = false;
    }

    // ── Tick ───────────────────────────────────────────────────────────────
    @Subscribe
    private void onTick(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        if (tickDisconnectOnPlayer()) return;
        tickPlayerTracking();
        tickOutlineShader();
    }

    // ── Outline management (Spectral / GlowingRegistry) ────────────────────
    private void tickOutlineShader() {
        if (!trackPlayers.getValue()) {
            clearAllOutlines();
            return;
        }

        boolean spectral = highlightMode.getValue() == HighlightMode.Spectral;
        Set<Integer> newlyActive = new HashSet<>();

        for (Player player : mc.level.players()) {
            if (player == mc.player || player.isSpectator()) continue;
            if (mc.player.distanceTo(player) > trackRange.getValue()) continue;

            String       name   = player.getName().getString();
            PlayerStatus status = getPlayerStatusPublic(name);

            boolean shouldHighlight = trackFilter.getValue() == TabFilter.All || switch (status) {
                case Friend -> trackFilter.getValue() == TabFilter.Friends;
                case Enemy  -> trackFilter.getValue() == TabFilter.Enemies;
                case Proxy  -> trackFilter.getValue() == TabFilter.Proxies;
                case Other  -> trackFilter.getValue() == TabFilter.Others;
            };
            if (!shouldHighlight) continue;

            if (spectral) {
                Color color = colorFor(status);
                GlowingRegistry.add(player.getId(), (255 << 24) | (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue());
            }

            newlyActive.add(player.getId());
        }

        for (int id : activelyOutlined) {
            if (!newlyActive.contains(id) || !spectral) {
                GlowingRegistry.remove(id);
            }
        }

        activelyOutlined.clear();
        activelyOutlined.addAll(newlyActive);
    }

    // ── Render 3D — wireframe (bounding box) outline ───────────────────────
    @Subscribe
    private void onRender(EventRender3D event) {
        if (mc.level == null || mc.player == null) return;
        if (!trackPlayers.getValue() || highlightMode.getValue() != HighlightMode.Wireframe) return;

        IRenderer3D renderer = event.getRenderer();
        renderer.begin(event.getMatrixStack());

        for (Player player : mc.level.players()) {
            if (!activelyOutlined.contains(player.getId())) continue;

            PlayerStatus status = getPlayerStatusPublic(player.getName().getString());
            Color color = colorFor(status);
            renderer.drawBox(player, event.getPartialTicks(), false, true, color.getRGB());
        }

        renderer.end();
    }

    // ── Packet Handler — Tab list ─────────────────────────────────────────
    @Subscribe
    private void onPacketReceive(EventPacket.Receive event) {
        if (!(event.getPacket() instanceof ClientboundPlayerInfoUpdatePacket packet)) return;

        for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
            if (entry.profile() == null) continue;
            String name = entry.profile().getName();
            if (name == null || name.isEmpty()) continue;

            if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                if (playersInTab.add(name)) {
                    handleTabListChange(name, "joined");
                }
            } else if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED) && !entry.listed()) {
                if (playersInTab.remove(name)) {
                    handleTabListChange(name, "left");
                }
            }
        }
    }

    // ── Chat message listener — Message Control ───────────────────────────
    @Subscribe
    private void onReceiveMessage(EventAddChat event) {
        if (mc.player == null || mc.player.connection == null) return;
        if (keywordList().isEmpty()) return;

        if (filterMode.getValue() == FilterMode.AutoIgnore) {
            parseMessageForAutoIgnore(event.getChatComponent().getString());
        } else {
            String censored = censorMessage(event.getChatComponent().getString());
            if (censored != null) event.setChatComponent(Component.literal(censored));
        }
    }

    // ── Tick Logic ────────────────────────────────────────────────────────
    private boolean tickDisconnectOnPlayer() {
        if (!disconnectOnPlayer.getValue()) return false;

        for (Player player : mc.level.players()) {
            if (player == mc.player || player.getAbilities().instabuild || player.isSpectator()) continue;
            if (ignoreFriendsOnDisconnect.getValue()  && isFriend(player.getName().getString())) continue;
            if (ignoreProxiesOnDisconnect.getValue()  && isProxy(player.getName().getString()))  continue;
            if (mc.player.distanceTo(player) <= playerDetectionRange.getValue()) {
                disconnect("[NeighbourhoodWatch] Player detected: " + player.getName().getString());
                return true;
            }
        }
        return false;
    }

    private void tickPlayerTracking() {
        if (!trackPlayers.getValue()) {
            anyPlayerNearby = false;
            return;
        }

        anyPlayerNearby = false;

        for (Player player : mc.level.players()) {
            if (player == mc.player || player.isSpectator()) continue;
            if (mc.player.distanceTo(player) > trackRange.getValue()) continue;

            anyPlayerNearby = true;

            String       name   = player.getName().getString();
            PlayerStatus status = getPlayerStatusPublic(name);

            boolean isNewlySpotted = notifiedPlayers.add(player.getId());

            if (isNewlySpotted) {
                notifyLastSeenHud(player);
            }

            boolean shouldNotify = trackFilter.getValue() == TabFilter.All || switch (status) {
                case Friend -> trackFilter.getValue() == TabFilter.Friends;
                case Enemy  -> trackFilter.getValue() == TabFilter.Enemies;
                case Proxy  -> trackFilter.getValue() == TabFilter.Proxies;
                case Other  -> trackFilter.getValue() == TabFilter.Others;
            };
            if (!shouldNotify) continue;

            if (isNewlySpotted) {
                if (notifyChat.getValue()) {
                    String statusStr = status.name().toLowerCase();
                    String msg = customMessage.getValue()
                        .replace("{player}", name)
                        .replace("{status}", statusStr);
                    sendNotification(NotificationType.INFO, msg);
                }

                DangerSound sound = dangerSound.getValue();
                if (sound != DangerSound.Off && sound.event != null) {
                    mc.player.playSound(
                        sound.event,
                        soundVolume.getValue().floatValue(),
                        soundPitch.getValue().floatValue()
                    );
                }
            }
        }
        notifiedPlayers.removeIf(id -> mc.level.getEntity(id) == null);
    }

    // ── Tab List ──────────────────────────────────────────────────────────
    private void handleTabListChange(String playerName, String action) {
        PlayerStatus status = getPlayerStatusPublic(playerName);

        if (tabEvent.getValue() != TabEvent.Both) {
            TabEvent eventType = action.equals("joined") ? TabEvent.Join : TabEvent.Leave;
            if (tabEvent.getValue() != eventType) return;
        }

        boolean shouldNotify = tabFilter.getValue() == TabFilter.All || switch (status) {
            case Friend -> tabFilter.getValue() == TabFilter.Friends;
            case Enemy  -> tabFilter.getValue() == TabFilter.Enemies;
            case Proxy  -> tabFilter.getValue() == TabFilter.Proxies;
            case Other  -> tabFilter.getValue() == TabFilter.Others;
        };
        if (!shouldNotify) return;

        String label = switch (status) {
            case Friend -> "§aFriend";
            case Enemy  -> "§cEnemy";
            case Proxy  -> "§6Proxy";
            case Other  -> "Player";
        };
        sendNotification(NotificationType.INFO, String.format("%s %s has %s the server.", label, playerName, action));
    }

    // ── Chat Parsing — Message Control ────────────────────────────────────
    private String[] parseSenderAndBody(String rawMessage) {
        if (rawMessage.startsWith("<")) {
            int close = rawMessage.indexOf('>');
            if (close < 1) return null;
            return new String[]{ rawMessage.substring(1, close).trim(),
                                 rawMessage.substring(close + 1).trim() };
        }
        int colon = rawMessage.indexOf(':');
        if (colon < 1 || colon >= 20) return null;
        String name = rawMessage.substring(0, colon);
        if (name.contains(" ")) return null;
        return new String[]{ name.trim(), rawMessage.substring(colon + 1).trim() };
    }

    private String findKeyword(String body) {
        String search = body.toLowerCase();
        for (String kw : keywordList()) {
            if (kw.isBlank()) continue;
            if (search.contains(kw.toLowerCase())) return kw;
        }
        return null;
    }

    private String censorMessage(String rawMessage) {
        String  working = rawMessage;
        boolean changed = false;
        for (String kw : keywordList()) {
            if (kw.isBlank()) continue;
            String replacement = "X".repeat(kw.length());
            String replaced = working.replaceAll("(?i)" + java.util.regex.Pattern.quote(kw), replacement);
            if (!replaced.equals(working)) { working = replaced; changed = true; }
        }
        return changed ? working : null;
    }

    private void parseMessageForAutoIgnore(String rawMessage) {
        String[] parts = parseSenderAndBody(rawMessage);
        if (parts == null) return;
        String sender = parts[0], messageBody = parts[1];

        if (sender.equalsIgnoreCase(mc.player.getName().getString())) return;
        if (isFriend(sender) || isProxy(sender)) return;
        if (ignoredThisSession.contains(sender.toLowerCase())) return;
        if (findKeyword(messageBody) == null) return;

        mc.player.connection.sendCommand("ignorehard " + sender);
        ignoredThisSession.add(sender.toLowerCase());
        sendNotification(NotificationType.INFO, String.format("Auto-ignored %s (keyword match).", sender));
    }

    private void clearAllOutlines() {
        for (int id : activelyOutlined) {
            GlowingRegistry.remove(id);
        }
        activelyOutlined.clear();
    }

    // ── General Helpers ──────────────────────────────────────────────────
    private void resetState() {
        notifiedPlayers.clear();
        ignoredThisSession.clear();
        playersInTab.clear();
    }

    private List<String> keywordList() {
        return splitCsv(ignoreKeywords.getValue());
    }

    private static List<String> splitCsv(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private void updateFriendEnemySets() {
        friendSet.clear();
        for (String name : splitCsv(friends.getValue())) friendSet.add(name.toLowerCase());
        enemySet.clear();
        for (String name : splitCsv(enemies.getValue())) enemySet.add(name.toLowerCase());
        proxySet.clear();
        for (String name : splitCsv(proxies.getValue())) proxySet.add(name.toLowerCase());
    }

    private void disconnect(String reason) {
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.getConnection().disconnect(Component.literal(reason));
        }
        this.toggle();
    }

    private Color colorFor(PlayerStatus status) {
        return switch (status) {
            case Friend -> friendColor.getValue();
            case Enemy  -> enemyColor.getValue();
            case Proxy  -> proxyColor.getValue();
            case Other  -> otherColor.getValue();
        };
    }

    /** TODO: wire to LastSeenPlayerHud once that HUD is ported. */
    private void notifyLastSeenHud(Player player) {
        // no-op until the HUD exists
    }

    // ── Category Visibility Helpers ──────────────────────────────────────
    private boolean isFriendCategoryVisible() {
        return trackFilter.getValue() == TabFilter.Friends || trackFilter.getValue() == TabFilter.All
            || tabFilter.getValue()   == TabFilter.Friends || tabFilter.getValue()   == TabFilter.All;
    }

    private boolean isEnemyCategoryVisible() {
        return trackFilter.getValue() == TabFilter.Enemies || trackFilter.getValue() == TabFilter.All
            || tabFilter.getValue()   == TabFilter.Enemies || tabFilter.getValue()   == TabFilter.All;
    }

    private boolean isProxyCategoryVisible() {
        return trackFilter.getValue() == TabFilter.Proxies || trackFilter.getValue() == TabFilter.All
            || tabFilter.getValue()   == TabFilter.Proxies || tabFilter.getValue()   == TabFilter.All;
    }

    private boolean isOtherCategoryVisible() {
        return trackFilter.getValue() == TabFilter.Others || trackFilter.getValue() == TabFilter.All
            || tabFilter.getValue()   == TabFilter.Others  || tabFilter.getValue()   == TabFilter.All;
    }

    // ── Public API ──────────────────────────────────────────────────────
    public boolean isFriend(String name) { return name != null && friendSet.contains(name.toLowerCase()); }
    public boolean isEnemy(String name)  { return name != null && enemySet.contains(name.toLowerCase()); }
    public boolean isProxy(String name)  { return name != null && proxySet.contains(name.toLowerCase()); }

    public PlayerStatus getPlayerStatusPublic(String name) {
        if (isFriend(name)) return PlayerStatus.Friend;
        if (isEnemy(name))  return PlayerStatus.Enemy;
        if (isProxy(name))  return PlayerStatus.Proxy;
        return PlayerStatus.Other;
    }

    public boolean isDisconnectOnPlayerArmed() {
        return disconnectOnPlayer.getValue();
    }

    /** Exposes whether a player is actively inside tracking range. Used by the HUD. */
    public boolean isAnyPlayerNearby() {
        return anyPlayerNearby;
    }
}
