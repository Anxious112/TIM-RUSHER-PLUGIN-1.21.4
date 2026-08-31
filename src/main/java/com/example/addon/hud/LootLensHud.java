package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.modules.LootLens;

import org.rusherhack.client.api.RusherHackAPI;

public class LootLensHud extends TimHudElement {

    public LootLensHud() {
        super("LootLensHUD", "Displays tracked container counts from Loot Lens.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        Object m = RusherHackAPI.getModuleManager().getFeature("loot-lens").orElse(null);
        if (!(m instanceof LootLens module) || !module.isToggled()) return lines;

        int dbl = module.getDoubleChestCount();
        int shulk = module.getShulkerBoxCount();
        int ender = module.getEnderChestCount();
        if (dbl > 0) lines.add("§eDouble Chests: §f" + dbl);
        if (shulk > 0) lines.add("§6Shulkers: §f" + shulk);
        if (ender > 0) lines.add("§dEnder Chests: §f" + ender);
        if (!lines.isEmpty()) lines.add("§b§lContainers: §f" + module.getTotalContainers());
        return lines;
    }
}
