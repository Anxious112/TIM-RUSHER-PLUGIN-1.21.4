package com.example.addon.mixin;

import java.util.function.Consumer;

import com.example.addon.modules.EightToOne;
import com.example.addon.modules.Gatekeeper;
import org.rusherhack.client.api.RusherHackAPI;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notifies the portal-tracking modules the moment a chunk arrives from the
 * server (or leaves view distance), so they re-scan immediately instead of
 * waiting for their next periodic scan tick.
 */
@Mixin(ClientChunkCache.class)
public abstract class TunnelersMixin {

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void tunnelers$onChunkLoaded(
            int x, int z, FriendlyByteBuf buf, CompoundTag nbt,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
            CallbackInfoReturnable<LevelChunk> cir) {
        LevelChunk chunk = cir.getReturnValue();
        if (chunk == null) return;
        ChunkPos pos = chunk.getPos();

        EightToOne eto = (EightToOne) RusherHackAPI.getModuleManager().getFeature("eight-to-one").orElse(null);
        if (eto != null && eto.isToggled()) eto.markChunkDirty(pos);

        Gatekeeper gk = (Gatekeeper) RusherHackAPI.getModuleManager().getFeature("gatekeeper").orElse(null);
        if (gk != null && gk.isToggled()) gk.markChunkDirty(pos);
    }

    @Inject(method = "drop", at = @At("HEAD"))
    private void tunnelers$onChunkUnloaded(ChunkPos pos, CallbackInfo ci) {
        EightToOne eto = (EightToOne) RusherHackAPI.getModuleManager().getFeature("eight-to-one").orElse(null);
        if (eto != null && eto.isToggled()) eto.markChunkDirty(pos);

        Gatekeeper gk = (Gatekeeper) RusherHackAPI.getModuleManager().getFeature("gatekeeper").orElse(null);
        if (gk != null && gk.isToggled()) gk.markChunkDirty(pos);
    }
}
