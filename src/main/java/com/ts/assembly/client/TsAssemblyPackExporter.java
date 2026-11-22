package com.ts.assembly.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.ts.assembly.TsAssemblyMod;
import com.ts.assembly.client.model.RuleTileBakedModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

@SuppressWarnings("null")
public class TsAssemblyPackExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum ExportMode {
        COMPOSITE,
        FULL
    }

    private record SpriteGroup(TextureAtlasSprite sprite, Set<Direction> faces) {}

    public static int export(BlockState state, ExportMode mode, String packName) throws IOException {
        Block targetBlock = state.getBlock();
        String rawBlockPath = BuiltInRegistries.BLOCK.getKey(targetBlock).getPath();
        String blockNamespace = BuiltInRegistries.BLOCK.getKey(targetBlock).getNamespace();
        String targetBlockFullId = blockNamespace + ":" + rawBlockPath;
        String exportNamespace = TsAssemblyMod.MOD_ID;

        File gameDir = Minecraft.getInstance().gameDirectory;
        File packRoot = new File(gameDir, "resourcepacks/" + packName);
        File texturesDir = new File(packRoot, "assets/" + exportNamespace + "/textures/block");
        File rulesDir = new File(packRoot, "assets/" + exportNamespace + "/rule_tiles");

        texturesDir.mkdirs();
        rulesDir.mkdirs();

        File mcmetaFile = new File(packRoot, "pack.mcmeta");
        if (!mcmetaFile.exists()) {
            writePackMcmeta(mcmetaFile, "TsAssembly Dynamic Connected Textures Pack");
        }

        Map<ResourceLocation, SpriteGroup> spriteGroups = collectAllSpritesForBlock(targetBlock);
        boolean isUniform = (spriteGroups.size() == 1);
        int exportedCount = 0;

        for (SpriteGroup group : spriteGroups.values()) {
            TextureAtlasSprite sprite = group.sprite();
            Set<Direction> faces = group.faces();

            List<String> faceTags = new ArrayList<>();
            String faceSuffix = "";

            if (isUniform || faces.size() >= 6) {
                faceSuffix = "";
                faceTags.add("all");
            } else if (faces.size() == 4 && faces.contains(Direction.NORTH) && faces.contains(Direction.SOUTH) &&
                       faces.contains(Direction.WEST) && faces.contains(Direction.EAST)) {
                faceSuffix = "_sides";
                faceTags.add("sides");
            } else if (faces.size() == 1) {
                Direction single = faces.iterator().next();
                if (single == Direction.UP) {
                    faceSuffix = "_top";
                    faceTags.add("top");
                } else if (single == Direction.DOWN) {
                    faceSuffix = "_bottom";
                    faceTags.add("bottom");
                } else {
                    faceSuffix = "_" + single.getSerializedName();
                    faceTags.add(single.getSerializedName());
                }
            } else {
                StringBuilder sb = new StringBuilder();
                for (Direction d : faces) {
                    sb.append("_").append(d.getSerializedName());
                    faceTags.add(d.getSerializedName());
                }
                faceSuffix = sb.toString();
            }

            String spritePath = sprite.contents().name().getPath();
            String spriteShortName = spritePath.substring(spritePath.lastIndexOf('/') + 1);

            String fileBaseName = isUniform ? (rawBlockPath + faceSuffix) : (rawBlockPath + "_" + spriteShortName + faceSuffix);
            int gridSize = (mode == ExportMode.COMPOSITE) ? 3 : 6;
            String typeStr = (mode == ExportMode.COMPOSITE) ? "COMPOSITE" : "FULL";
            String textureName = fileBaseName + "_" + typeStr.toLowerCase();
            String originalTextureName = (mode == ExportMode.FULL) ? (fileBaseName + "_original") : null;
            String matchSpriteName = sprite.contents().name().toString();

            if (originalTextureName != null) {
                exportOriginalImage(sprite, new File(texturesDir, originalTextureName + ".png"));
            }

            exportGridTemplate(sprite, new File(texturesDir, textureName + ".png"), gridSize);
            writeRuleJson(rulesDir, fileBaseName, targetBlockFullId, faceTags, typeStr, textureName, originalTextureName, matchSpriteName);
            exportedCount++;
        }

        return exportedCount;
    }

    private static Map<ResourceLocation, SpriteGroup> collectAllSpritesForBlock(Block block) {
        Map<ResourceLocation, SpriteGroup> spriteGroups = new LinkedHashMap<>();
        BlockModelShaper shaper = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper();
        RandomSource rand = RandomSource.create(42);

        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            BakedModel model = shaper.getBlockModel(state);
            while (model instanceof RuleTileBakedModel ruleModel) {
                model = ruleModel.getVanillaModel();
            }

            for (Direction face : Direction.values()) {
                List<BakedQuad> quads = model.getQuads(state, face, rand, ModelData.EMPTY, null);
                for (BakedQuad quad : quads) {
                    registerSpriteQuad(spriteGroups, quad.getSprite(), face);
                }
            }

            List<BakedQuad> unculled = model.getQuads(state, null, rand, ModelData.EMPTY, null);
            for (BakedQuad quad : unculled) {
                Direction quadDir = quad.getDirection();
                registerSpriteQuad(spriteGroups, quad.getSprite(), quadDir != null ? quadDir : Direction.UP);
            }
        }

        if (spriteGroups.isEmpty()) {
            BakedModel defaultModel = shaper.getBlockModel(block.defaultBlockState());
            while (defaultModel instanceof RuleTileBakedModel ruleModel) {
                defaultModel = ruleModel.getVanillaModel();
            }
            TextureAtlasSprite particle = defaultModel.getParticleIcon(ModelData.EMPTY);
            if (particle != null) {
                Set<Direction> allFaces = new HashSet<>(Arrays.asList(Direction.values()));
                spriteGroups.put(particle.contents().name(), new SpriteGroup(particle, allFaces));
            }
        }

        return spriteGroups;
    }

    private static void registerSpriteQuad(Map<ResourceLocation, SpriteGroup> spriteGroups,
                                           TextureAtlasSprite sprite, Direction face) {
        if (sprite == null) return;
        ResourceLocation spriteName = sprite.contents().name();

        spriteGroups.compute(spriteName, (key, existing) -> {
            if (existing == null) {
                Set<Direction> faceSet = new HashSet<>();
                if (face != null) faceSet.add(face);
                return new SpriteGroup(sprite, faceSet);
            } else {
                if (face != null) existing.faces().add(face);
                return existing;
            }
        });
    }

    private static void exportOriginalImage(TextureAtlasSprite sprite, File outputFile) throws IOException {
        int tileW = sprite.contents().width();
        int tileH = sprite.contents().height();

        try (NativeImage image = new NativeImage(tileW, tileH, false)) {
            for (int y = 0; y < tileH; y++) {
                for (int x = 0; x < tileW; x++) {
                    image.setPixelRGBA(x, y, sprite.getPixelRGBA(0, x, y));
                }
            }
            image.writeToFile(outputFile);
        }
    }

    private static void exportGridTemplate(TextureAtlasSprite sprite, File outputFile, int size) throws IOException {
        int tileW = sprite.contents().width();
        int tileH = sprite.contents().height();

        NativeImage baseImage = new NativeImage(tileW, tileH, false);
        for (int y = 0; y < tileH; y++) {
            for (int x = 0; x < tileW; x++) {
                baseImage.setPixelRGBA(x, y, sprite.getPixelRGBA(0, x, y));
            }
        }

        NativeImage sheet = new NativeImage(tileW * size, tileH * size, false);
        for (int col = 0; col < size; col++) {
            for (int row = 0; row < size; row++) {
                for (int y = 0; y < tileH; y++) {
                    for (int x = 0; x < tileW; x++) {
                        sheet.setPixelRGBA(col * tileW + x, row * tileH + y, baseImage.getPixelRGBA(x, y));
                    }
                }
            }
        }

        sheet.writeToFile(outputFile);
        sheet.close();
        baseImage.close();
    }

    private static void writeRuleJson(File dir, String ruleName, String targetBlockId,
                                      List<String> faceTags, String type, String textureName,
                                      String originalTextureName, String matchSpriteName) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("type", type);

        JsonArray faces = new JsonArray();
        faceTags.forEach(faces::add);
        root.add("faces", faces);

        root.addProperty("texture", textureName);

        if (originalTextureName != null) {
            root.addProperty("original_texture", originalTextureName);
        }

        if (matchSpriteName != null && !matchSpriteName.isEmpty()) {
            root.addProperty("match_sprite", matchSpriteName);
        }

        JsonArray targets = new JsonArray();
        targets.add(targetBlockId);
        root.add("target_blocks", targets);

        try (FileWriter writer = new FileWriter(new File(dir, ruleName + ".json"))) {
            GSON.toJson(root, writer);
        }
    }

    private static void writePackMcmeta(File mcmetaFile, String description) throws IOException {
        JsonObject mcmeta = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", 34);
        pack.addProperty("description", description);
        mcmeta.add("pack", pack);

        try (FileWriter writer = new FileWriter(mcmetaFile)) {
            GSON.toJson(mcmeta, writer);
        }
    }
}