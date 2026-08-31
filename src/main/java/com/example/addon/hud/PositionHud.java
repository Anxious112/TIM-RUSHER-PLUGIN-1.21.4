package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public class PositionHud extends TimHudElement {

    public PositionHud() {
        super("PositionHUD", "Shows coordinates, dimension and Nether/Overworld equivalents.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        if (mc.player == null || mc.level == null) return lines;

        BlockPos p = mc.player.blockPosition();
        lines.add("§7X: §f" + p.getX() + "  §7Y: §f" + p.getY() + "  §7Z: §f" + p.getZ());

        String dim;
        double factor = 0;
        if (mc.level.dimension() == Level.NETHER) { dim = "Nether"; factor = 8.0; }
        else if (mc.level.dimension() == Level.END) { dim = "End"; }
        else { dim = "Overworld"; factor = 1.0 / 8.0; }
        lines.add("§7Dim: §f" + dim);

        if (mc.level.dimension() == Level.NETHER) {
            lines.add("§7OW: §f" + (p.getX() * 8) + ", " + (p.getZ() * 8));
        } else if (mc.level.dimension() != Level.END) {
            lines.add("§7Nether: §f" + (int) Math.floor(p.getX() / 8.0) + ", " + (int) Math.floor(p.getZ() / 8.0));
        }

        mc.level.getBiome(p).unwrapKey().ifPresent(k -> lines.add("§7Biome: §f" + k.location().getPath()));
        return lines;
    }
}
