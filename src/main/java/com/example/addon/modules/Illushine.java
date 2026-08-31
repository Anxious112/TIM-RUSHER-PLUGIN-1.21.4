package com.example.addon.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.example.addon.Tim;
import com.example.addon.utils.GlowingRegistry;

import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.client.api.setting.EntityTypeListSetting;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.LightLayer;

import java.awt.Color;

public class Illushine extends ToggleableModule {

    private enum MobCategory { PASSIVE, NEUTRAL, HOSTILE }

    public enum HighlightMode { Wireframe, Spectral }

    public enum CrosshairMode { None, WhiteDot, Normal }

    private static final Map<EntityType<?>, MobCategory> CATEGORY_OVERRIDES = new HashMap<>(Map.ofEntries(
        Map.entry(EntityType.FOX,              MobCategory.PASSIVE),
        Map.entry(EntityType.PIGLIN,           MobCategory.HOSTILE),
        Map.entry(EntityType.ZOMBIFIED_PIGLIN, MobCategory.NEUTRAL),
        Map.entry(EntityType.ENDERMAN,         MobCategory.NEUTRAL),
        Map.entry(EntityType.GOAT,             MobCategory.NEUTRAL),
        Map.entry(EntityType.PIGLIN_BRUTE,     MobCategory.HOSTILE),
        Map.entry(EntityType.GHAST,            MobCategory.HOSTILE),
        Map.entry(EntityType.SHULKER,          MobCategory.HOSTILE),
        Map.entry(EntityType.PHANTOM,          MobCategory.HOSTILE),
        Map.entry(EntityType.SLIME,            MobCategory.HOSTILE),
        Map.entry(EntityType.MAGMA_CUBE,       MobCategory.HOSTILE),
        Map.entry(EntityType.HOGLIN,           MobCategory.HOSTILE)
    ));

    private final EnumSetting<HighlightMode> highlightMode = new EnumSetting<>("highlight-mode",
        "How mobs are outlined. Wireframe draws a bounding-box outline; Spectral uses the vanilla glow pipeline.", HighlightMode.Wireframe);

    private final NumberSetting<Integer> range = new NumberSetting<>("range", "Range to highlight mobs.", 64, 1, 256);

    private final EntityTypeListSetting ignoredEntities = new EntityTypeListSetting("ignored-entities", "Entities to ignore.");

    private final NumberSetting<Double> outlineScale = new NumberSetting<>("outline-scale",
        "Scale of the wireframe outline (Wireframe mode only).", 1.0, 0.1, 2.0)
        .setVisibility(() -> highlightMode.getValue() == HighlightMode.Wireframe);

    private final EnumSetting<CrosshairMode> crosshairMode = new EnumSetting<>("crosshair-mode",
        "The crosshair style to display while Illushine is active.", CrosshairMode.Normal);

    private final ColorSetting crosshairColor = new ColorSetting("crosshair-color", "Color of the Normal crosshair lines.", new Color(255, 255, 255, 255))
        .setVisibility(() -> crosshairMode.getValue() == CrosshairMode.Normal);

    private final NumberSetting<Integer> crosshairSize = new NumberSetting<>("crosshair-size", "Half-length of each crosshair arm in pixels.", 5, 1, 20)
        .setVisibility(() -> crosshairMode.getValue() == CrosshairMode.Normal);

    private final NumberSetting<Integer> crosshairGap = new NumberSetting<>("crosshair-gap", "Gap (in pixels) between center and each arm.", 2, 0, 10)
        .setVisibility(() -> crosshairMode.getValue() == CrosshairMode.Normal);

    private final NumberSetting<Integer> crosshairThickness = new NumberSetting<>("crosshair-thickness", "Thickness of the crosshair lines in pixels.", 1, 1, 5)
        .setVisibility(() -> crosshairMode.getValue() == CrosshairMode.Normal);

    private final BooleanSetting highlightPassive = new BooleanSetting("highlight-passive", "Highlight passive mobs.", true);
    private final ColorSetting passiveColor = new ColorSetting("passive-color", "Outline color for passive mobs.", new Color(0, 255, 100, 255)).setVisibility(highlightPassive::getValue);
    private final NumberSetting<Double> passiveScale = new NumberSetting<>("passive-scale", "Visual scale for passive mobs.", 1.0, 0.1, 3.0).setVisibility(highlightPassive::getValue);

    private final BooleanSetting highlightNeutral = new BooleanSetting("highlight-neutral", "Highlight neutral mobs.", true);
    private final ColorSetting neutralColor = new ColorSetting("neutral-color", "Outline color for neutral mobs.", new Color(255, 200, 0, 255)).setVisibility(highlightNeutral::getValue);
    private final NumberSetting<Double> neutralScale = new NumberSetting<>("neutral-scale", "Visual scale for neutral mobs.", 1.0, 0.1, 3.0).setVisibility(highlightNeutral::getValue);

    private final BooleanSetting highlightHostile = new BooleanSetting("highlight-hostile", "Highlight hostile mobs.", true);
    private final ColorSetting hostileColor = new ColorSetting("hostile-color", "Outline color for hostile mobs.", new Color(255, 50, 50, 255)).setVisibility(highlightHostile::getValue);
    private final NumberSetting<Double> hostileScale = new NumberSetting<>("hostile-scale", "Visual scale for hostile mobs.", 1.0, 0.1, 3.0).setVisibility(highlightHostile::getValue);

    private final NumberSetting<Double> playerScale = new NumberSetting<>("player-scale", "Visually scales your own player model and camera height.", 1.0, 0.1, 3.0);

    private final BooleanSetting scaleOtherPlayers = new BooleanSetting("scale-other-players", "Apply a visual scale to other players.", false);

    private final NumberSetting<Double> otherPlayerScale = new NumberSetting<>("other-player-scale", "Visual scale applied to other players.", 1.0, 0.1, 3.0)
        .setVisibility(scaleOtherPlayers::getValue);

    private final Map<Integer, MobCategory> activelyOutlined = new HashMap<>();

    public Illushine() {
        super("illushine", "Highlights mobs with a wireframe or spectral outline by hostility type.", Tim.CATEGORY);
        this.registerSettings(
            highlightMode, range, ignoredEntities, outlineScale,
            crosshairMode, crosshairColor, crosshairSize, crosshairGap, crosshairThickness,
            highlightPassive, passiveColor, passiveScale,
            highlightNeutral, neutralColor, neutralScale,
            highlightHostile, hostileColor, hostileScale,
            playerScale, scaleOtherPlayers, otherPlayerScale
        );
    }

    public CrosshairMode getCrosshairMode() { return crosshairMode.getValue(); }
    public double getPlayerScale() { return playerScale.getValue(); }
    public boolean getScaleOtherPlayers() { return scaleOtherPlayers.getValue(); }
    public double getOtherPlayerScale() { return otherPlayerScale.getValue(); }

    public double getMobScale(Mob mob) {
        MobCategory cat = categorise(mob);
        return switch (cat) {
            case PASSIVE -> passiveScale.getValue();
            case NEUTRAL -> neutralScale.getValue();
            case HOSTILE -> hostileScale.getValue();
        };
    }

    @Override
    public void onEnable() {
        activelyOutlined.clear();
    }

    @Override
    public void onDisable() {
        for (Integer id : activelyOutlined.keySet()) {
            GlowingRegistry.remove(id);
        }
        activelyOutlined.clear();
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.level == null || mc.player == null) return;

        boolean spectral = highlightMode.getValue() == HighlightMode.Spectral;

        Map<Integer, MobCategory> newOutlined = new HashMap<>();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Mob mob)) continue;
            if (ignoredEntities.getList().contains(mob.getType())) continue;
            if (mc.player.distanceTo(mob) > range.getValue()) continue;

            MobCategory category = categorise(mob);
            boolean shouldHighlight = switch (category) {
                case PASSIVE -> highlightPassive.getValue();
                case NEUTRAL -> highlightNeutral.getValue();
                case HOSTILE -> highlightHostile.getValue();
            };
            if (!shouldHighlight) continue;

            newOutlined.put(mob.getId(), category);
        }

        if (spectral) {
            for (Integer id : activelyOutlined.keySet()) {
                if (!newOutlined.containsKey(id)) GlowingRegistry.remove(id);
            }
            for (Map.Entry<Integer, MobCategory> entry : newOutlined.entrySet()) {
                Color c = colorForCategory(entry.getValue());
                GlowingRegistry.add(entry.getKey(), c.getRGB());
            }
        } else {
            for (Integer id : activelyOutlined.keySet()) {
                GlowingRegistry.remove(id);
            }
        }

        activelyOutlined.clear();
        activelyOutlined.putAll(newOutlined);
    }

    @Subscribe
    private void onRender(EventRender3D event) {
        if (mc.level == null || mc.player == null || activelyOutlined.isEmpty()) return;

        boolean wireframe = highlightMode.getValue() == HighlightMode.Wireframe;
        if (!wireframe) return;

        IRenderer3D renderer = event.getRenderer();
        renderer.begin(event.getMatrixStack());

        for (Map.Entry<Integer, MobCategory> entry : activelyOutlined.entrySet()) {
            Entity entity = mc.level.getEntity(entry.getKey());
            if (!(entity instanceof Mob mob)) continue;

            Color color = colorForCategory(entry.getValue());
            renderer.drawBox(mob, event.getPartialTicks(), false, true, color.getRGB());
        }

        renderer.end();
    }

    public void drawCrosshair(GuiGraphics context) {
        if (mc.getWindow() == null) return;
        if (!mc.options.getCameraType().isFirstPerson()) return;
        if (mc.screen != null) return;

        int cx = mc.getWindow().getGuiScaledWidth()  / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;

        switch (crosshairMode.getValue()) {
            case WhiteDot -> context.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFFFFFF);
            case Normal   -> drawNormalCrosshair(context, cx, cy);
            default       -> {}
        }
    }

    private void drawNormalCrosshair(GuiGraphics context, int cx, int cy) {
        int arm = crosshairSize.getValue();
        int gap = crosshairGap.getValue();
        int th  = crosshairThickness.getValue();
        int col = crosshairColor.getValue().getRGB();

        int halfU = th / 2;
        int halfD = th - halfU;

        context.fill(cx - arm - gap, cy - halfU, cx - gap,       cy + halfD, col);
        context.fill(cx + gap,       cy - halfU, cx + arm + gap, cy + halfD, col);
        context.fill(cx - halfU,     cy - arm - gap, cx + halfD, cy - gap,   col);
        context.fill(cx - halfU,     cy + gap,       cx + halfD, cy + arm + gap, col);
    }

    private MobCategory categorise(Mob mob) {
        if (mob.getType() == EntityType.PIGLIN && mob.isBaby()) return MobCategory.PASSIVE;

        if (mob.getType() == EntityType.SPIDER || mob.getType() == EntityType.CAVE_SPIDER) {
            long time = mc.level.getDayTime() % 24000;
            boolean isDay = time < 13000;
            boolean canSeeSky = mc.level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(mob.blockPosition()) >= 15;
            return (isDay && canSeeSky) ? MobCategory.NEUTRAL : MobCategory.HOSTILE;
        }

        MobCategory override = CATEGORY_OVERRIDES.get(mob.getType());
        if (override != null) return override;

        if (mob instanceof Monster) return MobCategory.HOSTILE;
        if (mob instanceof NeutralMob) return MobCategory.NEUTRAL;
        if (mob instanceof Animal) return MobCategory.PASSIVE;
        return MobCategory.NEUTRAL;
    }

    private Color colorForCategory(MobCategory cat) {
        return switch (cat) {
            case PASSIVE -> passiveColor.getValue();
            case NEUTRAL -> neutralColor.getValue();
            case HOSTILE -> hostileColor.getValue();
        };
    }
}
