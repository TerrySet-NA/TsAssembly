package com.ts.assembly.client.model;

import net.neoforged.neoforge.client.model.data.ModelProperty;

public class RuleTileModelProperties {
    // 儲存 6 個面的 8-bit mask
    public static final ModelProperty<int[]> NEIGHBOR_MASKS = new ModelProperty<>();
    
    // 分別記錄上方與下方是否有相同狀態的玻璃片
    public static final ModelProperty<Boolean> HAS_MATCHING_ABOVE = new ModelProperty<>();
    public static final ModelProperty<Boolean> HAS_MATCHING_BELOW = new ModelProperty<>();

    public static final ModelProperty<Integer> CULLED_FACES = new ModelProperty<>();
}