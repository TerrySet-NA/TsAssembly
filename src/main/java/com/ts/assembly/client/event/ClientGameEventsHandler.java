package com.ts.assembly.client.event;

import com.ts.assembly.TsAssemblyMod;
import com.ts.assembly.client.config.TsAssemblyClientConfig;
import com.ts.assembly.client.registry.RuleTileRegistry;
import com.ts.assembly.client.util.DynamicFaceOcclusionCache;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

@SuppressWarnings({ "removal" })
@EventBusSubscriber(modid = TsAssemblyMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class ClientGameEventsHandler {

    /**
     * 伺服器同步 Tag 時觸發（更新規則並重編譯區塊）
     */
    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        DynamicFaceOcclusionCache.clear();
        RuleTileRegistry.rebuildTags();
        TsAssemblyClientConfig.bakeCache();

        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
        }
    }

    /**
     * 【關鍵修復】：玩家剛連進單人世界或伺服器的第一瞬間
     * 此時 World、Chunk 與 TextureAtlas 全部就緒，自動執行一次全域區塊刷新！
     * 徹底告別剛進遊戲失效、必須手動按 F3 + T 的困境！
     */
    @SubscribeEvent
    public static void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        DynamicFaceOcclusionCache.clear();
        RuleTileRegistry.rebuildTags();
        TsAssemblyClientConfig.bakeCache();

        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
        }
    }
}