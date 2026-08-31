package com.example.addon.modules;

import com.example.addon.Tim;
import com.example.addon.utils.InvUtils;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.utils.InventoryUtils;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.setting.StringSetting;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.util.Mth;

public class Handhold extends ToggleableModule {

    public enum Role { Leader, Follower }
    public enum OrbitSide { Left, Right }

    private enum FollowerState { TRACKING, PANIC_BOOST, WAITING }
    private enum LeaderState { NORMAL, SLOWING_DOWN }

    public final StringSetting targetName = new StringSetting("target", "Leader's name if you are Follower, Follower's name if you are Leader.", "");

    private final EnumSetting<Role> role = new EnumSetting<>("role", "Are you leading the flight, or following?", Role.Follower)
        .onChange((java.util.function.Consumer<Role>) v -> {
            if (v == Role.Leader) this.sendNotification(NotificationType.INFO, "Leader mode: Watching for disconnects.");
            else this.sendNotification(NotificationType.INFO, "Follower mode: Tracking target.");
        });

    private final BooleanSetting disableWhenTargetLands = new BooleanSetting("disable-when-target-lands", "Disable Handhold and Rocket Pilot when the target stops flying", true)
        .setVisibility(() -> role.getValue() == Role.Follower);

    private final BooleanSetting enablePaceControl = new BooleanSetting("pace-control", "Automatically slow down if you pull too far ahead of the follower.", true)
        .setVisibility(() -> role.getValue() == Role.Leader);

    private final NumberSetting<Double> maxLeadDistance = new NumberSetting<>("max-lead-distance", "Horizontal distance before starting to slow down.", 50.0, 10.0, 128.0)
        .setVisibility(() -> role.getValue() == Role.Leader && enablePaceControl.getValue());

    private final NumberSetting<Double> resumeDistance = new NumberSetting<>("resume-distance", "Horizontal distance to the follower before resuming normal speed.", 30.0, 5.0, 100.0)
        .setVisibility(() -> role.getValue() == Role.Leader && enablePaceControl.getValue());

    private final NumberSetting<Double> slowdownPitch = new NumberSetting<>("slowdown-pitch", "How aggressively to pitch up to bleed speed (negative = up).", -35.0, -60.0, -5.0)
        .setVisibility(() -> role.getValue() == Role.Leader && enablePaceControl.getValue());

    private final NumberSetting<Double> minFollowDistance = new NumberSetting<>("min-follow-distance", "If closer than this, orbit instead of aiming directly at them.", 5.0, 1.0, 20.0)
        .setVisibility(() -> role.getValue() == Role.Follower);

    private final NumberSetting<Double> orbitOffset = new NumberSetting<>("orbit-offset", "How many degrees to shift your yaw when orbiting too close.", 25.0, 5.0, 90.0)
        .setVisibility(() -> role.getValue() == Role.Follower);

    private final EnumSetting<OrbitSide> orbitSide = new EnumSetting<>("orbit-side", "Which side to orbit on when you get too close.", OrbitSide.Left)
        .setVisibility(() -> role.getValue() == Role.Follower);

    private final BooleanSetting lookAtTarget = new BooleanSetting("look-at-target", "Always keep your camera aimed at the target.", true)
        .setVisibility(() -> role.getValue() == Role.Follower);

    private final NumberSetting<Double> rotationSpeed = new NumberSetting<>("rotation-speed", "How smoothly to turn towards the target (lower = smoother).", 0.1, 0.01, 1.0)
        .setVisibility(() -> role.getValue() == Role.Follower);

    private final BooleanSetting limitRotationSpeed = new BooleanSetting("limit-rotation-speed", "Caps rotation speed per tick to reduce anti-cheat flags.", true)
        .setVisibility(() -> role.getValue() == Role.Follower);

    private final NumberSetting<Double> maxRotationPerTick = new NumberSetting<>("max-rotation-per-tick", "Maximum degrees to rotate per tick.", 20.0, 1.0, 90.0)
        .setVisibility(() -> limitRotationSpeed.getValue() && role.getValue() == Role.Follower);

    private final BooleanSetting safetyDisconnect = new BooleanSetting("safety-disconnect", "Triggers a panic rocket towards their last location, then DCs if they aren't found.", true);

    private final NumberSetting<Double> disconnectDelay = new NumberSetting<>("disconnect-delay", "Seconds to fly towards last location before giving up and disconnecting.", 4.0, 1.0, 15.0)
        .setVisibility(safetyDisconnect::getValue);

    private final BooleanSetting pauseOnObstacle = new BooleanSetting("pause-on-obstacle", "Stops looking at target if a wall is in the way.", true)
        .setVisibility(() -> role.getValue() == Role.Follower);

    private final NumberSetting<Integer> obstaclePauseTicks = new NumberSetting<>("obstacle-pause-ticks", "How many ticks to pause tracking when an obstacle is hit.", 15, 5, 40)
        .setVisibility(() -> pauseOnObstacle.getValue() && role.getValue() == Role.Follower);

    private boolean wasTargetFlying = false;
    private boolean forcedRocketPilot = false;
    private int obstaclePauseTimer = 0;
    private boolean hasWarnedNotFound = false;
    private boolean wasInWorld = false;

    private FollowerState followerState = FollowerState.TRACKING;
    private float lastKnownYaw = 0;
    private int panicTimer = 0;
    private int waitTimerTicks = 0;
    private boolean hasFiredPanicRocket = false;

    private LeaderState leaderState = LeaderState.NORMAL;
    private RocketPilot.FlightMode savedRpMode = RocketPilot.FlightMode.Normal;
    private boolean savedRpTargetY = true;

    public Handhold() {
        super("handhold", "Follow a player or lead with mutual safety disconnects.", Tim.CATEGORY);
        this.registerSettings(
            targetName, role, disableWhenTargetLands,
            enablePaceControl, maxLeadDistance, resumeDistance, slowdownPitch,
            minFollowDistance, orbitOffset, orbitSide,
            lookAtTarget, rotationSpeed, limitRotationSpeed, maxRotationPerTick,
            safetyDisconnect, disconnectDelay, pauseOnObstacle, obstaclePauseTicks
        );
    }

    private RocketPilot rocketPilot() {
        return (RocketPilot) RusherHackAPI.getModuleManager().getFeature("rocket-pilot").orElse(null);
    }

    @Override
    public void onEnable() {
        wasTargetFlying = false;
        forcedRocketPilot = false;
        obstaclePauseTimer = 0;
        hasWarnedNotFound = false;
        wasInWorld = false;
        resetFollowerPanicState();
        resetLeaderSlowdownState();

        if (role.getValue() == Role.Leader) this.sendNotification(NotificationType.INFO, "Leading " + targetName.getValue() + ". Watching for disconnects.");
        else this.sendNotification(NotificationType.INFO, "Following " + targetName.getValue() + ".");
    }

    @Override
    public void onDisable() {
        if (forcedRocketPilot) {
            RocketPilot rp = rocketPilot();
            if (rp != null && rp.isToggled()) rp.toggle();
            forcedRocketPilot = false;
        }
        resetFollowerPanicState();
        resetLeaderSlowdownState();
    }

    private void resetFollowerPanicState() {
        followerState = FollowerState.TRACKING;
        lastKnownYaw = 0;
        panicTimer = 0;
        waitTimerTicks = 0;
        hasFiredPanicRocket = false;
    }

    private void resetLeaderSlowdownState() {
        if (leaderState == LeaderState.SLOWING_DOWN) {
            restoreRocketPilot();
        }
        leaderState = LeaderState.NORMAL;
    }

    private void overrideRocketPilot() {
        RocketPilot rp = rocketPilot();
        if (rp != null && rp.isToggled()) {
            savedRpMode = rp.flightMode.getValue();
            savedRpTargetY = rp.useTargetY.getValue();

            rp.flightMode.setValue(RocketPilot.FlightMode.None);
            rp.useTargetY.setValue(false);
        }
    }

    private void restoreRocketPilot() {
        RocketPilot rp = rocketPilot();
        if (rp != null && rp.isToggled()) {
            rp.flightMode.setValue(savedRpMode);
            rp.useTargetY.setValue(savedRpTargetY);
        }
    }

    private Player getTarget() {
        if (targetName.getValue() == null || targetName.getValue().isEmpty()) return null;
        if (mc.level == null) return null;

        for (Player player : mc.level.players()) {
            if (player != mc.player &&
                player.getName().getString().equalsIgnoreCase(targetName.getValue())) {
                return player;
            }
        }
        return null;
    }

    private void forceDisconnect(String reason) {
        this.sendNotification(NotificationType.ERROR, reason);
        toggle();
        mc.disconnect();
    }

    private void firePanicRocket() {
        if (hasFiredPanicRocket || !mc.player.isFallFlying()) return;

        if (mc.player.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
            mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            hasFiredPanicRocket = true;
            return;
        }

        int rocketSlot = InventoryUtils.findItemHotbar(Items.FIREWORK_ROCKET);
        if (rocketSlot != -1) {
            int prevSlot = mc.player.getInventory().selected;
            mc.player.getInventory().selected = rocketSlot;
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.getInventory().selected = prevSlot;
            hasFiredPanicRocket = true;
        }
    }

    private void lookAtSmooth(Vec3 target) {
        Vec3 diff = target.subtract(mc.player.getEyePosition());
        if (diff.lengthSqr() < 0.01) return;

        double targetYawExact = Math.toDegrees(Math.atan2(-diff.x, diff.z));
        float targetYaw = (float) targetYawExact;

        double horizontalDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);

        if (horizontalDist < minFollowDistance.getValue()) {
            float offset = orbitOffset.getValue().floatValue();
            if (orbitSide.getValue() == OrbitSide.Right) offset = -offset;
            targetYaw += offset;
        }

        float currentYaw = mc.player.getYRot();
        float diffYaw = Mth.wrapDegrees(targetYaw - currentYaw);

        float desiredChange = diffYaw * rotationSpeed.getValue().floatValue();

        if (limitRotationSpeed.getValue()) {
            desiredChange = Mth.clamp(desiredChange,
                -maxRotationPerTick.getValue().floatValue(),
                 maxRotationPerTick.getValue().floatValue());
        }

        if (Math.abs(desiredChange) < 0.1f) return;

        float newYaw = currentYaw + desiredChange;

        mc.player.setYRot(newYaw);
        mc.player.yBodyRot = newYaw;
        mc.player.yHeadRot = newYaw;
    }

    private boolean isObstacleInWay(Vec3 targetPos) {
        if (!pauseOnObstacle.getValue()) return false;
        BlockHitResult hit = mc.level.clip(new ClipContext(
            mc.player.getEyePosition(), targetPos, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, mc.player
        ));
        return hit.getType() == HitResult.Type.BLOCK;
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.level == null || mc.player == null) return;

        boolean targetExists = getTarget() != null;

        if (role.getValue() == Role.Follower && safetyDisconnect.getValue() && !targetName.getValue().isEmpty()) {

            if (wasInWorld && !targetExists && followerState == FollowerState.TRACKING) {
                lastKnownYaw = mc.player.getYRot();
                followerState = FollowerState.PANIC_BOOST;
                panicTimer = 3;
                waitTimerTicks = (int)(disconnectDelay.getValue() * 20.0);
                hasFiredPanicRocket = false;
                this.sendNotification(NotificationType.WARNING, "Target lost visual! Firing panic rocket...");
            }

            if (followerState == FollowerState.PANIC_BOOST) {
                mc.player.setYRot(lastKnownYaw);
                mc.player.yBodyRot = lastKnownYaw;
                mc.player.yHeadRot = lastKnownYaw;
                firePanicRocket();
                panicTimer--;
                if (panicTimer <= 0) followerState = FollowerState.WAITING;
                return;
            }

            if (followerState == FollowerState.WAITING) {
                mc.player.setYRot(lastKnownYaw);
                mc.player.yBodyRot = lastKnownYaw;
                mc.player.yHeadRot = lastKnownYaw;

                waitTimerTicks--;

                if (targetExists) {
                    this.sendNotification(NotificationType.INFO, "Target re-acquired! Resuming normal tracking.");
                    resetFollowerPanicState();
                } else if (waitTimerTicks <= 0) {
                    forceDisconnect("[Handhold] Safety Disconnect: Lost " + targetName.getValue() + ".");
                    return;
                }
                return;
            }
        }

        if (safetyDisconnect.getValue() && !targetName.getValue().isEmpty()) {
            if (wasInWorld && !targetExists && role.getValue() == Role.Leader) {
                forceDisconnect("[Handhold] Safety Disconnect: " + targetName.getValue() + " left the server.");
                return;
            }
        }
        wasInWorld = targetExists;

        if (!targetExists) {
            if (!hasWarnedNotFound && !targetName.getValue().isEmpty()) {
                this.sendNotification(NotificationType.WARNING, "Waiting for " + targetName.getValue() + "...");
                hasWarnedNotFound = true;
            }
            if (forcedRocketPilot) {
                RocketPilot rp = rocketPilot();
                if (rp != null && rp.isToggled()) rp.toggle();
                forcedRocketPilot = false;
            }
            return;
        } else {
            if (hasWarnedNotFound) {
                this.sendNotification(NotificationType.INFO, "Locked onto " + targetName.getValue() + ".");
                hasWarnedNotFound = false;
            }
        }

        if (role.getValue() == Role.Leader) {
            if (!enablePaceControl.getValue() || !mc.player.isFallFlying()) {
                if (leaderState == LeaderState.SLOWING_DOWN) restoreRocketPilot();
                leaderState = LeaderState.NORMAL;
                return;
            }

            Player follower = getTarget();
            double dx = mc.player.getX() - follower.getX();
            double dz = mc.player.getZ() - follower.getZ();
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);

            if (leaderState == LeaderState.NORMAL) {
                if (horizontalDist > maxLeadDistance.getValue()) {
                    leaderState = LeaderState.SLOWING_DOWN;
                    overrideRocketPilot();
                    this.sendNotification(NotificationType.INFO, "Follower falling behind. Slowing down...");
                }
            } else if (leaderState == LeaderState.SLOWING_DOWN) {
                if (horizontalDist < resumeDistance.getValue()) {
                    leaderState = LeaderState.NORMAL;
                    restoreRocketPilot();
                    this.sendNotification(NotificationType.INFO, "Follower caught up. Resuming normal flight.");
                } else {
                    mc.player.setXRot(slowdownPitch.getValue().floatValue());
                    mc.player.yBodyRot = mc.player.getYRot();
                    mc.player.yHeadRot = mc.player.getYRot();
                }
            }
            return;
        }

        Player target = getTarget();
        boolean targetFlying = target.isFallFlying();

        if (lookAtTarget.getValue()) {
            if (obstaclePauseTimer > 0) {
                obstaclePauseTimer--;
            } else {
                Vec3 lookPos = targetFlying ?
                    target.position().add(target.getDeltaMovement().scale(5)) :
                    target.position();

                if (mc.player.isFallFlying() && isObstacleInWay(lookPos)) {
                    obstaclePauseTimer = obstaclePauseTicks.getValue();
                } else {
                    lookAtSmooth(lookPos);
                }
            }
        }

        if (targetFlying) {
            RocketPilot rp = rocketPilot();
            if (rp != null) {
                if (rp.flightPattern.getValue() != RocketPilot.FlightPattern.Manual) {
                    rp.flightPattern.setValue(RocketPilot.FlightPattern.Manual);
                    this.sendNotification(NotificationType.INFO, "Rocket Pilot pattern set to Manual for Handhold.");
                }

                if (!rp.isToggled()) {
                    rp.toggle();
                    forcedRocketPilot = true;
                    this.sendNotification(NotificationType.INFO, "Target started flying, enabled Rocket Pilot.");
                }
            }
        } else {
            if (wasTargetFlying) {
                if (disableWhenTargetLands.getValue()) {
                    if (forcedRocketPilot) {
                        RocketPilot rp = rocketPilot();
                        if (rp != null && rp.isToggled()) rp.toggle();
                        forcedRocketPilot = false;
                    }
                    this.sendNotification(NotificationType.INFO, "Target landed, disabling Handhold.");
                    this.toggle();
                    return;
                } else {
                    if (forcedRocketPilot) {
                        RocketPilot rp = rocketPilot();
                        if (rp != null && rp.isToggled()) rp.toggle();
                        forcedRocketPilot = false;
                        this.sendNotification(NotificationType.INFO, "Target landed, disabled Rocket Pilot.");
                    }
                }
            }
        }

        wasTargetFlying = targetFlying;
    }

    @Override
    public String getMetadata() {
        if (role.getValue() == Role.Leader) {
            if (leaderState == LeaderState.SLOWING_DOWN) return "Slowing Down";
            return "Leading: " + (targetName.getValue().isEmpty() ? "None" : targetName.getValue());
        }

        if (followerState == FollowerState.PANIC_BOOST) return "Panic Boost!";
        if (followerState == FollowerState.WAITING) {
            float remainingSecs = waitTimerTicks / 20.0f;
            return String.format("DC in %.1fs", remainingSecs);
        }

        Player target = getTarget();
        if (target == null) return "Searching...";
        return target.getName().getString() + (target.isFallFlying() ? " (flying)" : " (visual)");
    }
}
