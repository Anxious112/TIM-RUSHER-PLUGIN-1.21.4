package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.modules.Baromine;

import org.rusherhack.client.api.RusherHackAPI;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.ShulkerBoxBlock;

public class BaromineHud extends TimHudElement {

    public BaromineHud() {
        super("BaromineHUD", "Displays Baromine status, progress, tool durability and session runtime.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        Object m = RusherHackAPI.getModuleManager().getFeature("baromine").orElse(null);
        if (!(m instanceof Baromine b) || !b.isToggled()) return lines;

        lines.add("§bStatus: §f" + b.getCurrentStatus());

        int mined = b.getCurrentTargetCount();
        int target = b.targetStacks.getValue() * 64;
        double pct = target > 0 ? (mined * 100.0 / target) : 0;
        String col = pct >= 100 ? "§a" : pct >= 50 ? "§e" : "§f";
        lines.add("§7Mined: " + col + mined + "§7/§f" + target);

        double dur = b.getMainHandDurabilityPercent();
        String dcol = dur > 50 ? "§a" : dur > 20 ? "§6" : "§c";
        lines.add("§7Tool: " + dcol + String.format("%.0f%%", dur));

        long elapsed = System.currentTimeMillis() - b.getSessionStartTime();
        long secs = elapsed / 1000;
        lines.add(String.format("§7Runtime: §f%02dh %02dm %02ds", secs / 3600, (secs % 3600) / 60, secs % 60));

        if (mc.player != null) {
            int echests = 0, shulkers = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack s = mc.player.getInventory().getItem(i);
                if (s.is(Items.ENDER_CHEST)) echests += s.getCount();
                if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) shulkers += s.getCount();
            }
            if (echests > 0) lines.add("§7Ender Chests: §f" + echests);
            if (shulkers > 0) lines.add("§7Shulkers: §f" + shulkers);
        }
        return lines;
    }
}
