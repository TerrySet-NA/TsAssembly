package com.ts.assembly.client.model;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

@SuppressWarnings("null")
public class QuadSubUVMapper {

    public static BakedQuad remapToGrid(BakedQuad original, TextureAtlasSprite targetSprite, 
                                        RuleTileSolver.TileCoord coord, int cols, int rows) {
        return remapToGrid(original, targetSprite, coord, cols, rows, false, false);
    }

    /**
     * @param flipU 側面水平 U 軸翻轉
     * @param flipV 頂底面垂直 V 軸翻轉
     */
    public static BakedQuad remapToGrid(BakedQuad original, TextureAtlasSprite targetSprite, 
                                        RuleTileSolver.TileCoord coord, int cols, int rows,
                                        boolean flipU, boolean flipV) {
        if (targetSprite == null || original == null) return original;

        int[] vertices = original.getVertices().clone();
        TextureAtlasSprite origSprite = original.getSprite();

        float origU0 = origSprite.getU0();
        float origWidth = Math.max(1e-7f, origSprite.getU1() - origU0);
        float origV0 = origSprite.getV0();
        float origHeight = Math.max(1e-7f, origSprite.getV1() - origV0);

        float targetU0 = targetSprite.getU0();
        float targetWidth = targetSprite.getU1() - targetU0;
        float targetV0 = targetSprite.getV0();
        float targetHeight = targetSprite.getV1() - targetV0;

        float subColMin = (float) coord.col() / cols;
        float subColMax = (float) (coord.col() + 1) / cols;
        float subRowMin = (float) coord.row() / rows;
        float subRowMax = (float) (coord.row() + 1) / rows;

        for (int i = 0; i < 4; i++) {
            int offset = i * 8;
            float rawU = Float.intBitsToFloat(vertices[offset + 4]);
            float rawV = Float.intBitsToFloat(vertices[offset + 5]);

            float progressU = Math.max(0.0f, Math.min(1.0f, (rawU - origU0) / origWidth));
            float progressV = Math.max(0.0f, Math.min(1.0f, (rawV - origV0) / origHeight));

            if (flipU) {
                progressU = 1.0f - progressU;
            }
            if (flipV) {
                progressV = 1.0f - progressV;
            }

            float sheetU = subColMin + (subColMax - subColMin) * progressU;
            float sheetV = subRowMin + (subRowMax - subRowMin) * progressV;

            vertices[offset + 4] = Float.floatToRawIntBits(targetU0 + targetWidth * sheetU);
            vertices[offset + 5] = Float.floatToRawIntBits(targetV0 + targetHeight * sheetV);
        }

        return new BakedQuad(
            vertices,
            original.getTintIndex(),
            original.getDirection(),
            targetSprite,
            original.isShade(),
            original.hasAmbientOcclusion()
        );
    }
}