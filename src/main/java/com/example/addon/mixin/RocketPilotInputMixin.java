package com.example.addon.mixin;

import com.example.addon.modules.RocketPilot;
import org.rusherhack.client.api.RusherHackAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public class RocketPilotInputMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        RocketPilot rocketPilot = (RocketPilot) RusherHackAPI.getModuleManager().getFeature("rocket-pilot").orElse(null);
        if (rocketPilot != null && rocketPilot.isToggled() && rocketPilot.useFreeLookY.getValue()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.isFallFlying()) {
                ClientInput input = (ClientInput) (Object) this;
                input.forwardImpulse = 0;
                input.leftImpulse = 0;
            }
        }
    }
}
