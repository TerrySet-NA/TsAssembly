package com.ts.assembly.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    
    /**
     * 直接取得 SpriteContents 內部的原始未壓縮貼圖，無任何反射開銷
     */
    @Accessor("originalImage")
    NativeImage tsassembly$getOriginalImage();
}