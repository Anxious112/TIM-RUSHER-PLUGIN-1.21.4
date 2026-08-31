package com.example.addon.mixin;

import com.example.addon.modules.ThirdSight;
import org.rusherhack.client.api.RusherHackAPI;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerEntityMixin {

    @Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
    private void onGetFovMultiplier(boolean isFirstPerson, float scale, CallbackInfoReturnable<Float> cir) {
        ThirdSight thirdSight = (ThirdSight) RusherHackAPI.getModuleManager().getFeature("third-sight").orElse(null);
        if (thirdSight != null && thirdSight.isBeaconEffectCountered()) {
            cir.setReturnValue(1.0f);
        }
    }
}
