package com.ts.assembly.client.model;

import com.ts.assembly.TsAssemblyMod;
import com.ts.assembly.client.config.TsAssemblyClientConfig;
import com.ts.assembly.client.util.EarlyTagResolver;
import com.ts.assembly.client.util.FallbackModelHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings({ "null", "removal" })
@EventBusSubscriber(modid = TsAssemblyMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class ModelBakeHandler {

    @SubscribeEvent
    public static void onModelBake(ModelEvent.ModifyBakingResult event) {
        FallbackModelHelper.resetDebugFlag();

        try {
            EarlyTagResolver.init();
        } catch (Exception e) {
            TsAssemblyMod.LOGGER.error("[TsAssembly] Failed to early resolve tags:", e);
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            ResourceManager rm = mc.getResourceManager();
            if (rm != null) {
                // 【核心修正 1】：在模型替換的第一時間，直接使用當前活體 ResourceManager 烘焙規則與活躍包清單
                TsAssemblyClientConfig.bakeCache(rm);
            }
        }

        var models = event.getModels();

        // 1. 預先從已烘焙的模型池中收集所有可用的 Sprite，避免 ModelManager 未初始化
        Map<ResourceLocation, TextureAtlasSprite> spriteMap = new HashMap<>();
        RandomSource rand = RandomSource.create(42L);
        for (BakedModel bm : models.values()) {
            if (bm == null) continue;
            try {
                TextureAtlasSprite particle = bm.getParticleIcon(ModelData.EMPTY);
                if (particle != null) {
                    spriteMap.put(particle.contents().name(), particle);
                }
                for (Direction d : Direction.values()) {
                    for (BakedQuad q : bm.getQuads(null, d, rand, ModelData.EMPTY, null)) {
                        if (q.getSprite() != null) {
                            spriteMap.put(q.getSprite().contents().name(), q.getSprite());
                        }
                    }
                }
                for (BakedQuad q : bm.getQuads(null, null, rand, ModelData.EMPTY, null)) {
                    if (q.getSprite() != null) {
                        spriteMap.put(q.getSprite().contents().name(), q.getSprite());
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. 執行模型替換與 3D 包回退處理
        for (ModelResourceLocation key : new ArrayList<>(models.keySet())) {
            if ("inventory".equals(key.variant())) {
                continue;
            }

            if (key.id().getPath().startsWith("item/")) {
                continue;
            }

            BakedModel originalModel = models.get(key);
            if (originalModel != null && !(originalModel instanceof RuleTileBakedModel)) {
                BakedModel fallbackModel = FallbackModelHelper.getFallbackBakedModel(key, spriteMap);
                BakedModel modelToWrap = (fallbackModel != null) ? fallbackModel : originalModel;

                models.put(key, new RuleTileBakedModel(modelToWrap));
            }
        }
    }
}