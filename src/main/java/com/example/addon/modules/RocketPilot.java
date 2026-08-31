package com.example.addon.modules;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import com.example.addon.Tim;
import com.example.addon.utils.InvUtils;

import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.network.EventPacket;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.setting.BindSetting;
import org.rusherhack.core.bind.key.NullKey;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class RocketPilot extends ToggleableModule {

    public enum FlightMode { None, Normal, Pitch40, AltitudeBounce }

    public enum FlightPattern {
        Manual, Drunk, Grid, Circle, Hexagon, Triangle, ZigZag, FigureEight, Sweep
    }

    public enum DrunkBias { None, North, South, East, West, PositiveOnly, NegativeOnly, NegPos, PosNeg }

    public enum DrunkSpiralMode { None, Grid, Circle, Hexagon, Triangle }

    private static final int   TAKEOFF_GRACE_TICKS       = 40;
    private static final float ELYTRA_LOW_PERCENT        = 5.0f;
    private static final int   ELYTRA_MIN_SWAP_DUR       = 50;
    private static final long  COLLISION_ROCKET_COOLDOWN = 200L;

    public final BooleanSetting useTargetY = new BooleanSetting("use-target-y", "Whether to maintain a specific Y level.", true);

    public final NumberSetting<Double> targetY = new NumberSetting<>("target-y", "The Y level to maintain.", 120.0, -64.0, 10000.0)
        .setVisibility(useTargetY::getValue);

    public final NumberSetting<Double> flightTolerance = new NumberSetting<>("flight-tolerance", "Allowable drop below target Y before climbing.", 2.0, 0.5, 10.0);

    public final BooleanSetting useFreeLookY = new BooleanSetting("use-freelook-y", "Render the camera at a specific Y level while flying.", false);

    public final NumberSetting<Double> freeLookY = new NumberSetting<>("freelook-y", "The Y level to render the camera at.", 120.0, -64.0, 320.0)
        .setVisibility(useFreeLookY::getValue);

    private final BindSetting toggleFreeLookY = new BindSetting("toggle-freelook-y", "Key to toggle the freelook Y feature.", NullKey.INSTANCE);

    private final BooleanSetting autoTakeoff = new BooleanSetting("auto-takeoff", "Automatically jump and fire a rocket to start elytra flight.", true);

    private final BooleanSetting disableOnLand = new BooleanSetting("disable-on-land", "Automatically disable the module when you land.", false);

    public final NumberSetting<Integer> rocketDelay = new NumberSetting<>("rocket-delay", "Delay in milliseconds between rockets.", 2000, 100, 10000);

    public final BooleanSetting silentRockets = new BooleanSetting("silent-rockets", "Suppresses the hand swing animation when firing rockets.", true);

    public final EnumSetting<FlightMode> flightMode = new EnumSetting<>("flight-mode", "The primary flight mode for pitch control.", FlightMode.Normal)
        .onChange((java.util.function.Consumer<FlightMode>) v -> {
            if (!isToggled() || mc.level == null) return;
            resetPatternState();
            switch (v) {
                case Pitch40        -> this.sendNotification(NotificationType.INFO, "Pitch40 mode enabled.");
                case AltitudeBounce  -> this.sendNotification(NotificationType.INFO, "Altitude Bounce mode enabled.");
                case None            -> this.sendNotification(NotificationType.INFO, "Flight pitch control disabled.");
                default              -> this.sendNotification(NotificationType.INFO, "Normal flight mode enabled.");
            }
        });

    public final NumberSetting<Double> pitchSmoothing = new NumberSetting<>("pitch-smoothing", "How smoothly pitch changes in Normal and Pattern modes (lower = smoother).", 0.15, 0.01, 1.0)
        .setVisibility(() -> flightMode.getValue() == FlightMode.Normal);

    private final NumberSetting<Double> pitch40UpperY = new NumberSetting<>("upper-y", "Upper Y-level ceiling; stop climbing above this.", 120.0, -64.0, 320.0)
        .setVisibility(() -> flightMode.getValue() == FlightMode.Pitch40);
    private final NumberSetting<Double> pitch40LowerY = new NumberSetting<>("lower-y", "Lower Y-level floor; start climbing below this.", 110.0, -64.0, 320.0)
        .setVisibility(() -> flightMode.getValue() == FlightMode.Pitch40);
    private final NumberSetting<Double> pitch40Smoothing = new NumberSetting<>("smoothing", "How smoothly to adjust pitch in Pitch40 mode.", 0.05, 0.01, 1.0)
        .setVisibility(() -> flightMode.getValue() == FlightMode.Pitch40);
    private final NumberSetting<Integer> pitch40BelowMinDelay = new NumberSetting<>("below-min-delay", "Time in ms to remain below lower-y before firing rockets.", 8000, 1000, 20000)
        .setVisibility(() -> flightMode.getValue() == FlightMode.Pitch40);

    public final EnumSetting<FlightPattern> flightPattern = new EnumSetting<>("flight-pattern", "The flight pattern to follow. Manual allows free mouse look.", FlightPattern.Manual)
        .onChange((java.util.function.Consumer<FlightPattern>) v -> {
            resetPatternState();
            resetDrunkSpiralState();
        });

    private final NumberSetting<Integer> sweepWidth = new NumberSetting<>("sweep-width", "Total side-to-side distance in chunks.", 10, 1, 200)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Sweep);
    private final NumberSetting<Integer> sweepAdvance = new NumberSetting<>("sweep-advance", "Forward distance moved per sweep in chunks.", 2, 1, 100)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Sweep);
    private final NumberSetting<Double> sweepExpansionRate = new NumberSetting<>("sweep-expansion-rate", "Percentage increase in sweep width/advance per full cycle (e.g., 0.1 for 10% increase).", 0.0, 0.0, 0.5)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Sweep);
    private final NumberSetting<Double> sweepMaxFactor = new NumberSetting<>("sweep-max-factor", "Maximum multiplier for sweep width/advance (e.g., 2.0 for double the initial size).", 1.0, 1.0, 5.0)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Sweep && sweepExpansionRate.getValue() > 0.0);
    private final BooleanSetting sweepAutoUpdate = new BooleanSetting("auto-update-origin", "Relocates the sweep pattern origin to your position if you manually fly too far from the current target.", true)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Sweep);

    private final NumberSetting<Double> bounceClimbPitch = new NumberSetting<>("climb-pitch", "Pitch angle while climbing aggressively (negative = nose up).", -35.0, -60.0, -5.0)
        .setVisibility(() -> flightMode.getValue() == FlightMode.AltitudeBounce);
    private final NumberSetting<Double> bounceGlidePitch = new NumberSetting<>("glide-pitch", "Pitch angle during the glide descent phase (positive = nose down).", 20.0, 5.0, 60.0)
        .setVisibility(() -> flightMode.getValue() == FlightMode.AltitudeBounce);
    private final NumberSetting<Double> bouncePeakY = new NumberSetting<>("peak-y", "Y level to reach before cutting rockets and beginning the glide.", 130.0, -64.0, 10000.0)
        .setVisibility(() -> flightMode.getValue() == FlightMode.AltitudeBounce);
    private final NumberSetting<Double> bounceFloorY = new NumberSetting<>("floor-y", "Y level at which the glide ends and the climb begins again.", 100.0, -64.0, 320.0)
        .setVisibility(() -> flightMode.getValue() == FlightMode.AltitudeBounce);
    private final NumberSetting<Double> bouncePitchSmoothing = new NumberSetting<>("bounce-pitch-smoothing", "How smoothly to transition between climb and glide pitches.", 0.08, 0.01, 1.0)
        .setVisibility(() -> flightMode.getValue() == FlightMode.AltitudeBounce);

    private final BindSetting pauseKey = new BindSetting("pause-key", "Pauses/resumes the current flight pattern or drunk spiral.", NullKey.INSTANCE);

    private final NumberSetting<Double> patternTurnSpeed = new NumberSetting<>("turn-speed", "How quickly to yaw toward pattern waypoints.", 0.1, 0.01, 1.0)
        .setVisibility(() -> flightPattern.getValue() != FlightPattern.Manual && flightPattern.getValue() != FlightPattern.Drunk);
    private final NumberSetting<Integer> waypointReachRadius = new NumberSetting<>("waypoint-reach-radius", "Horizontal distance (blocks) to a waypoint before advancing.", 30, 5, 200)
        .setVisibility(() -> flightPattern.getValue() != FlightPattern.Manual && flightPattern.getValue() != FlightPattern.Drunk);
    private final NumberSetting<Integer> gridSpacing = new NumberSetting<>("grid-spacing", "Distance between grid lines in chunks.", 8, 1, 32)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Grid);
    private final NumberSetting<Integer> circleSegments = new NumberSetting<>("circle-segments", "Number of waypoints per full spiral rotation.", 32, 4, 128)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Circle);
    private final NumberSetting<Integer> circleExpansion = new NumberSetting<>("circle-expansion", "How many chunks the radius increases per rotation.", 4, 1, 16)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Circle);
    private final NumberSetting<Integer> hexagonSideLength = new NumberSetting<>("hexagon-side-length", "Side length of the hexagon in chunks.", 4, 1, 32)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Hexagon);
    private final NumberSetting<Integer> hexagonExpansion = new NumberSetting<>("hexagon-expansion", "Chunks the hexagon side length grows per full rotation.", 2, 1, 16)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Hexagon);
    private final NumberSetting<Integer> triangleSideLength = new NumberSetting<>("triangle-side-length", "Side length of the triangle in chunks.", 6, 1, 32)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Triangle);
    private final NumberSetting<Integer> triangleExpansion = new NumberSetting<>("triangle-expansion", "Chunks the triangle side length grows per full rotation.", 3, 1, 16)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Triangle);
    private final NumberSetting<Integer> zigzagLegLength = new NumberSetting<>("zigzag-leg-length", "Length of each zigzag leg in chunks.", 5, 1, 50)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.ZigZag);
    private final NumberSetting<Double> zigzagAngle = new NumberSetting<>("zigzag-angle", "Turn angle at each ZigZag corner (degrees from forward).", 45.0, 10.0, 80.0)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.ZigZag);
    private final NumberSetting<Integer> figureEightRadius = new NumberSetting<>("figure-eight-radius", "Radius of the loops in chunks.", 5, 1, 20)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.FigureEight);

    private final EnumSetting<DrunkSpiralMode> drunkSpiralMode = new EnumSetting<>("spiral-mode", "Constrains drunk wandering to follow an expanding grid or circular spiral outward.", DrunkSpiralMode.None)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Drunk)
        .onChange((java.util.function.Consumer<DrunkSpiralMode>) v -> resetDrunkSpiralState());

    private final NumberSetting<Integer> drunkInterval = new NumberSetting<>("change-interval", "Ticks between direction changes.", 5, 1, 20)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Drunk);
    private final NumberSetting<Double> drunkIntensity = new NumberSetting<>("intensity", "Maximum yaw change per update (degrees). Applied when coordinate-bias is None.", 120.0, 1.0, 180.0)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Drunk && drunkSpiralMode.getValue() == DrunkSpiralMode.None);
    public final EnumSetting<DrunkBias> drunkBias = new EnumSetting<>("coordinate-bias", "Constrains drunk-pilot heading. None = fully random.", DrunkBias.None)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Drunk && drunkSpiralMode.getValue() == DrunkSpiralMode.None);
    private final BooleanSetting drunkAvoidVisited = new BooleanSetting("avoid-visited", "Attempts to steer the Drunk Pilot away from chunks it has already flown over.", true)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Drunk && drunkSpiralMode.getValue() == DrunkSpiralMode.None);
    private final NumberSetting<Double> drunkSmoothing = new NumberSetting<>("drunk-smoothing", "How smoothly to rotate to the new heading (lower = smoother).", 0.05, 0.01, 1.0)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Drunk);
    private final NumberSetting<Integer> drunkGridSpacing = new NumberSetting<>("drunk-grid-spacing", "Distance (chunks) between grid legs when spiral-mode is Grid.", 4, 1, 16)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Drunk && drunkSpiralMode.getValue() == DrunkSpiralMode.Grid);
    private final NumberSetting<Integer> drunkCircleSegments = new NumberSetting<>("drunk-circle-segments", "Waypoints per full rotation when spiral-mode is Circle.", 24, 4, 64)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Drunk && drunkSpiralMode.getValue() == DrunkSpiralMode.Circle);
    private final NumberSetting<Integer> drunkCircleExpansion = new NumberSetting<>("drunk-circle-expansion", "Chunks the circle radius grows per full rotation.", 2, 1, 8)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Drunk && drunkSpiralMode.getValue() == DrunkSpiralMode.Circle);
    private final NumberSetting<Integer> drunkHexagonSideLength = new NumberSetting<>("drunk-hexagon-side-length", "Side length (chunks) of each hexagon edge when spiral-mode is Hexagon.", 4, 1, 32)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Drunk && drunkSpiralMode.getValue() == DrunkSpiralMode.Hexagon);
    private final NumberSetting<Integer> drunkHexagonExpansion = new NumberSetting<>("drunk-hexagon-expansion", "Chunks the hexagon side length grows per full rotation.", 2, 1, 16)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Drunk && drunkSpiralMode.getValue() == DrunkSpiralMode.Hexagon);
    private final NumberSetting<Integer> drunkTriangleSideLength = new NumberSetting<>("drunk-triangle-side-length", "Side length (chunks) of each triangle edge when spiral-mode is Triangle.", 6, 1, 32)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Drunk && drunkSpiralMode.getValue() == DrunkSpiralMode.Triangle);
    private final NumberSetting<Integer> drunkTriangleExpansion = new NumberSetting<>("drunk-triangle-expansion", "Chunks the triangle side length grows per full rotation.", 3, 1, 16)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Drunk && drunkSpiralMode.getValue() == DrunkSpiralMode.Triangle);
    private final NumberSetting<Double> drunkSpiralNoise = new NumberSetting<>("spiral-noise", "Random yaw offset (degrees) added to the spiral heading for the drunk feel.", 30.0, 0.0, 180.0)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Drunk && drunkSpiralMode.getValue() != DrunkSpiralMode.None);
    private final NumberSetting<Integer> drunkSpiralReach = new NumberSetting<>("spiral-waypoint-reach", "Horizontal distance (blocks) to a spiral waypoint before advancing.", 20, 5, 80)
        .setVisibility(() -> flightPattern.getValue() == FlightPattern.Drunk && drunkSpiralMode.getValue() != DrunkSpiralMode.None);

    private final BooleanSetting collisionAvoidance = new BooleanSetting("collision-avoidance", "Attempts to avoid flying straight into walls.", true);
    private final NumberSetting<Integer> avoidanceDistance = new NumberSetting<>("avoidance-distance", "How far ahead to look for obstacles (blocks).", 10, 3, 20)
        .setVisibility(collisionAvoidance::getValue);
    private final BooleanSetting limitRotationSpeed = new BooleanSetting("limit-rotation-speed", "Caps rotation speed per tick to reduce anti-cheat flags.", true);
    private final NumberSetting<Double> maxRotationPerTick = new NumberSetting<>("max-rotation-per-tick", "Maximum degrees to rotate per tick.", 20.0, 1.0, 90.0)
        .setVisibility(limitRotationSpeed::getValue);
    private final BindSetting panicKey = new BindSetting("panic-key", "Immediately disconnects from the server and disables the module.", NullKey.INSTANCE);

    private final BooleanSetting autoDisableOnLowHealth = new BooleanSetting("auto-disable-on-low-health", "Disables the module if health is critically low while a totem is equipped.", true);
    private final NumberSetting<Integer> lowHealthThreshold = new NumberSetting<>("low-health-threshold", "Health level (hearts) to trigger auto-disable.", 3, 1, 10)
        .setVisibility(autoDisableOnLowHealth::getValue);
    private final BooleanSetting disconnectOnTotemPop = new BooleanSetting("disconnect-on-totem-pop", "Disconnect from the server if a totem of undying is consumed.", false);
    private final BooleanSetting disconnectOnLowRockets = new BooleanSetting("disconnect-on-low-rockets", "Disconnect from the server when your firework rocket count drops below the minimum.", false);
    private final NumberSetting<Integer> minRockets = new NumberSetting<>("min-rockets", "Minimum number of firework rockets to keep before disconnecting.", 5, 1, 64)
        .setVisibility(disconnectOnLowRockets::getValue);

    public  long    lastRocketTime           = 0;
    private boolean needsTakeoffRocket       = false;
    private boolean ascentMode               = false;
    private final Set<Long> drunkVisitedChunks = new LinkedHashSet<>();
    private boolean pitch40Climbing          = false;
    private boolean pitch40Rocketing         = false;
    private long    pitch40BelowMinStartTime = -1;
    private long    lastLagbackTime          = 0;
    private boolean bounceClimbing           = true;

    private float   targetPitch              = 0;
    private int     drunkTimer               = 0;
    private float   targetDrunkYaw           = 0;
    private int     currentDrunkDuration     = 0;
    private int     totemPops                = 0;
    private int     takeoffTimer             = 0;
    private int     takeoffWaitTicks         = 0;

    private boolean paused              = false;
    private Vec3    origin              = null;
    private Vec3    currentTarget       = null;
    private int     gridStep            = 1;
    private int     gridStepsInLeg      = 0;
    private int     gridDirection       = 0;
    private float   zigzagCurrentYaw    = 0;
    private boolean zigzagTurnRight     = true;
    private boolean zigzagFirstLeg      = true;
    private double  circleAngle         = 0;
    private int     sweepStep           = 0;
    private double  currentSweepFactor  = 1.0;
    private float   sweepInitialYaw     = 0;
    private int     figureEightWaypoint = 0;
    private int     polygonSide         = 0;
    private int     polygonRotation     = 0;

    private Vec3   drunkSpiralOrigin    = null;
    private Vec3   drunkSpiralTarget    = null;
    private int    drunkGridStep        = 1;
    private int    drunkGridStepsInLeg  = 0;
    private int    drunkGridDirection   = 0;
    private double drunkCircleAngle     = 0;
    private int    drunkPolygonSide     = 0;
    private int    drunkPolygonRotation = 0;

    public RocketPilot() {
        super("rocket-pilot", "Automatic elytra + rocket flight with height maintenance, auto-takeoff, and pattern flight.", Tim.CATEGORY);
        this.registerSettings(
            useTargetY, targetY, flightTolerance, useFreeLookY, freeLookY, toggleFreeLookY,
            autoTakeoff, disableOnLand, rocketDelay, silentRockets, flightMode, pitchSmoothing,
            pitch40UpperY, pitch40LowerY, pitch40Smoothing, pitch40BelowMinDelay,
            flightPattern, sweepWidth, sweepAdvance, sweepExpansionRate, sweepMaxFactor, sweepAutoUpdate,
            bounceClimbPitch, bounceGlidePitch, bouncePeakY, bounceFloorY, bouncePitchSmoothing,
            pauseKey, patternTurnSpeed, waypointReachRadius, gridSpacing,
            circleSegments, circleExpansion, hexagonSideLength, hexagonExpansion,
            triangleSideLength, triangleExpansion, zigzagLegLength, zigzagAngle, figureEightRadius,
            drunkSpiralMode, drunkInterval, drunkIntensity, drunkBias, drunkAvoidVisited, drunkSmoothing,
            drunkGridSpacing, drunkCircleSegments, drunkCircleExpansion,
            drunkHexagonSideLength, drunkHexagonExpansion, drunkTriangleSideLength, drunkTriangleExpansion,
            drunkSpiralNoise, drunkSpiralReach,
            collisionAvoidance, avoidanceDistance, limitRotationSpeed, maxRotationPerTick, panicKey,
            autoDisableOnLowHealth, lowHealthThreshold, disconnectOnTotemPop, disconnectOnLowRockets, minRockets
        );
    }

    private boolean isPatternMode() {
        if (flightPattern.getValue() == FlightPattern.Manual) return false;
        if (flightPattern.getValue() == FlightPattern.Drunk && drunkSpiralMode.getValue() == DrunkSpiralMode.None) return false;
        return true;
    }

    private void resetPatternState() {
        paused              = false;
        origin              = null;
        currentTarget       = null;
        gridStep            = 1;
        gridStepsInLeg      = 0;
        gridDirection       = 0;
        zigzagCurrentYaw    = 0;
        zigzagTurnRight     = true;
        zigzagFirstLeg      = true;
        circleAngle         = 0;
        sweepStep           = 0;
        currentSweepFactor  = 1.0;
        sweepInitialYaw     = 0;
        drunkVisitedChunks.clear();
        figureEightWaypoint = 0;
        polygonSide         = 0;
        polygonRotation     = 0;
    }

    private void resetDrunkSpiralState() {
        drunkSpiralOrigin    = null;
        drunkSpiralTarget    = null;
        drunkGridStep        = 1;
        drunkGridStepsInLeg  = 0;
        drunkGridDirection   = 0;
        drunkCircleAngle     = 0;
        drunkPolygonSide     = 0;
        drunkPolygonRotation = 0;
    }

    @Override
    public void onEnable() {
        lastRocketTime           = 0;
        needsTakeoffRocket       = false;
        drunkTimer               = 0;
        currentDrunkDuration     = 0;
        ascentMode               = false;
        pitch40Climbing          = false;
        pitch40Rocketing         = false;
        pitch40BelowMinStartTime = -1;
        bounceClimbing           = true;
        lastLagbackTime          = 0;
        takeoffTimer             = 0;
        takeoffWaitTicks         = 0;

        if (mc.player == null || mc.level == null) { toggle(); return; }

        if (origin != null && mc.player.position().distanceTo(origin) > 100) {
            resetPatternState();
        }

        if (drunkSpiralOrigin != null && mc.player.position().distanceTo(drunkSpiralOrigin) > 100) {
            resetDrunkSpiralState();
        }

        totemPops      = mc.player.getStats().getValue(Stats.ITEM_USED, Items.TOTEM_OF_UNDYING);
        targetPitch    = mc.player.getXRot();
        targetDrunkYaw = mc.player.getYRot();

        if (mc.player.isFallFlying()) return;
        if (!autoTakeoff.getValue()) return;

        ItemStack elytra = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.is(Items.ELYTRA)) {
            this.sendNotification(NotificationType.ERROR, "No elytra equipped.");
            toggle();
            return;
        }
        if (countFireworks() == 0) {
            this.sendNotification(NotificationType.ERROR, "No fireworks in inventory.");
            toggle();
            return;
        }
        if (!isNearGround()) {
            this.sendNotification(NotificationType.WARNING, "Not on ground — auto-takeoff skipped.");
            return;
        }

        targetPitch = -28.0f;
        mc.player.setXRot(targetPitch);
        mc.player.jumpFromGround();
        needsTakeoffRocket = true;
        this.sendNotification(NotificationType.INFO, "Taking off!");
    }

    @Override
    public void onDisable() {
        needsTakeoffRocket = false;
        takeoffWaitTicks   = 0;
        paused             = false;
        drunkVisitedChunks.clear();
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (System.currentTimeMillis() - lastLagbackTime < 500) return;
        if (mc.player == null || mc.level == null) return;

        replenishRockets();

        if (disconnectOnTotemPop.getValue()) {
            int currentPops = mc.player.getStats().getValue(Stats.ITEM_USED, Items.TOTEM_OF_UNDYING);
            if (currentPops > totemPops) {
                this.sendNotification(NotificationType.ERROR, "Totem popped! Disconnecting...");
                disconnect();
                return;
            }
        }

        if (disconnectOnLowRockets.getValue() && countFireworks() < minRockets.getValue()) {
            this.sendNotification(NotificationType.ERROR, "Low on rockets (" + countFireworks() + " < " + minRockets.getValue() + ")! Disconnecting...");
            disconnect();
            return;
        }

        if (autoDisableOnLowHealth.getValue()) {
            boolean hasTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
                            || mc.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
            if (hasTotem && mc.player.getHealth() <= lowHealthThreshold.getValue() * 2f) {
                this.sendNotification(NotificationType.ERROR, "Health critical (" + mc.player.getHealth() + " hp), disabling.");
                toggle();
                return;
            }
        }

        ItemStack elytra = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.is(Items.ELYTRA)) {
            this.sendNotification(NotificationType.ERROR, "Elytra missing — disabling.");
            toggle();
            return;
        }

        if (takeoffTimer > 0) takeoffTimer--;

        if (disableOnLand.getValue() && mc.player.onGround() && !needsTakeoffRocket && takeoffTimer == 0) {
            this.sendNotification(NotificationType.INFO, "Landed — disabling.");
            toggle();
            return;
        }

        boolean wantsToFly = !useTargetY.getValue() || mc.player.getY() < targetY.getValue();
        if (flightMode.getValue() == FlightMode.Pitch40 || flightMode.getValue() == FlightMode.AltitudeBounce) {
            wantsToFly = true;
        }

        if (isNearGround() && !mc.player.isFallFlying() && wantsToFly && autoTakeoff.getValue() && countFireworks() > 0 && !needsTakeoffRocket) {
            targetPitch = -28.0f;
            mc.player.setXRot(targetPitch);
            if (mc.player.onGround()) mc.player.jumpFromGround();
            needsTakeoffRocket = true;
            takeoffWaitTicks   = 0;
            this.sendNotification(NotificationType.INFO, "Re-launching!");
        }

        if (needsTakeoffRocket) {
            handleTakeoff();
            return;
        }

        if (!mc.player.isFallFlying()) return;

        handleElytraHealth();

        Float desiredPitch = null;
        boolean safetyOverride = false;

        if (desiredPitch == null && collisionAvoidance.getValue()) {
            desiredPitch = handleCollisionAvoidance();
            if (desiredPitch != null) safetyOverride = true;
        }

        if (desiredPitch == null) {
            desiredPitch = switch (flightMode.getValue()) {
                case Pitch40        -> handlePitch40Mode();
                case AltitudeBounce -> handleAltitudeBounceMode();
                case None           -> useTargetY.getValue() ? handleNormalMode() : null;
                default             -> handleNormalMode();
            };
        }

        if (!safetyOverride) {
            FlightPattern currentPattern = flightPattern.getValue();
            if (currentPattern == FlightPattern.Drunk) {
                if (drunkVisitedChunks.size() > 2000) {
                    Iterator<Long> it = drunkVisitedChunks.iterator();
                    for (int i = 0; i < 1000 && it.hasNext(); i++) {
                        it.next();
                        it.remove();
                    }
                }
                drunkVisitedChunks.add(mc.player.chunkPosition().toLong());
                handleDrunkMode();
            } else if (currentPattern != FlightPattern.Manual) {
                handlePatternYaw();
            }
        }

        applyPitch(desiredPitch);
    }

    @Subscribe
    private void onPacketReceive(EventPacket.Receive event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            lastLagbackTime = System.currentTimeMillis();
            mc.options.keyUp.setDown(false);
        }
    }

    private void handleTakeoff() {
        if (mc.player.onGround()) {
            mc.player.jumpFromGround();
            return;
        }
        if (!mc.player.isFallFlying()) {
            if (mc.player.connection != null) {
                mc.player.connection.send(
                    new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING)
                );
            }
            return;
        }
        boolean rocketInHotbar = hotbarHasRocket();
        if (!rocketInHotbar) {
            takeoffWaitTicks++;
            if (takeoffWaitTicks < 10) return;
        }
        if (shouldFireRocket() && countFireworks() > 0) {
            fireRocket();
            lastRocketTime = System.currentTimeMillis();
        }
        needsTakeoffRocket = false;
        takeoffWaitTicks   = 0;
        takeoffTimer       = TAKEOFF_GRACE_TICKS;
    }

    private boolean hotbarHasRocket() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.FIREWORK_ROCKET)) return true;
        }
        return false;
    }

    private void handleElytraHealth() {
        if (getDurabilityPercent() <= ELYTRA_LOW_PERCENT) {
            Integer newDura = swapToFreshElytra();
            if (newDura != null) {
                this.sendNotification(NotificationType.INFO, "Auto-swapped elytra (durability was low).");
            } else {
                this.sendNotification(NotificationType.WARNING, "No replacement elytra found!");
            }
        }
    }

    private Float handleCollisionAvoidance() {
        if (!mc.player.isFallFlying() || mc.player.getXRot() >= 30) return null;

        Vec3 camPos   = mc.player.getEyePosition(1.0f);
        Vec3 velocity = mc.player.getDeltaMovement();
        if (velocity.lengthSqr() < 0.01) return null;

        Vec3 fwd    = velocity.normalize();
        Vec3[] rays = { fwd, fwd.yRot(0.5f), fwd.yRot(-0.5f) };

        boolean obstacleDetected = false;
        for (Vec3 dir : rays) {
            BlockHitResult hit = mc.level.clip(new ClipContext(
                camPos, camPos.add(dir.scale(avoidanceDistance.getValue())),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player
            ));
            if (hit.getType() == HitResult.Type.BLOCK) { obstacleDetected = true; break; }
        }
        if (!obstacleDetected) return null;

        if (isPatternMode()) {
            currentTarget = null;
        }

        Vec3 leftDir  = fwd.yRot(1.5f);
        Vec3 rightDir = fwd.yRot(-1.5f);
        double checkDist = avoidanceDistance.getValue() * 1.5;

        boolean leftClear = mc.level.clip(new ClipContext(
            camPos, camPos.add(leftDir.scale(checkDist)),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player
        )).getType() == HitResult.Type.MISS;

        boolean rightClear = mc.level.clip(new ClipContext(
            camPos, camPos.add(rightDir.scale(checkDist)),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player
        )).getType() == HitResult.Type.MISS;

        float yawSpeed = 5.0f;
        if (limitRotationSpeed.getValue()) yawSpeed = Math.min(yawSpeed, maxRotationPerTick.getValue().floatValue());

        if (leftClear && !rightClear) {
            mc.player.setYRot(mc.player.getYRot() + yawSpeed);
        } else if (rightClear && !leftClear) {
            mc.player.setYRot(mc.player.getYRot() - yawSpeed);
        } else if (leftClear) {
            if (mc.player.tickCount % 2 == 0) mc.player.setYRot(mc.player.getYRot() + yawSpeed);
            else mc.player.setYRot(mc.player.getYRot() - yawSpeed);
        }

        float currentPitch = mc.player.getXRot();
        double speed       = mc.player.getDeltaMovement().horizontalDistance();
        float pullUpStr    = (float) Mth.clamp(speed * 20, 20, 60);

        if (shouldFireRocket() && countFireworks() > 0 && mc.player.getDeltaMovement().y < 0.2) {
            long now = System.currentTimeMillis();
            if (now - lastRocketTime >= COLLISION_ROCKET_COOLDOWN) {
                fireRocket();
                lastRocketTime = now;
            }
        }
        return Mth.lerp(0.3f, currentPitch, -pullUpStr);
    }

    private Float handleNormalMode() {
        if (!useTargetY.getValue()) {
            long now = System.currentTimeMillis();
            if (now - lastRocketTime >= rocketDelay.getValue()
                    && mc.player.getDeltaMovement().y < 0.5
                    && shouldFireRocket() && countFireworks() > 0) {
                fireRocket();
                lastRocketTime = now;
            }
            return null;
        }

        double currentY  = mc.player.getY();
        double target    = targetY.getValue();
        double tolerance = flightTolerance.getValue();
        double diff      = target - currentY;

        if      (diff > tolerance) ascentMode = true;
        else if (diff <= 0)        ascentMode = false;

        if (ascentMode) {
            long now = System.currentTimeMillis();
            if (now - lastRocketTime >= rocketDelay.getValue()
                    && mc.player.getDeltaMovement().y < 0.5
                    && shouldFireRocket() && countFireworks() > 0) {
                fireRocket();
                lastRocketTime = now;
            }
        }

        float calculatedPitch;
        if (Math.abs(diff) < 0.5) {
            calculatedPitch = 0.0f;
        } else {
            calculatedPitch = (float) (-Math.tanh(diff / 10.0) * 60.0);
            calculatedPitch = Mth.clamp(calculatedPitch, -60.0f, 45.0f);
        }

        targetPitch = calculatedPitch;
        float smooth = pitchSmoothing.getValue().floatValue();
        return mc.player.getXRot() + (targetPitch - mc.player.getXRot()) * smooth;
    }

    private Float handlePitch40Mode() {
        double currentY = mc.player.getY();
        double upperY   = pitch40UpperY.getValue();
        double lowerY   = pitch40LowerY.getValue();
        float  smooth   = pitch40Smoothing.getValue().floatValue();

        if      (currentY <= lowerY) { pitch40Climbing = true; }
        else if (currentY >= upperY) { pitch40Climbing = false; pitch40Rocketing = false; }

        if (currentY < lowerY) {
            if (pitch40BelowMinStartTime < 0) pitch40BelowMinStartTime = System.currentTimeMillis();
            if (System.currentTimeMillis() - pitch40BelowMinStartTime > pitch40BelowMinDelay.getValue()) {
                pitch40Rocketing = true;
            }
        } else {
            pitch40BelowMinStartTime = -1;
        }

        float pitch = pitch40Climbing
            ? Mth.lerp(smooth, mc.player.getXRot(), -40f)
            : Mth.lerp(smooth, mc.player.getXRot(),  40f);

        if (pitch40Rocketing) {
            long now = System.currentTimeMillis();
            if (now - lastRocketTime >= rocketDelay.getValue() && shouldFireRocket() && countFireworks() > 0) {
                fireRocket();
                lastRocketTime = now;
            }
        }
        return pitch;
    }

    private Float handleAltitudeBounceMode() {
        double currentY = mc.player.getY();
        double peakY    = bouncePeakY.getValue();
        double floorY   = bounceFloorY.getValue();
        float  smooth   = bouncePitchSmoothing.getValue().floatValue();

        if (bounceClimbing && currentY >= peakY)  bounceClimbing = false;
        if (!bounceClimbing && currentY <= floorY) bounceClimbing = true;

        if (bounceClimbing) {
            long now = System.currentTimeMillis();
            if (now - lastRocketTime >= rocketDelay.getValue()
                    && mc.player.getDeltaMovement().y < 0.5
                    && shouldFireRocket() && countFireworks() > 0) {
                fireRocket();
                lastRocketTime = now;
            }
            return Mth.lerp(smooth, mc.player.getXRot(), bounceClimbPitch.getValue().floatValue());
        } else {
            return Mth.lerp(smooth, mc.player.getXRot(), bounceGlidePitch.getValue().floatValue());
        }
    }

    private void handlePatternYaw() {
        if (paused) return;

        if (flightPattern.getValue() != FlightPattern.Manual && flightPattern.getValue() != FlightPattern.Drunk) {
            if (origin == null) origin = mc.player.position();

            if (currentTarget == null) {
                calculateNextTarget();
            } else {
                double dx = currentTarget.x - mc.player.getX();
                double dz = currentTarget.z - mc.player.getZ();

                if (sweepAutoUpdate.getValue() && flightPattern.getValue() == FlightPattern.Sweep && (dx * dx + dz * dz) > 4096.0) {
                    resetPatternState();
                    return;
                }

                int    radius = waypointReachRadius.getValue();
                if (dx * dx + dz * dz < (double)(radius * radius)) calculateNextTarget();
            }

            if (currentTarget != null) {
                double dx = currentTarget.x - mc.player.getX();
                double dz = currentTarget.z - mc.player.getZ();
                float targetYaw  = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float currentYaw = mc.player.getYRot();
                float diffYaw    = Mth.wrapDegrees(targetYaw - currentYaw);
                float yawChange  = diffYaw * patternTurnSpeed.getValue().floatValue();
                if (limitRotationSpeed.getValue()) {
                    yawChange = Mth.clamp(yawChange,
                        -maxRotationPerTick.getValue().floatValue(),
                         maxRotationPerTick.getValue().floatValue());
                }
                mc.player.setYRot(currentYaw + yawChange);
            }
        } else {
            currentTarget = null;
        }
    }

    private void calculateNextTarget() {
        if (origin == null) origin = mc.player.position();

        double targetYValue  = useTargetY.getValue() ? targetY.getValue() : mc.player.getY();
        double nextX, nextZ;
        FlightPattern currentPattern = flightPattern.getValue();

        if (currentPattern == FlightPattern.Manual || currentPattern == FlightPattern.Drunk) { currentTarget = null; return; }

        if (currentPattern == FlightPattern.Grid) {
            int spacing = gridSpacing.getValue() * 16;
            if (currentTarget == null) {
                gridDirection  = 3;
                gridStepsInLeg = 0;
                Vec3 offset = getGridDirectionOffset(gridDirection, spacing);
                nextX = origin.x + offset.x;
                nextZ = origin.z + offset.z;
                gridStepsInLeg = 1;
            } else {
                if (gridStepsInLeg >= gridStep) {
                    gridDirection  = (gridDirection + 1) % 4;
                    gridStepsInLeg = 0;
                    if (gridDirection == 0 || gridDirection == 2) gridStep++;
                }
                Vec3 offset = getGridDirectionOffset(gridDirection, spacing);
                nextX = currentTarget.x + offset.x;
                nextZ = currentTarget.z + offset.z;
                gridStepsInLeg++;
            }
        } else if (currentPattern == FlightPattern.ZigZag) {
            double legLength = zigzagLegLength.getValue() * 16.0;
            if (currentTarget == null) {
                zigzagCurrentYaw = mc.player.getYRot();
                zigzagTurnRight  = true;
                zigzagFirstLeg   = true;
            }
            if (zigzagFirstLeg) {
                zigzagFirstLeg = false;
            } else {
                double turnAmount = zigzagAngle.getValue() * 2.0;
                zigzagCurrentYaw = Mth.wrapDegrees(
                    zigzagCurrentYaw + (float)(zigzagTurnRight ? turnAmount : -turnAmount)
                );
                zigzagTurnRight = !zigzagTurnRight;
            }
            double radYaw    = Math.toRadians(zigzagCurrentYaw);
            Vec3 startPoint = (currentTarget != null) ? currentTarget : origin;
            nextX = startPoint.x + (-Math.sin(radYaw) * legLength);
            nextZ = startPoint.z + ( Math.cos(radYaw) * legLength);
        } else if (currentPattern == FlightPattern.FigureEight) {
            double r = figureEightRadius.getValue() * 16.0;
            double x_off, z_off;
            switch (figureEightWaypoint) {
                case 0: x_off =  r; z_off =  r;    break;
                case 1: x_off =  0; z_off =  2*r;  break;
                case 2: x_off = -r; z_off =  r;    break;
                case 3: x_off =  0; z_off =  0;    break;
                case 4: x_off = -r; z_off = -r;    break;
                case 5: x_off =  0; z_off = -2*r;  break;
                case 6: x_off =  r; z_off = -r;    break;
                default: x_off = 0; z_off =  0;    break;
            }
            nextX = origin.x + x_off;
            nextZ = origin.z + z_off;
            figureEightWaypoint = (figureEightWaypoint + 1) % 8;
        } else if (currentPattern == FlightPattern.Circle) {
            double angleStep       = 2.0 * Math.PI / circleSegments.getValue();
            double expansionBlocks = circleExpansion.getValue() * 16.0;
            double b               = expansionBlocks / (2.0 * Math.PI);
            double radius          = b * circleAngle;
            nextX = origin.x + radius * Math.cos(circleAngle);
            nextZ = origin.z + radius * Math.sin(circleAngle);
            circleAngle += angleStep;
        } else if (currentPattern == FlightPattern.Hexagon || currentPattern == FlightPattern.Triangle) {
            int    sides      = currentPattern == FlightPattern.Hexagon ? 6 : 3;
            double extAngle   = 2.0 * Math.PI / sides;
            int    baseSide   = currentPattern == FlightPattern.Hexagon
                                ? hexagonSideLength.getValue() : triangleSideLength.getValue();
            int    expansion  = currentPattern == FlightPattern.Hexagon
                                ? hexagonExpansion.getValue() : triangleExpansion.getValue();

            int    totalSteps  = polygonRotation * sides + polygonSide;
            double growPerSide = (expansion * 16.0) / sides;
            double sideLen     = (baseSide * 16.0) + totalSteps * growPerSide;

            double heading = polygonSide * extAngle;
            Vec3 start    = (currentTarget != null) ? currentTarget : origin;
            nextX = start.x + Math.cos(heading) * sideLen;
            nextZ = start.z + Math.sin(heading) * sideLen;

            polygonSide++;
            if (polygonSide >= sides) {
                polygonSide = 0;
                polygonRotation++;
            }
        } else if (currentPattern == FlightPattern.Sweep) {
            if (currentTarget == null) {
                sweepInitialYaw = mc.player.getYRot();
                sweepStep = 0;
                currentSweepFactor = 1.0;
            }

            if (sweepExpansionRate.getValue() > 0.0 && sweepStep > 0 && sweepStep % 4 == 0) {
                currentSweepFactor = Math.min(sweepMaxFactor.getValue(), currentSweepFactor * (1.0 + sweepExpansionRate.getValue()));
            }

            double width   = sweepWidth.getValue() * 16.0 * currentSweepFactor;
            double advance = sweepAdvance.getValue() * 16.0 * currentSweepFactor;

            float rad = (float) Math.toRadians(sweepInitialYaw);
            Vec3 fwd  = new Vec3(-Math.sin(rad), 0, Math.cos(rad));
            Vec3 side = new Vec3(-Math.cos(rad), 0, -Math.sin(rad));
            Vec3 base = (currentTarget != null) ? currentTarget : origin;

            Vec3 move;
            switch (sweepStep % 4) {
                case 0:  move = side.scale(sweepStep == 0 ? -width : -width * 2.0); break;
                case 1:  move = fwd.scale(advance); break;
                case 2:  move = side.scale(width * 2.0);  break;
                default: move = fwd.scale(advance); break;
            }

            nextX = base.x + move.x;
            nextZ = base.z + move.z;

            sweepStep++;
        } else {
            return;
        }

        currentTarget = new Vec3(nextX, targetYValue, nextZ);
    }

    private Vec3 getGridDirectionOffset(int dir, int dist) {
        return switch (dir) {
            case 0 -> new Vec3( dist, 0,    0);
            case 1 -> new Vec3(   0, 0, -dist);
            case 2 -> new Vec3(-dist, 0,    0);
            case 3 -> new Vec3(   0, 0,  dist);
            default -> Vec3.ZERO;
        };
    }

    private void handleDrunkMode() {
        if (drunkSpiralMode.getValue() != DrunkSpiralMode.None) {
            if (paused) return;
            handleDrunkSpiralMode();
            return;
        }

        if (drunkTimer++ >= currentDrunkDuration) {
            float intensity = drunkIntensity.getValue().floatValue();
            DrunkBias bias  = drunkBias.getValue();

            if (bias == DrunkBias.None) {
                if (drunkAvoidVisited.getValue()) {
                    float bestCandidate = mc.player.getYRot();

                    for (int i = 0; i < 10; i++) {
                        float candidate = mc.player.getYRot() + (float)((Math.random() - 0.5) * 2.0 * intensity);
                        double rad = Math.toRadians(candidate);
                        boolean pathVisited = false;

                        for (int dist : new int[]{16, 32, 48}) {
                            int cx = (int) Math.floor((mc.player.getX() - Math.sin(rad) * dist) / 16.0);
                            int cz = (int) Math.floor((mc.player.getZ() + Math.cos(rad) * dist) / 16.0);
                            if (drunkVisitedChunks.contains(ChunkPos.asLong(cx, cz))) {
                                pathVisited = true;
                                break;
                            }
                        }
                        if (!pathVisited) {
                            bestCandidate = candidate;
                            break;
                        }
                        if (i == 0) bestCandidate = candidate;
                    }
                    targetDrunkYaw = bestCandidate;
                } else {
                    targetDrunkYaw = mc.player.getYRot() + (float)((Math.random() - 0.5) * 2.0 * intensity);
                }
            } else {
                float minYaw, maxYaw;
                boolean isNorth = false;
                switch (bias) {
                    case North        -> { isNorth = true; minYaw = 0; maxYaw = 0; }
                    case South        -> { minYaw = -22.5f; maxYaw =  22.5f; }
                    case East         -> { minYaw = -112.5f; maxYaw = -67.5f; }
                    case West         -> { minYaw =  67.5f; maxYaw = 112.5f; }
                    case PositiveOnly -> { minYaw = -90f;  maxYaw =   0f; }
                    case NegativeOnly -> { minYaw =  90f;  maxYaw = 180f; }
                    case NegPos       -> { minYaw =   0f;  maxYaw =  90f; }
                    case PosNeg       -> { minYaw = -180f; maxYaw = -90f; }
                    default           -> { minYaw = -180f; maxYaw = 180f; }
                }

                if (isNorth) {
                    targetDrunkYaw = 180f + ((float)Math.random() * 45f - 22.5f);
                } else {
                    targetDrunkYaw = minYaw + (float)(Math.random() * (maxYaw - minYaw));
                }
            }

            drunkTimer           = 0;
            currentDrunkDuration = drunkInterval.getValue() + (int)(Math.random() * 10);
        }

        float currentYaw = mc.player.getYRot();
        float diffYaw    = Mth.wrapDegrees(targetDrunkYaw - currentYaw);
        float change     = diffYaw * drunkSmoothing.getValue().floatValue();

        if (limitRotationSpeed.getValue()) {
            float max = maxRotationPerTick.getValue().floatValue();
            change = Mth.clamp(change, -max, max);
        }
        mc.player.setYRot(currentYaw + change);
    }

    private void handleDrunkSpiralMode() {
        if (mc.player == null) return;
        if (drunkSpiralOrigin == null) drunkSpiralOrigin = mc.player.position();

        if (drunkSpiralTarget == null) {
            calculateDrunkSpiralTarget();
        } else {
            double dx = drunkSpiralTarget.x - mc.player.getX();
            double dz = drunkSpiralTarget.z - mc.player.getZ();
            int    r  = drunkSpiralReach.getValue();
            if (dx * dx + dz * dz < (double)(r * r)) calculateDrunkSpiralTarget();
        }
        if (drunkSpiralTarget == null) return;

        if (drunkTimer++ >= currentDrunkDuration) {
            double dx = drunkSpiralTarget.x - mc.player.getX();
            double dz = drunkSpiralTarget.z - mc.player.getZ();
            float baseYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float noise   = (float)((Math.random() - 0.5) * 2.0 * drunkSpiralNoise.getValue());
            targetDrunkYaw = Mth.wrapDegrees(baseYaw + noise);

            drunkTimer           = 0;
            currentDrunkDuration = drunkInterval.getValue() + (int)(Math.random() * 10);
        }

        float currentYaw = mc.player.getYRot();
        float diffYaw    = Mth.wrapDegrees(targetDrunkYaw - currentYaw);
        float change     = diffYaw * drunkSmoothing.getValue().floatValue();

        if (limitRotationSpeed.getValue()) {
            float max = maxRotationPerTick.getValue().floatValue();
            change = Mth.clamp(change, -max, max);
        }
        mc.player.setYRot(currentYaw + change);
    }

    private void calculateDrunkSpiralTarget() {
        if (drunkSpiralOrigin == null) drunkSpiralOrigin = mc.player.position();

        double targetYValue = useTargetY.getValue() ? targetY.getValue() : mc.player.getY();
        double nextX, nextZ;

        if (drunkSpiralMode.getValue() == DrunkSpiralMode.Grid) {
            int spacing = drunkGridSpacing.getValue() * 16;
            if (drunkSpiralTarget == null) {
                drunkGridDirection  = 3;
                drunkGridStepsInLeg = 0;
                Vec3 off = getGridDirectionOffset(drunkGridDirection, spacing);
                nextX = drunkSpiralOrigin.x + off.x;
                nextZ = drunkSpiralOrigin.z + off.z;
                drunkGridStepsInLeg = 1;
            } else {
                if (drunkGridStepsInLeg >= drunkGridStep) {
                    drunkGridDirection  = (drunkGridDirection + 1) % 4;
                    drunkGridStepsInLeg = 0;
                    if (drunkGridDirection == 0 || drunkGridDirection == 2) drunkGridStep++;
                }
                Vec3 off = getGridDirectionOffset(drunkGridDirection, spacing);
                nextX = drunkSpiralTarget.x + off.x;
                nextZ = drunkSpiralTarget.z + off.z;
                drunkGridStepsInLeg++;
            }
        } else if (drunkSpiralMode.getValue() == DrunkSpiralMode.Hexagon || drunkSpiralMode.getValue() == DrunkSpiralMode.Triangle) {
            int    sides      = drunkSpiralMode.getValue() == DrunkSpiralMode.Hexagon ? 6 : 3;
            double extAngle   = 2.0 * Math.PI / sides;
            int    baseSide   = drunkSpiralMode.getValue() == DrunkSpiralMode.Hexagon
                                ? drunkHexagonSideLength.getValue() : drunkTriangleSideLength.getValue();
            int    expansion  = drunkSpiralMode.getValue() == DrunkSpiralMode.Hexagon
                                ? drunkHexagonExpansion.getValue() : drunkTriangleExpansion.getValue();

            int    totalSteps  = drunkPolygonRotation * sides + drunkPolygonSide;
            double growPerSide = (expansion * 16.0) / sides;
            double sideLen     = (baseSide * 16.0) + totalSteps * growPerSide;

            double heading = drunkPolygonSide * extAngle;
            Vec3 start    = (drunkSpiralTarget != null) ? drunkSpiralTarget : drunkSpiralOrigin;
            nextX = start.x + Math.cos(heading) * sideLen;
            nextZ = start.z + Math.sin(heading) * sideLen;

            drunkPolygonSide++;
            if (drunkPolygonSide >= sides) {
                drunkPolygonSide = 0;
                drunkPolygonRotation++;
            }
        } else {
            double angleStep       = 2.0 * Math.PI / drunkCircleSegments.getValue();
            double expansionBlocks = drunkCircleExpansion.getValue() * 16.0;
            double b               = expansionBlocks / (2.0 * Math.PI);
            double radius          = b * drunkCircleAngle;
            nextX = drunkSpiralOrigin.x + radius * Math.cos(drunkCircleAngle);
            nextZ = drunkSpiralOrigin.z + radius * Math.sin(drunkCircleAngle);
            drunkCircleAngle += angleStep;
        }

        drunkSpiralTarget = new Vec3(nextX, targetYValue, nextZ);
    }

    private void applyPitch(Float desiredPitch) {
        if (desiredPitch == null) return;
        float current = mc.player.getXRot();
        if (limitRotationSpeed.getValue()) {
            float max  = maxRotationPerTick.getValue().floatValue();
            float diff = Mth.clamp(desiredPitch - current, -max, max);
            mc.player.setXRot(current + diff);
        } else {
            mc.player.setXRot(desiredPitch);
        }
    }

    public boolean shouldFireRocket() {
        if (mc.player == null) return false;
        ItemStack elytra = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.is(Items.ELYTRA)) return false;
        if (Math.abs(mc.player.getXRot()) > 70) return false;
        if (!needsTakeoffRocket && mc.player.getDeltaMovement().horizontalDistance() < 0.3) return false;
        return elytra.getDamageValue() < elytra.getMaxDamage() - 1;
    }

    public double getDurabilityPercent() {
        if (mc.player == null) return 100.0;
        ItemStack elytra = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.is(Items.ELYTRA)) return 100.0;
        return 100.0 * (elytra.getMaxDamage() - elytra.getDamageValue()) / (double) elytra.getMaxDamage();
    }

    private void replenishRockets() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.FIREWORK_ROCKET)) return;
        }
        int invSlot = -1;
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.FIREWORK_ROCKET)) { invSlot = i; break; }
        }
        if (invSlot == -1) return;

        int hotbarSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) { hotbarSlot = i; break; }
        }
        if (hotbarSlot == -1) return;
        InvUtils.moveToSlot(invSlot, InvUtils.toContainerSlot(hotbarSlot));
    }

    private int countFireworks() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.is(Items.FIREWORK_ROCKET)) count += s.getCount();
        }
        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand.is(Items.FIREWORK_ROCKET)) count += offhand.getCount();
        return count;
    }

    private Integer swapToFreshElytra() {
        int bestSlot = -1, bestDurability = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.ELYTRA)) {
                int dur = stack.getMaxDamage() - stack.getDamageValue();
                if (dur > bestDurability && dur > ELYTRA_MIN_SWAP_DUR) {
                    bestSlot = i; bestDurability = dur;
                }
            }
        }
        if (bestSlot == -1) return null;
        InvUtils.moveToSlot(InvUtils.toContainerSlot(bestSlot), InvUtils.ARMOR_CHESTPLATE_SLOT);
        return bestDurability;
    }

    private boolean isNearGround() {
        if (mc.player == null || mc.level == null) return false;
        if (mc.player.onGround()) return true;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= 3; i++) {
            pos.set(mc.player.getX(), mc.player.getY() - i, mc.player.getZ());
            if (mc.level.getBlockState(pos).entityCanStandOn(mc.level, pos, mc.player)) return true;
        }
        return false;
    }

    private void fireRocket() {
        if (mc.player == null || mc.gameMode == null) return;

        int rocketSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.FIREWORK_ROCKET)) { rocketSlot = i; break; }
        }

        if (rocketSlot == -1) {
            if (mc.player.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
                mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
                if (!silentRockets.getValue()) mc.player.swing(InteractionHand.OFF_HAND);
            }
            return;
        }

        int previousSlot = mc.player.getInventory().selected;
        mc.player.getInventory().selected = rocketSlot;
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        if (!silentRockets.getValue()) mc.player.swing(InteractionHand.MAIN_HAND);
        mc.player.getInventory().selected = previousSlot;
    }

    private void disconnect() {
        toggle();
        mc.disconnect();
    }
}
