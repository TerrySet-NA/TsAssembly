package com.ts.assembly;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.ts.assembly.client.ModPackFinder;
import com.ts.assembly.client.TsAssemblyExportCommand;
import com.ts.assembly.client.config.TsAssemblyClientConfig;
import com.ts.assembly.client.reload.RuleTileReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(TsAssemblyMod.MOD_ID)
public class TsAssemblyMod {
    public static final String MOD_ID = "tsassembly";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TsAssemblyMod(IEventBus modEventBus, ModContainer modContainer) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }

        // 1. 註冊 Client 端全域設定檔 (.minecraft/config/tsassembly-client.toml)
        modContainer.registerConfig(ModConfig.Type.CLIENT, TsAssemblyClientConfig.SPEC);
        modEventBus.addListener(this::onConfigLoad);
        modEventBus.addListener(this::onConfigReload);

        // 註冊資源包重載監聽器 (讀取 rule_tiles/*.json)
        modEventBus.addListener(this::onRegisterReloadListeners);

        // 註冊內建可選資源包
        modEventBus.addListener(ModPackFinder::onAddPackFinders);

        // 註冊純客戶端指令
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == TsAssemblyClientConfig.SPEC) {
            TsAssemblyClientConfig.bakeCache();
        }
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == TsAssemblyClientConfig.SPEC) {
            TsAssemblyClientConfig.bakeCache();
        }
    }

    private void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new RuleTileReloadListener());
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        TsAssemblyExportCommand.register(event.getDispatcher(), event.getBuildContext());
    }
}