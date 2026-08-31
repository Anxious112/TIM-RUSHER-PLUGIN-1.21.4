package com.example.addon.hud;

import java.awt.Color;
import java.util.List;

import org.rusherhack.client.api.feature.hud.HudElement;
import org.rusherhack.client.api.render.IRenderer2D;
import org.rusherhack.client.api.render.RenderContext;
import org.rusherhack.client.api.render.font.IFontRenderer;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;

/**
 * Shared base for the ported "Tim" HUD panels. The Meteor originals do heavy
 * custom quad/bar drawing; the RusherHack port renders the same information as a
 * stack of text lines (Minecraft "§" colour codes are honoured by the font
 * renderer) with an optional background box.
 */
public abstract class TimHudElement extends HudElement {

    protected final ColorSetting textColor = new ColorSetting("text-color", "Default text colour.", new Color(255, 255, 255));
    protected final BooleanSetting shadow = new BooleanSetting("shadow", "Draw a text shadow.", true);
    protected final NumberSetting<Double> lineSpacing = new NumberSetting<>("line-spacing", "Extra pixels between lines.", 1.0, 0.0, 6.0);
    protected final BooleanSetting background = new BooleanSetting("background", "Draw a background box behind the panel.", true);
    protected final ColorSetting backgroundColor = new ColorSetting("background-color", "Background box colour.", new Color(0, 0, 0, 140))
        .setVisibility(background::getValue);

    private double width = 10;
    private double height = 10;

    public TimHudElement(String name, String description) {
        super(name, description);
        this.registerSettings(textColor, shadow, lineSpacing, background, backgroundColor);
    }

    /** Return the lines to display, top to bottom. An empty list hides the panel. */
    protected abstract List<String> getLines();

    @Override
    public void renderContent(RenderContext context, double x, double y) {
        final List<String> lines = getLines();
        final IFontRenderer font = getFontRenderer();
        final double pad = 2.0;
        final double lineH = font.getFontHeight() + lineSpacing.getValue();

        if (lines == null || lines.isEmpty()) {
            this.width = 0;
            this.height = 0;
            return;
        }

        double maxW = 0;
        for (String line : lines) maxW = Math.max(maxW, font.getStringWidth(line));

        this.width = maxW + pad * 2;
        this.height = lines.size() * lineH + pad * 2;

        if (background.getValue()) {
            IRenderer2D r = getRenderer();
            r.begin(context.pose());
            r.drawRectangle(x, y, this.width, this.height, backgroundColor.getValueRGB());
            r.end();
        }

        double ty = y + pad;
        for (String line : lines) {
            font.drawString(line, x + pad, ty, textColor.getValueRGB(), shadow.getValue());
            ty += lineH;
        }
    }

    @Override
    public double getWidth() {
        return width;
    }

    @Override
    public double getHeight() {
        return height;
    }
}
