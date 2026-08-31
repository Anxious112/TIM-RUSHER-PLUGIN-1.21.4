package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.modules.Gatekeeper;

import org.rusherhack.client.api.RusherHackAPI;

public class EndAssistantHud extends TimHudElement {

    public EndAssistantHud() {
        super("EndAssistantHUD", "Displays End Assistant stats: elytras, chests and shulkers.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        Object m = RusherHackAPI.getModuleManager().getFeature("gatekeeper").orElse(null);
        if (!(m instanceof Gatekeeper t) || !t.isToggled()) return lines;

        lines.add("§dElytras Found: §f" + t.getTotalElytrasFound());
        int nearby = t.getElytrasNearby();
        if (nearby > 0) lines.add("§bElytras Nearby: §f" + nearby);
        int chests = t.getChestsNearby();
        if (chests > 0) lines.add("§eChests Nearby: §f" + chests);
        int shulkers = t.getShulkersNearby();
        if (shulkers > 0) lines.add("§6Shulkers: §f" + shulkers);
        return lines;
    }
}
