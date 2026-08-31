package com.example.addon.modules;

import java.awt.Color;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.addon.Tim;
import com.example.addon.utils.InvUtils;
import com.example.addon.utils.RenderUtils;

import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.network.EventPacket;
import org.rusherhack.client.api.events.render.EventRender3D;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class EightToOne extends ToggleableModule {

    private static final int    DIMENSION_SETTLE_TICKS         = 40;
    private static final int    ENTRY_EXCLUSION_COOLDOWN_TICKS = 200;
    private static final int    ENTRY_EXCLUSION_RADIUS         = 5;
    private static final double ENTRY_EXCLUSION_RADIUS_SQ      = (double) ENTRY_EXCLUSION_RADIUS * ENTRY_EXCLUSION_RADIUS;
    private static final int    CHUNK_SCAN_LIMIT_PER_TICK      = 64;
    private static final int    CLEANUP_INTERVAL_TICKS         = 60;
    private static final long   MESSAGE_COOLDOWN_MS            = 2000;

    public enum HighlightStyle { GLOW, SPECTRAL, PULSE }
    public enum CoordVisibility { Visible, Censored, Hidden }
    public enum BeamStyle { BOX, GUARDIAN }
    public enum ReplenishItem { Obsidian, EnderChest }
    public enum BoxMode { Both, Sides, Lines }

    // ── Toggles ──
    private final BooleanSetting scanAnchors = new BooleanSetting("scan-anchors", "Scan Respawn Anchors.", true);

    // ── General ──
    private final NumberSetting<Integer> range = new NumberSetting<>("range", "Portal detection range in chunks.", 32, 16, 64);
    private final BooleanSetting showCreatedCount = new BooleanSetting("show-created-count", "Show a chat message each time a new portal you created is discovered.", true);
    private final EnumSetting<CoordVisibility> coordVisibility = new EnumSetting<>("coord-visibility", "Controls how coordinates are displayed in chat.", CoordVisibility.Visible);

    // ── Nether Portals ──
    private final BooleanSetting differentiatePortalSizes = new BooleanSetting("different-sizes", "Scans Nether portals and gives exit portals and custom/built portals different colors.", true);
    private final NumberSetting<Integer> autoMarkRange = new NumberSetting<>("auto-mark-range", "Auto-mark Nether portals within this many blocks of the player as created by you.", 10, 0, 50)
        .setVisibility(differentiatePortalSizes::getValue);
    private final ColorSetting netherColorFull = new ColorSetting("color-exit-portal", "Exit portal color.", new Color(180, 60, 255, 255)).setVisibility(differentiatePortalSizes::getValue);
    private final ColorSetting netherColorCustom = new ColorSetting("color-custom-built", "Custom/built portal color.", new Color(255, 140, 0, 255)).setVisibility(differentiatePortalSizes::getValue);

    // ── Respawn Anchors ──
    private final ColorSetting anchorChargedColor = new ColorSetting("color-charged", "Charged anchor color.", new Color(255, 200, 0, 255)).setVisibility(scanAnchors::getValue);
    private final ColorSetting anchorUnchargedColor = new ColorSetting("color-uncharged", "Uncharged anchor color.", new Color(100, 100, 120, 255)).setVisibility(scanAnchors::getValue);
    private final BooleanSetting onlyShowChargedAnchors = new BooleanSetting("only-charged", "Only highlight anchors that have at least 1 charge.", false).setVisibility(scanAnchors::getValue);

    // ── Render ──
    private final EnumSetting<BoxMode> shapeMode = new EnumSetting<>("shape-mode", "Box render mode.", BoxMode.Both);
    private final EnumSetting<HighlightStyle> highlightStyle = new EnumSetting<>("highlight-style", "Highlight style.", HighlightStyle.GLOW);
    private final BooleanSetting highlightFrame = new BooleanSetting("highlight-frame", "Highlights the obsidian frame of Nether portals.", true).setVisibility(differentiatePortalSizes::getValue);
    private final BooleanSetting dynamicColors = new BooleanSetting("dynamic-colors", "Cycle colors over time.", false);
    private final NumberSetting<Integer> glowLayers = new NumberSetting<>("glow-layers", "Bloom layer count.", 4, 1, 8)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.GLOW || highlightStyle.getValue() == HighlightStyle.PULSE);
    private final NumberSetting<Double> glowSpread = new NumberSetting<>("glow-spread", "Bloom spread.", 0.05, 0.01, 0.2)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.GLOW || highlightStyle.getValue() == HighlightStyle.PULSE);
    private final NumberSetting<Integer> glowBaseAlpha = new NumberSetting<>("glow-base-alpha", "Bloom alpha.", 50, 4, 150)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.GLOW);
    private final NumberSetting<Integer> spectralLineAlpha = new NumberSetting<>("line-alpha", "Outline alpha.", 255, 0, 255)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.SPECTRAL);
    private final NumberSetting<Integer> spectralFillAlpha = new NumberSetting<>("fill-alpha", "Fill alpha.", 15, 0, 255)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.SPECTRAL);
    private final NumberSetting<Double> spectralExpand = new NumberSetting<>("expand", "Box expansion.", 0.05, 0.0, 0.5)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.SPECTRAL);
    private final NumberSetting<Double> pulseSpeed = new NumberSetting<>("pulse-speed", "Pulse cycle speed. 1.0 = one full fade in/out per second.", 1.0, 0.1, 5.0)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.PULSE);
    private final NumberSetting<Integer> pulseMinAlpha = new NumberSetting<>("pulse-min-alpha", "Lowest alpha reached during the pulse (0 = invisible).", 15, 0, 255)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.PULSE);
    private final NumberSetting<Integer> pulseMaxAlpha = new NumberSetting<>("pulse-max-alpha", "Peak alpha reached during the pulse.", 220, 50, 255)
        .setVisibility(() -> highlightStyle.getValue() == HighlightStyle.PULSE);

    // ── Beam ──
    private final BooleanSetting showBeam = new BooleanSetting("show-beam", "Render a vertical beam at each structure.", true);
    private final NumberSetting<Integer> beamRange = new NumberSetting<>("beam-range", "Maximum horizontal distance (in chunks) to render the vertical beam.", 16, 1, 64)
        .setVisibility(showBeam::getValue);
    private final BooleanSetting onlyNearestBeam = new BooleanSetting("only-nearest-beam", "Only render the beam for the portal closest to the player.", false)
        .setVisibility(showBeam::getValue);
    private final EnumSetting<BeamStyle> beamStyle = new EnumSetting<>("beam-style", "Beam style.", BeamStyle.GUARDIAN).setVisibility(showBeam::getValue);
    private final NumberSetting<Integer> beamWidth = new NumberSetting<>("beam-width", "Beam width.", 15, 1, 100)
        .setVisibility(() -> showBeam.getValue() && beamStyle.getValue() == BeamStyle.BOX);
    private final NumberSetting<Double> guardianRadius = new NumberSetting<>("guardian-radius", "Guardian strand radius.", 0.08, 0.01, 1.0)
        .setVisibility(() -> showBeam.getValue() && beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Integer> guardianStrands = new NumberSetting<>("guardian-strands", "Guardian strand count.", 4, 1, 16)
        .setVisibility(() -> showBeam.getValue() && beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Double> guardianSpinSpeed = new NumberSetting<>("guardian-spin-speed", "Guardian spin speed.", 1.0, 0.1, 5.0)
        .setVisibility(() -> showBeam.getValue() && beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Integer> guardianCoreAlpha = new NumberSetting<>("guardian-core-alpha", "Guardian core alpha.", 90, 4, 255)
        .setVisibility(() -> showBeam.getValue() && beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Integer> guardianStrandAlpha = new NumberSetting<>("guardian-strand-alpha", "Guardian strand alpha.", 160, 4, 255)
        .setVisibility(() -> showBeam.getValue() && beamStyle.getValue() == BeamStyle.GUARDIAN);

    // ── Replenish ──
    private final BooleanSetting replenishMode = new BooleanSetting("replenish-mode", "Toggles the replenish keybind.", false);
    private final EnumSetting<ReplenishItem> replenishItem = new EnumSetting<>("replenish-item", "The item to replenish.", ReplenishItem.Obsidian)
        .setVisibility(replenishMode::getValue);
    private final BooleanSetting useSelectedSlot = new BooleanSetting("use-selected-slot", "Replenishes the currently selected hotbar slot instead of a specific one.", false)
        .setVisibility(replenishMode::getValue);
    private final NumberSetting<Integer> targetSlot = new NumberSetting<>("target-slot", "The specific hotbar slot to replenish (1-9).", 1, 1, 9)
        .setVisibility(() -> replenishMode.getValue() && !useSelectedSlot.getValue());
    private final BindSetting replenishKey = new BindSetting("replenish-key", "Replenishes the target hotbar slot's item to its max stack size from the main inventory.", NullKey.INSTANCE)
        .setVisibility(replenishMode::getValue);

    // ── State ──
    private final Map<BlockPos, PortalType> portals = new ConcurrentHashMap<>();
    private final Set<BlockPos> createdPortals = ConcurrentHashMap.newKeySet();
    private final Map<BlockPos, PortalStructure> portalStructureMap = new ConcurrentHashMap<>();
    private final Map<BlockPos, Boolean> anchorChargeMap = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final Set<ChunkPos> dirtyChunks = new HashSet<>();
    private final Map<String, Long> messageCooldowns = new ConcurrentHashMap<>();

    private String lastDimension = "";
    private int dimensionChangeCooldown = 0;
    private int totalCreated = 0;
    private boolean portalsDirty = false;
    private boolean framesDirty = false;
    private BlockPos entryPortalPos = null;
    private int exclusionTimer = 0;
    private int cleanupTimer = 0;
    private int pendingCheckTimer = 0;
    private boolean wasReplenishPressed = false;

    private final Map<String, Boolean> crossDimensionSizeCache = new ConcurrentHashMap<>();

    public EightToOne() {
        super("eight-to-one", "Tracks Nether portals and Respawn Anchors with 8:1 conversion awareness.", Tim.CATEGORY);
        this.registerSettings(
            scanAnchors, range, showCreatedCount, coordVisibility,
            differentiatePortalSizes, autoMarkRange, netherColorFull, netherColorCustom,
            anchorChargedColor, anchorUnchargedColor, onlyShowChargedAnchors,
            shapeMode, highlightStyle, highlightFrame, dynamicColors,
            glowLayers, glowSpread, glowBaseAlpha, spectralLineAlpha, spectralFillAlpha, spectralExpand,
            pulseSpeed, pulseMinAlpha, pulseMaxAlpha,
            showBeam, beamRange, onlyNearestBeam, beamStyle, beamWidth,
            guardianRadius, guardianStrands, guardianSpinSpeed, guardianCoreAlpha, guardianStrandAlpha,
            replenishMode, replenishItem, useSelectedSlot, targetSlot, replenishKey
        );
    }

    @Override
    public void onEnable() {
        clearAllState();
        totalCreated = 0;
        if (mc.player != null && mc.level != null) lastDimension = mc.level.dimension().location().toString();
    }

    @Override
    public void onDisable() {
        clearAllState();
        totalCreated = 0;
    }

    private void clearAllState() {
        portals.clear(); createdPortals.clear(); portalStructureMap.clear();
        anchorChargeMap.clear(); scannedChunks.clear(); dirtyChunks.clear();
        crossDimensionSizeCache.clear();
        portalsDirty = false; framesDirty = false;
        pendingCheckTimer = 0;
    }

    @Subscribe
    private void onTick(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        handleReplenishHotkey();

        if (dimensionChangeCooldown > 0) { dimensionChangeCooldown--; return; }
        if (exclusionTimer > 0) exclusionTimer--;

        handleDimensionChange();

        if (!dirtyChunks.isEmpty()) {
            scannedChunks.removeAll(dirtyChunks);
            dirtyChunks.clear();
        }

        BlockPos playerPos = mc.player.blockPosition();
        scanNewChunks(playerPos.getX() >> 4, playerPos.getZ() >> 4);

        if (++pendingCheckTimer >= 20) {
            pendingCheckTimer = 0;
            for (PortalStructure s : portalStructureMap.values()) {
                if (s.sizeState == SizeState.PENDING) {
                    portalsDirty = true;
                    break;
                }
            }
        }

        if (portalsDirty) {
            portalsDirty = false;
            groupPortals();
        }

        if (framesDirty) {
            framesDirty = false;
            precomputeFrameBoxes();
        }

        if (++cleanupTimer >= CLEANUP_INTERVAL_TICKS) {
            cleanupTimer = 0;
            cleanupDistantPortals();
        }
    }

    private void handleReplenishHotkey() {
        boolean pressed = replenishKey.getValue().isKeyDown();
        if (pressed && !wasReplenishPressed && mc.screen == null && mc.player != null && mc.level != null && replenishMode.getValue()) {
            handleReplenish();
        }
        wasReplenishPressed = pressed;
    }

    private void handleDimensionChange() {
        String currDim = mc.level.dimension().location().toString();
        if (currDim.equals(lastDimension)) return;

        dimensionChangeCooldown = DIMENSION_SETTLE_TICKS;
        exclusionTimer = ENTRY_EXCLUSION_COOLDOWN_TICKS;
        lastDimension = currDim;
        entryPortalPos = mc.player.blockPosition();

        portals.clear(); createdPortals.clear(); portalStructureMap.clear(); scannedChunks.clear();
        dirtyChunks.clear(); crossDimensionSizeCache.clear(); anchorChargeMap.clear();
        portalsDirty = false; framesDirty = false;

        if (currDim.equals("minecraft:the_nether") || currDim.equals("minecraft:overworld")) {
            sendMessage("§7Entered " + (currDim.contains("nether") ? "Nether" : "Overworld") + " — 八対一 scanning started");
        }
    }

    private void precomputeFrameBoxes() {
        for (PortalStructure structure : portalStructureMap.values()) {
            if (structure.type != PortalType.NETHER) continue;

            AABB frameBox = null;
            try {
                for (BlockPos p : structure.portalBlocks) {
                    for (Direction d : Direction.values()) {
                        BlockPos n = p.relative(d);
                        if (!structure.portalBlocks.contains(n)) {
                            if (isChunkLoaded(n) && mc.level.getBlockState(n).is(Blocks.OBSIDIAN)) {
                                AABB nb = new AABB(n);
                                frameBox = (frameBox == null) ? nb : frameBox.minmax(nb);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                frameBox = null;
            }

            structure.cachedFrameBox = (frameBox != null) ? frameBox.inflate(0.02) : null;
        }
    }

    private void scanNewChunks(int centerChunkX, int centerChunkZ) {
        int r = range.getValue(), rSq = r * r, scanned = 0;
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
            LevelChunk chunk = mc.level.getChunk(cx, cz);
            scanChunk(chunk);
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

            boolean hasNether = false;
            boolean hasAnchor = false;

            if (differentiatePortalSizes.getValue()) {
                try {
                    hasNether = section.maybeHas(state -> state.is(Blocks.NETHER_PORTAL));
                } catch (Exception e) {
                    hasNether = false;
                }
            }
            if (scanAnchors.getValue()) {
                try {
                    hasAnchor = section.maybeHas(state -> state.is(Blocks.RESPAWN_ANCHOR));
                } catch (Exception e) {
                    hasAnchor = false;
                }
            }

            if (!hasNether && !hasAnchor) continue;

            int sectionMinY = (mc.level.getMinSectionY() + i) * 16;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (hasAnchor && state.is(Blocks.RESPAWN_ANCHOR)) {
                            BlockPos pos = new BlockPos(chunkX + x, sectionMinY + y, chunkZ + z);
                            anchorChargeMap.put(pos, state.getValue(RespawnAnchorBlock.CHARGE) > 0);
                            portals.put(pos, PortalType.RESPAWN_ANCHOR);
                            portalsDirty = true;
                        } else if (hasNether && state.is(Blocks.NETHER_PORTAL)) {
                            BlockPos pos = new BlockPos(chunkX + x, sectionMinY + y, chunkZ + z);
                            portals.put(pos, PortalType.NETHER);
                            portalsDirty = true;
                            processNewDiscovery(pos);
                        }
                    }
                }
            }
        }
    }

    private PortalType classifyBlock(Block block) {
        if (differentiatePortalSizes.getValue() && block == Blocks.NETHER_PORTAL) return PortalType.NETHER;
        if (scanAnchors.getValue() && block == Blocks.RESPAWN_ANCHOR) return PortalType.RESPAWN_ANCHOR;
        return null;
    }

    private void processNewDiscovery(BlockPos pos) {
        if (autoMarkRange.getValue() <= 0 || mc.player == null) return;
        if (pos.distToCenterSqr(mc.player.position()) > (double) autoMarkRange.getValue() * autoMarkRange.getValue()) return;
        if (exclusionTimer > 0 && entryPortalPos != null && pos.distSqr(entryPortalPos) <= ENTRY_EXCLUSION_RADIUS_SQ) return;
        if (createdPortals.add(pos)) portalsDirty = true;
    }

    private void groupPortals() {
        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> active = new HashSet<>();

        List<BlockPos> portalKeys = List.copyOf(portals.keySet());

        for (BlockPos startPos : portalKeys) {
            if (visited.contains(startPos)) continue;
            PortalType type = portals.get(startPos);
            if (type == null) continue;

            if (type == PortalType.RESPAWN_ANCHOR) {
                visited.add(startPos);
                if (onlyShowChargedAnchors.getValue() && !anchorChargeMap.getOrDefault(startPos, false)) continue;
                active.add(startPos);
                portalStructureMap.put(startPos, new PortalStructure(new AABB(startPos).inflate(0.02), Set.of(startPos), false, SizeState.EXIT, type));
                continue;
            }

            Set<BlockPos> component = new HashSet<>();
            Queue<BlockPos> queue = new LinkedList<>();
            AABB structureBox = new AABB(startPos);
            boolean isCreated = false;
            queue.add(startPos); visited.add(startPos);

            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                component.add(current);
                if (createdPortals.contains(current)) isCreated = true;
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.relative(dir);
                    PortalType neighborType = portals.get(neighbor);
                    if (neighborType == type && visited.add(neighbor)) {
                        queue.add(neighbor);
                        structureBox = structureBox.minmax(new AABB(neighbor));
                    }
                }
            }

            BlockPos anchor = componentAnchor(component);
            active.add(anchor);

            SizeState sizeState = SizeState.PENDING;
            String crossKey = lastDimension + ":" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ();
            Boolean crossCached = crossDimensionSizeCache.get(crossKey);
            if (crossCached != null) {
                sizeState = crossCached ? SizeState.EXIT : SizeState.CUSTOM;
            } else if (dimensionChangeCooldown <= 0) {
                Boolean allCorners = checkCorners(component);
                if (allCorners != null) {
                    sizeState = allCorners ? SizeState.EXIT : SizeState.CUSTOM;
                    crossDimensionSizeCache.put(crossKey, allCorners);
                } else {
                    sizeState = SizeState.PENDING;
                }
            }

            boolean wasInMap = portalStructureMap.containsKey(anchor);

            if (isCreated && showCreatedCount.getValue() && !wasInMap) {
                totalCreated++;
                sendMessage("§aCreated Portal #" + totalCreated + (sizeState == SizeState.EXIT ? " §8[Exit]" : " §8[Custom]"));
            }

            PortalStructure structure = new PortalStructure(structureBox.inflate(0.02), component, isCreated, sizeState, type);
            portalStructureMap.put(anchor, structure);
        }

        portalStructureMap.keySet().retainAll(active);
        framesDirty = true;
    }

    private Boolean checkCorners(Set<BlockPos> component) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (BlockPos pos : component) {
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
        }

        BlockPos[] corners = (minX == maxX)
            ? new BlockPos[]{
                new BlockPos(minX, minY - 1, minZ - 1), new BlockPos(minX, minY - 1, maxZ + 1),
                new BlockPos(minX, maxY + 1, minZ - 1), new BlockPos(minX, maxY + 1, maxZ + 1)
              }
            : new BlockPos[]{
                new BlockPos(minX - 1, minY - 1, minZ), new BlockPos(maxX + 1, minY - 1, minZ),
                new BlockPos(minX - 1, maxY + 1, minZ), new BlockPos(maxX + 1, maxY + 1, minZ)
              };

        for (BlockPos c : corners) {
            try {
                if (!isChunkLoaded(c)) return null;
                if (!mc.level.getBlockState(c).is(Blocks.OBSIDIAN)) return false;
            } catch (Exception e) {
                return null;
            }
        }
        return true;
    }

    private boolean isChunkLoaded(BlockPos pos) {
        return mc.level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private BlockPos componentAnchor(Set<BlockPos> comp) {
        BlockPos anchor = null;
        for (BlockPos p : comp) {
            if (anchor == null || p.getY() < anchor.getY() || (p.getY() == anchor.getY() && p.getX() < anchor.getX())) {
                anchor = p;
            }
        }
        return anchor;
    }

    private void cleanupDistantPortals() {
        if (mc.player == null) return;
        double distSq = Math.pow(range.getValue() * 16 + 64, 2);
        boolean removed = false;

        if (portals.entrySet().removeIf(e -> e.getKey().distToCenterSqr(mc.player.position()) > distSq)) {
            portalsDirty = true;
            removed = true;
        }

        if (removed) {
            portalStructureMap.entrySet().removeIf(e ->
                e.getValue().boundingBox.getCenter().distanceToSqr(mc.player.position()) > distSq);
            framesDirty = true;
        }

        int px = mc.player.blockPosition().getX() >> 4, pz = mc.player.blockPosition().getZ() >> 4;
        int rSq = range.getValue() * range.getValue();
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
        PortalType type = classifyBlock(newState.getBlock());
        if (type != null) {
            portals.put(pos.immutable(), type);
            portalsDirty = true;
        } else if (portals.remove(pos) != null) {
            portalsDirty = true;
        }

        if (newState.is(Blocks.OBSIDIAN)) {
            crossDimensionSizeCache.clear();
            portalsDirty = true;
            framesDirty = true;
        }
    }

    @Subscribe
    private void onRender(EventRender3D event) {
        if (mc.player == null || mc.level == null) return;

        IRenderer3D r = event.getRenderer();
        r.begin(event.getMatrixStack());

        double beamDistSq = Math.pow(beamRange.getValue() * 16.0, 2);

        PortalStructure nearest = null;
        if (showBeam.getValue() && onlyNearestBeam.getValue()) {
            double minSq = Double.MAX_VALUE;
            for (PortalStructure structure : portalStructureMap.values()) {
                double sq = mc.player.position().distanceToSqr(structure.boundingBox.getCenter());
                if (sq < minSq) { minSq = sq; nearest = structure; }
            }
        }

        List<PortalStructure> structuresToRender = List.copyOf(portalStructureMap.values());

        for (PortalStructure structure : structuresToRender) {
            BlockPos center = BlockPos.containing(structure.boundingBox.getCenter());
            if (!isChunkLoaded(center)) continue;

            Color color = getStructureColor(structure);
            if (color == null) continue;

            if (highlightStyle.getValue() == HighlightStyle.SPECTRAL) {
                renderSpectral(r, structure, color);
            } else if (highlightStyle.getValue() == HighlightStyle.PULSE) {
                if (highlightFrame.getValue() && structure.type == PortalType.NETHER && structure.cachedFrameBox != null) {
                    renderPulseBox(r, structure.cachedFrameBox, color);
                }
                renderPulseBox(r, structure.boundingBox, color);
            } else {
                if (highlightFrame.getValue() && structure.type == PortalType.NETHER && structure.cachedFrameBox != null) {
                    renderGlowLayers(r, structure.cachedFrameBox, color);
                    drawStyled(r, structure.cachedFrameBox, null, shapeMode.getValue() != BoxMode.Sides ? color.getRGB() : null);
                }
                renderGlowLayers(r, structure.boundingBox, color);
                drawStyled(r, structure.boundingBox, null, shapeMode.getValue() != BoxMode.Sides ? color.getRGB() : null);
            }

            if (showBeam.getValue() && (nearest == null || structure == nearest)
                && mc.player.position().distanceToSqr(structure.boundingBox.getCenter()) <= beamDistSq) {
                Color beamColor = (highlightStyle.getValue() == HighlightStyle.PULSE) ? pulseColor(color) : color;
                renderBeam(r, structure.boundingBox, beamColor);
            }
        }

        r.end();
    }

    // ── Render Helpers ──
    private void db(IRenderer3D r, AABB b, boolean fill, boolean outline, int color) {
        r.drawBox(b.minX, b.minY, b.minZ, b.getXsize(), b.getYsize(), b.getZsize(), fill, outline, color);
    }

    private void drawStyled(IRenderer3D r, AABB box, Integer fillColor, Integer lineColor) {
        if (fillColor != null) db(r, box, true, false, fillColor);
        if (lineColor != null) db(r, box, false, true, lineColor);
    }

    private void renderSpectral(IRenderer3D r, PortalStructure structure, Color color) {
        AABB renderBox = structure.boundingBox.inflate(spectralExpand.getValue());
        db(r, renderBox, true, false, RenderUtils.withAlpha(color, spectralFillAlpha.getValue()));
        db(r, renderBox, false, true, RenderUtils.withAlpha(color, spectralLineAlpha.getValue()));
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

    private void renderBeam(IRenderer3D r, AABB anchorBox, Color color) {
        if (beamStyle.getValue() == BeamStyle.GUARDIAN) renderGuardianBeam(r, anchorBox, color);
        else renderBoxBeam(r, anchorBox, color);
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
        double cx = (anchorBox.minX + anchorBox.maxX) / 2.0, cz = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int worldBot = mc.level.getMinY(), worldTop = worldBot + mc.level.getHeight();
        double radius = Math.max(0.01, guardianRadius.getValue());
        double rotationRad = (System.currentTimeMillis() % 6000L) / 6000.0 * Math.PI * 2.0 * guardianSpinSpeed.getValue();
        for (int i = 0; i < guardianStrands.getValue(); i++) {
            double angle = rotationRad + (Math.PI * 2.0 / guardianStrands.getValue()) * i;
            AABB strandBox = new AABB(
                cx + Math.cos(angle) * radius - 0.01, worldBot, cz + Math.sin(angle) * radius - 0.01,
                cx + Math.cos(angle) * radius + 0.01, worldTop, cz + Math.sin(angle) * radius + 0.01
            );
            db(r, strandBox, true, false, RenderUtils.withAlpha(color, guardianStrandAlpha.getValue() / 2));
            db(r, strandBox, false, true, RenderUtils.withAlpha(color, guardianStrandAlpha.getValue()));
        }
    }

    private Color getStructureColor(PortalStructure structure) {
        if (structure.type == PortalType.RESPAWN_ANCHOR) {
            boolean charged = false;
            for (BlockPos p : structure.portalBlocks) {
                charged = anchorChargeMap.getOrDefault(p, false);
                break;
            }
            if (dynamicColors.getValue()) {
                float hue = ((charged ? 0.13f : 0.65f) + (System.currentTimeMillis() % 3000) / 3000f) % 1f;
                int rgb = Color.HSBtoRGB(hue, 0.8f, 1.0f);
                return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
            }
            return charged ? anchorChargedColor.getValue() : anchorUnchargedColor.getValue();
        }
        if (dynamicColors.getValue()) {
            float hue = ((structure.isFullSize() ? 0.78f : 0.08f) + (System.currentTimeMillis() % 3000) / 3000f) % 1f;
            int rgb = Color.HSBtoRGB(hue, 0.8f, 1.0f);
            return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
        }
        return (differentiatePortalSizes.getValue() && !structure.isFullSize()) ? netherColorCustom.getValue() : netherColorFull.getValue();
    }

    private void sendMessage(String message) {
        long now = System.currentTimeMillis();
        if (now - messageCooldowns.getOrDefault(message, 0L) > MESSAGE_COOLDOWN_MS) {
            sendNotification(NotificationType.INFO, message);
            messageCooldowns.put(message, now);
        }
    }

    // ── Replenish Feature ──
    private void handleReplenish() {
        int selectedSlot = useSelectedSlot.getValue()
            ? mc.player.getInventory().selected
            : targetSlot.getValue() - 1;

        ItemStack targetStack = mc.player.getInventory().getItem(selectedSlot);
        Item targetItem = replenishItem.getValue() == ReplenishItem.Obsidian
            ? Items.OBSIDIAN
            : Items.ENDER_CHEST;

        if (!targetStack.isEmpty() && targetStack.getItem() != targetItem) {
            sendNotification(NotificationType.INFO, "Target slot has a different item — cannot replenish.");
            return;
        }

        int maxCount = targetItem.getDefaultMaxStackSize();
        int currentCount = targetStack.getCount();
        int needed = maxCount - currentCount;

        if (needed <= 0) {
            sendNotification(NotificationType.INFO, "Stack is already full (" + maxCount + ").");
            return;
        }

        for (int i = 9; i < 36 && needed > 0; i++) {
            ItemStack sourceStack = mc.player.getInventory().getItem(i);
            if (sourceStack.isEmpty()) continue;
            if (sourceStack.getItem() != targetItem) continue;

            int available = sourceStack.getCount();
            InvUtils.swapContainerSlots(InvUtils.toContainerSlot(i), 36 + selectedSlot);
            needed -= Math.min(needed, available);
        }

        int finalCount = maxCount - needed;
        String name = new ItemStack(targetItem).getHoverName().getString();

        if (needed > 0) {
            sendNotification(NotificationType.INFO, "Replenished " + name + " to " + finalCount + " (not enough items in inventory).");
        } else {
            sendNotification(NotificationType.INFO, "Replenished " + name + " to " + maxCount + ".");
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }

    public boolean isPortalGuiEnabled() { return isToggled(); }
    public int getTotalPortals() { return (int) portalStructureMap.values().stream().filter(s -> s.type == PortalType.NETHER).count(); }
    public int getTotalAnchors() { return (int) portalStructureMap.values().stream().filter(s -> s.type == PortalType.RESPAWN_ANCHOR).count(); }
    public int getTotalCreated() { return totalCreated; }
    public void markChunkDirty(ChunkPos cp) { scannedChunks.remove(cp); dirtyChunks.add(cp); portalsDirty = true; framesDirty = true; }

    private enum PortalType { NETHER, RESPAWN_ANCHOR }
    private enum SizeState { PENDING, EXIT, CUSTOM }

    private static class PortalStructure {
        final AABB boundingBox;
        final Set<BlockPos> portalBlocks;
        final boolean isCreated;
        final SizeState sizeState;
        final PortalType type;
        AABB cachedFrameBox;

        PortalStructure(AABB bb, Set<BlockPos> pb, boolean ic, SizeState ss, PortalType t) {
            this.boundingBox = bb;
            this.portalBlocks = pb;
            this.isCreated = ic;
            this.sizeState = ss;
            this.type = t;
            this.cachedFrameBox = null;
        }

        boolean isFullSize() { return sizeState == SizeState.EXIT; }
    }
}
