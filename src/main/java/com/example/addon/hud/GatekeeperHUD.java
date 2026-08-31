package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.modules.Gatekeeper;

import org.rusherhack.client.api.RusherHackAPI;

public class GatekeeperHUD extends TimHudElement {

    public GatekeeperHUD() {
        super("GatekeeperHUD", "Displays tracked End portal and End gateway counts.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        Object m = RusherHackAPI.getModuleManager().getFeature("gatekeeper").orElse(null);
        if (!(m instanceof Gatekeeper t) || !t.isToggled()) return lines;

        lines.add("§5End Portals: §f" + t.getTotalEndPortals());
        lines.add("§dGateways: §f" + t.getTotalGateways());
        return lines;
    }
}
