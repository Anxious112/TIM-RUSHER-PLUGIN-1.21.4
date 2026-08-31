package com.example.addon.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MouseHandler.class)
public interface IMouseAccessor {

    @Accessor("accumulatedDX")
    double getCursorDeltaX();

    @Accessor("accumulatedDY")
    double getCursorDeltaY();
}
