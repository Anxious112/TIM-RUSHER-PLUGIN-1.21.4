package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.addon.modules.DungeonAssistant;

import org.rusherhack.client.api.RusherHackAPI;

public class DungeonAssistantHud extends TimHudElement {

    public DungeonAssistantHud() {
        super("DungeonAssistantHUD", "Displays dungeon element counts.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        Object m = RusherHackAPI.getModuleManager().getFeature("dungeon-assistant").orElse(null);
        if (!(m instanceof DungeonAssistant module) || !module.isToggled()) return lines;

        Map<DungeonAssistant.TargetType, Integer> counts = module.getTargetCounts();
        int spawners = counts.getOrDefault(DungeonAssistant.TargetType.SPAWNER, 0);
        int chests = counts.getOrDefault(DungeonAssistant.TargetType.CHEST, 0);
        int minecarts = counts.getOrDefault(DungeonAssistant.TargetType.CHEST_MINECART, 0);
        int blocks = counts.getOrDefault(DungeonAssistant.TargetType.CUSTOM_BLOCK, 0);

        if (spawners > 0) lines.add("§cSpawners: §f" + spawners);
        if (chests > 0) lines.add("§eChests: §f" + chests);
        if (minecarts > 0) lines.add("§6Minecarts: §f" + minecarts);
        if (blocks > 0) lines.add("§aBlocks: §f" + blocks);
        if (!lines.isEmpty()) lines.add("§b§lTotal: §f" + module.getTotalTargets());
        return lines;
    }
}
