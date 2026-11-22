package com.ts.assembly.client.model;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;

@SuppressWarnings("null")
public class NeighborDetector {

    public static int[] calculateMasks(BlockAndTintGetter level, BlockPos pos, BlockState selfState) {
        int[] masks = new int[6];
        Block targetBlock = selfState.getBlock();
        Class<? extends Block> targetClass = targetBlock.getClass();

        for (Direction face : Direction.values()) {
            masks[face.get3DDataValue()] = getFaceMask(level, pos, face, targetBlock, targetClass);
        }
        return masks;
    }

    private static int getFaceMask(BlockAndTintGetter level, BlockPos pos, Direction face, Block target, Class<? extends Block> targetClass) {
        Direction up, down, left, right, top = face;

        // 嚴格對齊 UV 軸向
        switch (face) {
            case UP ->    { up = Direction.NORTH; down = Direction.SOUTH; left = Direction.WEST;  right = Direction.EAST; }
            case DOWN ->  { up = Direction.SOUTH; down = Direction.NORTH; left = Direction.WEST;  right = Direction.EAST; }
            case NORTH -> { up = Direction.UP;    down = Direction.DOWN;  left = Direction.EAST;  right = Direction.WEST; }
            case SOUTH -> { up = Direction.UP;    down = Direction.DOWN;  left = Direction.WEST;  right = Direction.EAST; }
            case WEST ->  { up = Direction.UP;    down = Direction.DOWN;  left = Direction.NORTH; right = Direction.SOUTH; }
            case EAST ->  { up = Direction.UP;    down = Direction.DOWN;  left = Direction.SOUTH; right = Direction.NORTH; }
            default -> throw new IllegalStateException();
        }

        // 1. 正交 4 向 (同平面)
        boolean bU = isBlock(level, pos.relative(up), target, targetClass);
        boolean bD = isBlock(level, pos.relative(down), target, targetClass);
        boolean bL = isBlock(level, pos.relative(left), target, targetClass);
        boolean bR = isBlock(level, pos.relative(right), target, targetClass);

        // 2. 正交 4 向上方 (遮擋 / 階梯)
        boolean bUT = isBlock(level, pos.relative(up).relative(top), target, targetClass);
        boolean bDT = isBlock(level, pos.relative(down).relative(top), target, targetClass);
        boolean bLT = isBlock(level, pos.relative(left).relative(top), target, targetClass);
        boolean bRT = isBlock(level, pos.relative(right).relative(top), target, targetClass);

        // 3. 在源頭直接解算 4 個角落是否繪製 (內部自動按需短路查詢對角頂部方塊)
        boolean drawUL = evalCorner(level, pos, target, targetClass, up, left, top, bU, bUT, bL, bLT);
        boolean drawUR = evalCorner(level, pos, target, targetClass, up, right, top, bU, bUT, bR, bRT);
        boolean drawDL = evalCorner(level, pos, target, targetClass, down, left, top, bD, bDT, bL, bLT);
        boolean drawDR = evalCorner(level, pos, target, targetClass, down, right, top, bD, bDT, bR, bRT);

        // 4. 直接壓縮為純淨 8-Bit 繪圖遮罩
        int mask = 0;
        if (!bU)    mask |= 1;   // 頂邊需繪製
        if (!bD)    mask |= 2;   // 底邊需繪製
        if (!bL)    mask |= 4;   // 左邊需繪製
        if (!bR)    mask |= 8;   // 右邊需繪製
        if (drawUL) mask |= 16;  // 左上角需繪製
        if (drawUR) mask |= 32;  // 右上角需繪製
        if (drawDL) mask |= 64;  // 左下角需繪製
        if (drawDR) mask |= 128; // 右下角需繪製

        return mask;
    }

    /**
     * 角落 3D 連通性解算（含惰性求值）
     */
    private static boolean evalCorner(BlockAndTintGetter level, BlockPos pos, Block target, Class<? extends Block> targetClass, 
                                      Direction dirA, Direction dirB, Direction top,
                                      boolean a, boolean at, boolean b, boolean bt) {
        // 1. 階梯外緣立面凸出折角
        if ((!a && at) || (!b && bt)) return true;

        boolean openA = !a && !at;
        boolean openB = !b && !bt;

        // 2. 雙邊完全開放 (外凸角)
        if (openA && openB) return true;

        // 3. 查詢同平面對角方塊
        boolean diag = isBlock(level, pos.relative(dirA).relative(dirB), target, targetClass);

        // 4. 單邊相連時的邊界接縫處理
        if (!diag && ((openA && b) || (openB && a))) return true;
        if (diag && (a ^ b) && (!at || !bt)) return true;

        // 5. 雙邊相連但缺對角 (若兩側有階梯差，才按需讀取斜對角頂部方塊)
        if (!diag && a && b) {
            if (!at && !bt) return true;
            if (at ^ bt) {
                boolean diagTop = isBlock(level, pos.relative(dirA).relative(dirB).relative(top), target, targetClass);
                return !diagTop;
            }
        }

        return false;
    }

    /**
     * 【型別封閉性核心比對】：
     * 1. 確保相鄰方塊的 Java 類別完全一致 (例如 GlassBlock 對 GlassBlock，IronBarsBlock 對 IronBarsBlock)
     * 2. 徹底阻絕玻璃片、蘑菇、樓梯、半磚等不完整方塊跨型別連通
     */
    private static boolean isBlock(BlockAndTintGetter level, BlockPos pos, Block target, Class<? extends Block> targetClass) {
        BlockState neighborState = level.getBlockState(pos);
        if (neighborState == null || neighborState.isAir()) return false;

        Block neighborBlock = neighborState.getBlock();

        // 1. 同一實例指標直接秒通
        if (neighborBlock == target) return true;

        // 2. 跨方塊時，雙方的 Block 類別必須嚴格相同（同為玻璃片類，或同為玻璃實體類）
        if (neighborBlock.getClass() != targetClass) return false;

        // 3. 類別相同時，才允許進一步走狀態或 Tag 相容比對
        return neighborState.is(target);
    }
}