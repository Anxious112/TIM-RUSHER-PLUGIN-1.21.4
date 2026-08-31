package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.Tim;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.module.IModule;
import org.rusherhack.client.api.feature.module.ToggleableModule;

public class InfoAssistantHud extends TimHudElement {

    public InfoAssistantHud() {
        super("InfoAssistantHUD", "Lists the currently enabled Tim modules.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        List<String> active = new ArrayList<>();
        for (IModule module : RusherHackAPI.getModuleManager().getFeatures()) {
            if (module.getCategory() != Tim.CATEGORY) continue;
            if (module instanceof ToggleableModule tm && tm.isToggled()) {
                active.add(tm.getName());
            }
        }
        if (active.isEmpty()) return lines;
        active.sort(String::compareToIgnoreCase);
        lines.add("§b§lTim §7— active (" + active.size() + ")");
        for (String name : active) lines.add("§a• §f" + name);
        return lines;
    }
}
