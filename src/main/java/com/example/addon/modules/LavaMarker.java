package com.example.addon.modules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.addon.Tim;
import com.example.addon.utils.RenderUtils;

import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

import java.awt.Color;

/**
 * LavaMarker — highlights fully-flowed lava falls in the Nether.
 *
 * Note: the original block-update reactivity (instant rescan on lava
 * placement/removal via a client packet-listener mixin) has no RusherHack
 * event equivalent, so this relies solely on the periodic chunk rescan loop
 * below -- lava changes are picked up within a few ticks instead of instantly.
 */
public class LavaMarker extends ToggleableModule {

    public enum RenderMode { GLOW, SPECTRAL, PULSE }

    private final NumberSetting<Integer> chunkRadius = new NumberSetting<>("chunk-radius", "Horizontal scan radius in chunks.", 4, 1, 128);
    private final NumberSetting<Integer> verticalRadius = new NumberSetting<>("vertical-radius", "Vertical scan radius in blocks.", 64, 0, 128);
    private final ColorSetting color = new ColorSetting("flowing-lava", "Color for fully-flowed lava falls. (Alpha is used for GLOW and SPECTRAL outlines).", new Color(255, 100, 0, 200))
        .setAlphaAllowed(true);
    private final NumberSetting<Integer> minFallHeight = new NumberSetting<>("min-fall-height", "Lava falls shorter than this will be ignored.", 5, 0, 32);
    private final NumberSetting<Integer> maxRenderBlocks = new NumberSetting<>("max-render-blocks", "Maximum number of blocks to render per frame to prevent crashes.", 5000, 100, 20000);

    private final EnumSetting<RenderMode> renderMode = new EnumSetting<>("render-mode", "GLOW = layered bloom boxes. SPECTRAL = subtle fill box. PULSE = fading in/out highlight.", RenderMode.GLOW);

    private final NumberSetting<Integer> glowLayers = new NumberSetting<>("glow-layers", "Number of bloom layers rendered around each lava block.", 3, 1, 6)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW || renderMode.getValue() == RenderMode.PULSE);

    private final NumberSetting<Double> glowSpread = new NumberSetting<>("glow-spread", "How far each bloom layer expands outward (in blocks).", 0.04, 0.01, 0.15)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW || renderMode.getValue() == RenderMode.PULSE);

    private final NumberSetting<Integer> glowBaseAlpha = new NumberSetting<>("glow-base-alpha", "Opacity of the outer glow layers in GLOW mode (0-255).", 40, 4, 120)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW);

    private final NumberSetting<Integer> spectralFillAlpha = new NumberSetting<>("spectral-fill-alpha", "Opacity of the fill box in SPECTRAL mode (0 = invisible, 80 = subtle).", 40, 0, 200)
        .setVisibility(() -> renderMode.getValue() == RenderMode.SPECTRAL);

    private final org.rusherhack.core.setting.BooleanSetting spectralOutline = new org.rusherhack.core.setting.BooleanSetting("spectral-outline", "Draw a solid outline around lava blocks in SPECTRAL mode.", true)
        .setVisibility(() -> renderMode.getValue() == RenderMode.SPECTRAL);

    private final NumberSetting<Double> pulseSpeed = new NumberSetting<>("pulse-speed", "Pulse cycle speed. 1.0 = one full fade in/out per second.", 1.0, 0.1, 5.0)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);

    private final NumberSetting<Integer> pulseMinAlpha = new NumberSetting<>("pulse-min-alpha", "Lowest opacity reached during the pulse (0 = invisible).", 15, 0, 255)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);

    private final NumberSetting<Integer> pulseMaxAlpha = new NumberSetting<>("pulse-max-alpha", "Peak opacity reached during the pulse.", 220, 15, 255)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);

    private final Map<ChunkPos, Set<BlockPos>> fallsByChunk = new ConcurrentHashMap<>();
    private final Set<ChunkPos>                scannedChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos>                dirtyChunks   = ConcurrentHashMap.newKeySet();

    private String lastDimension = "";

    public LavaMarker() {
        super("lava-marker", "Highlights fully-flowed lava falls in the Nether.", Tim.CATEGORY);
        this.registerSettings(
            chunkRadius, verticalRadius, color, minFallHeight, maxRenderBlocks,
            renderMode, glowLayers, glowSpread, glowBaseAlpha,
            spectralFillAlpha, spectralOutline,
            pulseSpeed, pulseMinAlpha, pulseMaxAlpha
        );
    }

    @Override
    public void onEnable() {
        clearData();
        if (mc.level != null) lastDimension = mc.level.dimension().location().toString();
    }

    @Override
    public void onDisable() {
        clearData();
    }

    private void clearData() {
        fallsByChunk.clear();
        scannedChunks.clear();
        dirtyChunks.clear();
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        String dim = mc.level.dimension().location().toString();
        if (!dim.equals("minecraft:the_nether")) {
            if (!fallsByChunk.isEmpty()) clearData();
            return;
        }
        if (!dim.equals(lastDimension)) {
            lastDimension = dim;
            clearData();
        }

        BlockPos playerPos = mc.player.blockPosition();
        int radius = chunkRadius.getValue();
        int pX = playerPos.getX() >> 4;
        int pZ = playerPos.getZ() >> 4;

        scannedChunks.removeIf(cp -> isOutOfRange(cp, pX, pZ, radius));
        fallsByChunk.keySet().removeIf(cp -> isOutOfRange(cp, pX, pZ, radius));
        dirtyChunks.removeIf(cp -> isOutOfRange(cp, pX, pZ, radius));

        List<ChunkPos> todo = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                ChunkPos cp = new ChunkPos(pX + x, pZ + z);
                if (!scannedChunks.contains(cp) && mc.level.hasChunk(cp.x, cp.z)) {
                    todo.add(cp);
                }
            }
        }
        todo.sort(Comparator.comparingDouble(cp -> {
            double dx = cp.x - pX, dz = cp.z - pZ;
            return dx * dx + dz * dz;
        }));

        int processed = 0;
        while (!dirtyChunks.isEmpty() && processed < 4) {
            ChunkPos cp = dirtyChunks.iterator().next();
            dirtyChunks.remove(cp);
            scannedChunks.remove(cp);
            if (mc.level.hasChunk(cp.x, cp.z)) {
                scanChunk(mc.level.getChunk(cp.x, cp.z));
                scannedChunks.add(cp);
                processed++;
            }
        }
        for (ChunkPos cp : todo) {
            if (processed >= 4) break;
            scanChunk(mc.level.getChunk(cp.x, cp.z));
            scannedChunks.add(cp);
            processed++;
        }
    }

    private boolean isOutOfRange(ChunkPos cp, int pX, int pZ, int radius) {
        return Math.abs(cp.x - pX) > radius || Math.abs(cp.z - pZ) > radius;
    }

    private void scanChunk(LevelChunk chunk) {
        if (chunk == null || mc.player == null || mc.level == null) return;

        ChunkPos cp = chunk.getPos();
        int vRadius = verticalRadius.getValue();
        int playerY = (int) mc.player.getY();
        int minY = Math.max(mc.level.getMinY(), playerY - vRadius);
        int maxY = Math.min(mc.level.getMinY() + mc.level.getHeight(), playerY + vRadius);

        Set<BlockPos> fallTips = new HashSet<>();
        LevelChunkSection[] sections = chunk.getSections();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;

            int sectionY    = chunk.getMinSectionY() + i;
            int sectionMinY = sectionY << 4;
            int sectionMaxY = sectionMinY + 15;
            if (sectionMaxY < minY || sectionMinY > maxY) continue;
            if (!section.maybeHas(s -> s.getFluidState().is(FluidTags.LAVA))) continue;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        int worldY = sectionMinY + y;
                        if (worldY < minY || worldY > maxY) continue;

                        FluidState fs = section.getBlockState(x, y, z).getFluidState();
                        if (!fs.is(FluidTags.LAVA)) continue;

                        boolean falling = fs.hasProperty(BlockStateProperties.FALLING) && fs.getValue(BlockStateProperties.FALLING);
                        if (!falling) continue;

                        BlockPos pos = new BlockPos(cp.getMinBlockX() + x, worldY, cp.getMinBlockZ() + z);
                        if (!isFalling(pos.below())) fallTips.add(pos);
                    }
                }
            }
        }

        Set<BlockPos> allValidFallBlocks = new HashSet<>();
        Set<BlockPos> visitedInScan      = new HashSet<>();
        for (BlockPos tip : fallTips) {
            if (visitedInScan.contains(tip)) continue;

            Set<BlockPos> currentFall = new HashSet<>();
            bfs(tip, currentFall, visitedInScan);
            if (currentFall.isEmpty()) continue;

            int fallMinY = Integer.MAX_VALUE;
            int fallMaxY = Integer.MIN_VALUE;
            for (BlockPos pos : currentFall) {
                fallMinY = Math.min(fallMinY, pos.getY());
                fallMaxY = Math.max(fallMaxY, pos.getY());
            }
            if (fallMaxY - fallMinY + 1 < minFallHeight.getValue()) continue;

            for (BlockPos pos : currentFall) {
                FluidState fs = mc.level.getFluidState(pos);
                if (fs.is(FluidTags.LAVA) && !fs.isSource()) allValidFallBlocks.add(pos);
            }
        }

        if (!allValidFallBlocks.isEmpty()) fallsByChunk.put(cp, allValidFallBlocks);
        else fallsByChunk.remove(cp);
    }

    private boolean isFalling(BlockPos pos) {
        FluidState fs = mc.level.getFluidState(pos);
        return fs.is(FluidTags.LAVA) && fs.hasProperty(BlockStateProperties.FALLING) && fs.getValue(BlockStateProperties.FALLING);
    }

    private void bfs(BlockPos start, Set<BlockPos> result, Set<BlockPos> visited) {
        if (visited.contains(start)) return;

        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            result.add(cur);

            for (BlockPos nb : new BlockPos[]{
                cur.north(), cur.south(), cur.east(), cur.west(), cur.below()
            }) {
                if (!visited.contains(nb)
                        && mc.level.hasChunk(nb.getX() >> 4, nb.getZ() >> 4)) {
                    FluidState ns = mc.level.getFluidState(nb);
                    if (ns.is(FluidTags.LAVA) && !ns.isSource()) {
                        visited.add(nb);
                        queue.add(nb);
                    }
                }
            }

            BlockPos up = cur.above();
            if (!visited.contains(up)
                    && mc.level.hasChunk(up.getX() >> 4, up.getZ() >> 4)) {
                if (isFalling(up)) {
                    visited.add(up);
                    queue.add(up);
                }
            }
        }
    }

    @Subscribe
    private void onRender(EventRender3D event) {
        if (mc.level == null) return;

        IRenderer3D renderer = event.getRenderer();
        renderer.begin(event.getMatrixStack());

        boolean isSpectral = renderMode.getValue() == RenderMode.SPECTRAL;
        boolean isPulse    = renderMode.getValue() == RenderMode.PULSE;
        int count = 0;
        int max   = maxRenderBlocks.getValue();

        outer:
        for (Set<BlockPos> set : fallsByChunk.values()) {
            for (BlockPos pos : set) {
                if (count >= max) break outer;

                FluidState fs = mc.level.getFluidState(pos);
                if (!fs.is(FluidTags.LAVA)) continue;
                if (fs.isSource()) continue;

                boolean isBottomBlock = !mc.level.getFluidState(pos.below()).is(FluidTags.LAVA);
                if (isBottomBlock && mc.level.getBlockState(pos.below()).isAir()) continue;

                if (isSpectral) {
                    int fillAlpha = spectralFillAlpha.getValue();
                    boolean outline = spectralOutline.getValue();
                    renderer.drawBox(pos, true, outline, RenderUtils.withAlpha(color.getValue(), fillAlpha));
                } else if (isPulse) {
                    renderPulseBox(renderer, pos, color.getValue());
                } else {
                    renderGlowLayers(renderer, pos, color.getValue());
                    renderer.drawBox(pos, true, true, color.getValue().getRGB());
                }

                count++;
            }
        }

        renderer.end();
    }

    private void renderGlowLayers(IRenderer3D renderer, BlockPos pos, Color color) {
        int    layers    = glowLayers.getValue();
        double spread    = glowSpread.getValue();
        int    baseAlpha = glowBaseAlpha.getValue();

        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            int    layerAlpha = Math.max(4, (int) (baseAlpha * (1.0 - (double)(i - 1) / layers)));
            renderer.drawBox(
                pos.getX() - expansion, pos.getY() - expansion, pos.getZ() - expansion,
                1 + expansion * 2, 1 + expansion * 2, 1 + expansion * 2,
                true, false, RenderUtils.withAlpha(color, layerAlpha)
            );
        }
    }

    private float getPulseFactor() {
        double speed = pulseSpeed.getValue();
        double t = System.currentTimeMillis() / 1000.0;
        double phase = t * speed * Math.PI * 2.0;
        return (float)((Math.sin(phase) + 1.0) * 0.5);
    }

    private int applyPulse() {
        float f = getPulseFactor();
        int min = pulseMinAlpha.getValue();
        int max = pulseMaxAlpha.getValue();
        return Math.min(255, Math.max(0, (int)(min + (max - min) * f)));
    }

    private void renderPulseBox(IRenderer3D renderer, BlockPos pos, Color base) {
        int pa = applyPulse();
        int layers = glowLayers.getValue();
        double spread = glowSpread.getValue();
        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double taper = 1.0 - ((double)(i - 1) / layers) * 0.6;
            int layerAlpha = Math.max(4, (int)(pa * taper));
            renderer.drawBox(
                pos.getX() - expansion, pos.getY() - expansion, pos.getZ() - expansion,
                1 + expansion * 2, 1 + expansion * 2, 1 + expansion * 2,
                true, false, RenderUtils.withAlpha(base, layerAlpha)
            );
        }
        renderer.drawBox(pos, true, true, RenderUtils.withAlpha(base, pa));
    }
}
