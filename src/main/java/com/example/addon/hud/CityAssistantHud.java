package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.modules.CityAssistant;

import org.rusherhack.client.api.RusherHackAPI;

public class CityAssistantHud extends TimHudElement {

    public CityAssistantHud() {
        super("CityAssistantHUD", "Displays Ancient City element counts and Warden timers.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        Object m = RusherHackAPI.getModuleManager().getFeature("city-assistant").orElse(null);
        if (!(m instanceof CityAssistant module) || !module.isToggled()) return lines;

        for (CityAssistant.CityStat st : module.getStats()) {
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
