package com.example.addon.commands;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.command.Command;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.core.command.annotations.CommandExecutor;

public class RocketPilotCommand extends Command {

    public RocketPilotCommand() {
        super("rocket-pilot", "Toggles the Rocket Pilot module.");
        this.addAliases("rp");
    }

    @CommandExecutor
    private String execute() {
        Object m = RusherHackAPI.getModuleManager().getFeature("rocket-pilot").orElse(null);
        if (m instanceof ToggleableModule module) {
            module.toggle();
            return "Toggled Rocket Pilot (now " + (module.isToggled() ? "ON" : "OFF") + ").";
        }
        return "Rocket Pilot module not found.";
    }
}
