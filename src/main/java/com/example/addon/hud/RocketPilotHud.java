package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.modules.RocketPilot;

import org.rusherhack.client.api.RusherHackAPI;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class RocketPilotHud extends TimHudElement {

    public RocketPilotHud() {
        super("RocketPilotHUD", "Displays RocketPilot status, elytra durability and rocket count.");
    }

    @Override
    protected List<String> getLines() {
        List<String> lines = new ArrayList<>();
        Object m = RusherHackAPI.getModuleManager().getFeature("rocket-pilot").orElse(null);
        if (!(m instanceof RocketPilot rp) || !rp.isToggled() || mc.player == null) return lines;

        RocketPilot.FlightPattern pat = rp.flightPattern.getValue();
        String status = pat == RocketPilot.FlightPattern.Manual ? rp.flightMode.getValue().toString() : pat.toString();
        lines.add("§dRocketPilot: §f" + status);

        int elytras = 0, rockets = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.is(Items.ELYTRA)) elytras += s.getCount();
            if (s.is(Items.FIREWORK_ROCKET)) rockets += s.getCount();
        }
        if (mc.player.getOffhandItem().is(Items.FIREWORK_ROCKET)) rockets += mc.player.getOffhandItem().getCount();
        if (mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.ELYTRA)) elytras++;

        String rCol = rockets <= 8 ? "§c" : rockets <= 32 ? "§6" : "§a";
        lines.add("§7Elytras: §f" + elytras);
        lines.add("§7Rockets: " + rCol + rockets);
        return lines;
    }
}
