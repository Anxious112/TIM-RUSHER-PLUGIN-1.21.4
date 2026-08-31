package com.example.addon.commands;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.command.Command;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.core.command.annotations.CommandExecutor;

public class LootLensCommand extends Command {

    public LootLensCommand() {
        super("loot-lens", "Allows you to toggle the Loot Lens module.");
    }

    @CommandExecutor
    private String execute() {
        Object m = RusherHackAPI.getModuleManager().getFeature("loot-lens").orElse(null);
        if (m instanceof ToggleableModule module) {
            module.toggle();
            return "Toggled Loot Lens (now " + (module.isToggled() ? "ON" : "OFF") + ").";
        }
        return "Loot Lens module not found.";
    }
}
