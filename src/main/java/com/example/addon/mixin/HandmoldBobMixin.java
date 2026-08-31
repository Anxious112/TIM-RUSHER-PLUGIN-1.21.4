package com.example.addon.mixin;

import com.example.addon.modules.Handmold;
import org.rusherhack.client.api.RusherHackAPI;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class HandmoldBobMixin {

    @Inject(
        method = "bobView",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onBobView(PoseStack matrices, float tickDelta, CallbackInfo ci) {
        Handmold mod = (Handmold) RusherHackAPI.getModuleManager().getFeature("handmold").orElse(null);
        if (mod != null && mod.shouldDisableHandBob()) ci.cancel();
    }
}
