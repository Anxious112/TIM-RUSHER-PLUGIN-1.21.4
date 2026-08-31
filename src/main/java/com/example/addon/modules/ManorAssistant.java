package com.example.addon.modules;

import java.awt.Color;
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

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class ManorAssistant extends ToggleableModule {

    public enum TargetType { CONTAINER, SECRET_CONTAINER }
    public enum RenderMode { GLOW, SPECTRAL, PULSE }

    public enum AlertSound {
        EVOKER_CAST("Evoker Cast"),
        TOTEM_USE("Totem Use"),
        LEVEL_UP("Level Up"),
        EXPERIENCE_ORB("Experience Orb"),
        BELL("Bell");

        private final String displayName;
        AlertSound(String displayName) { this.displayName = displayName; }
        @Override public String toString() { return displayName; }
    }

    private static final int DIMENSION_CHANGE_COOLDOWN_TICKS = 40;
    private static final int INTERACT_TIMEOUT_TICKS = 20;

    private final Map<BlockPos, TargetType> targets = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final Set<BlockPos> checkedContainers = new HashSet<>();

    private final Set<Integer> notifiedEvokers = new HashSet<>();
    private final Set<Integer> notifiedVindicators = new HashSet<>();
    private final Set<Integer> notifiedTotems = new HashSet<>();
    private int totalTotemsFound = 0;

    private boolean wasAutoOpened = false;
    private int interactTimeoutTimer = 0;
    private boolean hasAlertedForCurrentScreen = false;

    private String lastDimension = "";
    private int dimensionChangeCooldown = 0;

    private final List<Evoker> evokerTargets = new ArrayList<>();
    private final List<Vindicator> vindicatorTargets = new ArrayList<>();
    private final List<ItemEntity> totemDrops = new ArrayList<>();

    // ── Settings — General ──
    private final NumberSetting<Integer> range = new NumberSetting<>("range", "Detection range in chunks.", 16, 1, 128);
    private final NumberSetting<Integer> manorYLevel = new NumberSetting<>("manor-y-level", "Minimum Y level to scan. Mansions generate above Y = 60.", 60, -64, 320)
        .onChange((java.util.function.Consumer<Integer>) v -> {
            scannedChunks.clear();
            targets.entrySet().removeIf(entry -> entry.getKey().getY() < v);
        });
    private final EnumSetting<RenderMode> renderMode = new EnumSetting<>("render-mode", "GLOW = layered bloom boxes. SPECTRAL = outline shader. PULSE = fading highlight.", RenderMode.GLOW);
    private final NumberSetting<Integer> beamWidth = new NumberSetting<>("beam-width", "Width of the beams for dropped Totems.", 15, 5, 50);
    private final NumberSetting<Integer> glowLayers = new NumberSetting<>("glow-layers", "Number of bloom layers rendered around each target.", 4, 1, 8)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW || renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Double> glowSpread = new NumberSetting<>("glow-spread", "How far each bloom layer expands outward (in blocks).", 0.04, 0.01, 0.15)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW || renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> glowBaseAlpha = new NumberSetting<>("glow-base-alpha", "Alpha of the innermost glow layer (0-255).", 60, 10, 150)
        .setVisibility(() -> renderMode.getValue() == RenderMode.GLOW);
    private final NumberSetting<Integer> spectralBlockFillAlpha = new NumberSetting<>("spectral-block-fill-alpha", "Fill alpha for block targets in SPECTRAL mode.", 30, 0, 120)
        .setVisibility(() -> renderMode.getValue() == RenderMode.SPECTRAL);
    private final NumberSetting<Double> pulseSpeed = new NumberSetting<>("pulse-speed", "Pulse cycle speed. 1.0 = one full fade in/out per second.", 1.0, 0.1, 5.0)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> pulseMinAlpha = new NumberSetting<>("pulse-min-alpha", "Lowest alpha reached during the pulse (0 = invisible).", 15, 0, 255)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);
    private final NumberSetting<Integer> pulseMaxAlpha = new NumberSetting<>("pulse-max-alpha", "Peak alpha reached during the pulse.", 220, 15, 255)
        .setVisibility(() -> renderMode.getValue() == RenderMode.PULSE);

    // ── Settings — Targets ──
    private final BooleanSetting trackContainers = new BooleanSetting("track-chests", "Highlight standard chests.", true);
    private final ColorSetting containerColor = new ColorSetting("chest-color", "Color for standard chests.", new Color(0, 0, 255, 255)).setVisibility(trackContainers::getValue);
    private final BooleanSetting trackSecretContainers = new BooleanSetting("track-secret-chests", "Highlight chests hidden inside walls (secret rooms).", true);
    private final ColorSetting secretContainerColor = new ColorSetting("secret-chest-color", "Color for hidden chests.", new Color(255, 0, 255, 255)).setVisibility(trackSecretContainers::getValue);
    private final ItemListSetting containerWhitelist = new ItemListSetting("chest-whitelist", "Items to alert you about when opening Chests.",
        Items.NETHERITE_BLOCK, Items.NETHERITE_INGOT, Items.DIAMOND,
        Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE,
        Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
        Items.ENDER_CHEST, Items.ENCHANTED_GOLDEN_APPLE, Items.ELYTRA,
        Items.NETHERITE_SWORD, Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE,
        Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
        Items.SHULKER_BOX, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE,
        Items.TOTEM_OF_UNDYING, Items.WIND_CHARGE, Items.BOOK);

    // ── Settings — Entities ──
    private final BooleanSetting trackEvokers = new BooleanSetting("track-evokers", "Highlights Evokers (Totem droppers).", true);
    private final ColorSetting evokerColor = new ColorSetting("evoker-color", "Color for Evokers.", new Color(150, 0, 255, 255)).setVisibility(trackEvokers::getValue);
    private final BooleanSetting trackVindicators = new BooleanSetting("track-vindicators", "Highlights Vindicators (Axe mob).", true);
    private final ColorSetting vindicatorColor = new ColorSetting("vindicator-color", "Color for Vindicators.", new Color(255, 100, 0, 255)).setVisibility(trackVindicators::getValue);
    private final BooleanSetting trackDroppedTotems = new BooleanSetting("track-dropped-totems", "Highlights dropped Totems of Undying with a massive beam.", true);
    private final ColorSetting totemColor = new ColorSetting("totem-color", "Color for dropped Totems.", new Color(255, 215, 0, 255)).setVisibility(trackDroppedTotems::getValue);

    // ── Settings — Automation & Safety ──
    private final BooleanSetting enableAlerts = new BooleanSetting("enable-alerts", "Master toggle for audio cues and loot announcements.", true);
    private final EnumSetting<AlertSound> alertSound = new EnumSetting<>("alert-sound", "Which sound to play for module alerts.", AlertSound.EVOKER_CAST).setVisibility(enableAlerts::getValue);
    private final NumberSetting<Double> alertVolume = new NumberSetting<>("alert-volume", "Volume of the alert sound.", 1.0, 0.0, 5.0).setVisibility(enableAlerts::getValue);
    private final BooleanSetting autoOpenChests = new BooleanSetting("auto-open-chests", "Automatically opens nearby chests.", true);
    private final BooleanSetting disconnectOnPlayer = new BooleanSetting("disconnect-on-player", "Instantly disconnects from the server if another player enters render distance.", false);
    private final BooleanSetting autoDisableOnLowHealth = new BooleanSetting("auto-disable-on-low-health", "Disables the module if health is critical.", true);

    public ManorAssistant() {
        super("manor-assistant", "Highlights Woodland Manor elements: secret chests, evokers, vindicators, and dropped totems.", Tim.CATEGORY);
        this.registerSettings(
            range, manorYLevel, renderMode, beamWidth, glowLayers, glowSpread, glowBaseAlpha,
            spectralBlockFillAlpha, pulseSpeed, pulseMinAlpha, pulseMaxAlpha,
            trackContainers, containerColor, trackSecretContainers, secretContainerColor, containerWhitelist,
            trackEvokers, evokerColor, trackVindicators, vindicatorColor, trackDroppedTotems, totemColor,
            enableAlerts, alertSound, alertVolume, autoOpenChests, disconnectOnPlayer, autoDisableOnLowHealth
        );
    }

    @Override
    public void onEnable() {
        targets.clear();
        scannedChunks.clear();
        checkedContainers.clear();
        notifiedEvokers.clear();
        notifiedVindicators.clear();
        notifiedTotems.clear();
        totalTotemsFound = 0;
        hasAlertedForCurrentScreen = false;
        GlowingRegistry.clear();
    }

    @Override
    public void onDisable() {
        GlowingRegistry.clear();
        targets.clear();
    }

    @Subscribe
    private void onTick(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;
        if (performSafetyChecks()) return;
        checkForPlayers();
        updateContainerLogic();
        checkOpenedContainerLoot();
        updateScanningLogic();
    }

    @Subscribe
    private void onRender(EventRender3D event) {
        if (mc.player == null || mc.level == null) return;

        IRenderer3D r = event.getRenderer();
        r.begin(event.getMatrixStack());

        boolean isSpectral = renderMode.getValue() == RenderMode.SPECTRAL;
        boolean isPulse = renderMode.getValue() == RenderMode.PULSE;
        Set<BlockPos> toRemove = new HashSet<>();

        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            BlockPos pos = entry.getKey();
            TargetType type = entry.getValue();

            if (!isChunkLoaded(pos)) continue;
            if (mc.level.getBlockState(pos).isAir()) { toRemove.add(pos); continue; }

            Block currentBlock = mc.level.getBlockState(pos).getBlock();
            if (!validateBlockType(currentBlock, type)) { toRemove.add(pos); continue; }

            AABB renderBox = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
            Color color = getColor(type);
            if (color == null) continue;

            if (isSpectral) {
                db(r, renderBox, true, false, RenderUtils.withAlpha(color, spectralBlockFillAlpha.getValue()));
            } else if (isPulse) {
                renderPulseBox(r, renderBox, color);
            } else {
                renderGlowLayers(r, renderBox, color);
                db(r, renderBox, false, true, color.getRGB());
            }
        }

        for (BlockPos pos : toRemove) targets.remove(pos);

        renderEntity(r, isSpectral, isPulse, trackEvokers.getValue(), false, evokerTargets, evokerColor.getValue());
        renderEntity(r, isSpectral, isPulse, trackVindicators.getValue(), false, vindicatorTargets, vindicatorColor.getValue());
        renderEntity(r, isSpectral, isPulse, trackDroppedTotems.getValue(), true, totemDrops, totemColor.getValue());

        r.end();
    }

    // ── Scanning Logic ──
    private void updateScanningLogic() {
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
        scanEvokers();
        scanVindicators();
        scanDroppedTotems();
        pruneBlockTargets();
        scanNewChunks(centerChunkX, centerChunkZ);
    }

    private void scanEvokers() {
        evokerTargets.clear();
        if (!trackEvokers.getValue()) return;

        AABB searchBox = new AABB(mc.player.blockPosition()).inflate(range.getValue() * 16);
        Set<Integer> currentIds = new HashSet<>();

        for (Evoker evoker : mc.level.getEntitiesOfClass(Evoker.class, searchBox, e -> true)) {
            evokerTargets.add(evoker);
            currentIds.add(evoker.getId());

            if (renderMode.getValue() == RenderMode.SPECTRAL) {
                GlowingRegistry.add(evoker.getId(), evokerColor.getValue().getRGB());
            } else {
                GlowingRegistry.remove(evoker.getId());
            }

            if (notifiedEvokers.add(evoker.getId()) && enableAlerts.getValue()) {
                sendNotification(NotificationType.INFO, "§dEvoker Detected! Watch for Vexes.");
                playAlert();
            }
        }
        notifiedEvokers.retainAll(currentIds);
    }

    private void scanVindicators() {
        vindicatorTargets.clear();
        if (!trackVindicators.getValue()) return;

        AABB searchBox = new AABB(mc.player.blockPosition()).inflate(range.getValue() * 16);
        Set<Integer> currentIds = new HashSet<>();

        for (Vindicator vindicator : mc.level.getEntitiesOfClass(Vindicator.class, searchBox, e -> true)) {
            vindicatorTargets.add(vindicator);
            currentIds.add(vindicator.getId());

            if (renderMode.getValue() == RenderMode.SPECTRAL) {
                GlowingRegistry.add(vindicator.getId(), vindicatorColor.getValue().getRGB());
            } else {
                GlowingRegistry.remove(vindicator.getId());
            }

            if (notifiedVindicators.add(vindicator.getId()) && enableAlerts.getValue()) {
                sendNotification(NotificationType.INFO, "§6Vindicator Detected!");
                playAlert();
            }
        }
        notifiedVindicators.retainAll(currentIds);
    }

    private void scanDroppedTotems() {
        totemDrops.clear();
        if (!trackDroppedTotems.getValue()) return;

        AABB searchBox = new AABB(mc.player.blockPosition()).inflate(range.getValue() * 16);
        Set<Integer> currentIds = new HashSet<>();

        for (ItemEntity item : mc.level.getEntitiesOfClass(ItemEntity.class, searchBox, e -> true)) {
            if (item.getItem().is(Items.TOTEM_OF_UNDYING)) {
                totemDrops.add(item);
                currentIds.add(item.getId());

                if (renderMode.getValue() == RenderMode.SPECTRAL) {
                    GlowingRegistry.add(item.getId(), totemColor.getValue().getRGB());
                }

                if (notifiedTotems.add(item.getId())) {
                    totalTotemsFound++;
                    if (enableAlerts.getValue()) {
                        sendNotification(NotificationType.INFO, "§6§lTOTEM OF UNDYING DROPPED! §ePick it up!");
                        playAlert();
                    }
                }
            }
        }
        notifiedTotems.retainAll(currentIds);
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
        scanBlockEntitiesInChunk(chunk);
        scannedChunks.add(cp);
        return true;
    }

    private void scanBlockEntitiesInChunk(LevelChunk chunk) {
        int minY = manorYLevel.getValue();

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            BlockPos pos = be.getBlockPos();
            if (pos.getY() < minY) continue;

            if (be instanceof ChestBlockEntity) {
                targets.put(pos, isSecretChest(pos) ? TargetType.SECRET_CONTAINER : TargetType.CONTAINER);
            }
        }
    }

    private boolean isSecretChest(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue;
            if (!mc.level.getBlockState(pos.relative(dir)).canOcclude()) return false;
        }
        return true;
    }

    // ── Automation & Safety Logic ──
    private void updateContainerLogic() {
        if (!autoOpenChests.getValue()) return;

        if (interactTimeoutTimer > 0) interactTimeoutTimer--;

        if (mc.screen == null && !wasAutoOpened) {
            List<BlockPos> nearbyChests = targets.entrySet().stream()
                .filter(e -> e.getValue() == TargetType.CONTAINER || e.getValue() == TargetType.SECRET_CONTAINER)
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
                        sendNotification(NotificationType.INFO, "§cRare loot found in chest: §e" + stack.getHoverName().getString() + "§c!");
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

    private void checkForPlayers() {
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

    private void playAlert() {
        if (mc.player == null) return;
        SoundEvent sound = switch (alertSound.getValue()) {
            case LEVEL_UP -> SoundEvents.PLAYER_LEVELUP;
            case TOTEM_USE -> SoundEvents.TOTEM_USE;
            case EXPERIENCE_ORB -> SoundEvents.EXPERIENCE_ORB_PICKUP;
            case BELL -> SoundEvents.BELL_BLOCK;
            case EVOKER_CAST -> SoundEvents.EVOKER_CAST_SPELL;
        };
        mc.player.playSound(sound, alertVolume.getValue().floatValue(), 1.0f);
    }

    // ── Rendering helpers ──
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

    private void renderEntity(IRenderer3D r, boolean isSpectral, boolean isPulse, boolean isEnabled, boolean renderBeam, List<? extends Entity> entities, Color color) {
        if (!isEnabled || entities.isEmpty()) return;

        double beamSize = beamWidth.getValue() / 100.0;
        for (Entity entity : entities) {
            if (!entity.isAlive()) continue;
            AABB box = entity.getBoundingBox();
            Vec3 pos = entity.position();
            AABB beamBox = renderBeam ? new AABB(
                pos.x - beamSize, pos.y, pos.z - beamSize,
                pos.x + beamSize, mc.level.getHeight(), pos.z + beamSize
            ) : null;

            if (isSpectral) {
                db(r, box, false, true, RenderUtils.withAlpha(color, 200));
                if (renderBeam) {
                    db(r, beamBox, true, false, RenderUtils.withAlpha(color, 20));
                    db(r, beamBox, false, true, RenderUtils.withAlpha(color, 180));
                }
            } else if (isPulse) {
                renderPulseBox(r, box, color);
                if (renderBeam) renderPulseBox(r, beamBox, color);
            } else {
                renderGlowLayers(r, box, color);
                db(r, box, false, true, color.getRGB());
                if (renderBeam) {
                    renderGlowLayers(r, beamBox, color);
                    db(r, beamBox, true, false, RenderUtils.withAlpha(color, 60));
                    db(r, beamBox, false, true, color.getRGB());
                }
            }
        }
    }

    // ── Utility Helpers ──
    private boolean isChunkLoaded(BlockPos pos) {
        return mc.level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private boolean validateBlockType(Block block, TargetType type) {
        return switch (type) {
            case CONTAINER, SECRET_CONTAINER -> block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL;
        };
    }

    private Color getColor(TargetType type) {
        return switch (type) {
            case CONTAINER -> trackContainers.getValue() ? containerColor.getValue() : null;
            case SECRET_CONTAINER -> trackSecretContainers.getValue() ? secretContainerColor.getValue() : null;
        };
    }

    private void pruneBlockTargets() {
        if (mc.level == null || mc.player == null) return;
        Set<BlockPos> toRemove = new HashSet<>();
        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            BlockPos pos = entry.getKey();
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;

            if (mc.level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                Block currentBlock = mc.level.getBlockState(pos).getBlock();
                if (mc.level.getBlockState(pos).isAir() || !validateBlockType(currentBlock, entry.getValue())) {
                    toRemove.add(pos);
                }
            } else {
                toRemove.add(pos);
                scannedChunks.remove(new ChunkPos(chunkX, chunkZ));
            }
        }
        for (BlockPos pos : toRemove) targets.remove(pos);
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
            sendNotification(NotificationType.ERROR, "Health is critical, disabling to prevent totem pop.");
            toggle();
            return true;
        }
        return false;
    }
}
