package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;

public class ServerReportHUD extends TimHudElement {

    public ServerReportHUD() {
        super("ServerReportHUD", "Shows biome, weather and active status effects.");
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }

    private static String time(int ticks) {
        int s = ticks / 20;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        if (mc.player == null || mc.level == null) return lines;

        BlockPos p = mc.player.blockPosition();
        mc.level.getBiome(p).unwrapKey().ifPresent(k -> lines.add("§7Biome: §f" + k.location().getPath()));

        String weather = mc.level.isThundering() ? "§4Thunderstorm" : mc.level.isRaining() ? "§9Rain" : "§eClear";
        lines.add("§7Weather: " + weather);

        for (MobEffectInstance e : mc.player.getActiveEffects()) {
            String name = Component.translatable(e.getEffect().value().getDescriptionId()).getString();
            String col = e.getEffect().value().isBeneficial() ? "§a" : "§c";
            lines.add(col + name + " " + roman(e.getAmplifier() + 1) + " §7" + time(e.getDuration()));
        }
        return lines;
    }
}
