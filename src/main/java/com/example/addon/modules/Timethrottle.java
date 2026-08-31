package com.example.addon.modules;

import com.example.addon.Tim;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventTimerSpeed;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;

public class Timethrottle extends ToggleableModule {

    private static final double NORMAL_SPEED = 1.0;
    private static final int    GRACE_PERIOD = 100;
    private static final int    TICKS_PER_SECOND = 20;

    private interface ThrottleSource {
        String name();
        double evaluate();
    }

    public enum SafetyReason {
        NONE("None"),
        HURT("Took Damage"),
        HOSTILE_NEARBY("Hostile Nearby"),
        PLAYER_NEARBY("Player Nearby"),
        ATTACKING("Attacking");

        private final String title;
        SafetyReason(String title) { this.title = title; }
        public String getTitle() { return title; }
    }

    private final NumberSetting<Double> slowDownSmoothing = new NumberSetting<>("slow-down-smoothing", "How quickly speed drops when throttling. 0 = instant, higher = more gradual.", 0.1, 0.0, 0.99);
    private final NumberSetting<Double> speedUpSmoothing = new NumberSetting<>("speed-up-smoothing", "How quickly speed recovers after throttling. 0 = instant, higher = more gradual. Higher values give chunks more time to catch up.", 0.4, 0.0, 0.99);
    private final NumberSetting<Double> absoluteMinSpeed = new NumberSetting<>("absolute-min-speed", "The hard floor for game speed.", 0.15, 0.05, 0.5);

    private final NumberSetting<Double> targetTps = new NumberSetting<>("target-tps", "TPS above which no throttling is applied.", 19.0, 1.0, 20.0);
    private final NumberSetting<Double> minTps = new NumberSetting<>("min-tps", "TPS at which the slowest speed is applied.", 10.0, 1.0, 20.0);
    private final NumberSetting<Double> tpsMinSpeed = new NumberSetting<>("min-speed", "Speed multiplier applied when TPS is at or below min-tps.", 0.5, 0.1, 1.0);

    private final BooleanSetting chunkThrottle = new BooleanSetting("chunk-throttle", "Slow down when chunks are missing to force them to load.", true);
    private final NumberSetting<Double> chunkLoadSlowdown = new NumberSetting<>("chunk-min-speed", "Speed to lock to when max-throttle is reached. (0.7 = 70%)", 0.7, 0.1, 1.0)
        .setVisibility(chunkThrottle::getValue);

    private final BooleanSetting stallDetection = new BooleanSetting("stall-detection", "Give up early if chunks aren't actually loading (stalled).", true)
        .setVisibility(chunkThrottle::getValue);
    private final NumberSetting<Integer> stallTimeout = new NumberSetting<>("stall-timeout", "Seconds without chunk-loading progress before giving up.", 8, 1, 60)
        .setVisibility(() -> chunkThrottle.getValue() && stallDetection.getValue());

    private final NumberSetting<Integer> maxThrottleTime = new NumberSetting<>("max-throttle-time", "Max seconds of continuous chunk-throttling before giving up and running at normal speed. 0 = disabled.", 15, 0, 120)
        .setVisibility(chunkThrottle::getValue);
    private final NumberSetting<Integer> giveUpCooldown = new NumberSetting<>("give-up-cooldown", "Seconds at normal speed after giving up before re-evaluating chunks.", 3, 0, 30)
        .setVisibility(() -> chunkThrottle.getValue() && (maxThrottleTime.getValue() > 0 || stallDetection.getValue()));

    private final NumberSetting<Double> chunkEmaFactor = new NumberSetting<>("chunk-smoothing", "Smooths the unloaded-chunk count to prevent jittery speed changes. 0 = no smoothing, higher = more smoothing.", 0.5, 0.0, 0.95)
        .setVisibility(chunkThrottle::getValue);

    private final BooleanSetting dimensionOverride = new BooleanSetting("dimension-override", "Use different chunk thresholds for Overworld, Nether, and End.", true)
        .setVisibility(chunkThrottle::getValue);

    private final NumberSetting<Integer> owStart = new NumberSetting<>("overworld-start", "Missing chunks to start slowing down in the Overworld.", 10, 1, 100)
        .setVisibility(() -> chunkThrottle.getValue() && dimensionOverride.getValue());
    private final NumberSetting<Integer> owMax = new NumberSetting<>("overworld-max", "Missing chunks for max slowdown in the Overworld.", 80, 10, 500)
        .setVisibility(() -> chunkThrottle.getValue() && dimensionOverride.getValue());
    private final NumberSetting<Integer> netherStart = new NumberSetting<>("nether-start", "Missing chunks to start slowing down in the Nether.", 50, 1, 200)
        .setVisibility(() -> chunkThrottle.getValue() && dimensionOverride.getValue());
    private final NumberSetting<Integer> netherMax = new NumberSetting<>("nether-max", "Missing chunks for max slowdown in the Nether.", 200, 20, 1000)
        .setVisibility(() -> chunkThrottle.getValue() && dimensionOverride.getValue());
    private final NumberSetting<Integer> endStart = new NumberSetting<>("end-start", "Missing chunks to start slowing down in the End.", 5, 1, 100)
        .setVisibility(() -> chunkThrottle.getValue() && dimensionOverride.getValue());
    private final NumberSetting<Integer> endMax = new NumberSetting<>("end-max", "Missing chunks for max slowdown in the End.", 50, 10, 500)
        .setVisibility(() -> chunkThrottle.getValue() && dimensionOverride.getValue());

    private final NumberSetting<Integer> chunkLoadThreshold = new NumberSetting<>("start-throttle", "Missing chunks to start slowing down.", 10, 1, 100)
        .setVisibility(() -> chunkThrottle.getValue() && !dimensionOverride.getValue());
    private final NumberSetting<Integer> maxChunkThreshold = new NumberSetting<>("max-throttle", "Missing chunks to hit the maximum slowdown.", 80, 10, 500)
        .setVisibility(() -> chunkThrottle.getValue() && !dimensionOverride.getValue());

    private final BooleanSetting pingThrottle = new BooleanSetting("ping-throttle", "Slow down when server ping is high.", true);
    private final NumberSetting<Integer> pingThreshold = new NumberSetting<>("ping-threshold", "Ping (ms) above which throttling begins.", 150, 20, 500)
        .setVisibility(pingThrottle::getValue);
    private final NumberSetting<Integer> maxPing = new NumberSetting<>("max-ping", "Ping (ms) at which the slowest speed is applied.", 400, 50, 1000)
        .setVisibility(pingThrottle::getValue);
    private final NumberSetting<Double> pingMinSpeed = new NumberSetting<>("ping-min-speed", "Speed multiplier applied when ping is at or above max-ping.", 0.6, 0.1, 1.0)
        .setVisibility(pingThrottle::getValue);

    private final BooleanSetting combatSafety = new BooleanSetting("combat-safety", "Disables throttling when in combat or near enemies.", true);
    private final BooleanSetting detectSwing = new BooleanSetting("detect-attacking", "Resume normal speed when you swing your weapon.", true)
        .setVisibility(combatSafety::getValue);
    private final NumberSetting<Integer> safetyRange = new NumberSetting<>("safety-range", "Radius to check for hostile entities or players.", 15, 0, 32)
        .setVisibility(combatSafety::getValue);
    private final NumberSetting<Integer> safetyDuration = new NumberSetting<>("safety-duration", "Ticks to keep throttling disabled after a safety trigger.", 60, 0, 200)
        .setVisibility(combatSafety::getValue);

    private double       currentSpeed     = NORMAL_SPEED;
    private int          safetyTicks      = 0;
    private int          graceTicks       = 0;
    private SafetyReason lastSafetyReason = SafetyReason.NONE;

    private int     chunkThrottleTicks     = 0;
    private int     chunkGiveUpTicks       = 0;
    private int     stallTicks             = 0;
    private int     lastRawUnloaded        = -1;
    private double  smoothedUnloaded       = -1;
    private int     cachedUnloaded         = 0;
    private boolean cachedPlayerAreaLoaded = true;
    private boolean chunkDataValid         = false;

    private final ThrottleSource tpsSource = new ThrottleSource() {
        @Override public String name() { return "TPS"; }
        @Override public double evaluate() {
            double tps = RusherHackAPI.getServerState().getTPS();
            if (tps >= targetTps.getValue()) return NORMAL_SPEED;
            if (tps <= minTps.getValue())    return tpsMinSpeed.getValue();
            return Mth.map(tps, minTps.getValue(), targetTps.getValue(), tpsMinSpeed.getValue(), NORMAL_SPEED);
        }
    };

    private final ThrottleSource chunkSource = new ThrottleSource() {
        @Override public String name() { return "Chunks"; }
        @Override public double evaluate() {
            if (!chunkThrottle.getValue()) return NORMAL_SPEED;
            if (chunkGiveUpTicks > 0) return NORMAL_SPEED;
            if (!chunkDataValid) return NORMAL_SPEED;
            if (!cachedPlayerAreaLoaded) return NORMAL_SPEED;

            int startThr;
            int maxThr;

            if (dimensionOverride.getValue()) {
                if (mc.level.dimension() == Level.NETHER) {
                    startThr = netherStart.getValue();
                    maxThr = netherMax.getValue();
                } else if (mc.level.dimension() == Level.END) {
                    startThr = endStart.getValue();
                    maxThr = endMax.getValue();
                } else {
                    startThr = owStart.getValue();
                    maxThr = owMax.getValue();
                }
            } else {
                startThr = chunkLoadThreshold.getValue();
                maxThr = maxChunkThreshold.getValue();
            }

            if (cachedUnloaded <= startThr) return NORMAL_SPEED;
            if (cachedUnloaded >= maxThr) return chunkLoadSlowdown.getValue();
            return Mth.map(cachedUnloaded, startThr, maxThr, NORMAL_SPEED, chunkLoadSlowdown.getValue());
        }
    };

    private final ThrottleSource pingSource = new ThrottleSource() {
        @Override public String name() { return "Ping"; }
        @Override public double evaluate() {
            if (!pingThrottle.getValue()) return NORMAL_SPEED;
            int ping = getPlayerPing();
            if (ping <= pingThreshold.getValue()) return NORMAL_SPEED;
            if (ping >= maxPing.getValue())       return pingMinSpeed.getValue();
            return Mth.map(ping, pingThreshold.getValue(), maxPing.getValue(), NORMAL_SPEED, pingMinSpeed.getValue());
        }
    };

    private final ThrottleSource[] sources = { tpsSource, chunkSource, pingSource };

    public Timethrottle() {
        super("time-throttle", "Automatically adjusts game speed based on server TPS, chunk loading, and ping.", Tim.CATEGORY);
        this.registerSettings(
            slowDownSmoothing, speedUpSmoothing, absoluteMinSpeed,
            targetTps, minTps, tpsMinSpeed,
            chunkThrottle, chunkLoadSlowdown, stallDetection, stallTimeout, maxThrottleTime, giveUpCooldown,
            chunkEmaFactor, dimensionOverride,
            owStart, owMax, netherStart, netherMax, endStart, endMax,
            chunkLoadThreshold, maxChunkThreshold,
            pingThrottle, pingThreshold, maxPing, pingMinSpeed,
            combatSafety, detectSwing, safetyRange, safetyDuration
        );
    }

    @Override
    public void onEnable() {
        currentSpeed          = NORMAL_SPEED;
        safetyTicks           = 0;
        graceTicks            = GRACE_PERIOD;
        lastSafetyReason      = SafetyReason.NONE;
        chunkThrottleTicks    = 0;
        chunkGiveUpTicks      = 0;
        stallTicks            = 0;
        lastRawUnloaded       = -1;
        smoothedUnloaded      = -1;
        chunkDataValid        = false;
    }

    @Override
    public void onDisable() {
        currentSpeed = NORMAL_SPEED;
    }

    @Subscribe
    private void onTimerSpeed(EventTimerSpeed event) {
        event.setSpeed((float) currentSpeed);
        event.setOverrideTimer(true);
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.level == null || mc.player == null) return;

        if (!mc.level.hasChunk(mc.player.chunkPosition().x, mc.player.chunkPosition().z)) {
            currentSpeed = NORMAL_SPEED;
            return;
        }

        if (graceTicks > 0) {
            graceTicks--;
            currentSpeed = NORMAL_SPEED;
            return;
        }

        updateChunkTracking();

        updateSafety();
        if (safetyTicks > 0) {
            safetyTicks--;
            currentSpeed = NORMAL_SPEED;
            return;
        }
        lastSafetyReason = SafetyReason.NONE;

        double desired = computeDesiredSpeed();

        double chunkSpeed = chunkSource.evaluate();
        if (chunkSpeed < NORMAL_SPEED - 0.01) {
            chunkThrottleTicks++;
            checkGiveUp();
        } else {
            chunkThrottleTicks = 0;
        }

        if (chunkGiveUpTicks > 0) chunkGiveUpTicks--;

        smoothAndApply(desired);
    }

    private void updateChunkTracking() {
        if (mc.level == null || mc.player == null) {
            chunkDataValid = false;
            return;
        }

        int px = mc.player.chunkPosition().x;
        int pz = mc.player.chunkPosition().z;
        cachedPlayerAreaLoaded = true;
        outer:
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!mc.level.hasChunk(px + dx, pz + dz)) {
                    cachedPlayerAreaLoaded = false;
                    break outer;
                }
            }
        }

        if (!cachedPlayerAreaLoaded) {
            chunkDataValid = true;
            return;
        }

        int raw = countUnloadedChunks();

        if (smoothedUnloaded < 0) {
            smoothedUnloaded = raw;
        } else {
            double factor = chunkEmaFactor.getValue();
            smoothedUnloaded = smoothedUnloaded * factor + raw * (1.0 - factor);
        }
        cachedUnloaded = (int) Math.round(smoothedUnloaded);

        if (lastRawUnloaded < 0 || raw < lastRawUnloaded) {
            stallTicks = 0;
        } else {
            stallTicks++;
        }
        lastRawUnloaded = raw;

        chunkDataValid = true;
    }

    private void checkGiveUp() {
        boolean shouldGiveUp = false;

        if (maxThrottleTime.getValue() > 0 && chunkThrottleTicks >= maxThrottleTime.getValue() * TICKS_PER_SECOND) {
            shouldGiveUp = true;
        }

        if (stallDetection.getValue() && stallTicks >= stallTimeout.getValue() * TICKS_PER_SECOND) {
            shouldGiveUp = true;
        }

        if (shouldGiveUp) {
            chunkGiveUpTicks   = Math.max(giveUpCooldown.getValue(), 1) * TICKS_PER_SECOND;
            chunkThrottleTicks = 0;
            stallTicks         = 0;
        }
    }

    private void updateSafety() {
        if (!combatSafety.getValue()) return;
        SafetyReason reason = detectSafetyReason();
        if (reason != SafetyReason.NONE) {
            lastSafetyReason = reason;
            safetyTicks      = safetyDuration.getValue();
        }
    }

    private SafetyReason detectSafetyReason() {
        if (mc.player.hurtTime > 0) return SafetyReason.HURT;
        if (detectSwing.getValue() && mc.player.swingTime > 0) return SafetyReason.ATTACKING;

        int range = safetyRange.getValue();
        if (range <= 0) return SafetyReason.NONE;

        AABB box = mc.player.getBoundingBox().inflate(range);

        if (!mc.level.getEntitiesOfClass(Monster.class, box, Entity::isAlive).isEmpty())
            return SafetyReason.HOSTILE_NEARBY;

        if (!mc.level.getEntitiesOfClass(Player.class, box, p -> p != mc.player && p.isAlive()).isEmpty())
            return SafetyReason.PLAYER_NEARBY;

        return SafetyReason.NONE;
    }

    private double computeDesiredSpeed() {
        double desired = NORMAL_SPEED;
        for (ThrottleSource source : sources) desired = Math.min(desired, source.evaluate());
        return Math.max(desired, absoluteMinSpeed.getValue());
    }

    private void smoothAndApply(double desired) {
        double smoothing = (desired < currentSpeed)
            ? slowDownSmoothing.getValue()
            : speedUpSmoothing.getValue();
        currentSpeed = Mth.lerp(1.0 - smoothing, currentSpeed, desired);
        applySpeed(currentSpeed);
    }

    private void applySpeed(double speed) {
        if (Double.isNaN(speed) || Double.isInfinite(speed) || speed <= 0.0) speed = NORMAL_SPEED;
        currentSpeed = speed;
    }

    public double       getCurrentSpeed()     { return currentSpeed; }
    public boolean      isSafetyActive()      { return safetyTicks > 0; }
    public boolean      isChunkGiveUpActive() { return chunkGiveUpTicks > 0; }
    public SafetyReason getLastSafetyReason() { return lastSafetyReason; }
    public int          sourceCount()         { return sources.length; }
    public String       sourceName(int i)     { return (i >= 0 && i < sources.length) ? sources[i].name() : "?"; }
    public double       evaluateSource(int i) { return (i >= 0 && i < sources.length) ? sources[i].evaluate() : NORMAL_SPEED; }

    private int getPlayerPing() {
        if (mc.player == null || mc.getConnection() == null) return 0;
        PlayerInfo entry = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return entry != null ? entry.getLatency() : 0;
    }

    private int countUnloadedChunks() {
        if (mc.level == null || mc.player == null) return 0;
        int unloaded     = 0;
        int viewDistance = mc.options.getEffectiveRenderDistance();
        int cx           = mc.player.chunkPosition().x;
        int cz           = mc.player.chunkPosition().z;
        for (int dx = -viewDistance; dx <= viewDistance; dx++) {
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                if (!mc.level.hasChunk(cx + dx, cz + dz)) {
                    unloaded++;
                }
            }
        }
        return unloaded;
    }
}
