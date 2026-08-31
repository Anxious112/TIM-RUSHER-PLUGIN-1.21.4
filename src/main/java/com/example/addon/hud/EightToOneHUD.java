package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.modules.EightToOne;

import org.rusherhack.client.api.RusherHackAPI;

public class EightToOneHUD extends TimHudElement {

    public EightToOneHUD() {
        super("EightToOneHUD", "Tracks Nether portal and Respawn Anchor counts.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        Object m = RusherHackAPI.getModuleManager().getFeature("eight-to-one").orElse(null);
        if (!(m instanceof EightToOne t) || !t.isToggled()) return lines;

        lines.add("§dPortals: §f" + t.getTotalPortals());
        lines.add("§bAnchors: §f" + t.getTotalAnchors());
        lines.add("§aCreated: §f" + t.getTotalCreated());
        return lines;
    }
}
