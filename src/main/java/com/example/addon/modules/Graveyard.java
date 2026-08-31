package com.example.addon.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.addon.Tim;
import com.example.addon.utils.RenderUtils;

import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.client.api.setting.ItemListSetting;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;

public class Graveyard extends ToggleableModule {

    // ── General ───────────────────────────────────────────────────────────────

    private final NumberSetting<Integer> range = new NumberSetting<>("range", "Detection range in blocks.", 32, 16, 256);

    private final BooleanSetting showBeam = new BooleanSetting("show-beam", "Show beam above found items.", true);

    private final ColorSetting beamColor = new ColorSetting("beam-color", "Color of the beam.", new Color(255, 255, 255, 200))
        .setAlphaAllowed(true)
        .setVisibility(showBeam::getValue);

    private final NumberSetting<Double> beamWidth = new NumberSetting<>("beam-width", "Beam thickness (blocks).", 0.15, 0.05, 0.5)
        .setVisibility(showBeam::getValue);

    private final BooleanSetting onlyNearest = new BooleanSetting("only-nearest", "Only highlight and notify about the closest item.", false);

    private final BooleanSetting notification = new BooleanSetting("notification", "Send chat messages and play sound when new whitelisted items or XP orbs are found.", true);

    private final BooleanSetting sortByDistance = new BooleanSetting("sort-by-distance", "If enabled, prioritizes closest items.", false);

    private final ItemListSetting whitelistedItems = new ItemListSetting("whitelisted-items",
        "Items to look for on the ground, like diamond swords and valuable gear.",
        Items.ELYTRA, Items.TOTEM_OF_UNDYING, Items.BOW,
        Items.FLINT_AND_STEEL,
        Items.ENCHANTED_GOLDEN_APPLE,
        Items.FIREWORK_ROCKET, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE,
        Items.DIAMOND_SHOVEL,
        Items.DIAMOND_SWORD,
        Items.DIAMOND_HOE,
        Items.SHULKER_BOX,
        Items.WHITE_SHULKER_BOX, Items.ORANGE_SHULKER_BOX, Items.MAGENTA_SHULKER_BOX, Items.LIGHT_BLUE_SHULKER_BOX,
        Items.YELLOW_SHULKER_BOX, Items.LIME_SHULKER_BOX, Items.PINK_SHULKER_BOX, Items.GRAY_SHULKER_BOX,
        Items.LIGHT_GRAY_SHULKER_BOX, Items.CYAN_SHULKER_BOX, Items.PURPLE_SHULKER_BOX, Items.BLUE_SHULKER_BOX,
        Items.BROWN_SHULKER_BOX, Items.GREEN_SHULKER_BOX, Items.RED_SHULKER_BOX, Items.BLACK_SHULKER_BOX,
        Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_SWORD, Items.NETHERITE_HOE,
        Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
        Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS
    );

    // ── XP Orbs ───────────────────────────────────────────────────────────────

    private final BooleanSetting detectXpOrbs = new BooleanSetting("detect-xp-orbs", "Detects Experience Orbs on the ground and creates a beam and notifier.", true);

    private final ColorSetting xpBeamColor = new ColorSetting("xp-beam-color", "Color of the beam for Experience Orbs.", new Color(255, 255, 0, 200))
        .setAlphaAllowed(true)
        .setVisibility(() -> detectXpOrbs.getValue() && showBeam.getValue());

    // ── Enchantment Filter ────────────────────────────────────────────────────

    private final BooleanSetting enchantedOnly = new BooleanSetting("enchanted-only",
        "Only highlight whitelisted items if they are enchanted. Items that cannot be enchanted (shulker boxes, totems, etc.) are always shown regardless.", false);

    private final ColorSetting enchantedBeamColor = new ColorSetting("enchanted-beam-color",
        "Beam color override for enchanted items when enchanted-only is off, so both plain and enchanted items can be told apart visually.",
        new Color(180, 80, 255, 220))
        .setAlphaAllowed(true)
        .setVisibility(() -> !enchantedOnly.getValue() && showBeam.getValue());

    private final BooleanSetting separateEnchantedColor = new BooleanSetting("separate-enchanted-color",
        "Use the enchanted beam color above to visually distinguish enchanted items from plain ones.", false)
        .setVisibility(() -> !enchantedOnly.getValue() && showBeam.getValue());

    // ── State ─────────────────────────────────────────────────────────────────

    private final List<ItemEntity> itemsToRender          = new ArrayList<>();
    private final List<ExperienceOrb> xpOrbsToRender       = new ArrayList<>();
    private final Set<Integer>     notifiedItemEntities    = new HashSet<>();
    private long lastXpNotifyTime = 0;
    private static final long XP_NOTIFY_COOLDOWN_MS = 3000;

    public Graveyard() {
        super("graveyard", "Highlights valuable items and XP on the ground.", Tim.CATEGORY);
        this.registerSettings(
            range, showBeam, beamColor, beamWidth, onlyNearest, notification, sortByDistance, whitelistedItems,
            detectXpOrbs, xpBeamColor, enchantedOnly, enchantedBeamColor, separateEnchantedColor
        );
    }

    @Override
    public void onEnable() {
        notifiedItemEntities.clear();
        itemsToRender.clear();
        xpOrbsToRender.clear();
        lastXpNotifyTime = 0;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.level == null || mc.player == null) return;

        notifiedItemEntities.removeIf(id -> mc.level.getEntity(id) == null);
        itemsToRender.clear();
        xpOrbsToRender.clear();

        AABB searchArea = new AABB(mc.player.blockPosition()).inflate(range.getValue());

        List<ItemEntity> matching = mc.level.getEntitiesOfClass(
            ItemEntity.class,
            searchArea,
            e -> {
                ItemStack stack = e.getItem();
                if (!whitelistedItems.getList().contains(stack.getItem())) return false;
                if (enchantedOnly.getValue() && canBeEnchanted(stack) && !isEnchanted(stack)) return false;
                return true;
            }
        );

        if (sortByDistance.getValue()) {
            matching.sort(Comparator.comparingDouble(e -> mc.player.distanceToSqr(e)));
        }

        if (!matching.isEmpty()) {
            if (onlyNearest.getValue()) {
                ItemEntity closest = matching.get(0);
                itemsToRender.add(closest);
                notifyIfNew(closest);
            } else {
                itemsToRender.addAll(matching);
                for (ItemEntity item : matching) notifyIfNew(item);
            }
        }

        if (detectXpOrbs.getValue()) {
            List<ExperienceOrb> xpOrbs = mc.level.getEntitiesOfClass(
                ExperienceOrb.class,
                searchArea,
                e -> true
            );

            if (!xpOrbs.isEmpty()) {
                xpOrbsToRender.addAll(xpOrbs);
                if (notification.getValue()) {
                    long now = System.currentTimeMillis();
                    if (now - lastXpNotifyTime > XP_NOTIFY_COOLDOWN_MS) {
                        lastXpNotifyTime = now;
                        this.sendNotification(NotificationType.INFO, "Found XP orbs nearby!");
                        mc.player.playSound(SoundEvents.PLAYER_LEVELUP, 0.8f, 1.5f);
                    }
                }
            }
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Subscribe
    private void onRender(EventRender3D event) {
        if (!showBeam.getValue() || (itemsToRender.isEmpty() && xpOrbsToRender.isEmpty())) return;

        IRenderer3D renderer = event.getRenderer();
        renderer.begin(event.getMatrixStack());

        double  halfWidth  = beamWidth.getValue() / 2.0;
        double  topOfWorld = mc.level.getHeight();
        boolean useSplit   = separateEnchantedColor.getValue() && !enchantedOnly.getValue();

        for (ItemEntity item : itemsToRender) {
            Color c = (useSplit && isEnchanted(item.getItem()))
                ? enchantedBeamColor.getValue()
                : beamColor.getValue();

            Vec3 pos = item.position();
            renderer.drawBox(
                pos.x - halfWidth, pos.y, pos.z - halfWidth,
                halfWidth * 2, topOfWorld - pos.y, halfWidth * 2,
                true, true, c.getRGB()
            );
        }

        for (ExperienceOrb orb : xpOrbsToRender) {
            Color c = xpBeamColor.getValue();
            Vec3 pos = orb.position();
            renderer.drawBox(
                pos.x - halfWidth, pos.y, pos.z - halfWidth,
                halfWidth * 2, topOfWorld - pos.y, halfWidth * 2,
                true, true, c.getRGB()
            );
        }

        renderer.end();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isEnchanted(ItemStack stack) {
        var enchants = stack.get(DataComponents.ENCHANTMENTS);
        if (enchants != null && !enchants.isEmpty()) return true;
        var stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        return stored != null && !stored.isEmpty();
    }

    private boolean canBeEnchanted(ItemStack stack) {
        return stack.isEnchantable();
    }

    private void notifyIfNew(ItemEntity item) {
        int id = item.getId();
        if (!notifiedItemEntities.add(id)) return;

        if (notification.getValue()) {
            String name = item.getItem().getDisplayName().getString();
            this.sendNotification(NotificationType.INFO, "Found: " + name);
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.9f, 1.0f);
        }
    }
}
