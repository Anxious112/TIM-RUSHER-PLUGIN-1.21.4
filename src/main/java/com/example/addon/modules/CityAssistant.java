package com.example.addon.modules;

import java.util.ArrayList;
import java.util.Comparator;
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

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.Slot;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;

public class CityAssistant extends ToggleableModule {

    public enum TargetType {
        SHRIEKER, ACTIVE_SHRIEKER, DISABLED_SHRIEKER, SENSOR, ACTIVE_SENSOR, CONTAINER
    }

    public enum RenderMode { GLOW, SPECTRAL, PULSE }

    public enum AlertSound { WARDEN_ROAR, DRAGON_GROWL, LEVEL_UP, RAVAGER_ROAR, EXPERIENCE_ORB, BELL }

    public enum StatSeverity { Normal, Warning, Critical }
    public record CityStat(String name, int count, ItemStack icon, StatSeverity severity) {}

    private static final int DIMENSION_CHANGE_COOLDOWN_TICKS = 40;
    private static final int INTERACT_TIMEOUT_TICKS = 20;

    private final Map<BlockPos, TargetType> targets = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final Set<BlockPos> checkedContainers = new HashSet<>();

    private final Set<Integer> notifiedWardens = new HashSet<>();
    private final Map<Integer, Long> wardenSpawnTimes = new ConcurrentHashMap<>();
    private int darknessWarnTimer = 0;
    private int totalWardenSpawns = 0;

    private boolean wasAutoOpened = false;
    private int interactTimeoutTimer = 0;

    private int drinkTimer = 0;
    private int previousDrinkSlot = -1;
    private boolean hasAlertedForCurrentScreen = false;

    private String lastDimension = "";
    private int dimensionChangeCooldown = 0;

    private final NumberSetting<Integer> range = new NumberSetting<>("range", "Detection range in chunks.", 16, 1, 128);

    private final NumberSetting<Integer> cityYLevel = new NumberSetting<>("city-y-level", "Maximum Y level to scan. Ancient Cities generate around Y = -52.", -20, -64, 320)
        .onChange((java.util.function.Consumer<Integer>) v -> {
            scannedChunks.clear();
            targets.entrySet().removeIf(entry -> entry.getKey().getY() > v);
        });

    private final EnumSetting<RenderMode> renderMode = new EnumSetting<>("render-mode", "GLOW = layered bloom boxes. SPECTRAL = outline shader. PULSE = fading highlight.", RenderMode.GLOW);

    private final NumberSetting<Integer> glowLayers = new NumberSetting<>("glow-layers", "Number of bloom layers rendered around each target.", 4, 1, 8)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW || renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Double> glowSpread = new NumberSetting<>("glow-spread", "How far each bloom layer expands outward (in blocks).", 0.04, 0.01, 0.15)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW || renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> glowBaseAlpha = new NumberSetting<>("glow-base-alpha", "Alpha of the innermost glow layer (0-255).", 60, 10, 150)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW);
    private final NumberSetting<Integer> spectralBlockFillAlpha = new NumberSetting<>("spectral-block-fill-alpha", "Fill alpha for block targets in SPECTRAL mode (0 = invisible, 30 = subtle).", 30, 0, 120)
        .setVisibility(() -> renderMode.getValue() == RenderMode.SPECTRAL);
    private final NumberSetting<Double> pulseSpeed = new NumberSetting<>("pulse-speed", "Pulse cycle speed. 1.0 = one full fade in/out per second.", 1.0, 0.1, 5.0)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> pulseMinAlpha = new NumberSetting<>("pulse-min-alpha", "Lowest alpha reached during the pulse (0 = invisible).", 15, 0, 255)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> pulseMaxAlpha = new NumberSetting<>("pulse-max-alpha", "Peak alpha reached during the pulse.", 220, 15, 255)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);

    private final BooleanSetting trackShriekers = new BooleanSetting("track-shriekers", "Highlight Sculk Shriekers.", true);
    private final ColorSetting shriekerColor = new ColorSetting("shrieker-color", "Color for idle Sculk Shriekers.", new Color(0, 180, 255, 255)).setVisibility(trackShriekers::getValue);
    private final ColorSetting activeShriekerColor = new ColorSetting("active-shrieker-color", "Color for currently shrieking blocks.", new Color(255, 0, 0, 255)).setVisibility(trackShriekers::getValue);
    private final ColorSetting disabledShriekerColor = new ColorSetting("disabled-shrieker-color", "Color for Shriekers that can no longer summon Wardens.", new Color(100, 100, 100, 255)).setVisibility(trackShriekers::getValue);

    private final BooleanSetting trackSensors = new BooleanSetting("track-sensors", "Highlight Sculk Sensors.", true);
    private final ColorSetting sensorColor = new ColorSetting("sensor-color", "Color for idle Sculk Sensors.", new Color(255, 255, 255, 255)).setVisibility(trackSensors::getValue);
    private final ColorSetting activeSensorColor = new ColorSetting("active-sensor-color", "Color for actively listening/triggered Sculk Sensors.", new Color(255, 100, 0, 255)).setVisibility(trackSensors::getValue);

    private final BooleanSetting trackContainers = new BooleanSetting("track-containers", "Highlight standard chests.", true);
    private final ColorSetting containerColor = new ColorSetting("container-color", "Color for standard chests.", new Color(0, 0, 255, 255)).setVisibility(trackContainers::getValue);

    private final ItemListSetting containerWhitelist = new ItemListSetting("container-whitelist",
        "Items to alert you about when opening Chests.",
        Items.NETHERITE_BLOCK, Items.NETHERITE_INGOT, Items.DIAMOND,
        Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE,
        Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
        Items.ENDER_CHEST, Items.ENCHANTED_GOLDEN_APPLE, Items.ELYTRA, Items.MACE,
        Items.NETHERITE_SWORD, Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE,
        Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
        Items.SHULKER_BOX, Items.WHITE_SHULKER_BOX, Items.ORANGE_SHULKER_BOX, Items.MAGENTA_SHULKER_BOX,
        Items.LIGHT_BLUE_SHULKER_BOX, Items.YELLOW_SHULKER_BOX, Items.LIME_SHULKER_BOX, Items.PINK_SHULKER_BOX,
        Items.GRAY_SHULKER_BOX, Items.LIGHT_GRAY_SHULKER_BOX, Items.CYAN_SHULKER_BOX, Items.PURPLE_SHULKER_BOX,
        Items.BLUE_SHULKER_BOX, Items.BROWN_SHULKER_BOX, Items.GREEN_SHULKER_BOX, Items.RED_SHULKER_BOX,
        Items.BLACK_SHULKER_BOX,
        Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
        Items.ECHO_SHARD, Items.MUSIC_DISC_5, Items.MUSIC_DISC_RELIC,
        Items.SCULK_CATALYST, Items.SCULK_SHRIEKER, Items.SCULK_SENSOR
    );

    private final BooleanSetting enableAlerts = new BooleanSetting("alerts", "Master toggle for audio cues and loot announcements.", true);
    private final EnumSetting<AlertSound> alertSound = new EnumSetting<>("alert-sound", "Which sound to play for module alerts.", AlertSound.WARDEN_ROAR).setVisibility(enableAlerts::getValue);
    private final NumberSetting<Double> alertVolume = new NumberSetting<>("alert-volume", "Volume of the alert sound. Goes up to 5.0 for extra loud alerts.", 1.0, 0.0, 5.0).setVisibility(enableAlerts::getValue);
    private final BooleanSetting enableWardenPing = new BooleanSetting("warden-ping", "Plays a distinct sound and warns you heavily when a Warden spawns or approaches.", true);
    private final BooleanSetting autoMilkDarkness = new BooleanSetting("auto-milk-darkness", "Automatically drinks milk to clear the Darkness effect.", false);

    private final BooleanSetting disconnectOnPlayer = new BooleanSetting("disconnect-on-player", "Instantly disconnects from the server if another player enters render distance.", false);
    private final BooleanSetting autoDisableOnLowHealth = new BooleanSetting("auto-disable-on-low-health", "Disables the module if health is critical.", true);

    public CityAssistant() {
        super("city-assistant", "Highlights Ancient City elements: shriekers, sensors, chests, and pings for Wardens.", Tim.CATEGORY);
        this.registerSettings(
            range, cityYLevel, renderMode, glowLayers, glowSpread, glowBaseAlpha, spectralBlockFillAlpha,
            pulseSpeed, pulseMinAlpha, pulseMaxAlpha,
            trackShriekers, shriekerColor, activeShriekerColor, disabledShriekerColor,
            trackSensors, sensorColor, activeSensorColor,
            trackContainers, containerColor, containerWhitelist,
            enableAlerts, alertSound, alertVolume, enableWardenPing, autoMilkDarkness,
            disconnectOnPlayer, autoDisableOnLowHealth
        );
    }

    @Override
    public void onEnable() {
        targets.clear();
        scannedChunks.clear();
        checkedContainers.clear();
        notifiedWardens.clear();
        wardenSpawnTimes.clear();
        darknessWarnTimer = 0;
        totalWardenSpawns = 0;
        drinkTimer = 0;
        previousDrinkSlot = -1;
        hasAlertedForCurrentScreen = false;
        GlowingRegistry.clear();
    }

    @Override
    public void onDisable() {
        if (drinkTimer > 0) {
            mc.options.keyUse.setDown(false);
            if (previousDrinkSlot != -1 && mc.player != null) {
                mc.player.getInventory().selected = previousDrinkSlot;
            }
        }
        GlowingRegistry.clear();
        targets.clear();
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;
        if (performSafetyChecks()) return;
        checkForPlayers();
        checkDarknessEffect();
        updateContainerLogic();
        checkOpenedContainerLoot();
        updateMilkDrink();
        updateDynamicStates();
        updateScanningLogic();
    }

    @Subscribe
    private void onRender(EventRender3D event) {
        if (mc.player == null || mc.level == null) return;

        IRenderer3D renderer = event.getRenderer();
        renderer.begin(event.getMatrixStack());

        boolean isSpectral = renderMode.getValue() == RenderMode.SPECTRAL;
        boolean isPulse = renderMode.getValue() == RenderMode.PULSE;
        Set<BlockPos> toRemove = new HashSet<>();

        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            BlockPos pos = entry.getKey();
            TargetType type = entry.getValue();

            if (!mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            if (mc.level.getBlockState(pos).isAir()) { toRemove.add(pos); continue; }

            Block currentBlock = mc.level.getBlockState(pos).getBlock();
            if (!validateBlockType(currentBlock, type)) { toRemove.add(pos); continue; }

            Color color = getColor(type);
            if (color == null) continue;

            if (isSpectral) {
                renderer.drawBox(pos, true, false, RenderUtils.withAlpha(color, spectralBlockFillAlpha.getValue()));
            } else if (isPulse) {
                renderPulseBox(renderer, pos, color);
            } else {
                renderGlowLayers(renderer, pos, color);
                renderer.drawBox(pos, false, true, color.getRGB());
            }
        }

        for (BlockPos pos : toRemove) {
            targets.remove(pos);
        }

        renderer.end();
    }

    private void updateDynamicStates() {
        if (mc.level == null || mc.player == null) return;

        for (BlockPos pos : new HashSet<>(targets.keySet())) {
            if (!mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;

            BlockState state = mc.level.getBlockState(pos);
            Block block = state.getBlock();

            if (block == Blocks.SCULK_SHRIEKER) {
                boolean isShrieking = state.getValue(BlockStateProperties.SHRIEKING);
                boolean canSummon = state.getValue(BlockStateProperties.CAN_SUMMON);
                TargetType currentType = targets.get(pos);
                TargetType newType;

                if (isShrieking) {
                    newType = TargetType.ACTIVE_SHRIEKER;
                } else if (!canSummon) {
                    newType = TargetType.DISABLED_SHRIEKER;
                } else {
                    newType = TargetType.SHRIEKER;
                }

                if (currentType != newType) {
                    targets.put(pos, newType);
                    if (newType == TargetType.ACTIVE_SHRIEKER && enableAlerts.getValue()) {
                        this.sendNotification(NotificationType.ERROR, "Shrieker Activated! Warden spawn risk!");
                        playAlert();
                    }
                }
            } else if (block == Blocks.SCULK_SENSOR) {
                SculkSensorPhase phase = state.getValue(BlockStateProperties.SCULK_SENSOR_PHASE);
                TargetType currentType = targets.get(pos);
                TargetType newType = (phase == SculkSensorPhase.ACTIVE) ? TargetType.ACTIVE_SENSOR : TargetType.SENSOR;

                if (currentType != newType) {
                    targets.put(pos, newType);
                }
            }
        }
    }

    private void updateScanningLogic() {
        if (mc.level.dimension() == null) return;
        if (dimensionChangeCooldown > 0) { dimensionChangeCooldown--; return; }

        String currDim = mc.level.dimension().location().toString();
        if (!currDim.equals(lastDimension)) {
            dimensionChangeCooldown = DIMENSION_CHANGE_COOLDOWN_TICKS;
            lastDimension = currDim;
            targets.clear();
            scannedChunks.clear();
            GlowingRegistry.clear();
            return;
        }

        BlockPos playerPos = mc.player.blockPosition();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;

        cleanupDistantTargets(playerPos);
        scanWardens();
        pruneBlockTargets();
        scanNewChunks(centerChunkX, centerChunkZ);
    }

    private void scanWardens() {
        if (!enableWardenPing.getValue()) return;

        int blockRange = range.getValue() * 16;
        AABB searchBox = new AABB(mc.player.blockPosition()).inflate(blockRange);
        Set<Integer> currentIds = new HashSet<>();

        for (Warden warden : mc.level.getEntitiesOfClass(Warden.class, searchBox, e -> true)) {
            currentIds.add(warden.getId());

            if (notifiedWardens.add(warden.getId())) {
                totalWardenSpawns++;
                wardenSpawnTimes.put(warden.getId(), System.currentTimeMillis());
                this.sendNotification(NotificationType.ERROR, "WARDEN DETECTED! Stealth mode recommended.");
                playAlert();
            } else {
                if (warden.getTarget() != null) {
                    wardenSpawnTimes.put(warden.getId(), System.currentTimeMillis());
                }
            }
        }
        notifiedWardens.retainAll(currentIds);
        wardenSpawnTimes.keySet().retainAll(currentIds);
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
        if (!mc.level.hasChunk(cx, cz)) return false;

        LevelChunk chunk = mc.level.getChunk(cx, cz);
        scanBlockEntitiesInChunk(chunk);
        scannedChunks.add(cp);
        return true;
    }

    private void scanBlockEntitiesInChunk(LevelChunk chunk) {
        int maxY = cityYLevel.getValue();

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            BlockPos pos = be.getBlockPos();
            if (pos.getY() > maxY) continue;

            BlockState state = mc.level.getBlockState(pos);
            Block block = state.getBlock();

            if (block == Blocks.SCULK_SHRIEKER) {
                boolean isShrieking = state.getValue(BlockStateProperties.SHRIEKING);
                boolean canSummon = state.getValue(BlockStateProperties.CAN_SUMMON);

                if (isShrieking) targets.put(pos, TargetType.ACTIVE_SHRIEKER);
                else if (!canSummon) targets.put(pos, TargetType.DISABLED_SHRIEKER);
                else targets.put(pos, TargetType.SHRIEKER);
            }
            else if (block == Blocks.SCULK_SENSOR) {
                SculkSensorPhase phase = state.getValue(BlockStateProperties.SCULK_SENSOR_PHASE);
                targets.put(pos, phase == SculkSensorPhase.ACTIVE ? TargetType.ACTIVE_SENSOR : TargetType.SENSOR);
            }
            else if (be instanceof ChestBlockEntity) {
                targets.put(pos, TargetType.CONTAINER);
            }
        }
    }

    private void updateContainerLogic() {
        if (interactTimeoutTimer > 0) interactTimeoutTimer--;

        if (mc.screen == null && !wasAutoOpened) {
            List<BlockPos> nearbyChests = targets.entrySet().stream()
                .filter(e -> e.getValue() == TargetType.CONTAINER)
                .map(Map.Entry::getKey)
                .filter(pos -> !checkedContainers.contains(pos))
                .filter(pos -> Vec3.atCenterOf(pos).distanceTo(mc.player.position()) <= 4.5)
                .sorted(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(mc.player.position())))
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

    private void checkOpenedContainerLoot() {
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
                        this.sendNotification(NotificationType.ERROR, "Rare loot found in chest: " + stack.getHoverName().getString() + "!");
                        playAlert();
                        hasAlertedForCurrentScreen = true;
                        break;
                    }
                }
            }
        } else {
            hasAlertedForCurrentScreen = false;
        }
    }

    private void updateMilkDrink() {
        if (!autoMilkDarkness.getValue()) {
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

        boolean hasDarkness = mc.player.hasEffect(MobEffects.DARKNESS);

        if (drinkTimer == 0 && hasDarkness && mc.screen == null) {
            int milkSlot = findMilkBucket();
            if (milkSlot != -1) {
                previousDrinkSlot = mc.player.getInventory().selected;
                mc.player.getInventory().selected = milkSlot;
                mc.options.keyUse.setDown(true);
                drinkTimer = 32;
            }
        } else if (drinkTimer > 0) {
            drinkTimer--;
            if (!hasDarkness || drinkTimer == 0 || mc.player.getInventory().getItem(mc.player.getInventory().selected).getItem() != Items.MILK_BUCKET) {
                mc.options.keyUse.setDown(false);
                if (previousDrinkSlot != -1) {
                    mc.player.getInventory().selected = previousDrinkSlot;
                    previousDrinkSlot = -1;
                }
                drinkTimer = 0;
            }
        }
    }

    private int findMilkBucket() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.MILK_BUCKET)) return i;
        }
        return -1;
    }

    private void checkDarknessEffect() {
        if (!enableAlerts.getValue()) return;

        if (darknessWarnTimer > 0) {
            darknessWarnTimer--;
            return;
        }

        boolean hasDarkness = mc.player.hasEffect(MobEffects.DARKNESS);

        if (hasDarkness) {
            this.sendNotification(NotificationType.WARNING, "Darkness effect applied! Vision impaired.");
            playAlert();
            darknessWarnTimer = 200;
        }
    }

    private void checkForPlayers() {
        if (!disconnectOnPlayer.getValue()) return;
        for (Player player : mc.level.players()) {
            if (player == mc.player || player.isSpectator()) continue;
            if (player.distanceTo(mc.player) < 128) {
                this.sendNotification(NotificationType.ERROR, "Player detected in render distance! Disconnecting...");
                mc.disconnect();
                return;
            }
        }
    }

    private void playAlert() {
        if (mc.player == null) return;
        SoundEvent sound = switch (alertSound.getValue()) {
            case LEVEL_UP -> SoundEvents.PLAYER_LEVELUP;
            case RAVAGER_ROAR -> SoundEvents.RAVAGER_ROAR;
            case EXPERIENCE_ORB -> SoundEvents.EXPERIENCE_ORB_PICKUP;
            case BELL -> SoundEvents.BELL_BLOCK;
            case DRAGON_GROWL -> SoundEvents.ENDER_DRAGON_GROWL;
            case WARDEN_ROAR -> SoundEvents.WARDEN_ROAR;
        };
        mc.player.playSound(sound, alertVolume.getValue().floatValue(), 1.0f);
    }

    private void renderGlowLayers(IRenderer3D renderer, BlockPos pos, Color color) {
        int layers = glowLayers.getValue();
        double spread = glowSpread.getValue();
        int baseAlpha = glowBaseAlpha.getValue();

        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            int layerAlpha = Math.max(4, (int) (baseAlpha * (1.0 - (double)(i - 1) / layers)));
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

    private int applyPulse(int baseAlpha) {
        float f = getPulseFactor();
        int min = pulseMinAlpha.getValue();
        int max = pulseMaxAlpha.getValue();
        return Math.min(255, Math.max(0, (int)(min + (max - min) * f)));
    }

    private void renderPulseBox(IRenderer3D renderer, BlockPos pos, Color base) {
        int pa = applyPulse(base.getAlpha());
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

    private boolean validateBlockType(Block block, TargetType type) {
        return switch (type) {
            case SHRIEKER, ACTIVE_SHRIEKER, DISABLED_SHRIEKER -> block == Blocks.SCULK_SHRIEKER;
            case SENSOR, ACTIVE_SENSOR -> block == Blocks.SCULK_SENSOR;
            case CONTAINER -> block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL;
        };
    }

    private Color getColor(TargetType type) {
        return switch (type) {
            case SHRIEKER -> trackShriekers.getValue() ? shriekerColor.getValue() : null;
            case ACTIVE_SHRIEKER -> trackShriekers.getValue() ? activeShriekerColor.getValue() : null;
            case DISABLED_SHRIEKER -> trackShriekers.getValue() ? disabledShriekerColor.getValue() : null;
            case SENSOR -> trackSensors.getValue() ? sensorColor.getValue() : null;
            case ACTIVE_SENSOR -> trackSensors.getValue() ? activeSensorColor.getValue() : null;
            case CONTAINER -> trackContainers.getValue() ? containerColor.getValue() : null;
        };
    }

    private void pruneBlockTargets() {
        if (mc.level == null || mc.player == null) return;
        Set<BlockPos> toRemove = new HashSet<>();
        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            BlockPos pos = entry.getKey();
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;

            if (mc.level.hasChunk(chunkX, chunkZ)) {
                Block currentBlock = mc.level.getBlockState(pos).getBlock();
                if (mc.level.getBlockState(pos).isAir() || !validateBlockType(currentBlock, entry.getValue())) {
                    toRemove.add(pos);
                }
            } else {
                toRemove.add(pos);
                scannedChunks.remove(new ChunkPos(chunkX, chunkZ));
            }
        }
        for (BlockPos pos : toRemove) {
            targets.remove(pos);
        }
    }

    private void cleanupDistantTargets(BlockPos playerPos) {
        int r = range.getValue();
        int pChunkX = playerPos.getX() >> 4;
        int pChunkZ = playerPos.getZ() >> 4;

        targets.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            int dx = (pos.getX() >> 4) - pChunkX;
            int dz = (pos.getZ() >> 4) - pChunkZ;
            if (dx * dx + dz * dz > r * r) {
                scannedChunks.remove(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
                return true;
            }
            return false;
        });
    }

    private boolean performSafetyChecks() {
        if (!autoDisableOnLowHealth.getValue()) return false;
        boolean hasTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
            || mc.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
        if (hasTotem && mc.player.getHealth() <= 6) {
            this.sendNotification(NotificationType.ERROR, "Health is critical, disabling to prevent totem pop.");
            toggle();
            return true;
        }
        return false;
    }

    public List<CityStat> getStats() {
        List<CityStat> stats = new ArrayList<>();
        int activeShriekers = 0, idleShriekers = 0, disabledShriekers = 0;
        int activeSensors = 0, idleSensors = 0, chestsNearby = 0;

        for (TargetType type : targets.values()) {
            switch (type) {
                case ACTIVE_SHRIEKER -> activeShriekers++;
                case SHRIEKER -> idleShriekers++;
                case DISABLED_SHRIEKER -> disabledShriekers++;
                case ACTIVE_SENSOR -> activeSensors++;
                case SENSOR -> idleSensors++;
                case CONTAINER -> chestsNearby++;
            }
        }

        int wardensNearby = notifiedWardens.size();

        int wardenTimer = 0;
        for (long spawnTime : wardenSpawnTimes.values()) {
            long elapsed = (System.currentTimeMillis() - spawnTime) / 1000;
            int remaining = (int) (60 - elapsed);
            if (remaining > wardenTimer) wardenTimer = remaining;
        }

        stats.add(new CityStat("Warden Timer", wardenTimer, new ItemStack(Items.CLOCK), wardenTimer > 0 ? StatSeverity.Critical : StatSeverity.Normal));
        stats.add(new CityStat("Warden Spawns", totalWardenSpawns, new ItemStack(Items.SCULK_CATALYST), totalWardenSpawns > 0 ? StatSeverity.Warning : StatSeverity.Normal));
        stats.add(new CityStat("Wardens Nearby", wardensNearby, new ItemStack(Items.WARDEN_SPAWN_EGG), wardensNearby > 0 ? StatSeverity.Critical : StatSeverity.Normal));
        stats.add(new CityStat("Chests Nearby", chestsNearby, new ItemStack(Items.CHEST), StatSeverity.Normal));
        stats.add(new CityStat("Act Shrieks", activeShriekers, new ItemStack(Items.SCULK_SHRIEKER), activeShriekers > 0 ? StatSeverity.Warning : StatSeverity.Normal));
        stats.add(new CityStat("Shriekers", idleShriekers, new ItemStack(Items.SCULK_SHRIEKER), StatSeverity.Normal));
        stats.add(new CityStat("Dis Shrieks", disabledShriekers, new ItemStack(Items.SCULK_SHRIEKER), StatSeverity.Normal));
        stats.add(new CityStat("Act Sensor", activeSensors, new ItemStack(Items.SCULK_SENSOR), activeSensors > 0 ? StatSeverity.Warning : StatSeverity.Normal));
        stats.add(new CityStat("Sensors", idleSensors, new ItemStack(Items.SCULK_SENSOR), StatSeverity.Normal));

        return stats;
    }
}
