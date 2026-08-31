package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;

public class DuraPanelHUD extends TimHudElement {

    public DuraPanelHUD() {
        super("DuraPanelHUD", "Shows main-hand and off-hand item durability.");
    }

    private static String row(String slot, ItemStack stack) {
        if (stack.isEmpty() || stack.getMaxDamage() <= 0) return null;
        int max = stack.getMaxDamage();
        int left = max - stack.getDamageValue();
        double pct = left * 100.0 / max;
        String col = pct > 50 ? "§a" : pct > 20 ? "§6" : "§c";
        return "§7" + slot + " " + stack.getHoverName().getString() + ": " + col + left + "§7/§f" + max
            + " " + col + String.format("(%.0f%%)", pct);
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        if (mc.player == null) return lines;
        String main = row("Main", mc.player.getMainHandItem());
        String off = row("Off", mc.player.getOffhandItem());
        if (main != null) lines.add(main);
        if (off != null) lines.add(off);
        return lines;
    }
}
