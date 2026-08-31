package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.modules.NeighbourhoodWatch;

import org.rusherhack.client.api.RusherHackAPI;

import net.minecraft.world.entity.player.Player;

public class NeighbourhoodWatchHUD extends TimHudElement {

    public NeighbourhoodWatchHUD() {
        super("NeighbourhoodWatchHUD", "Lists nearby players with their tracked status.");
    }

    private static String colorFor(NeighbourhoodWatch.PlayerStatus s) {
        return switch (s) {
            case Friend -> "§a";
            case Enemy -> "§c";
            case Proxy -> "§d";
            default -> "§f";
        };
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        Object m = RusherHackAPI.getModuleManager().getFeature("neighbourhood-watch").orElse(null);
        if (!(m instanceof NeighbourhoodWatch nw) || !nw.isToggled() || mc.player == null || mc.level == null) return lines;

        List<Player> others = new ArrayList<>();
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;
            others.add(p);
        }
        if (others.isEmpty()) return lines;

        others.sort((a, b) -> Double.compare(a.distanceToSqr(mc.player), b.distanceToSqr(mc.player)));
        lines.add("§7Nearby players: §f" + others.size() + (nw.isDisconnectOnPlayerArmed() ? "  §c[DC ARMED]" : ""));
        int shown = 0;
        for (Player p : others) {
            if (shown++ >= 8) break;
            String name = p.getGameProfile().getName();
            NeighbourhoodWatch.PlayerStatus st = nw.getPlayerStatusPublic(name);
            int dist = (int) Math.sqrt(p.distanceToSqr(mc.player));
            lines.add(colorFor(st) + name + " §7(" + dist + "m)");
        }
        return lines;
    }
}
