package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.modules.Timethrottle;

import org.rusherhack.client.api.RusherHackAPI;

public class TimeThrottleHUD extends TimHudElement {

    public TimeThrottleHUD() {
        super("TimeThrottleHUD", "Displays the system speed impact of the Timethrottle module.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        Object m = RusherHackAPI.getModuleManager().getFeature("time-throttle").orElse(null);
        if (!(m instanceof Timethrottle module) || !module.isToggled()) return lines;

        double mult = module.getCurrentSpeed();

        String sourceName = null;
        double minVal = 0.99;
        for (int i = 0; i < module.sourceCount(); i++) {
            double val = module.evaluateSource(i);
            if (val < minVal) {
                minVal = val;
                sourceName = module.sourceName(i);
            }
        }

        String speedColor = mult > 0.8 ? "§a" : mult > 0.4 ? "§6" : "§c";
        lines.add("§7Speed: " + speedColor + String.format("%.0f%%", mult * 100));
        lines.add("§7Source: " + speedColor + (sourceName != null ? sourceName : "None"));
        return lines;
    }
}
