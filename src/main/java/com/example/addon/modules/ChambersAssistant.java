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
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.Slot;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;

public class ChambersAssistant extends ToggleableModule {

    public enum TargetType {
        TRIAL_SPAWNER, ACTIVE_TRIAL_SPAWNER, EJECTING_TRIAL_SPAWNER,
        OMINOUS_SPAWNER, ACTIVE_OMINOUS_SPAWNER, EJECTING_OMINOUS_SPAWNER,
        VAULT, EJECTING_VAULT, OMINOUS_VAULT,
        LOOT_POT, CONTAINER
    }

    public enum RenderMode { GLOW, SPECTRAL, PULSE }

    public enum AlertSound {
        DRAGON_GROWL, LEVEL_UP, RAVAGER_ROAR, EXPERIENCE_ORB, BELL
    }

    public enum StatSeverity { Normal, Warning, Critical }
    public record ChamberStat(String name, int count, ItemStack icon, StatSeverity severity) {}

    private static final int DIMENSION_CHANGE_COOLDOWN_TICKS = 40;
    private static final int INTERACT_TIMEOUT_TICKS = 20;

    private final Map<BlockPos, TargetType> targets = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final Set<BlockPos> checkedContainers = new HashSet<>();
    private final Set<BlockPos> notifiedPots = new HashSet<>();
    private final Set<BlockPos> notifiedActiveOminousSpawners = new HashSet<>();

    private final List<Breeze> breezeTargets = new ArrayList<>();
    private final List<WindCharge> windChargeTargets = new ArrayList<>();
    private final List<ItemFrame> itemFrameTargets = new ArrayList<>();
    private final List<ItemEntity> trialItemTargets = new ArrayList<>();

    private final Set<Integer> notifiedBreezes = new HashSet<>();
    private final Set<Integer> notifiedDroppedRewards = new HashSet<>();
    private int omenWarnTimer = 0;

    private boolean wasAutoOpened = false;
    private int interactTimeoutTimer = 0;

    private int drinkTimer = 0;
    private int previousDrinkSlot = -1;
    private boolean hasAlertedForCurrentScreen = false;

    private String lastDimension = "";
    private int dimensionChangeCooldown = 0;

    private final NumberSetting<Integer> range = new NumberSetting<>("range", "Detection range in chunks.", 16, 1, 128);

    private final NumberSetting<Integer> chamberYLevel = new NumberSetting<>("chamber-y-level", "Maximum Y level to scan. Trial Chambers can generate up to around Y = 40.", 40, -64, 320)
        .onChange((java.util.function.Consumer<Integer>) v -> {
            scannedChunks.clear();
            targets.entrySet().removeIf(entry -> entry.getKey().getY() > v);
        });

    private final EnumSetting<RenderMode> renderMode = new EnumSetting<>("render-mode", "GLOW = layered bloom boxes. SPECTRAL = outline shader. PULSE = fading highlight.", RenderMode.GLOW);

    private final NumberSetting<Integer> beamWidth = new NumberSetting<>("beam-width", "Width of the beams for entities and anomalies.", 15, 5, 50);

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

    private final BooleanSetting trackSpawners = new BooleanSetting("track-spawners", "Highlight Trial Spawners (normal and ominous).", true);
    private final ColorSetting spawnerColor = new ColorSetting("spawner-color", "Color for idle Trial Spawners.", new Color(255, 255, 255, 255)).setVisibility(trackSpawners::getValue);
    private final ColorSetting activeSpawnerColor = new ColorSetting("active-spawner-color", "Color for Trial Spawners that are currently active.", new Color(255, 0, 0, 255)).setVisibility(trackSpawners::getValue);
    private final ColorSetting ejectingSpawnerColor = new ColorSetting("ejecting-spawner-color", "Color for Trial Spawners that are ejecting rewards.", new Color(0, 255, 0, 255)).setVisibility(trackSpawners::getValue);
    private final ColorSetting ominousSpawnerColor = new ColorSetting("ominous-spawner-color", "Color for idle Ominous Spawners.", new Color(0, 180, 255, 255)).setVisibility(trackSpawners::getValue);
    private final ColorSetting activeOminousSpawnerColor = new ColorSetting("active-ominous-spawner-color", "Color for Ominous Spawners that are currently active.", new Color(180, 0, 0, 255)).setVisibility(trackSpawners::getValue);

    private final BooleanSetting trackVaults = new BooleanSetting("track-vaults", "Highlight Vaults (normal and ominous).", true);
    private final ColorSetting vaultColor = new ColorSetting("vault-color", "Color for active/unlooted vaults.", new Color(255, 215, 0, 255)).setVisibility(trackVaults::getValue);
    private final ColorSetting ejectingVaultColor = new ColorSetting("ejecting-vault-color", "Color for vaults that are currently ejecting loot.", new Color(0, 255, 0, 255)).setVisibility(trackVaults::getValue);
    private final ColorSetting ominousVaultColor = new ColorSetting("ominous-vault-color", "Color for Ominous Vaults.", new Color(180, 0, 255, 255)).setVisibility(trackVaults::getValue);

    private final BooleanSetting trackContainers = new BooleanSetting("track-containers", "Highlight standard chests, barrels, and dispensers.", true);
    private final ColorSetting containerColor = new ColorSetting("container-color", "Color for standard chests, barrels, and dispensers.", new Color(0, 0, 255, 255)).setVisibility(trackContainers::getValue);

    private final ItemListSetting containerWhitelist = new ItemListSetting("container-whitelist",
        "Items to alert you about when opening Chests/Barrels/Dispensers.",
        Items.NETHERITE_BLOCK, Items.NETHERITE_INGOT, Items.DIAMOND,
        Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE,
        Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
        Items.ENDER_CHEST, Items.ENCHANTED_GOLDEN_APPLE, Items.ELYTRA, Items.MACE, Items.OMINOUS_BOTTLE,
        Items.NETHERITE_SWORD, Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE,
        Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
        Items.SHULKER_BOX, Items.WHITE_SHULKER_BOX, Items.ORANGE_SHULKER_BOX, Items.MAGENTA_SHULKER_BOX,
        Items.LIGHT_BLUE_SHULKER_BOX, Items.YELLOW_SHULKER_BOX, Items.LIME_SHULKER_BOX, Items.PINK_SHULKER_BOX,
        Items.GRAY_SHULKER_BOX, Items.LIGHT_GRAY_SHULKER_BOX, Items.CYAN_SHULKER_BOX, Items.PURPLE_SHULKER_BOX,
        Items.BLUE_SHULKER_BOX, Items.BROWN_SHULKER_BOX, Items.GREEN_SHULKER_BOX, Items.RED_SHULKER_BOX,
        Items.BLACK_SHULKER_BOX,
        Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE
    );

    private final ItemListSetting potWhitelist = new ItemListSetting("pot-whitelist",
        "Items to search for inside Decorated Pots.",
        Items.DIAMOND, Items.EMERALD, Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE,
        Items.ENDER_PEARL, Items.TRIAL_KEY, Items.OMINOUS_TRIAL_KEY, Items.EXPERIENCE_BOTTLE, Items.OMINOUS_BOTTLE,
        Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE,
        Items.MUSIC_DISC_5, Items.MUSIC_DISC_RELIC,
        Items.ENDER_CHEST
    );

    private final ColorSetting lootPotColor = new ColorSetting("loot-pot-color", "Color for pots containing whitelisted items.", new Color(0, 255, 255, 255));

    private final BooleanSetting trackBreezes = new BooleanSetting("track-breezes", "Highlights Breezes and Wind Charge projectiles.", true);
    private final ColorSetting breezeColor = new ColorSetting("breeze-color", "Color for Breezes and Wind Charges.", new Color(255, 255, 255, 255)).setVisibility(trackBreezes::getValue);

    private final BooleanSetting trackOminousItemFrames = new BooleanSetting("track-item-frames", "Highlights invisible Ominous Item Frames holding items.", true);
    private final ColorSetting itemFrameColor = new ColorSetting("item-frame-color", "Color for invisible Item Frames.", new Color(255, 0, 255, 255)).setVisibility(trackOminousItemFrames::getValue);

    private final BooleanSetting trackTrialItems = new BooleanSetting("track-keys-and-bottles", "Highlights dropped Trial Keys and Ominous Bottles.", true);
    private final ColorSetting trialItemColor = new ColorSetting("trial-item-color", "Color for dropped Trial Keys and Ominous Bottles.", new Color(255, 255, 0, 255)).setVisibility(trackTrialItems::getValue);

    private final BooleanSetting autoOpenVaults = new BooleanSetting("auto-open-vaults", "Automatically opens Vaults when you have a Trial Key.", true);
    private final BooleanSetting autoDrinkOminous = new BooleanSetting("auto-drink-ominous", "Automatically drinks an Ominous Bottle when near a Trial Spawner to trigger Ominous state.", false);
    private final BooleanSetting enableAlerts = new BooleanSetting("alerts", "Master toggle for audio cues, reward announcements, and omen effect warnings.", true);
    private final EnumSetting<AlertSound> alertSound = new EnumSetting<>("alert-sound", "Which sound to play for module alerts.", AlertSound.DRAGON_GROWL).setVisibility(enableAlerts::getValue);
    private final NumberSetting<Double> alertVolume = new NumberSetting<>("alert-volume", "Volume of the alert sound. Goes up to 5.0 for extra loud alerts.", 1.0, 0.0, 5.0).setVisibility(enableAlerts::getValue);
    private final BooleanSetting alertOnLootPot = new BooleanSetting("alert-on-loot-pot", "Plays a sound and warns you when a pot containing whitelisted loot is found.", true);

    private final BooleanSetting disconnectOnPlayer = new BooleanSetting("disconnect-on-player", "Instantly disconnects from the server if another player enters render distance.", false);
    private final BooleanSetting autoDisableOnLowHealth = new BooleanSetting("auto-disable-on-low-health", "Disables the module if health is critical.", true);

    public ChambersAssistant() {
        super("chambers-assistant", "Highlights Trial Chambers elements: spawners, vaults, pots, and breezes.", Tim.CATEGORY);
        this.registerSettings(
            range, chamberYLevel, renderMode, beamWidth, glowLayers, glowSpread, glowBaseAlpha,
            spectralBlockFillAlpha, pulseSpeed, pulseMinAlpha, pulseMaxAlpha,
            trackSpawners, spawnerColor, activeSpawnerColor, ejectingSpawnerColor, ominousSpawnerColor, activeOminousSpawnerColor,
            trackVaults, vaultColor, ejectingVaultColor, ominousVaultColor,
            trackContainers, containerColor, containerWhitelist, potWhitelist, lootPotColor,
            trackBreezes, breezeColor, trackOminousItemFrames, itemFrameColor, trackTrialItems, trialItemColor,
            autoOpenVaults, autoDrinkOminous, enableAlerts, alertSound, alertVolume, alertOnLootPot,
            disconnectOnPlayer, autoDisableOnLowHealth
        );
    }

    @Override
    public void onEnable() {
        targets.clear();
        scannedChunks.clear();
        checkedContainers.clear();
        notifiedPots.clear();
        notifiedActiveOminousSpawners.clear();
        notifiedDroppedRewards.clear();
        breezeTargets.clear();
        windChargeTargets.clear();
        itemFrameTargets.clear();
        trialItemTargets.clear();
        notifiedBreezes.clear();
        omenWarnTimer = 0;
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
        checkOmenEffects();
        updateContainerLogic();
        checkOpenedContainerLoot();
        updateOminousDrink();
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
            notifiedPots.remove(pos);
            notifiedActiveOminousSpawners.remove(pos);
        }

        renderEntity(renderer, isSpectral, isPulse, trackBreezes.getValue(), false, breezeTargets, breezeColor.getValue());
        renderEntity(renderer, isSpectral, isPulse, trackBreezes.getValue(), true, windChargeTargets, breezeColor.getValue());
        renderEntity(renderer, isSpectral, isPulse, trackOminousItemFrames.getValue(), true, itemFrameTargets, itemFrameColor.getValue());
        renderEntity(renderer, isSpectral, isPulse, trackTrialItems.getValue(), true, trialItemTargets, trialItemColor.getValue());

        renderer.end();
    }

    private void updateDynamicStates() {
        if (mc.level == null || mc.player == null) return;

        for (BlockPos pos : new HashSet<>(targets.keySet())) {
            if (!mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;

            BlockState state = mc.level.getBlockState(pos);
            Block block = state.getBlock();

            if (block == Blocks.TRIAL_SPAWNER) {
                boolean isOminous = state.getValue(BlockStateProperties.OMINOUS);
                TrialSpawnerState spawnerState = state.getValue(BlockStateProperties.TRIAL_SPAWNER_STATE);
                TargetType currentType = targets.get(pos);
                TargetType newType;

                if (spawnerState == TrialSpawnerState.EJECTING_REWARD) {
                    newType = isOminous ? TargetType.EJECTING_OMINOUS_SPAWNER : TargetType.EJECTING_TRIAL_SPAWNER;
                } else if (spawnerState == TrialSpawnerState.ACTIVE) {
                    newType = isOminous ? TargetType.ACTIVE_OMINOUS_SPAWNER : TargetType.ACTIVE_TRIAL_SPAWNER;
                } else {
                    newType = isOminous ? TargetType.OMINOUS_SPAWNER : TargetType.TRIAL_SPAWNER;
                }

                if (currentType != newType) {
                    targets.put(pos, newType);

                    if (newType == TargetType.ACTIVE_OMINOUS_SPAWNER && enableAlerts.getValue() && notifiedActiveOminousSpawners.add(pos)) {
                        this.sendNotification(NotificationType.ERROR, "Ominous Spawner Activated!");
                        playAlert();
                    } else if (newType == TargetType.EJECTING_TRIAL_SPAWNER || newType == TargetType.EJECTING_OMINOUS_SPAWNER) {
                        if (enableAlerts.getValue()) {
                            this.sendNotification(NotificationType.WARNING, "Trial Spawner is ejecting rewards!");
                            playAlert();
                        }
                    }
                }
            } else if (block == Blocks.VAULT) {
                boolean isOminous = state.getValue(BlockStateProperties.OMINOUS);
                VaultState vState = state.getValue(BlockStateProperties.VAULT_STATE);
                TargetType currentType = targets.get(pos);
                TargetType newType;

                if (vState == VaultState.EJECTING) {
                    newType = TargetType.EJECTING_VAULT;
                } else {
                    newType = isOminous ? TargetType.OMINOUS_VAULT : TargetType.VAULT;
                }

                if (currentType != newType) {
                    targets.put(pos, newType);
                    if (newType == TargetType.EJECTING_VAULT && enableAlerts.getValue()) {
                        this.sendNotification(NotificationType.INFO, "Vault is ejecting loot!");
                        playAlert();
                    }
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
        scanBreezes();
        scanWindCharges();
        scanOminousItemFrames();
        scanTrialItems();
        scanDroppedRewards();
        pruneBlockTargets();
        scanNewChunks(centerChunkX, centerChunkZ);
    }

    private void scanDroppedRewards() {
        if (!enableAlerts.getValue()) return;
        int blockRange = range.getValue() * 16;
        AABB searchBox = new AABB(mc.player.blockPosition()).inflate(blockRange);
        Set<Integer> currentIds = new HashSet<>();

        for (ItemEntity item : mc.level.getEntitiesOfClass(ItemEntity.class, searchBox, e -> true)) {
            currentIds.add(item.getId());
            if (notifiedDroppedRewards.add(item.getId())) {
                for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
                    TargetType type = entry.getValue();
                    if (type == TargetType.EJECTING_TRIAL_SPAWNER || type == TargetType.EJECTING_OMINOUS_SPAWNER || type == TargetType.EJECTING_VAULT) {
                        if (item.position().distanceTo(Vec3.atCenterOf(entry.getKey())) < 2.0) {
                            this.sendNotification(NotificationType.INFO, "Reward Ejected: " + item.getItem().getHoverName().getString() + "!");
                            playAlert();
                            break;
                        }
                    }
                }
            }
        }
        notifiedDroppedRewards.retainAll(currentIds);
    }

    private void scanBreezes() {
        breezeTargets.clear();
        if (!trackBreezes.getValue()) return;

        int blockRange = range.getValue() * 16;
        AABB searchBox = new AABB(mc.player.blockPosition()).inflate(blockRange);
        Set<Integer> currentIds = new HashSet<>();

        for (Breeze breeze : mc.level.getEntitiesOfClass(Breeze.class, searchBox, e -> true)) {
            breezeTargets.add(breeze);
            currentIds.add(breeze.getId());

            if (renderMode.getValue() == RenderMode.SPECTRAL) {
                GlowingRegistry.add(breeze.getId(), breezeColor.getValue().getRGB());
            } else {
                GlowingRegistry.remove(breeze.getId());
            }

            if (notifiedBreezes.add(breeze.getId())) {
                this.sendNotification(NotificationType.WARNING, "Breeze Detected!");
                playAlert();
            }
        }
        notifiedBreezes.retainAll(currentIds);
    }

    private void scanWindCharges() {
        windChargeTargets.clear();
        if (!trackBreezes.getValue()) return;

        int blockRange = range.getValue() * 16;
        AABB searchBox = new AABB(mc.player.blockPosition()).inflate(blockRange);

        for (WindCharge charge : mc.level.getEntitiesOfClass(WindCharge.class, searchBox, e -> true)) {
            windChargeTargets.add(charge);
            if (renderMode.getValue() == RenderMode.SPECTRAL) {
                GlowingRegistry.add(charge.getId(), breezeColor.getValue().getRGB());
            }
        }
    }

    private void scanOminousItemFrames() {
        itemFrameTargets.clear();
        if (!trackOminousItemFrames.getValue()) return;

        int blockRange = range.getValue() * 16;
        AABB searchBox = new AABB(mc.player.blockPosition()).inflate(blockRange);

        for (ItemFrame frame : mc.level.getEntitiesOfClass(ItemFrame.class, searchBox, e -> true)) {
            if (frame.isInvisible() && !frame.getItem().isEmpty()) {
                itemFrameTargets.add(frame);
                if (renderMode.getValue() == RenderMode.SPECTRAL) {
                    GlowingRegistry.add(frame.getId(), itemFrameColor.getValue().getRGB());
                }
            }
        }
    }

    private void scanTrialItems() {
        trialItemTargets.clear();
        if (!trackTrialItems.getValue()) return;

        int blockRange = range.getValue() * 16;
        AABB searchBox = new AABB(mc.player.blockPosition()).inflate(blockRange);

        for (ItemEntity item : mc.level.getEntitiesOfClass(ItemEntity.class, searchBox, e -> true)) {
            Item stackItem = item.getItem().getItem();
            if (stackItem == Items.TRIAL_KEY || stackItem == Items.OMINOUS_TRIAL_KEY || stackItem == Items.OMINOUS_BOTTLE) {
                trialItemTargets.add(item);
                if (renderMode.getValue() == RenderMode.SPECTRAL) {
                    GlowingRegistry.add(item.getId(), trialItemColor.getValue().getRGB());
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
        if (!mc.level.hasChunk(cx, cz)) return false;

        LevelChunk chunk = mc.level.getChunk(cx, cz);
        scanBlockEntitiesInChunk(chunk);
        scannedChunks.add(cp);
        return true;
    }

    private void scanBlockEntitiesInChunk(LevelChunk chunk) {
        int maxY = chamberYLevel.getValue();

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            BlockPos pos = be.getBlockPos();
            if (pos.getY() > maxY) continue;

            BlockState state = mc.level.getBlockState(pos);

            if (be instanceof TrialSpawnerBlockEntity) {
                boolean isOminous = state.getValue(BlockStateProperties.OMINOUS);
                TrialSpawnerState spawnerState = state.getValue(BlockStateProperties.TRIAL_SPAWNER_STATE);

                if (spawnerState == TrialSpawnerState.EJECTING_REWARD) {
                    targets.put(pos, isOminous ? TargetType.EJECTING_OMINOUS_SPAWNER : TargetType.EJECTING_TRIAL_SPAWNER);
                } else if (spawnerState == TrialSpawnerState.ACTIVE) {
                    targets.put(pos, isOminous ? TargetType.ACTIVE_OMINOUS_SPAWNER : TargetType.ACTIVE_TRIAL_SPAWNER);
                } else {
                    targets.put(pos, isOminous ? TargetType.OMINOUS_SPAWNER : TargetType.TRIAL_SPAWNER);
                }
            }
            else if (be instanceof VaultBlockEntity) {
                boolean isOminous = state.getValue(BlockStateProperties.OMINOUS);
                VaultState vState = state.getValue(BlockStateProperties.VAULT_STATE);

                if (vState == VaultState.EJECTING) {
                    targets.put(pos, TargetType.EJECTING_VAULT);
                } else {
                    targets.put(pos, isOminous ? TargetType.OMINOUS_VAULT : TargetType.VAULT);
                }
            }
            else if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof DispenserBlockEntity) {
                targets.put(pos, TargetType.CONTAINER);
            }
            else if (be instanceof DecoratedPotBlockEntity pot) {
                if (!potWhitelist.getList().isEmpty()) {
                    ItemStack potItem = pot.getTheItem();
                    if (!potItem.isEmpty() && potWhitelist.getList().contains(potItem.getItem())) {
                        targets.put(pos, TargetType.LOOT_POT);

                        if (notifiedPots.add(pos)) {
                            if (alertOnLootPot.getValue()) {
                                this.sendNotification(NotificationType.INFO, "Loot Pot detected containing: " + potItem.getHoverName().getString() + "!");
                                playAlert();
                            }
                        }
                    }
                }
            }
        }
    }

    private void updateContainerLogic() {
        if (interactTimeoutTimer > 0) interactTimeoutTimer--;

        if (mc.screen == null && !wasAutoOpened && autoOpenVaults.getValue()) {
            List<BlockPos> nearbyVaults = targets.entrySet().stream()
                .filter(e -> e.getValue() == TargetType.VAULT || e.getValue() == TargetType.OMINOUS_VAULT)
                .map(Map.Entry::getKey)
                .filter(pos -> !checkedContainers.contains(pos))
                .filter(pos -> Vec3.atCenterOf(pos).distanceTo(mc.player.position()) <= 4.5)
                .sorted(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(mc.player.position())))
                .toList();

            if (!nearbyVaults.isEmpty()) {
                BlockPos pos = nearbyVaults.get(0);
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
                        this.sendNotification(NotificationType.ERROR, "Rare loot found in container: " + stack.getHoverName().getString() + "!");
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

    private void updateOminousDrink() {
        if (!autoDrinkOminous.getValue()) {
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

        boolean hasOmen = mc.player.hasEffect(MobEffects.BAD_OMEN) || mc.player.hasEffect(MobEffects.TRIAL_OMEN);

        boolean hasNearbySpawner = targets.entrySet().stream()
            .anyMatch(e -> e.getValue() == TargetType.TRIAL_SPAWNER && mc.player.position().distanceTo(Vec3.atCenterOf(e.getKey())) < 8.0);

        if (drinkTimer == 0 && !hasOmen && hasNearbySpawner && mc.screen == null) {
            int bottleSlot = findOminousBottle();
            if (bottleSlot != -1) {
                previousDrinkSlot = mc.player.getInventory().selected;
                mc.player.getInventory().selected = bottleSlot;
                mc.options.keyUse.setDown(true);
                drinkTimer = 40;
            }
        } else if (drinkTimer > 0) {
            drinkTimer--;
            if (hasOmen || drinkTimer == 0 || mc.player.getInventory().getItem(mc.player.getInventory().selected).getItem() != Items.OMINOUS_BOTTLE) {
                mc.options.keyUse.setDown(false);
                if (previousDrinkSlot != -1) {
                    mc.player.getInventory().selected = previousDrinkSlot;
                    previousDrinkSlot = -1;
                }
                drinkTimer = 0;
            }
        }
    }

    private int findOminousBottle() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.OMINOUS_BOTTLE)) return i;
        }
        return -1;
    }

    private void checkOmenEffects() {
        if (!enableAlerts.getValue()) return;

        if (omenWarnTimer > 0) {
            omenWarnTimer--;
            return;
        }

        boolean hasBadOmen = mc.player.hasEffect(MobEffects.BAD_OMEN);
        boolean hasTrialOmen = mc.player.hasEffect(MobEffects.TRIAL_OMEN);

        if (hasTrialOmen) {
            this.sendNotification(NotificationType.WARNING, "You have the Trial Omen effect! Ominous Spawners are active.");
            playAlert();
            omenWarnTimer = 200;
        } else if (hasBadOmen) {
            this.sendNotification(NotificationType.INFO, "You have Bad Omen. Approaching a Trial Spawner will trigger an Ominous state.");
            playAlert();
            omenWarnTimer = 200;
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
            default -> SoundEvents.ENDER_DRAGON_GROWL;
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

    private void renderPulseBox(IRenderer3D renderer, AABB box, Color base) {
        int pa = applyPulse(base.getAlpha());
        int layers = glowLayers.getValue();
        double spread = glowSpread.getValue();
        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double taper = 1.0 - ((double)(i - 1) / layers) * 0.6;
            int layerAlpha = Math.max(4, (int)(pa * taper));
            AABB expanded = box.inflate(expansion);
            renderer.drawBox(expanded.minX, expanded.minY, expanded.minZ, expanded.getXsize(), expanded.getYsize(), expanded.getZsize(),
                true, false, RenderUtils.withAlpha(base, layerAlpha));
        }
        renderer.drawBox(box.minX, box.minY, box.minZ, box.getXsize(), box.getYsize(), box.getZsize(), true, true, RenderUtils.withAlpha(base, pa));
    }

    private void renderGlowLayers(IRenderer3D renderer, AABB box, Color color) {
        int layers = glowLayers.getValue();
        double spread = glowSpread.getValue();
        int baseAlpha = glowBaseAlpha.getValue();
        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            int layerAlpha = Math.max(4, (int) (baseAlpha * (1.0 - (double)(i - 1) / layers)));
            AABB expanded = box.inflate(expansion);
            renderer.drawBox(expanded.minX, expanded.minY, expanded.minZ, expanded.getXsize(), expanded.getYsize(), expanded.getZsize(),
                true, false, RenderUtils.withAlpha(color, layerAlpha));
        }
    }

    private void renderEntity(IRenderer3D renderer, boolean isSpectral, boolean isPulse, boolean isEnabled, boolean renderBeam, List<? extends net.minecraft.world.entity.Entity> entities, Color color) {
        if (!isEnabled || entities.isEmpty()) return;

        double beamSize = beamWidth.getValue() / 100.0;
        for (net.minecraft.world.entity.Entity entity : entities) {
            if (!entity.isAlive()) continue;
            AABB box = entity.getBoundingBox();
            Vec3 pos = entity.position();
            AABB beamBox = renderBeam ? new AABB(
                pos.x - beamSize, pos.y, pos.z - beamSize,
                pos.x + beamSize, mc.level.getHeight(), pos.z + beamSize
            ) : null;

            if (isSpectral) {
                renderer.drawBox(box.minX, box.minY, box.minZ, box.getXsize(), box.getYsize(), box.getZsize(), false, true, RenderUtils.withAlpha(color, 200));
                if (renderBeam) renderer.drawBox(beamBox.minX, beamBox.minY, beamBox.minZ, beamBox.getXsize(), beamBox.getYsize(), beamBox.getZsize(), true, true, RenderUtils.withAlpha(color, 180));
            } else if (isPulse) {
                renderPulseBox(renderer, box, color);
                if (renderBeam) renderPulseBox(renderer, beamBox, color);
            } else {
                renderGlowLayers(renderer, box, color);
                renderer.drawBox(box.minX, box.minY, box.minZ, box.getXsize(), box.getYsize(), box.getZsize(), false, true, color.getRGB());
                if (renderBeam) {
                    renderGlowLayers(renderer, beamBox, color);
                    renderer.drawBox(beamBox.minX, beamBox.minY, beamBox.minZ, beamBox.getXsize(), beamBox.getYsize(), beamBox.getZsize(), true, true, RenderUtils.withAlpha(color, 60));
                }
            }
        }
    }

    private boolean validateBlockType(Block block, TargetType type) {
        return switch (type) {
            case TRIAL_SPAWNER, ACTIVE_TRIAL_SPAWNER, EJECTING_TRIAL_SPAWNER, OMINOUS_SPAWNER, ACTIVE_OMINOUS_SPAWNER, EJECTING_OMINOUS_SPAWNER -> block == Blocks.TRIAL_SPAWNER;
            case VAULT, EJECTING_VAULT, OMINOUS_VAULT -> block == Blocks.VAULT;
            case LOOT_POT -> block == Blocks.DECORATED_POT;
            case CONTAINER -> block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL || block == Blocks.DISPENSER || block == Blocks.DROPPER;
        };
    }

    private Color getColor(TargetType type) {
        return switch (type) {
            case TRIAL_SPAWNER -> trackSpawners.getValue() ? spawnerColor.getValue() : null;
            case ACTIVE_TRIAL_SPAWNER -> trackSpawners.getValue() ? activeSpawnerColor.getValue() : null;
            case EJECTING_TRIAL_SPAWNER -> trackSpawners.getValue() ? ejectingSpawnerColor.getValue() : null;
            case OMINOUS_SPAWNER -> trackSpawners.getValue() ? ominousSpawnerColor.getValue() : null;
            case ACTIVE_OMINOUS_SPAWNER -> trackSpawners.getValue() ? activeOminousSpawnerColor.getValue() : null;
            case EJECTING_OMINOUS_SPAWNER -> trackSpawners.getValue() ? ejectingSpawnerColor.getValue() : null;
            case VAULT -> trackVaults.getValue() ? vaultColor.getValue() : null;
            case EJECTING_VAULT -> trackVaults.getValue() ? ejectingVaultColor.getValue() : null;
            case OMINOUS_VAULT -> trackVaults.getValue() ? ominousVaultColor.getValue() : null;
            case LOOT_POT -> lootPotColor.getValue();
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
            notifiedPots.remove(pos);
            notifiedActiveOminousSpawners.remove(pos);
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
                notifiedPots.remove(pos);
                notifiedActiveOminousSpawners.remove(pos);
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

    public List<ChamberStat> getStats() {
        List<ChamberStat> stats = new ArrayList<>();
        int normalSpawners = 0, ominousSpawners = 0, normalVaults = 0, ominousVaults = 0, lootPots = 0, containers = 0;

        for (TargetType type : targets.values()) {
            switch (type) {
                case TRIAL_SPAWNER, ACTIVE_TRIAL_SPAWNER, EJECTING_TRIAL_SPAWNER -> normalSpawners++;
                case ACTIVE_OMINOUS_SPAWNER, EJECTING_OMINOUS_SPAWNER, OMINOUS_SPAWNER -> ominousSpawners++;
                case VAULT, EJECTING_VAULT -> normalVaults++;
                case OMINOUS_VAULT -> ominousVaults++;
                case LOOT_POT -> lootPots++;
                case CONTAINER -> containers++;
            }
        }

        stats.add(new ChamberStat("Spawners", normalSpawners, new ItemStack(Items.TRIAL_SPAWNER), StatSeverity.Normal));
        stats.add(new ChamberStat("Ominous", ominousSpawners, new ItemStack(Items.TRIAL_SPAWNER), ominousSpawners > 0 ? StatSeverity.Warning : StatSeverity.Normal));
        stats.add(new ChamberStat("Vaults", normalVaults, new ItemStack(Items.VAULT), StatSeverity.Normal));
        stats.add(new ChamberStat("Ominous V", ominousVaults, new ItemStack(Items.VAULT), StatSeverity.Normal));
        stats.add(new ChamberStat("Pots", lootPots, new ItemStack(Items.DECORATED_POT), StatSeverity.Normal));
        stats.add(new ChamberStat("Chests", containers, new ItemStack(Items.CHEST), StatSeverity.Normal));
        stats.add(new ChamberStat("Breezes", breezeTargets.size(), new ItemStack(Items.WIND_CHARGE), breezeTargets.size() > 0 ? StatSeverity.Warning : StatSeverity.Normal));
        stats.add(new ChamberStat("Keys", trialItemTargets.size(), new ItemStack(Items.TRIAL_KEY), StatSeverity.Normal));

        return stats;
    }
}
