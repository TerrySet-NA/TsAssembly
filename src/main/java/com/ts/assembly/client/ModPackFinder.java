package com.ts.assembly.client;

import com.ts.assembly.TsAssemblyMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import java.nio.file.Path;
import java.util.Optional;

@SuppressWarnings("null")
public class ModPackFinder {

    // 建立一個允許玩家在選單中自由操作的 PackSource 標記
    private static final PackSource MOD_SOURCE = PackSource.create(
            name -> Component.translatable("pack.tsassembly.builtin"),
            true // true 代表允許玩家在資源包選單中自由啟用/停用
    );

    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }

        var modFile = ModList.get().getModFileById(TsAssemblyMod.MOD_ID);
        if (modFile == null) return;

        Path resourcePath = modFile.getFile().findResource("resourcepacks/tsassembly_connected_glass");

        // 每次原版重新掃描資源庫時，都動態建構乾淨的 Pack 實例
        event.addRepositorySource(packConsumer -> {
            PackLocationInfo locationInfo = new PackLocationInfo(
                    "tsassembly_connected_glass",
                    Component.translatable("pack.tsassembly.glass_pack_title"),
                    MOD_SOURCE,
                    Optional.empty()
            );

            // 1. required = false (可自由開關)
            // 2. defaultPosition = TOP (預設放最上面)
            // 3. fixedPosition = false (關鍵！設為 false 才能正確觸發選單 Dirty 狀態並重載)
            PackSelectionConfig selectionConfig = new PackSelectionConfig(
                    false,
                    Pack.Position.TOP,
                    false
            );

            Pack pack = Pack.readMetaAndCreate(
                    locationInfo,
                    new PathPackResources.PathResourcesSupplier(resourcePath),
                    PackType.CLIENT_RESOURCES,
                    selectionConfig
            );

            if (pack != null) {
                packConsumer.accept(pack);
            }
        });
    }
}