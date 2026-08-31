package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.modules.NeighbourhoodWatch;

import org.rusherhack.client.api.RusherHackAPI;

import net.minecraft.world.entity.player.Player;

/**
 * Simplified port: shows the closest player currently in render distance and
 * their NeighbourhoodWatch status. (The Meteor original also persisted a
 * "last seen X ago" timestamp fed by NeighbourhoodWatch's scan callbacks.)
 */
public class LastSeenPlayerHud extends TimHudElement {

    private String lastName = null;
    private long lastSeen = 0L;

    public LastSeenPlayerHud() {
        super("LastSeenPlayerHUD", "Shows the most recently seen nearby player.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        if (mc.player == null || mc.level == null) return lines;

        Player closest = null;
        double best = Double.MAX_VALUE;
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;
            double d = p.distanceToSqr(mc.player);
            if (d < best) { best = d; closest = p; }
        }

        if (closest != null) {
            lastName = closest.getGameProfile().getName();
            lastSeen = System.currentTimeMillis();
        }
        if (lastName == null) return lines;

        String status = "";
        Object m = RusherHackAPI.getModuleManager().getFeature("neighbourhood-watch").orElse(null);
        if (m instanceof NeighbourhoodWatch nw) {
            status = " §7[" + nw.getPlayerStatusPublic(lastName) + "]";
        }

        String age;
        if (closest != null) {
            age = " §7(now)";
        } else {
            long secs = (System.currentTimeMillis() - lastSeen) / 1000;
            age = " §7(" + secs + "s ago)";
        }
        lines.add("§bLast seen: §f" + lastName + status + age);
        return lines;
    }
}
