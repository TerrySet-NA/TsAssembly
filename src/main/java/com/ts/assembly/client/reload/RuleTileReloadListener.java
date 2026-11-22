package com.ts.assembly.client.reload;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.blaze3d.platform.NativeImage;
import com.ts.assembly.TsAssemblyMod;
import com.ts.assembly.client.config.RuleConfig;
import com.ts.assembly.client.config.TsAssemblyClientConfig;
import com.ts.assembly.client.registry.RuleTileRegistry;
import com.ts.assembly.client.util.DynamicFaceOcclusionCache;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

@SuppressWarnings("null")
public class RuleTileReloadListener extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public RuleTileReloadListener() {
        super(GSON, "rule_tiles");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        // 1. 重載第一時間清空面遮蔽快取
        DynamicFaceOcclusionCache.clear();

        // 2. 使用當前加載好的 manager 刷新活躍資源包與 Config 快取
        TsAssemblyClientConfig.bakeCache(manager);

        // 3. 清理並重新載入 RuleTile JSON
        RuleTileRegistry.clear();
        resources.forEach((location, json) -> {
            try {
                RuleConfig config = GSON.fromJson(json, RuleConfig.class);
                analyzeSheet(config, manager);
                RuleTileRegistry.registerRule(config);
            } catch (Exception e) {
                TsAssemblyMod.LOGGER.error("[TsAssembly] Failed to parse RuleTile JSON: {}", location, e);
            }
        });
        
        // 4. 完成重載並建立方塊映射
        RuleTileRegistry.finishReload();

        // 【核心修正 2】：規則加載完畢後，強制通知客戶端重新編譯世界區塊網格！
        // 徹底告別初次進入遊戲未渲染、必須手動按 F3 + T 的時序問題
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
        }
    }

    private void analyzeSheet(RuleConfig config, ResourceManager manager) {
        config.isPaneEdge = "PANE_EDGE".equalsIgnoreCase(config.type) || "PANE".equalsIgnoreCase(config.type);

        config.hasOriginalTexture = false;
        if (config.original_texture != null && !config.original_texture.isEmpty()) {
            ResourceLocation origLoc = RuleTileRegistry.resolveSpriteLocation(config.original_texture);
            ResourceLocation origPngPath = ResourceLocation.fromNamespaceAndPath(
                    origLoc.getNamespace(),
                    "textures/" + origLoc.getPath() + ".png"
            );
            config.hasOriginalTexture = manager.getResource(origPngPath).isPresent();
        }

        config.hasMainTexture = false;
        if (config.texture != null && !config.texture.isEmpty()) {
            ResourceLocation spriteLoc = RuleTileRegistry.resolveSpriteLocation(config.texture);
            ResourceLocation pngPath = ResourceLocation.fromNamespaceAndPath(
                    spriteLoc.getNamespace(),
                    "textures/" + spriteLoc.getPath() + ".png"
            );
            config.hasMainTexture = manager.getResource(pngPath).isPresent();
        }

        if ("AUTO".equalsIgnoreCase(config.type)) {
            config.isComposite = true;
        } else {
            config.isComposite = "COMPOSITE".equalsIgnoreCase(config.type);
        }

        int cols = config.isComposite ? 3 : 6;
        int rows = config.isComposite ? 3 : 6;
        
        config.tileModes = new RuleConfig.TileMode[cols][rows];
        RuleConfig.TileMode defaultMode = config.isComposite 
                ? RuleConfig.TileMode.EMPTY 
                : (config.hasOriginalTexture ? RuleConfig.TileMode.ORIGINAL_TEXTURE : RuleConfig.TileMode.FALLBACK_VANILLA);
                
        for (int c = 0; c < cols; c++) {
            Arrays.fill(config.tileModes[c], defaultMode);
        }

        if (config.isPaneEdge || !config.hasMainTexture) {
            return;
        }

        ResourceLocation spriteLoc = RuleTileRegistry.resolveSpriteLocation(config.texture);
        ResourceLocation pngPath = ResourceLocation.fromNamespaceAndPath(
                spriteLoc.getNamespace(),
                "textures/" + spriteLoc.getPath() + ".png"
        );

        Optional<Resource> res = manager.getResource(pngPath);
        if (res.isEmpty()) return;

        try (InputStream is = res.get().open(); NativeImage img = NativeImage.read(is)) {
            int w = img.getWidth();
            int h = img.getHeight();

            if ("AUTO".equalsIgnoreCase(config.type)) {
                config.isComposite = (w == h && (w / 3) == (h / 3));
                cols = config.isComposite ? 3 : 6;
                rows = config.isComposite ? 3 : 6;
                if (config.tileModes.length != cols) {
                    config.tileModes = new RuleConfig.TileMode[cols][rows];
                }
            }

            int tileW = Math.max(1, w / cols);
            int tileH = Math.max(1, h / rows);

            for (int col = 0; col < cols; col++) {
                for (int row = 0; row < rows; row++) {
                    boolean hasPixel = false;
                    for (int x = 0; x < tileW && !hasPixel; x++) {
                        for (int y = 0; y < tileH && !hasPixel; y++) {
                            int pixel = img.getPixelRGBA(col * tileW + x, row * tileH + y);
                            if (((pixel >> 24) & 0xFF) > 0) {
                                hasPixel = true;
                            }
                        }
                    }

                    if (hasPixel) {
                        config.tileModes[col][row] = RuleConfig.TileMode.MAIN;
                    } else {
                        if (config.isComposite) {
                            config.tileModes[col][row] = RuleConfig.TileMode.EMPTY;
                        } else if (config.hasOriginalTexture) {
                            config.tileModes[col][row] = RuleConfig.TileMode.ORIGINAL_TEXTURE;
                        } else {
                            config.tileModes[col][row] = RuleConfig.TileMode.FALLBACK_VANILLA;
                        }
                    }
                }
            }
        } catch (Exception e) {
            TsAssemblyMod.LOGGER.error("[TsAssembly] Failed to read and analyze decal pixels: {}", pngPath, e);
        }
    }
}