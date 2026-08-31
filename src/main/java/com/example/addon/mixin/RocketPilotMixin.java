package com.example.addon.mixin;

import com.example.addon.modules.RocketPilot;
import org.rusherhack.client.api.RusherHackAPI;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class RocketPilotMixin {

    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Shadow public abstract Vec3 getPosition();

    @Inject(method = "setup", at = @At("RETURN"))
    private void onUpdate(BlockGetter area, Entity focusedEntity, boolean detached, boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        RocketPilot rocketPilot = (RocketPilot) RusherHackAPI.getModuleManager().getFeature("rocket-pilot").orElse(null);
        if (rocketPilot != null && rocketPilot.isToggled() && rocketPilot.useFreeLookY.getValue()) {
            if (focusedEntity instanceof LivingEntity living && living.isFallFlying()) {
                setPosition(getPosition().x, rocketPilot.freeLookY.getValue(), getPosition().z);
            }
        }
    }
}
