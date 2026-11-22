package com.ts.assembly.client.model;

import com.ts.assembly.client.config.RuleConfig;
import com.ts.assembly.client.config.TsAssemblyClientConfig;
import com.ts.assembly.client.registry.RuleTileRegistry;
import com.ts.assembly.client.util.FaceCullHelper;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.IDynamicBakedModel;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SuppressWarnings({ "null", "deprecation" })
public class RuleTileBakedModel implements IDynamicBakedModel {
    private final BakedModel vanillaModel;

    public static final ModelProperty<BlockPos> BLOCK_POS = new ModelProperty<>();
    public static final ModelProperty<BlockGetter> LEVEL = new ModelProperty<>();

    private static final Direction[] DIRECTIONS = Direction.values();

    private static final BooleanProperty[] HORIZONTAL_PROPERTIES = new BooleanProperty[] {
            BlockStateProperties.NORTH,
            BlockStateProperties.SOUTH,
            BlockStateProperties.EAST,
            BlockStateProperties.WEST
    };

    private static final float FRONT_INWARD_OFFSET = 0.0002f;
    private static final float BACK_INWARD_OFFSET  = 0.0006f;

    public RuleTileBakedModel(BakedModel vanillaModel) {
        this.vanillaModel = vanillaModel;
    }

    public BakedModel getVanillaModel() {
        return this.vanillaModel;
    }

    private static boolean isCullCandidate(BlockState state) {
        if (state == null) return false;
        return TsAssemblyClientConfig.isCullInwardFaceBlock(state)
                || TsAssemblyClientConfig.isCullFacingBlock(state)
                || TsAssemblyClientConfig.shouldCullSameBlock(state, state)
                || TsAssemblyClientConfig.shouldCullFacingFullyOccluded(state);
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter level, @NotNull BlockPos pos,
                                           @NotNull BlockState state, @NotNull ModelData modelData) {
        boolean hasRules = RuleTileRegistry.hasRulesFor(state.getBlock());
        boolean needCull = isCullCandidate(state);

        if (!hasRules && !needCull) {
            return modelData;
        }

        ModelData.Builder builder = modelData.derive()
                .with(BLOCK_POS, pos.immutable())
                .with(LEVEL, level);

        if (hasRules) {
            int[] masks = NeighborDetector.calculateMasks(level, pos, state);

            boolean hasAbove = isMatchingPaneState(state, level.getBlockState(pos.above()));
            boolean hasBelow = isMatchingPaneState(state, level.getBlockState(pos.below()));

            int culledMask = 0;
            boolean is3DCompat = TsAssemblyClientConfig.is3DCompatibilityActiveFor(state);
            boolean isFakeCull = !is3DCompat && TsAssemblyClientConfig.isFakeCullBlock(state);

            if (isFakeCull) {
                for (Direction dir : DIRECTIONS) {
                    BlockPos neighborPos = pos.relative(dir);
                    BlockState neighborState = level.getBlockState(neighborPos);

                    if (neighborState.is(state.getBlock())) {
                        continue;
                    }

                    if (!Block.shouldRenderFace(state, level, pos, dir, neighborPos)) {
                        culledMask |= (1 << dir.get3DDataValue());
                    }
                }
            }

            builder.with(RuleTileModelProperties.NEIGHBOR_MASKS, masks)
                    .with(RuleTileModelProperties.HAS_MATCHING_ABOVE, hasAbove)
                    .with(RuleTileModelProperties.HAS_MATCHING_BELOW, hasBelow)
                    .with(RuleTileModelProperties.CULLED_FACES, culledMask);
        }

        return builder.build();
    }

    private static boolean isMatchingPaneState(BlockState self, BlockState neighbor) {
        if (!neighbor.is(self.getBlock())) return false;

        for (BooleanProperty prop : HORIZONTAL_PROPERTIES) {
            if (self.hasProperty(prop)) {
                if (!neighbor.hasProperty(prop) || self.getValue(prop) != neighbor.getValue(prop)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                    RandomSource rand, ModelData extraData, @Nullable RenderType renderType) {
        if (state == null) {
            return vanillaModel.getQuads(state, side, rand, extraData, renderType);
        }

        BlockPos pos = extraData.has(BLOCK_POS) ? extraData.get(BLOCK_POS) : null;
        BlockGetter level = extraData.has(LEVEL) ? extraData.get(LEVEL) : null;

        // =========================================================================
        // 【核心超速短路 (Early Exit)】
        // 針對標準 6 個外向面，在調用原版 getQuads 前直接比對相鄰方塊
        // =========================================================================
        if (side != null && level != null && pos != null) {
            BlockPos neighborPos = pos.relative(side);
            BlockState neighborState = level.getBlockState(neighborPos);

            if (!neighborState.isAir()) {
                // A. 同方塊內部接觸面剔除
                if (TsAssemblyClientConfig.shouldCullSameBlock(state, neighborState)) {
                    return Collections.emptyList();
                }

                // B. 面對非空氣粗暴剔除
                if (TsAssemblyClientConfig.isCullFacingBlock(state) && TsAssemblyClientConfig.shouldCullFacingIncomplete()) {
                    return Collections.emptyList();
                }

                // C. 完全遮蔽貼合面剔除
                Direction oppositeSide = side.getOpposite();
                if (TsAssemblyClientConfig.shouldCullFacingFullyOccluded(state)) {
                    if (FaceCullHelper.isFaceFullyOccluding(level, neighborPos, oppositeSide, neighborState)) {
                        return Collections.emptyList();
                    }
                }
            }
        }

        List<BakedQuad> baseQuads = vanillaModel.getQuads(state, side, rand, extraData, renderType);
        if (baseQuads.isEmpty()) {
            return baseQuads;
        }

        boolean hasRules = RuleTileRegistry.hasRulesFor(state.getBlock());
        boolean needCull = isCullCandidate(state);

        // 1. 若非 CTM 方塊，經幾何過濾後直接返回
        if (!hasRules) {
            return needCull ? FaceCullHelper.filterQuads(state, level, pos, baseQuads) : baseQuads;
        }

        // 2. CTM 方塊處理
        if (needCull) {
            baseQuads = FaceCullHelper.filterQuads(state, level, pos, baseQuads);
            if (baseQuads.isEmpty()) return baseQuads;
        }

        boolean checkRelative = TsAssemblyClientConfig.isRelativePositionBlock(state);
        boolean is3DCompat = TsAssemblyClientConfig.is3DCompatibilityActiveFor(state);
        boolean isFakeCull = !is3DCompat && TsAssemblyClientConfig.isFakeCullBlock(state);
        boolean invertCenterOnly = TsAssemblyClientConfig.isInvertBackfaceBlock(state);

        int[] masks = extraData.has(RuleTileModelProperties.NEIGHBOR_MASKS)
                ? extraData.get(RuleTileModelProperties.NEIGHBOR_MASKS) : null;
        boolean hasAbove = extraData.has(RuleTileModelProperties.HAS_MATCHING_ABOVE)
                && Boolean.TRUE.equals(extraData.get(RuleTileModelProperties.HAS_MATCHING_ABOVE));
        boolean hasBelow = extraData.has(RuleTileModelProperties.HAS_MATCHING_BELOW)
                && Boolean.TRUE.equals(extraData.get(RuleTileModelProperties.HAS_MATCHING_BELOW));

        int estimatedCap = baseQuads.size() * (isFakeCull ? 4 : 2) + (side == null && isFakeCull ? 16 : 0);
        List<BakedQuad> outputQuads = new ArrayList<>(estimatedCap);

        for (int i = 0; i < baseQuads.size(); i++) {
            BakedQuad baseQ = baseQuads.get(i);
            Direction effectiveSide = is3DCompat ? baseQ.getDirection() : (side != null ? side : baseQ.getDirection());
            if (effectiveSide == null) {
                effectiveSide = resolveQuadDirection(baseQ);
            }

            processSingleQuad(outputQuads, state, baseQ, effectiveSide, masks, hasAbove, hasBelow, checkRelative, isFakeCull, false, invertCenterOnly);
        }

        if (side == null && isFakeCull && extraData.has(RuleTileModelProperties.CULLED_FACES)) {
            int culledMask = extraData.get(RuleTileModelProperties.CULLED_FACES);
            for (Direction dir : DIRECTIONS) {
                if ((culledMask & (1 << dir.get3DDataValue())) != 0) {
                    List<BakedQuad> dirQuads = vanillaModel.getQuads(state, dir, rand, extraData, renderType);
                    int startIdx = outputQuads.size();

                    for (int i = 0; i < dirQuads.size(); i++) {
                        processSingleQuad(outputQuads, state, dirQuads.get(i), dir, masks, hasAbove, hasBelow, checkRelative, isFakeCull, true, invertCenterOnly);
                    }

                    int endIdx = outputQuads.size();
                    for (int i = startIdx; i < endIdx; i++) {
                        outputQuads.set(i, createBackfaceQuad(outputQuads.get(i)));
                    }
                }
            }
        }

        if (side != null && isFakeCull) {
            int startIdx = outputQuads.size();
            for (int i = 0; i < baseQuads.size(); i++) {
                BakedQuad baseQ = baseQuads.get(i);
                processSingleQuad(outputQuads, state, baseQ, side, masks, hasAbove, hasBelow, checkRelative, isFakeCull, true, invertCenterOnly);
            }

            int endIdx = outputQuads.size();
            for (int i = startIdx; i < endIdx; i++) {
                outputQuads.set(i, createBackfaceQuad(outputQuads.get(i)));
            }
        }

        return outputQuads;
    }

    private static Direction resolveQuadDirection(BakedQuad quad) {
        int[] v = quad.getVertices();
        if (v.length >= 32) {
            float x0 = Float.intBitsToFloat(v[0]), y0 = Float.intBitsToFloat(v[1]), z0 = Float.intBitsToFloat(v[2]);
            float x1 = Float.intBitsToFloat(v[8]), y1 = Float.intBitsToFloat(v[9]), z1 = Float.intBitsToFloat(v[10]);
            float x2 = Float.intBitsToFloat(v[16]), y2 = Float.intBitsToFloat(v[17]), z2 = Float.intBitsToFloat(v[18]);
            float nx = (y1 - y0) * (z2 - z0) - (z1 - z0) * (y2 - y0);
            float ny = (z1 - z0) * (x2 - x0) - (x1 - x0) * (z2 - z0);
            float nz = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);

            float absX = Math.abs(nx), absY = Math.abs(ny), absZ = Math.abs(nz);
            if (absY >= absX && absY >= absZ) return ny > 0 ? Direction.UP : Direction.DOWN;
            if (absZ >= absX && absZ >= absY) return nz > 0 ? Direction.SOUTH : Direction.NORTH;
            if (absX > 0) return nx > 0 ? Direction.EAST : Direction.WEST;
        }
        return Direction.UP;
    }

    private static boolean isUvMirrored(BakedQuad quad) {
        int[] v = quad.getVertices();
        float u0 = Float.intBitsToFloat(v[4]);
        float v0 = Float.intBitsToFloat(v[5]);
        float u1 = Float.intBitsToFloat(v[12]);
        float v1 = Float.intBitsToFloat(v[13]);
        float u3 = Float.intBitsToFloat(v[28]);
        float v3 = Float.intBitsToFloat(v[29]);

        return ((u1 - u0) * (v3 - v0) - (v1 - v0) * (u3 - u0)) > 0;
    }

    private static boolean isQuadBehindCenter(BakedQuad quad, Direction side) {
        int[] v = quad.getVertices();
        float avgX = (Float.intBitsToFloat(v[0]) + Float.intBitsToFloat(v[8])  + Float.intBitsToFloat(v[16]) + Float.intBitsToFloat(v[24])) * 0.25f - 0.5f;
        float avgY = (Float.intBitsToFloat(v[1]) + Float.intBitsToFloat(v[9])  + Float.intBitsToFloat(v[17]) + Float.intBitsToFloat(v[25])) * 0.25f - 0.5f;
        float avgZ = (Float.intBitsToFloat(v[2]) + Float.intBitsToFloat(v[10]) + Float.intBitsToFloat(v[18]) + Float.intBitsToFloat(v[26])) * 0.25f - 0.5f;

        float dot = avgX * side.getStepX() + avgY * side.getStepY() + avgZ * side.getStepZ();
        return dot < -1e-4f;
    }

    private static BakedQuad offsetQuad(BakedQuad quad, Direction dir, float amount) {
        if (dir == null || amount == 0.0f) return quad;
        int[] src = quad.getVertices();
        int[] dst = new int[32];

        float offX = dir.getStepX() * amount;
        float offY = dir.getStepY() * amount;
        float offZ = dir.getStepZ() * amount;

        for (int i = 0; i < 4; i++) {
            int off = i * 8;
            dst[off + 0] = Float.floatToRawIntBits(Float.intBitsToFloat(src[off + 0]) + offX);
            dst[off + 1] = Float.floatToRawIntBits(Float.intBitsToFloat(src[off + 1]) + offY);
            dst[off + 2] = Float.floatToRawIntBits(Float.intBitsToFloat(src[off + 2]) + offZ);
            System.arraycopy(src, off + 3, dst, off + 3, 5);
        }

        return new BakedQuad(dst, quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
    }

    private static BakedQuad createBackfaceQuad(BakedQuad quad) {
        int[] src = quad.getVertices();
        int[] dst = new int[32];

        Direction origDir = quad.getDirection();
        Direction oppDir = (origDir != null) ? origDir.getOpposite() : null;

        float offX = (oppDir != null) ? oppDir.getStepX() * BACK_INWARD_OFFSET : 0.0f;
        float offY = (oppDir != null) ? oppDir.getStepY() * BACK_INWARD_OFFSET : 0.0f;
        float offZ = (oppDir != null) ? oppDir.getStepZ() * BACK_INWARD_OFFSET : 0.0f;

        copyVertexWithOffset(src, 0, dst, 0, offX, offY, offZ);
        copyVertexWithOffset(src, 3, dst, 1, offX, offY, offZ);
        copyVertexWithOffset(src, 2, dst, 2, offX, offY, offZ);
        copyVertexWithOffset(src, 1, dst, 3, offX, offY, offZ);

        return new BakedQuad(dst, quad.getTintIndex(), oppDir, quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
    }

    private static void copyVertexWithOffset(int[] src, int srcIdx, int[] dst, int dstIdx, float offX, float offY, float offZ) {
        int sOff = srcIdx * 8;
        int dOff = dstIdx * 8;
        dst[dOff + 0] = Float.floatToRawIntBits(Float.intBitsToFloat(src[sOff + 0]) + offX);
        dst[dOff + 1] = Float.floatToRawIntBits(Float.intBitsToFloat(src[sOff + 1]) + offY);
        dst[dOff + 2] = Float.floatToRawIntBits(Float.intBitsToFloat(src[sOff + 2]) + offZ);
        System.arraycopy(src, sOff + 3, dst, dOff + 3, 5);
    }

    private void processSingleQuad(List<BakedQuad> output, BlockState state, BakedQuad baseQ,
                                   Direction effectiveSide, int[] masks,
                                   boolean hasAbove, boolean hasBelow, boolean checkRelative, boolean isFakeCull,
                                   boolean isBackface, boolean invertCenterOnly) {
        RuleConfig rule = RuleTileRegistry.getMatchedRule(state.getBlock(), effectiveSide, baseQ.getSprite());
        if (rule == null) {
            addOutputQuad(output, baseQ, effectiveSide, isFakeCull);
            return;
        }

        TextureAtlasSprite mainSprite = RuleTileRegistry.getSprite(rule.texture);
        TextureAtlasSprite origSprite = rule.hasOriginalTexture ? RuleTileRegistry.getSprite(rule.original_texture) : null;

        boolean isVertical = (effectiveSide == Direction.UP || effectiveSide == Direction.DOWN);
        boolean flipCenterU = isBackface && invertCenterOnly && !isVertical;
        boolean flipCenterV = isBackface && invertCenterOnly && isVertical;

        if (rule.isPaneEdge) {
            boolean isConnected = (effectiveSide == Direction.UP) ? hasAbove
                    : (effectiveSide == Direction.DOWN) ? hasBelow : (hasAbove || hasBelow);

            if (isConnected) {
                if (rule.hasMainTexture && mainSprite != null) {
                    BakedQuad remapped = QuadSubUVMapper.remapToGrid(baseQ, mainSprite, new RuleTileSolver.TileCoord(0, 0), 1, 1, flipCenterU, flipCenterV);
                    addOutputQuad(output, remapped, effectiveSide, isFakeCull);
                }
            } else {
                if (rule.hasOriginalTexture && origSprite != null) {
                    BakedQuad remapped = QuadSubUVMapper.remapToGrid(baseQ, origSprite, new RuleTileSolver.TileCoord(0, 0), 1, 1, flipCenterU, flipCenterV);
                    addOutputQuad(output, remapped, effectiveSide, isFakeCull);
                } else {
                    addOutputQuad(output, baseQ, effectiveSide, isFakeCull);
                }
            }
            return;
        }

        int mask = (masks != null) ? masks[effectiveSide.get3DDataValue()] : 0;
        boolean shouldFlip = isUvMirrored(baseQ);

        if (checkRelative && isQuadBehindCenter(baseQ, effectiveSide)) {
            shouldFlip = !shouldFlip;
        }

        if (shouldFlip) {
            mask = RuleTileSolver.flipHorizontalMask(mask);
        }

        if (rule.isComposite) {
            RuleTileSolver.TileCoord[] activeLayers = RuleTileSolver.resolveCompositeLayers(mask);
            boolean drawn = false;

            if (mainSprite != null) {
                for (RuleTileSolver.TileCoord layer : activeLayers) {
                    RuleConfig.TileMode mode = rule.tileModes[layer.col()][layer.row()];
                    if (mode == RuleConfig.TileMode.MAIN) {
                        boolean isCenterTile = (layer.col() == 1 && layer.row() == 1);
                        boolean flipU = isCenterTile && flipCenterU;
                        boolean flipV = isCenterTile && flipCenterV;

                        BakedQuad remapped = QuadSubUVMapper.remapToGrid(baseQ, mainSprite, layer, 3, 3, flipU, flipV);
                        addOutputQuad(output, remapped, effectiveSide, isFakeCull);
                        drawn = true;
                    }
                }
            }

            if (!drawn) {
                if (rule.hasOriginalTexture && origSprite != null) {
                    BakedQuad remapped = QuadSubUVMapper.remapToGrid(baseQ, origSprite, new RuleTileSolver.TileCoord(0, 0), 1, 1, flipCenterU, flipCenterV);
                    addOutputQuad(output, remapped, effectiveSide, isFakeCull);
                } else {
                    addOutputQuad(output, baseQ, effectiveSide, isFakeCull);
                }
            }
        } else {
            RuleTileSolver.TileCoord coord = RuleTileSolver.resolveFull(mask);
            if (coord == null) {
                addOutputQuad(output, baseQ, effectiveSide, isFakeCull);
                return;
            }

            boolean isFullCenter = (coord.col() == 0 && coord.row() == 0);
            boolean flipU = isFullCenter && flipCenterU;
            boolean flipV = isFullCenter && flipCenterV;

            RuleConfig.TileMode mode = rule.tileModes[coord.col()][coord.row()];
            switch (mode) {
                case MAIN -> {
                    if (mainSprite != null) {
                        BakedQuad remapped = QuadSubUVMapper.remapToGrid(baseQ, mainSprite, coord, 6, 6, flipU, flipV);
                        addOutputQuad(output, remapped, effectiveSide, isFakeCull);
                    } else {
                        addOutputQuad(output, baseQ, effectiveSide, isFakeCull);
                    }
                }
                case ORIGINAL_TEXTURE -> {
                    if (origSprite != null) {
                        BakedQuad remapped = QuadSubUVMapper.remapToGrid(baseQ, origSprite, new RuleTileSolver.TileCoord(0, 0), 1, 1, flipU, flipV);
                        addOutputQuad(output, remapped, effectiveSide, isFakeCull);
                    } else {
                        addOutputQuad(output, baseQ, effectiveSide, isFakeCull);
                    }
                }
                case FALLBACK_VANILLA, EMPTY -> addOutputQuad(output, baseQ, effectiveSide, isFakeCull);
            }
        }
    }

    private static void addOutputQuad(List<BakedQuad> output, BakedQuad quad, Direction effectiveSide, boolean isFakeCull) {
        if (isFakeCull && effectiveSide != null) {
            output.add(offsetQuad(quad, effectiveSide.getOpposite(), FRONT_INWARD_OFFSET));
        } else {
            output.add(quad);
        }
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        return vanillaModel.getRenderTypes(state, rand, data);
    }

    @Override
    public BakedModel applyTransform(net.minecraft.world.item.ItemDisplayContext transformType, 
                                     com.mojang.blaze3d.vertex.PoseStack poseStack, 
                                     boolean applyLeftHandTransform) {
        return vanillaModel.applyTransform(transformType, poseStack, applyLeftHandTransform);
    }

    @Override
    public List<BakedModel> getRenderPasses(net.minecraft.world.item.ItemStack itemStack, boolean fabulous) {
        return vanillaModel.getRenderPasses(itemStack, fabulous);
    }

    @Override public ItemOverrides getOverrides() { return vanillaModel.getOverrides(); }
    @Override public ItemTransforms getTransforms() { return vanillaModel.getTransforms(); }
    @Override public boolean useAmbientOcclusion() { return vanillaModel.useAmbientOcclusion(); }
    @Override public boolean isGui3d() { return vanillaModel.isGui3d(); }
    @Override public boolean usesBlockLight() { return vanillaModel.usesBlockLight(); }
    @Override public boolean isCustomRenderer() { return vanillaModel.isCustomRenderer(); }
    @Override public TextureAtlasSprite getParticleIcon() { return vanillaModel.getParticleIcon(); }
    @Override public TextureAtlasSprite getParticleIcon(ModelData data) { return vanillaModel.getParticleIcon(data); }
}