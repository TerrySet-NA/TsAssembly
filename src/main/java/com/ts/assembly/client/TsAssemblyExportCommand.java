package com.ts.assembly.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

@SuppressWarnings("null")
public class TsAssemblyExportCommand {
    public static final String DEFAULT_PACK_NAME = "TsAssembly Export";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(
            Commands.literal("tsassembly")
                .then(Commands.literal("export")
                    .then(Commands.argument("block", BlockStateArgument.block(context))
                        // 1. 預設 3x3 組合模式: /tsassembly export <block>
                        .executes(ctx -> executeExport(
                            BlockStateArgument.getBlock(ctx, "block").getState(),
                            TsAssemblyPackExporter.ExportMode.COMPOSITE,
                            DEFAULT_PACK_NAME
                        ))
                        // 2. 指定模式: /tsassembly export <block> <composite|full>
                        .then(Commands.literal("composite")
                            .executes(ctx -> executeExport(
                                BlockStateArgument.getBlock(ctx, "block").getState(),
                                TsAssemblyPackExporter.ExportMode.COMPOSITE,
                                DEFAULT_PACK_NAME
                            ))
                            .then(Commands.argument("pack_name", StringArgumentType.string())
                                .executes(ctx -> executeExport(
                                    BlockStateArgument.getBlock(ctx, "block").getState(),
                                    TsAssemblyPackExporter.ExportMode.COMPOSITE,
                                    StringArgumentType.getString(ctx, "pack_name")
                                ))
                            )
                        )
                        .then(Commands.literal("full")
                            .executes(ctx -> executeExport(
                                BlockStateArgument.getBlock(ctx, "block").getState(),
                                TsAssemblyPackExporter.ExportMode.FULL,
                                DEFAULT_PACK_NAME
                            ))
                            .then(Commands.argument("pack_name", StringArgumentType.string())
                                .executes(ctx -> executeExport(
                                    BlockStateArgument.getBlock(ctx, "block").getState(),
                                    TsAssemblyPackExporter.ExportMode.FULL,
                                    StringArgumentType.getString(ctx, "pack_name")
                                ))
                            )
                        )
                        // 3. 簡寫包名: /tsassembly export <block> [pack_name]
                        .then(Commands.argument("pack_name", StringArgumentType.string())
                            .executes(ctx -> executeExport(
                                BlockStateArgument.getBlock(ctx, "block").getState(),
                                TsAssemblyPackExporter.ExportMode.COMPOSITE,
                                StringArgumentType.getString(ctx, "pack_name")
                            ))
                        )
                    )
                )
        );
    }

    private static int executeExport(BlockState state, TsAssemblyPackExporter.ExportMode mode, String packName) {
        Minecraft mc = Minecraft.getInstance();
        try {
            int count = TsAssemblyPackExporter.export(state, mode, packName);
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.translatable(
                        "command.tsassembly.export.success",
                        state.getBlock().getName(),
                        count,
                        mode.name(),
                        packName
                ).withStyle(ChatFormatting.GREEN));
            }
            return 1;
        } catch (Exception e) {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.translatable(
                        "command.tsassembly.export.failed",
                        e.getMessage() != null ? e.getMessage() : "Unknown error"
                ).withStyle(ChatFormatting.RED));
            }
            e.printStackTrace();
            return 0;
        }
    }
}