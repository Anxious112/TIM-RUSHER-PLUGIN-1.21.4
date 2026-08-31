package com.example.addon.mixin;

import com.example.addon.utils.GlowingRegistry;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts LevelRenderer.renderEntity() to override the spectral outline
 * color for entities registered in GlowingRegistry.
 */
@Mixin(LevelRenderer.class)
public class EntityGlowingColorMixin {

    @Inject(
        method = "renderEntity",
        at = @At("HEAD")
    )
    private void illushine_overrideOutlineColor(
            Entity entity,
            double cameraX, double cameraY, double cameraZ,
            float tickDelta,
            PoseStack matrices,
            MultiBufferSource vertexConsumers,
            CallbackInfo ci) {

        if (!(vertexConsumers instanceof OutlineBufferSource outline)) return;
        if (!GlowingRegistry.isGlowing(entity.getId())) return;

        int argb = GlowingRegistry.getColor(entity.getId());
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8)  & 0xFF;
        int b =  argb        & 0xFF;
        int a = (argb >> 24) & 0xFF;

        outline.setColor(r, g, b, a);
    }
}
