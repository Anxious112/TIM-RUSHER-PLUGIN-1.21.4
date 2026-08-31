package com.example.addon.mixin;

import com.example.addon.modules.Datamine;
import org.rusherhack.client.api.RusherHackAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Redirects vanilla block breaking to Datamine.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class DatamineMixin {

    @Shadow
    private int destroyDelay;

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void onAttack(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> info) {
        this.datamine$mine(pos, side, info);
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void onUpdate(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> info) {
        this.datamine$mine(pos, side, info);
    }

    @Unique
    private void datamine$mine(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> info) {
        Minecraft client = Minecraft.getInstance();
        Datamine module = (Datamine) RusherHackAPI.getModuleManager().getFeature("datamine").orElse(null);

        if (module == null || !module.isToggled() ||
            client.player == null || client.player.getAbilities().instabuild) {
            return;
        }

        if (module.bypass(pos)) {
            // If the block is fast enough to bypass the queue, let vanilla handle it
            // but remove the cooldown so it breaks instantly.
            this.destroyDelay = 0;
            return;
        }

        // Otherwise, route it into the Datamine packet queue
        module.mine(pos, side);
        info.setReturnValue(true);
    }
}
