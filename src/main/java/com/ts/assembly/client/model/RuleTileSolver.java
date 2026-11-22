package com.ts.assembly.client.model;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class RuleTileSolver {
    public record TileCoord(int col, int row) {}

    // 預先快取 0~255 的所有解算結果，執行期完全 0 GC
    private static final TileCoord[][] COMPOSITE_LUT = new TileCoord[256][];
    private static final TileCoord[] FULL_LUT = new TileCoord[256];

    static {
        for (int mask = 0; mask < 256; mask++) {
            COMPOSITE_LUT[mask] = buildCompositeLayers(mask);
            FULL_LUT[mask] = resolveFullRaw(mask);
        }
    }

    // ================== 1. COMPOSITE (3x3 九宮格重疊模式) ==================

    public static TileCoord[] resolveCompositeLayers(int mask) {
        return COMPOSITE_LUT[mask & 0xFF];
    }

    private static TileCoord[] buildCompositeLayers(int mask) {
        List<TileCoord> layers = new ArrayList<>(9);

        // 1. 底層中心 (永遠繪製)
        layers.add(new TileCoord(1, 1));

        // 2. 四邊外框 (1 代表該邊需畫外框)
        if ((mask & 1) != 0) layers.add(new TileCoord(1, 0)); // 頂邊
        if ((mask & 2) != 0) layers.add(new TileCoord(1, 2)); // 底邊
        if ((mask & 4) != 0) layers.add(new TileCoord(0, 1)); // 左邊
        if ((mask & 8) != 0) layers.add(new TileCoord(2, 1)); // 右邊

        // 3. 四個角落 (1 代表該角需補像素)
        if ((mask & 16) != 0)  layers.add(new TileCoord(0, 0)); // 左上角
        if ((mask & 32) != 0)  layers.add(new TileCoord(2, 0)); // 右上角
        if ((mask & 64) != 0)  layers.add(new TileCoord(0, 2)); // 左下角
        if ((mask & 128) != 0) layers.add(new TileCoord(2, 2)); // 右下角

        return layers.toArray(new TileCoord[0]);
    }

    // ================== 2. FULL (6x6 全圖 47 貼圖模式) ==================

    @Nullable
    public static TileCoord resolveFull(int mask) {
        return FULL_LUT[mask & 0xFF];
    }

    @Nullable
    private static TileCoord resolveFullRaw(int mask) {
        // 連通狀態解碼 (true 代表連通/無邊線，false 代表未相連/有邊線或缺角)
        boolean u  = (mask & 1) == 0;
        boolean d  = (mask & 2) == 0;
        boolean l  = (mask & 4) == 0;
        boolean r  = (mask & 8) == 0;
        boolean ul = (mask & 16) == 0;
        boolean ur = (mask & 32) == 0;
        boolean dl = (mask & 64) == 0;
        boolean dr = (mask & 128) == 0;

        int orth = (u ? 1 : 0) + (d ? 1 : 0) + (l ? 1 : 0) + (r ? 1 : 0);

        // 0. 無相鄰連通
        if (orth == 0) return null;

        // 1. 單向連通
        if (orth == 1) {
            if (d) return new TileCoord(4, 0);
            if (r) return new TileCoord(3, 1);
            if (l) return new TileCoord(5, 1);
            if (u) return new TileCoord(4, 2);
        }

        // 2. 雙向連通
        if (orth == 2) {
            if (l && r) return new TileCoord(1, 4);
            if (u && d) return new TileCoord(4, 4);

            if (d && r) return dr ? new TileCoord(0, 0) : new TileCoord(0, 3);
            if (d && l) return dl ? new TileCoord(2, 0) : new TileCoord(2, 3);
            if (u && r) return ur ? new TileCoord(0, 2) : new TileCoord(0, 5);
            if (u && l) return ul ? new TileCoord(2, 2) : new TileCoord(2, 5);
        }

        // 3. 三向連通
        if (orth == 3) {
            if (d && l && r) {
                if (dl && dr)   return new TileCoord(1, 0);
                if (!dl && !dr) return new TileCoord(1, 3);
                if (dl && !dr)  return new TileCoord(3, 3);
                return new TileCoord(4, 3);
            }

            if (u && l && r) {
                if (ul && ur)   return new TileCoord(1, 2);
                if (!ul && !ur) return new TileCoord(1, 5);
                if (!ul && ur)  return new TileCoord(5, 5);
                return new TileCoord(4, 5);
            }

            if (u && d && r) {
                if (ur && dr)   return new TileCoord(0, 1);
                if (!ur && !dr) return new TileCoord(0, 4);
                if (ur && !dr)  return new TileCoord(3, 4);
                return new TileCoord(3, 5);
            }

            if (u && d && l) {
                if (ul && dl)   return new TileCoord(2, 1);
                if (!ul && !dl) return new TileCoord(2, 4);
                if (ul && !dl)  return new TileCoord(5, 3);
                return new TileCoord(5, 4);
            }
        }

        // 4. 四向全通
        if (orth == 4) {
            if (ul && ur && dl && dr) return new TileCoord(1, 1);

            if (ul && ur && dl && !dr) return new TileCoord(3, 0);
            if (ul && ur && !dl && dr) return new TileCoord(5, 0);
            if (ul && !ur && dl && dr) return new TileCoord(3, 2);
            if (!ul && ur && dl && dr) return new TileCoord(5, 2);

            return new TileCoord(4, 1);
        }

        return null;
    }

    /**
     * 水平反轉 8-bit 連通遮罩 (Left <-> Right, TL <-> TR, BL <-> BR)
     */
    public static int flipHorizontalMask(int mask) {
        int flipped = mask & 0b00000011; // 保留 Top (1) 與 Bottom (2)

        if ((mask & 4) != 0) flipped |= 8;
        if ((mask & 8) != 0) flipped |= 4;

        if ((mask & 16) != 0) flipped |= 32;
        if ((mask & 32) != 0) flipped |= 16;

        if ((mask & 64) != 0) flipped |= 128;
        if ((mask & 128) != 0) flipped |= 64;

        return flipped;
    }

    /**
     * 垂直翻轉 8-Neighbor CTM 連通遮罩（上下互換）
     * 遮罩位元映射：
     * Bit 0 <-> Bit 5
     * Bit 1 <-> Bit 6
     * Bit 2 <-> Bit 7
     * Bit 3, Bit 4 保持不變
     */
    public static int flipVerticalMask(int mask) {
        int tl = (mask & (1 << 0)) != 0 ? 1 : 0;
        int t  = (mask & (1 << 1)) != 0 ? 1 : 0;
        int tr = (mask & (1 << 2)) != 0 ? 1 : 0;
        int l  = (mask & (1 << 3)) != 0 ? 1 : 0;
        int r  = (mask & (1 << 4)) != 0 ? 1 : 0;
        int bl = (mask & (1 << 5)) != 0 ? 1 : 0;
        int b  = (mask & (1 << 6)) != 0 ? 1 : 0;
        int br = (mask & (1 << 7)) != 0 ? 1 : 0;

        // 上下交換：0<->5, 1<->6, 2<->7，3 與 4 不變
        return (bl << 0) | (b << 1) | (br << 2)
                | (l  << 3)            | (r  << 4)
                | (tl << 5) | (t << 6) | (tr << 7);
    }
}