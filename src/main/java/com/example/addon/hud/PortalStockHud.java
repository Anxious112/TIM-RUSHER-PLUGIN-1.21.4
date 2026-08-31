package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.modules.PortalMaker;

import org.rusherhack.client.api.RusherHackAPI;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class PortalStockHud extends TimHudElement {

    public PortalStockHud() {
        super("PortalStockHUD", "Displays obsidian / ender chest stock and portal frame progress.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        if (mc.player == null) return lines;

        int obsidian = 0, enderChests = 0, flintSteel = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.is(Items.OBSIDIAN)) obsidian += s.getCount();
            if (s.is(Items.ENDER_CHEST)) enderChests += s.getCount();
            if (s.is(Items.FLINT_AND_STEEL)) flintSteel++;
        }
        String oCol = obsidian <= 10 ? "§c" : obsidian <= 20 ? "§6" : "§a";
        lines.add("§7Obsidian: " + oCol + obsidian);
        lines.add("§7Ender Chests: §f" + enderChests + "   §7F&S: §f" + flintSteel);

        Object m = RusherHackAPI.getModuleManager().getFeature("portal-maker").orElse(null);
        if (m instanceof PortalMaker pm && pm.isToggled() && !pm.portalFramePositions.isEmpty() && mc.level != null) {
            int total = pm.portalFramePositions.size();
            int placed = 0;
            for (BlockPos pos : pm.portalFramePositions) {
                if (mc.level.getBlockState(pos).is(Blocks.OBSIDIAN)) placed++;
            }
            lines.add("§dPortal: §f" + placed + "/" + total);
        }
        return lines;
    }
}
