package com.ts.assembly.client.util;

import com.ts.assembly.TsAssemblyMod;
import com.ts.assembly.client.config.TsAssemblyClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.BlockModelDefinition;
import net.minecraft.client.renderer.block.model.MultiVariant;
import net.minecraft.client.renderer.block.model.multipart.MultiPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@SuppressWarnings("null")
public class FallbackModelHelper {

    private static boolean loggedActivePacks = false;

    @SuppressWarnings("unused")
    public static BakedModel getFallbackBakedModel(ModelResourceLocation mrl, Map<ResourceLocation, TextureAtlasSprite> spriteMap) {
        try {
            // 1. 每輪烘焙只印一次當前抓到的所有活躍資源包
            if (!loggedActivePacks) {
                Set<String> activePacks = TsAssemblyClientConfig.getAllActivePackIds();
                if (TsAssemblyClientConfig.Debug()) TsAssemblyMod.LOGGER.info("[TsAssembly-Debug] ModelBake activePackIds: {}", activePacks);
                loggedActivePacks = true;
            }

            ResourceLocation blockId = mrl.id();
            Block block = BuiltInRegistries.BLOCK.get(blockId);
            if (block == null) return null;

            BlockState defaultState = block.defaultBlockState();
            TsAssemblyClientConfig.PackRuleResult rule = TsAssemblyClientConfig.getMatchedRule(defaultState);

            // 如果該方塊根本沒配規則，直接靜默放行
            if (rule == null) {
                return null;
            }

            // 2. 命中規則時，印出規則狀態
            if (TsAssemblyClientConfig.Debug()) TsAssemblyMod.LOGGER.info("[TsAssembly-Debug] Block: {} matched Rule: [Type: {}, TargetPack: {}, Active: {}]",
                    blockId, rule.type, rule.targetPack, rule.isPackActive);

            if (!rule.isPackActive && rule.type == TsAssemblyClientConfig.RuleType.EXCLUDE) {
                TsAssemblyMod.LOGGER.warn("[TsAssembly-Debug] Block: {} matched EXCLUDE rule for '{}' BUT pack was considered INACTIVE!",
                        blockId, rule.targetPack);
                return null;
            }

            ResourceManager rm = Minecraft.getInstance().getResourceManager();
            if (rm == null) return null;

            Function<Material, TextureAtlasSprite> spriteGetter = mat -> {
                ResourceLocation texLoc = mat.texture();
                if (spriteMap != null && spriteMap.containsKey(texLoc)) {
                    return spriteMap.get(texLoc);
                }
                if (spriteMap != null) {
                    for (Map.Entry<ResourceLocation, TextureAtlasSprite> entry : spriteMap.entrySet()) {
                        if (entry.getKey().getPath().equals(texLoc.getPath())) {
                            return entry.getValue();
                        }
                    }
                    if (!spriteMap.isEmpty()) {
                        return spriteMap.values().iterator().next();
                    }
                }
                return null;
            };

            Map<ResourceLocation, UnbakedModel> modelCache = new HashMap<>();

            ModelBaker baker = new ModelBaker() {
                @Override
                public UnbakedModel getModel(ResourceLocation location) {
                    if (modelCache.containsKey(location)) {
                        return modelCache.get(location);
                    }

                    try {
                        ResourceLocation parentPath = ResourceLocation.fromNamespaceAndPath(
                                location.getNamespace(),
                                "models/" + location.getPath() + ".json"
                        );
                        List<Resource> parentStack = rm.getResourceStack(parentPath);
                        Resource parentRes = getTargetResource(parentStack, rule);

                        if (parentRes != null) {
                            try (Reader r = new InputStreamReader(parentRes.open(), StandardCharsets.UTF_8)) {
                                BlockModel bm = BlockModel.fromStream(r);
                                modelCache.put(location, bm);
                                bm.resolveParents(this::getModel);
                                return bm;
                            }
                        }
                    } catch (Exception ignored) {}

                    modelCache.put(location, null);
                    return null;
                }

                @Override
                public UnbakedModel getTopLevelModel(ModelResourceLocation location) {
                    return getModel(location.id());
                }

                @Override
                public @Nullable BakedModel bake(ResourceLocation location, ModelState state) {
                    return bake(location, state, spriteGetter);
                }

                @Override
                public @Nullable BakedModel bake(ResourceLocation location, ModelState state, Function<Material, TextureAtlasSprite> sprites) {
                    UnbakedModel unbaked = getModel(location);
                    return bakeUncached(unbaked, state, sprites);
                }

                @Override
                public @Nullable BakedModel bakeUncached(UnbakedModel unbaked, ModelState state, Function<Material, TextureAtlasSprite> sprites) {
                    if (unbaked != null) {
                        return unbaked.bake(this, sprites, state);
                    }
                    return null;
                }

                @Override
                public Function<Material, TextureAtlasSprite> getModelTextureGetter() {
                    return spriteGetter;
                }
            };

            // 1. 方案 A：玻璃片（Multipart / Variants）從 blockstates 載入
            ResourceLocation bsLocation = ResourceLocation.fromNamespaceAndPath(
                    mrl.id().getNamespace(),
                    "blockstates/" + mrl.id().getPath() + ".json"
            );
            List<Resource> bsStack = rm.getResourceStack(bsLocation);
            Resource targetBs = getTargetResource(bsStack, rule);

            if (targetBs != null) {
                try (Reader reader = new InputStreamReader(targetBs.open(), StandardCharsets.UTF_8)) {
                    BlockModelDefinition.Context context = new BlockModelDefinition.Context();
                    context.setDefinition(block.getStateDefinition());
                    BlockModelDefinition def = BlockModelDefinition.fromStream(context, reader);

                    if (def.isMultiPart()) {
                        MultiPart mp = def.getMultiPart();
                        mp.resolveParents(baker::getModel);
                        return mp.bake(baker, spriteGetter, BlockModelRotation.X0_Y0);
                    } else if (def.getVariants().containsKey(mrl.variant())) {
                        MultiVariant mv = def.getVariants().get(mrl.variant());
                        mv.resolveParents(baker::getModel);
                        return mv.bake(baker, spriteGetter, BlockModelRotation.X0_Y0);
                    } else if (def.getVariants().containsKey("")) {
                        MultiVariant mv = def.getVariants().get("");
                        mv.resolveParents(baker::getModel);
                        return mv.bake(baker, spriteGetter, BlockModelRotation.X0_Y0);
                    }
                } catch (Exception ignored) {}
            }

            // 2. 方案 B：完整方塊從 models/block 載入
            ResourceLocation modelLocation = ResourceLocation.fromNamespaceAndPath(
                    mrl.id().getNamespace(),
                    "models/block/" + mrl.id().getPath() + ".json"
            );
            List<Resource> stack = rm.getResourceStack(modelLocation);
            Resource targetResource = getTargetResource(stack, rule);

            if (targetResource != null) {
                try (Reader reader = new InputStreamReader(targetResource.open(), StandardCharsets.UTF_8)) {
                    BlockModel unbaked = BlockModel.fromStream(reader);
                    unbaked.resolveParents(baker::getModel);
                    return unbaked.bake(baker, spriteGetter, BlockModelRotation.X0_Y0);
                }
            }

        } catch (Exception e) {
            TsAssemblyMod.LOGGER.warn("[TsAssembly] Failed to parse model rules for {}: {}", mrl, e.getMessage());
        }
        return null;
    }

    public static void resetDebugFlag() {
        loggedActivePacks = false;
    }

    private static Resource getTargetResource(List<Resource> stack, TsAssemblyClientConfig.PackRuleResult rule) {
        if (stack == null || stack.isEmpty()) return null;
        if (rule == null) return stack.get(stack.size() - 1);

        if (rule.type == TsAssemblyClientConfig.RuleType.LOCK) {
            // 無條件鎖定原版 (targetPack 為空) 或未啟用該包：直接強制回歸底層原版
            if (rule.targetPack == null || rule.targetPack.isEmpty() || !rule.isPackActive) {
                return stack.get(0);
            }
            // 啟用該包：鎖定自該包載入
            for (int i = stack.size() - 1; i >= 0; i--) {
                Resource res = stack.get(i);
                if (TsAssemblyClientConfig.isPackNameMatch(res.sourcePackId(), rule.targetPack)) {
                    return res;
                }
            }
            return stack.get(0);
        } else if (rule.type == TsAssemblyClientConfig.RuleType.EXCLUDE) {
            // 去除該包：跳過該包，取前一個包或原版
            for (int i = stack.size() - 1; i >= 0; i--) {
                Resource res = stack.get(i);
                if (!TsAssemblyClientConfig.isPackNameMatch(res.sourcePackId(), rule.targetPack)) {
                    return res;
                }
            }
            return stack.get(0);
        }
        return stack.get(stack.size() - 1);
    }
}