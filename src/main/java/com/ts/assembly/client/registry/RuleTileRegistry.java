package com.ts.assembly.client.registry;

import com.ts.assembly.TsAssemblyMod;
import com.ts.assembly.client.config.RuleConfig;
import com.ts.assembly.client.config.TsAssemblyClientConfig.BlockMatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Block;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("null")
public class RuleTileRegistry {
    private static volatile Map<Block, List<RuleConfig>> BLOCK_RULES = Map.of();
    private static final List<RuleConfig> RAW_RULES = new ArrayList<>();
    private static final Map<String, ResourceLocation> LOCATION_CACHE = new ConcurrentHashMap<>();

    public static synchronized void clear() {
        RAW_RULES.clear();
    }

    public static synchronized void registerRule(RuleConfig rule) {
        if (rule.match_sprite != null && !rule.match_sprite.isEmpty()) {
            LOCATION_CACHE.computeIfAbsent(rule.match_sprite, ResourceLocation::parse);
        }
        RAW_RULES.add(rule);
    }

    public static synchronized void finishReload() {
        rebuildTags();
    }

    /**
     * 【完全重構】：徹底拔除 glass/pane 硬編碼，統一使用 BlockMatcher 展開
     * 無論是精確 ID、#tag，還是 *萬用字元，皆能在開局與 Tag 更新時精確匹配！
     */
    public static synchronized void rebuildTags() {
        Map<Block, List<RuleConfig>> stagingMap = new HashMap<>();

        for (RuleConfig rule : RAW_RULES) {
            if (rule.target_blocks == null || rule.target_blocks.isEmpty()) continue;

            BlockMatcher matcher = new BlockMatcher(rule.target_blocks);

            for (Block block : BuiltInRegistries.BLOCK) {
                if (matcher.matches(block.defaultBlockState())) {
                    stagingMap.computeIfAbsent(block, b -> new ArrayList<>()).add(rule);
                }
            }
        }

        Map<Block, List<RuleConfig>> immutableCopy = new HashMap<>();
        stagingMap.forEach((block, list) -> immutableCopy.put(block, List.copyOf(list)));
        BLOCK_RULES = Map.copyOf(immutableCopy);
    }

    public static boolean hasRulesFor(Block block) {
        return BLOCK_RULES.containsKey(block);
    }

    public static RuleConfig getMatchedRule(Block block, Direction side, TextureAtlasSprite quadSprite) {
        List<RuleConfig> list = BLOCK_RULES.get(block);
        if (list == null || list.isEmpty()) return null;

        for (int i = 0; i < list.size(); i++) {
            RuleConfig r = list.get(i);
            if (matchesFace(r.faces, side) && matchesSprite(r.match_sprite, quadSprite)) {
                return r;
            }
        }
        return null;
    }

    private static boolean matchesSprite(String matchSprite, TextureAtlasSprite quadSprite) {
        if (matchSprite == null || matchSprite.isEmpty()) return true;
        if (quadSprite == null) return false;

        String spriteName = quadSprite.contents().name().toString();
        if (matchSprite.contains("*")) {
            String[] tokens = Arrays.stream(matchSprite.split("\\*"))
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .toArray(String[]::new);
            return BlockMatcher.matchesSequence(spriteName.toLowerCase(Locale.ROOT), tokens);
        }

        ResourceLocation targetLoc = LOCATION_CACHE.computeIfAbsent(matchSprite, ResourceLocation::parse);
        return quadSprite.contents().name().equals(targetLoc);
    }

    private static boolean matchesFace(List<String> faces, Direction side) {
        if (faces == null || faces.isEmpty() || faces.contains("all")) return true;
        if (side == null) return true;
        String name = side.getSerializedName();
        if (faces.contains(name)) return true;
        if (side == Direction.UP && faces.contains("top")) return true;
        if (side == Direction.DOWN && faces.contains("bottom")) return true;
        if (side.getAxis().isHorizontal() && faces.contains("sides")) return true;
        return false;
    }

    public static ResourceLocation resolveSpriteLocation(String textureName) {
        return LOCATION_CACHE.computeIfAbsent(textureName, name -> {
            if (name.contains(":")) {
                return ResourceLocation.parse(name);
            }
            return ResourceLocation.fromNamespaceAndPath(TsAssemblyMod.MOD_ID, "block/" + name);
        });
    }

    public static TextureAtlasSprite getSprite(String textureName) {
        if (textureName == null || textureName.isEmpty()) return null;
        try {
            ResourceLocation loc = resolveSpriteLocation(textureName);
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return null;

            TextureAtlas atlas = null;

            if (mc.getModelManager() != null) {
                atlas = mc.getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
            }

            if (atlas == null && mc.getTextureManager() != null) {
                AbstractTexture tex = mc.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS);
                if (tex instanceof TextureAtlas ta) {
                    atlas = ta;
                }
            }

            if (atlas != null) {
                TextureAtlasSprite sprite = atlas.getSprite(loc);
                if (sprite != null && !sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
                    return sprite;
                }
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }
}