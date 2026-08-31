package com.example.addon.mixin;

import java.util.function.BooleanSupplier;

import com.example.addon.modules.DungeonAssistant;
import com.example.addon.modules.Inventory101;
import com.example.addon.modules.LootLens;

import org.rusherhack.client.api.RusherHackAPI;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin extends Screen {
    @Shadow protected int imageWidth;
    @Shadow protected int leftPos;
    @Shadow protected int topPos;

    @Unique private Button s1Button;
    @Unique private Button s2Button;

    protected HandledScreenMixin(Component title) {
        super(title);
    }

    private static Inventory101 inv101() {
        return (Inventory101) RusherHackAPI.getModuleManager().getFeature("inventory-101").orElse(null);
    }

    private static LootLens lootLens() {
        return (LootLens) RusherHackAPI.getModuleManager().getFeature("loot-lens").orElse(null);
    }

    private static DungeonAssistant dungeonAssistant() {
        return (DungeonAssistant) RusherHackAPI.getModuleManager().getFeature("dungeon-assistant").orElse(null);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        s1Button = null;
        s2Button = null;

        Inventory101 inv101 = inv101();
        boolean inv101Active = inv101 != null && inv101.isToggled();

        if ((Object) this instanceof InventoryScreen && inv101Active) {
            int bx = this.leftPos - 25;
            int by = this.topPos;

            s1Button = tim$mouseOnly(Component.literal("S1"), btn -> inv101.startInvSort(1), bx, by, 20, 20,
                Tooltip.create(Component.literal("Sort to " + inv101.getPresetName(1))), () -> !inv101.isPresetEmpty(1));
            s2Button = tim$mouseOnly(Component.literal("S2"), btn -> inv101.startInvSort(2), bx, by + 25, 20, 20,
                Tooltip.create(Component.literal("Sort to " + inv101.getPresetName(2))), () -> !inv101.isPresetEmpty(2));

            this.addRenderableWidget(s1Button);
            this.addRenderableWidget(s2Button);
            return;
        }

        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        int containerSlots = screen.getMenu().slots.size() - 36;

        if (inv101Active) {
            if ((Object) this instanceof ShulkerBoxScreen) {
                int bx = this.leftPos - 25;
                int by = this.topPos;

                this.addRenderableWidget(tim$mouseOnly(Component.literal("S"), btn -> inv101.toggleSaveMode(), bx, by, 20, 20,
                    Tooltip.create(Component.literal("Save Current Layout")), null));
                by += 25;
                this.addRenderableWidget(tim$mouseOnly(Component.literal("1"), btn -> {
                    if (!inv101.isSaveMode() && inv101.isPresetEmpty(1)) return;
                    inv101.handlePreset(1);
                }, bx, by, 20, 20, Tooltip.create(Component.literal("Load " + inv101.getPresetName(1))), () -> !inv101.isPresetEmpty(1)));
                by += 25;
                this.addRenderableWidget(tim$mouseOnly(Component.literal("2"), btn -> {
                    if (!inv101.isSaveMode() && inv101.isPresetEmpty(2)) return;
                    inv101.handlePreset(2);
                }, bx, by, 20, 20, Tooltip.create(Component.literal("Load " + inv101.getPresetName(2))), () -> !inv101.isPresetEmpty(2)));
                by += 25;
                this.addRenderableWidget(tim$mouseOnly(Component.literal("C"), btn -> inv101.clearPresets(), bx, by, 20, 20,
                    Tooltip.create(Component.literal("Clear Presets")), null));
                by += 25;

                if (inv101.isRegearButtonEnabled()) {
                    this.addRenderableWidget(tim$mouseOnly(Component.literal("G"), btn -> inv101.startRegearing(), bx, by, 20, 20,
                        Tooltip.create(Component.literal("Equip armor and replenish essentials")), null));
                    by += 25;
                }
                if (inv101.isReplenishButtonEnabled()) {
                    this.addRenderableWidget(tim$mouseOnly(Component.literal("R"), btn -> inv101.startReplenishing(), bx, by, 20, 20,
                        Tooltip.create(Component.literal("Replenish whitelisted items from shulker")), null));
                }
                return;
            }

            if ((Object) this instanceof ContainerScreen && inv101.isSortButtonEnabled()) {
                int bx = this.leftPos + this.imageWidth - 70;
                int by = this.topPos + 2;
                this.addRenderableWidget(tim$mouseOnly(Component.literal("Sort"), btn -> inv101.startSorting(), bx, by, 30, 14,
                    Tooltip.create(Component.literal("Sort shulkers by colour")), null));
            }
        }

        if (containerSlots <= 0) return;

        LootLens ll = lootLens();
        if (ll != null && ll.shouldShowStealDumpButtons()) {
            tim$addStealDumpButtons(screen, containerSlots);
        } else {
            DungeonAssistant da = dungeonAssistant();
            if (da != null && da.isToggled()) {
                tim$addStealDumpButtons(screen, containerSlots);
            }
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if ((Object) this instanceof InventoryScreen && s1Button != null && s2Button != null) {
            int defaultX = (this.width - this.imageWidth) / 2;
            boolean isRecipeBookOpen = this.leftPos > defaultX + 50;
            int bx = isRecipeBookOpen ? (this.leftPos + this.imageWidth + 5) : (this.leftPos - 25);
            int by = this.topPos;
            s1Button.setPosition(bx, by);
            s2Button.setPosition(bx, by + 25);
        }
    }

    @Unique
    private static Button tim$mouseOnly(Component label, Button.OnPress action, int x, int y, int width, int height,
                                        Tooltip tooltip, BooleanSupplier hasData) {
        Button btn = new Button(x, y, width, height, label, action, supplier -> supplier.get()) {
            @Override
            public boolean keyPressed(int keyCode, int scanCode, int modifiers) { return false; }
            @Override
            public boolean keyReleased(int keyCode, int scanCode, int modifiers) { return false; }
            @Override
            protected void renderWidget(GuiGraphics graphics, int mx, int my, float pd) {
                if (hasData != null) {
                    boolean prev = this.active;
                    this.active = hasData.getAsBoolean();
                    super.renderWidget(graphics, mx, my, pd);
                    this.active = prev;
                } else {
                    super.renderWidget(graphics, mx, my, pd);
                }
            }
        };
        if (tooltip != null) btn.setTooltip(tooltip);
        return btn;
    }

    @Unique
    private void tim$addStealDumpButtons(AbstractContainerScreen<?> screen, int containerSlots) {
        int buttonX, buttonY, buttonW, buttonH, buttonGap;

        if ((Object) this instanceof ContainerScreen) {
            buttonW = 14;
            buttonH = 14;
            buttonGap = 2;
            buttonX = this.leftPos + this.imageWidth - 8 - buttonW - buttonGap - buttonW;
            buttonY = this.topPos + 2;
        } else {
            buttonW = 20;
            buttonH = 20;
            buttonGap = 4;

            int screenWidth = this.width;
            int rightEdge = this.leftPos + this.imageWidth + 5 + buttonW;

            if (rightEdge <= screenWidth) {
                buttonX = this.leftPos + this.imageWidth + 5;
                buttonY = this.topPos + 5;
            } else {
                int containerRows = (containerSlots + 8) / 9;
                buttonX = this.leftPos + (this.imageWidth - (buttonW * 2 + buttonGap)) / 2;
                buttonY = this.topPos + containerRows * 18 + 2;
            }
        }

        this.addRenderableWidget(tim$mouseOnly(Component.literal("S"), button -> {
            for (int i = 0; i < containerSlots; i++) {
                if (screen.getMenu().getSlot(i).hasItem()) {
                    this.minecraft.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, i, 0, ClickType.QUICK_MOVE, this.minecraft.player);
                }
            }
        }, buttonX, buttonY, buttonW, buttonH, Tooltip.create(Component.literal("Steal all items from container")), null));

        this.addRenderableWidget(tim$mouseOnly(Component.literal("D"), button -> {
            for (int i = containerSlots; i < screen.getMenu().slots.size(); i++) {
                if (screen.getMenu().getSlot(i).getItem().isEmpty()) continue;
                this.minecraft.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, i, 0, ClickType.QUICK_MOVE, this.minecraft.player);
            }
        }, buttonX + buttonW + buttonGap, buttonY, buttonW, buttonH, Tooltip.create(Component.literal("Dump all inventory items into container")), null));
    }
}
