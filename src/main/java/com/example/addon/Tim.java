package com.example.addon;

import com.example.addon.hud.BaromineHud;
import com.example.addon.hud.ChambersAssistantHud;
import com.example.addon.hud.CityAssistantHud;
import com.example.addon.hud.DungeonAssistantHud;
import com.example.addon.hud.DuraPanelHUD;
import com.example.addon.hud.EightToOneHUD;
import com.example.addon.hud.EndAssistantHud;
import com.example.addon.hud.GatekeeperHUD;
import com.example.addon.hud.InfoAssistantHud;
import com.example.addon.hud.LastSeenPlayerHud;
import com.example.addon.hud.LootLensHud;
import com.example.addon.hud.MotanceHud;
import com.example.addon.hud.NeighbourhoodWatchHUD;
import com.example.addon.hud.PortalMakerHud;
import com.example.addon.hud.PortalStockHud;
import com.example.addon.hud.PositionHud;
import com.example.addon.hud.RocketPilotHud;
import com.example.addon.hud.SecondLifeHUD;
import com.example.addon.hud.ServerReportHUD;
import com.example.addon.hud.StatisticsInformation;
import com.example.addon.hud.TimeThrottleHUD;
import com.example.addon.commands.DungeonAssistantCommand;
import com.example.addon.commands.LootLensCommand;
import com.example.addon.commands.PortalMakerCommand;
import com.example.addon.commands.RocketPilotCommand;
import com.example.addon.modules.Baromine;
import com.example.addon.modules.ChambersAssistant;
import com.example.addon.modules.CityAssistant;
import com.example.addon.modules.Datamine;
import com.example.addon.modules.DungeonAssistant;
import com.example.addon.modules.Gatekeeper;
import com.example.addon.modules.Graveyard;
import com.example.addon.modules.Handhold;
import com.example.addon.modules.Handmold;
import com.example.addon.modules.EightToOne;
import com.example.addon.modules.ElytraAssistant;
import com.example.addon.modules.Illushine;
import com.example.addon.modules.Inventory101;
import com.example.addon.modules.InspectorGadget;
import com.example.addon.modules.LavaMarker;
import com.example.addon.modules.LootLens;
import com.example.addon.modules.ManorAssistant;
import com.example.addon.modules.Mendbot;
import com.example.addon.modules.Mobanom;
import com.example.addon.modules.NeighbourhoodWatch;
import com.example.addon.modules.Penpal;
import com.example.addon.modules.Raidar;
import com.example.addon.modules.PortalMaker;
import com.example.addon.modules.RocketPilot;
import com.example.addon.modules.SafetyNet;
import com.example.addon.modules.SignScanner;
import com.example.addon.modules.ServerHealthcareSystem;
import com.example.addon.modules.Timethrottle;
import com.example.addon.modules.Tunnelers;
import com.example.addon.modules.ThirdSight;
import com.example.addon.modules.TotalDisposal;
import com.example.addon.modules.Waypearl;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.plugin.Plugin;

public class Tim extends Plugin {

    public static final ModuleCategory CATEGORY = ModuleCategory.getOrRegister("Tim");

    @Override
    public void onLoad() {
        this.getLogger().info("Initializing Tim - Trail Investigator Module");

        RusherHackAPI.getModuleManager().registerFeature(new TotalDisposal());
        RusherHackAPI.getModuleManager().registerFeature(new Handmold());
        RusherHackAPI.getModuleManager().registerFeature(new Penpal());
        RusherHackAPI.getModuleManager().registerFeature(new Graveyard());
        RusherHackAPI.getModuleManager().registerFeature(new ThirdSight());
        RusherHackAPI.getModuleManager().registerFeature(new Illushine());
        RusherHackAPI.getModuleManager().registerFeature(new SafetyNet());
        RusherHackAPI.getModuleManager().registerFeature(new ElytraAssistant());
        RusherHackAPI.getModuleManager().registerFeature(new LavaMarker());
        RusherHackAPI.getModuleManager().registerFeature(new Mobanom());
        RusherHackAPI.getModuleManager().registerFeature(new Timethrottle());
        RusherHackAPI.getModuleManager().registerFeature(new RocketPilot());
        RusherHackAPI.getModuleManager().registerFeature(new Handhold());
        RusherHackAPI.getModuleManager().registerFeature(new ChambersAssistant());
        RusherHackAPI.getModuleManager().registerFeature(new CityAssistant());
        RusherHackAPI.getModuleManager().registerFeature(new Datamine());
        RusherHackAPI.getModuleManager().registerFeature(new PortalMaker());
        RusherHackAPI.getModuleManager().registerFeature(new NeighbourhoodWatch());
        RusherHackAPI.getModuleManager().registerFeature(new Mendbot());
        RusherHackAPI.getModuleManager().registerFeature(new InspectorGadget());
        RusherHackAPI.getModuleManager().registerFeature(new ServerHealthcareSystem());
        RusherHackAPI.getModuleManager().registerFeature(new EightToOne());
        RusherHackAPI.getModuleManager().registerFeature(new Gatekeeper());
        RusherHackAPI.getModuleManager().registerFeature(new Tunnelers());
        RusherHackAPI.getModuleManager().registerFeature(new Waypearl());
        RusherHackAPI.getModuleManager().registerFeature(new ManorAssistant());
        RusherHackAPI.getModuleManager().registerFeature(new Inventory101());
        RusherHackAPI.getModuleManager().registerFeature(new LootLens());
        RusherHackAPI.getModuleManager().registerFeature(new DungeonAssistant());
        RusherHackAPI.getModuleManager().registerFeature(new SignScanner());
        RusherHackAPI.getModuleManager().registerFeature(new Raidar());
        RusherHackAPI.getModuleManager().registerFeature(new Baromine());

        RusherHackAPI.getCommandManager().registerFeature(new PortalMakerCommand());
        RusherHackAPI.getCommandManager().registerFeature(new RocketPilotCommand());
        RusherHackAPI.getCommandManager().registerFeature(new LootLensCommand());
        RusherHackAPI.getCommandManager().registerFeature(new DungeonAssistantCommand());

        RusherHackAPI.getHudManager().registerFeature(new BaromineHud());
        RusherHackAPI.getHudManager().registerFeature(new ChambersAssistantHud());
        RusherHackAPI.getHudManager().registerFeature(new CityAssistantHud());
        RusherHackAPI.getHudManager().registerFeature(new DungeonAssistantHud());
        RusherHackAPI.getHudManager().registerFeature(new DuraPanelHUD());
        RusherHackAPI.getHudManager().registerFeature(new EightToOneHUD());
        RusherHackAPI.getHudManager().registerFeature(new EndAssistantHud());
        RusherHackAPI.getHudManager().registerFeature(new GatekeeperHUD());
        RusherHackAPI.getHudManager().registerFeature(new InfoAssistantHud());
        RusherHackAPI.getHudManager().registerFeature(new LastSeenPlayerHud());
        RusherHackAPI.getHudManager().registerFeature(new LootLensHud());
        RusherHackAPI.getHudManager().registerFeature(new MotanceHud());
        RusherHackAPI.getHudManager().registerFeature(new NeighbourhoodWatchHUD());
        RusherHackAPI.getHudManager().registerFeature(new PortalMakerHud());
        RusherHackAPI.getHudManager().registerFeature(new PortalStockHud());
        RusherHackAPI.getHudManager().registerFeature(new PositionHud());
        RusherHackAPI.getHudManager().registerFeature(new RocketPilotHud());
        RusherHackAPI.getHudManager().registerFeature(new SecondLifeHUD());
        RusherHackAPI.getHudManager().registerFeature(new ServerReportHUD());
        RusherHackAPI.getHudManager().registerFeature(new StatisticsInformation());
        RusherHackAPI.getHudManager().registerFeature(new TimeThrottleHUD());
    }

    @Override
    public void onUnload() {
        this.getLogger().info("Tim unloaded");
    }
}
