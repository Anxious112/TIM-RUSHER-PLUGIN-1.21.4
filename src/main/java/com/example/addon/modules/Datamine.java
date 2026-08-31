package com.example.addon.modules;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.addon.Tim;
import com.example.addon.mixin.InteractionAccessor;
import com.example.addon.utils.RenderUtils;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.network.EventPacket;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.client.api.setting.ItemListSetting;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.ListSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;

import java.awt.Color;

/**
 * Core packet mining, queuing, and bursting logic provided by Arkie.
 */
public class Datamine extends ToggleableModule {
    private static final double BREAK_THRESHOLD = 0.75;
    private static final long BURST_PAUSE = 305;
    private static final int BURST_COUNT = 22;
    private static final int FAKE_BLOCK_HEIGHT = 2048;

    public enum MiningMode { Packet, Normal }
    public enum SwapMode { Normal, Silent }
    public enum HighlightStyle { GLOW, SPECTRAL, PULSE }
    public enum ShapeMode { Both, Sides, Lines }

    private final EnumSetting<MiningMode> miningMode = new EnumSetting<>("mining-mode", "Packet uses exploit bursts for instant breaking. Normal uses standard vanilla breaking.", MiningMode.Packet);

    private final BooleanSetting instantRemine = new BooleanSetting("instant-remine", "Automatically mines the last broken block when it is replaced.", false);

    private final NumberSetting<Integer> remineDelay = new NumberSetting<>("remine-delay", "Delay in ticks before checking for a replaced block. 0 = instant.", 5, 0, 20)
        .setVisibility(instantRemine::getValue);

    private final NumberSetting<Integer> validationTicks = new NumberSetting<>("validation-ticks", "Ticks to wait before validating whether a block was broken.", 5, 1, 20);

    private final NumberSetting<Integer> maxAttempts = new NumberSetting<>("max-attempts", "Maximum total mining attempts for each block.", 3, 1, 3);

    private final EnumSetting<SwapMode> swapMode = new EnumSetting<>("auto-swap", "How to switch tools. Silent switches server-side without changing your visible hotbar.", SwapMode.Normal);

    private final BooleanSetting silentSwing = new BooleanSetting("silent-swing", "Hides the client-side hand swing animation. (Server still receives the packet).", false);

    private final BooleanSetting durabilityProtection = new BooleanSetting("durability-protection", "Prevents the auto-tool feature from selecting tools that are about to break.", true);

    private final NumberSetting<Integer> durabilityThreshold = new NumberSetting<>("durability-threshold", "The minimum durability remaining for a tool to be used.", 5, 1, 50)
        .setVisibility(durabilityProtection::getValue);

    private final NumberSetting<Integer> vanilla = new NumberSetting<>("vanilla-bypass", "Bypasses the mining queue for blocks that break within this many ticks. 0 = disabled.", 0, 0, 20);

    private final BooleanSetting autoCollect = new BooleanSetting("auto-collect", "Uses Baritone to pathfind and collect dropped items when the queue is empty.", false);

    private final NumberSetting<Integer> collectRange = new NumberSetting<>("collect-range", "Maximum distance to search for dropped items.", 16, 4, 64)
        .setVisibility(autoCollect::getValue);

    private final ListSetting<Item> collectWhitelist = new ItemListSetting("collect-whitelist", "Only collects the specified items. Leave empty to collect all items.")
        .setVisibility(autoCollect::getValue);

    private final NumberSetting<Integer> collectDelay = new NumberSetting<>("collect-delay", "Delay in ticks after an item drops before auto-collect activates.", 10, 0, 40)
        .setVisibility(autoCollect::getValue);

    private final NumberSetting<Integer> gracePeriod = new NumberSetting<>("collect-grace-period", "Seconds after a whitelisted item drops to actively search for it.", 5, 1, 15)
        .setVisibility(autoCollect::getValue);

    private final BooleanSetting render = new BooleanSetting("render", "Renders packet-mining progress and queued blocks.", true);

    private final EnumSetting<HighlightStyle> highlightStyle = new EnumSetting<>("highlight-style", "The style to highlight blocks with.", HighlightStyle.GLOW)
        .setVisibility(render::getValue);

    private final EnumSetting<ShapeMode> shapeMode = new EnumSetting<>("shape-mode", "How mining progress and queued blocks are rendered.", ShapeMode.Both)
        .setVisibility(() -> render.getValue() && highlightStyle.getValue() == HighlightStyle.GLOW);

    private final NumberSetting<Integer> glowLayers = new NumberSetting<>("glow-layers", "Bloom layer count.", 4, 1, 8)
        .setVisibility(() -> render.getValue() && (highlightStyle.getValue() == HighlightStyle.GLOW || highlightStyle.getValue() == HighlightStyle.PULSE));

    private final NumberSetting<Double> glowSpread = new NumberSetting<>("glow-spread", "Bloom spread.", 0.05, 0.01, 0.2)
        .setVisibility(() -> render.getValue() && (highlightStyle.getValue() == HighlightStyle.GLOW || highlightStyle.getValue() == HighlightStyle.PULSE));

    private final NumberSetting<Integer> glowBaseAlpha = new NumberSetting<>("glow-base-alpha", "Bloom alpha.", 50, 4, 150)
        .setVisibility(() -> render.getValue() && highlightStyle.getValue() == HighlightStyle.GLOW);

    private final NumberSetting<Double> pulseSpeed = new NumberSetting<>("pulse-speed", "Pulse cycle speed. 1.0 = one full fade in/out per second.", 1.0, 0.1, 5.0)
        .setVisibility(() -> render.getValue() && highlightStyle.getValue() == HighlightStyle.PULSE);

    private final NumberSetting<Integer> pulseMinAlpha = new NumberSetting<>("pulse-min-alpha", "Lowest alpha reached during the pulse (0 = invisible).", 15, 0, 255)
        .setVisibility(() -> render.getValue() && highlightStyle.getValue() == HighlightStyle.PULSE);

    private final NumberSetting<Integer> pulseMaxAlpha = new NumberSetting<>("pulse-max-alpha", "Peak alpha reached during the pulse.", 220, 15, 255)
        .setVisibility(() -> render.getValue() && highlightStyle.getValue() == HighlightStyle.PULSE);

    private final NumberSetting<Integer> spectralLineAlpha = new NumberSetting<>("spectral-line-alpha", "Outline alpha.", 255, 0, 255)
        .setVisibility(() -> render.getValue() && highlightStyle.getValue() == HighlightStyle.SPECTRAL);

    private final NumberSetting<Integer> spectralFillAlpha = new NumberSetting<>("spectral-fill-alpha", "Fill alpha.", 15, 0, 255)
        .setVisibility(() -> render.getValue() && highlightStyle.getValue() == HighlightStyle.SPECTRAL);

    private final NumberSetting<Double> spectralExpand = new NumberSetting<>("spectral-expand", "Box expansion.", 0.05, 0.0, 0.5)
        .setVisibility(() -> render.getValue() && highlightStyle.getValue() == HighlightStyle.SPECTRAL);

    private final ColorSetting queueColor = new ColorSetting("queue-color", "The color for queued blocks.", new Color(0, 200, 255, 200)).setVisibility(render::getValue);
    private final ColorSetting primaryColor = new ColorSetting("primary-color", "The color for the primary target.", new Color(0, 255, 100, 255)).setVisibility(render::getValue);
    private final ColorSetting secondaryColor = new ColorSetting("secondary-color", "The color for the secondary target.", new Color(180, 0, 255, 200)).setVisibility(render::getValue);

    private final Deque<Request> queue = new ArrayDeque<>();
    private Target primary;
    private Target secondary;
    private Request last;

    private int tick = 0;
    private int lastBreakTick = 0;
    private long lastMineTime = 0;
    private long stopped = 0;

    private final Set<Integer> seenItems = new HashSet<>();
    private boolean sendingCustomPacket = false;
    private boolean swapped = false;

    public Datamine() {
        super("datamine", "Queues blocks for fast packet mining with double break.", Tim.CATEGORY);
        this.registerSettings(
            miningMode, instantRemine, remineDelay, validationTicks, maxAttempts,
            swapMode, silentSwing, durabilityProtection, durabilityThreshold,
            vanilla, autoCollect, collectRange, collectWhitelist, collectDelay, gracePeriod,
            render, highlightStyle, shapeMode, glowLayers, glowSpread, glowBaseAlpha,
            pulseSpeed, pulseMinAlpha, pulseMaxAlpha, spectralLineAlpha, spectralFillAlpha, spectralExpand,
            queueColor, primaryColor, secondaryColor
        );
    }

    @Override
    public void onEnable() {
        this.resetState();
    }

    @Override
    public void onDisable() {
        if (this.primary != null && !this.primary.finished) {
            this.action(this.primary, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, this.primary.pos, this.primary.side);
        }
        if (this.secondary != null && !this.secondary.finished) {
            this.action(this.secondary, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, this.secondary.pos, this.secondary.side);
        }

        // Don't yank Baritone away if PortalMaker is the one driving it.
        if (!isPortalMakerActive() && this.autoCollect.getValue() && BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().forceCancel();
        }

        this.resetState();
    }

    @Subscribe
    private void onPacketSend(EventPacket.Send event) {
        if (this.miningMode.getValue() == MiningMode.Normal || sendingCustomPacket) return;

        if (event.getPacket() instanceof ServerboundPlayerActionPacket packet) {
            ServerboundPlayerActionPacket.Action action = packet.getAction();
            if (action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK ||
                action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK ||
                action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) {

                if (this.isTracked(packet.getPos())) {
                    event.setCancelled(true);
                }

                if (this.instantRemine.getValue() && this.last != null && action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK && packet.getPos().equals(this.last.pos)) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        this.tick++;

        this.clean();
        this.update(this.secondary);
        this.update(this.primary);
        this.fill();
        this.remine();
        this.checkForNewItems();
        this.doAutoCollect();
    }

    @Subscribe
    private void onRender(EventRender3D event) {
        if (!this.render.getValue()) return;

        IRenderer3D renderer = event.getRenderer();
        renderer.begin(event.getMatrixStack());

        for (Request request : this.queue) {
            this.renderBox(renderer, new AABB(request.pos), this.queueColor.getValue());
        }

        if (this.secondary != null) {
            this.renderTarget(renderer, this.secondary, this.secondaryColor.getValue());
        }
        if (this.primary != null) {
            this.renderTarget(renderer, this.primary, this.primaryColor.getValue());
        }

        renderer.end();
    }

    public void mine(BlockPos pos, Direction side) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || pos == null || side == null) return;

        pos = pos.immutable();
        if (this.isTracked(pos)) return;

        BlockState state = mc.level.getBlockState(pos);
        if (!this.breakable(pos, state)) return;

        this.queue.addLast(new Request(pos, side, 1));
        this.fill();
    }

    private boolean isPortalMakerActive() {
        PortalMaker pm = (PortalMaker) RusherHackAPI.getModuleManager().getFeature("portal-maker").orElse(null);
        return pm != null && pm.isToggled();
    }

    private void resetState() {
        this.queue.clear();
        this.primary = null;
        this.secondary = null;
        this.last = null;
        this.tick = 0;
        this.lastBreakTick = 0;
        this.lastMineTime = 0;
        this.stopped = 0;
        this.seenItems.clear();
        this.sendingCustomPacket = false;
        this.swapped = false;
    }

    private void clean() {
        this.queue.removeIf(request -> !this.breakable(request.pos, mc.level.getBlockState(request.pos)));
    }

    private void fill() {
        if (this.primary == null) {
            Target target = this.next();
            if (target != null) this.begin(target);
        }

        if (this.primary == null || this.secondary != null || this.queue.isEmpty() || !this.parkable()) return;

        Target target = this.next();
        if (target == null) return;

        this.park();
        this.begin(target);
    }

    private Target next() {
        while (!this.queue.isEmpty()) {
            Request request = this.queue.removeFirst();
            BlockState state = mc.level.getBlockState(request.pos);
            if (this.breakable(request.pos, state)) {
                return new Target(request, state);
            }
        }
        return null;
    }

    private boolean parkable() {
        return this.primary != null && !this.primary.finished && !this.primary.instant && this.primary.progress < 1;
    }

    private void park() {
        Target target = this.primary;
        this.action(target, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, target.pos, target.side);
        target.primary = false;
        target.parked = true;
        this.secondary = target;
        this.primary = null;
    }

    private void remove(Target target) {
        if (target == this.primary) this.primary = null;
        if (target == this.secondary) this.secondary = null;
    }

    public boolean isTracked(BlockPos pos) {
        if (this.primary != null && this.primary.pos.equals(pos)) return true;
        if (this.secondary != null && this.secondary.pos.equals(pos)) return true;
        for (Request request : this.queue) {
            if (request.pos.equals(pos)) return true;
        }
        return false;
    }

    private boolean breakable(BlockPos pos, BlockState state) {
        return !state.isAir() && state.getDestroySpeed(mc.level, pos) >= 0;
    }

    public boolean bypass(BlockPos pos) {
        if (mc.player == null || mc.level == null || pos == null || this.vanilla.getValue() <= 0 || this.isTracked(pos)) {
            return false;
        }

        BlockState state = mc.level.getBlockState(pos);
        if (!this.breakable(pos, state)) return false;

        float delta = state.getDestroyProgress(mc.player, mc.level, pos);

        return delta >= 1.0F / this.vanilla.getValue();
    }

    private void begin(Target target) {
        target.primary = true;
        target.started = System.currentTimeMillis();
        target.slot = this.best(target.state, target.pos);

        this.select(target.slot);

        target.delta = this.delta(target);
        target.instant = target.delta >= 1.0F;
        target.progress = target.instant ? 1 : 0;

        this.primary = target;

        this.packet(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, target.pos, target.side);

        if (this.miningMode.getValue() == MiningMode.Packet && !target.instant) {
            this.packet(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, this.fake(target.pos), target.side);
        }

        if (this.silentSwing.getValue()) {
            mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        } else {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }

        if (target.instant) this.finish(target);
        this.revertSlot();
    }

    private void update(Target target) {
        if (target == null) return;

        BlockState state = mc.level.getBlockState(target.pos);

        if (state.isAir()) {
            this.confirm(target);
            return;
        }

        if (target.finished) {
            if (this.tick - target.finish >= this.validationTicks.getValue()) {
                this.verify(target);
            }
            return;
        }

        target.slot = this.best(target.state, target.pos);
        target.delta = this.delta(target);
        target.progress = this.progress(target);

        long elapsed = System.currentTimeMillis() - target.started;

        if (this.miningMode.getValue() == MiningMode.Packet) {
            if (!target.burst && elapsed >= BURST_PAUSE && this.duration(target) > BURST_PAUSE && target.progress < 1) {
                this.burst(target);
            }
        }

        if (target.progress >= 1) this.finish(target);
    }

    private void finish(Target target) {
        if (target.finished) return;

        if (!target.instant && !target.parked) {
            this.action(target, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, target.pos, target.side);
        }

        target.progress = 1;
        target.finished = true;
        target.finish = this.tick;
    }

    private void verify(Target target) {
        BlockState state = mc.level.getBlockState(target.pos);

        if (state.isAir()) {
            this.confirm(target);
            return;
        }

        this.remove(target);
        if (target.attempt >= this.maxAttempts.getValue()) return;

        this.queue.addFirst(new Request(target.pos, target.side, target.attempt + 1));
    }

    private void confirm(Target target) {
        this.packet(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, target.pos, target.side);

        this.last = new Request(target.pos, target.side, 1);
        this.lastBreakTick = this.tick;
        this.remove(target);
    }

    private void burst(Target target) {
        target.slot = this.best(target.state, target.pos);
        this.select(target.slot);

        BlockPos pos = this.fake(target.pos);

        for (int idx = 0; idx < BURST_COUNT; idx++) {
            this.packet(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, target.side);
        }

        target.burst = true;
        this.revertSlot();
    }

    private boolean remine() {
        if (!this.instantRemine.getValue() || this.last == null ||
            this.primary != null || this.secondary != null) {
            return false;
        }

        BlockState state = mc.level.getBlockState(this.last.pos);
        if (!this.breakable(this.last.pos, state)) return false;

        Direction side = this.last.side;
        int slot = this.best(state, this.last.pos);

        this.select(slot);
        this.packet(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, this.last.pos, side);

        this.stopped = System.currentTimeMillis();
        return true;
    }

    private double progress(Target target) {
        if (target.finished) return 1;
        if (target.delta <= 0) return 0;

        double diff = System.currentTimeMillis() - target.started;
        double ticks = Math.max(1.0, diff / 50.0);
        double limit = (this.miningMode.getValue() == MiningMode.Packet && target.primary) ? BREAK_THRESHOLD : 1.0;

        return Math.min(1.0, target.delta * ticks / limit);
    }

    private long duration(Target target) {
        if (target.delta <= 0) return Long.MAX_VALUE;
        double limit = (this.miningMode.getValue() == MiningMode.Packet && target.primary) ? BREAK_THRESHOLD : 1.0;
        return (long) Math.max(0, (limit / target.delta - 1.0) * 50.0);
    }

    private float delta(Target target) {
        Inventory inv = mc.player.getInventory();
        int selected = inv.selected;
        inv.selected = target.slot;

        try {
            return target.state.getDestroyProgress(mc.player, mc.level, target.pos);
        } finally {
            inv.selected = selected;
        }
    }

    private int best(BlockState state, BlockPos pos) {
        Inventory inv = mc.player.getInventory();
        int selected = inv.selected;
        int best = selected;

        float speed = -1;
        boolean suitable = false;
        boolean required = state.requiresCorrectToolForDrops();

        try {
            for (int idx = 0; idx < 9; idx++) {
                ItemStack stack = inv.getItem(idx);

                if (this.durabilityProtection.getValue() && stack.isDamageableItem()) {
                    int remaining = stack.getMaxDamage() - stack.getDamageValue();
                    if (remaining <= this.durabilityThreshold.getValue()) continue;
                }

                boolean good = stack.isCorrectToolForDrops(state);
                inv.selected = idx;

                float value = state.getDestroyProgress(mc.player, mc.level, pos);

                if (required && good != suitable) {
                    if (!good) continue;
                    best = idx;
                    speed = value;
                    suitable = true;
                    continue;
                }

                if (value <= speed) continue;

                best = idx;
                speed = value;
                suitable = good;
            }
        } finally {
            inv.selected = selected;
        }
        return best;
    }

    private void action(Target target, ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction side) {
        target.slot = this.best(target.state, target.pos);
        this.select(target.slot);
        this.packet(action, pos, side);
        this.revertSlot();
    }

    private void select(int slot) {
        Inventory inv = mc.player.getInventory();
        if (inv.selected == slot) {
            this.swapped = false;
            return;
        }

        this.swapped = true;

        if (this.swapMode.getValue() == SwapMode.Normal) {
            inv.selected = slot;
        }

        mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
    }

    private void revertSlot() {
        if (!this.swapped || this.swapMode.getValue() != SwapMode.Silent) return;

        Inventory inv = mc.player.getInventory();
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(inv.selected));
        this.swapped = false;
    }

    private void packet(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction side) {
        if (mc.level == null || mc.gameMode == null) return;

        sendingCustomPacket = true;
        try {
            ((InteractionAccessor) mc.gameMode).Tim$startPrediction(
                mc.level,
                sequence -> new ServerboundPlayerActionPacket(action, pos, side, sequence)
            );
        } finally {
            sendingCustomPacket = false;
        }
    }

    private BlockPos fake(BlockPos pos) {
        return new BlockPos(pos.getX(), FAKE_BLOCK_HEIGHT, pos.getZ());
    }

    private void checkForNewItems() {
        if (!this.autoCollect.getValue() || mc.player == null || mc.level == null) return;

        boolean foundNew = false;
        List<ItemEntity> items = mc.level.getEntitiesOfClass(ItemEntity.class,
            mc.player.getBoundingBox().inflate(this.collectRange.getValue()), e -> {
                if (this.collectWhitelist.getList().isEmpty()) return true;
                return this.collectWhitelist.getList().contains(e.getItem().getItem());
            });

        for (ItemEntity item : items) {
            if (this.seenItems.add(item.getId())) {
                foundNew = true;
            }
        }

        if (foundNew) {
            this.lastMineTime = System.currentTimeMillis();
        }

        this.seenItems.removeIf(id -> mc.level.getEntity(id) == null);
    }

    private void doAutoCollect() {
        if (!this.autoCollect.getValue() || mc.player == null || mc.level == null) return;
        // Yield Baritone to PortalMaker while it's building/entering a portal.
        if (isPortalMakerActive()) return;

        long currentTime = System.currentTimeMillis();
        long elapsedMs = currentTime - this.lastMineTime;
        long gracePeriodMs = this.gracePeriod.getValue() * 1000L;
        long collectDelayMs = this.collectDelay.getValue() * 50L;

        if (this.primary != null || this.secondary != null || !this.queue.isEmpty()) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().forceCancel();
            }
            return;
        }

        if (elapsedMs < collectDelayMs) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().forceCancel();
            }
            return;
        }

        if (elapsedMs > gracePeriodMs) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().forceCancel();
            }
            return;
        }

        if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) return;

        ItemEntity closestItem = null;
        double closestDist = this.collectRange.getValue() * this.collectRange.getValue();

        List<ItemEntity> items = mc.level.getEntitiesOfClass(ItemEntity.class,
            mc.player.getBoundingBox().inflate(this.collectRange.getValue()), e -> {
                if (this.collectWhitelist.getList().isEmpty()) return true;
                return this.collectWhitelist.getList().contains(e.getItem().getItem());
            });

        for (ItemEntity item : items) {
            double dist = item.distanceToSqr(mc.player);
            if (dist < closestDist) {
                closestDist = dist;
                closestItem = item;
            }
        }

        if (closestItem != null) {
            BlockPos itemPos = closestItem.blockPosition();
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(itemPos));
        }
    }

    private void renderTarget(IRenderer3D renderer, Target target, Color color) {
        double offset = (1.0 - target.progress) / 2.0;

        AABB box = new AABB(
            target.pos.getX() + offset,
            target.pos.getY() + offset,
            target.pos.getZ() + offset,
            target.pos.getX() + 1.0 - offset,
            target.pos.getY() + 1.0 - offset,
            target.pos.getZ() + 1.0 - offset
        );

        this.renderBox(renderer, box, color);
    }

    private void renderBox(IRenderer3D renderer, AABB box, Color color) {
        if (this.highlightStyle.getValue() == HighlightStyle.SPECTRAL) {
            double expand = this.spectralExpand.getValue();
            AABB renderBox = box.inflate(expand);
            renderer.drawBox(renderBox.minX, renderBox.minY, renderBox.minZ, renderBox.getXsize(), renderBox.getYsize(), renderBox.getZsize(),
                true, true, RenderUtils.withAlpha(color, this.spectralFillAlpha.getValue()));
        } else if (this.highlightStyle.getValue() == HighlightStyle.GLOW) {
            this.renderGlowLayers(renderer, box, color);
            boolean fill = this.shapeMode.getValue() != ShapeMode.Lines;
            boolean outline = this.shapeMode.getValue() != ShapeMode.Sides;
            renderer.drawBox(box.minX, box.minY, box.minZ, box.getXsize(), box.getYsize(), box.getZsize(), fill, outline, color.getRGB());
        } else if (this.highlightStyle.getValue() == HighlightStyle.PULSE) {
            this.renderPulseBox(renderer, box, color);
        }
    }

    private void renderGlowLayers(IRenderer3D renderer, AABB box, Color color) {
        int layers = this.glowLayers.getValue();
        double spread = this.glowSpread.getValue();
        int baseAlpha = this.glowBaseAlpha.getValue();

        for (int i = layers; i >= 1; i--) {
            int layerAlpha = Math.max(4, (int)(baseAlpha * (1.0 - (double)(i-1) / layers)));
            AABB expanded = box.inflate(spread * i);
            renderer.drawBox(expanded.minX, expanded.minY, expanded.minZ, expanded.getXsize(), expanded.getYsize(), expanded.getZsize(),
                true, false, RenderUtils.withAlpha(color, layerAlpha));
        }
    }

    private float getPulseFactor() {
        double speed = this.pulseSpeed.getValue();
        double t = System.currentTimeMillis() / 1000.0;
        double phase = t * speed * Math.PI * 2.0;
        return (float)((Math.sin(phase) + 1.0) * 0.5);
    }

    private int applyPulse(int baseAlpha) {
        float f = getPulseFactor();
        int min = this.pulseMinAlpha.getValue();
        int max = this.pulseMaxAlpha.getValue();
        return Math.min(255, Math.max(0, (int)(min + (max - min) * f)));
    }

    private void renderPulseBox(IRenderer3D renderer, AABB box, Color color) {
        int pa = applyPulse(color.getAlpha());
        int layers = this.glowLayers.getValue();
        double spread = this.glowSpread.getValue();

        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double taper = 1.0 - ((double)(i - 1) / layers) * 0.6;
            int layerAlpha = Math.max(4, (int)(pa * taper));
            AABB expanded = box.inflate(expansion);
            renderer.drawBox(expanded.minX, expanded.minY, expanded.minZ, expanded.getXsize(), expanded.getYsize(), expanded.getZsize(),
                true, false, RenderUtils.withAlpha(color, layerAlpha));
        }

        renderer.drawBox(box.minX, box.minY, box.minZ, box.getXsize(), box.getYsize(), box.getZsize(), true, true, RenderUtils.withAlpha(color, pa));
    }

    private record Request(BlockPos pos, Direction side, int attempt) {
        private Request {
            pos = pos.immutable();
        }
    }

    private static class Target {
        private final BlockPos pos;
        private final BlockState state;
        private final Direction side;
        private final int attempt;

        private long started;
        private float delta;
        private double progress;
        private int slot;

        private boolean primary;
        private boolean parked;
        private boolean burst;
        private boolean instant;
        private boolean finished;
        private int finish;

        private Target(Request request, BlockState state) {
            this.pos = request.pos;
            this.state = state;
            this.side = request.side;
            this.attempt = request.attempt;
        }
    }
}
