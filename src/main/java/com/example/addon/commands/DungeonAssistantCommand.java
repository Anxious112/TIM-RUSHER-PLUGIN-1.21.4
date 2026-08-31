package com.example.addon.commands;

import com.example.addon.modules.DungeonAssistant;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.command.Command;
import org.rusherhack.core.command.annotations.CommandExecutor;

import java.util.Map;

public class DungeonAssistantCommand extends Command {

    public DungeonAssistantCommand() {
        super("dungeon-assistant", "Displays dungeon element statistics.");
        this.addAliases("da");
    }

    @CommandExecutor
    private String execute() {
        Object m = RusherHackAPI.getModuleManager().getFeature("dungeon-assistant").orElse(null);
        if (!(m instanceof DungeonAssistant module)) {
            return "DungeonAssistant module not found.";
        }
        if (!module.isToggled()) {
            return "DungeonAssistant is not active. Enable it first.";
        }

        Map<DungeonAssistant.TargetType, Integer> counts = module.getTargetCounts();
        int total = module.getTotalTargets();

        StringBuilder sb = new StringBuilder();
        sb.append("§6=== Dungeon Assistant Stats ===\n");
        sb.append("§cSpawners: §f").append(counts.getOrDefault(DungeonAssistant.TargetType.SPAWNER, 0)).append('\n');
        sb.append("§eChests: §f").append(counts.getOrDefault(DungeonAssistant.TargetType.CHEST, 0)).append('\n');
        sb.append("§6Chest Minecarts: §f").append(counts.getOrDefault(DungeonAssistant.TargetType.CHEST_MINECART, 0)).append('\n');
        sb.append("§aFiltered Blocks: §f").append(counts.getOrDefault(DungeonAssistant.TargetType.CUSTOM_BLOCK, 0)).append('\n');
        sb.append("§b§lTotal: §f").append(total);
        return sb.toString();
    }
}
