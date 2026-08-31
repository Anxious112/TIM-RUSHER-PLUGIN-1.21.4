package com.example.addon.modules;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.example.addon.Tim;
import com.example.addon.utils.RenderUtils;

import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.client.screen.EventScreen;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.client.api.setting.ItemListSetting;
import org.rusherhack.core.event.stage.Stage;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class LootLens extends ToggleableModule {

    public enum RenderMode { GLOW, SPECTRAL, PULSE }
    public enum BeamStyle { BOX, GUARDIAN }
    private enum BoxMode { Both, Sides, Lines }

    private enum StorageType {
        CHEST, TRAPPED_CHEST, BARREL, SHULKER_BOX, ENDER_CHEST, CHEST_MINECART, UTILITY, DECORATIVE
    }

    // ── State ──
    private final Map<BlockPos, StorageType> containers = new HashMap<>();
    private final Set<BlockPos> inventoryCheckedContainers = new HashSet<>();
    private final Set<BlockPos> scannedByScanner = new HashSet<>();
    private final Set<BlockPos> shulkerContainers = new HashSet<>();
    private final Map<BlockPos, Integer> shulkerCounts = new HashMap<>();
    private final Map<Vec3, ItemFrame> itemFrameEntities = new HashMap<>();
    private final Map<Vec3, GlowItemFrame> glowItemFrameEntities = new HashMap<>();
    private final Set<Vec3> notifiedItemFrames = new HashSet<>();
    private final Set<BlockPos> minecartInventoryChecked = new HashSet<>();
    private final Map<UUID, StackedState> knownStackedMinecarts = new HashMap<>();
    private final Map<BlockPos, DyeColor> bedPositions = new HashMap<>();

    private BlockPos lastOpenedContainer = null;
    private boolean screenInventoryChecked = false;

    private String lastDimension = "";
    private static final int DIMENSION_CHANGE_COOLDOWN_TICKS = 40;
    private int dimensionChangeCooldown = 0;
    private static final int CLEANUP_INTERVAL = 40;
    private int cleanupTimer = 0;

    // ── General ──
    private final NumberSetting<Integer> range = new NumberSetting<>("range", "Container detection range in blocks.", 128, 16, 512);
    private final BooleanSetting notification = new BooleanSetting("notification", "Send chat messages and play sound when shulkers are found.", true);
    private final ItemListSetting customItems = new ItemListSetting("custom-items", "Additional items to highlight in containers.", Items.ENCHANTED_GOLDEN_APPLE, Items.ELYTRA);
    private final BooleanSetting stealDumpButtons = new BooleanSetting("steal-dump-buttons", "Show steal and dump buttons on container screens.", true);
    private final EnumSetting<RenderMode> renderMode = new EnumSetting<>("render-mode", "GLOW = layered bloom boxes. SPECTRAL = subtle fill. PULSE = fading highlight.", RenderMode.GLOW);
    private final NumberSetting<Integer> glowLayers = new NumberSetting<>("glow-layers", "Number of bloom layers rendered around each container.", 4, 1, 8)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW || renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Double> glowSpread = new NumberSetting<>("glow-spread", "How far each bloom layer expands outward (in blocks).", 0.04, 0.01, 0.15)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW || renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> glowBaseAlpha = new NumberSetting<>("glow-base-alpha", "Alpha of the innermost glow layer (0-255).", 60, 10, 150)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW);
    private final NumberSetting<Integer> spectralFillAlpha = new NumberSetting<>("spectral-fill-alpha", "Fill alpha for block containers in SPECTRAL mode.", 40, 0, 200)
        .setVisibility(() -> renderMode.getValue() == RenderMode.SPECTRAL);
    private final BooleanSetting spectralOutline = new BooleanSetting("spectral-outline", "Draw a crisp outline around block containers in SPECTRAL mode.", true)
        .setVisibility(() -> renderMode.getValue() == RenderMode.SPECTRAL);
    private final NumberSetting<Double> pulseSpeed = new NumberSetting<>("pulse-speed", "Pulse cycle speed. 1.0 = one full fade in/out per second.", 1.0, 0.1, 5.0)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> pulseMinAlpha = new NumberSetting<>("pulse-min-alpha", "Lowest alpha reached during the pulse (0 = invisible).", 15, 0, 255)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> pulseMaxAlpha = new NumberSetting<>("pulse-max-alpha", "Peak alpha reached during the pulse.", 220, 0, 255)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);
    private final BooleanSetting pulseBeams = new BooleanSetting("pulse-beams", "Also pulse the beam opacity in sync with the highlights.", true)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);

    // ── Beam ──
    private final EnumSetting<BeamStyle> beamStyle = new EnumSetting<>("beam-style", "BOX = simple box beam. GUARDIAN = wider glow beam.", BeamStyle.GUARDIAN);
    private final NumberSetting<Integer> beamWidth = new NumberSetting<>("beam-width", "Box beam width (in hundredths of a block).", 15, 5, 50)
        .setVisibility(() -> beamStyle.getValue() == BeamStyle.BOX);
    private final BooleanSetting mergeBeams = new BooleanSetting("merge-beams", "Merge beams for nearby shulker containers to reduce clutter.", true);
    private final NumberSetting<Double> mergeDistance = new NumberSetting<>("merge-distance", "Distance within which beams are merged.", 2.0, 0.0, 10.0).setVisibility(mergeBeams::getValue);
    private final NumberSetting<Double> guardianBeamRadius = new NumberSetting<>("guardian-radius", "Radius of the guardian beam (blocks).", 0.08, 0.01, 0.6)
        .setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Integer> guardianStrands = new NumberSetting<>("guardian-strands", "Guardian glow layer count.", 4, 2, 8)
        .setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Double> guardianSpinSpeed = new NumberSetting<>("guardian-spin-speed", "Unused (visual pacing).", 1.0, 0.1, 5.0)
        .setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Integer> guardianCoreAlpha = new NumberSetting<>("guardian-core-alpha", "Alpha of the solid centre core of the guardian beam.", 90, 0, 255)
        .setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Integer> guardianStrandAlpha = new NumberSetting<>("guardian-strand-alpha", "Alpha of the outer glow layers.", 160, 10, 255)
        .setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final BooleanSetting guardianGlow = new BooleanSetting("guardian-glow", "Add a soft bloom halo around the guardian beam.", true)
        .setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN);
    private final NumberSetting<Double> guardianGlowRadius = new NumberSetting<>("guardian-glow-radius", "Radius of the bloom halo around the guardian beam.", 0.18, 0.02, 1.0)
        .setVisibility(() -> beamStyle.getValue() == BeamStyle.GUARDIAN && guardianGlow.getValue());

    // ── Storage ──
    private final BooleanSetting scanChests = new BooleanSetting("chests", "Detect chests and trapped chests.", true)
        .onChange((java.util.function.Consumer<Boolean>) v -> { if (!v) { removeContainersOfType(StorageType.CHEST); removeContainersOfType(StorageType.TRAPPED_CHEST); } });
    private final ColorSetting chestColor = new ColorSetting("chest-color", "Chest color.", new Color(255, 215, 0, 200)).setVisibility(scanChests::getValue);
    private final BooleanSetting scanBarrels = new BooleanSetting("barrels", "Detect barrels.", true)
        .onChange((java.util.function.Consumer<Boolean>) v -> { if (!v) removeContainersOfType(StorageType.BARREL); });
    private final ColorSetting barrelColor = new ColorSetting("barrel-color", "Barrel color.", new Color(139, 69, 19, 200)).setVisibility(scanBarrels::getValue);
    private final BooleanSetting scanShulkerBoxes = new BooleanSetting("shulker-boxes", "Detect shulker boxes placed in the world.", true)
        .onChange((java.util.function.Consumer<Boolean>) v -> { if (!v) removeContainersOfType(StorageType.SHULKER_BOX); });
    private final ColorSetting shulkerBoxColor = new ColorSetting("shulker-box-color", "Shulker box color.", new Color(160, 32, 240, 200)).setVisibility(scanShulkerBoxes::getValue);
    private final BooleanSetting scanEnderChests = new BooleanSetting("ender-chests", "Detect ender chests.", true)
        .onChange((java.util.function.Consumer<Boolean>) v -> { if (!v) removeContainersOfType(StorageType.ENDER_CHEST); });
    private final ColorSetting enderChestColor = new ColorSetting("ender-chest-color", "Ender chest color.", new Color(75, 0, 130, 200)).setVisibility(scanEnderChests::getValue);
    private final BooleanSetting scanChestMinecarts = new BooleanSetting("chest-minecarts", "Detect chest minecarts.", true)
        .onChange((java.util.function.Consumer<Boolean>) v -> { if (!v) removeContainersOfType(StorageType.CHEST_MINECART); });
    private final ColorSetting chestMinecartColor = new ColorSetting("chest-minecart-color", "Chest minecart color.", new Color(255, 180, 0, 200)).setVisibility(scanChestMinecarts::getValue);
    private final NumberSetting<Integer> stackedMinecartThreshold = new NumberSetting<>("stacked-threshold", "Minecarts at one block position that count as 'stacked'.", 2, 2, 10).setVisibility(scanChestMinecarts::getValue);
    private final ColorSetting stackedMinecartColor = new ColorSetting("stacked-minecart-color", "Highlight/beam color for stacked chest minecarts.", new Color(255, 0, 255, 255)).setVisibility(scanChestMinecarts::getValue);
    private final ColorSetting shulkerFoundColor = new ColorSetting("shulker-found-color", "Bright color for containers confirmed to hold shulkers/custom items.", new Color(0, 255, 80, 255));

    // ── Utility ──
    private final BooleanSetting scanUtility = new BooleanSetting("utility-blocks", "Detect furnaces, hoppers, dispensers, droppers.", true)
        .onChange((java.util.function.Consumer<Boolean>) v -> { if (!v) removeContainersOfType(StorageType.UTILITY); });
    private final ColorSetting utilityColor = new ColorSetting("utility-color", "Utility container color.", new Color(150, 150, 150, 200)).setVisibility(scanUtility::getValue);

    // ── Decorative ──
    private final BooleanSetting scanDecorative = new BooleanSetting("decorative-blocks", "Detect brewing stands, crafters, chiseled bookshelves, decorated pots.", true)
        .onChange((java.util.function.Consumer<Boolean>) v -> { if (!v) removeContainersOfType(StorageType.DECORATIVE); });
    private final ColorSetting decorativeColor = new ColorSetting("decorative-color", "Decorative container color.", new Color(180, 100, 220, 200)).setVisibility(scanDecorative::getValue);
    private final BooleanSetting scanItemFramesSetting = new BooleanSetting("item-frames", "Detect item frames holding shulker boxes or custom items.", true);
    private final ColorSetting itemFrameColor = new ColorSetting("item-frame-color", "Item frame highlight color.", new Color(255, 100, 255, 200)).setVisibility(scanItemFramesSetting::getValue);
    private final BooleanSetting scanBeds = new BooleanSetting("beds", "Highlight all coloured beds using their matching dye colour.", false);
    private final NumberSetting<Integer> bedFillAlpha = new NumberSetting<>("bed-fill-alpha", "Fill transparency for bed highlights.", 50, 0, 200).setVisibility(scanBeds::getValue);

    public LootLens() {
        super("loot-lens", "Highlights storage containers confirmed to hold shulkers or custom items.", Tim.CATEGORY);
        this.registerSettings(
            range, notification, customItems, stealDumpButtons, renderMode,
            glowLayers, glowSpread, glowBaseAlpha, spectralFillAlpha, spectralOutline,
            pulseSpeed, pulseMinAlpha, pulseMaxAlpha, pulseBeams,
            beamStyle, beamWidth, mergeBeams, mergeDistance, guardianBeamRadius, guardianStrands,
            guardianSpinSpeed, guardianCoreAlpha, guardianStrandAlpha, guardianGlow, guardianGlowRadius,
            scanChests, chestColor, scanBarrels, barrelColor, scanShulkerBoxes, shulkerBoxColor,
            scanEnderChests, enderChestColor, scanChestMinecarts, chestMinecartColor, stackedMinecartThreshold, stackedMinecartColor,
            shulkerFoundColor, scanUtility, utilityColor, scanDecorative, decorativeColor,
            scanItemFramesSetting, itemFrameColor, scanBeds, bedFillAlpha
        );
    }

    @Override
    public void onEnable() {
        clearAllState();
        if (mc.player != null && mc.level != null) lastDimension = mc.level.dimension().location().toString();
    }

    @Override
    public void onDisable() { clearAllState(); }

    private void clearAllState() {
        containers.clear(); inventoryCheckedContainers.clear(); scannedByScanner.clear();
        shulkerContainers.clear(); shulkerCounts.clear();
        itemFrameEntities.clear(); glowItemFrameEntities.clear(); notifiedItemFrames.clear();
        minecartInventoryChecked.clear();
        knownStackedMinecarts.clear();
        bedPositions.clear();
        lastOpenedContainer = null; screenInventoryChecked = false; cleanupTimer = 0;
    }

    private void info(String fmt, Object... args) {
        sendNotification(NotificationType.INFO, args.length == 0 ? fmt : String.format(fmt, args));
    }

    // ── Tick ──
    @Subscribe
    private void onTick(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;
        if (event.getStage() == Stage.POST) { onTickPost(); return; }
        onTickPre();
    }

    private void onTickPre() {
        if (dimensionChangeCooldown > 0) { dimensionChangeCooldown--; return; }
        String currDim = mc.level.dimension().location().toString();
        if (!currDim.equals(lastDimension)) {
            dimensionChangeCooldown = DIMENSION_CHANGE_COOLDOWN_TICKS;
            lastDimension = currDim; clearAllState(); return;
        }
        if (++cleanupTimer >= CLEANUP_INTERVAL) { cleanupTimer = 0; cleanupDistantContainers(); }
        scanChestMinecartsTick(); scanItemFrames();
        if (scanBeds.getValue()) scanDecorativeWorldBlocks();
        BlockPos currentPos = mc.player.blockPosition();
        scanBlockEntities(currentPos.getX() >> 4, currentPos.getZ() >> 4);
    }

    private void onTickPost() {
        if (mc.screen instanceof AbstractContainerScreen<?> screen
                && !(mc.screen instanceof InventoryScreen)
                && lastOpenedContainer != null && !screenInventoryChecked) {
            if (containers.containsKey(lastOpenedContainer) || shulkerContainers.contains(lastOpenedContainer)) {
                checkScreenInventoryForShulkers(screen); screenInventoryChecked = true;
            }
        }
        if (mc.screen == null && lastOpenedContainer != null) {
            lastOpenedContainer = null; screenInventoryChecked = false;
        }
    }

    @Subscribe
    private void onOpenScreen(EventScreen.Change event) {
        if (mc.player == null || mc.level == null) return;
        screenInventoryChecked = false;
        if (event.getTo() instanceof InventoryScreen) return;
        HitResult hitResult = mc.hitResult;
        if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK)
            lastOpenedContainer = ((BlockHitResult) hitResult).getBlockPos().immutable();
    }

    // ── Mixin-facing API ──
    public void setLastInteractedPos(BlockPos pos) { lastOpenedContainer = pos; screenInventoryChecked = false; }
    public void onOpenScreenPacket() { screenInventoryChecked = false; }
    public boolean shouldShowStealDumpButtons() { return isToggled() && stealDumpButtons.getValue(); }
    public int getTotalContainers() { return containers.size(); }

    private boolean isImmediateHighlight(StorageType type) {
        return switch (type) {
            case SHULKER_BOX, ENDER_CHEST, UTILITY, DECORATIVE -> true;
            case CHEST, TRAPPED_CHEST, BARREL, CHEST_MINECART -> false;
        };
    }

    private boolean bposEquals(BlockPos a, BlockPos b) {
        return a != null && a.equals(b);
    }

    // ── Container Logic ──
    private void checkScreenInventoryForShulkers(AbstractContainerScreen<?> screen) {
        if (lastOpenedContainer == null) return;
        if (mc.level.getBlockState(lastOpenedContainer).getBlock() == Blocks.ENDER_CHEST) return;
        AbstractContainerMenu handler = screen.getMenu();
        int playerInventoryStart = handler.slots.size() - 36;
        int shulkerCount = 0;
        boolean previouslyHad = shulkerContainers.contains(lastOpenedContainer);
        for (int i = 0; i < playerInventoryStart; i++) {
            Slot slot = handler.slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            boolean isShulker = stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
            if (isShulker || customItems.getList().contains(stack.getItem())) shulkerCount++;
        }
        StorageType type = containers.get(lastOpenedContainer);
        if (type != null && isImmediateHighlight(type)) return;

        inventoryCheckedContainers.add(lastOpenedContainer);
        if (type == StorageType.CHEST_MINECART) minecartInventoryChecked.add(lastOpenedContainer);

        BlockPos adjacentChest = findAdjacentChest(lastOpenedContainer, false);
        if (adjacentChest != null) inventoryCheckedContainers.add(adjacentChest);

        if (shulkerCount > 0) {
            shulkerContainers.add(lastOpenedContainer);
            shulkerCounts.put(lastOpenedContainer, shulkerCount);
            if (adjacentChest != null) { shulkerContainers.add(adjacentChest); shulkerCounts.put(adjacentChest, shulkerCount); }
            if (!previouslyHad && notification.getValue()) {
                mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                info("%d %s found!", shulkerCount, shulkerCount == 1 ? "item" : "items");
            }
        } else {
            if (type == StorageType.CHEST_MINECART
                    && knownStackedMinecarts.values().stream().anyMatch(st -> st.stacked && bposEquals(st.lastBlockPos, lastOpenedContainer))) {
                minecartInventoryChecked.add(lastOpenedContainer);
                return;
            }
            containers.remove(lastOpenedContainer);
            shulkerContainers.remove(lastOpenedContainer);
            shulkerCounts.remove(lastOpenedContainer);
            minecartInventoryChecked.remove(lastOpenedContainer);
            if (adjacentChest != null) { containers.remove(adjacentChest); shulkerContainers.remove(adjacentChest); shulkerCounts.remove(adjacentChest); }
            if (previouslyHad && notification.getValue()) info("0 items found, removing highlight.");
        }
    }

    // ── Scanning ──
    private void scanBlockEntities(int centerChunkX, int centerChunkZ) {
        int rangeBlocks = range.getValue();
        int chunkRange = (rangeBlocks >> 4) + 1;
        int chunkRangeSq = chunkRange * chunkRange;
        long maxDistSq = (long) rangeBlocks * rangeBlocks;
        BlockPos playerPos = mc.player.blockPosition();
        for (int cx = centerChunkX - chunkRange; cx <= centerChunkX + chunkRange; cx++) {
            for (int cz = centerChunkZ - chunkRange; cz <= centerChunkZ + chunkRange; cz++) {
                int dx = cx - centerChunkX, dz = cz - centerChunkZ;
                if (dx * dx + dz * dz > chunkRangeSq) continue;
                if (!mc.level.getChunkSource().hasChunk(cx, cz)) continue;
                LevelChunk chunk = mc.level.getChunk(cx, cz);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getBlockPos();
                    if (pos.distSqr(playerPos) > maxDistSq) continue;
                    if (scannedByScanner.contains(pos) && !shulkerContainers.contains(pos) && !inventoryCheckedContainers.contains(pos)) continue;
                    Block block = mc.level.getBlockState(pos).getBlock();
                    StorageType type = classifyBlock(block);
                    if (type != null) { containers.put(pos, type); scannedByScanner.add(pos); }
                }
            }
        }
    }

    private StorageType classifyBlock(Block block) {
        if (block == Blocks.CHEST && scanChests.getValue()) return StorageType.CHEST;
        if (block == Blocks.TRAPPED_CHEST && scanChests.getValue()) return StorageType.TRAPPED_CHEST;
        if (block == Blocks.BARREL && scanBarrels.getValue()) return StorageType.BARREL;
        if (block == Blocks.ENDER_CHEST && scanEnderChests.getValue()) return StorageType.ENDER_CHEST;
        if (block instanceof ShulkerBoxBlock && scanShulkerBoxes.getValue()) return StorageType.SHULKER_BOX;
        if (scanUtility.getValue() && (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER
                || block == Blocks.HOPPER || block == Blocks.DISPENSER || block == Blocks.DROPPER)) return StorageType.UTILITY;
        if (scanDecorative.getValue() && (block == Blocks.BREWING_STAND || block == Blocks.CRAFTER
                || block == Blocks.CHISELED_BOOKSHELF || block == Blocks.DECORATED_POT)) return StorageType.DECORATIVE;
        return null;
    }

    private void scanChestMinecartsTick() {
        if (!scanChestMinecarts.getValue()) return;
        BlockPos playerPos = mc.player.blockPosition();
        int scanRange = range.getValue();
        AABB searchBox = new AABB(
            playerPos.getX() - scanRange, playerPos.getY() - scanRange, playerPos.getZ() - scanRange,
            playerPos.getX() + scanRange, playerPos.getY() + scanRange, playerPos.getZ() + scanRange
        );

        List<MinecartChest> minecarts = mc.level.getEntitiesOfClass(MinecartChest.class, searchBox, e -> true);

        List<Set<MinecartChest>> clusters = new ArrayList<>();
        Set<MinecartChest> assigned = new HashSet<>();

        for (MinecartChest m1 : minecarts) {
            if (assigned.contains(m1)) continue;
            Set<MinecartChest> cluster = new HashSet<>();
            cluster.add(m1);
            assigned.add(m1);
            for (MinecartChest m2 : minecarts) {
                if (assigned.contains(m2)) continue;
                if (m1.distanceToSqr(m2) < 0.5) { cluster.add(m2); assigned.add(m2); }
            }
            clusters.add(cluster);
        }

        Set<UUID> seenClusterIds = new HashSet<>();
        Set<BlockPos> currentMinecartPositions = new HashSet<>();

        for (Set<MinecartChest> cluster : clusters) {
            if (cluster.isEmpty()) continue;
            int count = cluster.size();
            Vec3 centroid = new Vec3(0, 0, 0);
            UUID clusterId = null;
            for (MinecartChest m : cluster) {
                centroid = centroid.add(m.position());
                currentMinecartPositions.add(m.blockPosition());
                containers.putIfAbsent(m.blockPosition(), StorageType.CHEST_MINECART);
                if (clusterId == null || m.getUUID().compareTo(clusterId) < 0) clusterId = m.getUUID();
            }
            centroid = centroid.scale(1.0 / count);
            BlockPos bpos = BlockPos.containing(centroid);
            seenClusterIds.add(clusterId);
            updateStackedMinecartState(clusterId, bpos, centroid, count);
        }

        Iterator<Map.Entry<UUID, StackedState>> it = knownStackedMinecarts.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            Map.Entry<UUID, StackedState> e = it.next();
            UUID id = e.getKey();
            StackedState s = e.getValue();
            if (seenClusterIds.contains(id)) continue;
            if (++s.missingTicks < 3) continue;
            boolean wasStacked = s.stacked;
            if (wasStacked) clearStackedHighlight(s, id, s.lastBlockPos);
            it.remove();
            if (wasStacked) removed++;
        }
        if (removed > 0 && notification.getValue() && mc.player != null) {
            int remaining = (int) knownStackedMinecarts.values().stream().filter(st -> st.stacked).count();
            if (remaining > 0) info("§7%d stacked minecart group(s) cleared. §f%d §7group(s) remaining.", removed, remaining);
            else info("§7All stacked minecart groups cleared.");
        }

        containers.entrySet().removeIf(entry -> {
            if (entry.getValue() != StorageType.CHEST_MINECART) return false;
            BlockPos pos = entry.getKey();
            if (currentMinecartPositions.contains(pos)) return false;
            if (knownStackedMinecarts.values().stream().anyMatch(st -> st.stacked && bposEquals(st.lastBlockPos, pos))) return false;
            inventoryCheckedContainers.remove(pos); scannedByScanner.remove(pos);
            shulkerContainers.remove(pos); shulkerCounts.remove(pos);
            minecartInventoryChecked.remove(pos);
            return true;
        });
    }

    private void updateStackedMinecartState(UUID id, BlockPos bpos, Vec3 centroid, int count) {
        final int entryThreshold = stackedMinecartThreshold.getValue();
        final int exitThreshold = Math.max(1, entryThreshold - 1);

        StackedState s = knownStackedMinecarts.get(id);
        if (s == null && count < entryThreshold) return;
        if (s == null) s = new StackedState();
        knownStackedMinecarts.put(id, s);

        BlockPos oldPos = s.lastBlockPos;
        s.observedCount = count;
        s.lastBlockPos = bpos;
        s.lastCentroid = centroid;
        s.missingTicks = 0;

        boolean meetsEntry = count >= entryThreshold;
        boolean meetsExit = count <= exitThreshold;

        if (!s.stacked) {
            if (meetsEntry) {
                if (++s.entryDebounce >= 3) enterStacked(s, id, bpos, count);
            } else {
                s.entryDebounce = 0;
            }
        } else {
            if (meetsExit) {
                if (++s.exitDebounce >= 3) {
                    clearStackedHighlight(s, id, bpos);
                    knownStackedMinecarts.remove(id);
                    if (notification.getValue() && mc.player != null) info("§7Stacked minecart group resolved (below exit threshold).");
                }
            } else {
                s.exitDebounce = 0;
            }

            if (s.stacked && s.confirmedCount != count) {
                s.confirmedCount = count;
                if (notification.getValue() && mc.player != null) info("§eStack updated: §f%d§e minecarts at one position.", count);
            }

            if (s.stacked && bpos != null && !bpos.equals(oldPos)) {
                if (oldPos != null) { shulkerContainers.remove(oldPos); shulkerCounts.remove(oldPos); }
                shulkerContainers.add(bpos);
                shulkerCounts.put(bpos, count);
            } else if (s.stacked && bpos != null) {
                shulkerCounts.put(bpos, count);
            }
        }
    }

    private void enterStacked(StackedState s, UUID id, BlockPos bpos, int count) {
        s.stacked = true;
        s.confirmedCount = count;
        s.entryDebounce = 0;
        s.exitDebounce = 0;
        if (bpos != null) {
            shulkerContainers.add(bpos);
            shulkerCounts.put(bpos, count);
        }
        if (notification.getValue() && mc.player != null) {
            info("§dStacked minecarts detected! §f%d§d minecarts at one position.", count);
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 0.5f);
        }
    }

    private void clearStackedHighlight(StackedState s, UUID id, BlockPos bpos) {
        s.stacked = false;
        s.entryDebounce = 0;
        s.exitDebounce = 0;
        if (bpos != null) {
            if (!minecartInventoryChecked.contains(bpos) || !shulkerCounts.containsKey(bpos)) {
                shulkerContainers.remove(bpos);
                shulkerCounts.remove(bpos);
            }
        }
    }

    private void scanItemFrames() {
        if (!scanItemFramesSetting.getValue()) return;
        BlockPos playerPos = mc.player.blockPosition();
        int scanRange = range.getValue();
        AABB searchBox = new AABB(
            playerPos.getX() - scanRange, playerPos.getY() - scanRange, playerPos.getZ() - scanRange,
            playerPos.getX() + scanRange, playerPos.getY() + scanRange, playerPos.getZ() + scanRange
        );
        Set<Vec3> currentFramePositions = new HashSet<>();
        for (ItemFrame frame : mc.level.getEntitiesOfClass(ItemFrame.class, searchBox, entity -> true)) {
            ItemStack heldStack = frame.getItem();
            if (heldStack.isEmpty()) continue;
            boolean isShulker = heldStack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
            boolean isCustom = customItems.getList().contains(heldStack.getItem());
            if (!isShulker && !isCustom) continue;
            Vec3 pos = frame.position();
            currentFramePositions.add(pos);
            if (frame instanceof GlowItemFrame glow) glowItemFrameEntities.put(pos, glow);
            else itemFrameEntities.put(pos, frame);
            if (notifiedItemFrames.add(pos) && notification.getValue()) {
                if (isShulker) info("Shulker found in item frame!");
                else info("Tracked item found in item frame!");
                mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            }
        }
        itemFrameEntities.entrySet().removeIf(e -> !currentFramePositions.contains(e.getKey()));
        glowItemFrameEntities.entrySet().removeIf(e -> !currentFramePositions.contains(e.getKey()));
        notifiedItemFrames.removeIf(pos -> !itemFrameEntities.containsKey(pos) && !glowItemFrameEntities.containsKey(pos));
    }

    private void scanDecorativeWorldBlocks() {
        if (mc.player == null || mc.level == null || !scanBeds.getValue()) return;

        BlockPos playerPos = mc.player.blockPosition();
        int rangeBlocks = range.getValue();
        int chunkRange = (rangeBlocks >> 4) + 1;
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;
        int chunkRangeSq = chunkRange * chunkRange;
        long maxDistSq = (long) rangeBlocks * rangeBlocks;

        bedPositions.clear();

        for (int cx = centerChunkX - chunkRange; cx <= centerChunkX + chunkRange; cx++) {
            for (int cz = centerChunkZ - chunkRange; cz <= centerChunkZ + chunkRange; cz++) {
                int dx = cx - centerChunkX, dz = cz - centerChunkZ;
                if (dx * dx + dz * dz > chunkRangeSq) continue;
                if (!mc.level.getChunkSource().hasChunk(cx, cz)) continue;
                LevelChunk chunk = mc.level.getChunk(cx, cz);

                LevelChunkSection[] sections = chunk.getSections();
                for (int sectionIdx = 0; sectionIdx < sections.length; sectionIdx++) {
                    LevelChunkSection section = sections[sectionIdx];
                    if (section == null || section.hasOnlyAir()) continue;
                    if (!section.maybeHas(state -> state.getBlock() instanceof BedBlock)) continue;

                    int baseY = (mc.level.getMinSectionY() + sectionIdx) << 4;
                    int baseX = cx << 4;
                    int baseZ = cz << 4;

                    for (int lx = 0; lx < 16; lx++) {
                        for (int ly = 0; ly < 16; ly++) {
                            for (int lz = 0; lz < 16; lz++) {
                                BlockState state = section.getBlockState(lx, ly, lz);
                                Block block = state.getBlock();
                                BlockPos pos = new BlockPos(baseX + lx, baseY + ly, baseZ + lz);
                                if (pos.distSqr(playerPos) > maxDistSq) continue;

                                if (block instanceof BedBlock bed) {
                                    try {
                                        if (state.getValue(BlockStateProperties.BED_PART) != BedPart.HEAD) continue;
                                    } catch (Exception ignored) { continue; }
                                    bedPositions.put(pos.immutable(), bed.getColor());
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private Color dyeToColor(DyeColor dye, int alpha) {
        return switch (dye) {
            case WHITE -> new Color(255, 255, 255, alpha);
            case ORANGE -> new Color(255, 140, 0, alpha);
            case MAGENTA -> new Color(255, 0, 255, alpha);
            case LIGHT_BLUE -> new Color(100, 200, 255, alpha);
            case YELLOW -> new Color(255, 240, 0, alpha);
            case LIME -> new Color(100, 230, 50, alpha);
            case PINK -> new Color(255, 150, 180, alpha);
            case GRAY -> new Color(100, 100, 100, alpha);
            case LIGHT_GRAY -> new Color(190, 190, 190, alpha);
            case CYAN -> new Color(0, 200, 200, alpha);
            case PURPLE -> new Color(150, 0, 200, alpha);
            case BLUE -> new Color(30, 80, 200, alpha);
            case BROWN -> new Color(130, 80, 30, alpha);
            case GREEN -> new Color(50, 160, 50, alpha);
            case RED -> new Color(220, 30, 30, alpha);
            case BLACK -> new Color(30, 30, 30, alpha);
        };
    }

    private BlockPos findAdjacentChest(BlockPos pos, boolean checkContainers) {
        if (mc.level == null) return null;
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return null;
        try {
            ChestType chestType = state.getValue(BlockStateProperties.CHEST_TYPE);
            if (chestType == ChestType.SINGLE) return null;
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            Direction neighborDir = chestType == ChestType.LEFT ? facing.getClockWise() : facing.getCounterClockWise();
            BlockPos neighborPos = pos.relative(neighborDir);
            BlockState neighborState = mc.level.getBlockState(neighborPos);
            if (!(neighborState.getBlock() instanceof ChestBlock)) return null;
            ChestType neighborType = neighborState.getValue(BlockStateProperties.CHEST_TYPE);
            Direction neighborFacing = neighborState.getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (neighborFacing != facing || neighborType == ChestType.SINGLE || neighborType == chestType) return null;
            if (checkContainers && !containers.containsKey(neighborPos)) return null;
            return neighborPos.immutable();
        } catch (Exception ignored) { return null; }
    }

    private void removeContainersOfType(StorageType type) {
        containers.entrySet().removeIf(entry -> {
            if (entry.getValue() != type) return false;
            BlockPos pos = entry.getKey();
            inventoryCheckedContainers.remove(pos); scannedByScanner.remove(pos);
            shulkerContainers.remove(pos); shulkerCounts.remove(pos);
            if (type == StorageType.CHEST_MINECART) {
                minecartInventoryChecked.remove(pos);
                knownStackedMinecarts.entrySet().removeIf(e -> {
                    StackedState s = e.getValue();
                    if (bposEquals(s.lastBlockPos, pos)) {
                        if (s.stacked) { shulkerContainers.remove(pos); shulkerCounts.remove(pos); }
                        return true;
                    }
                    return false;
                });
            }
            return true;
        });
    }

    private void cleanupDistantContainers() {
        if (mc.player == null) return;
        BlockPos playerPos = mc.player.blockPosition();
        int cleanupRange = range.getValue() + (range.getValue() >> 1);
        long cleanupRangeSq = (long) cleanupRange * cleanupRange;

        knownStackedMinecarts.entrySet().removeIf(entry -> {
            StackedState s = entry.getValue();
            if (s.lastBlockPos == null) return false;
            if (s.lastBlockPos.distSqr(playerPos) > cleanupRangeSq) {
                if (s.stacked) { shulkerContainers.remove(s.lastBlockPos); shulkerCounts.remove(s.lastBlockPos); }
                return true;
            }
            return false;
        });

        containers.entrySet().removeIf(entry -> {
            if (entry.getKey().distSqr(playerPos) <= cleanupRangeSq) return false;
            BlockPos pos = entry.getKey();
            if (knownStackedMinecarts.values().stream().anyMatch(st -> st.stacked && bposEquals(st.lastBlockPos, pos))) return false;
            inventoryCheckedContainers.remove(pos); scannedByScanner.remove(pos);
            shulkerContainers.remove(pos); shulkerCounts.remove(pos);
            minecartInventoryChecked.remove(pos);
            return true;
        });
    }

    // ── Rendering ──
    @Subscribe
    private void onRender(EventRender3D event) {
        if (mc.player == null || mc.level == null) return;

        IRenderer3D r = event.getRenderer();
        r.begin(event.getMatrixStack());

        boolean isSpectral = renderMode.getValue() == RenderMode.SPECTRAL;
        boolean isPulse = renderMode.getValue() == RenderMode.PULSE;
        Set<BlockPos> toRemove = new HashSet<>();
        Set<BlockPos> renderedDoubleChests = new HashSet<>();
        List<BeamData> beamsToRender = new ArrayList<>();

        renderItemFrames(r, beamsToRender);
        if (scanBeds.getValue()) renderBeds(r);

        for (Map.Entry<BlockPos, StorageType> entry : containers.entrySet()) {
            BlockPos pos = entry.getKey();
            StorageType type = entry.getValue();

            boolean shouldRender;
            if (type == StorageType.CHEST_MINECART) shouldRender = true;
            else if (isImmediateHighlight(type)) shouldRender = true;
            else shouldRender = shulkerContainers.contains(pos);
            if (!shouldRender) continue;
            if (renderedDoubleChests.contains(pos)) continue;

            AABB renderBox;
            Color baseColor;
            boolean isStackedMinecart = false;

            if (type == StorageType.CHEST_MINECART) {
                isStackedMinecart = knownStackedMinecarts.values().stream().anyMatch(st -> st.stacked && bposEquals(st.lastBlockPos, pos));
                List<MinecartChest> minecarts = mc.level.getEntitiesOfClass(MinecartChest.class, new AABB(pos), entity -> true);
                if (minecarts.isEmpty()) {
                    if (isStackedMinecart) renderBox = createPaddedBox(pos);
                    else { toRemove.add(pos); continue; }
                } else {
                    renderBox = getMinecartChestBox(minecarts.get(0));
                }
                boolean hasShulkers = shulkerContainers.contains(pos);
                baseColor = isStackedMinecart
                    ? (hasShulkers ? shulkerFoundColor.getValue() : stackedMinecartColor.getValue())
                    : (hasShulkers ? shulkerFoundColor.getValue() : chestMinecartColor.getValue());
            } else {
                BlockState currentState = mc.level.getBlockState(pos);
                if (!validateBlockType(currentState.getBlock(), type)) { toRemove.add(pos); continue; }
                BlockPos adjacentPos = findAdjacentChest(pos, true);
                if (adjacentPos != null) {
                    renderBox = createPaddedDoubleChestBox(pos, adjacentPos);
                    renderedDoubleChests.add(adjacentPos);
                } else if (type == StorageType.SHULKER_BOX) {
                    renderBox = createShulkerBox(pos, currentState);
                } else {
                    renderBox = createPaddedBox(pos);
                }
                baseColor = isImmediateHighlight(type) ? getColor(type) : shulkerFoundColor.getValue();
            }

            if (isSpectral) {
                int fillAlpha = (type == StorageType.CHEST_MINECART) ? 0 : spectralFillAlpha.getValue();
                int lineAlpha = (type == StorageType.CHEST_MINECART || !spectralOutline.getValue()) ? 0 : baseColor.getAlpha();
                if (fillAlpha > 0) db(r, renderBox, true, false, RenderUtils.withAlpha(baseColor, fillAlpha));
                if (lineAlpha > 0) db(r, renderBox, false, true, RenderUtils.withAlpha(baseColor, lineAlpha));
            } else if (isPulse) {
                renderPulseBox(r, renderBox, baseColor);
            } else {
                renderGlowLayers(r, renderBox, baseColor);
                db(r, renderBox, false, true, baseColor.getRGB());
            }

            boolean shouldBeam = type != StorageType.UTILITY && type != StorageType.DECORATIVE && type != StorageType.ENDER_CHEST;
            if (type == StorageType.CHEST_MINECART && !isStackedMinecart) shouldBeam = false;

            if (shouldBeam) {
                Color beamColor = (isPulse && pulseBeams.getValue()) ? pulseColor(baseColor) : baseColor;
                beamsToRender.add(new BeamData(renderBox, beamColor));
            }
        }

        renderBeams(r, beamsToRender);

        for (BlockPos removePos : toRemove) {
            containers.remove(removePos); inventoryCheckedContainers.remove(removePos);
            scannedByScanner.remove(removePos); shulkerContainers.remove(removePos);
            shulkerCounts.remove(removePos); minecartInventoryChecked.remove(removePos);
        }

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

    private void renderBeds(IRenderer3D r) {
        if (mc.level == null) return;
        boolean isSpectral = renderMode.getValue() == RenderMode.SPECTRAL;
        boolean isPulse = renderMode.getValue() == RenderMode.PULSE;
        int fill = bedFillAlpha.getValue();

        for (Map.Entry<BlockPos, DyeColor> entry : bedPositions.entrySet()) {
            BlockPos pos = entry.getKey();
            DyeColor dye = entry.getValue();

            BlockState state = mc.level.getBlockState(pos);
            if (!(state.getBlock() instanceof BedBlock)) continue;

            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            BlockPos footPos = pos.relative(facing.getOpposite());
            boolean hasFootBlock = mc.level.getBlockState(footPos).getBlock() instanceof BedBlock;

            AABB renderBox;
            if (hasFootBlock) {
                double minX = Math.min(pos.getX(), footPos.getX());
                double minZ = Math.min(pos.getZ(), footPos.getZ());
                double maxX = Math.max(pos.getX(), footPos.getX()) + 1.0;
                double maxZ = Math.max(pos.getZ(), footPos.getZ()) + 1.0;
                renderBox = new AABB(minX + 0.0625, pos.getY(), minZ + 0.0625, maxX - 0.0625, pos.getY() + 0.5625, maxZ - 0.0625);
            } else {
                renderBox = new AABB(pos.getX() + 0.0625, pos.getY(), pos.getZ() + 0.0625, pos.getX() + 0.9375, pos.getY() + 0.5625, pos.getZ() + 0.9375);
            }

            Color color = dyeToColor(dye, 200);

            if (isSpectral) {
                if (fill > 0) db(r, renderBox, true, false, RenderUtils.withAlpha(color, fill));
                if (spectralOutline.getValue()) db(r, renderBox, false, true, color.getRGB());
            } else if (isPulse) {
                renderPulseBox(r, renderBox, color);
            } else {
                renderGlowLayers(r, renderBox, color);
                db(r, renderBox, true, false, RenderUtils.withAlpha(color, fill));
                db(r, renderBox, false, true, color.getRGB());
            }
        }
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

    private void renderItemFrames(IRenderer3D r, List<BeamData> beams) {
        if (!scanItemFramesSetting.getValue()) return;
        Color color = itemFrameColor.getValue();
        boolean isSpectral = renderMode.getValue() == RenderMode.SPECTRAL;
        boolean isPulse = renderMode.getValue() == RenderMode.PULSE;

        List<ItemFrame> all = new ArrayList<>();
        all.addAll(itemFrameEntities.values());
        all.addAll(glowItemFrameEntities.values());

        for (ItemFrame frame : all) {
            if (frame == null || frame.isRemoved()) continue;
            AABB box = frame.getBoundingBox();
            if (isSpectral) {
                db(r, box, true, false, RenderUtils.withAlpha(color, spectralFillAlpha.getValue()));
                if (spectralOutline.getValue()) db(r, box, false, true, color.getRGB());
            } else if (isPulse) {
                renderPulseBox(r, box, color);
            } else {
                renderGlowLayers(r, box, color);
                db(r, box, false, true, color.getRGB());
            }
            ItemStack held = frame.getItem();
            if (held.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                beams.add(new BeamData(box, color));
            }
        }
    }

    // ── Box Helpers ──
    private AABB getMinecartChestBox(MinecartChest minecart) {
        AABB entityBox = minecart.getBoundingBox();
        double chestSz = 14.0 / 16.0;
        double xPad = (entityBox.getXsize() - chestSz) / 2.0, zPad = (entityBox.getZsize() - chestSz) / 2.0;
        double minY = entityBox.maxY - (10.0 / 16.0);
        return new AABB(entityBox.minX + xPad, minY, entityBox.minZ + zPad, entityBox.maxX - xPad, entityBox.maxY, entityBox.maxZ - zPad);
    }

    private AABB createPaddedBox(BlockPos pos) {
        double p = 0.0625;
        return new AABB(pos.getX() + p, pos.getY() + p, pos.getZ() + p, pos.getX() + 1 - p, pos.getY() + 1 - p, pos.getZ() + 1 - p);
    }

    private AABB createShulkerBox(BlockPos pos, BlockState state) {
        try {
            AABB shape = state.getShape(mc.level, pos).bounds();
            double p = 0.5 / 16.0;
            return new AABB(pos.getX() + shape.minX - p, pos.getY() + shape.minY - p, pos.getZ() + shape.minZ - p,
                pos.getX() + shape.maxX + p, pos.getY() + shape.maxY + p, pos.getZ() + shape.maxZ + p);
        } catch (Exception ignored) { return createPaddedBox(pos); }
    }

    private AABB createPaddedDoubleChestBox(BlockPos pos1, BlockPos pos2) {
        double p = 0.0625;
        double minX = Math.min(pos1.getX(), pos2.getX()), minY = Math.min(pos1.getY(), pos2.getY()), minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxX = Math.max(pos1.getX(), pos2.getX()) + 1, maxY = Math.max(pos1.getY(), pos2.getY()) + 1, maxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;
        return new AABB(minX + p, minY + p, minZ + p, maxX - p, maxY - p, maxZ - p);
    }

    private boolean validateBlockType(Block block, StorageType type) {
        return switch (type) {
            case CHEST -> block == Blocks.CHEST;
            case TRAPPED_CHEST -> block == Blocks.TRAPPED_CHEST;
            case BARREL -> block == Blocks.BARREL;
            case SHULKER_BOX -> block instanceof ShulkerBoxBlock;
            case ENDER_CHEST -> block == Blocks.ENDER_CHEST;
            case UTILITY -> block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER
                || block == Blocks.HOPPER || block == Blocks.DISPENSER || block == Blocks.DROPPER;
            case DECORATIVE -> block == Blocks.BREWING_STAND || block == Blocks.CRAFTER || block == Blocks.DECORATED_POT || block == Blocks.CHISELED_BOOKSHELF;
            case CHEST_MINECART -> true;
        };
    }

    private Color getColor(StorageType type) {
        return switch (type) {
            case CHEST, TRAPPED_CHEST -> chestColor.getValue();
            case BARREL -> barrelColor.getValue();
            case SHULKER_BOX -> shulkerBoxColor.getValue();
            case ENDER_CHEST -> enderChestColor.getValue();
            case CHEST_MINECART -> chestMinecartColor.getValue();
            case UTILITY -> utilityColor.getValue();
            case DECORATIVE -> decorativeColor.getValue();
        };
    }

    // ── Public API ──
    public int getDoubleChestCount() {
        if (mc.level == null) return 0;
        Set<BlockPos> counted = new HashSet<>();
        int count = 0;
        for (Map.Entry<BlockPos, StorageType> entry : containers.entrySet()) {
            BlockPos pos = entry.getKey();
            StorageType type = entry.getValue();
            if (type != StorageType.CHEST && type != StorageType.TRAPPED_CHEST) continue;
            if (counted.contains(pos)) continue;
            BlockState state = mc.level.getBlockState(pos);
            if (!(state.getBlock() instanceof ChestBlock)) continue;
            try {
                ChestType chestType = state.getValue(BlockStateProperties.CHEST_TYPE);
                if (chestType == ChestType.SINGLE) continue;
                BlockPos adjacent = findAdjacentChest(pos, false);
                if (adjacent == null) continue;
                counted.add(pos); counted.add(adjacent);
                count++;
            } catch (Exception ignored) {}
        }
        return count;
    }

    public int getShulkerBoxCount() {
        int count = 0;
        for (StorageType type : containers.values()) if (type == StorageType.SHULKER_BOX) count++;
        return count;
    }

    public int getEnderChestCount() {
        int count = 0;
        for (StorageType type : containers.values()) if (type == StorageType.ENDER_CHEST) count++;
        return count;
    }

    private record BeamData(AABB box, Color color) {}

    private static final class StackedState {
        boolean stacked = false;
        int observedCount = 0;
        int confirmedCount = 0;
        int entryDebounce = 0;
        int exitDebounce = 0;
        int missingTicks = 0;
        BlockPos lastBlockPos = null;
        Vec3 lastCentroid = null;
    }
}
