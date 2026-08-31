package com.example.addon.modules;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.addon.Tim;
import com.example.addon.utils.RenderUtils;

import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.network.EventPacket;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.events.world.EventLoadWorld;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.BindSetting;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.core.bind.key.NullKey;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

public class Raidar extends ToggleableModule {

    private static final int CHUNK_SCAN_LIMIT_PER_TICK = 64;
    private static final int CLEANUP_INTERVAL_TICKS = 60;

    public enum HighlightStyle { GLOW, SPECTRAL, PULSE }
    public enum BeamStyle { BOX, GUARDIAN }
    public enum YFilterMode { NONE, ABOVE_Y, BELOW_Y, BETWEEN }
    public enum BoxMode { Both, Sides, Lines }

    // ── General ──
    private final NumberSetting<Integer> range = new NumberSetting<>("range", "Scan range in blocks.", 128, 16, 256);
    private final EnumSetting<YFilterMode> yFilterMode = new EnumSetting<>("y-filter-mode", "Filter which Y levels are scanned.", YFilterMode.NONE);
    private final NumberSetting<Integer> minY = new NumberSetting<>("min-y", "Minimum Y level to scan.", -64, -64, 320)
        .setVisibility(() -> yFilterMode.getValue() == YFilterMode.ABOVE_Y || yFilterMode.getValue() == YFilterMode.BETWEEN);
    private final NumberSetting<Integer> maxY = new NumberSetting<>("max-y", "Maximum Y level to scan.", 320, -64, 320)
        .setVisibility(() -> yFilterMode.getValue() == YFilterMode.BELOW_Y || yFilterMode.getValue() == YFilterMode.BETWEEN);
    private final BindSetting clearBeamsKey = new BindSetting("clear-beams", "Toggles rendering of beams on and off.", NullKey.INSTANCE);

    // ── Storage ──
    private final BooleanSetting scanChests = new BooleanSetting("chests", "Scan for standard and trapped chests.", true);
    private final ColorSetting chestColor = new ColorSetting("chest-color", "Chest color.", new Color(255, 215, 0, 255)).setVisibility(scanChests::getValue);
    private final BooleanSetting scanBarrels = new BooleanSetting("barrels", "Scan for barrels.", true);
    private final ColorSetting barrelColor = new ColorSetting("barrel-color", "Barrel color.", new Color(139, 69, 19, 255)).setVisibility(scanBarrels::getValue);
    private final BooleanSetting scanShulkers = new BooleanSetting("shulkers", "Scan for shulker boxes.", true);
    private final ColorSetting shulkerColor = new ColorSetting("shulker-color", "Shulker color.", new Color(160, 32, 240, 255)).setVisibility(scanShulkers::getValue);
    private final BooleanSetting scanEnderChests = new BooleanSetting("ender-chests", "Scan for ender chests.", true);
    private final ColorSetting enderColor = new ColorSetting("ender-color", "Ender chest color.", new Color(75, 0, 130, 255)).setVisibility(scanEnderChests::getValue);

    // ── Obsidian ESP ──
    private final BooleanSetting scanObsidian = new BooleanSetting("nether-obsidian", "Detects unnatural obsidian clusters in the Nether only.", true);
    private final ColorSetting obsidianColor = new ColorSetting("obsidian-color", "Obsidian color.", new Color(30, 30, 30, 255)).setVisibility(scanObsidian::getValue);
    private final NumberSetting<Integer> maxObsidianCluster = new NumberSetting<>("max-cluster-size", "Max obsidian in a cluster before ignoring it. 0 to disable.", 15, 0, 50).setVisibility(scanObsidian::getValue);

    // ── Utility & Decorative ──
    private final BooleanSetting scanUtility = new BooleanSetting("utility-blocks", "Detect furnaces, hoppers, dispensers, etc.", true);
    private final ColorSetting utilityColor = new ColorSetting("utility-color", "Utility color.", new Color(150, 150, 150, 255)).setVisibility(scanUtility::getValue);
    private final BooleanSetting scanDecorative = new BooleanSetting("decorative-blocks", "Detect brewing stands, crafters, pots, etc.", true);
    private final ColorSetting decorativeColor = new ColorSetting("decorative-color", "Decorative color.", new Color(180, 100, 220, 255)).setVisibility(scanDecorative::getValue);

    // ── Render ──
    private final EnumSetting<BoxMode> shapeMode = new EnumSetting<>("shape-mode", "Box render mode.", BoxMode.Both);
    private final EnumSetting<HighlightStyle> highlightStyle = new EnumSetting<>("highlight-style", "Highlight style.", HighlightStyle.GLOW);
    private final NumberSetting<Integer> glowLayers = new NumberSetting<>("glow-layers", "Bloom layer count.", 4, 1, 8)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.GLOW || highlightStyle.getValue() == HighlightStyle.PULSE);
    private final NumberSetting<Double> glowSpread = new NumberSetting<>("glow-spread", "Bloom spread.", 0.05, 0.01, 0.2)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.GLOW || highlightStyle.getValue() == HighlightStyle.PULSE);
    private final NumberSetting<Integer> glowBaseAlpha = new NumberSetting<>("glow-base-alpha", "Bloom alpha.", 50, 4, 150).setVisibility(() -> highlightStyle.getValue() == HighlightStyle.GLOW);
    private final NumberSetting<Integer> spectralLineAlpha = new NumberSetting<>("line-alpha", "Outline alpha.", 255, 0, 255).setVisibility(() -> highlightStyle.getValue() == HighlightStyle.SPECTRAL);
    private final NumberSetting<Integer> spectralFillAlpha = new NumberSetting<>("fill-alpha", "Fill alpha.", 15, 0, 255).setVisibility(() -> highlightStyle.getValue() == HighlightStyle.SPECTRAL);
    private final NumberSetting<Double> spectralExpand = new NumberSetting<>("expand", "Box expansion.", 0.05, 0.0, 0.5).setVisibility(() -> highlightStyle.getValue() == HighlightStyle.SPECTRAL);
    private final NumberSetting<Double> pulseSpeed = new NumberSetting<>("pulse-speed", "Pulse cycle speed.", 1.0, 0.1, 5.0).setVisibility(() -> highlightStyle.getValue() == HighlightStyle.PULSE);
    private final NumberSetting<Integer> pulseMinAlpha = new NumberSetting<>("pulse-min-alpha", "Lowest alpha reached during the pulse.", 15, 0, 255).setVisibility(() -> highlightStyle.getValue() == HighlightStyle.PULSE);
    private final NumberSetting<Integer> pulseMaxAlpha = new NumberSetting<>("pulse-max-alpha", "Peak alpha reached during the pulse.", 220, 0, 255).setVisibility(() -> highlightStyle.getValue() == HighlightStyle.PULSE);

    // ── Beam Triggers ──
    private final NumberSetting<Integer> minChestsForBeam = new NumberSetting<>("min-chests-for-beam", "Minimum chests in a cluster to trigger a beam.", 4, 1, 20);
    private final NumberSetting<Integer> minBarrelsForBeam = new NumberSetting<>("min-barrels-for-beam", "Minimum barrels in a cluster to trigger a beam.", 4, 1, 20);
    private final NumberSetting<Integer> minShulkersForBeam = new NumberSetting<>("min-shulkers-for-beam", "Minimum shulkers in a cluster to trigger a beam.", 1, 1, 20);
    private final NumberSetting<Integer> minEnderChestsForBeam = new NumberSetting<>("min-ender-chests-for-beam", "Minimum ender chests in a cluster to trigger a beam.", 2, 1, 20);
    private final NumberSetting<Integer> minObsidianForBeam = new NumberSetting<>("min-obsidian-for-beam", "Minimum obsidian blocks in a cluster to trigger a beam.", 1, 1, 20);
    private final BooleanSetting mergeBeams = new BooleanSetting("merge-beams", "Merge beams for nearby clusters.", true);
    private final NumberSetting<Double> mergeDistance = new NumberSetting<>("merge-distance", "Distance within which beams are merged.", 3.0, 0.0, 10.0).setVisibility(mergeBeams::getValue);
    private final EnumSetting<BeamStyle> beamStyle = new EnumSetting<>("beam-style", "Beam style.", BeamStyle.GUARDIAN);
    private final NumberSetting<Integer> beamWidth = new NumberSetting<>("beam-width", "Box beam width.", 15, 1, 100).setVisibility(() -> beamStyle.getValue() == BeamStyle.BOX);
    private final NumberSetting<Double> guardianRadius = new NumberSetting<>("guardian-radius", "Guardian beam radius.", 0.08, 0.01, 1.0).setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Integer> guardianStrands = new NumberSetting<>("guardian-strands", "Guardian glow layer count.", 4, 2, 16).setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Double> guardianSpinSpeed = new NumberSetting<>("guardian-spin-speed", "Unused (visual pacing).", 1.0, 0.1, 5.0).setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN);

    // ── State ──
    private final Map<BlockPos, StashType> stashes = new ConcurrentHashMap<>();
    private final Map<BlockPos, StashCluster> stashClusterMap = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final Set<ChunkPos> dirtyChunks = new HashSet<>();

    private boolean stashesDirty = false;
    private boolean beamsHidden = false;
    private int cleanupTimer = 0;
    private boolean wasClearBeamsPressed = false;

    public Raidar() {
        super("raidar", "Finds and highlights stashes with advanced scanning, custom Y limits, and beam thresholds.", Tim.CATEGORY);
        this.registerSettings(
            range, yFilterMode, minY, maxY, clearBeamsKey,
            scanChests, chestColor, scanBarrels, barrelColor, scanShulkers, shulkerColor, scanEnderChests, enderColor,
            scanObsidian, obsidianColor, maxObsidianCluster,
            scanUtility, utilityColor, scanDecorative, decorativeColor,
            shapeMode, highlightStyle, glowLayers, glowSpread, glowBaseAlpha, spectralLineAlpha, spectralFillAlpha, spectralExpand,
            pulseSpeed, pulseMinAlpha, pulseMaxAlpha,
            minChestsForBeam, minBarrelsForBeam, minShulkersForBeam, minEnderChestsForBeam, minObsidianForBeam,
            mergeBeams, mergeDistance, beamStyle, beamWidth, guardianRadius, guardianStrands, guardianSpinSpeed
        );
    }

    @Override
    public void onEnable() { clearAllState(); }

    @Override
    public void onDisable() { clearAllState(); }

    @Subscribe
    private void onGameLeft(EventLoadWorld event) { clearAllState(); }

    private void clearAllState() {
        stashes.clear();
        stashClusterMap.clear();
        scannedChunks.clear();
        dirtyChunks.clear();
        stashesDirty = false;
        beamsHidden = false;
    }

    private void toggleBeams() {
        beamsHidden = !beamsHidden;
        sendNotification(NotificationType.INFO, "Beams " + (beamsHidden ? "hidden" : "visible"));
    }

    @Subscribe
    private void onTick(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        boolean p = clearBeamsKey.getValue().isKeyDown();
        if (p && !wasClearBeamsPressed) toggleBeams();
        wasClearBeamsPressed = p;

        if (!dirtyChunks.isEmpty()) {
            scannedChunks.removeAll(dirtyChunks);
            dirtyChunks.clear();
        }

        BlockPos playerPos = mc.player.blockPosition();
        scanNewChunks(playerPos.getX() >> 4, playerPos.getZ() >> 4);

        if (stashesDirty) {
            stashesDirty = false;
            groupStashes();
        }

        if (++cleanupTimer >= CLEANUP_INTERVAL_TICKS) {
            cleanupTimer = 0;
            cleanupDistantStashes();
        }
    }

    private boolean isYAllowed(int y) {
        return switch (yFilterMode.getValue()) {
            case NONE -> true;
            case ABOVE_Y -> y >= minY.getValue();
            case BELOW_Y -> y <= maxY.getValue();
            case BETWEEN -> y >= minY.getValue() && y <= maxY.getValue();
        };
    }

    private void scanNewChunks(int centerChunkX, int centerChunkZ) {
        int r = range.getValue() >> 4;
        int rSq = r * r, scanned = 0;
        for (int d = 0; d <= r; d++) {
            for (int x = -d; x <= d; x++) {
                if (tryScanChunk(centerChunkX + x, centerChunkZ - d, rSq, centerChunkX, centerChunkZ)) {
                    if (++scanned >= CHUNK_SCAN_LIMIT_PER_TICK) return;
                }
                if (d > 0 && tryScanChunk(centerChunkX + x, centerChunkZ + d, rSq, centerChunkX, centerChunkZ)) {
                    if (++scanned >= CHUNK_SCAN_LIMIT_PER_TICK) return;
                }
            }
            for (int z = -d + 1; z < d; z++) {
                if (tryScanChunk(centerChunkX - d, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) {
                    if (++scanned >= CHUNK_SCAN_LIMIT_PER_TICK) return;
                }
                if (tryScanChunk(centerChunkX + d, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) {
                    if (++scanned >= CHUNK_SCAN_LIMIT_PER_TICK) return;
                }
            }
        }
    }

    private boolean tryScanChunk(int cx, int cz, int rSq, int centerCX, int centerCZ) {
        int dx = cx - centerCX, dz = cz - centerCZ;
        if (dx * dx + dz * dz > rSq) return false;

        ChunkPos cp = new ChunkPos(cx, cz);
        if (scannedChunks.contains(cp)) return false;

        if (mc.level.getChunkSource().hasChunk(cx, cz)) {
            scanChunk(mc.level.getChunk(cx, cz));
            scannedChunks.add(cp);
            return true;
        }
        return false;
    }

    private void scanChunk(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int chunkX = chunk.getPos().x << 4;
        int chunkZ = chunk.getPos().z << 4;

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;

            boolean hasStash;
            try {
                hasStash = section.maybeHas(state -> classifyBlock(state.getBlock()) != null);
            } catch (Exception e) {
                hasStash = false;
            }
            if (!hasStash) continue;

            int sectionMinY = (mc.level.getMinSectionY() + i) * 16;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    int worldY = sectionMinY + y;
                    if (!isYAllowed(worldY)) continue;
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        BlockPos pos = new BlockPos(chunkX + x, worldY, chunkZ + z);
                        StashType type = classifyBlock(state.getBlock());
                        if (type != null) {
                            stashes.put(pos, type);
                            stashesDirty = true;
                        }
                    }
                }
            }
        }
    }

    private StashType classifyBlock(Block block) {
        if (scanChests.getValue() && (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST)) return StashType.CHEST;
        if (scanBarrels.getValue() && block == Blocks.BARREL) return StashType.BARREL;
        if (scanEnderChests.getValue() && block == Blocks.ENDER_CHEST) return StashType.ENDER_CHEST;
        if (scanShulkers.getValue() && block instanceof ShulkerBoxBlock) return StashType.SHULKER;
        if (scanUtility.getValue() && (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER || block == Blocks.HOPPER || block == Blocks.DISPENSER || block == Blocks.DROPPER)) return StashType.UTILITY;
        if (scanDecorative.getValue() && (block == Blocks.BREWING_STAND || block == Blocks.CRAFTER || block == Blocks.CHISELED_BOOKSHELF || block == Blocks.DECORATED_POT)) return StashType.DECORATIVE;
        if (scanObsidian.getValue() && block == Blocks.OBSIDIAN) {
            if (mc.level != null && mc.level.dimension().equals(Level.NETHER)) return StashType.OBSIDIAN;
        }
        return null;
    }

    private void groupStashes() {
        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> active = new HashSet<>();

        List<BlockPos> stashKeys = List.copyOf(stashes.keySet());

        for (BlockPos startPos : stashKeys) {
            if (visited.contains(startPos)) continue;
            StashType type = stashes.get(startPos);
            if (type == null) continue;

            Set<BlockPos> component = new HashSet<>();
            Queue<BlockPos> queue = new LinkedList<>();
            AABB structureBox = new AABB(startPos);
            queue.add(startPos); visited.add(startPos);

            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                component.add(current);
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.relative(dir);
                    if (stashes.get(neighbor) == type && visited.add(neighbor)) {
                        queue.add(neighbor);
                        structureBox = structureBox.minmax(new AABB(neighbor));
                    }
                }
            }

            if (type == StashType.OBSIDIAN) {
                int maxSz = maxObsidianCluster.getValue();
                if (maxSz > 0 && component.size() > maxSz) continue;
                if (hasCryingObsidianNearby(structureBox.inflate(8.0))) continue;
            }

            BlockPos anchor = componentAnchor(component);
            active.add(anchor);

            stashClusterMap.put(anchor, new StashCluster(structureBox.inflate(0.02), component, type));
        }

        stashClusterMap.keySet().retainAll(active);
    }

    private boolean hasCryingObsidianNearby(AABB searchBox) {
        int minX = (int) Math.floor(searchBox.minX);
        int minY0 = (int) Math.floor(searchBox.minY);
        int minZ = (int) Math.floor(searchBox.minZ);
        int maxX = (int) Math.ceil(searchBox.maxX);
        int maxY0 = (int) Math.ceil(searchBox.maxY);
        int maxZ = (int) Math.ceil(searchBox.maxZ);

        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY0; y <= maxY0; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (mc.level.getBlockState(mutable.set(x, y, z)).getBlock() == Blocks.CRYING_OBSIDIAN) return true;
                }
            }
        }
        return false;
    }

    private BlockPos componentAnchor(Set<BlockPos> comp) {
        BlockPos anchor = null;
        for (BlockPos p : comp) {
            if (anchor == null || p.getY() < anchor.getY() || (p.getY() == anchor.getY() && p.getX() < anchor.getX())) anchor = p;
        }
        return anchor;
    }

    private void cleanupDistantStashes() {
        if (mc.player == null) return;
        double distSq = Math.pow(range.getValue() + 64, 2);

        stashes.entrySet().removeIf(e -> e.getKey().distToCenterSqr(mc.player.position()) > distSq);
        stashClusterMap.entrySet().removeIf(e -> e.getValue().boundingBox.getCenter().distanceToSqr(mc.player.position()) > distSq);

        int px = mc.player.blockPosition().getX() >> 4, pz = mc.player.blockPosition().getZ() >> 4;
        int rSq = (range.getValue() >> 4) * (range.getValue() >> 4);
        scannedChunks.removeIf(cp -> (cp.x - px) * (cp.x - px) + (cp.z - pz) * (cp.z - pz) > rSq);
    }

    @Subscribe
    private void onPacket(EventPacket.Receive event) {
        if (mc.level == null || mc.player == null) return;
        if (event.getPacket() instanceof ClientboundBlockUpdatePacket pkt) {
            handleBlockChange(pkt.getPos(), pkt.getBlockState());
        } else if (event.getPacket() instanceof ClientboundSectionBlocksUpdatePacket pkt) {
            pkt.runUpdates(this::handleBlockChange);
        }
    }

    private void handleBlockChange(BlockPos pos, BlockState newState) {
        StashType type = classifyBlock(newState.getBlock());
        if (type != null) {
            if (!isYAllowed(pos.getY())) return;
            stashes.put(pos.immutable(), type);
            stashesDirty = true;
        } else if (stashes.remove(pos) != null) {
            stashesDirty = true;
        }
        if (newState.getBlock() == Blocks.CRYING_OBSIDIAN) stashesDirty = true;
    }

    @Subscribe
    private void onRender(EventRender3D event) {
        if (mc.player == null || mc.level == null) return;

        IRenderer3D r = event.getRenderer();
        r.begin(event.getMatrixStack());

        List<BeamData> beamsToRender = new ArrayList<>();
        Set<BlockPos> renderedDoubleChests = new HashSet<>();

        for (StashCluster cluster : stashClusterMap.values()) {
            Color color = getStructureColor(cluster.type);
            if (color == null) continue;

            for (BlockPos pos : cluster.blocks) {
                if (renderedDoubleChests.contains(pos)) continue;

                BlockState state = mc.level.getBlockState(pos);
                AABB renderBox;

                if (cluster.type == StashType.CHEST && state.getBlock() instanceof ChestBlock) {
                    try {
                        ChestType chestType = state.getValue(BlockStateProperties.CHEST_TYPE);
                        if (chestType != ChestType.SINGLE) {
                            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
                            Direction neighborDir = chestType == ChestType.LEFT ? facing.getClockWise() : facing.getCounterClockWise();
                            BlockPos neighborPos = pos.relative(neighborDir);
                            if (cluster.blocks.contains(neighborPos)) {
                                renderBox = createPaddedDoubleChestBox(pos, neighborPos);
                                renderedDoubleChests.add(neighborPos);
                            } else {
                                renderBox = new AABB(pos).inflate(0.02);
                            }
                        } else {
                            renderBox = new AABB(pos).inflate(0.02);
                        }
                    } catch (Exception e) {
                        renderBox = new AABB(pos).inflate(0.02);
                    }
                } else {
                    renderBox = new AABB(pos).inflate(0.02);
                }

                if (highlightStyle.getValue() == HighlightStyle.SPECTRAL) {
                    AABB b = renderBox.inflate(spectralExpand.getValue());
                    db(r, b, true, false, RenderUtils.withAlpha(color, spectralFillAlpha.getValue()));
                    db(r, b, false, true, RenderUtils.withAlpha(color, spectralLineAlpha.getValue()));
                } else if (highlightStyle.getValue() == HighlightStyle.PULSE) {
                    renderPulseBox(r, renderBox, color);
                } else {
                    renderGlowLayers(r, renderBox, color);
                    if (shapeMode.getValue() != BoxMode.Sides) db(r, renderBox, false, true, color.getRGB());
                }
            }

            if (!beamsHidden) {
                int count = cluster.blocks.size();
                boolean shouldBeam = switch (cluster.type) {
                    case CHEST -> count >= minChestsForBeam.getValue();
                    case BARREL -> count >= minBarrelsForBeam.getValue();
                    case SHULKER -> count >= minShulkersForBeam.getValue();
                    case ENDER_CHEST -> count >= minEnderChestsForBeam.getValue();
                    case OBSIDIAN -> count >= minObsidianForBeam.getValue();
                    default -> false;
                };
                if (shouldBeam) {
                    Color beamColor = (highlightStyle.getValue() == HighlightStyle.PULSE) ? pulseColor(color) : color;
                    beamsToRender.add(new BeamData(cluster.boundingBox, beamColor));
                }
            }
        }

        renderBeams(r, beamsToRender);
        r.end();
    }

    private void db(IRenderer3D r, AABB b, boolean fill, boolean outline, int color) {
        r.drawBox(b.minX, b.minY, b.minZ, b.getXsize(), b.getYsize(), b.getZsize(), fill, outline, color);
    }

    private void renderGlowLayers(IRenderer3D r, AABB box, Color color) {
        int layers = glowLayers.getValue();
        double spread = glowSpread.getValue();
        int baseAlpha = glowBaseAlpha.getValue();
        for (int i = layers; i >= 1; i--) {
            int layerAlpha = Math.max(4, (int) (baseAlpha * (1.0 - (double) (i - 1) / layers)));
            db(r, box.inflate(spread * i), true, false, RenderUtils.withAlpha(color, layerAlpha));
        }
    }

    private float getPulseFactor() {
        double speed = pulseSpeed.getValue();
        double t = System.currentTimeMillis() / 1000.0;
        double phase = t * speed * Math.PI * 2.0;
        return (float) ((Math.sin(phase) + 1.0) * 0.5);
    }

    private int applyPulse(int baseAlpha) {
        float f = getPulseFactor();
        int min = pulseMinAlpha.getValue();
        int max = pulseMaxAlpha.getValue();
        return Math.min(255, Math.max(0, (int) (min + (max - min) * f)));
    }

    private Color pulseColor(Color base) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), applyPulse(base.getAlpha()));
    }

    private void renderPulseBox(IRenderer3D r, AABB box, Color base) {
        int pa = applyPulse(base.getAlpha());
        int layers = glowLayers.getValue();
        double spread = glowSpread.getValue();
        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double taper = 1.0 - ((double) (i - 1) / layers) * 0.6;
            int layerAlpha = Math.max(4, (int) (pa * taper));
            db(r, box.inflate(expansion), true, false, RenderUtils.withAlpha(base, layerAlpha));
        }
        db(r, box, true, false, RenderUtils.withAlpha(base, pa / 3));
        db(r, box, false, true, RenderUtils.withAlpha(base, pa));
    }

    private void renderBeams(IRenderer3D r, List<BeamData> beams) {
        if (beams.isEmpty()) return;
        if (mergeBeams.getValue()) {
            List<BeamData> merged = new ArrayList<>();
            double distSq = Math.pow(mergeDistance.getValue(), 2);
            for (BeamData beam : beams) {
                boolean skip = false;
                double bx = (beam.box().minX + beam.box().maxX) / 2.0;
                double bz = (beam.box().minZ + beam.box().maxZ) / 2.0;
                for (BeamData m : merged) {
                    double mx = (m.box().minX + m.box().maxX) / 2.0;
                    double mz = (m.box().minZ + m.box().maxZ) / 2.0;
                    if (Math.pow(bx - mx, 2) + Math.pow(bz - mz, 2) <= distSq) { skip = true; break; }
                }
                if (!skip) merged.add(beam);
            }
            beams = merged;
        }
        for (BeamData beam : beams) {
            if (beamStyle.getValue() == BeamStyle.GUARDIAN) renderGuardianBeam(r, beam.box(), beam.color());
            else renderBoxBeam(r, beam.box(), beam.color());
        }
    }

    private void renderBoxBeam(IRenderer3D r, AABB anchorBox, Color color) {
        double beamSize = Math.max(0.01, beamWidth.getValue() / 100.0);
        double centerX = (anchorBox.minX + anchorBox.maxX) / 2.0;
        double centerZ = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int worldBot = mc.level.getMinY(), worldTop = worldBot + mc.level.getHeight();
        AABB beamBox = new AABB(centerX - beamSize, worldBot, centerZ - beamSize, centerX + beamSize, worldTop, centerZ + beamSize);
        renderGlowLayers(r, beamBox, color);
        db(r, beamBox, true, false, RenderUtils.withAlpha(color, 60));
        db(r, beamBox, false, true, color.getRGB());
    }

    private void renderGuardianBeam(IRenderer3D r, AABB anchorBox, Color color) {
        if (mc.level == null) return;
        double cx = (anchorBox.minX + anchorBox.maxX) / 2.0;
        double cz = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int worldBot = mc.level.getMinY();
        int worldTop = worldBot + mc.level.getHeight();
        double radius = Math.max(0.01, guardianRadius.getValue());
        int layers = guardianStrands.getValue();

        for (int i = layers; i >= 1; i--) {
            double exp = radius * i;
            int alpha = Math.max(4, (int) (160 * (1.0 - (double) (i - 1) / layers)));
            AABB box = new AABB(cx - exp, worldBot, cz - exp, cx + exp, worldTop, cz + exp);
            db(r, box, true, false, RenderUtils.withAlpha(color, alpha));
        }

        double coreR = radius * 0.25;
        AABB coreBox = new AABB(cx - coreR, worldBot, cz - coreR, cx + coreR, worldTop, cz + coreR);
        db(r, coreBox, true, false, RenderUtils.withAlpha(color, 90));
        db(r, coreBox, false, true, RenderUtils.withAlpha(color, 130));
    }

    private AABB createPaddedDoubleChestBox(BlockPos pos1, BlockPos pos2) {
        double p = 0.0625;
        double minX = Math.min(pos1.getX(), pos2.getX()), minY0 = Math.min(pos1.getY(), pos2.getY()), minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxX = Math.max(pos1.getX(), pos2.getX()) + 1, maxY0 = Math.max(pos1.getY(), pos2.getY()) + 1, maxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;
        return new AABB(minX + p, minY0 + p, minZ + p, maxX - p, maxY0 - p, maxZ - p);
    }

    private Color getStructureColor(StashType type) {
        return switch (type) {
            case CHEST -> chestColor.getValue();
            case BARREL -> barrelColor.getValue();
            case SHULKER -> shulkerColor.getValue();
            case ENDER_CHEST -> enderColor.getValue();
            case OBSIDIAN -> obsidianColor.getValue();
            case UTILITY -> utilityColor.getValue();
            case DECORATIVE -> decorativeColor.getValue();
        };
    }

    public void markChunkDirty(ChunkPos cp) { scannedChunks.remove(cp); dirtyChunks.add(cp); stashesDirty = true; }

    private enum StashType { CHEST, BARREL, SHULKER, ENDER_CHEST, OBSIDIAN, UTILITY, DECORATIVE }

    private static class StashCluster {
        final AABB boundingBox;
        final Set<BlockPos> blocks;
        final StashType type;

        StashCluster(AABB bb, Set<BlockPos> pb, StashType t) {
            this.boundingBox = bb;
            this.blocks = pb;
            this.type = t;
        }
    }

    private record BeamData(AABB box, Color color) {}
}
