package com.example.addon.modules;

import java.awt.Color;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.addon.Tim;
import com.example.addon.mixin.EndGatewayBlockEntityAccessor;
import com.example.addon.utils.GlowingRegistry;
import com.example.addon.utils.RenderUtils;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.network.EventPacket;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.client.api.setting.ItemListSetting;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class Gatekeeper extends ToggleableModule {

    private static final int CHUNK_SCAN_LIMIT_PER_TICK      = 64;
    private static final int CLEANUP_INTERVAL_TICKS         = 60;
    private static final int DIMENSION_CHANGE_COOLDOWN_TICKS = 40;
    private static final int INTERACT_TIMEOUT_TICKS          = 20;

    public enum RenderMode { GLOW, SPECTRAL, PULSE }
    public enum BeamStyle { BOX, GUARDIAN }
    public enum BoxMode { Both, Sides, Lines }
    public enum TargetType { CONTAINER }

    public enum AlertSound {
        ENDER_DRAGON_GROWL("Dragon Growl"),
        SHULKER_TELEPORT("Shulker Teleport"),
        LEVEL_UP("Level Up"),
        EXPERIENCE_ORB("Experience Orb"),
        BELL("Bell");

        private final String displayName;
        AlertSound(String displayName) { this.displayName = displayName; }
        @Override public String toString() { return displayName; }
    }

    // ── State ──
    private final Map<BlockPos, PortalType> portals = new ConcurrentHashMap<>();
    private final Map<BlockPos, PortalStructure> portalStructureMap = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final Set<ChunkPos> dirtyChunks = new HashSet<>();
    private final Set<String> notifiedStructures = new HashSet<>();
    private boolean portalsDirty = false;
    private int cleanupTimer = 0;

    private final Map<BlockPos, TargetType> targets = new ConcurrentHashMap<>();
    private final Set<ChunkPos> eaScannedChunks = new HashSet<>();
    private final Set<BlockPos> checkedContainers = new HashSet<>();
    private final Set<Integer> notifiedShulkers = new HashSet<>();
    private final Set<Integer> notifiedElytras = new HashSet<>();
    private int levitationWarnTimer = 0;
    private int totalElytrasFound = 0;
    private boolean wasAutoOpened = false;
    private int interactTimeoutTimer = 0;
    private int drinkTimer = 0;
    private int previousDrinkSlot = -1;
    private boolean hasAlertedForCurrentScreen = false;
    private String lastDimension = "";
    private int dimensionChangeCooldown = 0;
    private final List<ItemFrame> elytraFrameTargets = new java.util.ArrayList<>();
    private final List<Shulker> shulkerTargets = new java.util.ArrayList<>();
    private final List<ShulkerBullet> bulletTargets = new java.util.ArrayList<>();

    // ── Toggles ──
    private final BooleanSetting scanEndPortals = new BooleanSetting("end-portals", "Scan End portal blocks.", true)
        .onChange((Runnable) () -> portalsDirty = true);
    private final BooleanSetting scanEndGateways = new BooleanSetting("end-gateways", "Scan End gateways.", true)
        .onChange((Runnable) () -> portalsDirty = true);

    // ── General ──
    private final NumberSetting<Integer> range = new NumberSetting<>("range", "Detection range in chunks for all features.", 16, 1, 128);
    private final BooleanSetting enableEndAssistant = new BooleanSetting("end-assistant", "Master toggle for all End Assistant features.", true);

    // ── End Dimension ──
    private final ColorSetting endPortalColor = new ColorSetting("end-portal-color", "End portal color.", new Color(0, 255, 128, 255)).setVisibility(scanEndPortals::getValue);
    private final ColorSetting endGatewayColor = new ColorSetting("end-gateway-color", "End gateway color.", new Color(255, 0, 255, 255)).setVisibility(scanEndGateways::getValue);

    // ── Render ──
    private final EnumSetting<BoxMode> shapeMode = new EnumSetting<>("shape-mode", "Box render mode.", BoxMode.Both);
    private final EnumSetting<RenderMode> renderMode = new EnumSetting<>("render-mode", "GLOW = layered bloom boxes. SPECTRAL = outline shader. PULSE = fading highlight.", RenderMode.GLOW);
    private final BooleanSetting dynamicColors = new BooleanSetting("dynamic-colors", "Cycle colors over time.", false);
    private final NumberSetting<Integer> glowLayers = new NumberSetting<>("glow-layers", "Number of bloom layers rendered around each target.", 4, 1, 8)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW || renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Double> glowSpread = new NumberSetting<>("glow-spread", "How far each bloom layer expands outward (in blocks).", 0.04, 0.01, 0.15)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW || renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> glowBaseAlpha = new NumberSetting<>("glow-base-alpha", "Alpha of the innermost glow layer (0-255).", 60, 10, 150)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW);
    private final NumberSetting<Integer> spectralFillAlpha = new NumberSetting<>("spectral-fill-alpha", "Fill alpha for block targets in SPECTRAL mode.", 30, 0, 120)
        .setVisibility(() -> renderMode.getValue() == RenderMode.SPECTRAL);
    private final NumberSetting<Double> pulseSpeed = new NumberSetting<>("pulse-speed", "Pulse cycle speed. 1.0 = one full fade in/out per second.", 1.0, 0.1, 5.0)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> pulseMinAlpha = new NumberSetting<>("pulse-min-alpha", "Lowest alpha reached during the pulse (0 = invisible).", 15, 0, 255)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> pulseMaxAlpha = new NumberSetting<>("pulse-max-alpha", "Peak alpha reached during the pulse.", 220, 15, 255)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);

    // ── Beam ──
    private final BooleanSetting showBeam = new BooleanSetting("show-beam", "Render a vertical beam at each structure.", true);
    private final NumberSetting<Integer> beamRange = new NumberSetting<>("beam-range", "Maximum horizontal distance (in chunks) to render the vertical beam.", 16, 1, 64)
        .setVisibility(showBeam::getValue);
    private final BooleanSetting onlyNearestBeam = new BooleanSetting("nearest-beam", "Only render the beam for the portal closest to the player.", false)
        .setVisibility(showBeam::getValue);
    private final EnumSetting<BeamStyle> beamStyle = new EnumSetting<>("beam-style", "Beam style.", BeamStyle.GUARDIAN).setVisibility(showBeam::getValue);
    private final NumberSetting<Integer> beamWidth = new NumberSetting<>("beam-width", "Width of the box-style beam.", 15, 1, 100)
        .setVisibility(() -> showBeam.getValue() && beamStyle.getValue() == BeamStyle.BOX);
    private final NumberSetting<Double> guardianRadius = new NumberSetting<>("guardian-radius", "Radius of guardian-style beam strands.", 0.08, 0.01, 1.0)
        .setVisibility(() -> showBeam.getValue() && beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Integer> guardianStrands = new NumberSetting<>("guardian-strands", "Number of rotating strands.", 4, 1, 16)
        .setVisibility(() -> showBeam.getValue() && beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Double> guardianSpinSpeed = new NumberSetting<>("guardian-spin-speed", "Rotation speed of strands.", 1.0, 0.1, 5.0)
        .setVisibility(() -> showBeam.getValue() && beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Integer> guardianCoreAlpha = new NumberSetting<>("guardian-core-alpha", "Alpha of the beam center.", 90, 4, 255)
        .setVisibility(() -> showBeam.getValue() && beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Integer> guardianStrandAlpha = new NumberSetting<>("guardian-strand-alpha", "Alpha of the rotating strands.", 160, 4, 255)
        .setVisibility(() -> showBeam.getValue() && beamStyle.getValue() == BeamStyle.GUARDIAN);

    // ── End Assistant ──
    private final NumberSetting<Integer> cityYLevel = new NumberSetting<>("city-y-level", "Minimum Y level to scan. End Cities generate above Y = 0.", 0, -64, 320)
        .setVisibility(enableEndAssistant::getValue);
    private final BooleanSetting trackContainers = new BooleanSetting("track-chests", "Highlight standard chests.", true).setVisibility(enableEndAssistant::getValue);
    private final ColorSetting containerColor = new ColorSetting("chest-color", "Color for standard chests.", new Color(255, 215, 0, 255))
        .setVisibility(() -> enableEndAssistant.getValue() && trackContainers.getValue());
    private final ItemListSetting containerWhitelist = new ItemListSetting("chest-whitelist", "Items to alert you about when opening Chests.",
        Items.NETHERITE_BLOCK, Items.NETHERITE_INGOT, Items.DIAMOND,
        Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE,
        Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
        Items.ENDER_CHEST, Items.ENCHANTED_GOLDEN_APPLE, Items.ELYTRA,
        Items.NETHERITE_SWORD, Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE,
        Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
        Items.SHULKER_BOX, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
        Items.SHULKER_SHELL, Items.DRAGON_BREATH, Items.END_CRYSTAL, Items.CHORUS_FRUIT);
    private final BooleanSetting trackElytras = new BooleanSetting("elytra-frames", "Highlights Item Frames holding an Elytra.", true).setVisibility(enableEndAssistant::getValue);
    private final ColorSetting elytraColor = new ColorSetting("elytra-color", "Color for Elytra Item Frames.", new Color(255, 255, 0, 255))
        .setVisibility(() -> enableEndAssistant.getValue() && trackElytras.getValue());
    private final BooleanSetting trackShulkers = new BooleanSetting("shulkers", "Highlights Shulkers and Shulker Bullets.", true).setVisibility(enableEndAssistant::getValue);
    private final ColorSetting shulkerColor = new ColorSetting("shulker-color", "Color for Shulkers and Shulker Bullets.", new Color(255, 0, 255, 255))
        .setVisibility(() -> enableEndAssistant.getValue() && trackShulkers.getValue());
    private final BooleanSetting enableAlerts = new BooleanSetting("alerts", "Master toggle for audio cues and loot announcements.", true).setVisibility(enableEndAssistant::getValue);
    private final EnumSetting<AlertSound> alertSound = new EnumSetting<>("alert-sound", "Which sound to play for module alerts.", AlertSound.ENDER_DRAGON_GROWL)
        .setVisibility(() -> enableEndAssistant.getValue() && enableAlerts.getValue());
    private final NumberSetting<Double> alertVolume = new NumberSetting<>("alert-volume", "Volume of the alert sound.", 1.0, 0.0, 5.0)
        .setVisibility(() -> enableEndAssistant.getValue() && enableAlerts.getValue());
    private final BooleanSetting autoOpenChests = new BooleanSetting("auto-open-chests", "Automatically opens nearby chests.", true).setVisibility(enableEndAssistant::getValue);
    private final BooleanSetting autoMilkLevitation = new BooleanSetting("auto-milk-levitation", "Automatically drinks milk to clear the Levitation effect.", false).setVisibility(enableEndAssistant::getValue);
    private final BooleanSetting disconnectOnPlayer = new BooleanSetting("disconnect-on-player", "Instantly disconnects from the server if another player enters render distance.", false).setVisibility(enableEndAssistant::getValue);
    private final BooleanSetting autoDisableOnLowHealth = new BooleanSetting("auto-disable-on-low-health", "Disables the module if health is critical.", true).setVisibility(enableEndAssistant::getValue);

    public Gatekeeper() {
        super("gatekeeper", "Advanced End gateway and End portal tracking with integrated End Assistant.", Tim.CATEGORY);
        this.registerSettings(
            scanEndPortals, scanEndGateways, range, enableEndAssistant,
            endPortalColor, endGatewayColor,
            shapeMode, renderMode, dynamicColors, glowLayers, glowSpread, glowBaseAlpha,
            spectralFillAlpha, pulseSpeed, pulseMinAlpha, pulseMaxAlpha,
            showBeam, beamRange, onlyNearestBeam, beamStyle, beamWidth,
            guardianRadius, guardianStrands, guardianSpinSpeed, guardianCoreAlpha, guardianStrandAlpha,
            cityYLevel, trackContainers, containerColor, containerWhitelist,
            trackElytras, elytraColor, trackShulkers, shulkerColor,
            enableAlerts, alertSound, alertVolume, autoOpenChests, autoMilkLevitation,
            disconnectOnPlayer, autoDisableOnLowHealth
        );
    }

    // ── Lifecycle ──
    @Override
    public void onEnable() {
        clearAllState();
        targets.clear();
        eaScannedChunks.clear();
        checkedContainers.clear();
        notifiedShulkers.clear();
        notifiedElytras.clear();
        levitationWarnTimer = 0;
        totalElytrasFound = 0;
        drinkTimer = 0;
        previousDrinkSlot = -1;
        hasAlertedForCurrentScreen = false;
        GlowingRegistry.clear();
    }

    @Override
    public void onDisable() {
        clearAllState();
        if (drinkTimer > 0) {
            if (mc.options != null) mc.options.keyUse.setDown(false);
            if (previousDrinkSlot != -1 && mc.player != null) {
                mc.player.getInventory().selected = previousDrinkSlot;
            }
        }
        GlowingRegistry.clear();
        targets.clear();
    }

    private void clearAllState() {
        portals.clear(); portalStructureMap.clear(); scannedChunks.clear(); dirtyChunks.clear();
        notifiedStructures.clear(); portalsDirty = false;
    }

    // ── Event Handlers ──
    @Subscribe
    private void onTick(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        if (!dirtyChunks.isEmpty()) { scannedChunks.removeAll(dirtyChunks); dirtyChunks.clear(); }
        BlockPos p = mc.player.blockPosition();
        scanNewChunks(p.getX() >> 4, p.getZ() >> 4);
        if (portalsDirty) { portalsDirty = false; groupPortals(); }

        if (++cleanupTimer >= CLEANUP_INTERVAL_TICKS) {
            cleanupTimer = 0;
            cleanupDistantPortals();
        }

        if (enableEndAssistant.getValue()) {
            if (eaPerformSafetyChecks()) return;
            eaCheckForPlayers();
            eaCheckLevitationEffect();
            eaUpdateContainerLogic();
            eaCheckOpenedContainerLoot();
            eaUpdateMilkDrink();
            eaUpdateScanningLogic();
        }
    }

    @Subscribe
    private void onPacket(EventPacket.Receive event) {
        if (mc.level == null) return;
        if (event.getPacket() instanceof ClientboundBlockUpdatePacket pkt) {
            handleBlockChange(pkt.getPos(), pkt.getBlockState());
        } else if (event.getPacket() instanceof ClientboundSectionBlocksUpdatePacket pkt) {
            pkt.runUpdates(this::handleBlockChange);
        }
    }

    private void handleBlockChange(BlockPos pos, BlockState newState) {
        PortalType type = newState.is(Blocks.END_GATEWAY) ? PortalType.END_GATEWAY
            : newState.is(Blocks.END_PORTAL) ? PortalType.END_PORTAL : null;
        if (type != null) { portals.put(pos.immutable(), type); portalsDirty = true; }
        else if (portals.remove(pos) != null) portalsDirty = true;
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

        for (PortalStructure structure : portalStructureMap.values()) {
            Color color = getStructureColor(structure);
            if (color == null) continue;
            if (renderMode.getValue() == RenderMode.SPECTRAL) renderSpectral(r, structure, color);
            else if (renderMode.getValue() == RenderMode.PULSE) {
                renderPulseBox(r, structure.boundingBox, color);
            } else {
                renderGlowLayers(r, structure.boundingBox, color);
                drawStyled(r, structure.boundingBox, null, shapeMode.getValue() != BoxMode.Sides ? color.getRGB() : null);
            }
            if (showBeam.getValue() && (nearest == null || structure == nearest)
                && mc.player.position().distanceToSqr(structure.boundingBox.getCenter()) <= beamDistSq) {
                Color beamColor = (renderMode.getValue() == RenderMode.PULSE) ? pulseColor(color) : color;
                renderBeam(r, structure.boundingBox, beamColor);
            }
        }

        if (enableEndAssistant.getValue()) {
            boolean isSpectral = renderMode.getValue() == RenderMode.SPECTRAL;
            boolean isPulse = renderMode.getValue() == RenderMode.PULSE;
            Set<BlockPos> toRemove = new HashSet<>();

            for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
                BlockPos pos = entry.getKey();
                TargetType type = entry.getValue();

                if (!isChunkLoaded(pos)) continue;
                if (mc.level.getBlockState(pos).isAir()) { toRemove.add(pos); continue; }

                Block currentBlock = mc.level.getBlockState(pos).getBlock();
                if (!eaValidateBlockType(currentBlock, type)) { toRemove.add(pos); continue; }

                AABB renderBox = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
                Color color = eaGetColor(type);
                if (color == null) continue;

                if (isSpectral) {
                    db(r, renderBox, true, false, RenderUtils.withAlpha(color, spectralFillAlpha.getValue()));
                } else if (isPulse) {
                    renderPulseBox(r, renderBox, color);
                } else {
                    renderGlowLayers(r, renderBox, color);
                    db(r, renderBox, false, true, color.getRGB());
                }
            }

            for (BlockPos pos : toRemove) targets.remove(pos);

            eaRenderEntity(r, isSpectral, isPulse, trackElytras.getValue(), elytraFrameTargets, elytraColor.getValue());
            eaRenderEntity(r, isSpectral, isPulse, trackShulkers.getValue(), shulkerTargets, shulkerColor.getValue());
            eaRenderEntity(r, isSpectral, isPulse, trackShulkers.getValue(), bulletTargets, shulkerColor.getValue());
        }

        r.end();
    }

    // ── Gatekeeper Core Logic ──
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

            boolean hasPortal = scanEndPortals.getValue() && section.maybeHas(state -> state.is(Blocks.END_PORTAL));
            boolean hasGateway = scanEndGateways.getValue() && section.maybeHas(state -> state.is(Blocks.END_GATEWAY));
            if (!hasPortal && !hasGateway) continue;

            int sectionMinY = (mc.level.getMinSectionY() + i) * 16;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (hasPortal && state.is(Blocks.END_PORTAL)) {
                            BlockPos pos = new BlockPos(chunkX + x, sectionMinY + y, chunkZ + z);
                            if (!portals.containsKey(pos)) { portals.put(pos, PortalType.END_PORTAL); portalsDirty = true; }
                        } else if (hasGateway && state.is(Blocks.END_GATEWAY)) {
                            BlockPos pos = new BlockPos(chunkX + x, sectionMinY + y, chunkZ + z);
                            if (!portals.containsKey(pos)) { portals.put(pos, PortalType.END_GATEWAY); portalsDirty = true; }
                        }
                    }
                }
            }
        }
    }

    private void groupPortals() {
        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> active = new HashSet<>();

        for (BlockPos startPos : portals.keySet()) {
            if (visited.contains(startPos)) continue;
            PortalType type = portals.get(startPos);
            Set<BlockPos> component = new HashSet<>();
            Queue<BlockPos> queue = new LinkedList<>();
            AABB structureBox = new AABB(startPos);
            queue.add(startPos); visited.add(startPos);
            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                component.add(current);
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.relative(dir);
                    if (portals.get(neighbor) == type && visited.add(neighbor)) {
                        queue.add(neighbor); structureBox = structureBox.minmax(new AABB(neighbor));
                    }
                }
            }
            BlockPos anchor = componentAnchor(component);
            active.add(anchor);
            BlockPos dest = null;
            if (type == PortalType.END_GATEWAY) {
                BlockEntity be = mc.level.getBlockEntity(anchor);
                if (be instanceof TheEndGatewayBlockEntity gateway) {
                    dest = ((EndGatewayBlockEntityAccessor) gateway).getExitPortalPos();
                }
            }
            portalStructureMap.put(anchor, new PortalStructure(structureBox.inflate(0.02), component, type, dest));
            if (type == PortalType.END_GATEWAY) notifyGateway(anchor, dest);
        }
        portalStructureMap.keySet().retainAll(active);
    }

    private void cleanupDistantPortals() {
        if (mc.player == null) return;
        double distSq = Math.pow(range.getValue() * 16 + 64, 2);
        if (portals.entrySet().removeIf(e -> e.getKey().distToCenterSqr(mc.player.position()) > distSq)) portalsDirty = true;

        int px = mc.player.blockPosition().getX() >> 4, pz = mc.player.blockPosition().getZ() >> 4;
        int rSq = range.getValue() * range.getValue();
        scannedChunks.removeIf(cp -> (cp.x - px) * (cp.x - px) + (cp.z - pz) * (cp.z - pz) > rSq);
    }

    private void notifyGateway(BlockPos pos, BlockPos dest) {
        String id = "GW_" + pos.toShortString();
        if (!notifiedStructures.add(id)) return;
        sendNotification(NotificationType.INFO, "§dEnd Gateway §7detected");
    }

    private BlockPos componentAnchor(Set<BlockPos> comp) {
        BlockPos anchor = null;
        for (BlockPos p : comp) if (anchor == null || p.getY() < anchor.getY() || (p.getY() == anchor.getY() && p.getX() < anchor.getX())) anchor = p;
        return anchor;
    }

    private boolean isChunkLoaded(BlockPos pos) {
        return mc.level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
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
        AABB b = structure.boundingBox.inflate(0.05);
        db(r, b, true, false, RenderUtils.withAlpha(color, spectralFillAlpha.getValue()));
        db(r, b, false, true, RenderUtils.withAlpha(color, 255));
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
        double beamSize = beamWidth.getValue() / 100.0;
        double centerX = (anchorBox.minX + anchorBox.maxX) / 2.0, centerZ = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int worldBot = mc.level.getMinY(), worldTop = worldBot + mc.level.getHeight();
        AABB beamBox = new AABB(centerX - beamSize, worldBot, centerZ - beamSize, centerX + beamSize, worldTop, centerZ + beamSize);
        renderGlowLayers(r, beamBox, color);
        db(r, beamBox, true, false, RenderUtils.withAlpha(color, 60));
        db(r, beamBox, false, true, color.getRGB());
    }

    private void renderGuardianBeam(IRenderer3D r, AABB anchorBox, Color color) {
        double cx = (anchorBox.minX + anchorBox.maxX) / 2.0, cz = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int worldBot = mc.level.getMinY(), worldTop = worldBot + mc.level.getHeight();
        double radius = guardianRadius.getValue();
        double rotationRad = (System.currentTimeMillis() % 6000L) / 6000.0 * Math.PI * 2.0 * guardianSpinSpeed.getValue();
        for (int i = 0; i < guardianStrands.getValue(); i++) {
            double angle = rotationRad + (Math.PI * 2.0 / guardianStrands.getValue()) * i;
            AABB strandBox = new AABB(cx + Math.cos(angle) * radius - 0.01, worldBot, cz + Math.sin(angle) * radius - 0.01,
                cx + Math.cos(angle) * radius + 0.01, worldTop, cz + Math.sin(angle) * radius + 0.01);
            db(r, strandBox, true, false, RenderUtils.withAlpha(color, guardianStrandAlpha.getValue() / 2));
            db(r, strandBox, false, true, RenderUtils.withAlpha(color, guardianStrandAlpha.getValue()));
        }
    }

    private Color getStructureColor(PortalStructure structure) {
        if (dynamicColors.getValue()) {
            float hue = ((structure.type == PortalType.END_PORTAL ? 0.333f : 0.667f) + (System.currentTimeMillis() % 3000) / 3000f) % 1f;
            int rgb = Color.HSBtoRGB(hue, 0.8f, 1.0f);
            return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
        }
        return structure.type == PortalType.END_PORTAL ? endPortalColor.getValue() : endGatewayColor.getValue();
    }

    // ── End Assistant Logic ──
    private void eaUpdateScanningLogic() {
        if (dimensionChangeCooldown > 0) { dimensionChangeCooldown--; return; }

        String currDim = mc.level.dimension().location().toString();
        if (!currDim.equals(lastDimension)) {
            dimensionChangeCooldown = DIMENSION_CHANGE_COOLDOWN_TICKS;
            lastDimension = currDim;
            targets.clear();
            eaScannedChunks.clear();
            GlowingRegistry.clear();
            return;
        }

        BlockPos playerPos = mc.player.blockPosition();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;

        eaCleanupDistantTargets(playerPos);
        eaScanElytraFrames();
        eaScanShulkers();
        eaScanShulkerBullets();
        eaPruneBlockTargets();
        eaScanNewChunks(centerChunkX, centerChunkZ);
    }

    private void eaScanElytraFrames() {
        elytraFrameTargets.clear();
        if (!trackElytras.getValue()) return;

        int blockRange = range.getValue() * 16;
        AABB searchBox = new AABB(mc.player.blockPosition()).inflate(blockRange);
        Set<Integer> currentIds = new HashSet<>();

        for (ItemFrame frame : mc.level.getEntitiesOfClass(ItemFrame.class, searchBox, e -> true)) {
            if (frame.getItem().is(Items.ELYTRA)) {
                elytraFrameTargets.add(frame);
                currentIds.add(frame.getId());

                if (renderMode.getValue() == RenderMode.SPECTRAL) {
                    GlowingRegistry.add(frame.getId(), elytraColor.getValue().getRGB());
                } else {
                    GlowingRegistry.remove(frame.getId());
                }

                if (notifiedElytras.add(frame.getId())) {
                    totalElytrasFound++;
                    if (enableAlerts.getValue()) {
                        sendNotification(NotificationType.INFO, "§e§lELYTRA FOUND! §aItem frame detected.");
                        eaPlayAlert();
                    }
                }
            }
        }
        notifiedElytras.retainAll(currentIds);
    }

    private void eaScanShulkers() {
        shulkerTargets.clear();
        if (!trackShulkers.getValue()) return;

        int blockRange = range.getValue() * 16;
        AABB searchBox = new AABB(mc.player.blockPosition()).inflate(blockRange);
        Set<Integer> currentIds = new HashSet<>();

        for (Shulker shulker : mc.level.getEntitiesOfClass(Shulker.class, searchBox, e -> true)) {
            shulkerTargets.add(shulker);
            currentIds.add(shulker.getId());

            if (renderMode.getValue() == RenderMode.SPECTRAL) {
                GlowingRegistry.add(shulker.getId(), shulkerColor.getValue().getRGB());
            } else {
                GlowingRegistry.remove(shulker.getId());
            }

            if (notifiedShulkers.add(shulker.getId())) {
                if (enableAlerts.getValue()) {
                    sendNotification(NotificationType.INFO, "§dShulker Detected!");
                    eaPlayAlert();
                }
            }
        }
        notifiedShulkers.retainAll(currentIds);
    }

    private void eaScanShulkerBullets() {
        bulletTargets.clear();
        if (!trackShulkers.getValue()) return;

        int blockRange = range.getValue() * 16;
        AABB searchBox = new AABB(mc.player.blockPosition()).inflate(blockRange);

        for (ShulkerBullet bullet : mc.level.getEntitiesOfClass(ShulkerBullet.class, searchBox, e -> true)) {
            bulletTargets.add(bullet);
            if (renderMode.getValue() == RenderMode.SPECTRAL) {
                GlowingRegistry.add(bullet.getId(), shulkerColor.getValue().getRGB());
            }
        }
    }

    private void eaScanNewChunks(int centerChunkX, int centerChunkZ) {
        int r = range.getValue();
        int rSq = r * r;

        eaScannedChunks.removeIf(cp -> {
            int dx = cp.x - centerChunkX;
            int dz = cp.z - centerChunkZ;
            return dx * dx + dz * dz > rSq;
        });

        int chunksScanned = 0;
        int limit = 10;

        outer:
        for (int d = 0; d <= r; d++) {
            int minX = -d, maxX = d, minZ = -d, maxZ = d;

            for (int x = minX; x <= maxX; x++) {
                if (eaProcessChunk(centerChunkX + x, centerChunkZ + minZ, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                if (chunksScanned >= limit) break outer;
                if (minZ != maxZ) {
                    if (eaProcessChunk(centerChunkX + x, centerChunkZ + maxZ, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                    if (chunksScanned >= limit) break outer;
                }
            }

            for (int z = minZ + 1; z < maxZ; z++) {
                if (eaProcessChunk(centerChunkX + minX, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                if (chunksScanned >= limit) break outer;
                if (minX != maxX) {
                    if (eaProcessChunk(centerChunkX + maxX, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                    if (chunksScanned >= limit) break outer;
                }
            }
        }
    }

    private boolean eaProcessChunk(int cx, int cz, int rSq, int centerChunkX, int centerChunkZ) {
        int dx = cx - centerChunkX, dz = cz - centerChunkZ;
        if (dx * dx + dz * dz > rSq) return false;

        ChunkPos cp = new ChunkPos(cx, cz);
        if (eaScannedChunks.contains(cp)) return false;
        if (!mc.level.getChunkSource().hasChunk(cx, cz)) return false;

        LevelChunk chunk = mc.level.getChunk(cx, cz);
        eaScanBlockEntitiesInChunk(chunk);
        eaScannedChunks.add(cp);
        return true;
    }

    private void eaScanBlockEntitiesInChunk(LevelChunk chunk) {
        int minY = cityYLevel.getValue();

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            BlockPos pos = be.getBlockPos();
            if (pos.getY() < minY) continue;

            if (be instanceof ChestBlockEntity) {
                targets.put(pos, TargetType.CONTAINER);
            }
        }
    }

    private void eaUpdateContainerLogic() {
        if (!autoOpenChests.getValue()) return;

        if (interactTimeoutTimer > 0) interactTimeoutTimer--;

        if (mc.screen == null && !wasAutoOpened) {
            List<BlockPos> nearbyChests = targets.entrySet().stream()
                .filter(e -> e.getValue() == TargetType.CONTAINER)
                .map(Map.Entry::getKey)
                .filter(pos -> !checkedContainers.contains(pos))
                .filter(pos -> Math.sqrt(pos.distToCenterSqr(mc.player.position())) <= 4.5)
                .sorted(Comparator.comparingDouble(pos -> pos.distToCenterSqr(mc.player.position())))
                .toList();

            if (!nearbyChests.isEmpty()) {
                BlockPos pos = nearbyChests.get(0);
                checkedContainers.add(pos);
                wasAutoOpened = true;
                interactTimeoutTimer = INTERACT_TIMEOUT_TICKS;

                RusherHackAPI.getRotationManager().updateRotation(pos);
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        } else if (mc.screen == null && wasAutoOpened && interactTimeoutTimer == 0) {
            wasAutoOpened = false;
        }
    }

    private void eaCheckOpenedContainerLoot() {
        if (mc.screen instanceof AbstractContainerScreen<?> screen && !(mc.screen instanceof InventoryScreen)) {
            if (mc.screen instanceof ShulkerBoxScreen || screen.getTitle().getString().equals(Component.translatable("container.enderchest").getString())) {
                hasAlertedForCurrentScreen = true;
                return;
            }

            if (!hasAlertedForCurrentScreen) {
                for (int i = 0; i < screen.getMenu().slots.size(); i++) {
                    Slot slot = screen.getMenu().slots.get(i);
                    if (slot.container instanceof Inventory) continue;

                    ItemStack stack = slot.getItem();
                    if (!stack.isEmpty() && containerWhitelist.getList().contains(stack.getItem())) {
                        sendNotification(NotificationType.INFO, "§cRare loot found in chest: §e" + stack.getHoverName().getString() + "§c!");
                        eaPlayAlert();
                        hasAlertedForCurrentScreen = true;
                        break;
                    }
                }
            }
        } else {
            hasAlertedForCurrentScreen = false;
        }
    }

    private void eaUpdateMilkDrink() {
        if (!autoMilkLevitation.getValue()) {
            if (drinkTimer > 0) {
                mc.options.keyUse.setDown(false);
                if (previousDrinkSlot != -1 && mc.player != null) {
                    mc.player.getInventory().selected = previousDrinkSlot;
                    previousDrinkSlot = -1;
                }
                drinkTimer = 0;
            }
            return;
        }

        boolean hasLevitation = mc.player.hasEffect(MobEffects.LEVITATION);

        if (drinkTimer == 0 && hasLevitation && mc.screen == null) {
            int milkSlot = eaFindMilkBucket();
            if (milkSlot != -1) {
                previousDrinkSlot = mc.player.getInventory().selected;
                mc.player.getInventory().selected = milkSlot;
                mc.options.keyUse.setDown(true);
                drinkTimer = 32;
            }
        } else if (drinkTimer > 0) {
            drinkTimer--;
            if (!hasLevitation || drinkTimer == 0 || mc.player.getInventory().getItem(mc.player.getInventory().selected).getItem() != Items.MILK_BUCKET) {
                mc.options.keyUse.setDown(false);
                if (previousDrinkSlot != -1) {
                    mc.player.getInventory().selected = previousDrinkSlot;
                    previousDrinkSlot = -1;
                }
                drinkTimer = 0;
            }
        }
    }

    private int eaFindMilkBucket() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.MILK_BUCKET)) return i;
        }
        return -1;
    }

    private void eaCheckLevitationEffect() {
        if (!enableAlerts.getValue()) return;

        if (levitationWarnTimer > 0) {
            levitationWarnTimer--;
            return;
        }

        if (mc.player.hasEffect(MobEffects.LEVITATION)) {
            sendNotification(NotificationType.WARNING, "Levitation effect applied! Watch your altitude.");
            eaPlayAlert();
            levitationWarnTimer = 200;
        }
    }

    private void eaCheckForPlayers() {
        if (!disconnectOnPlayer.getValue()) return;
        for (Player player : mc.level.players()) {
            if (player == mc.player || player.isSpectator()) continue;
            if (player.distanceTo(mc.player) < 128) {
                sendNotification(NotificationType.ERROR, "§cPlayer detected in render distance! Disconnecting...");
                if (mc.player.connection != null) {
                    mc.player.connection.getConnection().disconnect(Component.literal("Player detected in render distance"));
                }
                return;
            }
        }
    }

    private void eaPlayAlert() {
        if (mc.player == null) return;
        SoundEvent sound = switch (alertSound.getValue()) {
            case LEVEL_UP -> SoundEvents.PLAYER_LEVELUP;
            case SHULKER_TELEPORT -> SoundEvents.SHULKER_TELEPORT;
            case EXPERIENCE_ORB -> SoundEvents.EXPERIENCE_ORB_PICKUP;
            case BELL -> SoundEvents.BELL_BLOCK;
            case ENDER_DRAGON_GROWL -> SoundEvents.ENDER_DRAGON_GROWL;
        };
        mc.player.playSound(sound, alertVolume.getValue().floatValue(), 1.0f);
    }

    private void eaRenderEntity(IRenderer3D r, boolean isSpectral, boolean isPulse, boolean isEnabled, List<? extends Entity> entities, Color color) {
        if (!isEnabled || entities.isEmpty()) return;

        for (Entity entity : entities) {
            if (!entity.isAlive()) continue;
            AABB box = entity.getBoundingBox();

            if (isSpectral) {
                db(r, box, false, true, RenderUtils.withAlpha(color, 200));
            } else if (isPulse) {
                renderPulseBox(r, box, color);
            } else {
                renderGlowLayers(r, box, color);
                db(r, box, false, true, color.getRGB());
            }
        }
    }

    private boolean eaValidateBlockType(Block block, TargetType type) {
        return switch (type) {
            case CONTAINER -> block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL;
        };
    }

    private Color eaGetColor(TargetType type) {
        return switch (type) {
            case CONTAINER -> trackContainers.getValue() ? containerColor.getValue() : null;
        };
    }

    private void eaPruneBlockTargets() {
        if (mc.level == null || mc.player == null) return;
        Set<BlockPos> toRemove = new HashSet<>();
        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            BlockPos pos = entry.getKey();
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;

            if (mc.level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                Block currentBlock = mc.level.getBlockState(pos).getBlock();
                if (mc.level.getBlockState(pos).isAir() || !eaValidateBlockType(currentBlock, entry.getValue())) {
                    toRemove.add(pos);
                }
            } else {
                toRemove.add(pos);
                eaScannedChunks.remove(new ChunkPos(chunkX, chunkZ));
            }
        }
        for (BlockPos pos : toRemove) targets.remove(pos);
    }

    private void eaCleanupDistantTargets(BlockPos playerPos) {
        int r = range.getValue();
        int pChunkX = playerPos.getX() >> 4;
        int pChunkZ = playerPos.getZ() >> 4;

        targets.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            int dx = (pos.getX() >> 4) - pChunkX;
            int dz = (pos.getZ() >> 4) - pChunkZ;
            if (dx * dx + dz * dz > r * r) {
                eaScannedChunks.remove(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
                return true;
            }
            return false;
        });
    }

    private boolean eaPerformSafetyChecks() {
        if (!autoDisableOnLowHealth.getValue()) return false;
        boolean hasTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
            || mc.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
        if (hasTotem && mc.player.getHealth() <= 6) {
            sendNotification(NotificationType.ERROR, "Health is critical, disabling to prevent totem pop.");
            toggle();
            return true;
        }
        return false;
    }

    // ── Public API ──
    public void markChunkDirty(ChunkPos cp) { scannedChunks.remove(cp); dirtyChunks.add(cp); portalsDirty = true; }
    public int getTotalEndPortals() { return (int) portalStructureMap.values().stream().filter(s -> s.type == PortalType.END_PORTAL).count(); }
    public int getTotalGateways()   { return (int) portalStructureMap.values().stream().filter(s -> s.type == PortalType.END_GATEWAY).count(); }
    public int getTotalElytrasFound() { return totalElytrasFound; }
    public int getElytrasNearby() { return elytraFrameTargets.size(); }
    public int getShulkersNearby() { return shulkerTargets.size(); }
    public int getChestsNearby() {
        int n = 0;
        for (TargetType t : targets.values()) if (t == TargetType.CONTAINER) n++;
        return n;
    }
    // TODO: getEndAssistantStats() returning List<EndAssistantHud.EndStat> once EndAssistantHud is ported.

    private enum PortalType { END_PORTAL, END_GATEWAY }

    private static class PortalStructure {
        final AABB boundingBox;
        final Set<BlockPos> portalBlocks;
        final PortalType type;
        final BlockPos destination;

        PortalStructure(AABB bb, Set<BlockPos> pb, PortalType t, BlockPos dest) {
            this.boundingBox = bb; this.portalBlocks = pb; this.type = t; this.destination = dest;
        }
    }
}
