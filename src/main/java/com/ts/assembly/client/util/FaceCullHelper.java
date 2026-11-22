package com.ts.assembly.client.util;

import com.ts.assembly.client.config.TsAssemblyClientConfig;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import java.util.ArrayList;
import java.util.List;

public class FaceCullHelper {

    private static final float CENTER = 0.5f;
    private static final float EPSILON = 1e-3f;

    /**
     * 核心判定：相鄰方塊的接觸面是否「幾何 100% 滿版」且「貼圖無孔完全遮蔽」
     * 純粹依據 Minecraft 原版物理支撐與幾何外觀，無任何方塊名稱硬編碼
     */
    public static boolean isFaceFullyOccluding(BlockGetter level, BlockPos neighborPos, Direction neighborFace, BlockState neighborState) {
        if (neighborState == null || neighborState.isAir()) return false;

        // 排除一般門類 (DoorBlock)
        if (neighborState.getBlock() instanceof DoorBlock) {
            return false;
        }

        // 地板門 / 活板門貼齊狀態精確校驗
        if (neighborState.getBlock() instanceof TrapDoorBlock) {
            boolean isOpen = neighborState.getValue(TrapDoorBlock.OPEN);
            if (!isOpen) {
                Half half = neighborState.getValue(TrapDoorBlock.HALF);
                if (half == Half.BOTTOM && neighborFace != Direction.DOWN) return false;
                if (half == Half.TOP && neighborFace != Direction.UP) return false;
            } else {
                Direction facing = neighborState.getValue(TrapDoorBlock.FACING);
                if (neighborFace != facing.getOpposite()) return false;
            }
        } else {
            // 一般方塊與不完整方塊物理滿版檢驗
            boolean isFull = neighborState.isFaceSturdy(level, neighborPos, neighborFace, SupportType.FULL)
                    || Block.isFaceFull(neighborState.getFaceOcclusionShape(level, neighborPos, neighborFace), neighborFace);

            if (!isFull) {
                return false;
            }
        }

        // 動態貼圖與無孔覆蓋檢驗
        return DynamicFaceOcclusionCache.isFaceSolidAndOpaque(neighborState, neighborFace);
    }

    public static List<BakedQuad> filterQuads(
            BlockState state, 
            BlockGetter level, 
            BlockPos pos, 
            List<BakedQuad> quads
    ) {
        if (quads == null || quads.isEmpty() || state == null) {
            return quads;
        }

        boolean checkInward = TsAssemblyClientConfig.isCullInwardFaceBlock(state);
        boolean hasLevel = (level != null && pos != null);
        boolean checkFacingList = hasLevel && TsAssemblyClientConfig.isCullFacingBlock(state);
        boolean checkSameBlock = hasLevel && TsAssemblyClientConfig.shouldCullSameBlock(state, state);
        boolean checkFullyOccluded = hasLevel && TsAssemblyClientConfig.shouldCullFacingFullyOccluded(state);

        if (!checkInward && !checkFacingList && !checkSameBlock && !checkFullyOccluded) {
            return quads;
        }

        int firstCulledIdx = -1;
        int size = quads.size();
        for (int i = 0; i < size; i++) {
            if (shouldCullQuad(quads.get(i), checkInward, checkFacingList, checkSameBlock, checkFullyOccluded, level, pos, state)) {
                firstCulledIdx = i;
                break;
            }
        }

        if (firstCulledIdx == -1) {
            return quads;
        }

        List<BakedQuad> result = new ArrayList<>(size - 1);
        for (int i = 0; i < firstCulledIdx; i++) {
            result.add(quads.get(i));
        }
        for (int i = firstCulledIdx + 1; i < size; i++) {
            BakedQuad quad = quads.get(i);
            if (!shouldCullQuad(quad, checkInward, checkFacingList, checkSameBlock, checkFullyOccluded, level, pos, state)) {
                result.add(quad);
            }
        }
        return result;
    }

    @SuppressWarnings("null")
    public static boolean shouldCullQuad(
            BakedQuad quad, 
            boolean checkInward, 
            boolean checkFacingList, 
            boolean checkSameBlock,
            boolean checkFullyOccluded,
            BlockGetter level, 
            BlockPos pos, 
            BlockState selfState
    ) {
        int[] v = quad.getVertices();
        if (v.length < 32) return false;

        float x0 = Float.intBitsToFloat(v[0]), y0 = Float.intBitsToFloat(v[1]), z0 = Float.intBitsToFloat(v[2]);
        float x1 = Float.intBitsToFloat(v[8]), y1 = Float.intBitsToFloat(v[9]), z1 = Float.intBitsToFloat(v[10]);
        float x2 = Float.intBitsToFloat(v[16]), y2 = Float.intBitsToFloat(v[17]), z2 = Float.intBitsToFloat(v[18]);
        float x3 = Float.intBitsToFloat(v[24]), y3 = Float.intBitsToFloat(v[25]), z3 = Float.intBitsToFloat(v[26]);

        float cx = (x0 + x1 + x2 + x3) * 0.25f;
        float cy = (y0 + y1 + y2 + y3) * 0.25f;
        float cz = (z0 + z1 + z2 + z3) * 0.25f;

        float dx = cx - CENTER;
        float dy = cy - CENTER;
        float dz = cz - CENTER;

        // Newell's Method 綜合法向量：環繞四頂點計算，免疫頂點旋轉與繞序打亂
        float nx = (y0 - y1) * (z0 + z1) + (y1 - y2) * (z1 + z2) + (y2 - y3) * (z2 + z3) + (y3 - y0) * (z3 + z0);
        float ny = (z0 - z1) * (x0 + x1) + (z1 - z2) * (x1 + x2) + (z2 - z3) * (x2 + x3) + (z3 - z0) * (x3 + x0);
        float nz = (x0 - x1) * (y0 + y1) + (x1 - x2) * (y1 + y2) + (x2 - x3) * (y2 + y3) + (x3 - x0) * (y3 + y0);

        // 1. 幾何內部朝內面剔除
        if (checkInward) {
            float distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > EPSILON) {
                float dot = nx * dx + ny * dy + nz * dz;
                if (dot < -EPSILON) {
                    return true;
                }
            }
        }

        // 2. 外部相鄰方塊遮蔽檢測
        if (level != null && pos != null) {
            Direction dir = getRobustQuadDirection(quad, x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, nx, ny, nz);
            if (dir != null) {
                // 自身 Quad 必須平貼在最外側邊界
                if (!isQuadFlushWithBoundary(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, dir)) {
                    return false;
                }

                BlockPos neighborPos = pos.relative(dir);
                BlockState neighborState = level.getBlockState(neighborPos);

                if (neighborState.isAir()) {
                    return false;
                }

                if (checkSameBlock && TsAssemblyClientConfig.shouldCullSameBlock(selfState, neighborState)) {
                    return true;
                }

                if (checkFacingList && TsAssemblyClientConfig.shouldCullFacingIncomplete()) {
                    return true;
                }

                Direction oppositeDir = dir.getOpposite();
                if (checkFullyOccluded && isFaceFullyOccluding(level, neighborPos, oppositeDir, neighborState)) {
                    return true;
                }
            }
        }

        return false;
    }

    public static boolean isQuadFlushWithBoundary(
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            Direction dir
    ) {
        return switch (dir) {
            case DOWN -> y0 <= EPSILON && y1 <= EPSILON && y2 <= EPSILON && y3 <= EPSILON;
            case UP -> y0 >= 1.0f - EPSILON && y1 >= 1.0f - EPSILON && y2 >= 1.0f - EPSILON && y3 >= 1.0f - EPSILON;
            case NORTH -> z0 <= EPSILON && z1 <= EPSILON && z2 <= EPSILON && z3 <= EPSILON;
            case SOUTH -> z0 >= 1.0f - EPSILON && z1 >= 1.0f - EPSILON && z2 >= 1.0f - EPSILON && z3 >= 1.0f - EPSILON;
            case WEST -> x0 <= EPSILON && x1 <= EPSILON && x2 <= EPSILON && x3 <= EPSILON;
            case EAST -> x0 >= 1.0f - EPSILON && x1 >= 1.0f - EPSILON && x2 >= 1.0f - EPSILON && x3 >= 1.0f - EPSILON;
        };
    }

    /**
     * 【抗旋轉強健方向解析】：
     * 優先以邊界貼合直接錨定方向，免疫任何模組旋轉造成的法向量翻轉
     */
    public static Direction getRobustQuadDirection(
            BakedQuad quad,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float nx, float ny, float nz
    ) {
        if (isQuadFlushWithBoundary(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, Direction.UP)) return Direction.UP;
        if (isQuadFlushWithBoundary(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, Direction.DOWN)) return Direction.DOWN;
        if (isQuadFlushWithBoundary(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, Direction.NORTH)) return Direction.NORTH;
        if (isQuadFlushWithBoundary(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, Direction.SOUTH)) return Direction.SOUTH;
        if (isQuadFlushWithBoundary(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, Direction.WEST)) return Direction.WEST;
        if (isQuadFlushWithBoundary(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, Direction.EAST)) return Direction.EAST;

        if (quad.getDirection() != null) {
            return quad.getDirection();
        }

        float absX = Math.abs(nx);
        float absY = Math.abs(ny);
        float absZ = Math.abs(nz);

        if (absY >= absX && absY >= absZ) {
            return ny > 0 ? Direction.UP : Direction.DOWN;
        } else if (absZ >= absX && absZ >= absY) {
            return nz > 0 ? Direction.SOUTH : Direction.NORTH;
        } else if (absX > 0) {
            return nx > 0 ? Direction.EAST : Direction.WEST;
        }

        return null;
    }
}