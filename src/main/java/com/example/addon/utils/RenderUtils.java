package com.example.addon.utils;

import java.awt.Color;

/**
 * RusherHack's IRenderer3D.drawBox takes a single packed color for fill+outline
 * (unlike Meteor's separate fillColor/lineColor), and takes origin+size rather
 * than min/max corners. These helpers bridge that gap for ported modules.
 */
public final class RenderUtils {
    private RenderUtils() {}

    public static int withAlpha(Color color, int alpha) {
        int a = Math.min(255, Math.max(0, alpha));
        return (a << 24) | (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
    }

    public static int withAlpha(int argb, int alpha) {
        int a = Math.min(255, Math.max(0, alpha));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
