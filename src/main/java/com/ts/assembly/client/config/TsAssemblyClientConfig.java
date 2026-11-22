package com.ts.assembly.client.config;

import com.ts.assembly.TsAssemblyMod;
import com.ts.assembly.client.util.EarlyTagResolver;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@SuppressWarnings({ "null", "deprecation", "removal" })
@EventBusSubscriber(modid = TsAssemblyMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class TsAssemblyClientConfig {
    public static final ModConfigSpec SPEC;
    public static final TsAssemblyClientConfig INSTANCE;

    // 既有基礎設定
    public final ModConfigSpec.ConfigValue<List<? extends String>> relativePositionBlocks;
    public final ModConfigSpec.ConfigValue<List<? extends String>> fakeCullBlocks;
    public final ModConfigSpec.ConfigValue<Boolean> enableFakeCullBlocks;
    public final ModConfigSpec.ConfigValue<List<? extends String>> invertBackfaceBlocks;
    public final ModConfigSpec.ConfigValue<List<? extends String>> compatible3DPacks;
    public final ModConfigSpec.ConfigValue<List<? extends String>> packBlockExclusions;

    // 【1. 幾何內部朝內貼圖剔除】
    public final ModConfigSpec.ConfigValue<Boolean> enableCullInwardFaces;
    public final ModConfigSpec.ConfigValue<List<? extends String>> cullInwardFaceBlocks;

    // 【2. 面對相鄰方塊剔除】
    public final ModConfigSpec.ConfigValue<List<? extends String>> cullFacingBlocks;
    public final ModConfigSpec.ConfigValue<Boolean> cullFacingIncompleteBlocks;

    // 【完全遮蔽貼合剔除】：全域預設生效，黑名單機制
    public final ModConfigSpec.ConfigValue<Boolean> cullFacingFullyOccluded;
    public final ModConfigSpec.ConfigValue<List<? extends String>> cullFacingFullyOccludedBlacklist;

    // 【3. 同方塊內部/相鄰接觸面剔除】
    public final ModConfigSpec.ConfigValue<Boolean> cullSameBlockFaces;
    public final ModConfigSpec.ConfigValue<List<? extends String>> cullSameBlockList;

    public final ModConfigSpec.ConfigValue<Boolean> enableDebug;

    // 快取物件
    private static BlockMatcher relativePositionMatcher = new BlockMatcher();
    private static BlockMatcher fakeCullMatcher = new BlockMatcher();
    private static BlockMatcher invertBackfaceMatcher = new BlockMatcher();
    private static Set<String> active3DPackNames = Collections.emptySet();
    private static PackBlockMatcher exclusionMatcher = new PackBlockMatcher(Collections.emptyList());
    private static boolean enableFakeCullBlockSet = false;

    // 新增剔除快取
    private static BlockMatcher cullInwardFaceMatcher = new BlockMatcher();
    private static BlockMatcher cullFacingMatcher = new BlockMatcher();
    private static BlockMatcher cullSameBlockMatcher = new BlockMatcher();
    private static boolean enableCullInwardFaceSet = true;
    private static boolean cullFacingIncompleteBlocksSet = false;
    private static boolean cullFacingFullyOccludedSet = true;
    private static BlockMatcher cullFacingFullyOccludedBlacklistMatcher = new BlockMatcher();
    private static boolean cullSameBlockFacesSet = true;
    private static boolean enableDebugSet = false;

    public enum RuleType {
        EXCLUDE, // '='  : 該包啟用時排除，退回前一個包或原版
        LOCK     // '!=' : 鎖定以該包載入 (若寫 '!=方塊' 前方為空，則無條件強制鎖定原版)
    }

    public static class PackRuleResult {
        public final RuleType type;
        public final String targetPack;
        public final boolean isPackActive;

        public PackRuleResult(RuleType type, String targetPack, boolean isPackActive) {
            this.type = type;
            this.targetPack = targetPack;
            this.isPackActive = isPackActive;
        }
    }

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        INSTANCE = new TsAssemblyClientConfig(builder);
        SPEC = builder.build();
    }

    private TsAssemblyClientConfig(ModConfigSpec.Builder builder) {
        builder.comment("TsAssembly Rendering and Compatibility Settings")
                .translation("tsassembly.configuration.section.rendering")
                .push("rendering");

        relativePositionBlocks = builder
                .comment("Evaluate backface flip based on vertex-to-center offset (Supports #tag, *wildcard)")
                .translation("tsassembly.configuration.relative_position_blocks")
                .defineListAllowEmpty("relative_position_blocks", Collections.emptyList(), o -> o instanceof String);

        enableFakeCullBlocks = builder
                .comment("Allow dual-sided rendering (FakeCull)")
                .translation("tsassembly.configuration.enable_fake_cull_blocks")
                .define("enable_fake_cull_blocks", false);

        fakeCullBlocks = builder
                .comment("Blocks or tags to receive automatic internal backface quads (Supports #tag, *wildcard)")
                .translation("tsassembly.configuration.fake_cull_blocks")
                .defineListAllowEmpty("fake_cull_blocks", List.of(
                        "#c:glass_blocks"
                ), o -> o instanceof String);

        invertBackfaceBlocks = builder
                .comment("Invert generated backface UV horizontally/vertically to remove mirroring (Supports #tag, *wildcard)")
                .translation("tsassembly.configuration.invert_backface_blocks")
                .defineListAllowEmpty("invert_backface_blocks", List.of(
                        "#c:glass_blocks"
                ), o -> o instanceof String);

        compatible3DPacks = builder
                .comment("Enable 3D resource pack compatibility (Zip filename, supports *wildcard, e.g. '*3D Default')")
                .translation("tsassembly.configuration.compatible_3d_packs")
                .defineListAllowEmpty("compatible_3d_packs", List.of(
                        "*3D Default"
                ), o -> o instanceof String);

        packBlockExclusions = builder
                .comment("Resource pack model routing rules (Supports *wildcard on pack names and block patterns):\n" +
                        "1. Exclusion: 'Pack.zip=BlockOrTag' (Strips 3D model, falls back to prior pack/vanilla)\n" +
                        "2. Whitelist: 'Pack.zip!=BlockOrTag' (Locks model to Pack.zip; forces vanilla if disabled)")
                .translation("tsassembly.configuration.pack_block_exclusions")
                .defineListAllowEmpty("pack_block_exclusions", List.of(
                        "*3D Default=#c:glass_blocks", "*3D Default=#c:glass_panes"
                ), o -> o instanceof String);

        enableCullInwardFaces = builder
                .comment("【剔除內部朝內面】：自動檢測並完全剔除朝向方塊幾何內部的 Quad 貼圖")
                .translation("tsassembly.configuration.enable_cull_inward_faces")
                .define("enable_cull_inward_faces", true);

        cullInwardFaceBlocks = builder
                .comment("剔除幾何朝內貼圖的方塊/Tag 清單 (支援 #tag, *萬用字元，例如 '#*leaves')")
                .translation("tsassembly.configuration.cull_inward_face_blocks")
                .defineListAllowEmpty("cull_inward_face_blocks", List.of(
                        "#minecraft:leaves"
                ), o -> o instanceof String);

        cullFacingBlocks = builder
                .comment("啟用面朝相鄰方塊剔除的目標清單 (支援 #tag, *萬用字元，例如 '#*leaves')")
                .translation("tsassembly.configuration.cull_facing_blocks")
                .defineListAllowEmpty("cull_facing_blocks", List.of(
                        "#minecraft:leaves"
                ), o -> o instanceof String);

        cullFacingIncompleteBlocks = builder
                .comment("是否面對任意不完整方塊皆剔除：\n" +
                        "true  = 只要相鄰不是空氣（即使只是一截火把、欄杆、半磚），一律剔除接觸面\n" +
                        "false = 僅在相鄰符合完整方塊或完全遮蔽貼合時剔除")
                .translation("tsassembly.configuration.cull_facing_incomplete_blocks")
                .define("cull_facing_incomplete_blocks", false);

        cullFacingFullyOccluded = builder
                .comment("【完全遮蔽貼合剔除】：全域預設開啟。相鄰方塊若非完整方塊（如實心地板門、半磚），但在接觸面完全實心平貼且貼圖無孔時，一律剔除接觸面")
                .translation("tsassembly.configuration.cull_facing_fully_occluded")
                .define("cull_facing_fully_occluded", true);

        cullFacingFullyOccludedBlacklist = builder
                .comment("【完全遮蔽貼合剔除】黑名單：不執行完全遮蔽剔除的方塊/Tag 清單 (預設為空，支援 #tag, *萬用字元)")
                .translation("tsassembly.configuration.cull_facing_fully_occluded_blacklist")
                .defineListAllowEmpty("cull_facing_fully_occluded_blacklist", Collections.emptyList(), o -> o instanceof String);

        cullSameBlockFaces = builder
                .comment("【同方塊內部接觸面剔除】：相鄰方塊若為同種方塊或同屬該 Tag 時，是否剔除兩者之間的內部接觸面")
                .translation("tsassembly.configuration.cull_same_block_faces")
                .define("cull_same_block_faces", true);

        cullSameBlockList = builder
                .comment("啟用同方塊內部接觸面剔除的目標清單 (支援 #tag, *萬用字元，例如 '#*leaves')")
                .translation("tsassembly.configuration.cull_same_block_list")
                .defineListAllowEmpty("cull_same_block_list", List.of(
                        "#minecraft:leaves"
                ), o -> o instanceof String);

        enableDebug = builder
                .comment("Enable Debug Output")
                .define("enable_debug_output", false);

        builder.pop();
    }

    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            bakeCache();
        }
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            bakeCache();
        }
    }

    private static List<? extends String> getSafeList(ModConfigSpec.ConfigValue<List<? extends String>> val) {
        try {
            if (SPEC.isLoaded()) return val.get();
        } catch (Exception ignored) {}
        return val.getDefault();
    }

    private static boolean getSafeBoolean(ModConfigSpec.ConfigValue<Boolean> val) {
        try {
            if (SPEC.isLoaded()) return Boolean.TRUE.equals(val.get());
        } catch (Exception ignored) {}
        return Boolean.TRUE.equals(val.getDefault());
    }

    private static volatile Set<String> lastKnownActivePacks = Collections.emptySet();

    public static void bakeCache() {
        bakeCache(null);
    }

    public static synchronized void bakeCache(ResourceManager manager) {
        if (INSTANCE == null) return;

        Set<String> activePacks = new LinkedHashSet<>();
        if (manager != null) {
            manager.listPacks().forEach(p -> {
                if (p != null && p.packId() != null) {
                    activePacks.add(p.packId());
                }
            });
        }
        
        if (activePacks.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                if (mc.getResourceManager() != null) {
                    mc.getResourceManager().listPacks().forEach(p -> {
                        if (p != null && p.packId() != null) activePacks.add(p.packId());
                    });
                }
                PackRepository repo = mc.getResourcePackRepository();
                if (repo != null && repo.getSelectedIds() != null) {
                    activePacks.addAll(repo.getSelectedIds());
                }
            }
        }

        if (!activePacks.isEmpty()) {
            lastKnownActivePacks = Collections.unmodifiableSet(activePacks);
        }

        Set<String> set = new HashSet<>();
        List<? extends String> compPacks = getSafeList(INSTANCE.compatible3DPacks);
        if (compPacks != null) {
            for (String s : compPacks) {
                if (s != null && !s.isBlank()) {
                    set.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        active3DPackNames = Collections.unmodifiableSet(set);

        List<? extends String> relList = getSafeList(INSTANCE.relativePositionBlocks);
        relativePositionMatcher = new BlockMatcher(relList != null ? relList : Collections.emptyList());

        List<? extends String> fakeList = getSafeList(INSTANCE.fakeCullBlocks);
        fakeCullMatcher = new BlockMatcher(fakeList != null ? fakeList : Collections.emptyList());

        List<? extends String> invList = getSafeList(INSTANCE.invertBackfaceBlocks);
        invertBackfaceMatcher = new BlockMatcher(invList != null ? invList : Collections.emptyList());

        enableFakeCullBlockSet = getSafeBoolean(INSTANCE.enableFakeCullBlocks);

        List<? extends String> exclList = getSafeList(INSTANCE.packBlockExclusions);
        exclusionMatcher = new PackBlockMatcher(exclList != null ? exclList : Collections.emptyList());

        enableCullInwardFaceSet = getSafeBoolean(INSTANCE.enableCullInwardFaces);
        cullFacingIncompleteBlocksSet = getSafeBoolean(INSTANCE.cullFacingIncompleteBlocks);
        cullFacingFullyOccludedSet = getSafeBoolean(INSTANCE.cullFacingFullyOccluded);
        cullSameBlockFacesSet = getSafeBoolean(INSTANCE.cullSameBlockFaces);

        List<? extends String> inwardList = getSafeList(INSTANCE.cullInwardFaceBlocks);
        cullInwardFaceMatcher = new BlockMatcher(inwardList != null ? inwardList : Collections.emptyList());

        List<? extends String> facingList = getSafeList(INSTANCE.cullFacingBlocks);
        cullFacingMatcher = new BlockMatcher(facingList != null ? facingList : Collections.emptyList());

        List<? extends String> fullyOccludedBlacklist = getSafeList(INSTANCE.cullFacingFullyOccludedBlacklist);
        cullFacingFullyOccludedBlacklistMatcher = new BlockMatcher(fullyOccludedBlacklist != null ? fullyOccludedBlacklist : Collections.emptyList());

        List<? extends String> sameBlockList = getSafeList(INSTANCE.cullSameBlockList);
        cullSameBlockMatcher = new BlockMatcher(sameBlockList != null ? sameBlockList : Collections.emptyList());

        enableDebugSet = getSafeBoolean(INSTANCE.enableDebug);
    }

    public static Set<String> getAllActivePackIds() {
        if (!lastKnownActivePacks.isEmpty()) {
            return lastKnownActivePacks;
        }

        Set<String> set = new LinkedHashSet<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            if (mc.getResourceManager() != null) {
                mc.getResourceManager().listPacks().forEach(p -> {
                    if (p != null && p.packId() != null) set.add(p.packId());
                });
            }
            PackRepository repo = mc.getResourcePackRepository();
            if (repo != null && repo.getSelectedIds() != null) {
                set.addAll(repo.getSelectedIds());
            }
        }
        if (!set.isEmpty()) {
            lastKnownActivePacks = Collections.unmodifiableSet(set);
        }
        return set;
    }

    public static boolean shouldCullFacingFullyOccluded(BlockState state) {
        if (!cullFacingFullyOccludedSet || state == null) return false;
        return !cullFacingFullyOccludedBlacklistMatcher.matches(state);
    }

    public static boolean isRelativePositionBlock(BlockState state) {
        return state != null && relativePositionMatcher.matches(state);
    }

    public static boolean isFakeCullBlock(BlockState state) {
        return enableFakeCullBlockSet && state != null && fakeCullMatcher.matches(state);
    }

    public static boolean isInvertBackfaceBlock(BlockState state) {
        return state != null && invertBackfaceMatcher.matches(state);
    }

    public static boolean isCullInwardFaceBlock(BlockState state) {
        return enableCullInwardFaceSet && state != null && cullInwardFaceMatcher.matches(state);
    }

    public static boolean isCullFacingBlock(BlockState state) {
        return state != null && cullFacingMatcher.matches(state);
    }

    public static boolean shouldCullFacingIncomplete() {
        return cullFacingIncompleteBlocksSet;
    }

    public static boolean shouldCullFacingFullyOccluded() {
        return cullFacingFullyOccludedSet;
    }

    public static boolean Debug() {
        return enableDebugSet;
    }

    public static boolean shouldCullSameBlock(BlockState selfState, BlockState neighborState) {
        if (!cullSameBlockFacesSet || selfState == null || neighborState == null) return false;
        
        if (!cullSameBlockMatcher.matches(selfState)) return false;
        if (selfState.is(neighborState.getBlock())) return true;
        return cullSameBlockMatcher.matchesSameTag(selfState, neighborState);
    }

    public static boolean is3DCompatibilityActive() {
        if (active3DPackNames.isEmpty()) return false;
        Set<String> activeIds = getAllActivePackIds();
        for (String packId : activeIds) {
            for (String target : active3DPackNames) {
                if (PackBlockMatcher.isPackFileExactMatch(packId, target)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static PackRuleResult getMatchedRule(BlockState state) {
        if (state == null) return null;
        Set<String> activeIds = getAllActivePackIds();
        return exclusionMatcher.getMatchedRule(state, activeIds);
    }

    public static boolean is3DCompatibilityActiveFor(BlockState state) {
        PackRuleResult rule = getMatchedRule(state);
        if (rule != null) {
            if (rule.type == RuleType.EXCLUDE && rule.isPackActive) {
                return false;
            }
            if (rule.type == RuleType.LOCK) {
                if (rule.targetPack.isEmpty() || !rule.isPackActive) {
                    return false;
                }
                for (String target : active3DPackNames) {
                    if (PackBlockMatcher.isPackFileExactMatch(rule.targetPack, target)) {
                        return true;
                    }
                }
                return false;
            }
        }

        return is3DCompatibilityActive();
    }

    public static String getMatchedExcludedPack(BlockState state) {
        PackRuleResult res = getMatchedRule(state);
        if (res != null && res.type == RuleType.EXCLUDE && res.isPackActive) {
            return res.targetPack;
        }
        return null;
    }

    public static boolean isPackNameMatch(String packId, String targetZipName) {
        return PackBlockMatcher.isPackFileExactMatch(packId, targetZipName);
    }

    // ==========================================
    // 增強型 Block / Tag / 萬用字元匹配器 (修復 Pair 存取)
    // ==========================================
    public static class BlockMatcher {
        private final Set<Block> exactBlocks = new HashSet<>();
        private final Set<TagKey<Block>> exactTagKeys = new HashSet<>();
        private final List<Predicate<BlockState>> dynamicRules = new ArrayList<>();
        private final List<TagPatternRule> tagPatternRules = new ArrayList<>();

        private final ConcurrentHashMap<BlockState, Boolean> matchCache = new ConcurrentHashMap<>();

        public record TagPatternRule(String prefix, String[] tokens) {
            public boolean matchesTag(TagKey<Block> tag) {
                String fullTag = tag.location().toString().toLowerCase(Locale.ROOT);
                String path = tag.location().getPath().toLowerCase(Locale.ROOT);

                if (!prefix.isEmpty() && !fullTag.startsWith(prefix)) {
                    return false;
                }
                return matchesSequence(fullTag, tokens) || matchesSequence(path, tokens);
            }
        }

        public BlockMatcher() {}

        public BlockMatcher(Collection<? extends String> entries) {
            addAll(entries);
        }

        public void clear() {
            exactBlocks.clear();
            exactTagKeys.clear();
            dynamicRules.clear();
            tagPatternRules.clear();
            matchCache.clear();
        }

        public void add(String entry) {
            if (entry == null || entry.isBlank()) return;
            entry = entry.trim();
            matchCache.clear();

            int starIdx = entry.indexOf('*');

            // 1. 無萬用字元
            if (starIdx == -1) {
                try {
                    if (entry.startsWith("#")) {
                        String tagPath = entry.substring(1);
                        ResourceLocation tagLoc = ResourceLocation.tryParse(tagPath);
                        if (tagLoc != null) {
                            exactTagKeys.add(TagKey.create(Registries.BLOCK, tagLoc));
                        }
                    } else {
                        ResourceLocation blockLoc = ResourceLocation.tryParse(entry);
                        if (blockLoc != null && BuiltInRegistries.BLOCK.containsKey(blockLoc)) {
                            Block block = BuiltInRegistries.BLOCK.get(blockLoc);
                            if (block != null) {
                                exactBlocks.add(block);
                            }
                        }
                    }
                } catch (Exception ignored) {}
                return;
            }

            // 2. 萬用字元與順序多重匹配 (*內容 / *1*2)
            String prefix = entry.substring(0, starIdx).trim();
            String wildcardPart = entry.substring(starIdx);

            String[] tokens = Arrays.stream(wildcardPart.split("\\*"))
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .toArray(String[]::new);

            if (tokens.length == 0) return;

            if (prefix.startsWith("#")) {
                String tagPrefix = prefix.substring(1).toLowerCase(Locale.ROOT);
                TagPatternRule tagRule = new TagPatternRule(tagPrefix, tokens);
                tagPatternRules.add(tagRule);

                dynamicRules.add(state -> {
                    // A. 標準已綁定狀態比對
                    for (TagKey<Block> tag : state.getTags().toList()) {
                        if (tagRule.matchesTag(tag)) {
                            return true;
                        }
                    }
                    // B. 模型烘焙早期向註冊表直查 (Mojang Pair 使用 getFirst / getSecond)
                    return BuiltInRegistries.BLOCK.getTags().anyMatch(pair -> {
                        TagKey<Block> tagKey = pair.getFirst();
                        if (tagRule.matchesTag(tagKey)) {
                            return pair.getSecond().contains(state.getBlock().builtInRegistryHolder());
                        }
                        return false;
                    });
                });
            } else if (prefix.startsWith("@")) {
                String modPrefix = prefix.substring(1).toLowerCase(Locale.ROOT);
                dynamicRules.add(state -> {
                    ResourceLocation loc = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    String fullId = loc.toString().toLowerCase(Locale.ROOT);
                    String namespace = loc.getNamespace().toLowerCase(Locale.ROOT);
                    String path = loc.getPath().toLowerCase(Locale.ROOT);

                    if (!modPrefix.isEmpty()) {
                        if (!namespace.startsWith(modPrefix) && !fullId.startsWith(modPrefix)) {
                            return false;
                        }
                    }
                    return matchesSequence(fullId, tokens) || matchesSequence(path, tokens) || matchesSequence(namespace, tokens);
                });
            } else {
                String idPrefix = prefix.toLowerCase(Locale.ROOT);
                dynamicRules.add(state -> {
                    ResourceLocation loc = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    String fullId = loc.toString().toLowerCase(Locale.ROOT);
                    String path = loc.getPath().toLowerCase(Locale.ROOT);

                    if (!idPrefix.isEmpty() && !fullId.startsWith(idPrefix)) {
                        return false;
                    }
                    return matchesSequence(fullId, tokens) || matchesSequence(path, tokens);
                });
            }
        }

        public void addAll(Collection<? extends String> entries) {
            if (entries == null) return;
            for (String entry : entries) {
                add(entry);
            }
        }

        public boolean matches(BlockState state) {
            if (state == null || isEmpty()) return false;
            return matchCache.computeIfAbsent(state, this::evaluateState);
        }

        private boolean evaluateState(BlockState state) {
            Block block = state.getBlock();

            // 1. 原版精確 Block 比對
            if (exactBlocks.contains(block)) return true;

            // 2. 原版精確 Tag 比對
            for (TagKey<Block> tag : exactTagKeys) {
                // 原版已綁定時
                if (state.is(tag)) return true;

                // 原版未綁定時（模型烘焙早期）向 EarlyTagResolver 查！
                if (EarlyTagResolver.contains(tag, block)) {
                    return true;
                }
            }

            // 3. 通用動態規則比對
            for (Predicate<BlockState> rule : dynamicRules) {
                if (rule.test(state)) return true;
            }

            // 4. 動態萬用字元向 EarlyTag 查詢
            for (TagKey<Block> earlyTag : EarlyTagResolver.getTagsFor(block)) {
                for (TagPatternRule rule : tagPatternRules) {
                    if (rule.matchesTag(earlyTag)) {
                        return true;
                    }
                }
            }

            return false;
        }

        public boolean matchesSameTag(BlockState stateA, BlockState stateB) {
            if (stateA == null || stateB == null) return false;

            for (TagKey<Block> tag : exactTagKeys) {
                if (stateA.is(tag) && stateB.is(tag)) {
                    return true;
                }
                var tagOpt = BuiltInRegistries.BLOCK.getTag(tag);
                if (tagOpt.isPresent() 
                        && tagOpt.get().contains(stateA.getBlock().builtInRegistryHolder()) 
                        && tagOpt.get().contains(stateB.getBlock().builtInRegistryHolder())) {
                    return true;
                }
            }

            if (!tagPatternRules.isEmpty()) {
                for (TagKey<Block> tag : stateA.getTags().toList()) {
                    if (stateB.is(tag)) {
                        for (TagPatternRule rule : tagPatternRules) {
                            if (rule.matchesTag(tag)) {
                                return true;
                            }
                        }
                    }
                }
                return BuiltInRegistries.BLOCK.getTags().anyMatch(pair -> {
                    TagKey<Block> tagKey = pair.getFirst();
                    for (TagPatternRule rule : tagPatternRules) {
                        if (rule.matchesTag(tagKey)) {
                            return pair.getSecond().contains(stateA.getBlock().builtInRegistryHolder())
                                    && pair.getSecond().contains(stateB.getBlock().builtInRegistryHolder());
                        }
                    }
                    return false;
                });
            }

            return false;
        }

        public boolean isEmpty() {
            return exactBlocks.isEmpty() && exactTagKeys.isEmpty() && dynamicRules.isEmpty();
        }

        public static boolean matchesSequence(String target, String[] tokens) {
            if (target == null) return false;
            if (tokens == null || tokens.length == 0) return true;

            int cursor = 0;
            int targetLen = target.length();

            for (String token : tokens) {
                int found = target.indexOf(token, cursor);
                if (found == -1) {
                    return false;
                }
                cursor = found + token.length();
                if (cursor > targetLen) {
                    return false;
                }
            }
            return true;
        }
    }

    // ==========================================
    // 壓縮包規則匹配器
    // ==========================================
    public static class PackBlockMatcher {
        private static class RuleEntry {
            final RuleType type;
            final String zipPattern;
            final BlockMatcher matcher;

            RuleEntry(RuleType type, String zipPattern, BlockMatcher matcher) {
                this.type = type;
                this.zipPattern = zipPattern;
                this.matcher = matcher;
            }
        }

        private final List<RuleEntry> ruleEntries = new ArrayList<>();

        public PackBlockMatcher(List<? extends String> entries) {
            if (entries == null) return;

            for (String entry : entries) {
                if (entry == null) continue;
                entry = entry.trim();

                if (entry.contains("!=")) {
                    String[] split = entry.split("!=", 2);
                    String zipPattern = split[0].trim().toLowerCase(Locale.ROOT);
                    String blockOrTag = split[1].trim();
                    if (!blockOrTag.isEmpty()) {
                        BlockMatcher matcher = new BlockMatcher(List.of(blockOrTag));
                        ruleEntries.add(new RuleEntry(RuleType.LOCK, zipPattern, matcher));
                    }
                } else if (entry.contains("=")) {
                    String[] split = entry.split("=", 2);
                    String zipPattern = split[0].trim().toLowerCase(Locale.ROOT);
                    String blockOrTag = split[1].trim();
                    if (!blockOrTag.isEmpty()) {
                        BlockMatcher matcher = new BlockMatcher(List.of(blockOrTag));
                        if (zipPattern.isEmpty()) {
                            ruleEntries.add(new RuleEntry(RuleType.LOCK, "", matcher));
                        } else {
                            ruleEntries.add(new RuleEntry(RuleType.EXCLUDE, zipPattern, matcher));
                        }
                    }
                }
            }
        }

        public PackRuleResult getMatchedRule(BlockState state, Set<String> activePackIds) {
            if (ruleEntries.isEmpty() || state == null) return null;

            for (RuleEntry entry : ruleEntries) {
                if (entry.matcher.matches(state)) {
                    if (entry.zipPattern.isEmpty()) {
                        return new PackRuleResult(RuleType.LOCK, "", false);
                    }

                    boolean isActive = false;
                    String matchedActivePackId = entry.zipPattern;

                    for (String packId : activePackIds) {
                        if (isPackFileExactMatch(packId, entry.zipPattern)) {
                            isActive = true;
                            matchedActivePackId = packId;
                            break;
                        }
                    }

                    if (entry.type == RuleType.EXCLUDE) {
                        if (isActive) {
                            return new PackRuleResult(RuleType.EXCLUDE, matchedActivePackId, true);
                        }
                    } else if (entry.type == RuleType.LOCK) {
                        return new PackRuleResult(RuleType.LOCK, matchedActivePackId, isActive);
                    }
                }
            }
            return null;
        }

        public static boolean isPackFileExactMatch(String rawPackId, String targetZipName) {
            if (rawPackId == null || targetZipName == null || targetZipName.isEmpty()) return false;

            String raw = rawPackId.toLowerCase(Locale.ROOT).trim();
            String target = targetZipName.toLowerCase(Locale.ROOT).trim();

            int lastSlash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
            String rawFileName = (lastSlash >= 0) ? raw.substring(lastSlash + 1) : raw;
            String rawNoZip = rawFileName.endsWith(".zip") ? rawFileName.substring(0, rawFileName.length() - 4) : rawFileName;

            if (target.contains("*")) {
                String[] tokens = Arrays.stream(target.split("\\*"))
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new);

                if (tokens.length == 0) return true;
                return BlockMatcher.matchesSequence(raw, tokens) 
                        || BlockMatcher.matchesSequence(rawFileName, tokens) 
                        || BlockMatcher.matchesSequence(rawNoZip, tokens);
            }

            int lastTargetSlash = Math.max(target.lastIndexOf('/'), target.lastIndexOf('\\'));
            String targetFileName = (lastTargetSlash >= 0) ? target.substring(lastTargetSlash + 1) : target;
            String targetNoZip = targetFileName.endsWith(".zip") ? targetFileName.substring(0, targetFileName.length() - 4) : targetFileName;

            if (rawFileName.equals(targetFileName) || rawNoZip.equals(targetNoZip)) {
                return true;
            }

            return raw.equals(target);
        }
    }

    // ==========================================
    // 動態同步伺服器 Tag 封包
    // ==========================================
    @EventBusSubscriber(modid = TsAssemblyMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
    public static class ClientGameEvents {
        @SubscribeEvent
        public static void onTagsUpdated(TagsUpdatedEvent event) {
            bakeCache();
            Minecraft mc = Minecraft.getInstance();
            if (mc.levelRenderer != null) {
                mc.levelRenderer.allChanged();
            }
        }
    }
}