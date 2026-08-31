package com.example.addon.mixin;

import com.example.addon.modules.Illushine;
import org.rusherhack.client.api.RusherHackAPI;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class InGameHudMixin {

    @Inject(
        method = "renderCrosshair",
        at = @At("HEAD"),
        cancellable = true
    )
    private void illushine$cancelCrosshair(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        Illushine mod = (Illushine) RusherHackAPI.getModuleManager().getFeature("illushine").orElse(null);
        if (mod != null && mod.isToggled() && mod.getCrosshairMode() != Illushine.CrosshairMode.None) {
            mod.drawCrosshair(context);
            ci.cancel();
        }
    }
}
