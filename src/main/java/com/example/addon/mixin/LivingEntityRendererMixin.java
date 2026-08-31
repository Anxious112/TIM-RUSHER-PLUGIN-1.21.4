package com.example.addon.mixin;

import com.example.addon.modules.Illushine;
import org.rusherhack.client.api.RusherHackAPI;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mojmap's PlayerRenderer doesn't override scale() -- it just inherits this
 * generic LivingEntityRenderer hook -- so both mob scaling (Illushine mob
 * categories) and player scaling (Illushine player-scale settings) are
 * handled here in one place, distinguished by instanceof Player.
 */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {

    @Unique
    private static final ThreadLocal<LivingEntity> illushine$currentEntity = new ThreadLocal<>();

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
        at = @At("TAIL")
    )
    private void illushine$captureEntity(LivingEntity entity, LivingEntityRenderState state, float partialTicks, CallbackInfo ci) {
        illushine$currentEntity.set(entity);
    }

    @Inject(method = "scale", at = @At("TAIL"))
    private void illushine$onScale(LivingEntityRenderState state, PoseStack matrices, CallbackInfo ci) {
        LivingEntity entity = illushine$currentEntity.get();
        if (entity == null) return;

        if (!entity.isAlive()) {
            illushine$currentEntity.set(null);
            return;
        }

        Illushine illushine = (Illushine) RusherHackAPI.getModuleManager().getFeature("illushine").orElse(null);
        if (illushine == null || !illushine.isToggled()) {
            illushine$currentEntity.set(null);
            return;
        }

        double scale = 1.0;
        if (entity instanceof Player player) {
            if (player.equals(net.minecraft.client.Minecraft.getInstance().player)) {
                scale = illushine.getPlayerScale();
            } else if (illushine.getScaleOtherPlayers()) {
                scale = illushine.getOtherPlayerScale();
            }
        } else if (entity instanceof Mob mob) {
            scale = illushine.getMobScale(mob);
        }

        if (scale != 1.0) {
            matrices.scale((float) scale, (float) scale, (float) scale);
        }

        illushine$currentEntity.set(null);
    }
}
