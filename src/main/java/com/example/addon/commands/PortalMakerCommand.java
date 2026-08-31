package com.example.addon.commands;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.command.Command;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.core.command.annotations.CommandExecutor;

public class PortalMakerCommand extends Command {

    public PortalMakerCommand() {
        super("portal-maker", "Toggles the Portal Maker module.");
        this.addAliases("pm");
    }

    @CommandExecutor
    private String execute() {
        Object m = RusherHackAPI.getModuleManager().getFeature("portal-maker").orElse(null);
        if (m instanceof ToggleableModule module) {
            module.toggle();
            return "Toggled Portal Maker (now " + (module.isToggled() ? "ON" : "OFF") + ").";
        }
        return "Portal Maker module not found.";
    }
}
