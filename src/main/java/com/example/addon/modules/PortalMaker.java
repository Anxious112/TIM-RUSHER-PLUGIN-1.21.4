package com.example.addon.modules;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.example.addon.Tim;
import com.example.addon.utils.InvUtils;
import com.example.addon.utils.RenderUtils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.IBaritoneProcess;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.BindSetting;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.client.api.utils.InventoryUtils;
import org.rusherhack.core.bind.key.NullKey;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;

/**
 * Builds and lights a minimal Nether portal (10 obsidian), then optionally
 * walks into it via Baritone and recycles back and forth across dimensions.
 */
public class PortalMaker extends ToggleableModule {

    // ── Enums ──────────────────────────────────────────────────────
    public enum EntryMode { None, Walk, Pearl }
    private enum RecycleState { IDLE, STEPPING_OUT, WAITING, RE_ENTERING }
    public enum RenderMode { GLOW, SPECTRAL, PULSE }
    public enum FrameShape { Both, Sides, Lines }

    // ── Settings — Building ────────────────────────────────────────
    private final NumberSetting<Integer> placeDelay = new NumberSetting<>("place-delay", "Ticks to wait between placement actions.", 2, 1, 12);
    private final NumberSetting<Integer> finishDelay = new NumberSetting<>("finish-delay", "Ticks to wait after lighting the portal before turning off.", 20, 0, 200);
    private final BooleanSetting airPlace = new BooleanSetting("air-place", "Lets you place obsidian in mid-air without needing a solid block to click against.", true);

    // ── Settings — Movement ────────────────────────────────────────
    private final BooleanSetting useBaritone = new BooleanSetting("use-baritone", "Uses Baritone to automatically path into the portal.", true);
    private final EnumSetting<EntryMode> entryMode = new EnumSetting<>("entry-mode", "How to enter the portal after it is created.", EntryMode.Walk)
        .setVisibility(useBaritone::getValue);

    // ── Settings — Recycle ─────────────────────────────────────────
    private final BooleanSetting autoRecycle = new BooleanSetting("auto-recycle", "After changing dimension, automatically step out, wait, and go back in.", false)
        .setVisibility(useBaritone::getValue);
    private final BooleanSetting cancelOnMovement = new BooleanSetting("cancel-on-movement", "Cancels the recycle process if you manually press a movement key.", true)
        .setVisibility(() -> useBaritone.getValue() && autoRecycle.getValue());
    private final NumberSetting<Integer> recycleDelaySeconds = new NumberSetting<>("recycle-wait-time", "How many seconds to wait before going back into the portal.", 5, 1, 60)
        .setVisibility(() -> useBaritone.getValue() && autoRecycle.getValue());
    private final BindSetting recycleKey = new BindSetting("recycle-key", "Manual keybind to trigger the recycle cycle (step out -> wait -> in).", NullKey.INSTANCE)
        .setVisibility(useBaritone::getValue);
    private final NumberSetting<Integer> dimensionSwitchCooldownTicks = new NumberSetting<>("dimension-switch-cooldown", "Ticks to wait after a dimension change before resuming operations (e.g., recycling).", 40, 0, 200)
        .setVisibility(useBaritone::getValue);

    // ── Settings — Render ──────────────────────────────────────────
    private final BooleanSetting render = new BooleanSetting("render", "Show remaining portal frame positions.", true);
    private final EnumSetting<FrameShape> shapeMode = new EnumSetting<>("shape-mode", "How the preview boxes are rendered.", FrameShape.Both)
        .setVisibility(render::getValue);
    private final ColorSetting sideColor = new ColorSetting("side-color", "Fill color for preview blocks.", new Color(80, 160, 255, 35)).setVisibility(render::getValue);
    private final ColorSetting lineColor = new ColorSetting("line-color", "Outline color for preview blocks.", new Color(100, 180, 255, 255)).setVisibility(render::getValue);

    // ── Settings — Glow ────────────────────────────────────────────
    private final EnumSetting<RenderMode> renderMode = new EnumSetting<>("render-mode", "GLOW = layered bloom boxes. SPECTRAL = subtle fill. PULSE = fading in/out highlight.", RenderMode.GLOW)
        .setVisibility(render::getValue);
    private final NumberSetting<Integer> glowLayers = new NumberSetting<>("glow-layers", "Number of bloom layers rendered around each preview block.", 4, 1, 8)
        .setVisibility(() -> render.getValue() && (renderMode.getValue() == RenderMode.GLOW || renderMode.getValue() == RenderMode.PULSE));
    private final NumberSetting<Double> glowSpread = new NumberSetting<>("glow-spread", "How far each bloom layer expands outward (in blocks).", 0.05, 0.01, 0.2)
        .setVisibility(() -> render.getValue() && (renderMode.getValue() == RenderMode.GLOW || renderMode.getValue() == RenderMode.PULSE));
    private final NumberSetting<Integer> glowBaseAlpha = new NumberSetting<>("glow-base-alpha", "Alpha of the innermost glow layer (0-255).", 60, 4, 150)
        .setVisibility(() -> render.getValue() && renderMode.getValue() == RenderMode.GLOW);
    private final NumberSetting<Integer> spectralFillAlpha = new NumberSetting<>("spectral-fill-alpha", "Fill alpha for preview blocks in SPECTRAL mode.", 40, 0, 200)
        .setVisibility(() -> render.getValue() && renderMode.getValue() == RenderMode.SPECTRAL);
    private final NumberSetting<Double> pulseSpeed = new NumberSetting<>("pulse-speed", "Pulse cycle speed. 1.0 = one full fade in/out per second.", 1.0, 0.1, 5.0)
        .setVisibility(() -> render.getValue() && renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> pulseMinAlpha = new NumberSetting<>("pulse-min-alpha", "Lowest alpha reached during the pulse (0 = invisible).", 15, 0, 255)
        .setVisibility(() -> render.getValue() && renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> pulseMaxAlpha = new NumberSetting<>("pulse-max-alpha", "Peak alpha reached during the pulse.", 220, 15, 255)
        .setVisibility(() -> render.getValue() && renderMode.getValue() == RenderMode.PULSE);

    // ── State ──────────────────────────────────────────────────────
    public final List<BlockPos> portalFramePositions = new ArrayList<>();
    private int     placementIndex   = 0;
    private int     tickTimer        = 0;
    private int     finishTimer      = 0;
    private String  lastDimension    = "";
    private String  builtDimension   = "";
    private boolean portalLitDetected = false;
    private int     dimensionChangeCooldown = 0;
    private RecycleState recycleState = RecycleState.IDLE;
    private Vec3    recycleTarget    = null;
    private Vec3    stepOutTarget    = null;
    private int     recycleWaitTimer = 0;
    private boolean wasRecyclePressed = false;

    private boolean originalEnterPortal = true;
    private boolean originalAllowPlace = true;
    private boolean originalAllowBreak = true;
    private boolean originalAllowParkour = true;
    private boolean originalAllowParkourPlace = true;

    public PortalMaker() {
        super("portal-maker", "Builds and lights a minimal Nether portal (10 obsidian).", Tim.CATEGORY);
        this.registerSettings(
            placeDelay, finishDelay, airPlace,
            useBaritone, entryMode,
            autoRecycle, cancelOnMovement, recycleDelaySeconds, recycleKey, dimensionSwitchCooldownTicks,
            render, shapeMode, sideColor, lineColor,
            renderMode, glowLayers, glowSpread, glowBaseAlpha, spectralFillAlpha,
            pulseSpeed, pulseMinAlpha, pulseMaxAlpha
        );
    }

    // ── Safe Block State Helper ────────────────────────────────────
    private BlockState getSafeBlockState(BlockPos pos) {
        if (mc.level == null) return Blocks.AIR.defaultBlockState();
        try {
            if (!mc.level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                return Blocks.AIR.defaultBlockState();
            }
            return mc.level.getBlockState(pos);
        } catch (Exception e) {
            return Blocks.AIR.defaultBlockState();
        }
    }

    private boolean isChunkSafe(BlockPos pos) {
        if (mc.level == null || mc.player == null) return false;
        try {
            return mc.level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────
    @Override
    public void onEnable() {
        portalFramePositions.clear();
        placementIndex   = 0;
        tickTimer        = 0;
        finishTimer      = 0;
        recycleState     = RecycleState.IDLE;
        lastDimension    = "";
        wasRecyclePressed = false;
        builtDimension   = "";
        portalLitDetected = false;
        dimensionChangeCooldown = 0;

        // Force Baritone to allow entering portals and placing blocks to reach it
        if (useBaritone.getValue()) {
            try {
                originalEnterPortal = BaritoneAPI.getSettings().enterPortal.value;
                BaritoneAPI.getSettings().enterPortal.value = true;

                originalAllowPlace = BaritoneAPI.getSettings().allowPlace.value;
                BaritoneAPI.getSettings().allowPlace.value = true;

                originalAllowBreak = BaritoneAPI.getSettings().allowBreak.value;
                BaritoneAPI.getSettings().allowBreak.value = true;

                originalAllowParkour = BaritoneAPI.getSettings().allowParkour.value;
                BaritoneAPI.getSettings().allowParkour.value = true;

                originalAllowParkourPlace = BaritoneAPI.getSettings().allowParkourPlace.value;
                BaritoneAPI.getSettings().allowParkourPlace.value = true;
            } catch (Exception ignored) {}
        }

        if (mc.player == null || mc.level == null) { toggle(); return; }

        if (!hasItemInHotbar(Items.OBSIDIAN)) {
            int total = countItem(Items.OBSIDIAN);
            if (total > 0) sendNotification(NotificationType.WARNING, "Obsidian is in inventory but not hotbar!");
        }
        int obsidianCount = getObsidianCount();
        if (obsidianCount < 10) {
            sendNotification(NotificationType.ERROR, "Need at least 10 obsidian (found " + obsidianCount + ")");
            toggle();
            return;
        }

        if (!hasItem(Items.FLINT_AND_STEEL)) sendNotification(NotificationType.WARNING, "No flint & steel found — light manually.");

        if (useBaritone.getValue() && !hasThrowawayBlocks()) {
            sendNotification(NotificationType.WARNING, "No throwaway blocks (dirt, cobblestone, etc.) found! Baritone may fail to bridge to the portal.");
        }

        Direction facing = mc.player.getDirection();
        Direction right  = facing.getClockWise();

        BlockPos feet     = mc.player.blockPosition();
        boolean  adjusted = false;

        if (!mc.level.getBlockState(feet.below()).isCollisionShapeFullBlock(mc.level, feet.below())) {
            feet     = feet.above();
            adjusted = true;
        }

        BlockPos origin = feet.relative(facing, 2).relative(right, -1);

        portalFramePositions.add(origin.relative(right, 1));
        portalFramePositions.add(origin.relative(right, 2));
        portalFramePositions.add(origin.above(1));
        portalFramePositions.add(origin.above(2));
        portalFramePositions.add(origin.above(3));
        portalFramePositions.add(origin.relative(right, 3).above(1));
        portalFramePositions.add(origin.relative(right, 3).above(2));
        portalFramePositions.add(origin.relative(right, 3).above(3));
        portalFramePositions.add(origin.relative(right, 1).above(4));
        portalFramePositions.add(origin.relative(right, 2).above(4));

        if (adjusted) {
            BlockPos stepPos = feet.relative(facing, 1);
            if (mc.level.getBlockState(stepPos).canBeReplaced()) portalFramePositions.add(stepPos);
        }

        boolean blocked = portalFramePositions.stream()
            .anyMatch(p -> !mc.level.getBlockState(p).canBeReplaced());
        if (blocked) { sendNotification(NotificationType.ERROR, "Portal area is obstructed. Move slightly and try again."); toggle(); return; }

        long existing = portalFramePositions.stream()
            .filter(p -> mc.level.getBlockState(p).getBlock() == Blocks.OBSIDIAN)
            .count();
        if (existing >= 9) {
            sendNotification(NotificationType.INFO, "Portal frame looks complete → attempting to light it.");
            placementIndex = portalFramePositions.size();
        }

        lastDimension = mc.level.dimension().location().toString();
        builtDimension = lastDimension;

        selectHotbarItem(Items.OBSIDIAN);
        sendNotification(NotificationType.INFO, "Building minimal Nether portal...");
    }

    @Override
    public void onDisable() {
        portalFramePositions.clear();
        placementIndex   = 0;
        tickTimer        = 0;
        stopMovement();

        // Restore Baritone's settings to what they were before
        if (useBaritone.getValue()) {
            try {
                BaritoneAPI.getSettings().enterPortal.value = originalEnterPortal;
                BaritoneAPI.getSettings().allowPlace.value = originalAllowPlace;
                BaritoneAPI.getSettings().allowBreak.value = originalAllowBreak;
                BaritoneAPI.getSettings().allowParkour.value = originalAllowParkour;
                BaritoneAPI.getSettings().allowParkourPlace.value = originalAllowParkourPlace;
            } catch (Exception ignored) {}
        }
    }

    // ── Event Handlers ─────────────────────────────────────────────
    @Subscribe
    private void onTick(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        if (mc.player.isDeadOrDying() || !mc.player.isAlive()) {
            stopMovement();
            toggle();
            return;
        }

        if (useBaritone.getValue() && autoRecycle.getValue() && cancelOnMovement.getValue() && isMovingManually()) {
            if (recycleState != RecycleState.IDLE || dimensionChangeCooldown > 0) {
                sendNotification(NotificationType.INFO, "Recycle cancelled by manual movement.");
            }
            toggle();
            return;
        }

        String currentDim;
        try {
            currentDim = mc.level.dimension().location().toString();
        } catch (Exception e) {
            return;
        }

        if (builtDimension.isEmpty()) builtDimension = currentDim;

        if (!currentDim.equals(lastDimension)) {
            lastDimension = currentDim;
            portalFramePositions.clear();
            if (useBaritone.getValue() && autoRecycle.getValue()) {
                dimensionChangeCooldown = dimensionSwitchCooldownTicks.getValue();
            }
            return;
        }

        if (dimensionChangeCooldown > 0) {
            dimensionChangeCooldown--;
            if (dimensionChangeCooldown == 0) {
                startRecycle();
            }
            return;
        }

        boolean recyclePressed = recycleKey.getValue().isKeyDown();
        if (useBaritone.getValue() && recyclePressed && !wasRecyclePressed) {
            if (recycleState == RecycleState.IDLE) {
                if (dimensionChangeCooldown > 0) sendNotification(NotificationType.INFO, "Cannot recycle yet, waiting for dimension change cooldown.");
                else startRecycle();
            } else {
                recycleState = RecycleState.IDLE;
                stopMovement();
                sendNotification(NotificationType.INFO, "Recycle cancelled.");
            }
        }
        wasRecyclePressed = recyclePressed;

        if (recycleState != RecycleState.IDLE) {
            handleRecycle();
            return;
        }

        if (isPlayerInPortal()) {
            stopMovement();
            if (useBaritone.getValue() && !autoRecycle.getValue() && !isRecycleKeySet() && recycleState == RecycleState.IDLE) {
                toggle();
            }
            return;
        }

        if (isPortalLit()) portalLitDetected = true;

        if (portalLitDetected || !currentDim.equals(builtDimension)) {
            if (currentDim.equals(builtDimension)) handlePhase2();
            return;
        }

        placementIndex = portalFramePositions.size();
        for (int i = 0; i < portalFramePositions.size(); i++) {
            BlockPos bp = portalFramePositions.get(i);
            if (!isChunkSafe(bp)) {
                placementIndex = portalFramePositions.size();
                break;
            }
            if (mc.level.getBlockState(bp).getBlock() != Blocks.OBSIDIAN) {
                placementIndex = i;
                break;
            }
        }

        if (placementIndex < portalFramePositions.size()) {
            if (mc.player.getInventory().items.isEmpty()) return;

            if (!mc.player.getMainHandItem().is(Items.OBSIDIAN)) {
                int slot = InventoryUtils.findItem(Items.OBSIDIAN, true, false);
                if (slot == -1) { sendNotification(NotificationType.ERROR, "No obsidian found -> disabled."); toggle(); return; }
                if (slot <= 8) mc.player.getInventory().selected = slot;
                else InvUtils.swapContainerSlots(InvUtils.toContainerSlot(slot), InvUtils.toContainerSlot(mc.player.getInventory().selected));
            }

            tickTimer++;
            if (tickTimer < placeDelay.getValue()) return;
            tickTimer = 0;

            BlockPos target = portalFramePositions.get(placementIndex);
            if (!isChunkSafe(target)) return;

            if (mc.level.getBlockState(target).getBlock() == Blocks.OBSIDIAN) { placementIndex++; return; }

            if (!mc.level.getBlockState(target).canBeReplaced()) {
                mc.gameMode.startDestroyBlock(target, mc.player.getDirection().getOpposite());
                mc.player.swing(InteractionHand.MAIN_HAND);
                return;
            }

            // Pre-calculate the hit result and rotation target
            BlockHitResult hit;
            BlockPos lookTarget = target;

            if (airPlace.getValue()) {
                // Air place trick: target the air block directly from the top
                hit = new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false);
            } else {
                // Vanilla placement: find an adjacent solid block to click against
                Direction placeDir = null;
                BlockPos neighborPos = null;

                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = target.relative(dir);
                    BlockState neighborState = getSafeBlockState(neighbor);
                    // Look for a solid, full block to click against
                    if (!neighborState.canBeReplaced() && neighborState.isCollisionShapeFullBlock(mc.level, neighbor)) {
                        placeDir = dir.getOpposite();
                        neighborPos = neighbor;
                        break;
                    }
                }

                if (neighborPos != null) {
                    // Click the exact face of the neighboring solid block
                    Vec3 hitVec = Vec3.atCenterOf(neighborPos).add(new Vec3(placeDir.getStepX(), placeDir.getStepY(), placeDir.getStepZ()).scale(0.5));
                    hit = new BlockHitResult(hitVec, placeDir, neighborPos, false);
                    lookTarget = neighborPos; // Rotate to look directly at the solid block
                } else {
                    // No valid neighbor found, and air-place is disabled. Abort.
                    sendNotification(NotificationType.ERROR, "Cannot place block without air-place (no solid neighbors found).");
                    toggle();
                    return;
                }
            }

            RusherHackAPI.getRotationManager().updateRotation(lookTarget);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            mc.player.swing(InteractionHand.MAIN_HAND);
            placementIndex++;
            return;
        }

        handlePhase2();
    }

    @Subscribe
    private void onRender(EventRender3D event) {
        if (mc.level == null) return;
        if (!render.getValue() || portalFramePositions.isEmpty()) return;

        IRenderer3D renderer = event.getRenderer();
        renderer.begin(event.getMatrixStack());

        for (int i = placementIndex; i < portalFramePositions.size(); i++) {
            BlockPos pos = portalFramePositions.get(i);
            if (!isChunkSafe(pos)) continue;
            if (!mc.level.getBlockState(pos).canBeReplaced()) continue;

            AABB box = new AABB(pos);

            if (renderMode.getValue() == RenderMode.PULSE) {
                renderPulseBox(renderer, box);
            } else if (renderMode.getValue() == RenderMode.SPECTRAL) {
                drawBox(renderer, box, true, true, RenderUtils.withAlpha(lineColor.getValue(), spectralFillAlpha.getValue()));
            } else {
                renderGlowLayers(renderer, box);
                boolean fill = shapeMode.getValue() != FrameShape.Lines;
                boolean outline = shapeMode.getValue() != FrameShape.Sides;
                if (fill) drawBox(renderer, box, true, false, sideColor.getValue().getRGB());
                if (outline) drawBox(renderer, box, false, true, lineColor.getValue().getRGB());
            }
        }

        renderer.end();
    }

    // ── Building Logic ─────────────────────────────────────────────
    private void handlePhase2() {
        if (!portalLitDetected) {
            finishTimer = 0;
            if (tickTimer++ >= 10) { lightPortal(); tickTimer = 0; }
            return;
        }

        if (useBaritone.getValue() && entryMode.getValue() != EntryMode.None) {
            moveToPortal();
        } else {
            if (finishTimer++ >= finishDelay.getValue()) {
                sendNotification(NotificationType.INFO, "PortalMaker finished.");
                toggle();
            }
        }
    }

    // ── Recycle Logic ──────────────────────────────────────────────
    private void handleRecycle() {
        if (!useBaritone.getValue()) {
            recycleState = RecycleState.IDLE;
            return;
        }

        if (mc.player == null || mc.level == null) {
            recycleState = RecycleState.IDLE;
            stopMovement();
            return;
        }

        if (mc.player.isDeadOrDying() || !mc.player.isAlive()) {
            recycleState = RecycleState.IDLE;
            stopMovement();
            toggle();
            return;
        }

        switch (recycleState) {
            case STEPPING_OUT -> {
                if (stepOutTarget == null) {
                    recycleState = RecycleState.WAITING;
                    return;
                }

                // Wait for Baritone to finish pathing
                if (isBaritoneIdle()) {
                    // If we are safely out of the portal, start the wait timer
                    if (!isPlayerInPortal()) {
                        stopMovement();
                        recycleState = RecycleState.WAITING;
                        return;
                    } else {
                        // Still somehow in the portal, try pathing out again
                        moveTo(stepOutTarget);
                    }
                }
                // If Baritone is not idle, it is still walking. Do nothing and let it finish.
            }
            case WAITING -> {
                stopMovement();
                if (recycleWaitTimer-- <= 0) {
                    recycleState = RecycleState.RE_ENTERING;
                    sendNotification(NotificationType.INFO, "Wait complete. Re-entering portal...");
                }
            }
            case RE_ENTERING -> {
                // If we entered the portal, we are done
                if (isPlayerInPortal()) {
                    stopMovement();
                    toggle();
                    return;
                }

                // Wait for Baritone to finish pathing
                if (isBaritoneIdle()) {
                    // If we aren't in the portal yet, issue the path command again
                    moveTo(recycleTarget);
                }
            }
            default -> {}
        }
    }

    private void startRecycle() {
        if (!useBaritone.getValue()) return;

        if (mc.player == null || mc.level == null) {
            recycleState = RecycleState.IDLE;
            return;
        }

        BlockPos playerPos = mc.player.blockPosition();
        if (!isChunkSafe(playerPos)) {
            dimensionChangeCooldown = 10;
            return;
        }

        setupRecycleTarget();
        recycleState = RecycleState.STEPPING_OUT;
        recycleWaitTimer = recycleDelaySeconds.getValue() * 20;
        sendNotification(NotificationType.INFO, "Initiating portal recycle...");
    }

    private void setupRecycleTarget() {
        if (mc.player == null || mc.level == null) {
            recycleTarget = null;
            stepOutTarget = null;
            return;
        }

        BlockPos pos = mc.player.blockPosition();

        if (!getSafeBlockState(pos).is(Blocks.NETHER_PORTAL)) {
            for (BlockPos p : BlockPos.betweenClosed(pos.offset(-5, -5, -5), pos.offset(5, 5, 5))) {
                if (!isChunkSafe(p)) continue;
                if (getSafeBlockState(p).is(Blocks.NETHER_PORTAL)) {
                    pos = p.immutable();
                    break;
                }
            }
        }

        if (getSafeBlockState(pos).is(Blocks.NETHER_PORTAL)) {
            BlockState state = getSafeBlockState(pos);
            Direction.Axis axis = state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)
                ? state.getValue(BlockStateProperties.HORIZONTAL_AXIS)
                : Direction.Axis.X;

            int minC = axis == Direction.Axis.X ? pos.getX() : pos.getZ();
            int maxC = minC;

            int maxIterations = 20;
            while (maxIterations-- > 0) {
                BlockPos checkPos = axis == Direction.Axis.X
                    ? new BlockPos(minC - 1, pos.getY(), pos.getZ())
                    : new BlockPos(pos.getX(), pos.getY(), minC - 1);
                if (!isChunkSafe(checkPos)) break;
                if (!getSafeBlockState(checkPos).is(Blocks.NETHER_PORTAL)) break;
                minC--;
            }

            maxIterations = 20;
            while (maxIterations-- > 0) {
                BlockPos checkPos = axis == Direction.Axis.X
                    ? new BlockPos(maxC + 1, pos.getY(), pos.getZ())
                    : new BlockPos(pos.getX(), pos.getY(), maxC + 1);
                if (!isChunkSafe(checkPos)) break;
                if (!getSafeBlockState(checkPos).is(Blocks.NETHER_PORTAL)) break;
                maxC++;
            }

            double mid = (minC + maxC + 1) / 2.0;
            if (axis == Direction.Axis.X) {
                recycleTarget = new Vec3(mid, pos.getY(), pos.getZ() + 0.5);
                Vec3 o1 = recycleTarget.add(0, 0, 2.0);
                Vec3 o2 = recycleTarget.add(0, 0, -2.0);
                if (isAreaClear(o1)) stepOutTarget = o1;
                else if (isAreaClear(o2)) stepOutTarget = o2;
                else stepOutTarget = o1;
            } else {
                recycleTarget = new Vec3(pos.getX() + 0.5, pos.getY(), mid);
                Vec3 o1 = recycleTarget.add(2.0, 0, 0);
                Vec3 o2 = recycleTarget.add(-2.0, 0, 0);
                if (isAreaClear(o1)) stepOutTarget = o1;
                else if (isAreaClear(o2)) stepOutTarget = o2;
                else stepOutTarget = o1;
            }
        } else {
            recycleTarget = mc.player.position();
            stepOutTarget = mc.player.position().add(mc.player.getLookAngle().scale(-2.0));
        }
    }

    private boolean isAreaClear(Vec3 pos) {
        if (mc.level == null) return false;
        BlockPos bp = BlockPos.containing(pos);
        if (!isChunkSafe(bp)) return false;
        return getSafeBlockState(bp).canBeReplaced() && getSafeBlockState(bp.above()).canBeReplaced();
    }

    // ── Portal Helpers ─────────────────────────────────────────────
    private boolean isPlayerInPortal() {
        if (mc.player == null || mc.level == null) return false;
        BlockPos feet = mc.player.blockPosition();
        if (!isChunkSafe(feet)) return false;
        return getSafeBlockState(feet).is(Blocks.NETHER_PORTAL) ||
               getSafeBlockState(feet.above()).is(Blocks.NETHER_PORTAL);
    }

    private void lightPortal() {
        if (portalFramePositions.isEmpty()) return;
        if (!selectHotbarItem(Items.FLINT_AND_STEEL)) { sendNotification(NotificationType.WARNING, "Cannot find flint & steel in hotbar."); return; }

        BlockPos bottom1 = portalFramePositions.get(0);
        BlockPos bottom2 = portalFramePositions.get(1);

        for (BlockPos pos : new BlockPos[]{bottom1, bottom2}) {
            if (!isChunkSafe(pos)) continue;
            if (getSafeBlockState(pos.above()).isAir()) {
                RusherHackAPI.getRotationManager().updateRotation(pos);
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                mc.player.swing(InteractionHand.MAIN_HAND);
                break;
            }
        }
    }

    private boolean isPortalLit() {
        if (portalFramePositions.size() < 2) return false;
        BlockPos p1 = portalFramePositions.get(0).above();
        BlockPos p2 = portalFramePositions.get(1).above();

        if (!isChunkSafe(p1) || !isChunkSafe(p2)) {
            return portalLitDetected;
        }

        return mc.level.getBlockState(p1).getBlock() == Blocks.NETHER_PORTAL ||
               mc.level.getBlockState(p2).getBlock() == Blocks.NETHER_PORTAL;
    }

    // ── Baritone Movement Engine ───────────────────────────────────
    private void moveToPortal() {
        if (portalFramePositions.size() < 2) return;
        moveTo(getPortalOpeningCenter());
    }

    private void moveTo(Vec3 target) {
        if (mc.player == null || mc.level == null || target == null) return;
        if (mc.player.isDeadOrDying() || !mc.player.isAlive()) {
            stopMovement();
            toggle();
            return;
        }

        BlockPos targetPos = BlockPos.containing(target.x, target.y, target.z);

        // Use the Baritone API directly to avoid command manager chat spam (prevents coordinate leaks)
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

        // GoalBlock forces the player's feet into the exact portal block so the teleport triggers reliably.
        if (isBaritoneIdle()) {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(targetPos));
        }
    }

    private Vec3 getPortalOpeningCenter() {
        BlockPos p1 = portalFramePositions.get(0).above();
        BlockPos p2 = portalFramePositions.get(1).above();
        return new Vec3(
            (p1.getX() + p2.getX()) / 2.0 + 0.5,
             p1.getY(),
            (p1.getZ() + p2.getZ()) / 2.0 + 0.5
        );
    }

    // ── Placement Helpers ──────────────────────────────────────────
    private void stopMovement() {
        if (mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyShift.setDown(false);

        // Stop Baritone pathing silently
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            IPathingBehavior pathing = baritone.getPathingBehavior();
            if (pathing != null && (pathing.isPathing() || pathing.hasPath() || pathing.getInProgress().isPresent())) {
                pathing.cancelEverything();
            }
        } catch (Exception ignored) {}
    }

    // ── Render Helpers ─────────────────────────────────────────────
    private void drawBox(IRenderer3D renderer, AABB box, boolean fill, boolean outline, int color) {
        renderer.drawBox(box.minX, box.minY, box.minZ, box.getXsize(), box.getYsize(), box.getZsize(), fill, outline, color);
    }

    private void renderGlowLayers(IRenderer3D renderer, AABB box) {
        int    layers    = glowLayers.getValue();
        double spread    = glowSpread.getValue();
        int    baseAlpha = glowBaseAlpha.getValue();

        for (int i = layers; i >= 1; i--) {
            double expansion  = spread * i;
            int    layerAlpha = Math.max(4, (int) (baseAlpha * (1.0 - (double) (i - 1) / layers)));
            drawBox(renderer, box.inflate(expansion), true, false, RenderUtils.withAlpha(lineColor.getValue(), layerAlpha));
        }
    }

    private float getPulseFactor() {
        double speed = pulseSpeed.getValue();
        double t = System.currentTimeMillis() / 1000.0;
        double phase = t * speed * Math.PI * 2.0;
        return (float) ((Math.sin(phase) + 1.0) * 0.5);
    }

    private int applyPulse() {
        float f = getPulseFactor();
        int min = pulseMinAlpha.getValue();
        int max = pulseMaxAlpha.getValue();
        return Math.min(255, Math.max(0, (int) (min + (max - min) * f)));
    }

    private void renderPulseBox(IRenderer3D renderer, AABB box) {
        int pa = applyPulse();
        Color base = lineColor.getValue();
        int layers = glowLayers.getValue();
        double spread = glowSpread.getValue();
        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double taper = 1.0 - ((double) (i - 1) / layers) * 0.6;
            int layerAlpha = Math.max(4, (int) (pa * taper));
            drawBox(renderer, box.inflate(expansion), true, false, RenderUtils.withAlpha(base, layerAlpha));
        }
        drawBox(renderer, box, true, true, RenderUtils.withAlpha(base, pa));
    }

    // ── Utility Helpers ────────────────────────────────────────────
    private boolean isRecycleKeySet() {
        return recycleKey.getValue() != NullKey.INSTANCE;
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

    private boolean selectHotbarItem(Item targetItem) {
        if (mc.player == null) return false;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == targetItem) {
                mc.player.getInventory().selected = i;
                return true;
            }
        }
        return false;
    }

    private boolean hasItemInHotbar(Item targetItem) {
        if (mc.player == null) return false;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == targetItem) return true;
        }
        return false;
    }

    public int getObsidianCount() {
        return countItem(Items.OBSIDIAN);
    }

    private int countItem(Item targetItem) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(targetItem)) count += stack.getCount();
        }
        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand.is(targetItem)) count += offhand.getCount();
        return count;
    }

    private boolean hasItem(Item targetItem) {
        return countItem(targetItem) > 0;
    }

    private boolean hasThrowawayBlocks() {
        if (mc.player == null) return false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            Item item = stack.getItem();
            if (item == Items.DIRT || item == Items.COBBLESTONE || item == Items.NETHERRACK ||
                item == Items.STONE || item == Items.GRASS_BLOCK || item == Items.DEEPSLATE ||
                item == Items.COBBLED_DEEPSLATE || item == Items.SAND || item == Items.GRAVEL ||
                item == Items.GLASS || item == Items.OAK_PLANKS || item == Items.SPRUCE_PLANKS) {
                return true;
            }
        }
        return false;
    }

    private boolean isMovingManually() {
        if (mc.screen != null) return false;
        long win = mc.getWindow().getWindow();
        return GLFW.glfwGetKey(win, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS ||
               GLFW.glfwGetKey(win, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS ||
               GLFW.glfwGetKey(win, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS ||
               GLFW.glfwGetKey(win, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS ||
               GLFW.glfwGetKey(win, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS ||
               GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
               GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }
}
