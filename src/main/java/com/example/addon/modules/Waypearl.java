package com.example.addon.modules;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.example.addon.Tim;
import com.example.addon.utils.GlowingRegistry;
import com.example.addon.utils.RenderUtils;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.client.chat.EventAddChat;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.events.world.EventEntity;
import org.rusherhack.client.api.events.world.EventLoadWorld;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.BindSetting;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.core.bind.key.NullKey;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.setting.StringSetting;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class Waypearl extends ToggleableModule {

    public enum PingSound {
        BeaconActivate   ("Beacon Activate",    SoundEvents.BEACON_ACTIVATE),
        BeaconDeactivate ("Beacon Deactivate",  SoundEvents.BEACON_DEACTIVATE),
        BellUse          ("Bell",               SoundEvents.BELL_BLOCK),
        ExperienceOrb    ("Experience Orb",     SoundEvents.EXPERIENCE_ORB_PICKUP),
        PlayerLevelUp    ("Level Up",           SoundEvents.PLAYER_LEVELUP),
        NoteBlockBell    ("Note Bell",          SoundEvents.NOTE_BLOCK_BELL.value()),
        NoteBlockChime   ("Note Chime",         SoundEvents.NOTE_BLOCK_CHIME.value()),
        NoteBlockPling   ("Note Pling",         SoundEvents.NOTE_BLOCK_PLING.value()),
        EnderEye         ("Ender Eye",          SoundEvents.ENDER_EYE_LAUNCH),
        EndermanTeleport ("Enderman Teleport",  SoundEvents.ENDERMAN_TELEPORT);

        public final String label;
        public final SoundEvent sound;
        PingSound(String label, SoundEvent sound) { this.label = label; this.sound = sound; }
        @Override public String toString() { return label; }
    }

    public enum ModuleMode { Assistant, Requester }
    public enum PullOrder { DISCOVERY, NEAREST }
    public enum RenderMode { Default, PearlsOnly }
    public enum CapPosition { NONE, BOTTOM, TOP, BOTH }
    public enum BoxMode { Both, Sides, Lines }

    private enum WalkState { IDLE, WALKING_TO, PULLING, WALKING_BACK, ABORTED }

    private static final Map<Integer, Vec3> BEAM_POS_CACHE = new ConcurrentHashMap<>();

    // ── Settings — General ──
    private final NumberSetting<Integer> range = new NumberSetting<>("range", "Detection radius in blocks.", 64, 16, 128);
    private final NumberSetting<Integer> scanInterval = new NumberSetting<>("scan-interval", "Ticks between bubble column background scans.", 40, 10, 200);
    private final EnumSetting<RenderMode> renderMode = new EnumSetting<>("render-mode", "Default = bubble column beams. Pearls Only = entity outlines & soul sand beams.", RenderMode.Default);
    private final BooleanSetting highlightOwnPearl = new BooleanSetting("highlight-own-pearl", "Use a distinct color for your own pearls.", true);
    private final ColorSetting ownPearlColor = new ColorSetting("own-pearl-color", "Color for your own pearl's outline in Default mode.", new Color(0, 255, 0, 255))
        .setVisibility(() -> renderMode.getValue() == RenderMode.Default && highlightOwnPearl.getValue());

    // ── Bubble Columns ──
    private final BooleanSetting columnsEnabled = new BooleanSetting("highlight-columns", "Highlight bubble columns with a beam.", true)
        .setVisibility(() -> renderMode.getValue() == RenderMode.Default);
    private final ColorSetting coreColor = new ColorSetting("core-color", "Beam core color.", new Color(180, 230, 255, 255))
        .setVisibility(() -> renderMode.getValue() == RenderMode.Default && columnsEnabled.getValue());
    private final ColorSetting glowColor = new ColorSetting("glow-color", "Beam glow color.", new Color(0, 180, 255, 50))
        .setVisibility(() -> renderMode.getValue() == RenderMode.Default && columnsEnabled.getValue());
    private final NumberSetting<Double> coreWidth = new NumberSetting<>("core-width", "Beam core half-width.", 0.03, 0.005, 0.25)
        .setVisibility(() -> renderMode.getValue() == RenderMode.Default && columnsEnabled.getValue());
    private final NumberSetting<Double> glowSpread = new NumberSetting<>("glow-spread", "Beam glow spread.", 0.08, 0.01, 0.5)
        .setVisibility(() -> renderMode.getValue() == RenderMode.Default && columnsEnabled.getValue());
    private final NumberSetting<Integer> glowLayers = new NumberSetting<>("glow-layers", "Beam glow layer count.", 4, 1, 8)
        .setVisibility(() -> renderMode.getValue() == RenderMode.Default && columnsEnabled.getValue());
    private final NumberSetting<Integer> glowBaseAlpha = new NumberSetting<>("glow-base-alpha", "Beam glow alpha.", 50, 4, 150)
        .setVisibility(() -> renderMode.getValue() == RenderMode.Default && columnsEnabled.getValue());

    // ── Cap Box ──
    private final EnumSetting<CapPosition> capPosition = new EnumSetting<>("cap-position", "Where to draw a cap box.", CapPosition.BOTTOM)
        .setVisibility(() -> renderMode.getValue() == RenderMode.Default);
    private final ColorSetting capColor = new ColorSetting("cap-color", "Cap box color.", new Color(0, 200, 255, 160))
        .setVisibility(() -> renderMode.getValue() == RenderMode.Default && capPosition.getValue() != CapPosition.NONE);
    private final NumberSetting<Double> capSize = new NumberSetting<>("cap-size", "Cap box half-size.", 0.4, 0.05, 2.0)
        .setVisibility(() -> renderMode.getValue() == RenderMode.Default && capPosition.getValue() != CapPosition.NONE);
    private final NumberSetting<Double> capThickness = new NumberSetting<>("cap-thickness", "Cap box thickness.", 0.04, 0.01, 0.5)
        .setVisibility(() -> renderMode.getValue() == RenderMode.Default && capPosition.getValue() != CapPosition.NONE);
    private final EnumSetting<BoxMode> capShapeMode = new EnumSetting<>("cap-shape-mode", "Cap box render mode.", BoxMode.Both)
        .setVisibility(() -> renderMode.getValue() == RenderMode.Default && capPosition.getValue() != CapPosition.NONE);
    private final BooleanSetting capGlow = new BooleanSetting("cap-glow", "Render cap box glow.", true)
        .setVisibility(() -> renderMode.getValue() == RenderMode.Default && capPosition.getValue() != CapPosition.NONE);

    // ── Sound ──
    private final BooleanSetting soundEnabled = new BooleanSetting("sound-ping", "Play a sound when a new pearl is detected.", true);
    private final EnumSetting<PingSound> pingSound = new EnumSetting<>("sound", "Ping sound.", PingSound.BeaconActivate).setVisibility(soundEnabled::getValue);
    private final NumberSetting<Double> soundVolume = new NumberSetting<>("volume", "Ping volume.", 1.0, 0.1, 2.0).setVisibility(soundEnabled::getValue);
    private final NumberSetting<Double> soundPitch = new NumberSetting<>("pitch", "Ping pitch.", 1.8, 0.5, 2.0).setVisibility(soundEnabled::getValue);

    // ── Bot Assistant ──
    private final EnumSetting<ModuleMode> moduleMode = new EnumSetting<>("mode", "Assistant pulls pearls for others; Requester asks a bot.", ModuleMode.Requester);
    private final StringSetting botUsername = new StringSetting("bot-username", "The assistant bot's username.", "");
    private final StringSetting triggerPhrase = new StringSetting("trigger-phrase", "Comma-separated trigger keywords.", "tp,pull,pearl");
    private final StringSetting whitelist = new StringSetting("whitelist", "Comma-separated players allowed to trigger a pull (Assistant mode).", "")
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant);
    private final BindSetting selfTriggerKey = new BindSetting("self-trigger-key", "Key to request a pull from the bot (Requester mode).", NullKey.INSTANCE)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Requester);
    private final NumberSetting<Integer> pullCooldown = new NumberSetting<>("cooldown", "Seconds between pulls.", 5, 1, 30);
    private final EnumSetting<PullOrder> pullOrder = new EnumSetting<>("pull-order", "Order to pick pearls when multiple match.", PullOrder.DISCOVERY)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant);
    private final BooleanSetting notifyEnabled = new BooleanSetting("notify-requester", "Whisper the requester when pulling.", true)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant);
    private final StringSetting notifyMessage = new StringSetting("notify-message", "Message whispered to the requester. {player} = name.", "Pulling your pearl now, {player}.")
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && notifyEnabled.getValue());

    // ── Walker ──
    private final BooleanSetting walkerEnabled = new BooleanSetting("walker-enabled", "Walk to out-of-reach trapdoors.", true)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant);
    private final NumberSetting<Double> interactReach = new NumberSetting<>("interact-reach", "Reach to interact with a trapdoor.", 3.5, 1.0, 5.0)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue());
    private final NumberSetting<Double> slowZoneRadius = new NumberSetting<>("slow-zone-radius", "Distance to stop sprinting near the target.", 6.0, 2.0, 16.0)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue());
    private final NumberSetting<Double> waypointSpacing = new NumberSetting<>("waypoint-spacing", "Distance between path waypoints.", 6.0, 2.0, 20.0)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue());
    private final NumberSetting<Integer> walkTimeoutTicks = new NumberSetting<>("walk-timeout", "Ticks before a walk aborts.", 200, 40, 600)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue());
    private final NumberSetting<Integer> returnTimeoutTicks = new NumberSetting<>("return-timeout", "Ticks before a return aborts.", 200, 40, 600)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue());
    private final NumberSetting<Double> returnTolerance = new NumberSetting<>("return-tolerance", "How close to idle counts as returned.", 1.0, 0.3, 5.0)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue());
    private final NumberSetting<Integer> stuckThresholdTicks = new NumberSetting<>("stuck-threshold", "Ticks of no movement before aborting.", 30, 10, 100)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue());
    private final NumberSetting<Double> stuckMovementMin = new NumberSetting<>("stuck-min-movement", "Minimum XZ movement per tick to not be stuck.", 0.02, 0.005, 0.2)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue());
    private final NumberSetting<Double> voidAbortY = new NumberSetting<>("void-abort-y", "Abort if Y drops below this.", 0.0, -64.0, 64.0)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue());
    private final BooleanSetting returnAfterPull = new BooleanSetting("return-after-pull", "Walk back to the idle position after pulling.", true)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue());
    private final BooleanSetting walkerSprint = new BooleanSetting("sprint", "Sprint while walking.", true)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue());
    private final BooleanSetting walkerJump = new BooleanSetting("auto-jump", "Jump when stuck.", true)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue());
    private final NumberSetting<Integer> jumpAttemptTicks = new NumberSetting<>("jump-attempt-ticks", "Stuck ticks before jumping.", 8, 2, 30)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue() && walkerJump.getValue());
    private final NumberSetting<Integer> jumpCooldownTicks = new NumberSetting<>("jump-cooldown", "Ticks between jumps.", 12, 4, 40)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue() && walkerJump.getValue());
    private final BooleanSetting sneakOnInteract = new BooleanSetting("sneak-on-interact", "Sneak while interacting with the trapdoor.", true)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue());
    private final BooleanSetting abortOnLowHealth = new BooleanSetting("abort-on-low-health", "Abort the walk if health is low.", true)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant);
    private final NumberSetting<Integer> lowHealthThreshold = new NumberSetting<>("low-health-threshold", "Hearts at which to abort.", 4, 1, 10)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue() && abortOnLowHealth.getValue());
    private final BooleanSetting abortOnFire = new BooleanSetting("abort-on-fire", "Abort the walk if on fire.", false)
        .setVisibility(() -> moduleMode.getValue() == ModuleMode.Assistant && walkerEnabled.getValue());

    // ── Pearls Only ──
    private final BooleanSetting poOutlineEnabled = new BooleanSetting("outline", "Renders an outline around the pearl entity.", true)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PearlsOnly);
    private final ColorSetting poOutlineColor = new ColorSetting("outline-color", "Outline color.", new Color(0, 200, 255, 255))
        .setVisibility(() -> renderMode.getValue() == RenderMode.PearlsOnly && poOutlineEnabled.getValue());
    private final NumberSetting<Integer> poGlowStrength = new NumberSetting<>("glow-strength", "Brightens the outline color channels (1-8).", 3, 1, 8)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PearlsOnly && poOutlineEnabled.getValue());
    private final BooleanSetting poOwnOutlineEnabled = new BooleanSetting("own-outline", "Renders an outline around your own pearl.", true)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PearlsOnly && highlightOwnPearl.getValue());
    private final ColorSetting poOwnOutlineColor = new ColorSetting("own-outline-color", "Own outline color.", new Color(255, 255, 0, 255))
        .setVisibility(() -> renderMode.getValue() == RenderMode.PearlsOnly && poOwnOutlineEnabled.getValue());
    private final NumberSetting<Integer> poOwnGlowStrength = new NumberSetting<>("own-glow-strength", "Own outline brightness.", 3, 1, 8)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PearlsOnly && poOwnOutlineEnabled.getValue());
    private final BooleanSetting poBeamEnabled = new BooleanSetting("beam", "Renders a beam down to the soul sand.", true)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PearlsOnly);
    private final ColorSetting poBeamColor = new ColorSetting("beam-color", "Beam color.", new Color(255, 140, 0, 200))
        .setVisibility(() -> renderMode.getValue() == RenderMode.PearlsOnly && poBeamEnabled.getValue());
    private final NumberSetting<Double> poBeamInnerRadius = new NumberSetting<>("beam-inner-radius", "Beam core radius.", 0.1, 0.01, 1.0)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PearlsOnly && poBeamEnabled.getValue());
    private final NumberSetting<Double> poBeamOuterRadius = new NumberSetting<>("beam-glow-radius", "Beam glow radius.", 0.175, 0.01, 2.0)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PearlsOnly && poBeamEnabled.getValue());
    private final NumberSetting<Integer> poBeamHeight = new NumberSetting<>("beam-height", "How high up the beam shoots.", 64, 1, 319)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PearlsOnly && poBeamEnabled.getValue());
    private final BooleanSetting poOwnBeamEnabled = new BooleanSetting("own-beam", "Renders a beam for your own pearl.", true)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PearlsOnly && highlightOwnPearl.getValue());
    private final ColorSetting poOwnBeamColor = new ColorSetting("own-beam-color", "Own beam color.", new Color(0, 255, 0, 200))
        .setVisibility(() -> renderMode.getValue() == RenderMode.PearlsOnly && poOwnBeamEnabled.getValue());
    private final NumberSetting<Double> poOwnBeamInnerRadius = new NumberSetting<>("own-beam-inner-radius", "Own beam core radius.", 0.1, 0.01, 1.0)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PearlsOnly && poOwnBeamEnabled.getValue());
    private final NumberSetting<Double> poOwnBeamOuterRadius = new NumberSetting<>("own-beam-glow-radius", "Own beam glow radius.", 0.175, 0.01, 2.0)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PearlsOnly && poOwnBeamEnabled.getValue());
    private final NumberSetting<Integer> poOwnBeamHeight = new NumberSetting<>("own-beam-height", "Own beam height.", 64, 1, 319)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PearlsOnly && poOwnBeamEnabled.getValue());

    // ── Auto Disconnect ──
    private final BooleanSetting disconnectEnabled = new BooleanSetting("disconnect-enabled", "Disconnects when another player enters render distance.", false);
    private final BooleanSetting ignoreFriendsOnDisconnect = new BooleanSetting("ignore-friends", "Doesn't disconnect if the entering player is a NeighbourhoodWatch friend.", true)
        .setVisibility(disconnectEnabled::getValue);
    private final BooleanSetting ignoreBotUser = new BooleanSetting("ignore-bot-user", "Doesn't trigger if the entering player is the configured bot username.", true)
        .setVisibility(disconnectEnabled::getValue);
    private final StringSetting disconnectUser = new StringSetting("disconnect-notify-user", "Username to notify before disconnecting (Assistant mode).", "")
        .setVisibility(() -> disconnectEnabled.getValue() && moduleMode.getValue() == ModuleMode.Assistant);
    private final StringSetting disconnectMessage = new StringSetting("disconnect-message", "Message sent to the notify user. {player} = triggering player.", "Disconnecting — {player} entered render distance.")
        .setVisibility(() -> disconnectEnabled.getValue() && moduleMode.getValue() == ModuleMode.Assistant);
    private final NumberSetting<Integer> disconnectAntiSpam = new NumberSetting<>("anti-spam-interval", "Minimum seconds between outgoing disconnect notifications.", 5, 1, 60)
        .setVisibility(() -> disconnectEnabled.getValue() && moduleMode.getValue() == ModuleMode.Assistant);

    private record PearlRecord(int entityId, String owner, BlockPos trapdoor, long discoveredMs) {}

    // ── State ──
    private final AtomicReference<Map<String, Vec3[]>> columnLines = new AtomicReference<>(Collections.emptyMap());
    private final LinkedHashMap<Integer, PearlRecord> pearlMemory = new LinkedHashMap<>();
    private final Set<Integer> seenPearlIds = Collections.synchronizedSet(new HashSet<>());

    private final AtomicBoolean scanPending = new AtomicBoolean(false);
    private final AtomicBoolean pingQueued = new AtomicBoolean(false);
    private final AtomicBoolean pullQueued = new AtomicBoolean(false);
    private final AtomicLong lastTriggerMs = new AtomicLong(0L);

    private volatile String pendingNotifyTarget = null;
    private boolean wasSelfTriggerPressed = false;
    private int tickCounter = 0;

    private WalkState walkState = WalkState.IDLE;
    private BlockPos walkTarget = null;
    private Vec3 idlePosition = null;
    private final Deque<Vec3> waypoints = new ArrayDeque<>();
    private int walkTicks = 0;
    private Vec3 lastPos = null;
    private int stuckTicks = 0;
    private int jumpCooldown = 0;

    private final Set<Integer> trackedGlowIds = Collections.synchronizedSet(new HashSet<>());

    private volatile long lastDisconnectMsgMs = 0L;
    private volatile boolean disconnectArmed = true;

    public Waypearl() {
        super("waypearl", "Detects nearby stasis pearls sitting above bubble columns.", Tim.CATEGORY);
        this.registerSettings(
            range, scanInterval, renderMode, highlightOwnPearl, ownPearlColor,
            columnsEnabled, coreColor, glowColor, coreWidth, glowSpread, glowLayers, glowBaseAlpha,
            capPosition, capColor, capSize, capThickness, capShapeMode, capGlow,
            soundEnabled, pingSound, soundVolume, soundPitch,
            moduleMode, botUsername, triggerPhrase, whitelist, selfTriggerKey, pullCooldown, pullOrder, notifyEnabled, notifyMessage,
            walkerEnabled, interactReach, slowZoneRadius, waypointSpacing, walkTimeoutTicks, returnTimeoutTicks, returnTolerance,
            stuckThresholdTicks, stuckMovementMin, voidAbortY, returnAfterPull, walkerSprint, walkerJump, jumpAttemptTicks,
            jumpCooldownTicks, sneakOnInteract, abortOnLowHealth, lowHealthThreshold, abortOnFire,
            poOutlineEnabled, poOutlineColor, poGlowStrength, poOwnOutlineEnabled, poOwnOutlineColor, poOwnGlowStrength,
            poBeamEnabled, poBeamColor, poBeamInnerRadius, poBeamOuterRadius, poBeamHeight,
            poOwnBeamEnabled, poOwnBeamColor, poOwnBeamInnerRadius, poOwnBeamOuterRadius, poOwnBeamHeight,
            disconnectEnabled, ignoreFriendsOnDisconnect, ignoreBotUser, disconnectUser, disconnectMessage, disconnectAntiSpam
        );
    }

    public int getRange() { return range.getValue(); }
    public Set<Integer> getSeenPearlIds() { return seenPearlIds; }

    // ── Lifecycle ──
    @Override
    public void onEnable() {
        columnLines.set(Collections.emptyMap());
        synchronized (pearlMemory) { pearlMemory.clear(); }
        seenPearlIds.clear();
        pingQueued.set(false);
        pullQueued.set(false);
        scanPending.set(false);
        lastTriggerMs.set(0L);
        pendingNotifyTarget = null;
        tickCounter = 0;
        resetWalker();
        wasSelfTriggerPressed = false;

        lastDisconnectMsgMs = 0L;
        disconnectArmed = true;

        for (int id : trackedGlowIds) GlowingRegistry.remove(id);
        trackedGlowIds.clear();
        BEAM_POS_CACHE.clear();
    }

    @Override
    public void onDisable() {
        columnLines.set(Collections.emptyMap());
        synchronized (pearlMemory) { pearlMemory.clear(); }
        seenPearlIds.clear();
        pingQueued.set(false);
        pullQueued.set(false);
        scanPending.set(false);
        stopMovement();
        resetWalker();

        lastDisconnectMsgMs = 0L;
        disconnectArmed = true;

        for (int id : trackedGlowIds) GlowingRegistry.remove(id);
        trackedGlowIds.clear();
        BEAM_POS_CACHE.clear();
    }

    @Subscribe
    private void onGameJoined(EventLoadWorld event) {
        disconnectArmed = true;
        lastDisconnectMsgMs = 0L;
    }

    // ── Chat listener ──
    @Subscribe
    private void onReceiveMessage(EventAddChat event) {
        if (moduleMode.getValue() != ModuleMode.Assistant) return;
        String raw = event.getChatComponent().getString().trim();

        if (raw.startsWith("<")) {
            int closeAngle = raw.indexOf('>');
            if (closeAngle >= 2) {
                evaluateTrigger(raw.substring(1, closeAngle), raw.substring(closeAngle + 1).trim(), false);
                return;
            }
        }

        if (raw.startsWith("[") && raw.contains("->") && raw.contains("]:")) {
            int arrowIdx = raw.indexOf("->");
            int bracketEnd = raw.indexOf("]:");
            if (arrowIdx > 1 && bracketEnd > arrowIdx) {
                evaluateTrigger(raw.substring(1, arrowIdx).trim(), raw.substring(bracketEnd + 2).trim(), true);
                return;
            }
        }

        String tag3 = " whispers to you: ";
        int idx3 = raw.indexOf(tag3);
        if (idx3 > 0) {
            evaluateTrigger(raw.substring(0, idx3).trim(), raw.substring(idx3 + tag3.length()).trim(), true);
            return;
        }

        String tag4 = " whispers: ";
        int idx4 = raw.indexOf(tag4);
        if (idx4 > 0) {
            evaluateTrigger(raw.substring(0, idx4).trim(), raw.substring(idx4 + tag4.length()).trim(), true);
        }
    }

    private void evaluateTrigger(String senderName, String content, boolean isWhisper) {
        if (mc.player == null) return;
        String bot = botUsername.getValue().trim();
        if (bot.isEmpty()) return;

        boolean whitelisted = false;
        for (String name : csv(whitelist.getValue())) {
            if (name.equals(senderName)) { whitelisted = true; break; }
        }
        if (!whitelisted) return;

        String phraseRaw = triggerPhrase.getValue().trim();
        if (phraseRaw.isEmpty()) return;
        boolean keywordMatched = false;
        for (String kw : phraseRaw.split(",")) {
            String k = kw.trim();
            if (!k.isEmpty() && content.contains(k)) { keywordMatched = true; break; }
        }
        if (!keywordMatched) return;

        if (!isWhisper && !content.contains(bot)) return;

        long nowMs = System.currentTimeMillis();
        if (nowMs - lastTriggerMs.get() < pullCooldown.getValue() * 1000L) {
            info("Cooldown active — ignoring.");
            return;
        }

        if (walkState != WalkState.IDLE && walkState != WalkState.ABORTED) {
            info("Walker active — ignoring.");
            return;
        }

        lastTriggerMs.set(nowMs);
        pendingNotifyTarget = senderName;
        pullQueued.set(true);
        info("Pull triggered by " + senderName + ".");
    }

    // ── Player proximity → Disconnect ──
    @Subscribe
    private void onEntityAdded(EventEntity.Add event) {
        if (!disconnectEnabled.getValue()) return;
        if (mc.level == null || mc.player == null || mc.player.connection == null) return;

        if (!(event.getEntity() instanceof Player enteringPlayer)) return;
        if (enteringPlayer == mc.player || enteringPlayer.getId() == mc.player.getId()) return;

        String enteringName = enteringPlayer.getName().getString();

        String botName = botUsername.getValue().trim();
        if (ignoreBotUser.getValue() && !botName.isEmpty() && enteringName.equalsIgnoreCase(botName)) return;

        if (ignoreFriendsOnDisconnect.getValue()) {
            NeighbourhoodWatch nw = getNeighbourhoodWatch();
            if (nw != null && nw.isToggled() && nw.isFriend(enteringName)) return;
        }

        if (!disconnectArmed) return;
        disconnectArmed = false;

        long now = System.currentTimeMillis();
        boolean isAssistant = moduleMode.getValue() == ModuleMode.Assistant;

        if (isAssistant) {
            String targetUser = disconnectUser.getValue().trim();

            if (targetUser.isEmpty()) {
                info("No target user configured — leaving immediately.");
            } else if (isPlayerOnline(targetUser)) {
                long minMs = disconnectAntiSpam.getValue() * 1000L;
                if (now - lastDisconnectMsgMs < minMs) {
                    info("Anti-spam active — skipping message, disconnecting.");
                } else {
                    lastDisconnectMsgMs = now;
                    try {
                        String msg = disconnectMessage.getValue().replace("{player}", enteringName);
                        mc.player.connection.sendChat("/msg " + targetUser + " " + msg);
                        info("Sent disconnect notice to " + targetUser + " — " + enteringName + " entered render distance.");
                    } catch (Exception ignored) {}
                }
            } else {
                info("Target user " + targetUser + " is offline — leaving immediately.");
            }
        } else {
            info("Requester mode — disconnecting without message (" + enteringName + " entered render distance).");
        }

        Thread.ofVirtual().name("Waypearl-disconnect").start(() -> {
            try {
                Thread.sleep(150L);
                if (mc.level != null && mc.player != null) {
                    mc.level.disconnect();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private NeighbourhoodWatch getNeighbourhoodWatch() {
        return (NeighbourhoodWatch) RusherHackAPI.getModuleManager().getFeature("neighbourhood-watch").orElse(null);
    }

    private boolean isPlayerOnline(String username) {
        if (username == null || username.isEmpty()) return false;
        if (mc.player == null || mc.player.connection == null) return false;

        PlayerInfo entry = mc.player.connection.getPlayerInfo(username);
        if (entry != null) return true;

        for (PlayerInfo ple : mc.player.connection.getListedOnlinePlayers()) {
            if (ple != null && ple.getProfile() != null && username.equalsIgnoreCase(ple.getProfile().getName())) {
                return true;
            }
        }
        return false;
    }

    // ── Tick ──
    @Subscribe
    private void onTick(EventUpdate event) {
        if (mc.level == null || mc.player == null) return;
        if (mc.level.dimension() == Level.NETHER) return;

        boolean selfPressed = selfTriggerKey.getValue().isKeyDown();
        if (selfPressed && !wasSelfTriggerPressed && moduleMode.getValue() == ModuleMode.Requester) {
            if (mc.screen == null && (walkState == WalkState.IDLE || walkState == WalkState.ABORTED)) {
                long now = System.currentTimeMillis();
                if (now - lastTriggerMs.get() >= pullCooldown.getValue() * 1000L) {
                    sendSelfTrigger();
                }
            }
        }
        wasSelfTriggerPressed = selfPressed;

        if (pingQueued.compareAndSet(true, false) && soundEnabled.getValue()) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                pingSound.getValue().sound,
                soundPitch.getValue().floatValue(),
                soundVolume.getValue().floatValue()));
        }

        if (pullQueued.compareAndSet(true, false)) {
            String requester = pendingNotifyTarget;
            sendNotifyMessage(requester);
            executePull(requester);
            pendingNotifyTarget = null;
        }

        tickWalker();
        updatePearlMemory();

        tickCounter++;
        if (tickCounter >= scanInterval.getValue()) {
            tickCounter = 0;
            triggerColumnScan();
        }
    }

    private void sendSelfTrigger() {
        String bot = botUsername.getValue().trim();
        if (bot.isEmpty()) return;

        String[] phrases = triggerPhrase.getValue().split(",");
        List<String> validPhrases = new ArrayList<>();
        for (String p : phrases) {
            String t = p.trim();
            if (!t.isEmpty()) validPhrases.add(t);
        }

        String phrase = validPhrases.isEmpty() ? "tp"
            : validPhrases.get((int) (Math.random() * validPhrases.size()));

        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(chars.charAt((int) (Math.random() * chars.length())));
        }

        mc.player.connection.sendCommand("msg " + bot + " " + phrase + " " + sb);
        info("Sent pull request to " + bot + " (phrase: " + phrase + ").");
    }

    // ── Pearl memory update ──
    private void updatePearlMemory() {
        Map<String, Vec3[]> lines = columnLines.get();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getType() != EntityType.ENDER_PEARL) continue;
            if (mc.player.distanceTo(e) > range.getValue()) continue;

            int px = (int) Math.floor(e.getX());
            int pz = (int) Math.floor(e.getZ());
            String key = px + "," + pz;
            if (!lines.containsKey(key)) continue;
            if (!seenPearlIds.add(e.getId())) continue;

            Vec3[] line = lines.get(key);
            int topY = (int) Math.floor(line[1].y);
            BlockPos trapdoor = findTrapdoor(px, topY, pz);

            String ownerName = "unknown";
            if (e instanceof ThrownEnderpearl pearl) {
                Entity owner = pearl.getOwner();
                if (owner != null) ownerName = owner.getName().getString();
            }

            synchronized (pearlMemory) {
                pearlMemory.put(e.getId(), new PearlRecord(e.getId(), ownerName, trapdoor, System.currentTimeMillis()));
            }

            pingQueued.set(true);
            info("New stasis pearl — owner: " + ownerName);
        }
    }

    // ── Notify & Pull execution ──
    private void sendNotifyMessage(String target) {
        if (!notifyEnabled.getValue()) return;
        if (target == null || target.isEmpty() || mc.player == null) return;
        String msg = notifyMessage.getValue().replace("{player}", target);
        mc.player.connection.sendChat("/msg " + target + " " + msg);
    }

    private void executePull(String requester) {
        if (mc.level == null || mc.player == null) return;

        String botName = botUsername.getValue().trim();

        List<PearlRecord> candidates = new ArrayList<>();
        synchronized (pearlMemory) {
            for (PearlRecord rec : pearlMemory.values()) {
                if (rec.trapdoor() == null) continue;
                if (!pearlStillExists(rec.entityId())) continue;
                if (!isAnyTrapdoor(mc.level.getBlockState(rec.trapdoor()).getBlock())) continue;
                if (!botName.isEmpty() && rec.owner().equalsIgnoreCase(botName)) continue;
                candidates.add(rec);
            }
        }

        if (candidates.isEmpty()) candidates = scanLiveTargets();

        if (requester != null) {
            candidates.removeIf(rec ->
                !rec.owner().equalsIgnoreCase(requester) && !rec.owner().equalsIgnoreCase("unknown"));
        }

        if (candidates.isEmpty()) {
            info("No valid stasis pearls found for " + (requester != null ? requester : "anyone") + ".");
            return;
        }

        Vec3 playerPos = mc.player.position();

        candidates.sort((a, b) -> {
            if (requester != null) {
                boolean aMine = a.owner().equalsIgnoreCase(requester);
                boolean bMine = b.owner().equalsIgnoreCase(requester);
                if (aMine && !bMine) return -1;
                if (!aMine && bMine) return 1;
            }
            if (pullOrder.getValue() == PullOrder.NEAREST) {
                double da = playerPos.distanceToSqr(Vec3.atCenterOf(a.trapdoor()));
                double db = playerPos.distanceToSqr(Vec3.atCenterOf(b.trapdoor()));
                return Double.compare(da, db);
            }
            return 0;
        });

        dispatchTarget(candidates.get(0).trapdoor());
    }

    private boolean pearlStillExists(int entityId) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getId() == entityId && e.getType() == EntityType.ENDER_PEARL) return true;
        }
        return false;
    }

    private List<PearlRecord> scanLiveTargets() {
        List<PearlRecord> found = new ArrayList<>();
        Map<String, Vec3[]> lines = columnLines.get();
        String botName = botUsername.getValue().trim();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getType() != EntityType.ENDER_PEARL) continue;
            if (mc.player.distanceTo(e) > range.getValue()) continue;

            String ownerName = "unknown";
            if (e instanceof ThrownEnderpearl pearl) {
                Entity owner = pearl.getOwner();
                if (owner != null) ownerName = owner.getName().getString();
            }

            if (!botName.isEmpty() && ownerName.equalsIgnoreCase(botName)) continue;

            int px = (int) Math.floor(e.getX());
            int pz = (int) Math.floor(e.getZ());
            String key = px + "," + pz;
            Vec3[] line = lines.get(key);
            if (line == null) continue;

            int topY = (int) Math.floor(line[1].y);
            BlockPos trapdoor = findTrapdoor(px, topY, pz);
            if (trapdoor == null) continue;

            found.add(new PearlRecord(e.getId(), ownerName, trapdoor, 0L));
        }
        return found;
    }

    private void dispatchTarget(BlockPos trapdoor) {
        double dist = mc.player.position().distanceTo(Vec3.atCenterOf(trapdoor));
        if (dist <= interactReach.getValue()) {
            interactTrapdoor(trapdoor);
        } else if (walkerEnabled.getValue()) {
            beginWalk(trapdoor);
        } else {
            info("Trapdoor out of reach and walker is disabled.");
        }
    }

    // ── Walker — state machine ──
    private void beginWalk(BlockPos target) {
        walkTarget = target;
        idlePosition = mc.player.position();
        walkState = WalkState.WALKING_TO;
        walkTicks = 0;
        stuckTicks = 0;
        jumpCooldown = 0;
        lastPos = idlePosition;
        waypoints.clear();

        List<Vec3> path = findPath(mc.player.blockPosition(), target);
        if (path != null) {
            waypoints.addAll(path);
        } else {
            abortWalk("No clear path to trapdoor found.");
            return;
        }

        info("Path found (" + waypoints.size() + " waypoints).");
    }

    private void tickWalker() {
        if (walkState == WalkState.IDLE || walkState == WalkState.ABORTED) return;
        if (mc.player == null || mc.level == null) {
            abortWalk("Player/world null.");
            return;
        }

        Vec3 pos = mc.player.position();

        if (pos.y < voidAbortY.getValue()) {
            abortWalk("Fell below void-abort Y (" + voidAbortY.getValue() + ").");
            return;
        }

        if (abortOnLowHealth.getValue() && mc.player.getHealth() <= lowHealthThreshold.getValue() * 2f) {
            abortWalk("Health low (" + String.format("%.1f", mc.player.getHealth()) + ").");
            return;
        }

        if (mc.player.isOnFire()) {
            if (abortOnFire.getValue()) {
                abortWalk("Player on fire.");
                return;
            }
            info("Warning: on fire.");
        }

        if (jumpCooldown > 0) jumpCooldown--;

        switch (walkState) {
            case WALKING_TO -> {
                walkTicks++;

                if (walkTicks > walkTimeoutTicks.getValue()) {
                    abortWalk("Timed out after " + walkTicks + " ticks.");
                    return;
                }

                if (lastPos != null) {
                    double moved = xzDist(pos, lastPos);
                    if (moved < stuckMovementMin.getValue()) {
                        stuckTicks++;
                        if (walkerJump.getValue() && stuckTicks >= jumpAttemptTicks.getValue()
                                && jumpCooldown == 0 && mc.player.onGround()) {
                            mc.player.jumpFromGround();
                            jumpCooldown = jumpCooldownTicks.getValue();
                            stuckTicks = 0;
                        }
                        if (stuckTicks >= stuckThresholdTicks.getValue()) {
                            abortWalk("Stuck for " + stuckTicks + " ticks.");
                            return;
                        }
                    } else {
                        stuckTicks = 0;
                    }
                }
                lastPos = pos;

                if (walkTarget != null && !isAnyTrapdoor(mc.level.getBlockState(walkTarget).getBlock())) {
                    abortWalk("Trapdoor vanished.");
                    return;
                }

                Vec3 currentWaypoint = waypoints.peek();
                if (currentWaypoint == null) {
                    stopMovement();
                    walkState = WalkState.PULLING;
                    walkTicks = 0;
                    return;
                }

                double distToWaypoint = pos.distanceTo(currentWaypoint);
                boolean isFinalWaypoint = waypoints.size() == 1;

                if (isFinalWaypoint && distToWaypoint <= interactReach.getValue()) {
                    stopMovement();
                    walkState = WalkState.PULLING;
                    walkTicks = 0;
                    return;
                }

                double advanceThreshold = isFinalWaypoint ? interactReach.getValue() : waypointSpacing.getValue() * 0.5;
                if (distToWaypoint <= advanceThreshold) {
                    waypoints.poll();
                    return;
                }

                double distToFinal = walkTarget != null
                    ? pos.distanceTo(Vec3.atCenterOf(walkTarget)) : distToWaypoint;
                boolean inSlowZone = distToFinal <= slowZoneRadius.getValue();
                faceAndWalkToward(pos, currentWaypoint, !inSlowZone);
            }

            case PULLING -> {
                stopMovement();
                if (sneakOnInteract.getValue()) {
                    mc.options.keyShift.setDown(true);
                    mc.player.setShiftKeyDown(true);
                }
                interactTrapdoor(walkTarget);
                if (sneakOnInteract.getValue()) {
                    mc.options.keyShift.setDown(false);
                    mc.player.setShiftKeyDown(false);
                }

                if (returnAfterPull.getValue() && idlePosition != null) {
                    walkState = WalkState.WALKING_BACK;
                    walkTicks = 0;
                    stuckTicks = 0;
                    lastPos = mc.player.position();
                    buildReturnWaypoints();
                    info("Returning to idle.");
                } else {
                    walkState = WalkState.IDLE;
                    resetWalkerFields();
                }
            }

            case WALKING_BACK -> {
                walkTicks++;

                if (walkTicks > returnTimeoutTicks.getValue()) {
                    info("Return timed out; stopping here.");
                    stopMovement();
                    walkState = WalkState.IDLE;
                    resetWalkerFields();
                    return;
                }

                if (lastPos != null) {
                    double moved = xzDist(pos, lastPos);
                    if (moved < stuckMovementMin.getValue()) {
                        stuckTicks++;
                        if (walkerJump.getValue() && stuckTicks >= jumpAttemptTicks.getValue()
                                && jumpCooldown == 0 && mc.player.onGround()) {
                            mc.player.jumpFromGround();
                            jumpCooldown = jumpCooldownTicks.getValue();
                            stuckTicks = 0;
                        }
                        if (stuckTicks >= stuckThresholdTicks.getValue()) {
                            info("Stuck returning; stopping here.");
                            stopMovement();
                            walkState = WalkState.IDLE;
                            resetWalkerFields();
                            return;
                        }
                    } else {
                        stuckTicks = 0;
                    }
                }
                lastPos = pos;

                Vec3 currentWaypoint = waypoints.peek();
                if (currentWaypoint == null) {
                    snapToIdle();
                    return;
                }

                double distToWaypoint = pos.distanceTo(currentWaypoint);
                boolean isFinal = waypoints.size() == 1;

                if (isFinal && distToWaypoint <= returnTolerance.getValue()) {
                    snapToIdle();
                    return;
                }

                double advanceThreshold = isFinal ? returnTolerance.getValue() : waypointSpacing.getValue() * 0.5;
                if (distToWaypoint <= advanceThreshold) {
                    waypoints.poll();
                    return;
                }

                double distToHome = pos.distanceTo(idlePosition);
                boolean inSlowZone = distToHome <= slowZoneRadius.getValue();
                faceAndWalkToward(pos, currentWaypoint, !inSlowZone);
            }

            default -> {}
        }
    }

    private void snapToIdle() {
        stopMovement();
        mc.player.setPos(idlePosition.x, idlePosition.y, idlePosition.z);
        info("Returned to idle position.");
        walkState = WalkState.IDLE;
        resetWalkerFields();
    }

    private void buildReturnWaypoints() {
        waypoints.clear();
        if (idlePosition == null) return;

        List<Vec3> path = findPath(mc.player.blockPosition(), BlockPos.containing(idlePosition));
        if (path != null) waypoints.addAll(path);
    }

    private List<Vec3> findPath(BlockPos start, BlockPos end) {
        Queue<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();

        queue.add(start);
        visited.add(start);

        BlockPos currentEnd = null;
        int iterations = 0;

        while (!queue.isEmpty() && iterations < 1500) {
            iterations++;
            BlockPos curr = queue.poll();

            if (curr.distSqr(end) <= interactReach.getValue() * interactReach.getValue()) {
                currentEnd = curr;
                break;
            }

            for (Direction dir : Direction.values()) {
                if (dir.getAxis().isHorizontal()) {
                    BlockPos next = curr.relative(dir);
                    if (isWalkable(next) && !visited.contains(next)) {
                        visited.add(next);
                        parents.put(next, curr);
                        queue.add(next);
                    } else if (isPassable(curr.above(2)) && isWalkable(next.above()) && !visited.contains(next.above())) {
                        BlockPos up = next.above();
                        visited.add(up);
                        parents.put(up, curr);
                        queue.add(up);
                    } else if (isPassable(next) && isPassable(next.above())) {
                        BlockPos drop = next.below();
                        if (isWalkable(drop) && !visited.contains(drop)) {
                            visited.add(drop);
                            parents.put(drop, curr);
                            queue.add(drop);
                        }
                    }
                }
            }
        }

        if (currentEnd == null) return null;

        List<Vec3> path = new ArrayList<>();
        BlockPos p = currentEnd;
        while (p != null) {
            path.add(Vec3.atCenterOf(p));
            p = parents.get(p);
        }
        Collections.reverse(path);
        return path;
    }

    private boolean isPassable(BlockPos pos) {
        return mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    private boolean isWalkable(BlockPos pos) {
        return isPassable(pos) && isPassable(pos.above()) && !isPassable(pos.below());
    }

    private void faceAndWalkToward(Vec3 from, Vec3 to, boolean sprint) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;

        float pYaw = Mth.wrapDegrees(mc.player.getYRot());
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float diff = Mth.wrapDegrees(targetYaw - pYaw);

        mc.options.keyUp.setDown(diff > -67.5 && diff <= 67.5);
        mc.options.keyDown.setDown(diff > 112.5 || diff <= -112.5);
        mc.options.keyLeft.setDown(diff > -157.5 && diff <= -22.5);
        mc.options.keyRight.setDown(diff > 22.5 && diff <= 157.5);

        boolean doSprint = walkerSprint.getValue() && sprint;
        mc.options.keySprint.setDown(doSprint);
        mc.player.setSprinting(doSprint);
    }

    private void stopMovement() {
        if (mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyShift.setDown(false);
        if (mc.player != null) {
            mc.player.setSprinting(false);
            mc.player.setShiftKeyDown(false);
        }
    }

    private void abortWalk(String reason) {
        stopMovement();
        walkState = WalkState.ABORTED;
        resetWalkerFields();
        info("Walker aborted: " + reason);
    }

    private void resetWalkerFields() {
        walkTarget = null;
        idlePosition = null;
        walkTicks = 0;
        stuckTicks = 0;
        jumpCooldown = 0;
        lastPos = null;
        waypoints.clear();
    }

    private void resetWalker() {
        walkState = WalkState.IDLE;
        resetWalkerFields();
    }

    // ── Trapdoor helpers ──
    private BlockPos findTrapdoor(int x, int topY, int z) {
        for (int dy = 0; dy <= 3; dy++) {
            BlockPos pos = new BlockPos(x, topY + dy, z);
            if (isAnyTrapdoor(mc.level.getBlockState(pos).getBlock())) return pos;
        }
        return null;
    }

    private boolean isAnyTrapdoor(Block block) {
        return block instanceof TrapDoorBlock;
    }

    private void interactTrapdoor(BlockPos pos) {
        if (pos == null) return;
        RusherHackAPI.getRotationManager().updateRotation(pos);
        Vec3 hitVec = Vec3.atCenterOf(pos);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);
        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        info(result.consumesAction() ? "Trapdoor activated." : "Interaction failed.");
    }

    // ── Background column scan ──
    private void triggerColumnScan() {
        if (!scanPending.compareAndSet(false, true)) return;

        final BlockPos origin = mc.player.blockPosition();
        final int r = range.getValue();
        final int chunkR = (r >> 4) + 1;
        final int originCX = origin.getX() >> 4;
        final int originCZ = origin.getZ() >> 4;

        final Map<Long, LevelChunk> snapshot = new HashMap<>();
        for (int cx = originCX - chunkR; cx <= originCX + chunkR; cx++) {
            for (int cz = originCZ - chunkR; cz <= originCZ + chunkR; cz++) {
                if (mc.level.getChunkSource().hasChunk(cx, cz)) {
                    snapshot.put(ChunkPos.asLong(cx, cz), mc.level.getChunk(cx, cz));
                }
            }
        }

        Thread.ofVirtual().name("Waypearl-scan").start(() -> {
            try { runColumnScan(origin, r, snapshot); }
            finally { scanPending.set(false); }
        });
    }

    private void runColumnScan(BlockPos origin, int r, Map<Long, LevelChunk> chunks) {
        Map<String, int[]> yExtents = new HashMap<>();
        Map<String, Vec3[]> newLines = new LinkedHashMap<>();

        int minX = origin.getX() - r, maxX = origin.getX() + r,
            minY = Math.max(origin.getY() - r, -64),
            maxY = Math.min(origin.getY() + r, 320),
            minZ = origin.getZ() - r, maxZ = origin.getZ() + r;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                LevelChunk chunk = chunks.get(ChunkPos.asLong(x >> 4, z >> 4));
                if (chunk == null) continue;

                int lowestY = Integer.MAX_VALUE, highestY = Integer.MIN_VALUE;
                for (int y = minY; y <= maxY; y++) {
                    if (!chunk.getBlockState(new BlockPos(x, y, z)).is(Blocks.BUBBLE_COLUMN)) continue;
                    if (y < lowestY) lowestY = y;
                    if (y > highestY) highestY = y;
                }
                if (lowestY == Integer.MAX_VALUE) continue;

                int srcY = lowestY - 1, srcFloor = Math.max(srcY - 384, -64);
                while (srcY >= srcFloor && chunk.getBlockState(new BlockPos(x, srcY, z)).is(Blocks.BUBBLE_COLUMN)) srcY--;

                if (!chunk.getBlockState(new BlockPos(x, srcY, z)).is(Blocks.SOUL_SAND)) continue;

                String key = x + "," + z;
                int fx = x, fz = z, flo = lowestY, fhi = highestY;
                yExtents.compute(key, (k, e) -> {
                    if (e == null) return new int[]{ fx, flo, fhi, fz };
                    e[1] = Math.min(e[1], flo);
                    e[2] = Math.max(e[2], fhi);
                    return e;
                });
            }
        }

        for (Map.Entry<String, int[]> entry : yExtents.entrySet()) {
            int[] e = entry.getValue();
            newLines.put(entry.getKey(), new Vec3[]{
                new Vec3(e[0] + 0.5, e[1], e[3] + 0.5),
                new Vec3(e[0] + 0.5, e[2] + 1, e[3] + 0.5)
            });
        }
        columnLines.set(newLines);
    }

    // ── Render ──
    @Subscribe
    private void onRender3D(EventRender3D event) {
        if (mc.level == null || mc.player == null) return;

        IRenderer3D r = event.getRenderer();
        r.begin(event.getMatrixStack());

        if (renderMode.getValue() == RenderMode.Default) {
            renderDefaultMode(r, event.getPartialTicks());
        } else {
            renderPearlsOnlyMode(r);
        }

        r.end();
    }

    private void renderDefaultMode(IRenderer3D r, float partialTicks) {
        boolean doBeam = columnsEnabled.getValue();
        CapPosition cap = capPosition.getValue();
        boolean doCap = cap != CapPosition.NONE;
        if (!doBeam && !doCap) return;

        int range0 = range.getValue();
        Map<String, Vec3[]> lines = columnLines.get();

        Map<String, Vec3> activePearlPos = new HashMap<>();
        Set<Integer> activePearlIds = new HashSet<>();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getType() != EntityType.ENDER_PEARL) continue;
            if (mc.player.distanceTo(e) > range0) continue;

            Vec3 pos = e.getPosition(partialTicks);
            int px = (int) Math.floor(e.getX());
            int pz = (int) Math.floor(e.getZ());
            String key = px + "," + pz;
            Vec3[] line = lines.get(key);

            if (line != null && pos.y >= line[0].y) {
                activePearlPos.put(key, pos);
                activePearlIds.add(e.getId());
            }
        }

        Color core = coreColor.getValue();
        Color glow = glowColor.getValue();
        double halfCore = coreWidth.getValue();
        double spread = glowSpread.getValue();
        int layers = glowLayers.getValue();
        int baseAlpha = glowBaseAlpha.getValue();

        Color capCol = capColor.getValue();
        double capHalf = capSize.getValue();
        double capThick = capThickness.getValue();
        BoxMode capMode = capShapeMode.getValue();
        boolean capBloom = capGlow.getValue();

        for (Map.Entry<String, Vec3[]> entry : lines.entrySet()) {
            Vec3 pearlPos = activePearlPos.get(entry.getKey());
            if (pearlPos == null) continue;

            Vec3[] line = entry.getValue();

            double cx = pearlPos.x;
            double cz = pearlPos.z;
            double botY = line[0].y;
            double topY = pearlPos.y;

            if (doBeam) {
                drawGlowBeam(r, cx, botY, cz, topY, core, glow, halfCore, spread, layers, baseAlpha);
            }

            if (doCap) {
                boolean db = (cap == CapPosition.BOTTOM || cap == CapPosition.BOTH);
                boolean dt = (cap == CapPosition.TOP || cap == CapPosition.BOTH);
                if (db) drawCapBox(r, cx, botY, cz, capHalf, capThick, capCol, capMode, capBloom, spread, layers, baseAlpha);
                if (dt) drawCapBox(r, cx, topY, cz, capHalf, capThick, capCol, capMode, capBloom, spread, layers, baseAlpha);
            }
        }

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ThrownEnderpearl pearl)) continue;
            if (mc.player.distanceTo(e) > range0) continue;
            if (!activePearlIds.contains(pearl.getId())) continue;

            boolean isOwn = pearl.getOwner() != null && pearl.getOwner().equals(mc.player);

            if (isOwn && highlightOwnPearl.getValue()) {
                GlowingRegistry.add(pearl.getId(), buildGlowArgb(ownPearlColor.getValue(), 3));
                trackedGlowIds.add(pearl.getId());
            } else if (!isOwn) {
                GlowingRegistry.add(pearl.getId(), buildGlowArgb(coreColor.getValue(), 3));
                trackedGlowIds.add(pearl.getId());
            }
        }

        for (Iterator<Integer> it = trackedGlowIds.iterator(); it.hasNext(); ) {
            int id = it.next();
            if (!activePearlIds.contains(id) || mc.level.getEntity(id) == null) {
                GlowingRegistry.remove(id);
                it.remove();
            }
        }
    }

    private void renderPearlsOnlyMode(IRenderer3D r) {
        int range0 = range.getValue();
        Set<Integer> activePearlIds = new HashSet<>();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ThrownEnderpearl pearl)) continue;
            if (mc.player.distanceTo(e) > range0) continue;

            int pearlId = pearl.getId();
            activePearlIds.add(pearlId);
            boolean isOwn = pearl.getOwner() != null && pearl.getOwner().equals(mc.player);

            if (isOwn && highlightOwnPearl.getValue()) {
                if (poOwnOutlineEnabled.getValue()) {
                    GlowingRegistry.add(pearlId, buildGlowArgb(poOwnOutlineColor.getValue(), poOwnGlowStrength.getValue()));
                    trackedGlowIds.add(pearlId);
                } else {
                    GlowingRegistry.remove(pearlId);
                    trackedGlowIds.remove(pearlId);
                }
            } else {
                if (poOutlineEnabled.getValue()) {
                    GlowingRegistry.add(pearlId, buildGlowArgb(poOutlineColor.getValue(), poGlowStrength.getValue()));
                    trackedGlowIds.add(pearlId);
                } else {
                    GlowingRegistry.remove(pearlId);
                    trackedGlowIds.remove(pearlId);
                }
            }

            if (isInBubbleColumn(pearl)) {
                if (isOwn && highlightOwnPearl.getValue()) {
                    if (poOwnBeamEnabled.getValue()) {
                        renderPearlsOnlyBeam(r, pearl, poOwnBeamColor.getValue(),
                            poOwnBeamInnerRadius.getValue(), poOwnBeamOuterRadius.getValue(), poOwnBeamHeight.getValue());
                    }
                } else {
                    if (poBeamEnabled.getValue()) {
                        renderPearlsOnlyBeam(r, pearl, poBeamColor.getValue(),
                            poBeamInnerRadius.getValue(), poBeamOuterRadius.getValue(), poBeamHeight.getValue());
                    }
                }
            }
        }

        for (Iterator<Integer> it = trackedGlowIds.iterator(); it.hasNext(); ) {
            int id = it.next();
            if (!activePearlIds.contains(id) || mc.level.getEntity(id) == null) {
                GlowingRegistry.remove(id);
                it.remove();
            }
        }

        BEAM_POS_CACHE.keySet().removeIf(id -> mc.level.getEntity(id) == null);
    }

    // ── Render Helpers ──
    private int buildGlowArgb(Color c, int strength) {
        float factor = 1f + (strength - 1) * 0.15f;
        int r = Math.min(255, (int) (c.getRed() * factor));
        int g = Math.min(255, (int) (c.getGreen() * factor));
        int b = Math.min(255, (int) (c.getBlue() * factor));
        return (255 << 24) | (r << 16) | (g << 8) | b;
    }

    private boolean isInBubbleColumn(ThrownEnderpearl pearl) {
        if (mc.level == null) return false;
        BlockPos pos = BlockPos.containing(pearl.position());
        return mc.level.getBlockState(pos).is(Blocks.BUBBLE_COLUMN)
            || mc.level.getBlockState(pos.below()).is(Blocks.BUBBLE_COLUMN);
    }

    private Vec3 getOrCreateBeamPos(ThrownEnderpearl pearl) {
        return BEAM_POS_CACHE.computeIfAbsent(pearl.getId(), id -> {
            double x = pearl.getX();
            double y = pearl.getY();
            double z = pearl.getZ();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            for (int i = 1; i < 256; i++) {
                pos.set((int) Math.floor(x), (int) (y - i), (int) Math.floor(z));
                BlockState state = mc.level.getBlockState(pos);

                if (!state.is(Blocks.WATER) && !state.is(Blocks.BUBBLE_COLUMN)) {
                    return new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                }
            }
            return new Vec3(Math.floor(x) + 0.5, y - 256, Math.floor(z) + 0.5);
        });
    }

    private void renderPearlsOnlyBeam(IRenderer3D r, ThrownEnderpearl pearl, Color color, double innerRadius, double outerRadius, int height) {
        Vec3 base = getOrCreateBeamPos(pearl);
        double cx = base.x, cz = base.z;
        double botY = base.y;
        double topY = base.y + height;

        for (int i = 3; i >= 1; i--) {
            double exp = (outerRadius - innerRadius) * i / 3.0 + innerRadius;
            int alpha = Math.max(4, (int) (color.getAlpha() * (1.0 - (i - 1) / 3.0)));
            dbBox(r, cx - exp, botY, cz - exp, cx + exp, topY, cz + exp, true, false, RenderUtils.withAlpha(color, alpha));
        }
        dbBox(r, cx - innerRadius, botY, cz - innerRadius, cx + innerRadius, topY, cz + innerRadius, true, true, color.getRGB());
    }

    private void dbBox(IRenderer3D r, double x1, double y1, double z1, double x2, double y2, double z2, boolean fill, boolean outline, int color) {
        r.drawBox(x1, y1, z1, x2 - x1, y2 - y1, z2 - z1, fill, outline, color);
    }

    private void drawGlowBeam(IRenderer3D r, double cx, double botY, double cz, double topY,
            Color core, Color glow, double halfCore, double spread, int layers, int baseAlpha) {
        for (int i = layers; i >= 1; i--) {
            double exp = spread * i;
            int alpha = Math.max(4, (int) (baseAlpha * (1.0 - (double) (i - 1) / layers)));
            dbBox(r, cx - halfCore - exp, botY, cz - halfCore - exp, cx + halfCore + exp, topY, cz + halfCore + exp,
                true, false, RenderUtils.withAlpha(glow, alpha));
        }
        dbBox(r, cx - halfCore, botY, cz - halfCore, cx + halfCore, topY, cz + halfCore, true, true, RenderUtils.withAlpha(core, 180));
    }

    private void drawCapBox(IRenderer3D r, double cx, double y, double cz,
            double halfXZ, double halfY, Color color, BoxMode mode,
            boolean bloom, double spread, int layers, int baseAlpha) {
        double minY = y - halfY, maxY = y + halfY;
        if (bloom) {
            for (int i = layers; i >= 1; i--) {
                double exp = spread * i;
                int alpha = Math.max(4, (int) (baseAlpha * (1.0 - (double) (i - 1) / layers)));
                dbBox(r, cx - halfXZ - exp, minY, cz - halfXZ - exp, cx + halfXZ + exp, maxY, cz + halfXZ + exp,
                    true, false, RenderUtils.withAlpha(color, alpha));
            }
        }
        dbBox(r, cx - halfXZ, minY, cz - halfXZ, cx + halfXZ, maxY, cz + halfXZ,
            mode != BoxMode.Lines, mode != BoxMode.Sides, color.getRGB());
    }

    // ── Utilities ──
    private double xzDist(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static List<String> csv(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private void info(String msg) {
        sendNotification(NotificationType.INFO, "[Waypearl] " + msg);
    }
}
