package com.ts.assembly.client.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ts.assembly.TsAssemblyMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class EarlyTagResolver {
    private static final Gson GSON = new Gson();
    private static final Map<TagKey<Block>, Set<Block>> EARLY_TAG_MAP = new ConcurrentHashMap<>();
    private static final Map<TagKey<Block>, Set<TagKey<Block>>> NESTED_TAG_MAP = new ConcurrentHashMap<>();

    public static synchronized void init() {
        EARLY_TAG_MAP.clear();
        NESTED_TAG_MAP.clear();

        // 1. 掃描所有已載入的 Mod 文件 (NeoForge API 傳入 "data" 根目錄)
        for (IModFileInfo modInfo : ModList.get().getModFiles()) {
            try {
                Path dataDir = modInfo.getFile().findResource("data");
                if (dataDir != null && Files.exists(dataDir) && Files.isDirectory(dataDir)) {
                    scanDataRoot(dataDir);
                }
            } catch (Exception ignored) {}
        }

        // 2. 補充掃描原版 Vanilla DataPack，使用 ioSupplier.get() 解出 InputStream
        try {
            VanillaPackResources vanillaPack = ServerPacksSource.createVanillaPackSource();
            vanillaPack.listResources(PackType.SERVER_DATA, "minecraft", "tags/block", (loc, ioSupplier) -> {
                try (InputStream is = ioSupplier.get()) {
                    parseTagStream(loc, is);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}

        // 3. 遞迴展開巢狀 Tag 參照
        resolveNestedTags();

        TsAssemblyMod.LOGGER.info("[TsAssembly] EarlyTagResolver initialized: loaded {} tag definitions before ModelBake.", EARLY_TAG_MAP.size());
    }

    private static void scanDataRoot(Path dataDir) {
        try (Stream<Path> namespaces = Files.list(dataDir)) {
            namespaces.filter(Files::isDirectory).forEach(nsPath -> {
                String namespace = nsPath.getFileName().toString();
                scanTagDir(namespace, nsPath.resolve("tags/blocks"));
                scanTagDir(namespace, nsPath.resolve("tags/block"));
            });
        } catch (Exception ignored) {}
    }

    private static void scanTagDir(String namespace, Path tagDir) {
        if (!Files.exists(tagDir) || !Files.isDirectory(tagDir)) return;

        try (Stream<Path> files = Files.walk(tagDir)) {
            files.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".json")).forEach(file -> {
                String relative = tagDir.relativize(file).toString().replace('\\', '/');
                String tagPath = relative.substring(0, relative.length() - 5);
                ResourceLocation tagLoc = ResourceLocation.fromNamespaceAndPath(namespace, tagPath);
                TagKey<Block> tagKey = TagKey.create(Registries.BLOCK, tagLoc);

                try (InputStream is = Files.newInputStream(file)) {
                    parseTagJson(tagKey, is);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private static void parseTagStream(ResourceLocation fileLoc, InputStream is) {
        String path = fileLoc.getPath(); // 例如 tags/block/leaves.json
        if (!path.endsWith(".json")) return;
        String tagPath = path.substring(path.indexOf('/') + 1);
        if (tagPath.startsWith("block/")) tagPath = tagPath.substring(6);
        else if (tagPath.startsWith("blocks/")) tagPath = tagPath.substring(7);
        tagPath = tagPath.substring(0, tagPath.length() - 5);

        ResourceLocation tagLoc = ResourceLocation.fromNamespaceAndPath(fileLoc.getNamespace(), tagPath);
        TagKey<Block> tagKey = TagKey.create(Registries.BLOCK, tagLoc);
        parseTagJson(tagKey, is);
    }

    private static void parseTagJson(TagKey<Block> tagKey, InputStream is) {
        try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json != null && json.has("values")) {
                JsonArray values = json.getAsJsonArray("values");
                for (JsonElement valElem : values) {
                    String entryId = valElem.isJsonObject() ? valElem.getAsJsonObject().get("id").getAsString() : valElem.getAsString();
                    if (entryId.startsWith("#")) {
                        ResourceLocation subTagLoc = ResourceLocation.tryParse(entryId.substring(1));
                        if (subTagLoc != null) {
                            NESTED_TAG_MAP.computeIfAbsent(tagKey, k -> new HashSet<>())
                                    .add(TagKey.create(Registries.BLOCK, subTagLoc));
                        }
                    } else {
                        ResourceLocation bLoc = ResourceLocation.tryParse(entryId);
                        if (bLoc != null && BuiltInRegistries.BLOCK.containsKey(bLoc)) {
                            Block b = BuiltInRegistries.BLOCK.get(bLoc);
                            if (b != null) {
                                EARLY_TAG_MAP.computeIfAbsent(tagKey, k -> new HashSet<>()).add(b);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static void resolveNestedTags() {
        boolean changed = true;
        int depth = 0;
        while (changed && depth++ < 8) {
            changed = false;
            for (Map.Entry<TagKey<Block>, Set<TagKey<Block>>> entry : NESTED_TAG_MAP.entrySet()) {
                TagKey<Block> parentTag = entry.getKey();
                Set<Block> parentBlocks = EARLY_TAG_MAP.computeIfAbsent(parentTag, k -> new HashSet<>());

                for (TagKey<Block> childTag : entry.getValue()) {
                    Set<Block> childBlocks = EARLY_TAG_MAP.get(childTag);
                    if (childBlocks != null && !childBlocks.isEmpty()) {
                        if (parentBlocks.addAll(childBlocks)) {
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    public static boolean contains(TagKey<Block> tagKey, Block block) {
        Set<Block> blocks = EARLY_TAG_MAP.get(tagKey);
        return blocks != null && blocks.contains(block);
    }

    public static Set<TagKey<Block>> getTagsFor(Block block) {
        Set<TagKey<Block>> set = new HashSet<>();
        EARLY_TAG_MAP.forEach((tag, blocks) -> {
            if (blocks.contains(block)) set.add(tag);
        });
        return set;
    }
}