package com.example.addon.mixin;

import com.example.addon.modules.ElytraAssistant;
import com.example.addon.modules.RocketPilot;
import org.rusherhack.client.api.RusherHackAPI;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public class ElytraAssistantSwingMixin {
    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;)V", at = @At("HEAD"), cancellable = true)
    private void onSwingHand(InteractionHand hand, CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        ItemStack stack = player.getItemInHand(hand);
        if (stack.is(Items.FIREWORK_ROCKET)) {
            ElytraAssistant ea = (ElytraAssistant) RusherHackAPI.getModuleManager().getFeature("elytra-assistant").orElse(null);
            if (ea != null && ea.shouldSilentRocket()) {
                ci.cancel();
                return;
            }

            RocketPilot rp = (RocketPilot) RusherHackAPI.getModuleManager().getFeature("rocket-pilot").orElse(null);
            if (rp != null && rp.isToggled() && rp.silentRockets.getValue()) {
                ci.cancel();
            }
        }
    }
}
