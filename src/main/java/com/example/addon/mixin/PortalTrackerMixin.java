package com.example.addon.mixin;

import com.example.addon.modules.EightToOne;
import com.example.addon.modules.Gatekeeper;
import org.rusherhack.client.api.RusherHackAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks chunks dirty for the portal-tracking modules whenever a portal-related
 * block is placed, so they re-scan immediately instead of on the next timer tick.
 */
@Mixin(Level.class)
public abstract class PortalTrackerMixin {

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("RETURN")
    )
    private void onSetBlock(BlockPos pos, BlockState newState, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return;

        boolean isPortalRelated = newState.is(Blocks.NETHER_PORTAL)
            || newState.is(Blocks.END_PORTAL)
            || newState.is(Blocks.END_GATEWAY)
            || newState.is(Blocks.END_PORTAL_FRAME);

        if (isPortalRelated) {
            EightToOne eto = (EightToOne) RusherHackAPI.getModuleManager().getFeature("eight-to-one").orElse(null);
            if (eto != null && eto.isToggled()) {
                eto.markChunkDirty(new ChunkPos(pos));
            }

            Gatekeeper gk = (Gatekeeper) RusherHackAPI.getModuleManager().getFeature("gatekeeper").orElse(null);
            if (gk != null && gk.isToggled()) {
                gk.markChunkDirty(new ChunkPos(pos));
            }
        }
    }
}
