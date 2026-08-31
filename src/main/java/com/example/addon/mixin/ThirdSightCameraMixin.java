package com.example.addon.mixin;

import com.example.addon.modules.ThirdSight;
import org.rusherhack.client.api.RusherHackAPI;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class ThirdSightCameraMixin {

    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void onClipToSpace(float desiredDistance, CallbackInfoReturnable<Float> cir) {
        ThirdSight module = (ThirdSight) RusherHackAPI.getModuleManager().getFeature("third-sight").orElse(null);
        if (module == null || !module.isToggled()) return;

        if (module.isNoDistanceActive() && !module.isZooming()) return;

        cir.setReturnValue((float) module.getDistance());
    }

    @ModifyArg(
        method = "setup",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setRotation(FF)V"
        ),
        index = 0
    )
    private float modifyCameraYaw(float yaw) {
        ThirdSight module = (ThirdSight) RusherHackAPI.getModuleManager().getFeature("third-sight").orElse(null);
        if (module == null || !module.isFreeLookActive()) return yaw;
        return module.cameraYaw;
    }

    @ModifyArg(
        method = "setup",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setRotation(FF)V"
        ),
        index = 1
    )
    private float modifyCameraPitch(float pitch) {
        ThirdSight module = (ThirdSight) RusherHackAPI.getModuleManager().getFeature("third-sight").orElse(null);
        if (module == null || !module.isFreeLookActive()) return pitch;
        return module.cameraPitch;
    }
}
