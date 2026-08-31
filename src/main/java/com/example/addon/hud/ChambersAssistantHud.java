package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.modules.ChambersAssistant;

import org.rusherhack.client.api.RusherHackAPI;

public class ChambersAssistantHud extends TimHudElement {

    public ChambersAssistantHud() {
        super("ChambersAssistantHUD", "Displays Trial Chamber element counts.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        Object m = RusherHackAPI.getModuleManager().getFeature("chambers-assistant").orElse(null);
        if (!(m instanceof ChambersAssistant module) || !module.isToggled()) return lines;

        for (ChambersAssistant.ChamberStat st : module.getStats()) {
            if (st.count() <= 0) continue;
            String col = switch (st.severity()) {
                case Critical -> "§c";
                case Warning -> "§6";
                default -> "§f";
            };
            lines.add("§7" + st.name() + ": " + col + st.count());
        }
        return lines;
    }
}
