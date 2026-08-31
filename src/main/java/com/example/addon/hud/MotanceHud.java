package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

public class MotanceHud extends TimHudElement {

    public MotanceHud() {
        super("MotanceHUD", "Shows movement state: sneaking, jumping, and sprinting.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        if (mc.player == null) return lines;

        boolean sprinting = mc.player.isSprinting();
        boolean sneaking = mc.player.isShiftKeyDown();
        boolean jumping = !mc.player.onGround() && mc.player.getDeltaMovement().y > 0.0;

        if (!sprinting && !sneaking && !jumping) return lines;

        StringBuilder sb = new StringBuilder("§7Motion: ");
        if (sneaking) sb.append("§bSNEAK ");
        if (jumping) sb.append("§aJUMP ");
        if (sprinting) sb.append("§eSPRINT");
        lines.add(sb.toString().trim());
        return lines;
    }
}
