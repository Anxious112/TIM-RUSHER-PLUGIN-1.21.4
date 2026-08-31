package com.example.addon.modules;

import com.example.addon.Tim;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.setting.BindSetting;
import org.rusherhack.client.api.setting.BlockListSetting;
import org.rusherhack.client.api.setting.ItemListSetting;
import org.rusherhack.client.api.utils.InventoryUtils;
import org.rusherhack.core.bind.key.NullKey;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.ListSetting;
import org.rusherhack.core.setting.NumberSetting;

import baritone.api.BaritoneAPI;

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Automated Baritone miner ported from the Meteor "Tim" addon. Drives Baritone
 * via chat "#" commands and layers heavy safety / deposit / craft / mend state
 * machines on top.
 */
public class Baromine extends ToggleableModule {

    // ─────────────────────────── Enums ───────────────────────────
    public enum TargetMode { Ores, Blocks }
    public enum CraftMode { Disabled, Ores, Blocks }
    public enum ExcessDropMode { Disabled, Cobblestone, Netherrack, CobbledDeepslate }
    public enum DepositMode { Disabled, Inventory, EnderChest }
    public enum ToolEnchant { SilkTouch, Fortune }
    public enum PingMode { Chat, Sound, Both }
    public enum WarningSound {
        Pling, Bass, Harp, Bell, Anvil, LevelUp, OrbPickup, Beacon,
        GhastWarn, DragonGrowl, WitherSpawn, ChallengeComplete
    }

    private enum HideoutState { IDLE, DIGGING, ENTERING, SEALING, DONE }
    private enum DeepDarkState { IDLE, RETREATING, ASCENDING, RUNNING_AWAY }
    private enum CraftState {
        IDLE, GATHERING_WOOD, FINDING_TABLE, PLACING_TABLE, OPENING_TABLE,
        PULLING_MATERIALS, CRAFTING, CLOSING_TABLE, BREAKING_TABLE, PICKING_UP_TABLE, RESUMING
    }
    private enum DepositState {
        IDLE, PAUSING_BARITONE, CLEARING_SPACE, PLACING_ECHEST, OPENING_ECHEST, EXTRACTING_SHULKER,
        CLOSING_ECHEST, PLACING_SHULKER, OPENING_SHULKER, TRANSFERRING_ITEMS, CLOSING_SHULKER,
        BREAKING_SHULKER, PICKING_UP_SHULKER, REOPENING_ECHEST, DEPOSITING_SHULKER, REPLACING_TOOLS,
        REGEAR_FOOD, CLOSING_ECHEST_AGAIN, BREAKING_ECHEST, PICKING_UP_ECHEST, MINING_SURROUNDINGS_SHULKER, RESUMING
    }

    // ─────────────────────────── Settings: Target ───────────────────────────
    private final EnumSetting<TargetMode> targetMode = new EnumSetting<>("target-mode", "What type of block to mine.", TargetMode.Ores)
        .onChange((java.util.function.Consumer<TargetMode>) v -> { if (isToggled()) updateBaritoneGoal(); });
    private final ListSetting<Block> targetOres = new BlockListSetting("target-ores", "Ores to mine. Full block picker -- add as many as you like.", Blocks.DIAMOND_ORE)
        .setVisibility(() -> targetMode.getValue() == TargetMode.Ores);
    private final ListSetting<Block> targetBlocks = new BlockListSetting("target-blocks", "Blocks to mine. Full block picker -- add as many as you like.", Blocks.STONE)
        .setVisibility(() -> targetMode.getValue() == TargetMode.Blocks);
    private final BooleanSetting includeDeepslate = new BooleanSetting("include-deepslate", "Also mine the deepslate variant of each selected ore.", true)
        .setVisibility(() -> targetMode.getValue() == TargetMode.Ores);
    public final NumberSetting<Integer> targetStacks = new NumberSetting<>("target-stacks", "Total stacks to mine before stopping the module entirely.", 1, 1, 64);

    private final EnumSetting<CraftMode> autoCraft = new EnumSetting<>("auto-craft", "Automatically crafts items using a crafting table.", CraftMode.Disabled);
    private final ListSetting<Block> craftOreList = new BlockListSetting("craft-ore-list", "Ore blocks to craft from raw materials (e.g. Iron Block, Diamond Block).")
        .setVisibility(() -> autoCraft.getValue() == CraftMode.Ores);
    private final ListSetting<Block> craftBlockList = new BlockListSetting("craft-block-list", "Blocks to craft from mined materials (e.g. Stone Bricks, slabs, stairs).")
        .setVisibility(() -> autoCraft.getValue() == CraftMode.Blocks);

    // ─────────────────────────── Settings: Safety & Limits ───────────────────────────
    private final NumberSetting<Double> warningHealth = new NumberSetting<>("warning-health", "Health level to trigger a warning.", 12.0, 1.0, 20.0);
    private final NumberSetting<Double> criticalHealth = new NumberSetting<>("critical-health", "Health level to trigger an instant disconnect.", 6.0, 1.0, 20.0);
    private final BooleanSetting disconnectOnPlayer = new BooleanSetting("disconnect-on-player", "Instantly disconnects if another player enters render distance.", false);
    private final BooleanSetting pauseInCombat = new BooleanSetting("pause-in-combat", "Pauses if a hostile mob is within ~6 blocks.", true);
    private final BooleanSetting goldenHelmet = new BooleanSetting("golden-helmet", "Stops mining if you are not wearing a Golden Helmet.", false);
    private final BooleanSetting lavaAvoidance = new BooleanSetting("lava-avoidance", "Activates safety protocols if lava is directly touching you.", true);
    private final BooleanSetting waterAvoidance = new BooleanSetting("water-avoidance", "Activates safety protocols if water is directly touching you.", false);
    private final EnumSetting<ExcessDropMode> dropExcessMode = new EnumSetting<>("drop-excess", "Automatically drops excess blocks, keeping only a single stack.", ExcessDropMode.Disabled);
    private final BooleanSetting avoidDeepDark = new BooleanSetting("avoid-deep-dark", "Tactical retreat if you enter the Deep Dark biome.", true);
    private final BooleanSetting safeLogout = new BooleanSetting("safe-logout", "Hides inside a wall before turning off when the target is reached.", true);
    private final NumberSetting<Integer> minYLevel = new NumberSetting<>("min-y-level", "Stops mining if the player goes below this Y-level.", -64, -64, 320);
    private final NumberSetting<Integer> maxYLevel = new NumberSetting<>("max-y-level", "Stops mining if the player goes above this Y-level.", 320, -64, 320);
    private final BooleanSetting radiusLimit = new BooleanSetting("radius-limit", "Stops mining if the player wanders too far from the start.", false);
    private final NumberSetting<Integer> radiusBlocks = new NumberSetting<>("radius-blocks", "The maximum block radius from the starting position.", 500, 50, 5000)
        .setVisibility(radiusLimit::getValue);

    // ─────────────────────────── Settings: Auto Deposit ───────────────────────────
    private final EnumSetting<DepositMode> depositMode = new EnumSetting<>("deposit-mode", "Where to stash items.", DepositMode.EnderChest);
    private final NumberSetting<Integer> depositStacks = new NumberSetting<>("deposit-stacks", "How many stacks in your inventory trigger the deposit process.", 1, 1, 16)
        .setVisibility(() -> depositMode.getValue() != DepositMode.Disabled);
    private final NumberSetting<Integer> swapSlot = new NumberSetting<>("swap-slot", "The hotbar slot used to swap Shulkers / Ender Chests into.", 1, 0, 8)
        .setVisibility(() -> depositMode.getValue() != DepositMode.Disabled);
    private final EnumSetting<ToolEnchant> toolEnchant = new EnumSetting<>("tool-enchant", "Which enchantment to require when breaking blocks / replacing tools.", ToolEnchant.SilkTouch)
        .setVisibility(() -> depositMode.getValue() != DepositMode.Disabled);
    private final ListSetting<Item> foodItems = new ItemListSetting("food-items", "Food items to pull from the Ender Chest when low. Full item picker.", Items.ENCHANTED_GOLDEN_APPLE)
        .setVisibility(() -> depositMode.getValue() == DepositMode.EnderChest);
    private final NumberSetting<Integer> minFoodCount = new NumberSetting<>("min-food-count", "Minimum total of the selected foods to keep.", 10, 1, 64)
        .setVisibility(() -> depositMode.getValue() == DepositMode.EnderChest && !foodItems.getList().isEmpty());
    private final BindSetting highlightContainerKey = new BindSetting("highlight-craft-container", "Look at a Shulker Box or Chest and press to set it as the crafting material source.", NullKey.INSTANCE)
        .setVisibility(() -> autoCraft.getValue() != CraftMode.Disabled);

    // ─────────────────────────── Settings: Auto Mend ───────────────────────────
    private final BooleanSetting autoMend = new BooleanSetting("auto-mend", "Mines XP ores to repair tools, then resumes.", false);
    private final ListSetting<Block> mendOres = new BlockListSetting("mend-ores", "Ore blocks to mine for XP when auto-mending. Full block picker.", Blocks.NETHER_QUARTZ_ORE)
        .setVisibility(autoMend::getValue);
    private final NumberSetting<Double> maxMendDurability = new NumberSetting<>("max-mend-durability", "Durability % to reach before stopping Auto-Mend.", 70.0, 10.0, 100.0)
        .setVisibility(autoMend::getValue);
    private final NumberSetting<Double> minToolDurability = new NumberSetting<>("min-tool-durability", "Durability % to trigger replacing tools / activating Auto-Mend.", 10.0, 1.0, 50.0)
        .setVisibility(() -> depositMode.getValue() != DepositMode.Disabled || autoMend.getValue());

    // ─────────────────────────── Settings: Session ───────────────────────────
    private final BooleanSetting antiAfk = new BooleanSetting("anti-afk", "Periodically jumps and swings to prevent AFK kicks when stuck.", true);
    private final BooleanSetting enableMaxRuntime = new BooleanSetting("enable-max-runtime", "Enables a maximum time limit before auto-off.", false);
    private final NumberSetting<Double> maxRuntimeHours = new NumberSetting<>("max-runtime-hours", "Maximum hours to run before turning off.", 8.0, 0.5, 24.0)
        .setVisibility(enableMaxRuntime::getValue);
    private final BooleanSetting autoReconnect = new BooleanSetting("auto-reconnect", "Reconnects after X hours if disconnected by safety or timeout.", false);
    private final NumberSetting<Integer> reconnectHours = new NumberSetting<>("delay-hours", "How many hours to wait before attempting to reconnect.", 2, 1, 24)
        .setVisibility(autoReconnect::getValue);

    // ─────────────────────────── Settings: Notifications ───────────────────────────
    private final EnumSetting<PingMode> pingMode = new EnumSetting<>("ping-mode", "How you want to be notified of module events.", PingMode.Both);
    private final EnumSetting<WarningSound> warningSound = new EnumSetting<>("warning-sound", "Which sound to play for notifications.", WarningSound.Pling)
        .setVisibility(() -> pingMode.getValue() != PingMode.Chat);
    private final NumberSetting<Double> soundVolume = new NumberSetting<>("volume", "The volume of the warning sound.", 1.0, 0.0, 1.0)
        .setVisibility(() -> pingMode.getValue() != PingMode.Chat);

    private static final Predicate<ItemStack> SHULKER_PREDICATE = stack ->
        stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;

    // ─────────────────────────── State ───────────────────────────
    private boolean wasPausedForCombat = false;
    private boolean hasHealthWarned = false;
    private boolean playerLogoutPending = false;
    private boolean isFinalDeposit = false;
    private boolean autoMendFailed = false;

    private boolean isAutoMending = false;
    private int mendToolSwapDelay = 0;

    private ServerData lastServer = null;
    private boolean isWaitingToReconnect = false;
    private long reconnectTime = 0;

    private long startTime = 0;
    private double startX = 0;
    private double startZ = 0;
    private int antiAfkTickCounter = 0;
    private int jumpTicks = 0;

    private boolean shulkerRecoveryAttempted = false;
    private int pickupTimeout = 0;

    private boolean isHandlingLava = false;
    private int lavaSafetyDelay = 0;
    private int lavaSafetyCounter = 0;
    private int lavaMoveTicks = 0;

    private boolean isHandlingWater = false;
    private int waterSafetyDelay = 0;
    private int waterSafetyCounter = 0;
    private int waterMoveTicks = 0;

    private boolean wasPausedForPortalMaker = false;
    private boolean wasHighlightPressed = false;

    private HideoutState hideoutState = HideoutState.IDLE;
    private int hideoutDelay = 0;
    private Direction hideoutDir = null;
    private BlockPos hideoutPos = null;

    private DeepDarkState deepDarkState = DeepDarkState.IDLE;
    private final Deque<BlockPos> safePosQueue = new ArrayDeque<>();
    private static final int MAX_QUEUE_SIZE = 10;
    private int safePosTimer = 0;
    private int retreatDelay = 0;
    private static final Random RANDOM = new Random();

    private CraftState craftState = CraftState.IDLE;
    private int craftDelay = 0;
    private int craftStep = 0;
    private BlockPos craftTablePos = null;
    private BlockPos craftContainerPos1 = null;
    private BlockPos craftContainerPos2 = null;

    private DepositState depositState = DepositState.IDLE;
    private int depositDelay = 0;
    private BlockPos echestPos = null;
    private BlockPos shulkerPos = null;
    private boolean spaceClearingStarted = false;
    private int spaceClearAttempts = 0;
    private int spaceClearMinWait = 0;

    public Baromine() {
        super("baromine", "Automated Baritone miner for targeted ores or blocks with heavy safety protocols.", Tim.CATEGORY);
        this.registerSettings(
            targetMode, targetOres, targetBlocks, includeDeepslate, targetStacks, autoCraft, craftOreList, craftBlockList,
            warningHealth, criticalHealth, disconnectOnPlayer, pauseInCombat, goldenHelmet, lavaAvoidance, waterAvoidance,
            dropExcessMode, avoidDeepDark, safeLogout, minYLevel, maxYLevel, radiusLimit, radiusBlocks,
            depositMode, depositStacks, swapSlot, toolEnchant, foodItems, minFoodCount, highlightContainerKey,
            autoMend, mendOres, maxMendDurability, minToolDurability,
            antiAfk, enableMaxRuntime, maxRuntimeHours, autoReconnect, reconnectHours,
            pingMode, warningSound, soundVolume
        );
    }

    // ─────────────────────────── Lifecycle ───────────────────────────
    @Override
    public void onEnable() {
        wasPausedForCombat = false;
        hasHealthWarned = false;
        playerLogoutPending = false;
        isFinalDeposit = false;
        isAutoMending = false;
        autoMendFailed = false;
        hideoutState = HideoutState.IDLE;
        deepDarkState = DeepDarkState.IDLE;
        depositState = DepositState.IDLE;
        craftState = CraftState.IDLE;

        isWaitingToReconnect = false;
        lastServer = mc.getCurrentServer();

        startTime = System.currentTimeMillis();
        antiAfkTickCounter = 0;

        safePosQueue.clear();
        wasPausedForPortalMaker = false;

        if (mc.player != null) {
            startX = mc.player.getX();
            startZ = mc.player.getZ();
        }

        updateBaritoneGoal();

        int targetItems = targetStacks.getValue() * 64;
        StringBuilder names = new StringBuilder();
        for (Block b : getTargetBlocks()) {
            if (names.length() > 0) names.append(", ");
            names.append(b.getName().getString());
        }
        sendPing("Baromine activated. Targeting " + names + " x" + targetItems + " (" + targetStacks.getValue() + " stacks).");
    }

    @Override
    public void onDisable() {
        isWaitingToReconnect = false;
        antiAfkTickCounter = 0;
        jumpTicks = 0;
        isHandlingLava = false;
        lavaSafetyDelay = 0;
        lavaMoveTicks = 0;
        isHandlingWater = false;
        waterSafetyDelay = 0;
        waterMoveTicks = 0;
        playerLogoutPending = false;
        isFinalDeposit = false;
        isAutoMending = false;
        hideoutState = HideoutState.IDLE;
        deepDarkState = DeepDarkState.IDLE;
        craftState = CraftState.IDLE;
        safePosQueue.clear();
        wasPausedForPortalMaker = false;
        if (mc.options != null) {
            mc.options.keyJump.setDown(false);
            mc.options.keyUp.setDown(false);
        }
        try {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing() && !isPortalMakerActive()) {
                baritone("stop");
            }
        } catch (Throwable ignored) {}
        baritone("set okIfWater false");
        sendPing("Baromine deactivated.");
    }

    // ─────────────────────────── Tick ───────────────────────────
    @Subscribe
    private void onTick(EventUpdate event) {
        if (enableMaxRuntime.getValue() && System.currentTimeMillis() - startTime >= maxRuntimeHours.getValue() * 3600000L) {
            sendPing("Maximum runtime of " + maxRuntimeHours.getValue() + " hours reached. Stopping module.");
            toggle();
            return;
        }

        if (autoReconnect.getValue() && lastServer != null && mc.level == null && !isWaitingToReconnect) {
            isWaitingToReconnect = true;
            reconnectTime = System.currentTimeMillis() + (reconnectHours.getValue() * 3600000L);
            sendPing("Disconnected. Waiting " + reconnectHours.getValue() + " hours before attempting to reconnect.");
        }

        if (isWaitingToReconnect) {
            if (mc.level != null) {
                isWaitingToReconnect = false;
            } else if (System.currentTimeMillis() >= reconnectTime && lastServer != null) {
                isWaitingToReconnect = false;
                sendPing("Reconnect delay finished. Attempting to reconnect...");
                ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(lastServer.ip), lastServer, false, null);
            }
            return;
        }

        if (mc.player == null || mc.level == null) return;

        // Yield Baritone control to PortalMaker if it is active
        if (isPortalMakerActive()) {
            if (!wasPausedForPortalMaker) {
                wasPausedForPortalMaker = true;
                baritone("stop");
            }
            return;
        }

        if (wasPausedForPortalMaker) {
            wasPausedForPortalMaker = false;
            if (depositState == DepositState.IDLE && craftState == CraftState.IDLE && hideoutState == HideoutState.IDLE && deepDarkState == DeepDarkState.IDLE && !isAutoMending) {
                sendPing("PortalMaker disabled. Resuming Baromine operations.");
                updateBaritoneGoal();
            }
        }

        boolean highlightNow = highlightContainerKey.getValue().isKeyDown();
        if (highlightNow && !wasHighlightPressed && craftState == CraftState.IDLE && depositState == DepositState.IDLE) {
            if (mc.hitResult instanceof BlockHitResult hit) {
                BlockState state = mc.level.getBlockState(hit.getBlockPos());
                if (state.getBlock() instanceof ShulkerBoxBlock) {
                    craftContainerPos1 = hit.getBlockPos();
                    craftContainerPos2 = null;
                    sendPing("Highlighted Shulker Box for material pulling.");
                } else if (state.getBlock() == Blocks.CHEST) {
                    craftContainerPos1 = hit.getBlockPos();
                    craftContainerPos2 = null;
                    if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                        craftContainerPos2 = hit.getBlockPos().relative(ChestBlock.getConnectedDirection(state));
                    }
                    sendPing("Highlighted Chest for material pulling.");
                }
            }
        }
        wasHighlightPressed = highlightNow;

        if (hideoutState != HideoutState.IDLE) { handleHideoutState(); return; }
        if (deepDarkState != DeepDarkState.IDLE) { handleDeepDarkRetreat(); return; }

        float health = mc.player.getHealth();
        if (health <= criticalHealth.getValue()) {
            baritone("stop");
            disconnectSafely("CRITICAL HEALTH");
            return;
        }

        if (isAutoMending) { handleAutoMend(); return; }

        if (autoMend.getValue() && !autoMendFailed && depositState == DepositState.IDLE && craftState == CraftState.IDLE && hasToolsBelowDurability(minToolDurability.getValue())) {
            startAutoMend();
            return;
        }

        if (disconnectOnPlayer.getValue()) {
            for (Player player : mc.level.players()) {
                if (player != mc.player) { playerLogoutPending = true; break; }
            }
        }

        if (playerLogoutPending) {
            if (depositState == DepositState.IDLE && craftState == CraftState.IDLE) {
                disconnectSafely("Player detected in render distance!");
                playerLogoutPending = false;
                return;
            }
        }

        if (avoidDeepDark.getValue() && depositState == DepositState.IDLE && craftState == CraftState.IDLE) {
            safePosTimer++;
            if (safePosTimer >= 40) {
                safePosTimer = 0;
                Optional<ResourceKey<Biome>> biomeKey = mc.level.getBiome(mc.player.blockPosition()).unwrapKey();
                boolean inDeepDark = biomeKey.isPresent() && biomeKey.get().equals(Biomes.DEEP_DARK);
                if (inDeepDark) { triggerDeepDarkRetreat(); return; }
                safePosQueue.addLast(mc.player.blockPosition());
                while (safePosQueue.size() > MAX_QUEUE_SIZE) safePosQueue.removeFirst();
            }
        }

        if (craftState != CraftState.IDLE) { handleCraftState(); return; }

        if (autoCraft.getValue() == CraftMode.Ores && depositState == DepositState.IDLE && hasCraftableOre()) {
            craftState = CraftState.FINDING_TABLE;
            baritone("stop");
            return;
        }
        if (autoCraft.getValue() == CraftMode.Blocks && depositState == DepositState.IDLE && hasCraftableBlock()) {
            craftState = CraftState.FINDING_TABLE;
            baritone("stop");
            return;
        }

        if (depositState != DepositState.IDLE) { handleDepositState(); return; }

        if (depositMode.getValue() == DepositMode.Disabled && mc.player.getInventory().getFreeSlot() == -1) {
            sendPing("Inventory is full and Auto Deposit is disabled. Stopping module to prevent wasted mining.");
            stopBaritoneSafely("Inventory full");
            toggle();
            return;
        }

        int requiredItems = targetStacks.getValue() * 64;
        if (getTotalAvailableTargetItems() >= requiredItems) {
            if (depositMode.getValue() != DepositMode.Disabled && getCurrentTargetCount() > 0) {
                isFinalDeposit = true;
                sendPing("Target stacks reached! Initiating final deposit...");
                depositState = DepositState.PAUSING_BARITONE;
                depositDelay = 5;
                return;
            } else {
                sendPing("Target stacks reached! Total items: " + getTotalAvailableTargetItems());
                baritone("stop");
                if (safeLogout.getValue() && isInNetherOrOverworld()) startHideout();
                else toggle();
                return;
            }
        }

        if (depositMode.getValue() != DepositMode.Disabled) {
            int requiredDepositItems = depositStacks.getValue() * 64;
            if (getCurrentTargetCount() >= requiredDepositItems) {
                depositState = DepositState.PAUSING_BARITONE;
                depositDelay = 5;
                return;
            }
        }

        int playerY = mc.player.blockPosition().getY();
        if (playerY < minYLevel.getValue()) {
            stopBaritoneSafely("Went below minimum Y-level (" + minYLevel.getValue() + ")!");
            toggle();
            return;
        }
        if (playerY > maxYLevel.getValue()) {
            stopBaritoneSafely("Went above maximum Y-level (" + maxYLevel.getValue() + ")!");
            toggle();
            return;
        }

        if (radiusLimit.getValue()) {
            double dist = Math.sqrt(Math.pow(mc.player.getX() - startX, 2) + Math.pow(mc.player.getZ() - startZ, 2));
            if (dist > radiusBlocks.getValue()) {
                stopBaritoneSafely("Went outside allowed radius limit (" + radiusBlocks.getValue() + " blocks)!");
                toggle();
                return;
            }
        }

        if (health <= warningHealth.getValue()) {
            if (!hasHealthWarned) { sendPing("Warning: Health is low!"); hasHealthWarned = true; }
        } else {
            hasHealthWarned = false;
        }

        if (lavaAvoidance.getValue()) {
            if (isHandlingLava) {
                if (lavaMoveTicks > 0) { lavaMoveTicks--; mc.options.keyUp.setDown(true); return; }
                mc.options.keyUp.setDown(false);
                if (lavaSafetyDelay > 0) { lavaSafetyDelay--; return; }
                if (isImmediateLavaDanger()) {
                    placeSafetyBlock(false);
                    lavaSafetyDelay = 5;
                    lavaSafetyCounter++;
                    if (lavaSafetyCounter > 40) { disconnectSafely("Lava safety failed!"); return; }
                    return;
                } else {
                    isHandlingLava = false;
                    mc.options.keyJump.setDown(false);
                    mc.options.keyUp.setDown(false);
                    sendPing("Lava avoided. Resuming Baromine.");
                    updateBaritoneGoal();
                }
                return;
            } else if (isImmediateLavaDanger()) {
                isHandlingLava = true;
                lavaSafetyCounter = 0;
                stopBaritoneSafely("Lava detected! Activating safety protocols.");
                lavaSafetyDelay = 5;
                return;
            }
        }

        if (waterAvoidance.getValue()) {
            if (isHandlingWater) {
                if (waterMoveTicks > 0) { waterMoveTicks--; mc.options.keyUp.setDown(true); return; }
                mc.options.keyUp.setDown(false);
                if (waterSafetyDelay > 0) { waterSafetyDelay--; return; }
                if (isImmediateWaterDanger()) {
                    placeSafetyBlock(true);
                    waterSafetyDelay = 5;
                    waterSafetyCounter++;
                    if (waterSafetyCounter > 40) { stopBaritoneSafely("Water safety failed!"); return; }
                    return;
                } else {
                    isHandlingWater = false;
                    mc.options.keyJump.setDown(false);
                    mc.options.keyUp.setDown(false);
                    sendPing("Water avoided. Resuming Baromine.");
                    updateBaritoneGoal();
                }
                return;
            } else if (isImmediateWaterDanger()) {
                isHandlingWater = true;
                waterSafetyCounter = 0;
                stopBaritoneSafely("Water detected! Activating safety protocols.");
                waterSafetyDelay = 5;
                return;
            }
        }

        if (goldenHelmet.getValue() && mc.player.getItemBySlot(EquipmentSlot.HEAD).getItem() != Items.GOLDEN_HELMET) {
            stopBaritoneSafely("Golden Helmet is not equipped!");
            return;
        }

        if (pauseInCombat.getValue()) {
            boolean inDanger = false;
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof Mob mob && mob.isAggressive() && mc.player.distanceTo(mob) < 6.0) { inDanger = true; break; }
            }
            if (inDanger && !wasPausedForCombat) {
                stopBaritoneSafely("Hostile entity detected! Pausing.");
                equipSword();
                wasPausedForCombat = true;
                return;
            } else if (!inDanger && wasPausedForCombat) {
                sendPing("Combat clear. Resuming Baromine.");
                wasPausedForCombat = false;
                updateBaritoneGoal();
            }
        }

        if (antiAfk.getValue()) {
            antiAfkTickCounter++;
            if (antiAfkTickCounter >= 600) {
                antiAfkTickCounter = 0;
                if (isBaritoneIdle()) { jumpTicks = 10; mc.player.swing(InteractionHand.MAIN_HAND); }
            }
            if (jumpTicks > 0) { mc.options.keyJump.setDown(true); jumpTicks--; }
            else mc.options.keyJump.setDown(false);
        }

        handleExcessDrop();
    }

    private void handleExcessDrop() {
        if (dropExcessMode.getValue() == ExcessDropMode.Disabled) return;

        Item targetItem = null;
        if (dropExcessMode.getValue() == ExcessDropMode.Cobblestone) targetItem = Items.COBBLESTONE;
        else if (dropExcessMode.getValue() == ExcessDropMode.Netherrack) targetItem = Items.NETHERRACK;
        else if (dropExcessMode.getValue() == ExcessDropMode.CobbledDeepslate) targetItem = Items.COBBLED_DEEPSLATE;
        if (targetItem == null) return;

        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() == targetItem) total += stack.getCount();
        }
        if (total <= 64) return;

        int[] order = new int[36];
        for (int i = 9; i < 36; i++) order[i - 9] = i;
        for (int i = 0; i < 9; i++) order[27 + i] = i;

        for (int i = 0; i < 36; i++) {
            int slot = order[i];
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.getItem() == targetItem) {
                int count = stack.getCount();
                if (total - count >= 64) {
                    int containerSlot = slot < 9 ? slot + 36 : slot;
                    mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, containerSlot, 1, ClickType.THROW, mc.player);
                    return;
                }
            }
        }
    }

    // ─────────────────────────── Deep Dark retreat ───────────────────────────
    private void triggerDeepDarkRetreat() {
        sendPing("Entered Deep Dark biome! Initiating tactical retreat.");
        deepDarkState = DeepDarkState.RETREATING;
        retreatDelay = 10;
        baritone("stop");
        equipSword();
        if (!safePosQueue.isEmpty()) {
            BlockPos target = safePosQueue.peekFirst();
            baritone("goto " + target.getX() + " " + target.getY() + " " + target.getZ());
        } else {
            deepDarkState = DeepDarkState.ASCENDING;
            int x = mc.player.blockPosition().getX();
            int z = mc.player.blockPosition().getZ();
            sendPing("Retreated to safe spot. Ascending to Y=0.");
            baritone("goto " + x + " 0 " + z);
        }
    }

    private void handleDeepDarkRetreat() {
        if (retreatDelay > 0) { retreatDelay--; return; }

        if (deepDarkState == DeepDarkState.RETREATING) {
            if (isBaritoneIdle()) {
                safePosQueue.clear();
                deepDarkState = DeepDarkState.ASCENDING;
                retreatDelay = 10;
                int x = mc.player.blockPosition().getX();
                int z = mc.player.blockPosition().getZ();
                sendPing("Retreated to safe spot. Ascending to Y=0.");
                baritone("goto " + x + " 0 " + z);
            }
        } else if (deepDarkState == DeepDarkState.ASCENDING) {
            if (isBaritoneIdle()) {
                deepDarkState = DeepDarkState.RUNNING_AWAY;
                retreatDelay = 10;
                sendPing("Reached Y=0. Relocating 200 blocks away.");
                startRunningAway();
            }
        } else if (deepDarkState == DeepDarkState.RUNNING_AWAY) {
            if (isBaritoneIdle()) {
                sendPing("Evasion complete. Resuming mining operations.");
                deepDarkState = DeepDarkState.IDLE;
                safePosQueue.clear();
                updateBaritoneGoal();
            }
        }
    }

    private void startRunningAway() {
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        Direction dir = dirs[RANDOM.nextInt(dirs.length)];
        int dist = 200;
        int targetX = mc.player.blockPosition().getX() + (dir.getStepX() * dist);
        int targetZ = mc.player.blockPosition().getZ() + (dir.getStepZ() * dist);
        int targetY = 64;
        baritone("goto " + targetX + " " + targetY + " " + targetZ);
        deepDarkState = DeepDarkState.RUNNING_AWAY;
    }

    // ─────────────────────────── Auto Craft ───────────────────────────
    private void handleCraftState() {
        if (craftDelay > 0) { craftDelay--; return; }

        switch (craftState) {
            case GATHERING_WOOD:
                if (hasLogs()) {
                    baritone("stop");
                    craftState = CraftState.FINDING_TABLE;
                    craftDelay = 5;
                } else if (isBaritoneIdle()) {
                    sendPing("Searching for wood to craft a table...");
                    baritone("mine minecraft:oak_log minecraft:spruce_log minecraft:birch_log minecraft:jungle_log minecraft:acacia_log minecraft:dark_oak_log minecraft:mangrove_log minecraft:cherry_log minecraft:crimson_stem minecraft:warped_stem");
                    craftDelay = 20;
                }
                break;

            case FINDING_TABLE: {
                if (findInvSlot(Items.CRAFTING_TABLE) != -1) {
                    craftState = CraftState.PLACING_TABLE;
                    craftDelay = 5;
                    return;
                }
                int plankCount = 0;
                for (int i = 0; i < 36; i++) {
                    ItemStack s = mc.player.getInventory().getItem(i);
                    if (isPlank(s.getItem())) plankCount += s.getCount();
                }
                if (plankCount >= 4) {
                    if (craftStep == 0) {
                        Item plank = getAnyPlank();
                        if (plank == null) { abortCraft("No planks found!"); return; }
                        int slot = findInvSlot(plank);
                        int invSlot = slot < 9 ? slot + 36 : slot;
                        mc.gameMode.handleInventoryMouseClick(0, invSlot, 0, ClickType.PICKUP, mc.player);
                        craftStep++;
                        craftDelay = 2;
                    } else if (craftStep <= 4) {
                        mc.gameMode.handleInventoryMouseClick(0, craftStep, 1, ClickType.PICKUP, mc.player);
                        craftStep++;
                        craftDelay = 2;
                    } else if (craftStep == 5) {
                        if (!mc.player.containerMenu.getCarried().isEmpty()) {
                            int emptySlot = mc.player.getInventory().getFreeSlot();
                            if (emptySlot != -1) {
                                int slotId = emptySlot < 9 ? emptySlot + 36 : emptySlot;
                                mc.gameMode.handleInventoryMouseClick(0, slotId, 0, ClickType.PICKUP, mc.player);
                            }
                        }
                        craftStep++;
                        craftDelay = 2;
                    } else if (craftStep == 6) {
                        mc.gameMode.handleInventoryMouseClick(0, 0, 0, ClickType.QUICK_MOVE, mc.player);
                        craftStep = 0;
                        craftDelay = 5;
                    }
                } else if (hasLogs()) {
                    Item log = getAnyLog();
                    if (craftStep == 0) {
                        int slot = findInvSlot(log);
                        int invSlot = slot < 9 ? slot + 36 : slot;
                        mc.gameMode.handleInventoryMouseClick(0, invSlot, 0, ClickType.PICKUP, mc.player);
                        craftStep++;
                        craftDelay = 2;
                    } else if (craftStep == 1) {
                        mc.gameMode.handleInventoryMouseClick(0, 1, 1, ClickType.PICKUP, mc.player);
                        craftStep++;
                        craftDelay = 2;
                    } else if (craftStep == 2) {
                        if (!mc.player.containerMenu.getCarried().isEmpty()) {
                            int emptySlot = mc.player.getInventory().getFreeSlot();
                            if (emptySlot != -1) {
                                int slotId = emptySlot < 9 ? emptySlot + 36 : emptySlot;
                                mc.gameMode.handleInventoryMouseClick(0, slotId, 0, ClickType.PICKUP, mc.player);
                            }
                        }
                        craftStep++;
                        craftDelay = 2;
                    } else if (craftStep == 3) {
                        mc.gameMode.handleInventoryMouseClick(0, 0, 0, ClickType.QUICK_MOVE, mc.player);
                        craftStep = 0;
                        craftDelay = 5;
                    }
                } else {
                    craftState = CraftState.GATHERING_WOOD;
                }
                break;
            }

            case PLACING_TABLE: {
                if (isWaterNearby(mc.player.blockPosition(), 2) || isImmediateLavaDanger()) {
                    if (!isBaritoneIdle()) return;
                    int dx = RANDOM.nextInt(16) + 10;
                    int dz = RANDOM.nextInt(16) + 10;
                    if (RANDOM.nextBoolean()) dx *= -1;
                    if (RANDOM.nextBoolean()) dz *= -1;
                    int tx = mc.player.blockPosition().getX() + dx;
                    int tz = mc.player.blockPosition().getZ() + dz;
                    int ty = mc.player.blockPosition().getY();
                    baritone("goto " + tx + " " + ty + " " + tz);
                    craftDelay = 10;
                    return;
                }
                int tableSlot = findHotbarSlot(Items.CRAFTING_TABLE);
                if (tableSlot == -1) { abortCraft("Lost Crafting Table!"); return; }
                craftTablePos = findAndPlace(tableSlot);
                if (craftTablePos == null) { abortCraft("Failed to place Crafting Table!"); return; }
                craftState = CraftState.OPENING_TABLE;
                craftDelay = 5;
                break;
            }

            case OPENING_TABLE: {
                if (mc.level.getBlockState(craftTablePos).getBlock() != Blocks.CRAFTING_TABLE) {
                    abortCraft("Failed to place Crafting Table.");
                    return;
                }
                BlockHitResult craftHit = new BlockHitResult(Vec3.atCenterOf(craftTablePos), Direction.UP, craftTablePos, false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, craftHit);
                mc.player.swing(InteractionHand.MAIN_HAND);
                craftState = CraftState.CRAFTING;
                craftDelay = 5;
                break;
            }

            case PULLING_MATERIALS: {
                if (craftContainerPos1 == null) { abortCraft("No container highlighted for materials!"); return; }
                if (!(mc.player.containerMenu instanceof ChestMenu) && !(mc.player.containerMenu instanceof ShulkerBoxMenu)) {
                    BlockHitResult containerHit = new BlockHitResult(Vec3.atCenterOf(craftContainerPos1), Direction.UP, craftContainerPos1, false);
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, containerHit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    craftDelay = 5;
                    return;
                }
                ChestMenu containerHandler = (ChestMenu) mc.player.containerMenu;
                boolean needMaterials = false;
                Item rawItemNeeded = null;
                if (autoCraft.getValue() == CraftMode.Ores) {
                    CraftRecipe recipe = getCraftableOreRecipe();
                    if (recipe != null && countItem(recipe.raw) < recipe.count) { needMaterials = true; rawItemNeeded = recipe.raw; }
                } else if (autoCraft.getValue() == CraftMode.Blocks) {
                    CraftRecipe recipe = getCraftableBlockRecipe();
                    if (recipe != null && countItem(recipe.raw) < recipe.count) { needMaterials = true; rawItemNeeded = recipe.raw; }
                }
                if (!needMaterials) {
                    mc.player.closeContainer();
                    craftState = CraftState.OPENING_TABLE;
                    craftDelay = 5;
                    return;
                }
                boolean pulled = false;
                for (int i = 0; i < 27; i++) {
                    ItemStack stack = containerHandler.getSlot(i).getItem();
                    if (stack.getItem() == rawItemNeeded) {
                        mc.gameMode.handleInventoryMouseClick(containerHandler.containerId, i, 0, ClickType.QUICK_MOVE, mc.player);
                        pulled = true;
                        break;
                    }
                }
                if (!pulled) { abortCraft("Ran out of " + itemName(rawItemNeeded) + " in the container!"); return; }
                craftDelay = 2;
                break;
            }

            case CRAFTING: {
                if (!(mc.player.containerMenu instanceof CraftingMenu)) { abortCraft("Failed to open Crafting Table."); return; }
                CraftingMenu craftHandler = (CraftingMenu) mc.player.containerMenu;

                Item rawItem = null;
                int requiredAmount = 0;
                int[] gridSlots = null;
                if (autoCraft.getValue() == CraftMode.Ores) {
                    CraftRecipe recipe = getCraftableOreRecipe();
                    if (recipe == null) { mc.player.closeContainer(); craftState = CraftState.CLOSING_TABLE; craftDelay = 5; return; }
                    rawItem = recipe.raw; requiredAmount = recipe.count; gridSlots = recipe.gridSlots;
                } else if (autoCraft.getValue() == CraftMode.Blocks) {
                    CraftRecipe recipe = getCraftableBlockRecipe();
                    if (recipe == null) { mc.player.closeContainer(); craftState = CraftState.CLOSING_TABLE; craftDelay = 5; return; }
                    rawItem = recipe.raw; requiredAmount = recipe.count; gridSlots = recipe.gridSlots;
                }
                if (rawItem == null) { mc.player.closeContainer(); craftState = CraftState.CLOSING_TABLE; craftDelay = 5; return; }

                if (craftStep == 0) {
                    int slot = findInvSlot(rawItem);
                    int slotId = slot < 9 ? slot + 37 : slot + 10;
                    mc.gameMode.handleInventoryMouseClick(craftHandler.containerId, slotId, 0, ClickType.PICKUP, mc.player);
                    craftStep++;
                    craftDelay = 2;
                } else if (craftStep <= requiredAmount) {
                    mc.gameMode.handleInventoryMouseClick(craftHandler.containerId, gridSlots[craftStep - 1], 1, ClickType.PICKUP, mc.player);
                    craftStep++;
                    craftDelay = 2;
                } else if (craftStep == requiredAmount + 1) {
                    if (!mc.player.containerMenu.getCarried().isEmpty()) {
                        int slot = findInvSlot(rawItem);
                        if (slot != -1) {
                            int slotId = slot < 9 ? slot + 37 : slot + 10;
                            mc.gameMode.handleInventoryMouseClick(craftHandler.containerId, slotId, 0, ClickType.PICKUP, mc.player);
                        } else {
                            int emptySlot = mc.player.getInventory().getFreeSlot();
                            if (emptySlot != -1) {
                                int slotId = emptySlot < 9 ? emptySlot + 37 : emptySlot + 10;
                                mc.gameMode.handleInventoryMouseClick(craftHandler.containerId, slotId, 0, ClickType.PICKUP, mc.player);
                            }
                        }
                    }
                    craftStep++;
                    craftDelay = 2;
                } else if (craftStep == requiredAmount + 2) {
                    mc.gameMode.handleInventoryMouseClick(craftHandler.containerId, 0, 0, ClickType.QUICK_MOVE, mc.player);
                    craftStep = 0;
                    craftDelay = 5;
                }
                break;
            }

            case CLOSING_TABLE:
                mc.player.closeContainer();
                craftState = CraftState.BREAKING_TABLE;
                craftDelay = 5;
                break;

            case BREAKING_TABLE:
                if (!equipEnchantedPickaxe()) { abortCraft("No " + toolEnchant.getValue() + " Pickaxe found!"); return; }
                if (mc.level.getBlockState(craftTablePos).getBlock() == Blocks.CRAFTING_TABLE) {
                    breakBlock(craftTablePos);
                    craftDelay = 1;
                    return;
                }
                pickupTimeout = 0;
                craftState = CraftState.PICKING_UP_TABLE;
                craftDelay = 5;
                break;

            case PICKING_UP_TABLE: {
                if (findInvSlot(Items.CRAFTING_TABLE) != -1) {
                    mc.options.keyUp.setDown(false);
                    craftState = CraftState.RESUMING;
                    craftDelay = 5;
                    return;
                }
                ItemEntity targetTable = null;
                double closestTableDist = 6.0;
                for (Entity entity : mc.level.entitiesForRendering()) {
                    if (entity instanceof ItemEntity itemEntity && itemEntity.getItem().getItem() == Items.CRAFTING_TABLE) {
                        double dist = mc.player.distanceTo(entity);
                        if (dist < closestTableDist) { closestTableDist = dist; targetTable = itemEntity; }
                    }
                }
                if (targetTable != null) {
                    Vec3 itemPos = targetTable.position();
                    double diffX = itemPos.x - mc.player.getX();
                    double diffZ = itemPos.z - mc.player.getZ();
                    float yaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90;
                    mc.player.setYRot(yaw);
                    mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, mc.player.getXRot(), mc.player.onGround(), false));
                    mc.options.keyUp.setDown(true);
                    craftDelay = 1;
                } else {
                    pickupTimeout++;
                    if (pickupTimeout > 60) {
                        mc.options.keyUp.setDown(false);
                        abortCraft("Lost Crafting Table after mining it!");
                    } else {
                        craftDelay = 1;
                    }
                }
                break;
            }

            case RESUMING:
                ensureToolsInHotbar();
                craftState = CraftState.IDLE;
                craftStep = 0;
                sendPing("Successfully crafted items. Resuming Baromine.");
                updateBaritoneGoal();
                break;

            default:
                break;
        }
    }

    private boolean isPlank(Item item) {
        return item == Items.OAK_PLANKS || item == Items.SPRUCE_PLANKS || item == Items.BIRCH_PLANKS ||
               item == Items.JUNGLE_PLANKS || item == Items.ACACIA_PLANKS || item == Items.DARK_OAK_PLANKS ||
               item == Items.MANGROVE_PLANKS || item == Items.CHERRY_PLANKS ||
               item == Items.CRIMSON_PLANKS || item == Items.WARPED_PLANKS;
    }

    private Item getAnyPlank() {
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (isPlank(s.getItem())) return s.getItem();
        }
        return null;
    }

    private boolean isLog(Item item) {
        return item == Items.OAK_LOG || item == Items.SPRUCE_LOG || item == Items.BIRCH_LOG ||
               item == Items.JUNGLE_LOG || item == Items.ACACIA_LOG || item == Items.DARK_OAK_LOG ||
               item == Items.MANGROVE_LOG || item == Items.CHERRY_LOG ||
               item == Items.CRIMSON_STEM || item == Items.WARPED_STEM;
    }

    private Item getAnyLog() {
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (isLog(s.getItem())) return s.getItem();
        }
        return null;
    }

    private boolean hasLogs() {
        for (int i = 0; i < 36; i++) {
            if (isLog(mc.player.getInventory().getItem(i).getItem())) return true;
        }
        return false;
    }

    private int countItem(Item item) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    private record CraftRecipe(Item raw, int count, int[] gridSlots) {}

    private CraftRecipe getOreRecipe(Block block) {
        int[] grid9 = new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9};
        int[] grid4 = new int[]{1, 2, 4, 5};
        if (block == Blocks.IRON_BLOCK) return new CraftRecipe(Items.RAW_IRON, 9, grid9);
        if (block == Blocks.GOLD_BLOCK) return new CraftRecipe(Items.RAW_GOLD, 9, grid9);
        if (block == Blocks.COPPER_BLOCK) return new CraftRecipe(Items.RAW_COPPER, 9, grid9);
        if (block == Blocks.DIAMOND_BLOCK) return new CraftRecipe(Items.DIAMOND, 9, grid9);
        if (block == Blocks.EMERALD_BLOCK) return new CraftRecipe(Items.EMERALD, 9, grid9);
        if (block == Blocks.COAL_BLOCK) return new CraftRecipe(Items.COAL, 9, grid9);
        if (block == Blocks.REDSTONE_BLOCK) return new CraftRecipe(Items.REDSTONE, 9, grid9);
        if (block == Blocks.LAPIS_BLOCK) return new CraftRecipe(Items.LAPIS_LAZULI, 9, grid9);
        if (block == Blocks.QUARTZ_BLOCK) return new CraftRecipe(Items.QUARTZ, 4, grid4);
        return null;
    }

    private CraftRecipe getBlockRecipe(Block block) {
        int[] grid4 = new int[]{1, 2, 4, 5};
        int[] grid9 = new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9};
        int[] grid3Bottom = new int[]{7, 8, 9};
        int[] grid6Top = new int[]{1, 2, 3, 4, 5, 6};
        int[] grid6Stairs = new int[]{1, 4, 5, 7, 8, 9};

        if (block == Blocks.STONE_BRICKS) return new CraftRecipe(Items.STONE, 4, grid4);
        if (block == Blocks.POLISHED_GRANITE) return new CraftRecipe(Items.GRANITE, 4, grid4);
        if (block == Blocks.POLISHED_DIORITE) return new CraftRecipe(Items.DIORITE, 4, grid4);
        if (block == Blocks.POLISHED_ANDESITE) return new CraftRecipe(Items.ANDESITE, 4, grid4);
        if (block == Blocks.POLISHED_DEEPSLATE) return new CraftRecipe(Items.DEEPSLATE, 4, grid4);
        if (block == Blocks.DEEPSLATE_BRICKS) return new CraftRecipe(Items.POLISHED_DEEPSLATE, 4, grid4);
        if (block == Blocks.DEEPSLATE_TILES) return new CraftRecipe(Items.DEEPSLATE_BRICKS, 4, grid4);
        if (block == Blocks.END_STONE_BRICKS) return new CraftRecipe(Items.END_STONE, 4, grid4);
        if (block == Blocks.MUD_BRICKS) return new CraftRecipe(Items.PACKED_MUD, 4, grid4);
        if (block == Blocks.NETHER_BRICKS) return new CraftRecipe(Items.NETHER_BRICK, 4, grid4);
        if (block == Blocks.QUARTZ_BRICKS) return new CraftRecipe(Items.QUARTZ_BLOCK, 4, grid4);
        if (block == Blocks.POLISHED_BASALT) return new CraftRecipe(Items.BASALT, 4, grid4);
        if (block == Blocks.POLISHED_BLACKSTONE) return new CraftRecipe(Items.BLACKSTONE, 4, grid4);
        if (block == Blocks.POLISHED_BLACKSTONE_BRICKS) return new CraftRecipe(Items.BLACKSTONE, 4, grid4);
        if (block == Blocks.SANDSTONE) return new CraftRecipe(Items.SAND, 4, grid4);
        if (block == Blocks.RED_SANDSTONE) return new CraftRecipe(Items.RED_SAND, 4, grid4);
        if (block == Blocks.CUT_SANDSTONE) return new CraftRecipe(Items.SANDSTONE, 4, grid4);
        if (block == Blocks.CUT_RED_SANDSTONE) return new CraftRecipe(Items.RED_SANDSTONE, 4, grid4);
        if (block == Blocks.BRICKS) return new CraftRecipe(Items.CLAY_BALL, 4, grid4);
        if (block == Blocks.SNOW_BLOCK) return new CraftRecipe(Items.SNOWBALL, 4, grid4);
        if (block == Blocks.GLOWSTONE) return new CraftRecipe(Items.GLOWSTONE_DUST, 4, grid4);
        if (block == Blocks.MELON) return new CraftRecipe(Items.MELON_SLICE, 9, grid9);
        if (block == Blocks.DRIED_KELP_BLOCK) return new CraftRecipe(Items.DRIED_KELP, 9, grid9);
        if (block == Blocks.BAMBOO_BLOCK) return new CraftRecipe(Items.BAMBOO, 9, grid9);
        if (block == Blocks.HAY_BLOCK) return new CraftRecipe(Items.WHEAT, 9, grid9);
        if (block == Blocks.BONE_BLOCK) return new CraftRecipe(Items.BONE_MEAL, 9, grid9);
        if (block == Blocks.PACKED_ICE) return new CraftRecipe(Items.ICE, 9, grid9);
        if (block == Blocks.BLUE_ICE) return new CraftRecipe(Items.PACKED_ICE, 9, grid9);
        if (block == Blocks.IRON_BARS) return new CraftRecipe(Items.IRON_INGOT, 6, grid6Top);
        if (block == Blocks.GLASS_PANE) return new CraftRecipe(Items.GLASS, 6, grid6Top);

        if (block == Blocks.STONE_SLAB) return new CraftRecipe(Items.STONE, 3, grid3Bottom);
        if (block == Blocks.COBBLESTONE_SLAB) return new CraftRecipe(Items.COBBLESTONE, 3, grid3Bottom);
        if (block == Blocks.STONE_BRICK_SLAB) return new CraftRecipe(Items.STONE_BRICKS, 3, grid3Bottom);
        if (block == Blocks.SANDSTONE_SLAB) return new CraftRecipe(Items.SANDSTONE, 3, grid3Bottom);
        if (block == Blocks.RED_SANDSTONE_SLAB) return new CraftRecipe(Items.RED_SANDSTONE, 3, grid3Bottom);
        if (block == Blocks.NETHER_BRICK_SLAB) return new CraftRecipe(Items.NETHER_BRICKS, 3, grid3Bottom);
        if (block == Blocks.QUARTZ_SLAB) return new CraftRecipe(Items.QUARTZ_BLOCK, 3, grid3Bottom);
        if (block == Blocks.END_STONE_BRICK_SLAB) return new CraftRecipe(Items.END_STONE_BRICKS, 3, grid3Bottom);
        if (block == Blocks.POLISHED_GRANITE_SLAB) return new CraftRecipe(Items.POLISHED_GRANITE, 3, grid3Bottom);
        if (block == Blocks.POLISHED_DIORITE_SLAB) return new CraftRecipe(Items.POLISHED_DIORITE, 3, grid3Bottom);
        if (block == Blocks.POLISHED_ANDESITE_SLAB) return new CraftRecipe(Items.POLISHED_ANDESITE, 3, grid3Bottom);
        if (block == Blocks.POLISHED_DEEPSLATE_SLAB) return new CraftRecipe(Items.POLISHED_DEEPSLATE, 3, grid3Bottom);
        if (block == Blocks.DEEPSLATE_BRICK_SLAB) return new CraftRecipe(Items.DEEPSLATE_BRICKS, 3, grid3Bottom);
        if (block == Blocks.DEEPSLATE_TILE_SLAB) return new CraftRecipe(Items.DEEPSLATE_TILES, 3, grid3Bottom);
        if (block == Blocks.COBBLED_DEEPSLATE_SLAB) return new CraftRecipe(Items.COBBLED_DEEPSLATE, 3, grid3Bottom);
        if (block == Blocks.BLACKSTONE_SLAB) return new CraftRecipe(Items.BLACKSTONE, 3, grid3Bottom);
        if (block == Blocks.POLISHED_BLACKSTONE_SLAB) return new CraftRecipe(Items.POLISHED_BLACKSTONE, 3, grid3Bottom);
        if (block == Blocks.POLISHED_BLACKSTONE_BRICK_SLAB) return new CraftRecipe(Items.POLISHED_BLACKSTONE_BRICKS, 3, grid3Bottom);
        if (block == Blocks.MUD_BRICK_SLAB) return new CraftRecipe(Items.MUD_BRICKS, 3, grid3Bottom);
        if (block == Blocks.OAK_SLAB) return new CraftRecipe(Items.OAK_PLANKS, 3, grid3Bottom);
        if (block == Blocks.SPRUCE_SLAB) return new CraftRecipe(Items.SPRUCE_PLANKS, 3, grid3Bottom);
        if (block == Blocks.BIRCH_SLAB) return new CraftRecipe(Items.BIRCH_PLANKS, 3, grid3Bottom);
        if (block == Blocks.JUNGLE_SLAB) return new CraftRecipe(Items.JUNGLE_PLANKS, 3, grid3Bottom);
        if (block == Blocks.ACACIA_SLAB) return new CraftRecipe(Items.ACACIA_PLANKS, 3, grid3Bottom);
        if (block == Blocks.DARK_OAK_SLAB) return new CraftRecipe(Items.DARK_OAK_PLANKS, 3, grid3Bottom);
        if (block == Blocks.MANGROVE_SLAB) return new CraftRecipe(Items.MANGROVE_PLANKS, 3, grid3Bottom);
        if (block == Blocks.CHERRY_SLAB) return new CraftRecipe(Items.CHERRY_PLANKS, 3, grid3Bottom);

        if (block == Blocks.STONE_STAIRS) return new CraftRecipe(Items.STONE, 6, grid6Stairs);
        if (block == Blocks.COBBLESTONE_STAIRS) return new CraftRecipe(Items.COBBLESTONE, 6, grid6Stairs);
        if (block == Blocks.STONE_BRICK_STAIRS) return new CraftRecipe(Items.STONE_BRICKS, 6, grid6Stairs);
        if (block == Blocks.SANDSTONE_STAIRS) return new CraftRecipe(Items.SANDSTONE, 6, grid6Stairs);
        if (block == Blocks.RED_SANDSTONE_STAIRS) return new CraftRecipe(Items.RED_SANDSTONE, 6, grid6Stairs);
        if (block == Blocks.NETHER_BRICK_STAIRS) return new CraftRecipe(Items.NETHER_BRICKS, 6, grid6Stairs);
        if (block == Blocks.QUARTZ_STAIRS) return new CraftRecipe(Items.QUARTZ_BLOCK, 6, grid6Stairs);
        if (block == Blocks.END_STONE_BRICK_STAIRS) return new CraftRecipe(Items.END_STONE_BRICKS, 6, grid6Stairs);
        if (block == Blocks.GRANITE_STAIRS) return new CraftRecipe(Items.GRANITE, 6, grid6Stairs);
        if (block == Blocks.DIORITE_STAIRS) return new CraftRecipe(Items.DIORITE, 6, grid6Stairs);
        if (block == Blocks.ANDESITE_STAIRS) return new CraftRecipe(Items.ANDESITE, 6, grid6Stairs);
        if (block == Blocks.POLISHED_GRANITE_STAIRS) return new CraftRecipe(Items.POLISHED_GRANITE, 6, grid6Stairs);
        if (block == Blocks.POLISHED_DIORITE_STAIRS) return new CraftRecipe(Items.POLISHED_DIORITE, 6, grid6Stairs);
        if (block == Blocks.POLISHED_ANDESITE_STAIRS) return new CraftRecipe(Items.POLISHED_ANDESITE, 6, grid6Stairs);
        if (block == Blocks.DEEPSLATE_BRICK_STAIRS) return new CraftRecipe(Items.DEEPSLATE_BRICKS, 6, grid6Stairs);
        if (block == Blocks.DEEPSLATE_TILE_STAIRS) return new CraftRecipe(Items.DEEPSLATE_TILES, 6, grid6Stairs);
        if (block == Blocks.COBBLED_DEEPSLATE_STAIRS) return new CraftRecipe(Items.COBBLED_DEEPSLATE, 6, grid6Stairs);
        if (block == Blocks.POLISHED_DEEPSLATE_STAIRS) return new CraftRecipe(Items.POLISHED_DEEPSLATE, 6, grid6Stairs);
        if (block == Blocks.BLACKSTONE_STAIRS) return new CraftRecipe(Items.BLACKSTONE, 6, grid6Stairs);
        if (block == Blocks.POLISHED_BLACKSTONE_STAIRS) return new CraftRecipe(Items.POLISHED_BLACKSTONE, 6, grid6Stairs);
        if (block == Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS) return new CraftRecipe(Items.POLISHED_BLACKSTONE_BRICKS, 6, grid6Stairs);
        if (block == Blocks.MUD_BRICK_STAIRS) return new CraftRecipe(Items.MUD_BRICKS, 6, grid6Stairs);
        if (block == Blocks.OAK_STAIRS) return new CraftRecipe(Items.OAK_PLANKS, 6, grid6Stairs);
        if (block == Blocks.SPRUCE_STAIRS) return new CraftRecipe(Items.SPRUCE_PLANKS, 6, grid6Stairs);
        if (block == Blocks.BIRCH_STAIRS) return new CraftRecipe(Items.BIRCH_PLANKS, 6, grid6Stairs);
        if (block == Blocks.JUNGLE_STAIRS) return new CraftRecipe(Items.JUNGLE_PLANKS, 6, grid6Stairs);
        if (block == Blocks.ACACIA_STAIRS) return new CraftRecipe(Items.ACACIA_PLANKS, 6, grid6Stairs);
        if (block == Blocks.DARK_OAK_STAIRS) return new CraftRecipe(Items.DARK_OAK_PLANKS, 6, grid6Stairs);
        if (block == Blocks.MANGROVE_STAIRS) return new CraftRecipe(Items.MANGROVE_PLANKS, 6, grid6Stairs);
        if (block == Blocks.CHERRY_STAIRS) return new CraftRecipe(Items.CHERRY_PLANKS, 6, grid6Stairs);

        if (block == Blocks.COBBLESTONE_WALL) return new CraftRecipe(Items.COBBLESTONE, 6, grid6Top);
        if (block == Blocks.STONE_BRICK_WALL) return new CraftRecipe(Items.STONE_BRICKS, 6, grid6Top);
        if (block == Blocks.SANDSTONE_WALL) return new CraftRecipe(Items.SANDSTONE, 6, grid6Top);
        if (block == Blocks.RED_SANDSTONE_WALL) return new CraftRecipe(Items.RED_SANDSTONE, 6, grid6Top);
        if (block == Blocks.NETHER_BRICK_WALL) return new CraftRecipe(Items.NETHER_BRICKS, 6, grid6Top);
        if (block == Blocks.END_STONE_BRICK_WALL) return new CraftRecipe(Items.END_STONE_BRICKS, 6, grid6Top);
        if (block == Blocks.GRANITE_WALL) return new CraftRecipe(Items.GRANITE, 6, grid6Top);
        if (block == Blocks.DIORITE_WALL) return new CraftRecipe(Items.DIORITE, 6, grid6Top);
        if (block == Blocks.ANDESITE_WALL) return new CraftRecipe(Items.ANDESITE, 6, grid6Top);
        if (block == Blocks.COBBLED_DEEPSLATE_WALL) return new CraftRecipe(Items.COBBLED_DEEPSLATE, 6, grid6Top);
        if (block == Blocks.POLISHED_DEEPSLATE_WALL) return new CraftRecipe(Items.POLISHED_DEEPSLATE, 6, grid6Top);
        if (block == Blocks.DEEPSLATE_BRICK_WALL) return new CraftRecipe(Items.DEEPSLATE_BRICKS, 6, grid6Top);
        if (block == Blocks.DEEPSLATE_TILE_WALL) return new CraftRecipe(Items.DEEPSLATE_TILES, 6, grid6Top);
        if (block == Blocks.BLACKSTONE_WALL) return new CraftRecipe(Items.BLACKSTONE, 6, grid6Top);
        if (block == Blocks.POLISHED_BLACKSTONE_WALL) return new CraftRecipe(Items.POLISHED_BLACKSTONE, 6, grid6Top);
        if (block == Blocks.POLISHED_BLACKSTONE_BRICK_WALL) return new CraftRecipe(Items.POLISHED_BLACKSTONE_BRICKS, 6, grid6Top);
        if (block == Blocks.MUD_BRICK_WALL) return new CraftRecipe(Items.MUD_BRICKS, 6, grid6Top);

        return null;
    }

    private boolean hasCraftableOre() { return getCraftableOreRecipe() != null; }

    private CraftRecipe getCraftableOreRecipe() {
        for (Block block : craftOreList.getList()) {
            CraftRecipe recipe = getOreRecipe(block);
            if (recipe != null && countItem(recipe.raw) >= recipe.count) return recipe;
        }
        return null;
    }

    private boolean hasCraftableBlock() { return getCraftableBlockRecipe() != null; }

    private CraftRecipe getCraftableBlockRecipe() {
        for (Block block : craftBlockList.getList()) {
            CraftRecipe recipe = getBlockRecipe(block);
            if (recipe != null && countItem(recipe.raw) >= recipe.count) return recipe;
        }
        return null;
    }

    private void abortCraft(String reason) {
        sendPing("CRAFT ABORTED: " + reason);
        if (mc.player.containerMenu != null) mc.player.closeContainer();
        craftState = CraftState.IDLE;
        craftStep = 0;
        craftDelay = 0;
        updateBaritoneGoal();
    }

    // ─────────────────────────── Safe hideout ───────────────────────────
    private boolean isInNetherOrOverworld() {
        if (mc.level == null) return false;
        return mc.level.dimension().equals(Level.OVERWORLD) || mc.level.dimension().equals(Level.NETHER);
    }

    private void startHideout() {
        baritone("stop");
        hideoutState = HideoutState.DIGGING;
        hideoutDir = null;
        hideoutPos = null;
        hideoutDelay = 0;
        sendPing("Target reached! Finding a safe wall to hide in before logging out.");
    }

    private void handleHideoutState() {
        if (hideoutDelay > 0) { hideoutDelay--; return; }

        switch (hideoutState) {
            case DIGGING: {
                if (hideoutDir == null) {
                    Direction[] dirs = {mc.player.getDirection(), Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
                    for (Direction d : dirs) {
                        BlockPos feet = mc.player.blockPosition().relative(d);
                        BlockPos head = feet.above();
                        if (!isAir(feet) && !isAir(head)
                            && mc.level.getBlockState(feet).getDestroySpeed(mc.level, feet) > 0
                            && mc.level.getBlockState(head).getDestroySpeed(mc.level, head) > 0) {
                            hideoutDir = d;
                            hideoutPos = feet;
                            break;
                        }
                    }
                    if (hideoutDir == null) {
                        sendPing("Could not find a valid wall to hide in. Logging out anyway.");
                        hideoutState = HideoutState.DONE;
                        hideoutDelay = 10;
                        return;
                    }
                }
                boolean feetBroken = isAir(hideoutPos);
                boolean headBroken = isAir(hideoutPos.above());
                if (!feetBroken) { breakBlock(hideoutPos); hideoutDelay = 2; }
                else if (!headBroken) { breakBlock(hideoutPos.above()); hideoutDelay = 2; }
                else { hideoutState = HideoutState.ENTERING; hideoutDelay = 2; }
                break;
            }

            case ENTERING:
                if (mc.player.blockPosition().equals(hideoutPos)) {
                    mc.options.keyUp.setDown(false);
                    hideoutState = HideoutState.SEALING;
                    hideoutDelay = 2;
                } else {
                    mc.options.keyUp.setDown(true);
                    hideoutDelay = 1;
                }
                break;

            case SEALING: {
                mc.options.keyUp.setDown(false);
                BlockPos sealPos = mc.player.blockPosition().relative(hideoutDir.getOpposite());
                int blockSlot = findHotbarSlot(item ->
                    item.getItem() instanceof BlockItem && !SHULKER_PREDICATE.test(item) && item.getItem() != Items.ENDER_CHEST);
                if (blockSlot != -1 && isAir(sealPos)) {
                    lookAtBlock(sealPos);
                    InventoryUtils.setHotbarSlot(blockSlot);
                    placeBlockAt(sealPos);
                    sendPing("Sealed inside wall. Safe logout complete.");
                } else {
                    sendPing("Warning: No blocks to seal the wall. Logging out in open air.");
                }
                hideoutState = HideoutState.DONE;
                hideoutDelay = 10;
                break;
            }

            case DONE:
                toggle();
                hideoutState = HideoutState.IDLE;
                break;

            default:
                break;
        }
    }

    // ─────────────────────────── Native auto-mend ───────────────────────────
    private void startAutoMend() {
        isAutoMending = true;

        sendPing("Tool durability low! Pausing mining to auto-mend tools.");
        baritone("stop");
        updateMendingTools();

        if (!isAutoMending) return;

        baritone("set minYLevelWhileMining 0");
        baritone("set maxYLevelWhileMining 320");
        StringBuilder cmd = new StringBuilder("mine");
        Collection<Block> mo = mendOres.getList();
        if (mo.isEmpty()) {
            cmd.append(' ').append(BuiltInRegistries.BLOCK.getKey(Blocks.NETHER_QUARTZ_ORE).toString());
        } else {
            for (Block b : mo) cmd.append(' ').append(BuiltInRegistries.BLOCK.getKey(b).toString());
        }
        baritone(cmd.toString());
    }

    private void handleAutoMend() {
        if (mendToolSwapDelay > 0) {
            mendToolSwapDelay--;
        } else {
            updateMendingTools();
            mendToolSwapDelay = 20;
        }
        if (!hasToolsBelowDurability(maxMendDurability.getValue())) stopAutoMend();
    }

    private void stopAutoMend() {
        isAutoMending = false;
        sendPing("Tools repaired. Resuming mining operations.");
        if (getToolType(mc.player.getOffhandItem()) != null) {
            int emptySlot = mc.player.getInventory().getFreeSlot();
            if (emptySlot != -1) com.example.addon.utils.InvUtils.swapContainerSlots(com.example.addon.utils.InvUtils.OFFHAND_SLOT, com.example.addon.utils.InvUtils.toContainerSlot(emptySlot));
        }
        updateBaritoneGoal();
    }

    private void updateMendingTools() {
        int mainHandSlot = mc.player.getInventory().selected;
        ItemStack mainHand = mc.player.getMainHandItem();

        boolean needsSwap = false;
        if (getToolType(mainHand) == null) needsSwap = true;
        else if (hasSilkTouch(mainHand)) needsSwap = true;
        else if (getDurabilityPercent(mainHand) >= maxMendDurability.getValue()) {
            if (findMostDamagedNonSilkTool(mainHandSlot) != -1) needsSwap = true;
        }

        if (needsSwap) {
            int bestSlot = findMostDamagedNonSilkTool(mainHandSlot);
            if (bestSlot == -1) {
                sendPing("Auto-Mend failed: No non-Silk Touch tool found to mine XP!");
                autoMendFailed = true;
                isAutoMending = false;
                updateBaritoneGoal();
                return;
            }
            if (bestSlot < 9) {
                InventoryUtils.setHotbarSlot(bestSlot);
            } else {
                com.example.addon.utils.InvUtils.swapContainerSlots(bestSlot, 36 + mc.player.getInventory().selected);
            }
            return;
        }

        ItemStack offHand = mc.player.getOffhandItem();
        boolean needsOffhandSwap = false;
        if (getToolType(offHand) == null) needsOffhandSwap = true;
        else if (hasSilkTouch(offHand)) needsOffhandSwap = true;
        else if (getDurabilityPercent(offHand) >= maxMendDurability.getValue()) {
            if (findMostDamagedNonSilkTool(mainHandSlot) != -1) needsOffhandSwap = true;
        }

        if (needsOffhandSwap) {
            int bestSlot = findMostDamagedNonSilkTool(mainHandSlot);
            if (bestSlot != -1) {
                if (!offHand.isEmpty()) {
                    int emptySlot = mc.player.getInventory().getFreeSlot();
                    if (emptySlot != -1) com.example.addon.utils.InvUtils.swapContainerSlots(com.example.addon.utils.InvUtils.OFFHAND_SLOT, com.example.addon.utils.InvUtils.toContainerSlot(emptySlot));
                }
                com.example.addon.utils.InvUtils.swapContainerSlots(com.example.addon.utils.InvUtils.toContainerSlot(bestSlot), com.example.addon.utils.InvUtils.OFFHAND_SLOT);
            }
        }
    }

    private int findMostDamagedNonSilkTool(int... excludeSlots) {
        Set<Integer> excluded = new HashSet<>();
        for (int s : excludeSlots) excluded.add(s);

        int worstSlot = -1;
        double worstDurability = 101.0;
        for (int i = 0; i < 36; i++) {
            if (excluded.contains(i)) continue;
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (getToolType(stack) != null && !hasSilkTouch(stack)) {
                double dur = getDurabilityPercent(stack);
                if (dur < maxMendDurability.getValue() && dur < worstDurability) { worstDurability = dur; worstSlot = i; }
            }
        }
        if (worstSlot == -1) {
            for (int i = 0; i < 36; i++) {
                if (excluded.contains(i)) continue;
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (getToolType(stack) != null && !hasSilkTouch(stack)) return i;
            }
        }
        return worstSlot;
    }

    private boolean hasSilkTouch(ItemStack stack) { return hasEnchant(stack, Enchantments.SILK_TOUCH); }

    private boolean hasEnchant(ItemStack stack, ResourceKey<Enchantment> key) {
        if (stack.isEmpty()) return false;
        ItemEnchantments e = stack.get(DataComponents.ENCHANTMENTS);
        if (e == null || e.isEmpty()) return false;
        for (Holder<Enchantment> h : e.keySet()) if (h.is(key)) return true;
        return false;
    }

    private double getDurabilityPercent(ItemStack stack) {
        if (stack.isEmpty() || stack.getMaxDamage() == 0) return 100.0;
        return (double) (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage() * 100.0;
    }

    // ─────────────────────────── Deposit state machine ───────────────────────────
    private void handleDepositState() {
        if (depositDelay > 0) { depositDelay--; return; }

        Set<Item> validItems = getValidTargetItems();

        switch (depositState) {
            case PAUSING_BARITONE:
                baritone("stop");
                if (!isBaritoneIdle()) { depositDelay = 5; return; }
                sendPing("Inventory threshold reached. Pausing Baritone to deposit items.");
                spaceClearingStarted = false;
                spaceClearAttempts = 0;
                depositState = DepositState.CLEARING_SPACE;
                depositDelay = 5;
                break;

            case CLEARING_SPACE:
                if (!spaceClearingStarted) {
                    spaceClearingStarted = true;
                    sendPing("Clearing 2x2 area with Baritone...");
                    spaceClearAttempts = 0;
                    spaceClearMinWait = 40;
                    baritone("sel pos1 ~-1 ~ ~-1");
                    baritone("sel pos2 ~1 ~1 ~1");
                    baritone("sel cleararea");
                }
                spaceClearMinWait--;
                if (spaceClearMinWait <= 0 && isBaritoneIdle()) {
                    baritone("sel clear");
                    baritone("stop");
                    spaceClearingStarted = false;
                    depositState = depositMode.getValue() == DepositMode.EnderChest ? DepositState.PLACING_ECHEST : DepositState.PLACING_SHULKER;
                    depositDelay = 5;
                }
                break;

            case PLACING_ECHEST: {
                int echestItemSlot = findInvSlot(Items.ENDER_CHEST);
                if (echestItemSlot == -1) { sendPing("No Ender Chest in inventory! Resuming mining."); depositState = DepositState.RESUMING; return; }
                if (echestItemSlot >= 9) {
                    int targetSlot = swapSlot.getValue();
                    com.example.addon.utils.InvUtils.swapContainerSlots(echestItemSlot, 36 + targetSlot);
                    InventoryUtils.setHotbarSlot(targetSlot);
                } else {
                    InventoryUtils.setHotbarSlot(echestItemSlot);
                }
                echestPos = findAndPlace(findHotbarSlot(Items.ENDER_CHEST));
                if (echestPos == null) {
                    spaceClearAttempts++;
                    if (spaceClearAttempts >= 3) {
                        sendPing("Failed to place Ender Chest after multiple attempts! Resuming mining.");
                        spaceClearAttempts = 0;
                        depositState = DepositState.RESUMING;
                        return;
                    }
                    sendPing("Failed to place Ender Chest! Retrying...");
                    depositState = DepositState.CLEARING_SPACE;
                    depositDelay = 10;
                    return;
                }
                depositState = DepositState.OPENING_ECHEST;
                depositDelay = 5;
                break;
            }

            case OPENING_ECHEST: {
                if (mc.level.getBlockState(echestPos).getBlock() != Blocks.ENDER_CHEST) {
                    sendPing("Failed to place/find Ender Chest. Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                BlockHitResult echestHit = new BlockHitResult(Vec3.atCenterOf(echestPos), Direction.UP, echestPos, false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, echestHit);
                mc.player.swing(InteractionHand.MAIN_HAND);
                depositState = DepositState.EXTRACTING_SHULKER;
                depositDelay = 5;
                break;
            }

            case EXTRACTING_SHULKER: {
                if (!(mc.player.containerMenu instanceof ChestMenu)) {
                    sendPing("Failed to open Ender Chest. Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                ChestMenu echestHandler = (ChestMenu) mc.player.containerMenu;
                boolean hasShulkerInInv = false;
                for (int i = 0; i < 36; i++) {
                    ItemStack stack = mc.player.getInventory().getItem(i);
                    if (SHULKER_PREDICATE.test(stack) && isValidShulkerForDeposit(stack, validItems)) { hasShulkerInInv = true; break; }
                }
                if (hasShulkerInInv) { depositState = DepositState.CLOSING_ECHEST; depositDelay = 2; return; }

                int shulkerSlot = -1;
                for (int i = 0; i < 27; i++) {
                    ItemStack stack = echestHandler.getSlot(i).getItem();
                    if (SHULKER_PREDICATE.test(stack) && isValidShulkerForDeposit(stack, validItems)) { shulkerSlot = i; break; }
                }
                if (shulkerSlot == -1) {
                    mc.player.closeContainer();
                    sendPing("All Shulker Boxes are completely full! Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                mc.gameMode.handleInventoryMouseClick(echestHandler.containerId, shulkerSlot, 0, ClickType.QUICK_MOVE, mc.player);
                depositState = DepositState.CLOSING_ECHEST;
                depositDelay = 2;
                break;
            }

            case CLOSING_ECHEST:
                mc.player.closeContainer();
                depositState = DepositState.PLACING_SHULKER;
                depositDelay = 5;
                break;

            case PLACING_SHULKER: {
                shulkerRecoveryAttempted = false;
                int shulkerInvSlot = -1;
                for (int i = 0; i < 36; i++) {
                    ItemStack stack = mc.player.getInventory().getItem(i);
                    if (SHULKER_PREDICATE.test(stack) && isValidShulkerForDeposit(stack, validItems)) { shulkerInvSlot = i; break; }
                }
                if (shulkerInvSlot == -1) {
                    sendPing("No valid Shulker Boxes found! Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                if (shulkerInvSlot >= 9) {
                    int targetSlot = swapSlot.getValue();
                    com.example.addon.utils.InvUtils.swapContainerSlots(shulkerInvSlot, 36 + targetSlot);
                    InventoryUtils.setHotbarSlot(targetSlot);
                } else {
                    InventoryUtils.setHotbarSlot(shulkerInvSlot);
                }
                BlockPos placeExclude = (depositMode.getValue() == DepositMode.EnderChest) ? echestPos : null;
                BlockPos placeExcludeUp = (depositMode.getValue() == DepositMode.EnderChest && echestPos != null) ? echestPos.above() : null;
                if (placeExclude != null && placeExcludeUp != null) {
                    shulkerPos = findAndPlace(findHotbarSlot(SHULKER_PREDICATE), placeExclude, placeExcludeUp);
                } else {
                    shulkerPos = findAndPlace(findHotbarSlot(SHULKER_PREDICATE));
                }
                if (shulkerPos == null) {
                    spaceClearAttempts++;
                    if (spaceClearAttempts >= 3) {
                        sendPing("Failed to place Shulker Box after 3 attempts! Resuming mining.");
                        spaceClearAttempts = 0;
                        depositState = DepositState.RESUMING;
                        return;
                    }
                    depositState = DepositState.CLEARING_SPACE;
                    depositDelay = 10;
                    return;
                }
                spaceClearAttempts = 0;
                depositState = DepositState.OPENING_SHULKER;
                depositDelay = 5;
                break;
            }

            case OPENING_SHULKER: {
                if (!(mc.level.getBlockState(shulkerPos).getBlock() instanceof ShulkerBoxBlock)) {
                    sendPing("Failed to place Shulker Box. Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                BlockHitResult shulkerHit = new BlockHitResult(Vec3.atCenterOf(shulkerPos), Direction.UP, shulkerPos, false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, shulkerHit);
                mc.player.swing(InteractionHand.MAIN_HAND);
                depositState = DepositState.TRANSFERRING_ITEMS;
                depositDelay = 5;
                break;
            }

            case TRANSFERRING_ITEMS: {
                if (!(mc.player.containerMenu instanceof ShulkerBoxMenu)) {
                    if (!shulkerRecoveryAttempted) {
                        sendPing("Warning: Unable to open Shulker Box. Checking for obstructions...");
                        shulkerRecoveryAttempted = true;
                        depositState = DepositState.MINING_SURROUNDINGS_SHULKER;
                        depositDelay = 5;
                    } else {
                        sendPing("Failed to open Shulker Box even after clearing space. Resuming mining.");
                        depositState = DepositState.RESUMING;
                    }
                    return;
                }
                ShulkerBoxMenu shulkerHandler = (ShulkerBoxMenu) mc.player.containerMenu;
                boolean moved = false;
                for (int i = 27; i < shulkerHandler.slots.size(); i++) {
                    ItemStack stack = shulkerHandler.getSlot(i).getItem();
                    if (validItems.contains(stack.getItem())) {
                        mc.gameMode.handleInventoryMouseClick(shulkerHandler.containerId, i, 0, ClickType.QUICK_MOVE, mc.player);
                        moved = true;
                        break;
                    }
                }
                if (moved) depositDelay = 2;
                else { depositState = DepositState.CLOSING_SHULKER; depositDelay = 2; }
                break;
            }

            case MINING_SURROUNDINGS_SHULKER: {
                if (!isAir(shulkerPos.above())) { breakBlock(shulkerPos.above()); depositDelay = 10; return; }
                sendPing("Surroundings cleared. Attempting to open Shulker Box again.");
                BlockHitResult shulkerReopenHit = new BlockHitResult(Vec3.atCenterOf(shulkerPos), Direction.UP, shulkerPos, false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, shulkerReopenHit);
                mc.player.swing(InteractionHand.MAIN_HAND);
                depositState = DepositState.TRANSFERRING_ITEMS;
                depositDelay = 5;
                break;
            }

            case CLOSING_SHULKER:
                mc.player.closeContainer();
                depositState = DepositState.BREAKING_SHULKER;
                depositDelay = 5;
                break;

            case BREAKING_SHULKER:
                if (!equipEnchantedPickaxe()) {
                    sendPing("No " + toolEnchant.getValue() + " Pickaxe found! Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                if (mc.level.getBlockState(shulkerPos).getBlock() instanceof ShulkerBoxBlock) {
                    breakBlock(shulkerPos);
                    depositDelay = 1;
                    return;
                }
                pickupTimeout = 0;
                depositState = DepositState.PICKING_UP_SHULKER;
                depositDelay = 5;
                break;

            case PICKING_UP_SHULKER: {
                if (findInvSlot(SHULKER_PREDICATE) != -1) {
                    mc.options.keyUp.setDown(false);
                    mc.options.keyJump.setDown(false);
                    mc.options.keyLeft.setDown(false);
                    mc.options.keyRight.setDown(false);
                    if (playerLogoutPending) {
                        sendPing("Shulker Box secured. Skipping Ender Chest cleanup to log out safely!");
                        depositState = DepositState.IDLE;
                        return;
                    }
                    depositState = depositMode.getValue() == DepositMode.EnderChest ? DepositState.REOPENING_ECHEST : DepositState.RESUMING;
                    depositDelay = 5;
                    return;
                }
                ItemEntity targetShulker = null;
                double closestShulkerDist = 8.0;
                boolean shulkerExists = false;
                for (Entity entity : mc.level.entitiesForRendering()) {
                    if (entity instanceof ItemEntity itemEntity && SHULKER_PREDICATE.test(itemEntity.getItem())) {
                        double dist = mc.player.distanceTo(entity);
                        if (dist < 32.0) shulkerExists = true;
                        if (dist < closestShulkerDist) { closestShulkerDist = dist; targetShulker = itemEntity; }
                    }
                }
                if (targetShulker != null) {
                    Vec3 itemPos = targetShulker.position();
                    double diffX = itemPos.x - mc.player.getX();
                    double diffZ = itemPos.z - mc.player.getZ();
                    float yaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90;
                    mc.player.setYRot(yaw);
                    mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, mc.player.getXRot(), mc.player.onGround(), false));
                    mc.options.keyUp.setDown(true);
                    if (Math.abs(itemPos.y - mc.player.getY()) > 0.5 || mc.player.horizontalCollision) mc.options.keyJump.setDown(true);
                    else mc.options.keyJump.setDown(false);
                    depositDelay = 1;
                } else {
                    pickupTimeout++;
                    if (pickupTimeout > 100) {
                        mc.options.keyUp.setDown(false);
                        mc.options.keyJump.setDown(false);
                        if (playerLogoutPending) {
                            sendPing("Failed to pick up Shulker, but logging out due to player!");
                            depositState = DepositState.IDLE;
                            return;
                        }
                        if ((isFinalDeposit || safeLogout.getValue()) && shulkerExists) {
                            sendPing("Waiting for Shulker Box to drop or come into range...");
                            pickupTimeout = 0;
                            depositDelay = 10;
                        } else {
                            sendPing("Lost Shulker Box after mining it! Resuming mining.");
                            depositState = DepositState.RESUMING;
                        }
                    } else {
                        depositDelay = 1;
                    }
                }
                break;
            }

            case REOPENING_ECHEST: {
                if (mc.level.getBlockState(echestPos).getBlock() != Blocks.ENDER_CHEST) {
                    sendPing("Ender Chest disappeared before reopening! Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                BlockHitResult echestHit2 = new BlockHitResult(Vec3.atCenterOf(echestPos), Direction.UP, echestPos, false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, echestHit2);
                mc.player.swing(InteractionHand.MAIN_HAND);
                depositState = DepositState.DEPOSITING_SHULKER;
                depositDelay = 5;
                break;
            }

            case DEPOSITING_SHULKER: {
                if (!(mc.player.containerMenu instanceof ChestMenu)) {
                    sendPing("Failed to reopen Ender Chest. Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                ChestMenu echestHandler2 = (ChestMenu) mc.player.containerMenu;
                int shulkerReturnSlot = -1;
                for (int i = 27; i < echestHandler2.slots.size(); i++) {
                    if (SHULKER_PREDICATE.test(echestHandler2.getSlot(i).getItem())) { shulkerReturnSlot = i; break; }
                }
                if (shulkerReturnSlot == -1) {
                    mc.player.closeContainer();
                    sendPing("Lost Shulker Box after mining it! Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                boolean placed = false;
                for (int i = 0; i < 27; i++) {
                    if (echestHandler2.getSlot(i).getItem().isEmpty()) {
                        mc.gameMode.handleInventoryMouseClick(echestHandler2.containerId, shulkerReturnSlot, 0, ClickType.PICKUP, mc.player);
                        mc.gameMode.handleInventoryMouseClick(echestHandler2.containerId, i, 0, ClickType.PICKUP, mc.player);
                        if (!mc.player.containerMenu.getCarried().isEmpty()) {
                            mc.gameMode.handleInventoryMouseClick(echestHandler2.containerId, shulkerReturnSlot, 0, ClickType.PICKUP, mc.player);
                        }
                        placed = true;
                        break;
                    }
                }
                if (!placed) {
                    mc.player.closeContainer();
                    sendPing("Ender Chest is completely full! Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                depositState = DepositState.REPLACING_TOOLS;
                depositDelay = 2;
                break;
            }

            case REPLACING_TOOLS: {
                if (!(mc.player.containerMenu instanceof ChestMenu)) {
                    sendPing("Failed to reopen Ender Chest for tool check. Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                ChestMenu echestHandler3 = (ChestMenu) mc.player.containerMenu;
                ResourceKey<Enchantment> enchantKey = toolEnchant.getValue() == ToolEnchant.SilkTouch ? Enchantments.SILK_TOUCH : Enchantments.FORTUNE;

                for (int i = 27; i < echestHandler3.slots.size(); i++) {
                    ItemStack invStack = echestHandler3.getSlot(i).getItem();
                    String type = getToolType(invStack);
                    if (type == null) continue;
                    double durability = getDurabilityPercent(invStack);
                    if (durability > minToolDurability.getValue()) continue;

                    int newToolSlot = -1;
                    for (int j = 0; j < 27; j++) {
                        ItemStack echestStack = echestHandler3.getSlot(j).getItem();
                        if (getToolType(echestStack) != null && getToolType(echestStack).equals(type) && hasEnchant(echestStack, enchantKey)) { newToolSlot = j; break; }
                    }
                    if (newToolSlot == -1) {
                        for (int j = 0; j < 27; j++) {
                            ItemStack echestStack = echestHandler3.getSlot(j).getItem();
                            if (getToolType(echestStack) != null && getToolType(echestStack).equals(type)) { newToolSlot = j; break; }
                        }
                    }
                    if (newToolSlot != -1) {
                        mc.gameMode.handleInventoryMouseClick(echestHandler3.containerId, newToolSlot, 0, ClickType.PICKUP, mc.player);
                        mc.gameMode.handleInventoryMouseClick(echestHandler3.containerId, i, 0, ClickType.PICKUP, mc.player);
                        if (!mc.player.containerMenu.getCarried().isEmpty()) {
                            mc.gameMode.handleInventoryMouseClick(echestHandler3.containerId, newToolSlot, 0, ClickType.PICKUP, mc.player);
                        }
                        sendPing("Replaced low durability " + type + ".");
                    } else {
                        sendPing("Warning: " + type + " durability low, but no replacement found in Ender Chest.");
                    }
                }
                depositState = DepositState.REGEAR_FOOD;
                depositDelay = 2;
                break;
            }

            case REGEAR_FOOD: {
                if (!(mc.player.containerMenu instanceof ChestMenu)) {
                    sendPing("Failed to reopen Ender Chest for food check. Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                ChestMenu echestHandler4 = (ChestMenu) mc.player.containerMenu;
                Collection<Item> foods = foodItems.getList();
                if (!foods.isEmpty()) {
                    int currentFoodCount = 0;
                    for (int i = 27; i < echestHandler4.slots.size(); i++) {
                        ItemStack invStack = echestHandler4.getSlot(i).getItem();
                        if (foods.contains(invStack.getItem())) currentFoodCount += invStack.getCount();
                    }
                    if (currentFoodCount < minFoodCount.getValue()) {
                        boolean movedFood = false;
                        for (int i = 0; i < 27; i++) {
                            ItemStack echestStack = echestHandler4.getSlot(i).getItem();
                            if (foods.contains(echestStack.getItem())) {
                                mc.gameMode.handleInventoryMouseClick(echestHandler4.containerId, i, 0, ClickType.QUICK_MOVE, mc.player);
                                movedFood = true;
                                break;
                            }
                        }
                        if (movedFood) {
                            sendPing("Low food detected. Regearing food from Ender Chest...");
                            depositDelay = 2;
                            return;
                        }
                    }
                }
                depositState = DepositState.CLOSING_ECHEST_AGAIN;
                depositDelay = 2;
                break;
            }

            case CLOSING_ECHEST_AGAIN:
                mc.player.closeContainer();
                depositState = DepositState.BREAKING_ECHEST;
                depositDelay = 5;
                break;

            case BREAKING_ECHEST:
                if (!equipEnchantedPickaxe()) {
                    sendPing("No " + toolEnchant.getValue() + " Pickaxe found! Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                if (mc.level.getBlockState(echestPos).getBlock() == Blocks.ENDER_CHEST) {
                    breakBlock(echestPos);
                    depositDelay = 1;
                    return;
                }
                pickupTimeout = 0;
                if (toolEnchant.getValue() == ToolEnchant.Fortune) {
                    sendPing("Fortune mode active: Leaving Ender Chest drops behind.");
                    depositState = DepositState.RESUMING;
                    depositDelay = 5;
                } else {
                    depositState = DepositState.PICKING_UP_ECHEST;
                    depositDelay = 5;
                }
                break;

            case PICKING_UP_ECHEST: {
                if (findInvSlot(Items.ENDER_CHEST) != -1) {
                    mc.options.keyUp.setDown(false);
                    mc.options.keyJump.setDown(false);
                    depositState = DepositState.RESUMING;
                    depositDelay = 5;
                    return;
                }
                ItemEntity targetEchest = null;
                double closestEchestDist = 8.0;
                boolean echestExists = false;
                for (Entity entity : mc.level.entitiesForRendering()) {
                    if (entity instanceof ItemEntity itemEntity && itemEntity.getItem().getItem() == Items.ENDER_CHEST) {
                        double dist = mc.player.distanceTo(entity);
                        if (dist < 32.0) echestExists = true;
                        if (dist < closestEchestDist) { closestEchestDist = dist; targetEchest = itemEntity; }
                    }
                }
                if (targetEchest != null) {
                    Vec3 itemPos = targetEchest.position();
                    double diffX = itemPos.x - mc.player.getX();
                    double diffZ = itemPos.z - mc.player.getZ();
                    float yaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90;
                    mc.player.setYRot(yaw);
                    mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, mc.player.getXRot(), mc.player.onGround(), false));
                    mc.options.keyUp.setDown(true);
                    if (Math.abs(itemPos.y - mc.player.getY()) > 0.5 || mc.player.horizontalCollision) mc.options.keyJump.setDown(true);
                    else mc.options.keyJump.setDown(false);
                    depositDelay = 1;
                } else {
                    pickupTimeout++;
                    if (pickupTimeout > 100) {
                        mc.options.keyUp.setDown(false);
                        mc.options.keyJump.setDown(false);
                        if ((isFinalDeposit || safeLogout.getValue()) && echestExists) {
                            sendPing("Waiting for Ender Chest to drop or come into range...");
                            pickupTimeout = 0;
                            depositDelay = 10;
                        } else {
                            sendPing("Lost Ender Chest after mining it! Resuming mining.");
                            depositState = DepositState.RESUMING;
                        }
                    } else {
                        depositDelay = 1;
                    }
                }
                break;
            }

            case RESUMING:
                ensureToolsInHotbar();
                depositState = DepositState.IDLE;
                if (isFinalDeposit) {
                    isFinalDeposit = false;
                    sendPing("Final deposit complete. Target stacks reached!");
                    baritone("stop");
                    if (safeLogout.getValue() && isInNetherOrOverworld()) startHideout();
                    else toggle();
                } else if (playerLogoutPending) {
                    // handled by onTick logout logic
                } else {
                    sendPing("Successfully deposited items. Resuming Baromine.");
                    updateBaritoneGoal();
                }
                break;

            default:
                break;
        }
    }

    // ─────────────────────────── Baritone & targeting helpers ───────────────────────────
    private void updateBaritoneGoal() {
        if (!isToggled()) return;
        if (mc.player == null) return;
        if (isPortalMakerActive()) return;

        StringBuilder mineCommand = new StringBuilder("mine");
        for (Block b : getTargetBlocks()) {
            mineCommand.append(' ').append(BuiltInRegistries.BLOCK.getKey(b).toString());
        }

        baritone("set minYLevelWhileMining " + (minYLevel.getValue() + 64));
        baritone("set maxYLevelWhileMining " + (maxYLevel.getValue() + 64));
        baritone(mineCommand.toString());
    }

    /** Every block Baritone should mine: the picked list, plus deepslate variants when enabled. */
    public List<Block> getTargetBlocks() {
        List<Block> out = new ArrayList<>();
        Collection<Block> picked = targetMode.getValue() == TargetMode.Ores ? targetOres.getList() : targetBlocks.getList();
        for (Block b : picked) {
            if (b != null && b != Blocks.AIR && !out.contains(b)) out.add(b);
        }
        if (out.isEmpty()) {
            out.add(targetMode.getValue() == TargetMode.Ores ? Blocks.DIAMOND_ORE : Blocks.STONE);
        }
        if (targetMode.getValue() == TargetMode.Ores && includeDeepslate.getValue()) {
            List<Block> extra = new ArrayList<>();
            for (Block b : out) {
                Block d = getDeepslateVariant(b);
                if (d != null && !out.contains(d) && !extra.contains(d)) extra.add(d);
            }
            out.addAll(extra);
        }
        return out;
    }

    public int getCurrentTargetCount() {
        Set<Item> validItems = getValidTargetItems();
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (validItems.contains(stack.getItem())) count += stack.getCount();
        }
        return count;
    }

    public int getTotalAvailableTargetItems() {
        Set<Item> validItems = getValidTargetItems();
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (validItems.contains(stack.getItem())) count += stack.getCount();
            else if (SHULKER_PREDICATE.test(stack)) count += countItemsInShulker(stack, validItems);
        }
        if (mc.player.getEnderChestInventory() != null) {
            for (int i = 0; i < mc.player.getEnderChestInventory().getContainerSize(); i++) {
                ItemStack stack = mc.player.getEnderChestInventory().getItem(i);
                if (validItems.contains(stack.getItem())) count += stack.getCount();
                else if (SHULKER_PREDICATE.test(stack)) count += countItemsInShulker(stack, validItems);
            }
        }
        return count;
    }

    private int countItemsInShulker(ItemStack shulkerStack, Set<Item> validItems) {
        ItemContainerContents container = shulkerStack.get(DataComponents.CONTAINER);
        if (container == null) return 0;
        return (int) container.nonEmptyStream()
            .filter(stack -> validItems.contains(stack.getItem()))
            .mapToInt(ItemStack::getCount)
            .sum();
    }

    private Set<Item> getValidTargetItems() {
        Set<Item> items = new HashSet<>();
        for (Block target : getTargetBlocks()) {
            items.add(target.asItem());
            Item drop = getOreDrop(target);
            if (drop != null) items.add(drop);
        }
        return items;
    }

    private Block getDeepslateVariant(Block block) {
        if (block == Blocks.DIAMOND_ORE) return Blocks.DEEPSLATE_DIAMOND_ORE;
        if (block == Blocks.IRON_ORE) return Blocks.DEEPSLATE_IRON_ORE;
        if (block == Blocks.GOLD_ORE) return Blocks.DEEPSLATE_GOLD_ORE;
        if (block == Blocks.COPPER_ORE) return Blocks.DEEPSLATE_COPPER_ORE;
        if (block == Blocks.COAL_ORE) return Blocks.DEEPSLATE_COAL_ORE;
        if (block == Blocks.LAPIS_ORE) return Blocks.DEEPSLATE_LAPIS_ORE;
        if (block == Blocks.REDSTONE_ORE) return Blocks.DEEPSLATE_REDSTONE_ORE;
        if (block == Blocks.EMERALD_ORE) return Blocks.DEEPSLATE_EMERALD_ORE;
        return null;
    }

    private Item getOreDrop(Block block) {
        if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) return Items.DIAMOND;
        if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) return Items.RAW_IRON;
        if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE) return Items.RAW_GOLD;
        if (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE) return Items.RAW_COPPER;
        if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE) return Items.COAL;
        if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) return Items.LAPIS_LAZULI;
        if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) return Items.REDSTONE;
        if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) return Items.EMERALD;
        if (block == Blocks.NETHER_GOLD_ORE) return Items.GOLD_NUGGET;
        if (block == Blocks.NETHER_QUARTZ_ORE) return Items.QUARTZ;
        return null;
    }

    private void stopBaritoneSafely(String reason) {
        if (isPortalMakerActive()) return;
        baritone("stop");
        sendPing("SAFETY STOP: " + reason);
    }

    private boolean isOre(Block block) {
        if (block == Blocks.ANCIENT_DEBRIS) return true;
        if (block == Blocks.SPORE_BLOSSOM || block == Blocks.HEAVY_CORE) return false;
        return block.getName().getString().toLowerCase().contains("ore");
    }

    private boolean hasToolsBelowDurability(double threshold) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (getToolType(stack) != null && getDurabilityPercent(stack) < threshold) return true;
        }
        return false;
    }

    // ─────────────────────────── Deposit & inventory helpers ───────────────────────────
    private void disconnectSafely(String reason) {
        sendPing(reason);
        if (mc.player != null) mc.player.playSound(getSoundEvent(), soundVolume.getValue().floatValue(), 1.0f);
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.getConnection().disconnect(Component.literal(reason + " Baromine disconnect."));
        }
        depositState = DepositState.IDLE;
        if (!autoReconnect.getValue()) toggle();
    }

    private BlockPos findAndPlace(int hotbarSlot, BlockPos... exclude) {
        if (hotbarSlot < 0) return null;
        InventoryUtils.setHotbarSlot(hotbarSlot);
        Set<BlockPos> excluded = new HashSet<>(Arrays.asList(exclude));

        BlockPos playerPos = mc.player.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        Direction[] horizontal = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction dir : horizontal) candidates.add(playerPos.relative(dir));
        for (Direction dir : horizontal) candidates.add(playerPos.above().relative(dir));
        candidates.add(playerPos.above(2));
        candidates.add(playerPos.below());

        for (BlockPos pos : candidates) {
            if (excluded.contains(pos)) continue;
            BlockState s = mc.level.getBlockState(pos);
            BlockState up = mc.level.getBlockState(pos.above());
            if ((s.isAir() || s.canBeReplaced()) && (up.isAir() || up.canBeReplaced())) {
                if (placeBlockAt(pos)) return pos;
            }
        }
        return null;
    }

    private boolean placeBlockAt(BlockPos pos) {
        RusherHackAPI.getRotationManager().updateRotation(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
        return !mc.level.getBlockState(pos).isAir();
    }

    private boolean isValidShulkerForDeposit(ItemStack shulkerStack, Set<Item> validItems) {
        ItemContainerContents container = shulkerStack.get(DataComponents.CONTAINER);
        if (container == null) return true;
        long filledSlots = container.nonEmptyStream().count();
        if (filledSlots >= 27) return false;
        return container.nonEmptyStream().allMatch(stack -> validItems.contains(stack.getItem()));
    }

    private String getToolType(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Item item = stack.getItem();
        if (item instanceof PickaxeItem) return "pickaxe";
        if (item instanceof AxeItem) return "axe";
        if (item instanceof ShovelItem) return "shovel";
        if (item instanceof HoeItem) return "hoe";
        return null;
    }

    private boolean equipEnchantedPickaxe() {
        if (mc.level == null) return false;
        ResourceKey<Enchantment> enchantKey = toolEnchant.getValue() == ToolEnchant.SilkTouch ? Enchantments.SILK_TOUCH : Enchantments.FORTUNE;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof PickaxeItem && hasEnchant(stack, enchantKey)) {
                if (i < 9) {
                    InventoryUtils.setHotbarSlot(i);
                } else {
                    int targetSlot = -1;
                    for (int j = 0; j < 9; j++) {
                        if (mc.player.getInventory().getItem(j).isEmpty()) { targetSlot = j; break; }
                    }
                    if (targetSlot == -1) targetSlot = mc.player.getInventory().selected;
                    com.example.addon.utils.InvUtils.swapContainerSlots(i, 36 + targetSlot);
                    InventoryUtils.setHotbarSlot(targetSlot);
                }
                return true;
            }
        }
        return false;
    }

    private void ensureToolsInHotbar() {
        if (mc.player == null) return;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (getToolType(stack) != null) {
                int targetSlot = -1;
                for (int j = 0; j < 9; j++) {
                    if (mc.player.getInventory().getItem(j).isEmpty()) { targetSlot = j; break; }
                }
                if (targetSlot != -1) com.example.addon.utils.InvUtils.swapContainerSlots(i, 36 + targetSlot);
                else com.example.addon.utils.InvUtils.swapContainerSlots(i, 36 + mc.player.getInventory().selected);
            }
        }
    }

    private void equipSword() {
        if (mc.player == null) return;
        int bestSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() instanceof SwordItem) { bestSlot = i; break; }
        }
        if (bestSlot == -1) {
            for (int i = 9; i < 36; i++) {
                if (mc.player.getInventory().getItem(i).getItem() instanceof SwordItem) {
                    int targetSlot = -1;
                    for (int j = 0; j < 9; j++) {
                        if (mc.player.getInventory().getItem(j).isEmpty()) { targetSlot = j; break; }
                    }
                    if (targetSlot == -1) targetSlot = mc.player.getInventory().selected;
                    com.example.addon.utils.InvUtils.swapContainerSlots(i, 36 + targetSlot);
                    bestSlot = targetSlot;
                    break;
                }
            }
        }
        if (bestSlot != -1) InventoryUtils.setHotbarSlot(bestSlot);
    }

    // ─────────────────────────── Lava / water safety & world interaction ───────────────────────────
    private boolean isAir(BlockPos p) { return mc.level.getBlockState(p).isAir(); }

    private boolean isWaterNearby(BlockPos center, int radius) {
        if (mc.level == null) return false;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -1; y <= 2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    var fluidState = mc.level.getBlockState(center.offset(x, y, z)).getFluidState();
                    if (fluidState.is(Fluids.WATER) || fluidState.is(Fluids.FLOWING_WATER)) return true;
                }
            }
        }
        return false;
    }

    private boolean isImmediateLavaDanger() {
        BlockPos playerPos = mc.player.blockPosition();
        if (mc.level.getBlockState(playerPos).getBlock() == Blocks.LAVA) return true;
        if (mc.level.getBlockState(playerPos.above()).getBlock() == Blocks.LAVA) return true;
        for (Direction dir : Direction.values()) {
            if (dir.getAxis().isHorizontal() && mc.level.getBlockState(playerPos.relative(dir)).getBlock() == Blocks.LAVA) return true;
        }
        return false;
    }

    private boolean isImmediateWaterDanger() {
        BlockPos playerPos = mc.player.blockPosition();
        if (mc.level.getBlockState(playerPos).getFluidState().is(FluidTags.WATER)) return true;
        if (mc.level.getBlockState(playerPos.above()).getFluidState().is(FluidTags.WATER)) return true;
        for (Direction dir : Direction.values()) {
            if (dir.getAxis().isHorizontal() && mc.level.getBlockState(playerPos.relative(dir)).getFluidState().is(FluidTags.WATER)) return true;
        }
        return false;
    }

    private void placeSafetyBlock(boolean isWater) {
        Block fluidBlock = isWater ? Blocks.WATER : Blocks.LAVA;
        int blockSlot = findHotbarSlot(itemStack ->
            itemStack.getItem() instanceof BlockItem && !SHULKER_PREDICATE.test(itemStack) && itemStack.getItem() != Items.ENDER_CHEST);

        if (blockSlot == -1) {
            if (isWater) stopBaritoneSafely("No blocks for water safety!");
            else disconnectSafely("No blocks for lava safety!");
            return;
        }
        InventoryUtils.setHotbarSlot(blockSlot);

        BlockPos playerPos = mc.player.blockPosition();
        Direction[] horizontal = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

        for (Direction dir : horizontal) {
            BlockPos sidePos = playerPos.relative(dir);
            if (mc.level.getBlockState(sidePos).getBlock() == fluidBlock) {
                lookAtBlock(sidePos);
                if (placeBlockAt(sidePos)) return;
            }
        }

        for (Direction dir : horizontal) {
            BlockPos sidePos = playerPos.relative(dir);
            BlockPos sideUpPos = sidePos.above();
            BlockPos sideDownPos = sidePos.below();

            boolean sideClear = isAir(sidePos) || mc.level.getBlockState(sidePos).getBlock() == fluidBlock;
            boolean sideUpClear = isAir(sideUpPos) || mc.level.getBlockState(sideUpPos).getBlock() == fluidBlock;

            if (sideClear && sideUpClear) {
                if (mc.level.getBlockState(sidePos).getBlock() == fluidBlock) { lookAtBlock(sidePos); placeBlockAt(sidePos); }
                if (isAir(sideDownPos) || mc.level.getBlockState(sideDownPos).getBlock() == fluidBlock) { lookAtBlock(sideDownPos); placeBlockAt(sideDownPos); }

                float yaw = switch (dir) {
                    case NORTH -> 180f;
                    case SOUTH -> 0f;
                    case WEST -> 90f;
                    case EAST -> -90f;
                    default -> 0f;
                };
                mc.player.setYRot(yaw);
                mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, mc.player.getXRot(), mc.player.onGround(), false));
                mc.options.keyUp.setDown(true);
                if (isWater) waterMoveTicks = 10;
                else lavaMoveTicks = 10;
                return;
            }
        }

        BlockPos headPos = playerPos.above(2);
        boolean headClear = isAir(headPos) || mc.level.getBlockState(headPos).getBlock() == fluidBlock;
        if (headClear) {
            BlockPos downPos = playerPos.below();
            if (isAir(downPos) || mc.level.getBlockState(downPos).getBlock() == fluidBlock) { lookAtBlock(downPos); placeBlockAt(downPos); }
            mc.options.keyJump.setDown(true);
            jumpTicks = 5;
        }
    }

    private void lookAtBlock(BlockPos pos) {
        Vec3 posVec = Vec3.atCenterOf(pos);
        double diffX = posVec.x - mc.player.getX();
        double diffY = posVec.y - (mc.player.getY() + mc.player.getEyeHeight());
        double diffZ = posVec.z - mc.player.getZ();
        double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
        float yaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90;
        float pitch = (float) -Math.toDegrees(Math.atan2(diffY, dist));
        mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, mc.player.onGround(), false));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
    }

    private void breakBlock(BlockPos pos) {
        if (mc.level.getBlockState(pos).isAir()) return;
        lookAtBlock(pos);
        mc.gameMode.continueDestroyBlock(pos, Direction.UP);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    // ─────────────────────────── Notifications ───────────────────────────
    private void sendPing(String message) {
        if (pingMode.getValue() == PingMode.Chat || pingMode.getValue() == PingMode.Both) {
            sendNotification(NotificationType.INFO, "[Baromine] " + message);
        }
        if (pingMode.getValue() == PingMode.Sound || pingMode.getValue() == PingMode.Both) {
            if (mc.player != null) mc.player.playSound(getSoundEvent(), soundVolume.getValue().floatValue(), 1.0f);
        }
    }

    private SoundEvent getSoundEvent() {
        return switch (warningSound.getValue()) {
            case Bass -> SoundEvents.NOTE_BLOCK_BASS.value();
            case Harp -> SoundEvents.NOTE_BLOCK_HARP.value();
            case Bell -> SoundEvents.BELL_BLOCK;
            case Anvil -> SoundEvents.ANVIL_LAND;
            case LevelUp -> SoundEvents.PLAYER_LEVELUP;
            case OrbPickup -> SoundEvents.EXPERIENCE_ORB_PICKUP;
            case Beacon -> SoundEvents.BEACON_POWER_SELECT;
            case GhastWarn -> SoundEvents.GHAST_WARN;
            case DragonGrowl -> SoundEvents.ENDER_DRAGON_GROWL;
            case WitherSpawn -> SoundEvents.WITHER_SPAWN;
            case ChallengeComplete -> SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;
            default -> SoundEvents.NOTE_BLOCK_PLING.value();
        };
    }

    // ─────────────────────────── Baritone state helper ───────────────────────────
    public static boolean isBaritoneIdle() {
        try {
            var pathing = BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior();
            return !pathing.isPathing() && !pathing.hasPath();
        } catch (Throwable t) {
            return true;
        }
    }

    private void baritone(String cmd) {
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendChat("#" + cmd);
        }
    }

    private boolean isPortalMakerActive() {
        Object pm = RusherHackAPI.getModuleManager().getFeature("portal-maker").orElse(null);
        return pm instanceof ToggleableModule tm && tm.isToggled();
    }

    // ─────────────────────────── Small inventory search helpers ───────────────────────────
    private int findInvSlot(Predicate<ItemStack> pred) {
        for (int i = 0; i < 36; i++) if (pred.test(mc.player.getInventory().getItem(i))) return i;
        return -1;
    }

    private int findInvSlot(Item item) { return findInvSlot(s -> s.getItem() == item); }

    private int findHotbarSlot(Predicate<ItemStack> pred) {
        for (int i = 0; i < 9; i++) if (pred.test(mc.player.getInventory().getItem(i))) return i;
        return -1;
    }

    private int findHotbarSlot(Item item) { return findHotbarSlot(s -> s.getItem() == item); }

    private String itemName(Item item) {
        return new ItemStack(item).getHoverName().getString();
    }

    // ─────────────────────────── HUD helpers ───────────────────────────
    public String getCurrentStatus() {
        if (!isToggled()) return "Inactive";
        if (isAutoMending) return "Auto-Mending";
        if (hideoutState != HideoutState.IDLE) return "Hiding for Logout";
        if (deepDarkState == DeepDarkState.RETREATING) return "Retreating (Deep Dark)";
        if (deepDarkState == DeepDarkState.ASCENDING) return "Ascending (Deep Dark)";
        if (deepDarkState == DeepDarkState.RUNNING_AWAY) return "Running Away";
        if (craftState != CraftState.IDLE) return "Crafting";
        if (depositState != DepositState.IDLE) return "Depositing: " + depositState.name().replace("_", " ");
        if (isHandlingLava) return "Avoiding Lava";
        if (isHandlingWater) return "Avoiding Water";
        if (wasPausedForCombat) return "Paused (Combat)";
        if (isWaitingToReconnect) return "Waiting to Reconnect";
        return "Mining";
    }

    public double getMainHandDurabilityPercent() {
        if (mc.player == null) return 100.0;
        return getDurabilityPercent(mc.player.getMainHandItem());
    }

    public long getSessionStartTime() { return startTime; }
}
