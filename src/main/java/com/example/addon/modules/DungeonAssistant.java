package com.example.addon.modules;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.addon.Tim;
import com.example.addon.utils.GlowingRegistry;
import com.example.addon.utils.RenderUtils;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.client.screen.EventScreen;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.BlockListSetting;
import org.rusherhack.client.api.setting.BindSetting;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.client.api.setting.ItemListSetting;
import org.rusherhack.core.bind.key.NullKey;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import net.minecraft.world.item.Item;
import org.rusherhack.core.setting.ListSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class DungeonAssistant extends ToggleableModule {

    public enum TargetType {
        SPAWNER, CHEST, CHEST_MINECART, MISROTATED_CHEST_MINECART, DISPLACED_CHEST_MINECART,
        CUSTOM_BLOCK, MISROTATED_DEEPSLATE, LOW_Y_STONE_DIRT
    }

    public enum RenderMode { GLOW, SPECTRAL, PULSE }
    public enum ChestBeamMode { NONE, NEAREST, ALL }
    public enum BeamStyle { BOX, GUARDIAN }

    private static final int DIMENSION_CHANGE_COOLDOWN_TICKS = 40;
    private static final int INTERACT_TIMEOUT_TICKS = 20;
    private static final int SILENT_SLOT_READ_MAX_RETRIES = 5;

    private final Map<BlockPos, TargetType> targets = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final Set<BlockPos> checkedContainers = new HashSet<>();
    private final List<Endermite> endermiteTargets = new ArrayList<>();
    private final List<ExperienceOrb> xpOrbTargets = new ArrayList<>();
    private final Set<Integer> notifiedEndermites = new HashSet<>();
    private final Set<Integer> checkedEntityIds = new HashSet<>();
    private final Set<Integer> notifiedAnomalousMinecarts = new HashSet<>();
    private final Set<BlockPos> spawnerTorches = new HashSet<>();

    private boolean isBreaking = false;
    private boolean isBreakingEntity = false;
    private boolean isBreakingChest = false;
    private BlockPos blockToBreak = null;
    private Entity entityToBreak = null;
    private int breakDelayTimer = 0;
    private int previousSlot = -1;
    private int brokenChestsCount = 0;
    private int lootFoundCount = 0;

    private boolean wasAutoOpened = false;
    private boolean hasPlayedSoundForCurrentScreen = false;
    private BlockPos lastOpenedContainer = null;
    private Entity lastOpenedEntity = null;
    private int interactTimeoutTimer = 0;

    private boolean silentOpenPending = false;
    private boolean silentFoundWhitelisted = false;
    private boolean pendingBreakCheck = false;
    private int silentSlotReadRetryTimer = 0;

    private String lastDimension = "";
    private int dimensionChangeCooldown = 0;

    private boolean wasToggleBlocksPressed = false;

    // ── General ──
    private final NumberSetting<Integer> range = new NumberSetting<>("range", "Detection range in chunks.", 16, 1, 128);
    private final NumberSetting<Integer> dungeonYLevel = new NumberSetting<>("dungeon-y-level", "Maximum Y level to scan.", 100, -64, 320)
        .onChange((java.util.function.Consumer<Integer>) v -> {
            scannedChunks.clear();
            targets.entrySet().removeIf(entry -> {
                TargetType type = entry.getValue();
                if (type == TargetType.CHEST_MINECART || type == TargetType.MISROTATED_CHEST_MINECART || type == TargetType.DISPLACED_CHEST_MINECART) return false;
                return entry.getKey().getY() > v;
            });
        });
    private final EnumSetting<RenderMode> renderMode = new EnumSetting<>("render-mode", "GLOW = layered bloom boxes. SPECTRAL = outline shader. PULSE = fading highlight.", RenderMode.GLOW)
        .onChange((Runnable) this::rebuildSpectralRegistry);
    private final NumberSetting<Integer> glowLayers = new NumberSetting<>("glow-layers", "Number of bloom layers rendered around each target.", 4, 1, 8)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW || renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Double> glowSpread = new NumberSetting<>("glow-spread", "How far each bloom layer expands outward.", 0.04, 0.01, 0.15)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW || renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> glowBaseAlpha = new NumberSetting<>("glow-base-alpha", "Alpha of the innermost glow layer.", 60, 10, 150)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW);
    private final NumberSetting<Integer> spectralBlockFillAlpha = new NumberSetting<>("spectral-block-fill-alpha", "Fill alpha for block targets in SPECTRAL mode.", 30, 0, 120)
        .setVisibility(() -> renderMode.getValue() == RenderMode.SPECTRAL);
    private final NumberSetting<Double> pulseSpeed = new NumberSetting<>("pulse-speed", "Pulse cycle speed.", 1.0, 0.1, 5.0)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> pulseMinAlpha = new NumberSetting<>("pulse-min-alpha", "Lowest alpha reached during the pulse.", 15, 0, 255)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> pulseMaxAlpha = new NumberSetting<>("pulse-max-alpha", "Peak alpha reached during the pulse.", 220, 15, 255)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);
    private final BooleanSetting stealDumpButtons = new BooleanSetting("steal-dump-buttons", "Show steal and dump buttons on container screens.", true);

    // ── Beams ──
    private final EnumSetting<BeamStyle> beamStyle = new EnumSetting<>("beam-style", "BOX = box beam. GUARDIAN = wide glow beam.", BeamStyle.GUARDIAN);
    private final NumberSetting<Integer> beamWidth = new NumberSetting<>("beam-width", "Box beam width (hundredths of a block).", 15, 5, 50)
        .setVisibility(() -> beamStyle.getValue() == BeamStyle.BOX);
    private final BooleanSetting mergeBeams = new BooleanSetting("merge-beams", "Merge beams for nearby targets.", true);
    private final NumberSetting<Double> mergeDistance = new NumberSetting<>("merge-distance", "Distance within which beams are merged.", 2.0, 0.0, 10.0).setVisibility(mergeBeams::getValue);
    private final NumberSetting<Double> guardianBeamRadius = new NumberSetting<>("guardian-radius", "Guardian beam radius (blocks).", 0.08, 0.01, 0.6)
        .setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Integer> guardianStrands = new NumberSetting<>("guardian-strands", "Guardian glow layer count.", 4, 2, 8)
        .setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Double> guardianSpinSpeed = new NumberSetting<>("guardian-spin-speed", "Unused (visual pacing).", 1.0, 0.1, 5.0)
        .setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Integer> guardianCoreAlpha = new NumberSetting<>("guardian-core-alpha", "Alpha of the beam centre core.", 90, 0, 255)
        .setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Integer> guardianStrandAlpha = new NumberSetting<>("guardian-strand-alpha", "Alpha of the outer glow layers.", 160, 10, 255)
        .setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final BooleanSetting guardianGlow = new BooleanSetting("guardian-glow", "Add a soft bloom halo around the guardian beam.", true)
        .setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Double> guardianGlowRadius = new NumberSetting<>("guardian-glow-radius", "Radius of the bloom halo.", 0.18, 0.02, 1.0)
        .setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN && guardianGlow.getValue());
    private final EnumSetting<ChestBeamMode> chestBeamMode = new EnumSetting<>("chest-beam-mode", "Connecting beams from chests to the sky.", ChestBeamMode.NONE);
    private final NumberSetting<Integer> chestBeamMinY = new NumberSetting<>("chest-beam-y-level", "Only render beams on chests at or above this Y level.", -64, -64, 320)
        .setVisibility(() -> chestBeamMode.getValue() != ChestBeamMode.NONE);
    private final ColorSetting chestBeamColor = new ColorSetting("chest-beam-color", "Color of the beams drawn on chests.", new Color(255, 215, 0, 180))
        .setVisibility(() -> chestBeamMode.getValue() != ChestBeamMode.NONE);

    // ── Targets - Blocks ──
    private final BooleanSetting trackSpawners = new BooleanSetting("track-spawners", "Highlight monster spawners.", true);
    private final ColorSetting spawnerColor = new ColorSetting("spawner-color", "Monster spawner highlight color.", new Color(255, 0, 0, 255)).setVisibility(trackSpawners::getValue);
    private final BooleanSetting highlightSpawnerTorches = new BooleanSetting("highlight-spawner-torches", "Highlights torches within 5 blocks of a spawner.", true).setVisibility(trackSpawners::getValue);
    private final ColorSetting spawnerTorchColor = new ColorSetting("spawner-torch-color", "Color for torches near spawners.", new Color(255, 255, 0, 255))
        .setVisibility(() -> trackSpawners.getValue() && highlightSpawnerTorches.getValue());
    private final BooleanSetting trackChests = new BooleanSetting("track-chests", "Highlight chests and count broken ones.", true);
    private final ColorSetting chestColor = new ColorSetting("chest-color", "Chest highlight color.", new Color(255, 215, 0, 255)).setVisibility(trackChests::getValue);
    private final BooleanSetting scanCustomBlocks = new BooleanSetting("scan-blocks", "Highlight selected blocks in the surrounding area.", true)
        .onChange((java.util.function.Consumer<Boolean>) v -> { targets.entrySet().removeIf(e -> e.getValue() == TargetType.CUSTOM_BLOCK); scannedChunks.clear(); });
    private final ListSetting<Block> filterBlocks = new BlockListSetting("blocks", "Blocks to search for and highlight in the world.",
        Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.NETHERRACK)
        .setVisibility(scanCustomBlocks::getValue);
    private final ColorSetting customBlockColor = new ColorSetting("block-color", "Highlight color for the selected blocks.", new Color(128, 200, 128, 255)).setVisibility(scanCustomBlocks::getValue);
    private final BindSetting toggleBlocksKey = new BindSetting("toggle-key", "Key to toggle custom block scanning on/off.", NullKey.INSTANCE);

    // ── Targets - Anomalies ──
    private final BooleanSetting trackMisrotatedDeepslate = new BooleanSetting("misrotated-deepslate", "Highlights Deepslate blocks facing the wrong direction.", false)
        .onChange((java.util.function.Consumer<Boolean>) v -> { targets.entrySet().removeIf(e -> e.getValue() == TargetType.MISROTATED_DEEPSLATE); scannedChunks.clear(); });
    private final ColorSetting misrotatedDeepslateColor = new ColorSetting("misrotated-deepslate-color", "Highlight color for misrotated Deepslate.", new Color(0, 180, 255, 255)).setVisibility(trackMisrotatedDeepslate::getValue);
    private final BooleanSetting trackLowYStoneDirt = new BooleanSetting("low-y-stone-dirt", "Highlights Stone and Dirt below a specified Y level.", false)
        .onChange((java.util.function.Consumer<Boolean>) v -> { targets.entrySet().removeIf(e -> e.getValue() == TargetType.LOW_Y_STONE_DIRT); scannedChunks.clear(); });
    private final NumberSetting<Integer> lowYLevel = new NumberSetting<>("low-y-level", "The Y level below which Stone and Dirt will be highlighted.", -5, -64, 320)
        .setVisibility(trackLowYStoneDirt::getValue)
        .onChange((java.util.function.Consumer<Integer>) v -> { targets.entrySet().removeIf(e -> e.getValue() == TargetType.LOW_Y_STONE_DIRT); scannedChunks.clear(); });
    private final ColorSetting lowYStoneDirtColor = new ColorSetting("low-y-color", "Highlight color for Stone and Dirt below the Y level.", new Color(128, 128, 128, 255)).setVisibility(trackLowYStoneDirt::getValue);

    // ── Targets - Entities ──
    private final BooleanSetting trackChestMinecarts = new BooleanSetting("track-chest-minecarts", "Highlight chest minecarts.", true);
    private final ColorSetting chestMinecartColor = new ColorSetting("chest-minecart-color", "Chest minecart highlight color.", new Color(255, 180, 0, 255)).setVisibility(trackChestMinecarts::getValue);
    private final BooleanSetting trackAnomalousMinecarts = new BooleanSetting("minecart-anomalies", "Highlights displaced or misrotated chest minecarts.", true).setVisibility(trackChestMinecarts::getValue);
    private final ColorSetting misrotatedMinecartColor = new ColorSetting("misrotated-minecart-color", "Color for misrotated chest minecarts.", new Color(180, 0, 255, 255))
        .setVisibility(() -> trackChestMinecarts.getValue() && trackAnomalousMinecarts.getValue());
    private final ColorSetting displacedMinecartColor = new ColorSetting("displaced-minecart-color", "Color for physically displaced chest minecarts.", new Color(0, 255, 255, 255))
        .setVisibility(() -> trackChestMinecarts.getValue() && trackAnomalousMinecarts.getValue());
    private final BooleanSetting trackEndermites = new BooleanSetting("track-endermites", "Highlights Endermites in the Overworld.", false);
    private final ColorSetting endermiteColor = new ColorSetting("endermite-color", "The highlight color for Endermites.", new Color(138, 43, 226, 255)).setVisibility(trackEndermites::getValue);
    private final BooleanSetting trackXpOrbs = new BooleanSetting("track-xp-orbs", "Highlights Experience Orbs in the world.", true);
    private final ColorSetting xpOrbColor = new ColorSetting("xp-orb-color", "The highlight color for Experience Orbs.", new Color(255, 255, 0, 255)).setVisibility(trackXpOrbs::getValue);

    // ── Automation ──
    private final BooleanSetting autoOpenBreak = new BooleanSetting("auto-open-break", "Automatically open, check, and break empty containers.", true);
    private final BooleanSetting silentMode = new BooleanSetting("silent-mode", "Open containers invisibly and switch tools silently.", true).setVisibility(autoOpenBreak::getValue);
    private final NumberSetting<Integer> breakDelay = new NumberSetting<>("break-delay", "Ticks to wait before breaking an empty container.", 5, 0, 40).setVisibility(autoOpenBreak::getValue);
    private final ListSetting<Item> whitelistedItems = new ItemListSetting("whitelisted-items", "Items to look for — if found the container is left open and a sound plays.",
        Items.ENCHANTED_GOLDEN_APPLE, Items.ENDER_CHEST, Items.SHULKER_BOX,
        Items.WHITE_SHULKER_BOX, Items.ORANGE_SHULKER_BOX, Items.MAGENTA_SHULKER_BOX, Items.LIGHT_BLUE_SHULKER_BOX,
        Items.YELLOW_SHULKER_BOX, Items.LIME_SHULKER_BOX, Items.PINK_SHULKER_BOX, Items.GRAY_SHULKER_BOX,
        Items.LIGHT_GRAY_SHULKER_BOX, Items.CYAN_SHULKER_BOX, Items.PURPLE_SHULKER_BOX, Items.BLUE_SHULKER_BOX,
        Items.BROWN_SHULKER_BOX, Items.GREEN_SHULKER_BOX, Items.RED_SHULKER_BOX, Items.BLACK_SHULKER_BOX).setVisibility(autoOpenBreak::getValue);
    private final BooleanSetting autoBreakSpawners = new BooleanSetting("auto-break-spawners", "Automatically break spawners in range.", false);
    private final NumberSetting<Integer> spawnerBreakRange = new NumberSetting<>("spawner-break-range", "Range in blocks to break spawners.", 5, 1, 10).setVisibility(autoBreakSpawners::getValue);
    private final NumberSetting<Integer> spawnerBreakDelay = new NumberSetting<>("spawner-break-delay", "Ticks to wait before breaking a spawner.", 5, 0, 20).setVisibility(autoBreakSpawners::getValue);
    private final BooleanSetting prioritizeSpawners = new BooleanSetting("prioritize-spawners", "Break spawners before opening chests.", true)
        .setVisibility(() -> autoOpenBreak.getValue() && autoBreakSpawners.getValue());

    // ── Safety ──
    private final BooleanSetting autoDisableOnLowHealth = new BooleanSetting("auto-disable-on-low-health", "Auto-disable if health is critically low with a totem equipped.", true);
    private final NumberSetting<Integer> lowHealthThreshold = new NumberSetting<>("low-health-threshold", "Health level (in hearts) to trigger auto-disable.", 3, 1, 10).setVisibility(autoDisableOnLowHealth::getValue);

    public DungeonAssistant() {
        super("dungeon-assistant", "Highlights dungeon elements: spawners, chests, and dungeon blocks.", Tim.CATEGORY);
        this.registerSettings(
            range, dungeonYLevel, renderMode, glowLayers, glowSpread, glowBaseAlpha, spectralBlockFillAlpha,
            pulseSpeed, pulseMinAlpha, pulseMaxAlpha, stealDumpButtons,
            beamStyle, beamWidth, mergeBeams, mergeDistance, guardianBeamRadius, guardianStrands, guardianSpinSpeed,
            guardianCoreAlpha, guardianStrandAlpha, guardianGlow, guardianGlowRadius, chestBeamMode, chestBeamMinY, chestBeamColor,
            trackSpawners, spawnerColor, highlightSpawnerTorches, spawnerTorchColor, trackChests, chestColor,
            scanCustomBlocks, filterBlocks, customBlockColor, toggleBlocksKey,
            trackMisrotatedDeepslate, misrotatedDeepslateColor, trackLowYStoneDirt, lowYLevel, lowYStoneDirtColor,
            trackChestMinecarts, chestMinecartColor, trackAnomalousMinecarts, misrotatedMinecartColor, displacedMinecartColor,
            trackEndermites, endermiteColor, trackXpOrbs, xpOrbColor,
            autoOpenBreak, silentMode, breakDelay, whitelistedItems, autoBreakSpawners, spawnerBreakRange, spawnerBreakDelay, prioritizeSpawners,
            autoDisableOnLowHealth, lowHealthThreshold
        );
    }

    private void info(String fmt, Object... args) { sendNotification(NotificationType.INFO, args.length == 0 ? fmt : String.format(fmt, args)); }
    private void error(String fmt, Object... args) { sendNotification(NotificationType.ERROR, args.length == 0 ? fmt : String.format(fmt, args)); }

    @Override
    public void onEnable() {
        targets.clear();
        scannedChunks.clear();
        checkedContainers.clear();
        endermiteTargets.clear();
        xpOrbTargets.clear();
        notifiedEndermites.clear();
        checkedEntityIds.clear();
        notifiedAnomalousMinecarts.clear();
        spawnerTorches.clear();
        brokenChestsCount = 0;
        lootFoundCount = 0;
        isBreakingChest = false;
        hasPlayedSoundForCurrentScreen = false;
        GlowingRegistry.clear();

        if (mc.player != null && mc.level != null) {
            info("§6Dungeon Assistant activated");
            lastDimension = mc.level.dimension().location().toString();
        }
        rebuildSpectralRegistry();
    }

    @Override
    public void onDisable() {
        if (isBreaking && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        restoreSlot();
        GlowingRegistry.clear();

        targets.clear();
        scannedChunks.clear();
        checkedContainers.clear();
        endermiteTargets.clear();
        xpOrbTargets.clear();
        notifiedEndermites.clear();
        checkedEntityIds.clear();
        notifiedAnomalousMinecarts.clear();
        spawnerTorches.clear();

        resetSoftState();
    }

    // ── Event Handlers ──
    @Subscribe
    private void onOpenScreen(EventScreen.Change event) {
        if (mc.player == null || mc.level == null) return;

        if (wasAutoOpened) {
            interactTimeoutTimer = 0;
            if (autoOpenBreak.getValue() && silentMode.getValue()
                    && event.getTo() instanceof AbstractContainerScreen<?>
                    && !(event.getTo() instanceof InventoryScreen)) {
                silentOpenPending = true;
                silentSlotReadRetryTimer = 0;
            }
            return;
        }

        HitResult hit = mc.hitResult;
        if (hit != null) {
            if (hit.getType() == HitResult.Type.BLOCK) {
                lastOpenedContainer = ((BlockHitResult) hit).getBlockPos().immutable();
                lastOpenedEntity = null;
            } else if (hit.getType() == HitResult.Type.ENTITY) {
                EntityHitResult entityHit = (EntityHitResult) hit;
                if (entityHit.getEntity() instanceof MinecartChest) {
                    lastOpenedEntity = entityHit.getEntity();
                    lastOpenedContainer = null;
                }
            }
        }
    }

    @Subscribe
    private void onTick(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;
        handleHotkey();
        if (performSafetyChecks()) return;
        updateBreakingLogic();
        updateContainerLogic();
        updateScanningLogic();
    }

    private void handleHotkey() {
        boolean p = toggleBlocksKey.getValue().isKeyDown();
        if (p && !wasToggleBlocksPressed && mc.screen == null) {
            boolean newValue = !scanCustomBlocks.getValue();
            scanCustomBlocks.setValue(newValue);
            info("Custom Blocks Highlight toggled %s.", newValue ? "§aON" : "§cOFF");
        }
        wasToggleBlocksPressed = p;
    }

    @Subscribe
    private void onRender(EventRender3D event) {
        if (mc.player == null || mc.level == null) return;

        IRenderer3D r = event.getRenderer();
        r.begin(event.getMatrixStack());

        boolean isSpectral = renderMode.getValue() == RenderMode.SPECTRAL;
        boolean isPulse = renderMode.getValue() == RenderMode.PULSE;
        Set<BlockPos> toRemove = new HashSet<>();
        List<BeamData> beamsToRender = new ArrayList<>();

        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            BlockPos pos = entry.getKey();
            TargetType type = entry.getValue();

            AABB renderBox;
            Color color;

            if (isMinecartType(type)) {
                AABB queryBox = new AABB(pos).inflate(0.5);
                List<MinecartChest> minecarts = mc.level.getEntitiesOfClass(MinecartChest.class, queryBox, entity -> true);
                if (minecarts.isEmpty()) { toRemove.add(pos); continue; }
                renderBox = getMinecartChestBox(minecarts.get(0));
                color = getColor(type);
                if (type == TargetType.MISROTATED_CHEST_MINECART || type == TargetType.DISPLACED_CHEST_MINECART) {
                    beamsToRender.add(new BeamData(renderBox, color));
                }
            } else {
                if (!isChunkLoaded(pos)) continue;
                if (mc.level.getBlockState(pos).isAir()) { toRemove.add(pos); continue; }
                Block currentBlock = mc.level.getBlockState(pos).getBlock();
                if (type == TargetType.SPAWNER || type == TargetType.CHEST || type == TargetType.MISROTATED_DEEPSLATE || type == TargetType.LOW_Y_STONE_DIRT) {
                    if (!validateBlockType(currentBlock, type)) { toRemove.add(pos); continue; }
                }
                renderBox = createPaddedBox(pos);
                color = getColor(type);
            }

            if (color == null) continue;

            if (isSpectral) {
                int fillAlpha = isMinecartType(type) ? 0 : spectralBlockFillAlpha.getValue();
                int outlineAlpha = isMinecartType(type) ? 200 : 0;
                if (fillAlpha > 0) db(r, renderBox, true, false, RenderUtils.withAlpha(color, fillAlpha));
                if (outlineAlpha > 0) db(r, renderBox, false, true, RenderUtils.withAlpha(color, outlineAlpha));
            } else if (isPulse) {
                renderPulseBox(r, renderBox, color);
            } else {
                renderGlowLayers(r, renderBox, color);
                db(r, renderBox, false, true, color.getRGB());
            }
        }

        for (BlockPos pos : toRemove) targets.remove(pos);

        if (!spawnerTorches.isEmpty() && trackSpawners.getValue() && highlightSpawnerTorches.getValue()) {
            Color torchColor = spawnerTorchColor.getValue();
            for (BlockPos pos : spawnerTorches) {
                if (!isChunkLoaded(pos)) continue;
                AABB torchBox = createPaddedBox(pos);
                if (isSpectral) {
                    db(r, torchBox, true, false, RenderUtils.withAlpha(torchColor, spectralBlockFillAlpha.getValue()));
                } else if (isPulse) {
                    renderPulseBox(r, torchBox, torchColor);
                } else {
                    renderGlowLayers(r, torchBox, torchColor);
                    db(r, torchBox, false, true, torchColor.getRGB());
                }
            }
        }

        if (trackEndermites.getValue() && !endermiteTargets.isEmpty()) {
            Color color = endermiteColor.getValue();
            for (Endermite endermite : endermiteTargets) {
                if (!endermite.isAlive()) continue;
                AABB entityBox = endermite.getBoundingBox();
                beamsToRender.add(new BeamData(entityBox, color));
                if (isSpectral) db(r, entityBox, false, true, RenderUtils.withAlpha(color, 200));
                else if (isPulse) renderPulseBox(r, entityBox, color);
                else { renderGlowLayers(r, entityBox, color); db(r, entityBox, false, true, color.getRGB()); }
            }
        }

        if (trackXpOrbs.getValue() && !xpOrbTargets.isEmpty()) {
            Color color = xpOrbColor.getValue();
            for (ExperienceOrb orb : xpOrbTargets) {
                if (!orb.isAlive()) continue;
                AABB orbBox = orb.getBoundingBox();
                if (isSpectral) db(r, orbBox, false, true, RenderUtils.withAlpha(color, 200));
                else if (isPulse) renderPulseBox(r, orbBox, color);
                else { renderGlowLayers(r, orbBox, color); db(r, orbBox, false, true, color.getRGB()); }
            }
        }

        if (chestBeamMode.getValue() != ChestBeamMode.NONE) {
            double maxDistSq = Math.pow(range.getValue() * 16, 2);
            Color beamColor = chestBeamColor.getValue();
            int minY = chestBeamMinY.getValue();

            List<BlockPos> chests = targets.entrySet().stream()
                .filter(e -> e.getValue() == TargetType.CHEST)
                .map(Map.Entry::getKey)
                .filter(pos -> pos.distToCenterSqr(mc.player.position()) <= maxDistSq)
                .filter(pos -> pos.getY() >= minY)
                .filter(this::isChunkLoaded)
                .filter(pos -> !mc.level.getBlockState(pos).isAir())
                .sorted(Comparator.comparingDouble(pos -> pos.distToCenterSqr(mc.player.position())))
                .toList();

            if (!chests.isEmpty()) {
                if (chestBeamMode.getValue() == ChestBeamMode.NEAREST) {
                    beamsToRender.add(new BeamData(createPaddedBox(chests.get(0)), beamColor));
                } else {
                    for (BlockPos pos : chests) beamsToRender.add(new BeamData(createPaddedBox(pos), beamColor));
                }
            }
        }

        renderBeams(r, beamsToRender);
        r.end();
    }

    private boolean isMinecartType(TargetType t) {
        return t == TargetType.CHEST_MINECART || t == TargetType.MISROTATED_CHEST_MINECART || t == TargetType.DISPLACED_CHEST_MINECART;
    }

    private boolean isChunkLoaded(BlockPos pos) {
        return mc.level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    // ── Beam rendering ──
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
        double beamSize = beamWidth.getValue() / 100.0;
        double centerX = (anchorBox.minX + anchorBox.maxX) / 2.0;
        double centerZ = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int worldBot = mc.level.getMinY();
        int worldTop = worldBot + mc.level.getHeight();
        AABB beamBox = new AABB(centerX - beamSize, worldBot, centerZ - beamSize, centerX + beamSize, worldTop, centerZ + beamSize);
        db(r, beamBox, true, false, RenderUtils.withAlpha(color, 80));
        db(r, beamBox, false, true, color.getRGB());
        for (int i = 1; i <= 2; i++) {
            double exp = beamSize * i * 1.5;
            int alpha = Math.max(4, 30 / i);
            AABB bloom = new AABB(centerX - beamSize - exp, worldBot, centerZ - beamSize - exp, centerX + beamSize + exp, worldTop, centerZ + beamSize + exp);
            db(r, bloom, true, false, RenderUtils.withAlpha(color, alpha));
        }
    }

    private void renderGuardianBeam(IRenderer3D r, AABB anchorBox, Color color) {
        if (mc.level == null) return;
        double cx = (anchorBox.minX + anchorBox.maxX) / 2.0;
        double cz = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int worldBot = mc.level.getMinY();
        int worldTop = worldBot + mc.level.getHeight();
        double radius = guardianBeamRadius.getValue();
        int layers = guardianStrands.getValue();
        int strandA = guardianStrandAlpha.getValue();

        for (int i = layers; i >= 1; i--) {
            double exp = radius * i;
            int alpha = Math.max(4, (int) (strandA * (1.0 - (double) (i - 1) / layers)));
            AABB box = new AABB(cx - exp, worldBot, cz - exp, cx + exp, worldTop, cz + exp);
            db(r, box, true, false, RenderUtils.withAlpha(color, alpha));
        }

        int coreAlpha = guardianCoreAlpha.getValue();
        if (coreAlpha > 0) {
            double coreR = radius * 0.25;
            AABB coreBox = new AABB(cx - coreR, worldBot, cz - coreR, cx + coreR, worldTop, cz + coreR);
            db(r, coreBox, true, false, RenderUtils.withAlpha(color, coreAlpha));
            db(r, coreBox, false, true, RenderUtils.withAlpha(color, Math.min(255, coreAlpha + 40)));
        }

        if (guardianGlow.getValue()) {
            double glowR = guardianGlowRadius.getValue();
            for (int ring = 1; ring <= 2; ring++) {
                double expansion = glowR * ring;
                int alpha = Math.max(4, 22 / ring);
                AABB bloomBox = new AABB(cx - radius - expansion, worldBot, cz - radius - expansion, cx + radius + expansion, worldTop, cz + radius + expansion);
                db(r, bloomBox, true, false, RenderUtils.withAlpha(color, alpha));
            }
        }
    }

    private void rebuildSpectralRegistry() {
        GlowingRegistry.clear();
        if (renderMode.getValue() != RenderMode.SPECTRAL) return;

        if (mc.level != null && mc.player != null && (trackChestMinecarts.getValue() || trackAnomalousMinecarts.getValue())) {
            int blockRange = range.getValue() * 16;
            int worldHeight = mc.level.getHeight();
            AABB searchBox = new AABB(mc.player.blockPosition()).inflate(blockRange, worldHeight, blockRange);
            for (MinecartChest minecart : mc.level.getEntitiesOfClass(MinecartChest.class, searchBox, e -> true)) {
                TargetType type = getMinecartType(minecart);
                if (type == TargetType.DISPLACED_CHEST_MINECART && trackAnomalousMinecarts.getValue()) {
                    GlowingRegistry.add(minecart.getId(), displacedMinecartColor.getValue().getRGB());
                } else if (type == TargetType.MISROTATED_CHEST_MINECART && trackAnomalousMinecarts.getValue()) {
                    GlowingRegistry.add(minecart.getId(), misrotatedMinecartColor.getValue().getRGB());
                } else if (trackChestMinecarts.getValue()) {
                    GlowingRegistry.add(minecart.getId(), chestMinecartColor.getValue().getRGB());
                }
            }
        }

        if (trackEndermites.getValue()) {
            for (Endermite e : endermiteTargets) if (e.isAlive()) GlowingRegistry.add(e.getId(), endermiteColor.getValue().getRGB());
        }
        if (trackXpOrbs.getValue()) {
            for (ExperienceOrb orb : xpOrbTargets) if (orb.isAlive()) GlowingRegistry.add(orb.getId(), xpOrbColor.getValue().getRGB());
        }
    }

    private void db(IRenderer3D r, AABB b, boolean fill, boolean outline, int color) {
        r.drawBox(b.minX, b.minY, b.minZ, b.getXsize(), b.getYsize(), b.getZsize(), fill, outline, color);
    }

    private void renderGlowLayers(IRenderer3D r, AABB box, Color color) {
        int layers = glowLayers.getValue();
        double spread = glowSpread.getValue();
        int baseAlpha = glowBaseAlpha.getValue();
        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            int layerAlpha = Math.max(4, (int) (baseAlpha * (1.0 - (double) (i - 1) / layers)));
            db(r, box.inflate(expansion), true, false, RenderUtils.withAlpha(color, layerAlpha));
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

    // ── Safety ──
    private boolean performSafetyChecks() {
        if (!autoDisableOnLowHealth.getValue()) return false;
        boolean hasTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || mc.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
        if (hasTotem && mc.player.getHealth() <= lowHealthThreshold.getValue() * 2) {
            error("Health is critical (%.1f), disabling to prevent totem pop.", mc.player.getHealth());
            toggle();
            return true;
        }
        return false;
    }

    // ── Breaking Logic ──
    private void updateBreakingLogic() {
        if (breakDelayTimer > 0) {
            breakDelayTimer--;
            if (breakDelayTimer == 0) {
                if (blockToBreak != null) {
                    Block targetBlock = mc.level.getBlockState(blockToBreak).getBlock();
                    if (targetBlock == Blocks.CHEST || targetBlock == Blocks.TRAPPED_CHEST || targetBlock == Blocks.SPAWNER) {
                        isBreaking = true;
                        isBreakingChest = (targetBlock == Blocks.CHEST || targetBlock == Blocks.TRAPPED_CHEST);
                        if (silentMode.getValue()) previousSlot = mc.player.getInventory().selected;
                    } else {
                        blockToBreak = null;
                    }
                } else if (entityToBreak != null) {
                    if (entityToBreak instanceof MinecartChest) {
                        isBreakingEntity = true;
                        if (silentMode.getValue()) previousSlot = mc.player.getInventory().selected;
                    } else {
                        entityToBreak = null;
                    }
                }
            }
        }

        if (isBreaking && blockToBreak != null && !mc.player.isInWater()) {
            Block currentBreakTarget = mc.level.getBlockState(blockToBreak).getBlock();
            boolean blockIsNowAir = mc.level.getBlockState(blockToBreak).isAir();
            boolean done = blockIsNowAir
                || (currentBreakTarget != Blocks.CHEST && currentBreakTarget != Blocks.TRAPPED_CHEST && currentBreakTarget != Blocks.SPAWNER)
                || Math.sqrt(mc.player.distanceToSqr(Vec3.atCenterOf(blockToBreak))) > 6;

            if (done) {
                if (isBreakingChest && blockIsNowAir && trackChests.getValue()) {
                    brokenChestsCount++;
                    info("Chests broken: " + brokenChestsCount);
                }
                isBreaking = false;
                blockToBreak = null;
                isBreakingChest = false;
                mc.gameMode.stopDestroyBlock();
                restoreSlot();
            } else {
                if (isBreakingChest) {
                    int axeSlot = findAxe();
                    if (axeSlot != -1) mc.player.getInventory().selected = axeSlot;
                } else {
                    int pickaxeSlot = findPickaxe();
                    if (pickaxeSlot != -1) mc.player.getInventory().selected = pickaxeSlot;
                }
                RusherHackAPI.getRotationManager().updateRotation(blockToBreak);
                mc.gameMode.continueDestroyBlock(blockToBreak, Direction.UP);
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        }

        if (isBreakingEntity && entityToBreak != null && !mc.player.isInWater()) {
            boolean gone = !(entityToBreak instanceof MinecartChest) || !entityToBreak.isAlive() || mc.player.distanceTo(entityToBreak) > 6;
            if (gone) {
                isBreakingEntity = false;
                entityToBreak = null;
                restoreSlot();
            } else {
                int swordSlot = findSword();
                if (swordSlot != -1) mc.player.getInventory().selected = swordSlot;
                if (mc.player.getAttackStrengthScale(0f) >= 1.0f) {
                    RusherHackAPI.getRotationManager().updateRotation(entityToBreak);
                    mc.gameMode.attack(mc.player, entityToBreak);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
            }
        }
    }

    // ── Container Logic ──
    private void updateContainerLogic() {
        if (interactTimeoutTimer > 0) {
            interactTimeoutTimer--;
            if (interactTimeoutTimer == 0 && wasAutoOpened && mc.screen == null) {
                if (lastOpenedContainer != null) checkedContainers.remove(lastOpenedContainer);
                if (lastOpenedEntity != null) checkedEntityIds.remove(lastOpenedEntity.getId());
                resetSoftState();
            }
        }

        if (silentOpenPending && mc.screen instanceof AbstractContainerScreen<?> silentScreen && !(mc.screen instanceof InventoryScreen)) {
            int numSlots = silentScreen.getMenu().slots.size();
            int containerSlots = Math.max(0, numSlots - 36);

            if (containerSlots > 0) {
                boolean anyNonEmpty = false;
                for (int i = 0; i < containerSlots; i++) {
                    if (!silentScreen.getMenu().slots.get(i).getItem().isEmpty()) { anyNonEmpty = true; break; }
                }

                boolean retriesExhausted = silentSlotReadRetryTimer >= SILENT_SLOT_READ_MAX_RETRIES;
                if (anyNonEmpty || retriesExhausted) {
                    silentFoundWhitelisted = false;
                    for (int i = 0; i < containerSlots; i++) {
                        Item item = silentScreen.getMenu().slots.get(i).getItem().getItem();
                        if (whitelistedItems.getList().contains(item)) { silentFoundWhitelisted = true; break; }
                    }
                    pendingBreakCheck = true;
                    mc.player.closeContainer();
                    silentOpenPending = false;
                    silentSlotReadRetryTimer = 0;
                    return;
                } else {
                    silentSlotReadRetryTimer++;
                    return;
                }
            }
        }

        if (pendingBreakCheck && mc.screen == null && !silentOpenPending) {
            pendingBreakCheck = false;
            wasAutoOpened = false;
            hasPlayedSoundForCurrentScreen = false;

            if (!silentFoundWhitelisted) {
                if (autoOpenBreak.getValue()) {
                    if (lastOpenedContainer != null) {
                        blockToBreak = lastOpenedContainer;
                        removeNeighborFromChecked(lastOpenedContainer);
                        breakDelayTimer = getRandomizedDelay(breakDelay.getValue());
                    } else if (lastOpenedEntity != null) {
                        entityToBreak = lastOpenedEntity;
                        breakDelayTimer = getRandomizedDelay(breakDelay.getValue());
                    }
                }
            } else {
                mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            }
            return;
        }

        if (mc.screen instanceof AbstractContainerScreen<?> screen && !(mc.screen instanceof InventoryScreen)) {
            if (!wasAutoOpened) return;
            if (lastOpenedContainer == null && lastOpenedEntity == null) return;
            if (lastOpenedEntity != null && !(lastOpenedEntity instanceof MinecartChest)) return;

            int numSlots = screen.getMenu().slots.size();
            int containerSlots = Math.max(0, numSlots - 36);

            if (containerSlots > 0) {
                boolean found = false;
                for (int i = 0; i < containerSlots; i++) {
                    if (whitelistedItems.getList().contains(screen.getMenu().slots.get(i).getItem().getItem())) { found = true; break; }
                }
                if (!found) {
                    mc.player.closeContainer();
                    wasAutoOpened = false;
                    if (autoOpenBreak.getValue()) {
                        if (lastOpenedContainer != null) {
                            blockToBreak = lastOpenedContainer;
                            removeNeighborFromChecked(lastOpenedContainer);
                            breakDelayTimer = getRandomizedDelay(breakDelay.getValue());
                        } else if (lastOpenedEntity != null) {
                            entityToBreak = lastOpenedEntity;
                            breakDelayTimer = getRandomizedDelay(breakDelay.getValue());
                        }
                    }
                } else {
                    wasAutoOpened = false;
                    if (!hasPlayedSoundForCurrentScreen) {
                        boolean isChestOrMinecart = lastOpenedEntity != null
                            || (lastOpenedContainer != null
                                && (mc.level.getBlockState(lastOpenedContainer).getBlock() == Blocks.CHEST
                                || mc.level.getBlockState(lastOpenedContainer).getBlock() == Blocks.TRAPPED_CHEST));
                        if (isChestOrMinecart) {
                            lootFoundCount++;
                            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                            hasPlayedSoundForCurrentScreen = true;
                        }
                    }
                }
            }
        } else if (mc.screen == null && !isBreaking && !isBreakingEntity
                && breakDelayTimer == 0 && !pendingBreakCheck && !silentOpenPending && !wasAutoOpened) {
            hasPlayedSoundForCurrentScreen = false;
            if (autoOpenBreak.getValue()) {
                if (prioritizeSpawners.getValue() && autoBreakSpawners.getValue() && isSpawnerInBreakRange()) {
                    if (runSpawnerCheck()) return;
                    if (runMinecartCheck()) return;
                    if (runChestCheck()) return;
                } else {
                    if (runMinecartCheck()) return;
                    if (runChestCheck()) return;
                    if (runSpawnerCheck()) return;
                }
            }
        }
    }

    // ── Scanning Logic ──
    private void updateScanningLogic() {
        if (dimensionChangeCooldown > 0) { dimensionChangeCooldown--; return; }

        String currDim = mc.level.dimension().location().toString();
        if (!currDim.equals(lastDimension)) {
            dimensionChangeCooldown = DIMENSION_CHANGE_COOLDOWN_TICKS;
            lastDimension = currDim;
            resetScanningState();
            return;
        }

        BlockPos playerPos = mc.player.blockPosition();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;

        cleanupDistantTargets(playerPos);
        scanChestMinecarts();
        pruneBlockTargets();
        scanNewChunks(centerChunkX, centerChunkZ);
        scanEndermites();
        scanXpOrbs();
        scanSpawnerTorches();
        pruneCheckedEntityIds();
        pruneCheckedContainers();
    }

    private boolean isSpawnerInBreakRange() {
        double rangeSq = Math.pow(spawnerBreakRange.getValue(), 2);
        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            if (entry.getValue() == TargetType.SPAWNER && entry.getKey().distToCenterSqr(mc.player.position()) <= rangeSq) return true;
        }
        return false;
    }

    private boolean runSpawnerCheck() {
        if (!autoBreakSpawners.getValue() || areMobsNearby()) return false;
        BlockPos bestPos = null;
        double minDistSq = Double.MAX_VALUE;
        double rangeSq = Math.pow(spawnerBreakRange.getValue(), 2);
        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            if (entry.getValue() == TargetType.SPAWNER) {
                double distSq = entry.getKey().distToCenterSqr(mc.player.position());
                if (distSq <= rangeSq && distSq < minDistSq) { minDistSq = distSq; bestPos = entry.getKey(); }
            }
        }
        if (bestPos == null) return false;
        blockToBreak = bestPos;
        breakDelayTimer = getRandomizedDelay(spawnerBreakDelay.getValue());
        return true;
    }

    private boolean areMobsNearby() {
        if (mc.player == null || mc.level == null) return false;
        double radius = spawnerBreakRange.getValue();
        return !mc.level.getEntitiesOfClass(Monster.class, new AABB(mc.player.blockPosition()).inflate(radius), Entity::isAlive).isEmpty();
    }

    private boolean runMinecartCheck() {
        if (!trackChestMinecarts.getValue() && !trackAnomalousMinecarts.getValue()) return false;

        List<MinecartChest> minecarts = mc.level.getEntitiesOfClass(MinecartChest.class,
            new AABB(mc.player.blockPosition()).inflate(4.5),
            e -> !checkedEntityIds.contains(e.getId()));
        if (minecarts.isEmpty()) return false;

        minecarts.sort(Comparator.comparingDouble(e -> mc.player.distanceToSqr(e)));
        MinecartChest cart = minecarts.get(0);
        if (mc.player.distanceTo(cart) > 4.5) return false;

        lastOpenedEntity = cart;
        lastOpenedContainer = null;
        checkedEntityIds.add(cart.getId());
        wasAutoOpened = true;
        interactTimeoutTimer = INTERACT_TIMEOUT_TICKS;

        RusherHackAPI.getRotationManager().updateRotation(cart);
        mc.gameMode.interact(mc.player, cart, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private boolean runChestCheck() {
        if (!trackChests.getValue()) return false;

        List<BlockPos> nearbyChests = targets.entrySet().stream()
            .filter(e -> e.getValue() == TargetType.CHEST)
            .map(Map.Entry::getKey)
            .filter(pos -> !checkedContainers.contains(pos))
            .filter(pos -> Math.sqrt(pos.distToCenterSqr(mc.player.position())) <= 4.5)
            .sorted(Comparator.comparingDouble(pos -> pos.distToCenterSqr(mc.player.position())))
            .toList();

        if (nearbyChests.isEmpty()) return false;

        BlockPos pos = nearbyChests.get(0);
        Block block = mc.level.getBlockState(pos).getBlock();

        checkedContainers.add(pos);
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos neighbor = pos.relative(dir);
                if (mc.level.getBlockState(neighbor).getBlock() == block) { checkedContainers.add(neighbor); break; }
            }
        }

        lastOpenedContainer = pos;
        lastOpenedEntity = null;
        wasAutoOpened = true;
        interactTimeoutTimer = INTERACT_TIMEOUT_TICKS;

        RusherHackAPI.getRotationManager().updateRotation(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    // ── Scanning ──
    private void resetScanningState() {
        targets.clear();
        scannedChunks.clear();
        checkedContainers.clear();
        checkedEntityIds.clear();
        notifiedAnomalousMinecarts.clear();
        GlowingRegistry.clear();
    }

    private void scanEndermites() {
        endermiteTargets.clear();
        if (!trackEndermites.getValue() || mc.level == null || mc.player == null) { notifiedEndermites.clear(); return; }
        if (!mc.level.dimension().location().toString().equals("minecraft:overworld")) { notifiedEndermites.clear(); return; }

        boolean isSpectral = renderMode.getValue() == RenderMode.SPECTRAL;
        AABB searchBox = new AABB(mc.player.blockPosition()).inflate(range.getValue() * 16);
        Set<Integer> currentIds = new HashSet<>();

        for (Endermite endermite : mc.level.getEntitiesOfClass(Endermite.class, searchBox, e -> true)) {
            endermiteTargets.add(endermite);
            currentIds.add(endermite.getId());
            if (isSpectral) GlowingRegistry.add(endermite.getId(), endermiteColor.getValue().getRGB());
            else GlowingRegistry.remove(endermite.getId());
            if (notifiedEndermites.add(endermite.getId())) {
                info("Endermite Detected, Beam created");
                mc.player.playSound(SoundEvents.ENDERMITE_AMBIENT, 1.0f, 1.0f);
            }
        }
        notifiedEndermites.retainAll(currentIds);
    }

    private void scanXpOrbs() {
        xpOrbTargets.clear();
        if (!trackXpOrbs.getValue() || mc.level == null || mc.player == null) return;
        boolean isSpectral = renderMode.getValue() == RenderMode.SPECTRAL;
        AABB searchBox = new AABB(mc.player.blockPosition()).inflate(range.getValue() * 16);
        for (ExperienceOrb orb : mc.level.getEntitiesOfClass(ExperienceOrb.class, searchBox, e -> true)) {
            xpOrbTargets.add(orb);
            if (isSpectral) GlowingRegistry.add(orb.getId(), xpOrbColor.getValue().getRGB());
            else GlowingRegistry.remove(orb.getId());
        }
    }

    private void scanSpawnerTorches() {
        spawnerTorches.clear();
        if (!trackSpawners.getValue() || !highlightSpawnerTorches.getValue()) return;

        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            if (entry.getValue() != TargetType.SPAWNER) continue;
            BlockPos spawnerPos = entry.getKey();
            if (!isChunkLoaded(spawnerPos)) continue;
            for (int x = -5; x <= 5; x++) {
                for (int y = -5; y <= 5; y++) {
                    for (int z = -5; z <= 5; z++) {
                        BlockPos pos = spawnerPos.offset(x, y, z);
                        Block b = mc.level.getBlockState(pos).getBlock();
                        if (b == Blocks.TORCH || b == Blocks.WALL_TORCH || b == Blocks.SOUL_TORCH || b == Blocks.SOUL_WALL_TORCH) {
                            spawnerTorches.add(pos.immutable());
                        }
                    }
                }
            }
        }
    }

    private void scanNewChunks(int centerChunkX, int centerChunkZ) {
        int r = range.getValue();
        int rSq = r * r;

        scannedChunks.removeIf(cp -> {
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
                if (processChunk(centerChunkX + x, centerChunkZ + minZ, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                if (chunksScanned >= limit) break outer;
                if (minZ != maxZ) {
                    if (processChunk(centerChunkX + x, centerChunkZ + maxZ, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                    if (chunksScanned >= limit) break outer;
                }
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                if (processChunk(centerChunkX + minX, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                if (chunksScanned >= limit) break outer;
                if (minX != maxX) {
                    if (processChunk(centerChunkX + maxX, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                    if (chunksScanned >= limit) break outer;
                }
            }
        }
    }

    private boolean processChunk(int cx, int cz, int rSq, int centerChunkX, int centerChunkZ) {
        int dx = cx - centerChunkX, dz = cz - centerChunkZ;
        if (dx * dx + dz * dz > rSq) return false;

        ChunkPos cp = new ChunkPos(cx, cz);
        if (scannedChunks.contains(cp)) return false;
        if (!mc.level.getChunkSource().hasChunk(cx, cz)) return false;

        LevelChunk chunk = mc.level.getChunk(cx, cz);
        scanChunk(chunk);
        scanBlockEntitiesInChunk(chunk);
        scannedChunks.add(cp);
        return true;
    }

    private void scanChunk(LevelChunk chunk) {
        if (mc.level == null) return;

        boolean isOverworld = "minecraft:overworld".equals(lastDimension);
        boolean doCustomBlocks = scanCustomBlocks.getValue() && !filterBlocks.getList().isEmpty() && isOverworld;
        boolean doMisrotated = trackMisrotatedDeepslate.getValue() && isOverworld;
        boolean doLowY = trackLowYStoneDirt.getValue();
        if (!doCustomBlocks && !doMisrotated && !doLowY) return;

        int maxY = dungeonYLevel.getValue();
        LevelChunkSection[] sections = chunk.getSections();
        ChunkPos currentChunkPos = chunk.getPos();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;

            int sectionY = mc.level.getMinSectionY() + i;
            int sectionMinY = sectionY * 16;
            if (sectionMinY > maxY) continue;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    int worldY = sectionMinY + y;
                    if (worldY > maxY) continue;
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        Block block = state.getBlock();
                        BlockPos blockPos = new BlockPos((currentChunkPos.x << 4) + x, worldY, (currentChunkPos.z << 4) + z);

                        if (doCustomBlocks && filterBlocks.getList().contains(block)) targets.put(blockPos, TargetType.CUSTOM_BLOCK);
                        if (doMisrotated && block == Blocks.DEEPSLATE
                                && state.hasProperty(BlockStateProperties.AXIS)
                                && state.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y) {
                            targets.put(blockPos, TargetType.MISROTATED_DEEPSLATE);
                        }
                        if (doLowY && worldY < lowYLevel.getValue()) {
                            if (block == Blocks.STONE || block == Blocks.DIRT) targets.put(blockPos, TargetType.LOW_Y_STONE_DIRT);
                        }
                    }
                }
            }
        }
    }

    private void scanBlockEntitiesInChunk(LevelChunk chunk) {
        int maxY = dungeonYLevel.getValue();
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            BlockPos pos = be.getBlockPos();
            if (pos.getY() > maxY) continue;

            if ((trackSpawners.getValue() || autoBreakSpawners.getValue()) && be instanceof SpawnerBlockEntity) {
                targets.put(pos, TargetType.SPAWNER);
            } else if (trackChests.getValue()) {
                Block block = mc.level.getBlockState(pos).getBlock();
                if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) targets.put(pos, TargetType.CHEST);
            }
        }
    }

    private TargetType getMinecartType(MinecartChest cart) {
        Vec3 exactPos = cart.position();
        BlockPos blockPos = cart.blockPosition();

        boolean isDisplaced;
        BlockState stateAtPos = mc.level.getBlockState(blockPos);

        if (!stateAtPos.isAir() && !stateAtPos.getCollisionShape(mc.level, blockPos).isEmpty() && !(stateAtPos.getBlock() instanceof BaseRailBlock)) {
            isDisplaced = true;
        } else {
            double closestCenterX = blockPos.getX() + 0.5;
            double closestCenterZ = blockPos.getZ() + 0.5;
            double offsetX = Math.abs(exactPos.x - closestCenterX);
            double offsetZ = Math.abs(exactPos.z - closestCenterZ);
            isDisplaced = offsetX > 0.1 || offsetZ > 0.1;

            if (!isDisplaced) {
                boolean hasRail = false;
                for (int y = 0; y >= -1; y--) {
                    if (mc.level.getBlockState(blockPos.offset(0, y, 0)).getBlock() instanceof BaseRailBlock) { hasRail = true; break; }
                }
                if (!hasRail) isDisplaced = true;
            }
        }
        if (isDisplaced) return TargetType.DISPLACED_CHEST_MINECART;

        float yaw = ((cart.getYRot() % 360) + 360) % 360;
        float remainder = yaw % 90;
        boolean isMisrotated = remainder > 5.0f && remainder < 85.0f;
        if (isMisrotated) return TargetType.MISROTATED_CHEST_MINECART;

        return TargetType.CHEST_MINECART;
    }

    private void scanChestMinecarts() {
        if (!trackChestMinecarts.getValue() && !trackAnomalousMinecarts.getValue()) return;

        boolean isSpectral = renderMode.getValue() == RenderMode.SPECTRAL;
        int blockRange = range.getValue() * 16;
        int worldHeight = mc.level.getHeight();
        AABB searchBox = new AABB(mc.player.blockPosition()).inflate(blockRange, worldHeight, blockRange);

        Set<BlockPos> currentPositions = new HashSet<>();
        for (MinecartChest minecart : mc.level.getEntitiesOfClass(MinecartChest.class, searchBox, entity -> true)) {
            BlockPos pos = minecart.blockPosition();
            currentPositions.add(pos);

            TargetType type = getMinecartType(minecart);
            TargetType targetType = null;
            int color = 0;

            if (type == TargetType.DISPLACED_CHEST_MINECART && trackAnomalousMinecarts.getValue()) {
                targetType = TargetType.DISPLACED_CHEST_MINECART;
                color = displacedMinecartColor.getValue().getRGB();
            } else if (type == TargetType.MISROTATED_CHEST_MINECART && trackAnomalousMinecarts.getValue()) {
                targetType = TargetType.MISROTATED_CHEST_MINECART;
                color = misrotatedMinecartColor.getValue().getRGB();
            } else if (trackChestMinecarts.getValue()) {
                targetType = TargetType.CHEST_MINECART;
                color = chestMinecartColor.getValue().getRGB();
            }

            if (targetType != null) {
                targets.put(pos, targetType);
                if (isSpectral) GlowingRegistry.add(minecart.getId(), color);
                else GlowingRegistry.remove(minecart.getId());

                if (targetType == TargetType.DISPLACED_CHEST_MINECART || targetType == TargetType.MISROTATED_CHEST_MINECART) {
                    if (notifiedAnomalousMinecarts.add(minecart.getId())) {
                        if (targetType == TargetType.DISPLACED_CHEST_MINECART) info("§bDisplaced minecart detected!");
                        else info("§5Misrotated minecart detected!");
                        mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 0.5f);
                    }
                }
            }
        }

        targets.entrySet().removeIf(entry -> isMinecartType(entry.getValue()) && !currentPositions.contains(entry.getKey()));
    }

    private void pruneBlockTargets() {
        if (mc.level == null || mc.player == null) return;

        Set<BlockPos> toRemove = new HashSet<>();
        Set<ChunkPos> chunksToRescan = new HashSet<>();

        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            TargetType type = entry.getValue();
            if (isMinecartType(type)) continue;

            BlockPos pos = entry.getKey();
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;

            if (mc.level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                Block currentBlock = mc.level.getBlockState(pos).getBlock();
                if (mc.level.getBlockState(pos).isAir() || !validateBlockType(currentBlock, type)) toRemove.add(pos);
            } else {
                chunksToRescan.add(new ChunkPos(chunkX, chunkZ));
            }
        }

        for (BlockPos pos : toRemove) targets.remove(pos);
        if (!chunksToRescan.isEmpty()) scannedChunks.removeAll(chunksToRescan);
    }

    private void pruneCheckedEntityIds() {
        if (checkedEntityIds.isEmpty() && notifiedAnomalousMinecarts.isEmpty()) return;
        Set<Integer> liveIds = new HashSet<>();
        for (MinecartChest e : mc.level.getEntitiesOfClass(MinecartChest.class, new AABB(mc.player.blockPosition()).inflate(range.getValue() * 16), Entity::isAlive)) {
            liveIds.add(e.getId());
        }
        checkedEntityIds.retainAll(liveIds);
        notifiedAnomalousMinecarts.retainAll(liveIds);
    }

    private void pruneCheckedContainers() {
        if (checkedContainers.isEmpty()) return;
        checkedContainers.removeIf(pos -> !targets.containsKey(pos));
    }

    private void cleanupDistantTargets(BlockPos playerPos) {
        long cleanupRangeSq = (long) Math.pow(range.getValue() * 16 + 32, 2);
        targets.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            double dx = pos.getX() - playerPos.getX();
            double dz = pos.getZ() - playerPos.getZ();
            if (dx * dx + dz * dz > cleanupRangeSq) {
                scannedChunks.remove(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
                return true;
            }
            return false;
        });
    }

    private void removeNeighborFromChecked(BlockPos pos) {
        if (pos == null || mc.level == null) return;
        Block block = mc.level.getBlockState(pos).getBlock();
        if (block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST) return;
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos neighbor = pos.relative(dir);
            if (mc.level.getBlockState(neighbor).getBlock() == block) { checkedContainers.remove(neighbor); break; }
        }
    }

    private AABB getMinecartChestBox(MinecartChest minecart) {
        AABB entityBox = minecart.getBoundingBox();
        double chestSize = 14.0 / 16.0;
        double xPadding = (entityBox.getXsize() - chestSize) / 2.0;
        double zPadding = (entityBox.getZsize() - chestSize) / 2.0;
        double chestHeight = 10.0 / 16.0;
        double minY = entityBox.maxY - chestHeight;
        return new AABB(entityBox.minX + xPadding, minY, entityBox.minZ + zPadding, entityBox.maxX - xPadding, entityBox.maxY, entityBox.maxZ - zPadding);
    }

    private AABB createPaddedBox(BlockPos pos) {
        return new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
    }

    private boolean validateBlockType(Block block, TargetType type) {
        return switch (type) {
            case SPAWNER -> block == Blocks.SPAWNER;
            case CHEST -> block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST;
            case CHEST_MINECART, MISROTATED_CHEST_MINECART, DISPLACED_CHEST_MINECART -> true;
            case CUSTOM_BLOCK -> filterBlocks.getList().contains(block);
            case MISROTATED_DEEPSLATE -> block == Blocks.DEEPSLATE;
            case LOW_Y_STONE_DIRT -> block == Blocks.STONE || block == Blocks.DIRT;
        };
    }

    private Color getColor(TargetType type) {
        return switch (type) {
            case SPAWNER -> trackSpawners.getValue() ? spawnerColor.getValue() : null;
            case CHEST -> chestColor.getValue();
            case CHEST_MINECART -> chestMinecartColor.getValue();
            case MISROTATED_CHEST_MINECART -> misrotatedMinecartColor.getValue();
            case DISPLACED_CHEST_MINECART -> displacedMinecartColor.getValue();
            case CUSTOM_BLOCK -> customBlockColor.getValue();
            case MISROTATED_DEEPSLATE -> misrotatedDeepslateColor.getValue();
            case LOW_Y_STONE_DIRT -> lowYStoneDirtColor.getValue();
        };
    }

    private void resetSoftState() {
        wasAutoOpened = false;
        silentOpenPending = false;
        silentFoundWhitelisted = false;
        pendingBreakCheck = false;
        silentSlotReadRetryTimer = 0;
        interactTimeoutTimer = 0;
        lastOpenedContainer = null;
        lastOpenedEntity = null;
        hasPlayedSoundForCurrentScreen = false;
    }

    private void restoreSlot() {
        if (silentMode.getValue() && previousSlot >= 0 && mc.player != null) {
            mc.player.getInventory().selected = previousSlot;
            previousSlot = -1;
        }
    }

    private int findAxe() {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).getItem() instanceof AxeItem) return i;
        return -1;
    }

    private int findPickaxe() {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).getItem() instanceof PickaxeItem) return i;
        return -1;
    }

    private int findSword() {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).getItem() instanceof SwordItem) return i;
        return -1;
    }

    private int getRandomizedDelay(int baseDelay) {
        if (baseDelay <= 0) return 1;
        return (int) Math.max(1, Math.round(baseDelay * (1.0 + (Math.random() - 0.5) * 0.8)));
    }

    // ── Public API ──
    public boolean shouldShowStealDumpButtons() { return isToggled() && stealDumpButtons.getValue(); }
    public int getBrokenChestsCount() { return brokenChestsCount; }
    public int getLootFoundCount() { return lootFoundCount; }

    public int getTotalTargets() {
        if (mc.player == null || mc.level == null) return 0;
        double rangeSq = Math.pow(range.getValue() * 16.0, 2);
        int count = 0;
        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            BlockPos pos = entry.getKey();
            TargetType type = entry.getValue();
            double dx = pos.getX() + 0.5 - mc.player.getX();
            double dz = pos.getZ() + 0.5 - mc.player.getZ();
            if (dx * dx + dz * dz > rangeSq) continue;
            if (!isChunkLoaded(pos)) continue;
            if (!isMinecartType(type)) {
                Block currentBlock = mc.level.getBlockState(pos).getBlock();
                if (!validateBlockType(currentBlock, type)) continue;
            }
            count++;
        }
        return count;
    }

    public Map<TargetType, Integer> getTargetCounts() {
        Map<TargetType, Integer> counts = new EnumMap<>(TargetType.class);
        for (TargetType type : TargetType.values()) counts.put(type, 0);
        if (mc.player == null || mc.level == null) return counts;

        double rangeSq = Math.pow(range.getValue() * 16.0, 2);
        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            BlockPos pos = entry.getKey();
            TargetType type = entry.getValue();
            double dx = pos.getX() + 0.5 - mc.player.getX();
            double dz = pos.getZ() + 0.5 - mc.player.getZ();
            if (dx * dx + dz * dz > rangeSq) continue;
            if (!isChunkLoaded(pos)) continue;
            if (!isMinecartType(type)) {
                Block currentBlock = mc.level.getBlockState(pos).getBlock();
                if (!validateBlockType(currentBlock, type)) continue;
            }
            counts.put(type, counts.get(type) + 1);
        }
        return counts;
    }

    private record BeamData(AABB box, Color color) {}
}
