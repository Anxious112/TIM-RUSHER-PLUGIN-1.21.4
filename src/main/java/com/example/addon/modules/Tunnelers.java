package com.example.addon.modules;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.addon.Tim;
import com.example.addon.utils.RenderUtils;

import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public class Tunnelers extends ToggleableModule {

    private static final int[][] ALL_DIRS_6 = {
        { 1, 0, 0}, {-1, 0, 0},
        { 0, 1, 0}, { 0,-1, 0},
        { 0, 0, 1}, { 0, 0,-1}
    };

    private static final int  MAX_QUEUE_PER_TICK    = 32;
    private static final int  MAX_BATCHES_PER_FLUSH = 4;
    private static final int  MAX_IN_FLIGHT         = 6;
    private static final int  DRAIN_PER_TICK        = 4;
    private static final long TIME_BUDGET_NS        = 500_000L;

    public enum HighlightStyle {
        GLOW("Glow"), SPECTRAL("Spectral"), PULSE("Pulse");
        private final String displayName;
        HighlightStyle(String name) { this.displayName = name; }
        @Override public String toString() { return displayName; }
    }

    public enum TunnelType { TUNNEL_1x1, OTHER_TUNNEL, HOLE, LADDER_SHAFT }
    public enum ShaftMode { Holes, LadderShafts, Both }
    public enum BoxMode { Both, Sides, Lines }

    // ── Settings — General ──
    private final NumberSetting<Integer> range = new NumberSetting<>("range", "Scan range in chunks.", 8, 1, 32);
    private final NumberSetting<Integer> scanDelay = new NumberSetting<>("scan-delay", "Ticks between out-of-range pruning passes.", 40, 10, 200);
    private final EnumSetting<BoxMode> shapeMode = new EnumSetting<>("shape-mode", "Box render mode.", BoxMode.Both);
    private final EnumSetting<HighlightStyle> highlightStyle = new EnumSetting<>("highlight-style", "GLOW renders layered bloom. SPECTRAL renders a crisp outline. PULSE renders a fading bloom effect.", HighlightStyle.GLOW);
    private final BooleanSetting fadeWithDistance = new BooleanSetting("fade-with-distance", "Reduces opacity of highlights that are further away.", true);
    private final NumberSetting<Integer> maxRenderBoxes = new NumberSetting<>("max-render-boxes", "Maximum merged boxes rendered per frame.", 2000, 100, 8000);

    private final NumberSetting<Integer> glowLayers = new NumberSetting<>("glow-layers", "Number of bloom layers rendered around each box.", 4, 1, 8)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.GLOW || highlightStyle.getValue() == HighlightStyle.PULSE);
    private final NumberSetting<Double> glowSpread = new NumberSetting<>("glow-spread", "How far each bloom layer expands outward (in blocks).", 0.05, 0.01, 0.2)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.GLOW || highlightStyle.getValue() == HighlightStyle.PULSE);
    private final NumberSetting<Integer> glowBaseAlpha = new NumberSetting<>("glow-base-alpha", "Alpha of the innermost glow layer (0-255).", 60, 4, 150)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.GLOW);

    private final NumberSetting<Double> pulseSpeed = new NumberSetting<>("pulse-speed", "Pulse cycle speed. 1.0 = one full fade in/out per second.", 1.0, 0.1, 5.0)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.PULSE);
    private final NumberSetting<Integer> pulseMinAlpha = new NumberSetting<>("pulse-min-alpha", "Lowest alpha reached during the pulse (0 = invisible).", 15, 0, 255)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.PULSE);
    private final NumberSetting<Integer> pulseMaxAlpha = new NumberSetting<>("pulse-max-alpha", "Peak alpha reached during the pulse.", 220, 15, 255)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.PULSE);

    // ── Settings — Spectral ──
    private final NumberSetting<Double> spectralExpand = new NumberSetting<>("expand", "How much to expand the outline box beyond each tunnel box surface (in blocks).", 0.05, 0.0, 0.3)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.SPECTRAL);
    private final NumberSetting<Integer> spectralLineAlpha = new NumberSetting<>("line-alpha", "Opacity of the spectral outline (0-255).", 255, 30, 255)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.SPECTRAL);
    private final NumberSetting<Integer> spectralFillAlpha = new NumberSetting<>("fill-alpha", "Alpha of a faint tinted fill drawn inside the outline (0 = lines only).", 15, 0, 80)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.SPECTRAL);
    private final BooleanSetting spectralPulse = new BooleanSetting("pulse", "Pulsate the spectral outline alpha over time.", true)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.SPECTRAL);

    // ── Settings — Tunnels ──
    private final BooleanSetting find1x1 = new BooleanSetting("1x1", "Detect 1x1 tunnels.", true);
    private final NumberSetting<Integer> min1x1Length = new NumberSetting<>("min-1x1-length", "Minimum length of a 1x1 tunnel to be rendered.", 8, 1, 64)
        .setVisibility(find1x1::getValue);
    private final ColorSetting color1x1 = new ColorSetting("color-1x1", "Color for 1x1 tunnels.", new Color(255, 255, 0, 75)).setVisibility(find1x1::getValue);

    private final BooleanSetting findOtherTunnels = new BooleanSetting("other-tunnels", "Detect 1x2, 2x2, 3x3, 4x4, and 5x5 tunnels.", true);
    private final NumberSetting<Integer> minOtherLength = new NumberSetting<>("min-other-length", "Minimum length for other tunnels to be rendered.", 2, 1, 64)
        .setVisibility(findOtherTunnels::getValue);
    private final ColorSetting colorOtherTunnels = new ColorSetting("color-other", "Color for 1x2, 2x2, 3x3, 4x4, and 5x5 tunnels.", new Color(255, 200, 0, 75)).setVisibility(findOtherTunnels::getValue);

    // ── Settings — Shafts ──
    private final EnumSetting<ShaftMode> shaftMode = new EnumSetting<>("shaft-mode", "Which vertical shaft types to detect: Holes, LadderShafts, or Both.", ShaftMode.Both);
    private final NumberSetting<Integer> minHoleHeight = new NumberSetting<>("min-hole-height", "Minimum shaft depth to be detected as a hole.", 4, 2, 20)
        .setVisibility(() -> shaftMode.getValue() == ShaftMode.Holes || shaftMode.getValue() == ShaftMode.Both);
    private final ColorSetting colorHoles = new ColorSetting("color-holes", "Color for holes.", new Color(0, 255, 255, 75))
        .setVisibility(() -> shaftMode.getValue() == ShaftMode.Holes || shaftMode.getValue() == ShaftMode.Both);
    private final NumberSetting<Integer> minLadderHeight = new NumberSetting<>("min-ladder-height", "Minimum consecutive ladder blocks to count as a shaft.", 4, 2, 20)
        .setVisibility(() -> shaftMode.getValue() == ShaftMode.LadderShafts || shaftMode.getValue() == ShaftMode.Both);
    private final ColorSetting colorLadderShafts = new ColorSetting("color-ladder-shafts", "Color for ladder shafts.", new Color(0, 255, 0, 75))
        .setVisibility(() -> shaftMode.getValue() == ShaftMode.LadderShafts || shaftMode.getValue() == ShaftMode.Both);

    // ── State & Threading ──
    private final ConcurrentHashMap<BlockPos, TunnelType> locations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Set<BlockPos>> chunkIndex = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ScanResult> pendingResults = new ConcurrentLinkedQueue<>();

    private volatile List<MergedBox> renderSnapshot = Collections.emptyList();
    private final AtomicBoolean mergeScheduled = new AtomicBoolean(false);
    private volatile int snapPX, snapPY, snapPZ;

    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final LinkedHashSet<ChunkPos> snapshotQueue = new LinkedHashSet<>();
    private final Set<ChunkPos> inFlight = ConcurrentHashMap.newKeySet();

    private ExecutorService executor;

    private String lastDimension = "";
    private int dimensionChangeCooldown = 0;
    private int pruneTimer = 0;

    public Tunnelers() {
        super("tunnelers", "Highlights player-made tunnels and shafts.", Tim.CATEGORY);
        this.registerSettings(
            range, scanDelay, shapeMode, highlightStyle, fadeWithDistance, maxRenderBoxes,
            glowLayers, glowSpread, glowBaseAlpha, pulseSpeed, pulseMinAlpha, pulseMaxAlpha,
            spectralExpand, spectralLineAlpha, spectralFillAlpha, spectralPulse,
            find1x1, min1x1Length, color1x1, findOtherTunnels, minOtherLength, colorOtherTunnels,
            shaftMode, minHoleHeight, colorHoles, minLadderHeight, colorLadderShafts
        );
    }

    @Override
    public void onEnable() {
        clearState();
        if (mc.level != null) lastDimension = mc.level.dimension().location().toString();

        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "Tunnelers-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    @Override
    public void onDisable() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        clearState();
    }

    private void clearState() {
        locations.clear();
        chunkIndex.clear();
        pendingResults.clear();
        scannedChunks.clear();
        snapshotQueue.clear();
        inFlight.clear();
        renderSnapshot = Collections.emptyList();
        mergeScheduled.set(false);
        pruneTimer = 0;
        dimensionChangeCooldown = 0;
    }

    // ── Tick & Queue Management ──
    @Subscribe
    private void onTick(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        if (dimensionChangeCooldown > 0) {
            dimensionChangeCooldown--;
            return;
        }

        String currDim = mc.level.dimension().location().toString();
        if (!currDim.equals(lastDimension)) {
            lastDimension = currDim;
            dimensionChangeCooldown = 40;
            clearState();
            return;
        }

        if (flushPendingResults()) scheduleMerge();

        if (++pruneTimer >= scanDelay.getValue()) {
            pruneTimer = 0;
            if (pruneOutOfRange()) scheduleMerge();
        }

        int playerCX = mc.player.blockPosition().getX() >> 4;
        int playerCZ = mc.player.blockPosition().getZ() >> 4;
        enqueueNewChunks(playerCX, playerCZ);
        drainSnapshotQueue();
    }

    private void enqueueNewChunks(int centerCX, int centerCZ) {
        int r = range.getValue();
        int rSq = r * r;
        int added = 0;
        long startTime = System.nanoTime();

        outer:
        for (int d = 0; d <= r; d++) {
            for (int x = -d; x <= d; x++) {
                if (tryEnqueue(centerCX + x, centerCZ - d, rSq, centerCX, centerCZ)) added++;
                if (added >= MAX_QUEUE_PER_TICK || System.nanoTime() - startTime > TIME_BUDGET_NS) break outer;

                if (d != 0) {
                    if (tryEnqueue(centerCX + x, centerCZ + d, rSq, centerCX, centerCZ)) added++;
                    if (added >= MAX_QUEUE_PER_TICK || System.nanoTime() - startTime > TIME_BUDGET_NS) break outer;
                }
            }
            for (int z = -d + 1; z < d; z++) {
                if (tryEnqueue(centerCX - d, centerCZ + z, rSq, centerCX, centerCZ)) added++;
                if (added >= MAX_QUEUE_PER_TICK || System.nanoTime() - startTime > TIME_BUDGET_NS) break outer;

                if (d != 0) {
                    if (tryEnqueue(centerCX + d, centerCZ + z, rSq, centerCX, centerCZ)) added++;
                    if (added >= MAX_QUEUE_PER_TICK || System.nanoTime() - startTime > TIME_BUDGET_NS) break outer;
                }
            }
        }
    }

    private boolean tryEnqueue(int cx, int cz, int rSq, int centerCX, int centerCZ) {
        int dx = cx - centerCX, dz = cz - centerCZ;
        if (dx * dx + dz * dz > rSq) return false;

        ChunkPos cp = new ChunkPos(cx, cz);
        if (scannedChunks.contains(cp) || inFlight.contains(cp) || snapshotQueue.contains(cp)) return false;
        if (!mc.level.getChunkSource().hasChunk(cx, cz)) return false;

        return snapshotQueue.add(cp);
    }

    private void drainSnapshotQueue() {
        for (int i = 0; i < DRAIN_PER_TICK; i++) {
            if (inFlight.size() >= MAX_IN_FLIGHT || snapshotQueue.isEmpty()) break;

            Iterator<ChunkPos> it = snapshotQueue.iterator();
            ChunkPos cp = it.next();
            it.remove();

            if (!mc.level.getChunkSource().hasChunk(cp.x, cp.z)) continue;
            LevelChunk chunk = mc.level.getChunk(cp.x, cp.z);
            if (chunk == null) continue;

            inFlight.add(cp);

            final ScanConfig config = createScanConfig();
            final int bottomCoord = config.minY >> 4;

            executor.submit(() -> {
                try {
                    BlockState[][] snapshot = snapshotChunk(chunk);
                    Map<BlockPos, TunnelType> results = scanSnapshot(cp, snapshot, bottomCoord, config);
                    pendingResults.add(new ScanResult(cp, results));
                } finally {
                    inFlight.remove(cp);
                }
            });
        }
    }

    private boolean flushPendingResults() {
        ScanResult batch;
        int n = 0;

        while (n < MAX_BATCHES_PER_FLUSH && (batch = pendingResults.poll()) != null) {
            scannedChunks.add(batch.chunkPos);
            Set<BlockPos> index = chunkIndex.computeIfAbsent(batch.chunkPos, k -> ConcurrentHashMap.newKeySet());

            for (Map.Entry<BlockPos, TunnelType> e : batch.results.entrySet()) {
                locations.put(e.getKey(), e.getValue());
                index.add(e.getKey());
            }
            n++;
        }
        return n > 0;
    }

    private boolean pruneOutOfRange() {
        if (mc.player == null) return false;

        int centerCX = mc.player.blockPosition().getX() >> 4;
        int centerCZ = mc.player.blockPosition().getZ() >> 4;
        int rSq = range.getValue() * range.getValue();
        boolean evicted = false;

        Iterator<ChunkPos> it = scannedChunks.iterator();
        while (it.hasNext()) {
            ChunkPos cp = it.next();
            int dx = cp.x - centerCX, dz = cp.z - centerCZ;
            if (dx * dx + dz * dz > rSq) {
                evictChunk(cp);
                it.remove();
                evicted = true;
            }
        }
        return evicted;
    }

    private void evictChunk(ChunkPos cp) {
        Set<BlockPos> idx = chunkIndex.remove(cp);
        if (idx != null) idx.forEach(locations::remove);
    }

    // ── Merge Scheduling & Connected-Component Bounding Boxes ──
    private void scheduleMerge() {
        if (!mergeScheduled.compareAndSet(false, true)) return;

        snapPX = mc.player.blockPosition().getX();
        snapPY = mc.player.blockPosition().getY();
        snapPZ = mc.player.blockPosition().getZ();

        final Map<BlockPos, TunnelType> locSnapshot = new HashMap<>(locations);
        final int px = snapPX, py = snapPY, pz = snapPZ;
        final double maxDistSq = (double) (range.getValue() * 16) * (range.getValue() * 16);

        final int min1 = min1x1Length.getValue();
        final int minOther = minOtherLength.getValue();

        executor.submit(() -> {
            try {
                renderSnapshot = buildMergedBoxes(locSnapshot, px, py, pz, maxDistSq, min1, minOther);
            } finally {
                mergeScheduled.set(false);
            }
        });
    }

    private static List<MergedBox> buildMergedBoxes(
            Map<BlockPos, TunnelType> locs, int px, int py, int pz, double maxDistSq,
            int min1, int minOther) {

        if (locs.isEmpty()) return Collections.emptyList();

        EnumMap<TunnelType, Set<Long>> blocksByType = new EnumMap<>(TunnelType.class);
        for (TunnelType t : TunnelType.values()) {
            blocksByType.put(t, new HashSet<>());
        }

        for (Map.Entry<BlockPos, TunnelType> e : locs.entrySet()) {
            BlockPos p = e.getKey();
            blocksByType.get(e.getValue()).add(pack(p.getX(), p.getY(), p.getZ()));
        }

        List<MergedBox> boxes = new ArrayList<>();

        boxes.addAll(buildComponentBoxes(blocksByType.get(TunnelType.TUNNEL_1x1),   px, py, pz, maxDistSq, TunnelType.TUNNEL_1x1,   min1));
        boxes.addAll(buildComponentBoxes(blocksByType.get(TunnelType.OTHER_TUNNEL),  px, py, pz, maxDistSq, TunnelType.OTHER_TUNNEL,  minOther));
        boxes.addAll(buildComponentBoxes(blocksByType.get(TunnelType.HOLE),          px, py, pz, maxDistSq, TunnelType.HOLE,          1));
        boxes.addAll(buildComponentBoxes(blocksByType.get(TunnelType.LADDER_SHAFT),  px, py, pz, maxDistSq, TunnelType.LADDER_SHAFT,  1));

        boxes.sort(Comparator.comparingDouble(b -> b.distSq));
        return boxes;
    }

    private static List<MergedBox> buildComponentBoxes(
            Set<Long> blocks, int px, int py, int pz, double maxDistSq,
            TunnelType type, int minBlocks) {

        List<MergedBox> boxes = new ArrayList<>();
        if (blocks.isEmpty()) return boxes;

        Set<Long> visited = new HashSet<>(blocks.size());

        for (Long startKey : blocks) {
            if (visited.contains(startKey)) continue;

            Queue<Long> queue = new LinkedList<>();
            queue.add(startKey);
            visited.add(startKey);

            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            int blockCount = 0;

            while (!queue.isEmpty()) {
                long key = queue.poll();
                int cx = unpackX(key), cy = unpackY(key), cz = unpackZ(key);
                blockCount++;

                minX = Math.min(minX, cx); maxX = Math.max(maxX, cx);
                minY = Math.min(minY, cy); maxY = Math.max(maxY, cy);
                minZ = Math.min(minZ, cz); maxZ = Math.max(maxZ, cz);

                for (int[] d : ALL_DIRS_6) {
                    long nk = pack(cx + d[0], cy + d[1], cz + d[2]);
                    if (blocks.contains(nk) && visited.add(nk)) {
                        queue.add(nk);
                    }
                }
            }

            if (minBlocks > 1 && blockCount < minBlocks) continue;

            double nearestX = Math.max(minX, Math.min(px, maxX));
            double nearestY = Math.max(minY, Math.min(py, maxY));
            double nearestZ = Math.max(minZ, Math.min(pz, maxZ));
            double ddx = nearestX - px, ddy = nearestY - py, ddz = nearestZ - pz;
            double distSq = Math.min(ddx * ddx + ddy * ddy + ddz * ddz, maxDistSq);

            boxes.add(new MergedBox(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1, type, distSq));
        }

        return boxes;
    }

    // ── Pack / Unpack ──
    private static long pack(int x, int y, int z) {
        return ((long) (x + 33_554_432) << 38) | ((long) (y + 2_048) << 26) | (z + 33_554_432);
    }

    private static int unpackX(long key) { return (int) (key >>> 38) - 33_554_432; }
    private static int unpackY(long key) { return (int) ((key >>> 26) & 0xFFF) - 2_048; }
    private static int unpackZ(long key) { return (int) (key & 0x3FFFFFF) - 33_554_432; }

    // ── Scanning Logic ──
    private ScanConfig createScanConfig() {
        boolean doHoles = shaftMode.getValue() == ShaftMode.Holes || shaftMode.getValue() == ShaftMode.Both;
        boolean doLadders = shaftMode.getValue() == ShaftMode.LadderShafts || shaftMode.getValue() == ShaftMode.Both;
        boolean ft = findOtherTunnels.getValue();

        return new ScanConfig(
            find1x1.getValue(),
            ft, ft, ft,
            doHoles, doLadders,
            minHoleHeight.getValue(), minLadderHeight.getValue(),
            mc.level.getMinY(), mc.level.getMinY() + mc.level.getHeight()
        );
    }

    private BlockState[][] snapshotChunk(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        BlockState[][] out = new BlockState[sections.length][];

        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection sec = sections[si];
            if (sec == null || sec.hasOnlyAir()) continue;

            BlockState[] data = new BlockState[16 * 16 * 16];
            for (int lx = 0; lx < 16; lx++)
                for (int ly = 0; ly < 16; ly++)
                    for (int lz = 0; lz < 16; lz++)
                        data[lx + lz * 16 + ly * 256] = sec.getBlockState(lx, ly, lz);
            out[si] = data;
        }
        return out;
    }

    private Map<BlockPos, TunnelType> scanSnapshot(ChunkPos cp, BlockState[][] snapshot, int bottomCoord, ScanConfig config) {
        Map<BlockPos, TunnelType> results = new HashMap<>();
        int baseX = cp.x << 4, baseZ = cp.z << 4;
        ScanContext ctx = new ScanContext(snapshot, bottomCoord, config.minY, config.maxY, baseX, baseZ);

        for (int si = 0; si < snapshot.length; si++) {
            if (snapshot[si] == null) continue;
            int sMinY = (bottomCoord + si) << 4, sMaxY = sMinY + 16;
            if (sMaxY <= config.minY || sMinY >= config.maxY) continue;

            for (int lx = 0; lx < 16; lx++) {
                for (int ly = 0; ly < 16; ly++) {
                    int wy = sMinY + ly;
                    if (wy < config.minY || wy >= config.maxY) continue;
                    for (int lz = 0; lz < 16; lz++) {
                        classifyBlock(baseX + lx, wy, baseZ + lz, ctx, config, results);
                    }
                }
            }
        }
        return results;
    }

    private void classifyBlock(int wx, int wy, int wz, ScanContext ctx, ScanConfig config, Map<BlockPos, TunnelType> results) {
        if (config.doHoles && isHole(wx, wy, wz, ctx, config.holeDepth)) {
            for (int i = 0; i < config.holeDepth; i++) results.put(new BlockPos(wx, wy - i, wz), TunnelType.HOLE);
            return;
        }

        if (config.doLadder && isLadderShaft(wx, wy, wz, ctx, config.ladderMin)) {
            for (int i = 0; i < config.ladderMin; i++) results.put(new BlockPos(wx, wy + i, wz), TunnelType.LADDER_SHAFT);
        }

        if (config.do1x1 && is1x1Tunnel(wx, wy, wz, ctx)) {
            results.put(new BlockPos(wx, wy + 1, wz), TunnelType.TUNNEL_1x1);
        }

        if (config.do1x1 && is1x1AllFourWalls(wx, wy, wz, ctx)) {
            results.put(new BlockPos(wx, wy + 1, wz), TunnelType.TUNNEL_1x1);
        }

        if (config.do1x2 && is1x2Tunnel(wx, wy, wz, ctx)) {
            results.put(new BlockPos(wx, wy + 1, wz), TunnelType.OTHER_TUNNEL);
            results.put(new BlockPos(wx, wy + 2, wz), TunnelType.OTHER_TUNNEL);
        }

        if (config.do2x2 && is2x2Tunnel(wx, wy, wz, ctx)) {
            for (int dx = 0; dx < 2; dx++) {
                for (int dy = 1; dy <= 2; dy++) {
                    for (int dz = 0; dz < 2; dz++) {
                        results.put(new BlockPos(wx + dx, wy + dy, wz + dz), TunnelType.OTHER_TUNNEL);
                    }
                }
            }
        }

        if (config.doAbnormal) {
            int sz = getAbnormalTunnelSize(wx, wy, wz, ctx);
            if (sz > 0) {
                for (int dx = 0; dx < sz; dx++) {
                    for (int dy = 1; dy <= sz; dy++) {
                        for (int dz = 0; dz < sz; dz++) {
                            results.put(new BlockPos(wx + dx, wy + dy, wz + dz), TunnelType.OTHER_TUNNEL);
                        }
                    }
                }
            }
        }
    }

    // ── Block Tests ──
    private boolean isHole(int x, int y, int z, ScanContext ctx, int depth) {
        if (!ctx.isTunnelInterior(x, y, z)) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && !ctx.isSolid(x + dx, y, z + dz)) return false;
            }
        }
        for (int i = 1; i < depth; i++) {
            int sy = y - i;
            if (!ctx.isTunnelInterior(x, sy, z) || !ctx.isSolid(x - 1, sy, z) || !ctx.isSolid(x + 1, sy, z)
                    || !ctx.isSolid(x, sy, z - 1) || !ctx.isSolid(x, sy, z + 1)) return false;
        }
        return true;
    }

    private boolean is1x1Tunnel(int x, int y, int z, ScanContext ctx) {
        if (!ctx.isSolid(x, y, z) || !ctx.isTunnelInterior(x, y + 1, z) || !ctx.isSolid(x, y + 2, z)) return false;

        boolean northSolid = ctx.isSolid(x, y + 1, z - 1);
        boolean southSolid = ctx.isSolid(x, y + 1, z + 1);
        boolean eastSolid  = ctx.isSolid(x + 1, y + 1, z);
        boolean westSolid  = ctx.isSolid(x - 1, y + 1, z);

        boolean neSolid = ctx.isSolid(x + 1, y + 1, z - 1);
        boolean nwSolid = ctx.isSolid(x - 1, y + 1, z - 1);
        boolean seSolid = ctx.isSolid(x + 1, y + 1, z + 1);
        boolean swSolid = ctx.isSolid(x - 1, y + 1, z + 1);

        int solidCardinal = (northSolid ? 1 : 0) + (southSolid ? 1 : 0) + (eastSolid ? 1 : 0) + (westSolid ? 1 : 0);

        if (solidCardinal == 3) return neSolid && nwSolid && seSolid && swSolid;
        if (solidCardinal == 2) {
            boolean straight = (northSolid && southSolid) || (eastSolid && westSolid);
            return straight && neSolid && nwSolid && seSolid && swSolid;
        }
        return false;
    }

    private boolean is1x1AllFourWalls(int x, int y, int z, ScanContext ctx) {
        if (!ctx.isSolid(x, y, z) || !ctx.isTunnelInterior(x, y + 1, z) || !ctx.isSolid(x, y + 2, z)) return false;
        if (!ctx.isSolid(x, y + 1, z - 1)) return false;
        if (!ctx.isSolid(x, y + 1, z + 1)) return false;
        if (!ctx.isSolid(x + 1, y + 1, z)) return false;
        if (!ctx.isSolid(x - 1, y + 1, z)) return false;
        return true;
    }

    private boolean is1x2Tunnel(int x, int y, int z, ScanContext ctx) {
        if (!is1x2Slice(x, y, z, ctx)) return false;
        if (isMineshaftBlock(ctx.get(x, y, z)) || isMineshaftBlock(ctx.get(x, y + 3, z))) return false;

        boolean northSolid = ctx.isSolid(x, y + 1, z - 1) && ctx.isSolid(x, y + 2, z - 1);
        boolean southSolid = ctx.isSolid(x, y + 1, z + 1) && ctx.isSolid(x, y + 2, z + 1);
        boolean eastSolid  = ctx.isSolid(x + 1, y + 1, z) && ctx.isSolid(x + 1, y + 2, z);
        boolean westSolid  = ctx.isSolid(x - 1, y + 1, z) && ctx.isSolid(x - 1, y + 2, z);

        int solidWalls = (northSolid ? 1 : 0) + (southSolid ? 1 : 0) + (eastSolid ? 1 : 0) + (westSolid ? 1 : 0);

        if (solidWalls == 3) return true;
        if (solidWalls == 2) return (northSolid && southSolid) || (eastSolid && westSolid);
        return false;
    }

    private boolean is1x2Slice(int x, int y, int z, ScanContext ctx) {
        return ctx.isSolid(x, y, z)
            && ctx.isTunnelInterior(x, y + 1, z)
            && ctx.isTunnelInterior(x, y + 2, z)
            && ctx.isSolid(x, y + 3, z);
    }

    private boolean is2x2Tunnel(int x, int y, int z, ScanContext ctx) {
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                if (!ctx.isSolid(x + dx, y, z + dz) || !ctx.isSolid(x + dx, y + 3, z + dz)) return false;
                if (!ctx.isTunnelInterior(x + dx, y + 1, z + dz) || !ctx.isTunnelInterior(x + dx, y + 2, z + dz)) return false;
            }
        }

        boolean northSolid = true, southSolid = true, eastSolid = true, westSolid = true;

        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 1; dy <= 2; dy++) {
                if (!ctx.isSolid(x + dx, y + dy, z - 1)) northSolid = false;
                if (!ctx.isSolid(x + dx, y + dy, z + 2)) southSolid = false;
            }
        }
        for (int dz = 0; dz < 2; dz++) {
            for (int dy = 1; dy <= 2; dy++) {
                if (!ctx.isSolid(x + 2, y + dy, z + dz)) eastSolid = false;
                if (!ctx.isSolid(x - 1, y + dy, z + dz)) westSolid = false;
            }
        }

        int solidWalls = (northSolid ? 1 : 0) + (southSolid ? 1 : 0) + (eastSolid ? 1 : 0) + (westSolid ? 1 : 0);
        if (solidWalls == 3) return true;
        if (solidWalls == 2) return (northSolid && southSolid) || (eastSolid && westSolid);
        return false;
    }

    private int getAbnormalTunnelSize(int x, int y, int z, ScanContext ctx) {
        if (isTunnelOfSize(x, y, z, ctx, 5)) return 5;
        if (isTunnelOfSize(x, y, z, ctx, 4)) return 4;
        if (isTunnelOfSize(x, y, z, ctx, 3)) return 3;
        return 0;
    }

    private boolean isTunnelOfSize(int x, int y, int z, ScanContext ctx, int s) {
        for (int fx = 0; fx < s; fx++) {
            for (int fz = 0; fz < s; fz++) {
                if (!ctx.isSolid(x + fx, y, z + fz) || !ctx.isSolid(x + fx, y + s + 1, z + fz)) return false;
            }
        }
        for (int fx = 0; fx < s; fx++) {
            for (int fy = 1; fy <= s; fy++) {
                for (int fz = 0; fz < s; fz++) {
                    if (!ctx.isTunnelInterior(x + fx, y + fy, z + fz)) return false;
                }
            }
        }
        for (int fx = 0; fx < s; fx++) {
            for (int fy = 1; fy <= s; fy++) {
                if (!ctx.isSolid(x + fx, y + fy, z - 1) || !ctx.isSolid(x + fx, y + fy, z + s)) return false;
            }
        }
        for (int fz = 0; fz < s; fz++) {
            for (int fy = 1; fy <= s; fy++) {
                if (!ctx.isSolid(x - 1, y + fy, z + fz) || !ctx.isSolid(x + s, y + fy, z + fz)) return false;
            }
        }
        return true;
    }

    private boolean isMineshaftBlock(BlockState s) {
        if (s == null) return false;
        Block b = s.getBlock();
        return b == Blocks.OAK_PLANKS || b == Blocks.DARK_OAK_PLANKS;
    }

    private boolean isLadderShaft(int x, int y, int z, ScanContext ctx, int minH) {
        if (!ctx.isSolid(x, y - 1, z)) return false;
        for (int i = 0; i < minH; i++) {
            int cy = y + i;
            if (!ctx.isTunnelInterior(x, cy, z)) return false;
            if (!ctx.isLadder(x - 1, cy, z) && !ctx.isLadder(x + 1, cy, z)
                    && !ctx.isLadder(x, cy, z - 1) && !ctx.isLadder(x, cy, z + 1)) return false;

            int walls = 0;
            if (ctx.isSolid(x - 1, cy, z)) walls++;
            if (ctx.isSolid(x + 1, cy, z)) walls++;
            if (ctx.isSolid(x, cy, z - 1)) walls++;
            if (ctx.isSolid(x, cy, z + 1)) walls++;
            if (walls < 3) return false;
        }
        return true;
    }

    // ── Rendering ──
    @Subscribe
    private void onRender(EventRender3D event) {
        if (mc.player == null) return;

        List<MergedBox> snapshot = renderSnapshot;
        if (snapshot.isEmpty()) return;

        IRenderer3D r = event.getRenderer();
        r.begin(event.getMatrixStack());

        boolean doFade = fadeWithDistance.getValue();
        double maxDistSq = (double) (range.getValue() * 16) * (range.getValue() * 16);
        int limit = maxRenderBoxes.getValue();
        BoxMode sm = shapeMode.getValue();
        HighlightStyle style = highlightStyle.getValue();

        double spectralPulseMult = 1.0;
        if (style == HighlightStyle.SPECTRAL && spectralPulse.getValue()) {
            spectralPulseMult = 0.6 + 0.4 * (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 750.0 * Math.PI));
        }

        float pulseFactor = (style == HighlightStyle.PULSE) ? getPulseFactor() : 0f;

        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();

        int drawn = 0;
        for (MergedBox box : snapshot) {
            if (drawn >= limit) break;

            Color base = getColor(box.type);
            if (base == null) continue;

            float fadeFrac = 1.0f;
            if (doFade) {
                double dx = px < box.x1 ? box.x1 - px : (px > box.x2 ? px - box.x2 : 0);
                double dy = py < box.y1 ? box.y1 - py : (py > box.y2 ? py - box.y2 : 0);
                double dz = pz < box.z1 ? box.z1 - pz : (pz > box.z2 ? pz - box.z2 : 0);
                double currentDistSq = dx * dx + dy * dy + dz * dz;

                fadeFrac = (float) Math.max(0.0, 1.0 - currentDistSq / maxDistSq);
                if (fadeFrac <= 0) continue;
            }

            int fadedA = Math.max(8, (int) (base.getAlpha() * fadeFrac));
            Color fadedColor = new Color(base.getRed(), base.getGreen(), base.getBlue(), fadedA);

            switch (style) {
                case GLOW     -> renderGlowBox(r, box, fadedColor, fadeFrac, sm);
                case PULSE    -> renderPulseBox(r, box, fadedColor, fadeFrac, pulseFactor, sm);
                case SPECTRAL -> renderSpectralBox(r, box, fadedColor, fadeFrac, spectralPulseMult);
            }
            drawn++;
        }

        r.end();
    }

    private void dbc(IRenderer3D r, double x1, double y1, double z1, double x2, double y2, double z2, boolean fill, boolean outline, int color) {
        r.drawBox(x1, y1, z1, x2 - x1, y2 - y1, z2 - z1, fill, outline, color);
    }

    private void renderGlowBox(IRenderer3D r, MergedBox box, Color faded, float fadeFrac, BoxMode sm) {
        int layers = glowLayers.getValue();
        double spread = glowSpread.getValue();
        int baseAlpha = glowBaseAlpha.getValue();

        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double t = (double) (i - 1) / layers;
            int layerAlpha = Math.max(4, (int) (baseAlpha * (1.0 - t * t)));
            layerAlpha = Math.max(4, (int) (layerAlpha * fadeFrac));

            dbc(r, box.x1 - expansion, box.y1 - expansion, box.z1 - expansion,
                box.x2 + expansion, box.y2 + expansion, box.z2 + expansion,
                true, false, RenderUtils.withAlpha(faded, layerAlpha));
        }
        dbc(r, box.x1, box.y1, box.z1, box.x2, box.y2, box.z2, sm != BoxMode.Lines, sm != BoxMode.Sides, faded.getRGB());
    }

    private void renderPulseBox(IRenderer3D r, MergedBox box, Color faded, float fadeFrac, float pulseFactor, BoxMode sm) {
        int maxA = (int) (pulseMaxAlpha.getValue() * fadeFrac);
        int minA = (int) (pulseMinAlpha.getValue() * fadeFrac);
        int pa = Math.min(255, Math.max(0, (int) (minA + (maxA - minA) * pulseFactor)));

        int layers = glowLayers.getValue();
        double spread = glowSpread.getValue();

        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double taper = 1.0 - ((double) (i - 1) / layers) * 0.6;
            int layerAlpha = Math.max(4, (int) (pa * taper));
            dbc(r, box.x1 - expansion, box.y1 - expansion, box.z1 - expansion,
                box.x2 + expansion, box.y2 + expansion, box.z2 + expansion,
                true, false, RenderUtils.withAlpha(faded, layerAlpha));
        }
        dbc(r, box.x1, box.y1, box.z1, box.x2, box.y2, box.z2, sm != BoxMode.Lines, false, RenderUtils.withAlpha(faded, pa / 3));
        dbc(r, box.x1, box.y1, box.z1, box.x2, box.y2, box.z2, false, sm != BoxMode.Sides, RenderUtils.withAlpha(faded, pa));
    }

    private void renderSpectralBox(IRenderer3D r, MergedBox box, Color faded, float fadeFrac, double pulseMult) {
        double expand = spectralExpand.getValue();
        double ex1 = box.x1 - expand, ey1 = box.y1 - expand, ez1 = box.z1 - expand;
        double ex2 = box.x2 + expand, ey2 = box.y2 + expand, ez2 = box.z2 + expand;

        int lineAlpha = Math.max(4, (int) (spectralLineAlpha.getValue() * fadeFrac * pulseMult));
        int fillAlpha = Math.max(0, (int) (spectralFillAlpha.getValue() * fadeFrac * pulseMult));

        if (fillAlpha > 0) {
            dbc(r, ex1, ey1, ez1, ex2, ey2, ez2, true, false, RenderUtils.withAlpha(faded, fillAlpha));
        }
        dbc(r, ex1, ey1, ez1, ex2, ey2, ez2, false, true, RenderUtils.withAlpha(faded, lineAlpha));
    }

    private float getPulseFactor() {
        double speed = pulseSpeed.getValue();
        double t = System.currentTimeMillis() / 1000.0;
        double phase = t * speed * Math.PI * 2.0;
        return (float) ((Math.sin(phase) + 1.0) * 0.5);
    }

    private Color getColor(TunnelType type) {
        if (type == null) return null;
        ShaftMode sm = shaftMode.getValue();
        return switch (type) {
            case TUNNEL_1x1   -> find1x1.getValue() ? color1x1.getValue() : null;
            case OTHER_TUNNEL -> findOtherTunnels.getValue() ? colorOtherTunnels.getValue() : null;
            case HOLE         -> (sm == ShaftMode.Holes || sm == ShaftMode.Both) ? colorHoles.getValue() : null;
            case LADDER_SHAFT -> (sm == ShaftMode.LadderShafts || sm == ShaftMode.Both) ? colorLadderShafts.getValue() : null;
        };
    }

    // ── Helper Classes ──
    private static final class ScanContext {
        private final BlockState[][] snapshot;
        private final int bottomCoord, minY, maxY, baseX, baseZ;

        ScanContext(BlockState[][] s, int bc, int minY, int maxY, int bx, int bz) {
            this.snapshot = s;
            this.bottomCoord = bc;
            this.minY = minY;
            this.maxY = maxY;
            this.baseX = bx;
            this.baseZ = bz;
        }

        BlockState get(int x, int y, int z) {
            if (y < minY || y >= maxY) return null;
            int lx = x - baseX, lz = z - baseZ;
            if (lx < 0 || lx >= 16 || lz < 0 || lz >= 16) return null;

            int si = (y >> 4) - bottomCoord;
            if (si < 0 || si >= snapshot.length) return null;

            BlockState[] sec = snapshot[si];
            return sec == null ? null : sec[lx + lz * 16 + (y & 15) * 256];
        }

        boolean isSolid(int x, int y, int z) {
            BlockState s = get(x, y, z);
            return s != null && s.canOcclude();
        }

        boolean isAir(int x, int y, int z) {
            BlockState s = get(x, y, z);
            return s == null || s.isAir();
        }

        boolean isLadder(int x, int y, int z) {
            BlockState s = get(x, y, z);
            return s != null && s.is(Blocks.LADDER);
        }

        boolean isTunnelInterior(int x, int y, int z) {
            BlockState s = get(x, y, z);
            if (s == null || s.isAir()) return true;
            if (s.is(Blocks.WATER) || s.is(Blocks.LAVA)) return true;
            return false;
        }
    }

    private static final class ScanConfig {
        final boolean do1x1, do1x2, do2x2, doAbnormal, doHoles, doLadder;
        final int holeDepth, ladderMin, minY, maxY;

        ScanConfig(boolean do1x1, boolean do1x2, boolean do2x2,
                   boolean doAbnormal, boolean doHoles, boolean doLadder, int holeDepth,
                   int ladderMin, int minY, int maxY) {
            this.do1x1 = do1x1;
            this.do1x2 = do1x2;
            this.do2x2 = do2x2;
            this.doAbnormal = doAbnormal;
            this.doHoles = doHoles;
            this.doLadder = doLadder;
            this.holeDepth = holeDepth;
            this.ladderMin = ladderMin;
            this.minY = minY;
            this.maxY = maxY;
        }
    }

    private static final class MergedBox {
        final double x1, y1, z1, x2, y2, z2;
        final TunnelType type;
        final double distSq;

        MergedBox(double x1, double y1, double z1, double x2, double y2, double z2, TunnelType type, double distSq) {
            this.x1 = x1; this.y1 = y1; this.z1 = z1;
            this.x2 = x2; this.y2 = y2; this.z2 = z2;
            this.type = type; this.distSq = distSq;
        }
    }

    private static final class ScanResult {
        final ChunkPos chunkPos;
        final Map<BlockPos, TunnelType> results;

        ScanResult(ChunkPos chunkPos, Map<BlockPos, TunnelType> results) {
            this.chunkPos = chunkPos;
            this.results = results;
        }
    }
}
