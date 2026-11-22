package com.ts.assembly.client.util;

import com.mojang.blaze3d.platform.NativeImage;
import com.ts.assembly.TsAssemblyMod;
import com.ts.assembly.client.config.TsAssemblyClientConfig;
import com.ts.assembly.client.model.RuleTileBakedModel;
import com.ts.assembly.mixin.SpriteContentsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class DynamicFaceOcclusionCache {

    private static final float EPSILON = 1e-3f;

    private static final ThreadLocal<RandomSource> THREAD_LOCAL_RANDOM = 
            ThreadLocal.withInitial(() -> RandomSource.create(42L));

    private static final ConcurrentHashMap<Integer, Byte> CACHE = new ConcurrentHashMap<>();
    private static final byte STATUS_SOLID = 1;
    private static final byte STATUS_TRANSPARENT = 2;

    private enum EvaluationResult {
        SOLID,
        TRANSPARENT,
        UNREADY
    }

    public static void clear() {
        CACHE.clear();
    }

    public static boolean isFaceSolidAndOpaque(BlockState state, Direction face) {
        if (state == null || state.isAir() || face == null) return false;

        if (state.getBlock() instanceof DoorBlock) {
            return false;
        }

        int stateId = Block.getId(state);
        int key = (stateId << 3) | face.ordinal();

        Byte cached = CACHE.get(key);
        if (cached != null) {
            return cached == STATUS_SOLID;
        }

        EvaluationResult result = evaluateFace(state, face);
        
        if (result == EvaluationResult.SOLID) {
            CACHE.put(key, STATUS_SOLID);
            return true;
        } else if (result == EvaluationResult.TRANSPARENT) {
            CACHE.put(key, STATUS_TRANSPARENT);
            return false;
        }

        return false;
    }

    @SuppressWarnings({ "deprecation", "null" })
    private static EvaluationResult evaluateFace(BlockState state, Direction face) {
        // 1. 只過濾真正的空氣
        if (state == null || state.isAir()) {
            return EvaluationResult.TRANSPARENT;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.getBlockRenderer() == null) {
            return EvaluationResult.UNREADY;
        }

        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        if (model == null) {
            return EvaluationResult.UNREADY;
        }

        while (model instanceof RuleTileBakedModel rtbm) {
            model = rtbm.getVanillaModel();
        }

        RandomSource rand = THREAD_LOCAL_RANDOM.get();

        List<BakedQuad> quads = model.getQuads(state, face, rand);
        if (quads.isEmpty()) {
            quads = model.getQuads(state, null, rand);
        }

        // 該面若無 Quad，代表物理無遮蔽
        if (quads.isEmpty()) {
            return EvaluationResult.TRANSPARENT;
        }

        float totalArea = 0.0f;

        for (BakedQuad quad : quads) {
            int[] v = quad.getVertices();
            if (v.length < 32) continue;

            float x0 = Float.intBitsToFloat(v[0]), y0 = Float.intBitsToFloat(v[1]), z0 = Float.intBitsToFloat(v[2]);
            float x1 = Float.intBitsToFloat(v[8]), y1 = Float.intBitsToFloat(v[9]), z1 = Float.intBitsToFloat(v[10]);
            float x2 = Float.intBitsToFloat(v[16]), y2 = Float.intBitsToFloat(v[17]), z2 = Float.intBitsToFloat(v[18]);
            float x3 = Float.intBitsToFloat(v[24]), y3 = Float.intBitsToFloat(v[25]), z3 = Float.intBitsToFloat(v[26]);

            float nx = (y0 - y1) * (z0 + z1) + (y1 - y2) * (z1 + z2) + (y2 - y3) * (z2 + z3) + (y3 - y0) * (z3 + z0);
            float ny = (z0 - z1) * (x0 + x1) + (z1 - z2) * (x1 + x2) + (z2 - z3) * (x2 + x3) + (z3 - z0) * (x3 + x0);
            float nz = (x0 - x1) * (y0 + y1) + (x1 - x2) * (y1 + y2) + (x2 - x3) * (y2 + y3) + (x3 - x0) * (y3 + y0);

            Direction qDir = FaceCullHelper.getRobustQuadDirection(quad, x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, nx, ny, nz);
            if (qDir != face) continue;

            if (!FaceCullHelper.isQuadFlushWithBoundary(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, face)) {
                continue;
            }

            TextureAtlasSprite sprite = quad.getSprite();
            if (sprite != null) {
                Boolean hasTrans = hasTransparentPixels(quad, sprite);
                if (hasTrans == null) {
                    return EvaluationResult.UNREADY;
                }
                if (hasTrans) {
                    return EvaluationResult.TRANSPARENT;
                }
            }

            totalArea += calculateQuadArea(quad, face);
        }

        boolean isSolid = totalArea >= (1.0f - EPSILON);
        return isSolid ? EvaluationResult.SOLID : EvaluationResult.TRANSPARENT;
    }

    private static float calculateQuadArea(BakedQuad quad, Direction face) {
        int[] v = quad.getVertices();
        if (v.length < 32) return 0.0f;

        float minA = Float.MAX_VALUE, maxA = -Float.MAX_VALUE;
        float minB = Float.MAX_VALUE, maxB = -Float.MAX_VALUE;

        for (int i = 0; i < 4; i++) {
            int off = i * 8;
            float x = Float.intBitsToFloat(v[off + 0]);
            float y = Float.intBitsToFloat(v[off + 1]);
            float z = Float.intBitsToFloat(v[off + 2]);

            float a = 0, b = 0;
            switch (face.getAxis()) {
                case Y -> { a = x; b = z; }
                case Z -> { a = x; b = y; }
                case X -> { a = y; b = z; }
            }
            minA = Math.min(minA, a); maxA = Math.max(maxA, a);
            minB = Math.min(minB, b); maxB = Math.max(maxB, b);
        }

        float width = Math.max(0.0f, maxA - minA);
        float height = Math.max(0.0f, maxB - minB);
        return width * height;
    }

    private static Boolean hasTransparentPixels(BakedQuad quad, TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        NativeImage image = ((SpriteContentsAccessor) contents).tsassembly$getOriginalImage();
        if (image == null) {
            return null;
        }

        int spriteWidth = contents.width();
        int spriteHeight = contents.height();

        int[] v = quad.getVertices();
        if (v.length < 32) return false;

        float uMin = Float.MAX_VALUE, uMax = -Float.MAX_VALUE;
        float vMin = Float.MAX_VALUE, vMax = -Float.MAX_VALUE;

        for (int i = 0; i < 4; i++) {
            int offset = i * 8;
            float u = Float.intBitsToFloat(v[offset + 4]);
            float valV = Float.intBitsToFloat(v[offset + 5]);

            uMin = Math.min(uMin, u);
            uMax = Math.max(uMax, u);
            vMin = Math.min(vMin, valV);
            vMax = Math.max(vMax, valV);
        }

        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();

        float deltaU = u1 - u0;
        float deltaV = v1 - v0;
        if (Math.abs(deltaU) < 1e-6f || Math.abs(deltaV) < 1e-6f) return false;

        float relUMin = Math.max(0.0f, Math.min(1.0f, (uMin - u0) / deltaU));
        float relUMax = Math.max(0.0f, Math.min(1.0f, (uMax - u0) / deltaU));
        float relVMin = Math.max(0.0f, Math.min(1.0f, (vMin - v0) / deltaV));
        float relVMax = Math.max(0.0f, Math.min(1.0f, (vMax - v0) / deltaV));

        int pxStart = Math.max(0, (int) Math.floor(Math.min(relUMin, relUMax) * spriteWidth));
        int pxEnd = Math.min(spriteWidth - 1, (int) Math.ceil(Math.max(relUMin, relUMax) * spriteWidth));
        int pyStart = Math.max(0, (int) Math.floor(Math.min(relVMin, relVMax) * spriteHeight));
        int pyEnd = Math.min(spriteHeight - 1, (int) Math.ceil(Math.max(relVMin, relVMax) * spriteHeight));

        for (int y = pyStart; y <= pyEnd; y++) {
            for (int x = pxStart; x <= pxEnd; x++) {
                int pixel = image.getPixelRGBA(x, y);
                int alpha = (pixel >> 24) & 0xFF;
                if (alpha < 255) return true;
            }
        }

        return false;
    }
}