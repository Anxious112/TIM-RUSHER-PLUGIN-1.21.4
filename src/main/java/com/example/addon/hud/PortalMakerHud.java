package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.modules.PortalMaker;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.module.ToggleableModule;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

public class PortalMakerHud extends TimHudElement {

    public PortalMakerHud() {
        super("PortalMakerHUD", "Displays the progress of the Portal Maker module.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        Object m = RusherHackAPI.getModuleManager().getFeature("portal-maker").orElse(null);
        if (!(m instanceof PortalMaker module) || !module.isToggled()) return lines;
        if (module.portalFramePositions.isEmpty()) return lines;

        int total = module.portalFramePositions.size();
        int placed = 0;
        if (mc.level != null) {
            for (BlockPos pos : module.portalFramePositions) {
                if (mc.level.getBlockState(pos).is(Blocks.OBSIDIAN)) placed++;
            }
        }
        lines.add("§dPortal: §f" + placed + "/" + total);
        return lines;
    }
}
