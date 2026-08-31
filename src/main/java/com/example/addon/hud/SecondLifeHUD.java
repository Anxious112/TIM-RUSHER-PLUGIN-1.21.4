package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class SecondLifeHUD extends TimHudElement {

    public SecondLifeHUD() {
        super("SecondLifeHUD", "Shows how many Totems of Undying you are carrying.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        if (mc.player == null) return lines;

        int totems = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.is(Items.TOTEM_OF_UNDYING)) totems += s.getCount();
        }
        if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) totems += mc.player.getOffhandItem().getCount();

        String col = totems <= 0 ? "§c" : totems <= 2 ? "§6" : "§a";
        lines.add("§7Totems: " + col + totems);
        return lines;
    }
}
