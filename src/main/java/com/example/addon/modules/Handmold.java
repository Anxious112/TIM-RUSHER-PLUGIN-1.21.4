package com.example.addon.modules;

import com.example.addon.Tim;

import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;

public class Handmold extends ToggleableModule {

    // ── General ───────────────────────────────────────────────────────────────

    private final BooleanSetting noHandBob = new BooleanSetting("no-hand-bob", "Disables hand bobbing while walking.", false);
    private final BooleanSetting hideEmptyMainhand = new BooleanSetting("hide-empty-mainhand", "Hides the main hand when not holding any item.", false);
    private final BooleanSetting hideOffhandCompletely = new BooleanSetting("hide-offhand-completely", "Hides the offhand completely regardless of what it holds.", false);

    public enum EatPosition { StayInPlace, Custom }

    private final EnumSetting<EatPosition> eatPosition = new EnumSetting<>("eat-position",
        "Where the hand moves to while eating or drinking. Custom defaults to 0 (center) unless changed.", EatPosition.Custom);

    private final NumberSetting<Double> eatTargetX = new NumberSetting<>("eat-target-x",
        "The X position the hand slides to while eating. 0 = center, negative = left, positive = right.", 0.0, -2.0, 2.0)
        .setVisibility(() -> eatPosition.getValue() == EatPosition.Custom);

    // ── Main Hand ─────────────────────────────────────────────────────────────

    private final NumberSetting<Double> mainX = new NumberSetting<>("x", "Horizontal offset.", 0.0, -2.0, 2.0);
    private final NumberSetting<Double> mainY = new NumberSetting<>("y", "Vertical offset.", 0.0, -2.0, 2.0);
    private final NumberSetting<Double> mainZ = new NumberSetting<>("z", "Depth offset.", 0.0, -2.0, 2.0);
    private final NumberSetting<Double> mainScale = new NumberSetting<>("scale", "Scale multiplier.", 1.0, 0.1, 3.0);
    private final NumberSetting<Double> mainRotX = new NumberSetting<>("rot-x", "Rotation around X axis (degrees).", 0.0, -180.0, 180.0);
    private final NumberSetting<Double> mainRotY = new NumberSetting<>("rot-y", "Rotation around Y axis (degrees).", 0.0, -180.0, 180.0);
    private final NumberSetting<Double> mainRotZ = new NumberSetting<>("rot-z", "Rotation around Z axis (degrees).", 0.0, -180.0, 180.0);

    // ── Off Hand ──────────────────────────────────────────────────────────────

    private final NumberSetting<Double> offX = new NumberSetting<>("x", "Horizontal offset.", 0.0, -2.0, 2.0);
    private final NumberSetting<Double> offY = new NumberSetting<>("y", "Vertical offset.", 0.0, -2.0, 2.0);
    private final NumberSetting<Double> offZ = new NumberSetting<>("z", "Depth offset.", 0.0, -2.0, 2.0);
    private final NumberSetting<Double> offScale = new NumberSetting<>("scale", "Scale multiplier.", 1.0, 0.1, 3.0);
    private final NumberSetting<Double> offRotX = new NumberSetting<>("rot-x", "Rotation around X axis (degrees).", 0.0, -180.0, 180.0);
    private final NumberSetting<Double> offRotY = new NumberSetting<>("rot-y", "Rotation around Y axis (degrees).", 0.0, -180.0, 180.0);
    private final NumberSetting<Double> offRotZ = new NumberSetting<>("rot-z", "Rotation around Z axis (degrees).", 0.0, -180.0, 180.0);

    // ── Constructor ───────────────────────────────────────────────────────────

    public Handmold() {
        super("handmold", "Adjusts the position, scale, and rotation of each hand independently.", Tim.CATEGORY);
        this.registerSettings(
            noHandBob, hideEmptyMainhand, hideOffhandCompletely, eatPosition, eatTargetX,
            mainX, mainY, mainZ, mainScale, mainRotX, mainRotY, mainRotZ,
            offX, offY, offZ, offScale, offRotX, offRotY, offRotZ
        );
    }

    // ── Public API — read by mixins ───────────────────────────────────────────

    public double getMainX()     { return mainX.getValue(); }
    public double getMainY()     { return mainY.getValue(); }
    public double getMainZ()     { return mainZ.getValue(); }
    public double getMainScale() { return mainScale.getValue(); }
    public double getMainRotX()  { return mainRotX.getValue(); }
    public double getMainRotY()  { return mainRotY.getValue(); }
    public double getMainRotZ()  { return mainRotZ.getValue(); }

    public double getOffX()      { return offX.getValue(); }
    public double getOffY()      { return offY.getValue(); }
    public double getOffZ()      { return offZ.getValue(); }
    public double getOffScale()  { return offScale.getValue(); }
    public double getOffRotX()   { return offRotX.getValue(); }
    public double getOffRotY()   { return offRotY.getValue(); }
    public double getOffRotZ()   { return offRotZ.getValue(); }

    public boolean shouldDisableHandBob()        { return isToggled() && noHandBob.getValue(); }
    public boolean shouldHideEmptyMainhand()     { return isToggled() && hideEmptyMainhand.getValue(); }
    public boolean shouldHideOffhandCompletely() { return isToggled() && hideOffhandCompletely.getValue(); }
    public EatPosition getEatPosition()          { return eatPosition.getValue(); }
    public double      getEatTargetX()           { return eatTargetX.getValue(); }
}
