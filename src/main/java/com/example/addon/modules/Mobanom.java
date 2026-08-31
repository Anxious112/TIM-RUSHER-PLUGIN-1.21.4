package com.example.addon.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.example.addon.Tim;
import com.example.addon.utils.GlowingRegistry;
import com.example.addon.utils.RenderUtils;

import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.client.api.setting.EntityTypeListSetting;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.AABB;

import java.awt.Color;

public class Mobanom extends ToggleableModule {

    public enum AnomalyType {
        DIMENSION_NETHER,
        DIMENSION_END,
        DIMENSION_OVERWORLD,
        ITEM,
        CHESTED
    }

    public enum HighlightMode { Wireframe, Spectral, Pulse }

    private final EnumSetting<HighlightMode> highlightMode = new EnumSetting<>("highlight-mode",
        "How anomalous mobs are outlined. Wireframe draws a box outline; Spectral uses the vanilla glow pipeline; Pulse uses a fading bloom effect.", HighlightMode.Wireframe);

    private final BooleanSetting chatNotification = new BooleanSetting("chat-notification", "Notify in chat when an anomaly is detected.", true);

    private final EntityTypeListSetting ignoredEntities = new EntityTypeListSetting("ignored-entities", "Entities to ignore.");

    private final NumberSetting<Integer> range = new NumberSetting<>("range", "The range to detect anomalies.", 128, 1, 256);

    private final NumberSetting<Integer> glowLayers = new NumberSetting<>("glow-layers", "Number of bloom layers rendered around each mob.", 4, 1, 8)
        .setVisibility(() -> highlightMode.getValue() == HighlightMode.Wireframe || highlightMode.getValue() == HighlightMode.Pulse);

    private final NumberSetting<Double> glowSpread = new NumberSetting<>("glow-spread", "How far each bloom layer expands outward (in blocks).", 0.05, 0.01, 0.2)
        .setVisibility(() -> highlightMode.getValue() == HighlightMode.Wireframe || highlightMode.getValue() == HighlightMode.Pulse);

    private final NumberSetting<Integer> glowBaseAlpha = new NumberSetting<>("glow-base-alpha", "Alpha of the innermost glow layer (0-255).", 60, 10, 150)
        .setVisibility(() -> highlightMode.getValue() == HighlightMode.Wireframe);

    private final NumberSetting<Double> pulseSpeed = new NumberSetting<>("pulse-speed", "Pulse cycle speed. 1.0 = one full fade in/out per second.", 1.0, 0.1, 5.0)
        .setVisibility(() -> highlightMode.getValue() == HighlightMode.Pulse);

    private final NumberSetting<Integer> pulseMinAlpha = new NumberSetting<>("pulse-min-alpha", "Lowest alpha reached during the pulse (0 = invisible).", 15, 0, 255)
        .setVisibility(() -> highlightMode.getValue() == HighlightMode.Pulse);

    private final NumberSetting<Integer> pulseMaxAlpha = new NumberSetting<>("pulse-max-alpha", "Peak alpha reached during the pulse.", 220, 15, 255)
        .setVisibility(() -> highlightMode.getValue() == HighlightMode.Pulse);

    private final ColorSetting overworldLineColor = new ColorSetting("overworld-line-color", "Glow color for Overworld mobs found in other dimensions.", new Color(0, 255, 0, 255));
    private final ColorSetting netherLineColor = new ColorSetting("nether-line-color", "Glow color for Nether mobs found in other dimensions.", new Color(255, 0, 0, 255));
    private final ColorSetting endLineColor = new ColorSetting("end-line-color", "Glow color for End mobs found in other dimensions.", new Color(255, 0, 255, 255));

    private final BooleanSetting detectUnnaturalItems = new BooleanSetting("detect-unnatural-items", "Detects mobs holding or wearing player-like items.", true);

    private final ColorSetting itemAnomalyLineColor = new ColorSetting("item-anomaly-line-color", "The glow color for mobs with unnatural items.", new Color(0, 255, 255, 255))
        .setVisibility(detectUnnaturalItems::getValue);

    private final BooleanSetting detectPumpkins = new BooleanSetting("detect-pumpkins", "Detects mobs wearing carved pumpkins or jack-o'-lanterns.", true)
        .setVisibility(detectUnnaturalItems::getValue);

    private final BooleanSetting detectChestedAnimals = new BooleanSetting("detect-chested-animals", "Detects animals (donkeys, llamas, etc.) carrying a chest.", true);

    private static final Set<EntityType<?>> OVERWORLD_NATIVES = Set.of(
        EntityType.ALLAY, EntityType.AXOLOTL, EntityType.BAT, EntityType.CAMEL, EntityType.CAT,
        EntityType.CHICKEN, EntityType.COD, EntityType.COW, EntityType.DONKEY, EntityType.FOX,
        EntityType.FROG, EntityType.GLOW_SQUID, EntityType.HORSE, EntityType.MOOSHROOM, EntityType.MULE,
        EntityType.OCELOT, EntityType.PARROT, EntityType.PIG, EntityType.RABBIT, EntityType.SALMON,
        EntityType.SHEEP, EntityType.SNIFFER, EntityType.SNOW_GOLEM, EntityType.SQUID, EntityType.TADPOLE,
        EntityType.TROPICAL_FISH, EntityType.TURTLE, EntityType.VILLAGER, EntityType.WANDERING_TRADER,
        EntityType.BEE, EntityType.IRON_GOLEM, EntityType.POLAR_BEAR, EntityType.WOLF,
        EntityType.ZOMBIE, EntityType.CREEPER, EntityType.WITCH, EntityType.PHANTOM, EntityType.DROWNED,
        EntityType.HUSK, EntityType.STRAY, EntityType.ZOMBIE_VILLAGER, EntityType.VINDICATOR, EntityType.EVOKER,
        EntityType.PILLAGER, EntityType.RAVAGER, EntityType.VEX, EntityType.ILLUSIONER, EntityType.GUARDIAN,
        EntityType.ELDER_GUARDIAN, EntityType.SILVERFISH, EntityType.ZOMBIE_HORSE
    );

    private static final Set<EntityType<?>> NETHER_NATIVES = Set.of(
        EntityType.GHAST, EntityType.BLAZE, EntityType.WITHER_SKELETON, EntityType.MAGMA_CUBE,
        EntityType.PIGLIN, EntityType.PIGLIN_BRUTE, EntityType.HOGLIN, EntityType.ZOGLIN, EntityType.STRIDER
    );

    private static final Set<EntityType<?>> END_NATIVES = Set.of(
        EntityType.SHULKER, EntityType.ENDERMITE, EntityType.ENDER_DRAGON
    );

    private final Map<Integer, AnomalyType> highlightedEntities = new HashMap<>();
    private final Set<Integer>              notifiedEntities    = new HashSet<>();

    public Mobanom() {
        super("mobanom", "Detects and highlights mobs in the wrong dimension or with unnatural items.", Tim.CATEGORY);
        this.registerSettings(
            highlightMode, chatNotification, ignoredEntities, range,
            glowLayers, glowSpread, glowBaseAlpha, pulseSpeed, pulseMinAlpha, pulseMaxAlpha,
            overworldLineColor, netherLineColor, endLineColor,
            detectUnnaturalItems, itemAnomalyLineColor, detectPumpkins, detectChestedAnimals
        );
    }

    @Override
    public void onEnable() {
        highlightedEntities.clear();
        notifiedEntities.clear();
        GlowingRegistry.clear();
    }

    @Override
    public void onDisable() {
        highlightedEntities.clear();
        notifiedEntities.clear();
        GlowingRegistry.clear();
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.level == null || mc.player == null) return;

        highlightedEntities.clear();
        GlowingRegistry.clear();
        notifiedEntities.removeIf(id -> mc.level.getEntity(id) == null);

        String dim = mc.level.dimension().location().toString();
        boolean spectral = highlightMode.getValue() == HighlightMode.Spectral;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Mob mob)) continue;
            if (ignoredEntities.getList().contains(mob.getType())) continue;
            if (mc.player.distanceTo(mob) > range.getValue()) continue;

            AnomalyType type = resolveAnomalyType(mob, dim);
            if (type == null) continue;

            highlightedEntities.put(mob.getId(), type);

            if (spectral) {
                Color c = getColorForType(type);
                GlowingRegistry.add(mob.getId(), c.getRGB());
            }

            if (chatNotification.getValue() && notifiedEntities.add(mob.getId())) {
                String mobName = mob.getType().getDescription().getString();
                switch (type) {
                    case CHESTED -> this.sendNotification(NotificationType.INFO, "Chested animal detected: " + mobName);
                    case ITEM    -> this.sendNotification(NotificationType.INFO, "Item anomaly detected: " + mobName);
                    default      -> this.sendNotification(NotificationType.INFO, "Dimension anomaly detected: " + mobName);
                }
            }
        }
    }

    @Subscribe
    private void onRender(EventRender3D event) {
        if (mc.level == null || mc.player == null || highlightedEntities.isEmpty()) return;

        boolean wireframe = highlightMode.getValue() == HighlightMode.Wireframe;
        boolean pulse = highlightMode.getValue() == HighlightMode.Pulse;
        if (!wireframe && !pulse) return;

        IRenderer3D renderer = event.getRenderer();
        renderer.begin(event.getMatrixStack());

        for (Map.Entry<Integer, AnomalyType> entry : highlightedEntities.entrySet()) {
            Entity entity = mc.level.getEntity(entry.getKey());
            if (!(entity instanceof Mob mob)) continue;

            Color color = getColorForType(entry.getValue());
            AABB box = mob.getBoundingBox();

            if (wireframe) {
                renderGlowLayers(renderer, box, color);
                renderer.drawBox(box.minX, box.minY, box.minZ, box.getXsize(), box.getYsize(), box.getZsize(), false, true, color.getRGB());
            } else {
                renderPulseBox(renderer, box, color);
            }
        }

        renderer.end();
    }

    private void renderGlowLayers(IRenderer3D renderer, AABB box, Color color) {
        int    layers    = glowLayers.getValue();
        double spread    = glowSpread.getValue();
        int    baseAlpha = glowBaseAlpha.getValue();

        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            int layerAlpha   = Math.max(4, (int)(baseAlpha * (1.0 - (double)(i - 1) / layers)));
            AABB expanded = box.inflate(expansion);
            renderer.drawBox(expanded.minX, expanded.minY, expanded.minZ, expanded.getXsize(), expanded.getYsize(), expanded.getZsize(),
                true, false, RenderUtils.withAlpha(color, layerAlpha));
        }
    }

    private float getPulseFactor() {
        double speed = pulseSpeed.getValue();
        double t = System.currentTimeMillis() / 1000.0;
        double phase = t * speed * Math.PI * 2.0;
        return (float)((Math.sin(phase) + 1.0) * 0.5);
    }

    private int applyPulse() {
        float f = getPulseFactor();
        int min = pulseMinAlpha.getValue();
        int max = pulseMaxAlpha.getValue();
        return Math.min(255, Math.max(0, (int)(min + (max - min) * f)));
    }

    private void renderPulseBox(IRenderer3D renderer, AABB box, Color base) {
        int pa = applyPulse();
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
        renderer.drawBox(box.minX, box.minY, box.minZ, box.getXsize(), box.getYsize(), box.getZsize(),
            true, true, RenderUtils.withAlpha(base, pa));
    }

    private AnomalyType resolveAnomalyType(Mob mob, String dimension) {
        if (detectChestedAnimals.getValue() && hasChestAttachment(mob)) return AnomalyType.CHESTED;
        if (detectUnnaturalItems.getValue() && hasUnnaturalItems(mob))  return AnomalyType.ITEM;

        EntityType<?> type = mob.getType();

        return switch (dimension) {
            case "minecraft:overworld" -> {
                if (NETHER_NATIVES.contains(type)) yield AnomalyType.DIMENSION_NETHER;
                if (END_NATIVES.contains(type))    yield AnomalyType.DIMENSION_END;
                yield null;
            }
            case "minecraft:the_nether" -> {
                if (OVERWORLD_NATIVES.contains(type)) yield AnomalyType.DIMENSION_OVERWORLD;
                if (END_NATIVES.contains(type))       yield AnomalyType.DIMENSION_END;
                yield null;
            }
            case "minecraft:the_end" -> {
                if (OVERWORLD_NATIVES.contains(type)) yield AnomalyType.DIMENSION_OVERWORLD;
                if (NETHER_NATIVES.contains(type))    yield AnomalyType.DIMENSION_NETHER;
                yield null;
            }
            default -> null;
        };
    }

    private boolean hasUnnaturalItems(Mob mob) {
        for (ItemStack stack : mob.getArmorSlots()) {
            if (isUnnatural(stack)) return true;
        }
        boolean skipMainHand = (mob.getType() == EntityType.PIGLIN
                                    && mob.getMainHandItem().is(Items.CROSSBOW))
                            || (mob.getType() == EntityType.ZOMBIFIED_PIGLIN
                                    && mob.getMainHandItem().is(Items.GOLDEN_SWORD));
        if (!skipMainHand && isUnnatural(mob.getMainHandItem())) return true;
        if (isUnnatural(mob.getOffhandItem())) return true;
        return false;
    }

    private boolean isUnnatural(ItemStack stack) {
        if (stack.isEmpty()) return false;

        Item item = stack.getItem();

        if (item == Items.ELYTRA) return true;
        if (item instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) return true;
        if (detectPumpkins.getValue() && (item == Items.CARVED_PUMPKIN || item == Items.JACK_O_LANTERN)) return true;

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        if (itemId.getNamespace().equals("minecraft") && itemId.getPath().startsWith("netherite_")) return true;

        ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
        if (enchants == null || enchants.isEmpty()) return false;

        for (Holder<Enchantment> enchantmentEntry : enchants.keySet()) {
            Enchantment enchantment = enchantmentEntry.value();
            if (enchantment == null) continue;
            if (enchantmentEntry.is(Enchantments.MENDING)) return true;
            if (enchants.getLevel(enchantmentEntry) > enchantment.getMaxLevel()) return true;
        }

        return false;
    }

    private boolean hasChestAttachment(Mob mob) {
        if (mob instanceof AbstractChestedHorse chestedHorse) return chestedHorse.hasChest();
        return false;
    }

    private Color getColorForType(AnomalyType type) {
        return switch (type) {
            case ITEM, CHESTED       -> itemAnomalyLineColor.getValue();
            case DIMENSION_END       -> endLineColor.getValue();
            case DIMENSION_NETHER    -> netherLineColor.getValue();
            case DIMENSION_OVERWORLD -> overworldLineColor.getValue();
        };
    }

    public Map<Integer, AnomalyType> getAnomalies() {
        return highlightedEntities;
    }
}
