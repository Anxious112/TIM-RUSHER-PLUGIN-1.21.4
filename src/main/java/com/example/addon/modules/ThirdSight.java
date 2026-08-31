package com.example.addon.modules;

import com.example.addon.Tim;

import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.client.input.EventMouse;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.setting.BindSetting;
import org.rusherhack.core.bind.key.NullKey;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.client.CameraType;

public class ThirdSight extends ToggleableModule {

    // ── General ──────────────────────────────────────────────────────────────

    private final BindSetting noDistanceKey = new BindSetting("no-distance-key",
        "Toggles a mode that disables camera distance modifications, allowing vanilla third person unless zooming.", NullKey.INSTANCE);

    public final NumberSetting<Double> distance = new NumberSetting<>("distance", "Camera distance from the player.", 4.0, 1.0, 30.0);

    public final NumberSetting<Double> transitionSpeed = new NumberSetting<>("transition-speed",
        "How smoothly the camera transitions between distances and FOV. 1.0 = instant.", 0.15, 0.01, 1.0);

    public final BooleanSetting customFov = new BooleanSetting("custom-first-person-fov",
        "Overrides your FOV while in First Person, allowing you to push past the vanilla limit of 115.", false);

    public final NumberSetting<Double> targetFov = new NumberSetting<>("target-fov",
        "The FOV to use while in First Person. Can be pushed past vanilla limits.", 110.0, 1.0, 200.0)
        .setVisibility(customFov::getValue);

    public final BooleanSetting freeLook = new BooleanSetting("free-look", "Orbit the camera around the player without affecting movement direction.", true);

    public final NumberSetting<Double> sensitivity = new NumberSetting<>("sensitivity", "Free-look mouse sensitivity.", 1.0, 1.0, 20.0)
        .setVisibility(freeLook::getValue);

    public final NumberSetting<Double> followSpeed = new NumberSetting<>("follow-speed",
        "How quickly the camera yaw catches up to the direction you're looking when free-look is off. 1.0 = instant.", 0.12, 0.01, 1.0)
        .setVisibility(() -> !freeLook.getValue());

    // ── Scroll-Wheel Adjustment ───────────────────────────────────────────────

    public final BooleanSetting scrollWheelAdjust = new BooleanSetting("scroll-wheel-adjust",
        "Use the mouse scroll wheel to adjust the third-person camera distance on the fly. "
            + "Only active while in third person and not zooming. Disabling reverts to the slider value.", false);

    public final BindSetting scrollWheelKey = new BindSetting("scroll-wheel-key",
        "Keybind to toggle the scroll wheel camera distance adjustment on or off.", NullKey.INSTANCE);

    public final NumberSetting<Double> scrollSpeed = new NumberSetting<>("scroll-speed",
        "Distance added/removed per scroll click. Positive scroll = zoom in, negative = zoom out.", 1.0, 0.1, 5.0)
        .setVisibility(scrollWheelAdjust::getValue);

    // ── Zoom ─────────────────────────────────────────────────────────────────

    public final NumberSetting<Double> zoomDistance = new NumberSetting<>("zoom-distance", "Camera distance when zoomed in.", 2.0, 0.5, 30.0);

    public final NumberSetting<Double> zoomFov = new NumberSetting<>("zoom-fov", "Field of View when zooming in First Person.", 30.0, 1.0, 110.0);

    public final BindSetting zoomKey = new BindSetting("zoom-key", "Key to activate zoom.", NullKey.INSTANCE);

    public final BooleanSetting zoomToggle = new BooleanSetting("toggle-mode", "If true, press to toggle zoom. If false, hold to zoom.", false);

    // ── State ─────────────────────────────────────────────────────────────────

    public float cameraYaw   = 0f;
    public float cameraPitch = 0f;

    private double  currentDistance         = 4.0;
    private boolean isZooming               = false;
    private boolean wasZoomKeyPressed       = false;
    private boolean noDistanceActive        = false;
    private boolean wasNoDistanceKeyPressed = false;
    private double  originalFov             = -1;
    private double  currentFov              = 0;

    private double scrollTargetDistance     = 4.0;
    private double lastKnownSliderDistance  = 4.0;
    private boolean wasScrollKeyPressed     = false;

    private CameraType previousPerspective = null;

    public ThirdSight() {
        super("third-sight", "Third-person camera with configurable distance, no block clipping, and free look.", Tim.CATEGORY);
        this.registerSettings(
            noDistanceKey, distance, transitionSpeed, customFov, targetFov, freeLook, sensitivity, followSpeed,
            scrollWheelAdjust, scrollWheelKey, scrollSpeed, zoomDistance, zoomFov, zoomKey, zoomToggle
        );
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.options == null) return;

        cameraYaw   = mc.player.getYRot();
        cameraPitch = Math.max(-89.9f, Math.min(89.9f, mc.player.getXRot()));

        previousPerspective = mc.options.getCameraType();

        currentDistance = (previousPerspective == CameraType.FIRST_PERSON) ? 0.0 : 4.0;

        if (previousPerspective == CameraType.FIRST_PERSON)
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);

        isZooming               = false;
        wasZoomKeyPressed       = false;
        noDistanceActive        = false;
        wasNoDistanceKeyPressed = false;
        wasScrollKeyPressed     = false;

        originalFov = -1;

        scrollTargetDistance    = distance.getValue();
        lastKnownSliderDistance = distance.getValue();
    }

    @Override
    public void onDisable() {
        if (mc.options != null) {
            if (previousPerspective != null)
                mc.options.setCameraType(previousPerspective);
            if (originalFov != -1)
                mc.options.fov().set((int) originalFov);
        }

        previousPerspective = null;
        originalFov = -1;
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.player == null || mc.options == null) return;

        if (mc.screen == null) {
            boolean noDistPressed = noDistanceKey.getValue().isKeyDown();
            if (noDistPressed && !wasNoDistanceKeyPressed) {
                noDistanceActive = !noDistanceActive;
                this.sendNotification(NotificationType.INFO, "No Distance mode " + (noDistanceActive ? "enabled" : "disabled") + ".");
            }
            wasNoDistanceKeyPressed = noDistPressed;

            boolean zoomPressed = zoomKey.getValue().isKeyDown();
            if (zoomToggle.getValue()) {
                if (zoomPressed && !wasZoomKeyPressed) isZooming = !isZooming;
            } else {
                isZooming = zoomPressed;
            }
            wasZoomKeyPressed = zoomPressed;

            boolean scrollPressed = scrollWheelKey.getValue().isKeyDown();
            if (scrollPressed && !wasScrollKeyPressed) {
                scrollWheelAdjust.setValue(!scrollWheelAdjust.getValue());
                this.sendNotification(NotificationType.INFO, "Scroll Wheel Adjust " + (scrollWheelAdjust.getValue() ? "enabled" : "disabled") + ".");
            }
            wasScrollKeyPressed = scrollPressed;

        } else {
            wasNoDistanceKeyPressed = false;
            wasZoomKeyPressed       = false;
            wasScrollKeyPressed     = false;
            if (!zoomToggle.getValue()) isZooming = false;
        }

        double currentSliderValue = distance.getValue();
        if (currentSliderValue != lastKnownSliderDistance) {
            scrollTargetDistance = currentSliderValue;
            lastKnownSliderDistance = currentSliderValue;
        }

        if (noDistanceActive) {
            if (previousPerspective != null) {
                mc.options.setCameraType(previousPerspective);
                previousPerspective = null;
            }
        } else {
            if (previousPerspective == null)
                previousPerspective = mc.options.getCameraType();
            if (mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK)
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
    }

    @Subscribe
    private void onMouseScroll(EventMouse.Scroll event) {
        if (!scrollWheelAdjust.getValue()) return;
        if (mc.player == null || mc.options == null) return;
        if (mc.screen != null) return;
        if (noDistanceActive) return;
        if (isZooming) return;
        if (mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) return;

        event.setCancelled(true);

        double delta = event.getScrollDeltaY();
        if (delta == 0.0) return;

        double next = scrollTargetDistance - delta * scrollSpeed.getValue();
        next = Math.max(1.0, Math.min(30.0, next));

        if (next == scrollTargetDistance) return;

        scrollTargetDistance = next;
        lastKnownSliderDistance = distance.getValue();
    }

    @Subscribe
    private void onRender(EventRender3D event) {
        double speed = transitionSpeed.getValue();
        double targetDist;

        if (noDistanceActive)                  targetDist = 4.0;
        else if (isZooming)                    targetDist = zoomDistance.getValue();
        else if (scrollWheelAdjust.getValue())  targetDist = scrollTargetDistance;
        else                                    targetDist = distance.getValue();

        boolean shouldFollow = !freeLook.getValue() && mc.player != null;
        if (shouldFollow) {
            float playerYaw = mc.player.getYRot();
            float yawDiff = playerYaw - cameraYaw;
            if (yawDiff >  180f) yawDiff -= 360f;
            if (yawDiff < -180f) yawDiff += 360f;
            float fs = followSpeed.getValue().floatValue();
            cameraYaw += yawDiff * fs;
        }

        currentDistance += (targetDist - currentDistance) * speed;
        if (Math.abs(targetDist - currentDistance) < 0.01) currentDistance = targetDist;

        double targetFovValue = originalFov;

        if (mc.options.getCameraType().isFirstPerson() && customFov.getValue()) {
            if (isZooming) {
                targetFovValue = zoomFov.getValue();
            } else {
                targetFovValue = targetFov.getValue();
            }
        }

        if (targetFovValue != originalFov) {
            if (originalFov == -1) {
                originalFov = mc.options.fov().get();
                currentFov = originalFov;
            }
            currentFov += (targetFovValue - currentFov) * speed;
            if (Math.abs(targetFovValue - currentFov) < 0.1) currentFov = targetFovValue;
            mc.options.fov().set((int) currentFov);
        } else if (originalFov != -1) {
            currentFov += (originalFov - currentFov) * speed;
            if (Math.abs(originalFov - currentFov) < 0.1) {
                currentFov = originalFov;
                mc.options.fov().set((int) originalFov);
                originalFov = -1;
            } else {
                mc.options.fov().set((int) currentFov);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public double getDistance() { return currentDistance; }

    public boolean isZooming() { return isZooming; }

    public void setZooming(boolean z) { this.isZooming = z; }

    public boolean isNoDistanceActive() { return noDistanceActive; }

    public double getScrollTargetDistance() { return scrollTargetDistance; }

    public void resetScrollDistance() {
        scrollTargetDistance = distance.getValue();
        lastKnownSliderDistance = distance.getValue();
    }

    public boolean isBeaconEffectCountered() {
        return isToggled();
    }

    public boolean isFreeLookActive() {
        if (!isToggled()) return false;
        if (mc.options.getCameraType().isFirstPerson()) return false;
        if (noDistanceActive && !isZooming()) return false;
        return freeLook.getValue();
    }
}
