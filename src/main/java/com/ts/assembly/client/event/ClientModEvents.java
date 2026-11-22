package com.ts.assembly.client.event;

import com.ts.assembly.TsAssemblyMod;
import com.ts.assembly.client.config.TsAssemblyClientConfig;
import com.ts.assembly.client.util.DynamicFaceOcclusionCache;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

@SuppressWarnings("removal")
@EventBusSubscriber(modid = TsAssemblyMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class ClientModEvents {

    /**
     * 客戶端初始化階段
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // 初始化快取
            TsAssemblyClientConfig.bakeCache();
        });
    }

    /**
     * 當所有模型與 Atlas 貼圖縫合完成時觸發：
     * 1. 遊戲剛啟動進入標題畫面時
     * 2. 玩家切換/調整資源包清單時
     * 3. 遊戲中按下 F3 + T 重新整理材質時
     */
    @SubscribeEvent
    public static void onModelBakingCompleted(ModelEvent.BakingCompleted event) {
        // 1. 清空貼圖 Alpha 像素快取（因為貼圖與 UV 已重新烘焙）
        DynamicFaceOcclusionCache.clear();

        // 2. 重新比對當前已啟用的 3D 資源包清單與規則快取
        TsAssemblyClientConfig.bakeCache();
    }
}