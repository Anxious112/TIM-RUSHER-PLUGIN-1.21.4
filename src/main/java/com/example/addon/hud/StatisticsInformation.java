package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import org.rusherhack.client.api.RusherHackAPI;

import net.minecraft.core.BlockPos;

public class StatisticsInformation extends TimHudElement {

    public StatisticsInformation() {
        super("StatisticsInformation", "Shows FPS, ping, TPS, loaded chunks, position and facing.");
    }

    private static String facing(float yaw) {
        String[] dirs = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        int i = Math.round(yaw / 45f) & 7;
        return dirs[i];
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();

        lines.add("§7FPS: §f" + mc.getFps());

        try {
            float tps = RusherHackAPI.getServerState().getTPS();
            float ping = RusherHackAPI.getServerState().getPing();
            lines.add("§7TPS: §f" + String.format("%.1f", tps) + "  §7Ping: §f" + (int) ping + "ms");
        } catch (Throwable ignored) {}

        if (mc.getConnection() != null) {
            lines.add("§7Online: §f" + mc.getConnection().getOnlinePlayers().size());
        }

        if (mc.level != null) {
            lines.add("§7Chunks: §f" + mc.level.getChunkSource().getLoadedChunksCount());
        }

        if (mc.player != null) {
            BlockPos p = mc.player.blockPosition();
            lines.add("§7Pos: §f" + p.getX() + " " + p.getY() + " " + p.getZ() + "  §7Facing: §f" + facing(mc.player.getYRot()));
        }

        long total = Runtime.getRuntime().totalMemory();
        long used = total - Runtime.getRuntime().freeMemory();
        lines.add("§7Mem: §f" + (used / 1048576) + "/" + (total / 1048576) + " MB");
        return lines;
    }
}
