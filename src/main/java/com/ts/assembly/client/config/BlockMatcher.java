package com.ts.assembly.client.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 高性能方塊匹配器，支援：
 * 1. 原版精確 ID: "minecraft:stone"
 * 2. 原版精確 Tag: "#minecraft:leaves"
 * 3. 泛用萬用字元: "*glass" (包含即可)
 * 4. 循序多重條件: "*oak*door" (必須同時存在且依序出現)
 * 5. 前綴萬用字元: "#*leaves", "#*oak*leaves", "@*create*gear", "@minecraft:*door"
 */
public class BlockMatcher {

    private final List<Rule> rules = new ArrayList<>();
    
    // BlockState ID -> 匹配結果快取，保障區塊渲染時 O(1) 零開銷
    private final ConcurrentHashMap<BlockState, Boolean> matchCache = new ConcurrentHashMap<>();

    public BlockMatcher() {}

    public BlockMatcher(List<? extends String> patterns) {
        if (patterns != null) {
            for (String pattern : patterns) {
                if (pattern != null && !pattern.isBlank()) {
                    addPattern(pattern.trim());
                }
            }
        }
    }

    public void clear() {
        this.rules.clear();
        this.matchCache.clear();
    }

    /**
     * 判定方塊狀態是否命中規則
     */
    public boolean matches(BlockState state) {
        if (state == null || rules.isEmpty()) return false;
        return matchCache.computeIfAbsent(state, this::evaluateRules);
    }

    private boolean evaluateRules(BlockState state) {
        for (Rule rule : rules) {
            if (rule.matches(state)) {
                return true;
            }
        }
        return false;
    }

    private void addPattern(String raw) {
        int starIdx = raw.indexOf('*');

        // ==========================================
        // 情況 1：無萬用字元，走原版精確匹配
        // ==========================================
        if (starIdx == -1) {
            if (raw.startsWith("#")) {
                String tagStr = raw.substring(1);
                ResourceLocation tagLoc = ResourceLocation.tryParse(tagStr);
                if (tagLoc != null) {
                    TagKey<Block> tagKey = TagKey.create(Registries.BLOCK, tagLoc);
                    rules.add(state -> state.is(tagKey));
                }
            } else {
                ResourceLocation blockLoc = ResourceLocation.tryParse(raw);
                if (blockLoc != null && BuiltInRegistries.BLOCK.containsKey(blockLoc)) {
                    Block block = BuiltInRegistries.BLOCK.get(blockLoc);
                    rules.add(state -> state.is(block));
                }
            }
            return;
        }

        // ==========================================
        // 情況 2：含有萬用字元，解析前綴與依序字串
        // ==========================================
        String prefix = raw.substring(0, starIdx);
        String wildcardPart = raw.substring(starIdx);

        // 將 *1*2 解析為 ["1", "2"]，過濾空字串並全轉小寫
        String[] tokens = Arrays.stream(wildcardPart.split("\\*"))
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toArray(String[]::new);

        if (tokens.length == 0) {
            return;
        }

        // 規則 A：Tag 前綴 (#)
        if (prefix.startsWith("#")) {
            String tagPrefix = prefix.substring(1); // 可能是 "" 或 "c:" 等
            rules.add(state -> {
                for (TagKey<Block> tag : state.getTags().toList()) {
                    String fullTag = tag.location().toString().toLowerCase(Locale.ROOT);
                    String tagPath = tag.location().getPath().toLowerCase(Locale.ROOT);

                    if (!tagPrefix.isEmpty() && !fullTag.startsWith(tagPrefix)) {
                        continue;
                    }
                    if (matchesSequence(fullTag, tokens) || matchesSequence(tagPath, tokens)) {
                        return true;
                    }
                }
                return false;
            });
        }
        // 規則 B：Mod ID / 命名空間前綴 (@)
        else if (prefix.startsWith("@")) {
            String modPrefix = prefix.substring(1); // 可能是 "" 或 "minecraft:"
            rules.add(state -> {
                ResourceLocation loc = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                String namespace = loc.getNamespace().toLowerCase(Locale.ROOT);
                String fullId = loc.toString().toLowerCase(Locale.ROOT);

                if (!modPrefix.isEmpty() && !fullId.startsWith(modPrefix)) {
                    return false;
                }
                return matchesSequence(fullId, tokens) || matchesSequence(namespace, tokens);
            });
        }
        // 規則 C：一般方塊 ID 模糊匹配
        else {
            rules.add(state -> {
                ResourceLocation loc = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                String fullId = loc.toString().toLowerCase(Locale.ROOT); // e.g. "minecraft:oak_door"
                String path = loc.getPath().toLowerCase(Locale.ROOT);       // e.g. "oak_door"

                if (!prefix.isEmpty() && !fullId.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                    return false;
                }
                return matchesSequence(fullId, tokens) || matchesSequence(path, tokens);
            });
        }
    }

    /**
     * 【核心高效率匹配】：檢查 target 是否「同時包含所有 token」且「順序嚴格遞增」
     * 採單指針游標推進，無正則開銷，0 GC 分配
     */
    public static boolean matchesSequence(String target, String[] tokens) {
        if (target == null || tokens == null || tokens.length == 0) return false;

        int cursor = 0;
        int targetLen = target.length();

        for (String token : tokens) {
            int found = target.indexOf(token, cursor);
            if (found == -1) {
                return false; // 缺少必要詞，或順序錯亂
            }
            cursor = found + token.length();
            if (cursor > targetLen) {
                return false;
            }
        }
        return true;
    }

    @FunctionalInterface
    private interface Rule {
        boolean matches(BlockState state);
    }
}