package com.example.addon.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.addon.Tim;
import com.example.addon.utils.RenderUtils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.IBaritoneProcess;

import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.BindSetting;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.core.bind.key.NullKey;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;

public class InspectorGadget extends ToggleableModule {

    public enum ScanState { SETUP, MOVING_TO_TILE, MOVING_TO_TARGET, OPENING_TARGET, WAITING, COOLDOWN, COMPLETE }

    public enum HighlightMode { GLOW, SPECTRAL, PULSE }

    public enum StorageTarget {
        Chests("Chests", Blocks.CHEST, Blocks.TRAPPED_CHEST),
        Barrels("Barrels", Blocks.BARREL),
        Chests_And_Barrels("Chests & Barrels", Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL),
        Dispensers_And_Droppers("Dispensers & Droppers", Blocks.DISPENSER, Blocks.DROPPER),
        Hoppers("Hoppers", Blocks.HOPPER),
        Furnaces("Furnaces", Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER),
        All_Standard("All Standard Storage",
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL,
            Blocks.DISPENSER, Blocks.DROPPER, Blocks.HOPPER,
            Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
            Blocks.BREWING_STAND);

        public final String title;
        public final Block[] blocks;

        StorageTarget(String title, Block... blocks) {
            this.title = title;
            this.blocks = blocks;
        }

        @Override
        public String toString() { return title; }

        public boolean contains(Block block) {
            for (Block b : blocks) {
                if (b == block) return true;
            }
            return false;
        }
    }

    public enum CompletionSound {
        None("None", null),
        LevelUp("Level Up", SoundEvents.PLAYER_LEVELUP),
        XpPickup("XP Pickup", SoundEvents.EXPERIENCE_ORB_PICKUP),
        TotemPop("Totem Pop", SoundEvents.TOTEM_USE),
        VillagerYes("Villager Yes", SoundEvents.VILLAGER_YES),
        Pling("Pling", SoundEvents.NOTE_BLOCK_PLING.value()),
        Bell("Bell", SoundEvents.BELL_BLOCK);

        public final String title;
        public final SoundEvent event;

        CompletionSound(String title, SoundEvent event) {
            this.title = title;
            this.event = event;
        }

        @Override
        public String toString() { return title; }
    }

    private final EnumSetting<StorageTarget> targetStorage = new EnumSetting<>("target-storage", "Which storage blocks to scan and open.", StorageTarget.Chests_And_Barrels);
    private final BindSetting addTileKey = new BindSetting("add-tile-key", "Key to add a tile while looking at a block.", NullKey.INSTANCE);
    private final BindSetting startKey = new BindSetting("start-key", "Key to start the automated pathing sequence.", NullKey.INSTANCE);
    private final BindSetting clearKey = new BindSetting("clear-key", "Key to clear all created tiles.", NullKey.INSTANCE);
    private final BindSetting pauseKey = new BindSetting("pause-key", "Pauses the pathing so you can chat or move. Press again to resume.", NullKey.INSTANCE);
    private final NumberSetting<Integer> tileScanRange = new NumberSetting<>("tile-scan-range", "Radius to scan for storage blocks around each tile.", 5, 2, 10);
    private final NumberSetting<Integer> openDelay = new NumberSetting<>("open-delay", "How long to wait between opening and closing containers to prevent anti-cheat kicks.", 15, 2, 60);
    private final EnumSetting<CompletionSound> completionSound = new EnumSetting<>("completion-sound", "Sound played when all tiles have been scanned.", CompletionSound.LevelUp);

    // ── Visual Settings ──
    private final EnumSetting<HighlightMode> highlightMode = new EnumSetting<>("highlight-mode", "GLOW = layered bloom boxes. SPECTRAL = subtle fill and outline. PULSE = fading in/out bloom.", HighlightMode.GLOW);
    private final NumberSetting<Integer> glowLayers = new NumberSetting<>("glow-layers", "Bloom layer count.", 4, 1, 8)
        .setVisibility(() -> highlightMode.getValue() == HighlightMode.GLOW || highlightMode.getValue() == HighlightMode.PULSE);
    private final NumberSetting<Double> glowSpread = new NumberSetting<>("glow-spread", "Bloom spread.", 0.05, 0.01, 0.2)
        .setVisibility(() -> highlightMode.getValue() == HighlightMode.GLOW || highlightMode.getValue() == HighlightMode.PULSE);
    private final NumberSetting<Integer> glowBaseAlpha = new NumberSetting<>("glow-base-alpha", "Bloom alpha.", 60, 4, 150)
        .setVisibility(() -> highlightMode.getValue() == HighlightMode.GLOW);
    private final NumberSetting<Integer> spectralLineAlpha = new NumberSetting<>("spectral-line-alpha", "Outline alpha.", 255, 0, 255)
        .setVisibility(() -> highlightMode.getValue() == HighlightMode.SPECTRAL);
    private final NumberSetting<Integer> spectralFillAlpha = new NumberSetting<>("spectral-fill-alpha", "Fill alpha.", 30, 0, 255)
        .setVisibility(() -> highlightMode.getValue() == HighlightMode.SPECTRAL);
    private final NumberSetting<Double> pulseSpeed = new NumberSetting<>("pulse-speed", "Pulse cycle speed. 1.0 = one full fade in/out per second.", 1.0, 0.1, 5.0)
        .setVisibility(() -> highlightMode.getValue() == HighlightMode.PULSE);
    private final NumberSetting<Integer> pulseMinAlpha = new NumberSetting<>("pulse-min-alpha", "Lowest alpha reached during the pulse (0 = invisible).", 15, 0, 255)
        .setVisibility(() -> highlightMode.getValue() == HighlightMode.PULSE);
    private final NumberSetting<Integer> pulseMaxAlpha = new NumberSetting<>("pulse-max-alpha", "Peak alpha reached during the pulse.", 220, 15, 255)
        .setVisibility(() -> highlightMode.getValue() == HighlightMode.PULSE);
    private final ColorSetting highlightColor = new ColorSetting("storage-color", "Color of the storage blocks found during scan.", new Color(255, 215, 0, 200));
    private final ColorSetting pathColor = new ColorSetting("tile-color", "Color of the pathing tiles and sequence pillars.", new Color(0, 255, 100, 200));

    // ── State ──
    private ScanState currentState = ScanState.SETUP;
    private final List<BlockPos> pathTiles = new ArrayList<>();
    private final List<BlockPos> localTargets = new ArrayList<>();
    private final Set<BlockPos> visitedTargets = new HashSet<>();

    private int tileIndex = 0;
    private int targetIndex = 0;
    private int waitTimer = 0;
    private int pathTimeout = 0;
    private boolean issuedMoveCommand = false;

    private boolean wasAddPressed = false;
    private boolean wasStartPressed = false;
    private boolean wasClearPressed = false;
    private boolean wasPausePressed = false;
    private boolean isPaused = false;

    private BlockPos currentInteractTile = null;
    private BlockPos currentPathTarget = null;

    private int openedCount = 0;
    private int shulkerCount = 0;

    public InspectorGadget() {
        super("inspector-gadget", "Walks a custom path of tiles to scan nearby storage blocks using Baritone.", Tim.CATEGORY);
        this.registerSettings(
            targetStorage, addTileKey, startKey, clearKey, pauseKey, tileScanRange, openDelay, completionSound,
            highlightMode, glowLayers, glowSpread, glowBaseAlpha, spectralLineAlpha, spectralFillAlpha,
            pulseSpeed, pulseMinAlpha, pulseMaxAlpha, highlightColor, pathColor
        );
    }

    @Override
    public void onEnable() {
        currentState = ScanState.SETUP;
        pathTiles.clear();
        localTargets.clear();
        visitedTargets.clear();
        tileIndex = 0;
        targetIndex = 0;
        waitTimer = 0;
        pathTimeout = 0;
        issuedMoveCommand = false;

        wasAddPressed = false;
        wasStartPressed = false;
        wasClearPressed = false;
        wasPausePressed = false;
        isPaused = false;

        currentInteractTile = null;
        currentPathTarget = null;

        resetStats();
    }

    @Override
    public void onDisable() {
        stopMovement();
        closeScreen();
        resetTargets();
    }

    private void closeScreen() {
        if (mc.screen != null && !(mc.screen instanceof InventoryScreen) && mc.player != null) {
            mc.player.closeContainer();
        }
    }

    private void resetTargets() {
        pathTiles.clear();
        localTargets.clear();
        visitedTargets.clear();
        currentInteractTile = null;
        currentPathTarget = null;
    }

    public int getOpenedCount() { return openedCount; }
    public int getNearbyCount() { return localTargets.size(); }
    public int getShulkerCount() { return shulkerCount; }

    public void resetStats() {
        openedCount = 0;
        shulkerCount = 0;
    }

    // ─────────────────────────── Logic ───────────────────────────
    @Subscribe
    private void onTick(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        if (currentState != ScanState.SETUP) {
            boolean pausePressed = pauseKey.getValue().isKeyDown();
            if (pausePressed && !wasPausePressed) {
                isPaused = !isPaused;
                if (isPaused) {
                    stopMovement();
                    closeScreen();
                    sendNotification(NotificationType.INFO, "Pathing Paused.");
                } else {
                    pathTimeout = 0;
                    issuedMoveCommand = false;
                    sendNotification(NotificationType.INFO, "Pathing Resumed.");
                }
            }
            wasPausePressed = pausePressed;

            if (isPaused) return;
        }

        if (currentState == ScanState.SETUP) {
            boolean addPressed = addTileKey.getValue().isKeyDown();
            boolean startPressed = startKey.getValue().isKeyDown();
            boolean clearPressed = clearKey.getValue().isKeyDown();

            if (addPressed && !wasAddPressed) addTile();

            if (startPressed && !wasStartPressed) {
                if (pathTiles.isEmpty()) {
                    sendNotification(NotificationType.ERROR, "No tiles created. Look at blocks and use the Add Tile key.");
                } else {
                    currentState = ScanState.MOVING_TO_TILE;
                    tileIndex = 0;
                    visitedTargets.clear();
                    pathTimeout = 0;
                    issuedMoveCommand = false;
                    sendNotification(NotificationType.INFO, String.format("Starting pathing sequence for %d tiles.", pathTiles.size()));
                }
            }

            if (clearPressed && !wasClearPressed) {
                pathTiles.clear();
                sendNotification(NotificationType.INFO, "Cleared all tiles.");
            }

            wasAddPressed = addPressed;
            wasStartPressed = startPressed;
            wasClearPressed = clearPressed;
            return;
        }

        switch (currentState) {
            case MOVING_TO_TILE -> handleMoveToTile();
            case MOVING_TO_TARGET -> handleMoveToTarget();
            case OPENING_TARGET -> handleOpeningTarget();
            case WAITING -> handleWaiting();
            case COOLDOWN -> handleCooldown();
            case COMPLETE -> handleCompletion();
            default -> {}
        }
    }

    private void addTile() {
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            BlockPos target = ((BlockHitResult) mc.hitResult).getBlockPos();
            pathTiles.add(target);
            sendNotification(NotificationType.INFO, String.format("Added tile %d", pathTiles.size()));
        } else {
            sendNotification(NotificationType.WARNING, "You must look at a block to add a tile.");
        }
    }

    private void handleMoveToTile() {
        if (tileIndex >= pathTiles.size()) {
            currentState = ScanState.COMPLETE;
            return;
        }

        currentPathTarget = pathTiles.get(tileIndex);
        BlockPos standPos = currentPathTarget.above();
        Vec3 targetPos = Vec3.atCenterOf(standPos);
        double distance = mc.player.position().distanceTo(targetPos);

        pathTimeout++;
        if (pathTimeout > 1000) {
            sendNotification(NotificationType.WARNING, "Timeout while pathing to tile. Skipping.");
            tileIndex++;
            currentPathTarget = null;
            pathTimeout = 0;
            issuedMoveCommand = false;
            stopMovement();
            return;
        }

        if (distance <= 1.5 && isBaritoneIdle()) {
            stopMovement();
            issuedMoveCommand = false;
            pathTimeout = 0;
            populateLocalTargets();

            if (localTargets.isEmpty()) {
                sendNotification(NotificationType.INFO, String.format("Tile %d: No new targets found nearby. Moving to next tile.", tileIndex + 1));
                tileIndex++;
                currentPathTarget = null;
            } else {
                sendNotification(NotificationType.INFO, String.format("Tile %d: Found %d targets. Scanning...", tileIndex + 1, localTargets.size()));
                targetIndex = 0;
                currentState = ScanState.MOVING_TO_TARGET;
            }
        } else {
            if (!issuedMoveCommand) {
                pathToBlock(standPos);
                issuedMoveCommand = true;
            }
        }
    }

    private void populateLocalTargets() {
        localTargets.clear();
        BlockPos center = pathTiles.get(tileIndex);
        int range = tileScanRange.getValue();
        StorageTarget storageFilter = targetStorage.getValue();

        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    if (x*x + y*y + z*z > range*range) continue;
                    BlockPos checkPos = center.offset(x, y, z);
                    Block b = mc.level.getBlockState(checkPos).getBlock();

                    if (b instanceof ShulkerBoxBlock) {
                        shulkerCount++;
                    }

                    if (storageFilter.contains(b) && !visitedTargets.contains(checkPos)) {
                        localTargets.add(checkPos);
                    }
                }
            }
        }

        // Greedy nearest-neighbour sort from the player's current position to avoid zigzag.
        List<BlockPos> ordered = new ArrayList<>();
        Set<BlockPos> remaining = new HashSet<>(localTargets);
        Vec3 cursor = mc.player.position();
        while (!remaining.isEmpty()) {
            BlockPos nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (BlockPos p : remaining) {
                double d = p.distToCenterSqr(cursor);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearest = p;
                }
            }
            ordered.add(nearest);
            remaining.remove(nearest);
            cursor = Vec3.atCenterOf(nearest);
        }
        localTargets.clear();
        localTargets.addAll(ordered);
    }

    private void handleMoveToTarget() {
        if (targetIndex >= localTargets.size()) {
            tileIndex++;
            localTargets.clear();
            currentInteractTile = null;
            currentPathTarget = null;
            currentState = ScanState.MOVING_TO_TILE;
            return;
        }

        BlockPos blockTarget = localTargets.get(targetIndex);
        Block block = mc.level.getBlockState(blockTarget).getBlock();
        StorageTarget storageFilter = targetStorage.getValue();

        if (!storageFilter.contains(block) || visitedTargets.contains(blockTarget)) {
            targetIndex++;
            return;
        }

        double distanceToChest = mc.player.position().distanceTo(Vec3.atCenterOf(blockTarget));

        if (currentInteractTile == null || !isStandable(currentInteractTile)) {
            currentInteractTile = null;
            List<BlockPos> validTiles = getValidInteractTiles(blockTarget);

            if (validTiles.isEmpty()) {
                // Fallback for elevated chests: Path to the block directly beneath the chest
                BlockPos fallbackTile = blockTarget.below();
                while (fallbackTile.getY() > mc.level.getMinY() && mc.level.getBlockState(fallbackTile).getCollisionShape(mc.level, fallbackTile).isEmpty()) {
                    fallbackTile = fallbackTile.below();
                }

                if (isStandable(fallbackTile.above())) {
                    currentInteractTile = fallbackTile.above();
                } else {
                    if (distanceToChest <= 4.2) {
                        stopMovement();
                        issuedMoveCommand = false;
                        currentState = ScanState.OPENING_TARGET;
                        return;
                    }
                    sendNotification(NotificationType.WARNING, "Cannot path to block. Skipping.");
                    markVisited(blockTarget);
                    targetIndex++;
                    return;
                }
            } else {
                validTiles.sort(Comparator.comparingDouble(pos -> pos.distSqr(mc.player.blockPosition())));
                currentInteractTile = validTiles.get(0);
            }
            pathTimeout = 0;
            issuedMoveCommand = false;
        }

        Vec3 targetPos = Vec3.atCenterOf(currentInteractTile);
        double distanceToTile = mc.player.position().distanceTo(targetPos);

        pathTimeout++;
        if (pathTimeout > 1000) {
            sendNotification(NotificationType.WARNING, "Timeout while pathing to target. Skipping.");
            markVisited(blockTarget);
            targetIndex++;
            currentInteractTile = null;
            pathTimeout = 0;
            issuedMoveCommand = false;
            stopMovement();
            return;
        }

        if (distanceToTile <= 1.5 || distanceToChest <= 4.2) {
            stopMovement();
            issuedMoveCommand = false;
            pathTimeout = 0;
            currentState = ScanState.OPENING_TARGET;
        } else {
            if (!issuedMoveCommand) {
                pathToBlock(currentInteractTile);
                issuedMoveCommand = true;
            }
        }
    }

    private void handleOpeningTarget() {
        BlockPos blockTarget = localTargets.get(targetIndex);

        Vec3 eye = mc.player.getEyePosition();
        Vec3 blockCenter = Vec3.atCenterOf(blockTarget);

        Vec3 diff = eye.subtract(blockCenter);

        Direction side;
        double ax = Math.abs(diff.x), ay = Math.abs(diff.y), az = Math.abs(diff.z);
        if (ax >= ay && ax >= az) {
            side = diff.x > 0 ? Direction.EAST : Direction.WEST;
        } else if (ay >= ax && ay >= az) {
            side = diff.y > 0 ? Direction.UP : Direction.DOWN;
        } else {
            side = diff.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }

        Direction opp = side.getOpposite();
        Vec3 hitVec = Vec3.atCenterOf(blockTarget)
            .add(new Vec3(opp.getStepX(), opp.getStepY(), opp.getStepZ()).scale(0.5));

        // Strictly horizontal difference for Yaw to prevent wild swinging when the chest is directly above/below
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-(blockCenter.x - eye.x), blockCenter.z - eye.z)));
        mc.player.setYHeadRot(mc.player.getYRot());

        double dist3d = blockCenter.distanceTo(eye);
        if (dist3d > 0) {
            mc.player.setXRot((float) -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, (blockCenter.y - eye.y) / dist3d)))));
        }

        BlockHitResult hitResult = new BlockHitResult(hitVec, side, blockTarget, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
        mc.player.swing(InteractionHand.MAIN_HAND);

        currentState = ScanState.WAITING;
        waitTimer = 0;
    }

    private void handleWaiting() {
        waitTimer++;

        if (mc.screen instanceof AbstractContainerScreen<?> && !(mc.screen instanceof InventoryScreen)) {
            if (waitTimer >= openDelay.getValue()) {
                mc.player.closeContainer();
                openedCount++;
                markVisited(localTargets.get(targetIndex));
                targetIndex++;
                currentInteractTile = null;
                waitTimer = 0;
                currentState = ScanState.COOLDOWN;
            }
        } else if (waitTimer > openDelay.getValue() + 20) {
            sendNotification(NotificationType.WARNING, "Failed to open block. Skipping...");
            markVisited(localTargets.get(targetIndex));
            targetIndex++;
            currentInteractTile = null;
            waitTimer = 0;
            currentState = ScanState.COOLDOWN;
        }
    }

    private void handleCooldown() {
        waitTimer++;
        if (waitTimer >= openDelay.getValue()) {
            waitTimer = 0;
            currentState = ScanState.MOVING_TO_TARGET;
        }
    }

    private void handleCompletion() {
        stopMovement();
        resetTargets();

        CompletionSound soundSetting = completionSound.getValue();
        if (soundSetting != CompletionSound.None && soundSetting.event != null && mc.player != null) {
            try {
                mc.player.playSound(soundSetting.event, 1.0f, 1.0f);
            } catch (Exception ignored) {}
        }

        sendNotification(NotificationType.INFO, "Inspector Gadget: Path complete!");
        this.toggle();
    }

    // ─────────────────────────── Baritone Movement Engine ───────────────────────────
    private void pathToBlock(BlockPos standPos) {
        if (standPos == null) return;
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (isBaritoneIdle()) {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(standPos));
        }
    }

    private void stopMovement() {
        if (mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyShift.setDown(false);

        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            IPathingBehavior pathing = baritone.getPathingBehavior();
            if (pathing != null && (pathing.isPathing() || pathing.hasPath() || pathing.getInProgress().isPresent())) {
                pathing.cancelEverything();
            }
        } catch (Exception ignored) {}
    }

    private boolean isBaritoneIdle() {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            IPathingBehavior pathing = baritone.getPathingBehavior();
            if (pathing == null) return true;

            boolean activeProcess = baritone.getPathingControlManager()
                    .mostRecentInControl()
                    .map(IBaritoneProcess::isActive)
                    .orElse(false);

            return !activeProcess && !pathing.isPathing() && !pathing.hasPath() && pathing.getInProgress().isEmpty();
        } catch (Exception e) {
            return true;
        }
    }

    // ─────────────────────────── Validation Helpers ───────────────────────────
    private boolean isStandable(BlockPos pos) {
        BlockState feet = mc.level.getBlockState(pos);
        BlockState head = mc.level.getBlockState(pos.above());
        BlockState floor = mc.level.getBlockState(pos.below());

        if (!feet.getCollisionShape(mc.level, pos).isEmpty()) return false;
        if (!head.getCollisionShape(mc.level, pos.above()).isEmpty()) return false;
        if (floor.getCollisionShape(mc.level, pos.below()).isEmpty()) return false;

        Block floorBlock = floor.getBlock();
        if (targetStorage.getValue().contains(floorBlock) || floorBlock instanceof ShulkerBoxBlock || floorBlock == Blocks.ENDER_CHEST) {
            return false;
        }

        return true;
    }

    private List<BlockPos> getValidInteractTiles(BlockPos blockPos) {
        List<BlockPos> tiles = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (dx * dx + dz * dz > 5) continue;
                for (int dy = -5; dy <= 5; dy++) {
                    BlockPos tilePos = blockPos.offset(dx, dy, dz);
                    if (isStandable(tilePos)) {
                        tiles.add(tilePos);
                    }
                }
            }
        }
        return tiles;
    }

    // Marks a chest as visited, including its double chest half if it has one.
    private void markVisited(BlockPos pos) {
        visitedTargets.add(pos);
        BlockState state = mc.level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.CHEST_TYPE)) {
            ChestType type = state.getValue(BlockStateProperties.CHEST_TYPE);
            if (type != ChestType.SINGLE) {
                Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
                Direction connectedDir = type == ChestType.LEFT ? facing.getClockWise() : facing.getCounterClockWise();
                BlockPos otherHalf = pos.relative(connectedDir);
                visitedTargets.add(otherHalf);
            }
        }
    }

    // ─────────────────────────── Rendering ───────────────────────────
    @Subscribe
    private void onRender(EventRender3D event) {
        if (mc.level == null) return;
        if (pathTiles.isEmpty() && localTargets.isEmpty()) return;

        IRenderer3D renderer = event.getRenderer();
        renderer.begin(event.getMatrixStack());

        Color pColor = pathColor.getValue();
        Color cColor = highlightColor.getValue();
        HighlightMode mode = highlightMode.getValue();

        for (int i = 0; i < pathTiles.size(); i++) {
            BlockPos pos = pathTiles.get(i);

            AABB flatTileBox = new AABB(
                pos.getX(), pos.getY() + 1.0, pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.02, pos.getZ() + 1.0
            );

            double height = Math.min((i + 1) * 0.25, 3.0);
            AABB pillarBox = new AABB(
                pos.getX() + 0.4, pos.getY() + 1.0, pos.getZ() + 0.4,
                pos.getX() + 0.6, pos.getY() + 1.0 + height, pos.getZ() + 0.6
            );

            if (mode == HighlightMode.GLOW) {
                renderGlowLayers(renderer, flatTileBox, pColor);
                renderGlowLayers(renderer, pillarBox, pColor);
                db(renderer, flatTileBox, true, false, RenderUtils.withAlpha(pColor, 60));
                db(renderer, pillarBox, true, false, RenderUtils.withAlpha(pColor, 100));
                db(renderer, pillarBox, false, true, pColor.getRGB());
            } else if (mode == HighlightMode.PULSE) {
                renderPulseBox(renderer, flatTileBox, pColor);
                renderPulseBox(renderer, pillarBox, pColor);
            } else { // SPECTRAL
                int lineC = RenderUtils.withAlpha(pColor, spectralLineAlpha.getValue());
                int fillC = RenderUtils.withAlpha(pColor, spectralFillAlpha.getValue());
                db(renderer, flatTileBox, true, false, fillC);
                db(renderer, flatTileBox, false, true, lineC);
                db(renderer, pillarBox, true, false, fillC);
                db(renderer, pillarBox, false, true, lineC);
            }
        }

        if (currentState != ScanState.SETUP && !localTargets.isEmpty()) {
            for (BlockPos pos : localTargets) {
                if (visitedTargets.contains(pos)) continue;
                AABB box = new AABB(pos);

                if (mode == HighlightMode.GLOW) {
                    renderGlowLayers(renderer, box, cColor);
                    db(renderer, box, false, true, cColor.getRGB());
                } else if (mode == HighlightMode.PULSE) {
                    renderPulseBox(renderer, box, cColor);
                } else { // SPECTRAL
                    int lineC = RenderUtils.withAlpha(cColor, spectralLineAlpha.getValue());
                    int fillC = RenderUtils.withAlpha(cColor, spectralFillAlpha.getValue());
                    db(renderer, box, true, false, fillC);
                    db(renderer, box, false, true, lineC);
                }
            }
        }

        renderer.end();
    }

    // ── Render Helpers ──
    private void db(IRenderer3D r, AABB b, boolean fill, boolean outline, int color) {
        r.drawBox(b.minX, b.minY, b.minZ, b.getXsize(), b.getYsize(), b.getZsize(), fill, outline, color);
    }

    private void renderGlowLayers(IRenderer3D r, AABB box, Color color) {
        int layers = glowLayers.getValue();
        double spread = glowSpread.getValue();
        int baseAlpha = glowBaseAlpha.getValue();

        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double t = (double) (i - 1) / layers;
            int layerAlpha = Math.max(4, (int) (baseAlpha * (1.0 - t * t)));
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
}
