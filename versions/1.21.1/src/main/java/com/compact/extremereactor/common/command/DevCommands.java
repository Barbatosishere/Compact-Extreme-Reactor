package com.compact.extremereactor.common.command;

import com.compact.extremereactor.CompactExtremeReactor;
import com.compact.extremereactor.common.capability.CompactEnergyStorage;
import com.compact.extremereactor.common.capability.CompactEnergySink;
import com.compact.extremereactor.common.capability.CompactFluidizerItemHandler;
import com.compact.extremereactor.common.config.CompactConfig;
import com.compact.extremereactor.common.multiblock.CompactFluidizerController;
import com.compact.extremereactor.common.multiblock.CompactReactorController;
import com.compact.extremereactor.common.multiblock.CompactTurbineController;
import com.compact.extremereactor.common.multiblock.ICompactController;
import com.compact.extremereactor.common.tile.AbstractCompactMachineTileEntity;
import com.compact.extremereactor.common.tile.CompactFluidizerTileEntity;
import com.compact.extremereactor.common.tile.CompactReactorTileEntity;
import com.compact.extremereactor.common.tile.CompactTurbineTileEntity;
import com.mojang.brigadier.CommandDispatcher;
import it.zerono.mods.extremereactors.config.Config;
import it.zerono.mods.extremereactors.api.coolant.FluidMappingsRegistry;
import it.zerono.mods.extremereactors.api.coolant.TransitionsRegistry;
import it.zerono.mods.extremereactors.api.reactor.Reactant;
import it.zerono.mods.extremereactors.gamecontent.multiblock.common.FluidContainer;
import it.zerono.mods.extremereactors.gamecontent.multiblock.common.FluidType;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.MultiblockReactor;
import it.zerono.mods.zerocore.lib.data.WideAmount;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import it.zerono.mods.zerocore.lib.energy.WideEnergyBuffer;
import it.zerono.mods.zerocore.lib.data.nbt.ISyncableEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 开发环境专用诊断指令（{@code /cerdev}）。仅在非生产环境注册
 * （{@code !FMLEnvironment.production}），生产 jar 不含此指令的注册路径。
 *
 * 用途：在无流体管道 mod 的 dev 环境里验证水→蒸汽链路——
 *   - fill/drain 走与真实管道完全相同的 {@code level.getCapability(FluidHandler)} 路径；
 *   - dump 反射读取 ER 内部 FluidContainer 与冷却剂注册表解析结果，
 *     用于定位汽化链路在哪一环被阻断。
 * 需要 op 权限 2（RCON 默认 level 4 可用）。
 */
public final class DevCommands {

    private DevCommands() {
    }

    /** 注册命令（由主类在 !production 时挂到 NeoForge.EVENT_BUS）。 */
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        final CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("cerdev")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("chunkstate")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    final BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
                                    final ServerLevel level = ctx.getSource().getLevel();
                                    final boolean loaded = level.getChunkSource().getChunkNow(
                                            pos.getX() >> 4, pos.getZ() >> 4) != null;
                                    feedback(ctx.getSource(), "chunkLoaded=%s blockTicking=%s".formatted(
                                            loaded, loaded && level.shouldTickBlocksAt(pos)));
                                    return 1;
                                }))));
        dispatcher.register(Commands.literal("cerdev")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("fill")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("fluid", ResourceLocationArgument.id())
                                        .then(Commands.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                                .executes(ctx -> devFill(ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                        ResourceLocationArgument.getId(ctx, "fluid"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "amount")))))))
                .then(Commands.literal("drain")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .executes(ctx -> devDrain(ctx.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "amount"),
                                                null))
                                        .then(Commands.argument("fluid", ResourceLocationArgument.id())
                                                .executes(ctx -> devDrain(ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "amount"),
                                                        ResourceLocationArgument.getId(ctx, "fluid")))))))
                .then(Commands.literal("active")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                        .executes(ctx -> devActive(ctx.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "on"))))))
                .then(Commands.literal("fuel")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("item", ResourceLocationArgument.id())
                                        .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                                .executes(ctx -> devFuel(ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                        ResourceLocationArgument.getId(ctx, "item"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count")))))))
                .then(Commands.literal("dump")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> devDump(ctx.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.literal("energy")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.literal("fill")
                                        .executes(ctx -> devEnergyFill(ctx.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"))))
                                .then(Commands.literal("extract")
                                        .then(Commands.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                                .executes(ctx -> devEnergyExtract(ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "amount")))))))
                .then(Commands.literal("selftest")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> devSelfTest(ctx.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"))))));

        // rods / waste 子命令：扁平构建（每层独立语句， brigadier 自动合并到已注册的 "cerdev" 字面量）。
        // rods：read 读取；adj 相对调整（与 GUI 控制棒包相同的服务端路径）；set 绝对设置。
        final var rodsPos = Commands.argument("pos", BlockPosArgument.blockPos());
        rodsPos.executes(ctx -> devRods(ctx.getSource(),
                BlockPosArgument.getLoadedBlockPos(ctx, "pos"), "read", 0));
        final var rodsAdj = Commands.literal("adj");
        rodsAdj.then(Commands.argument("delta", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                .executes(ctx -> devRods(ctx.getSource(),
                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"), "adj",
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "delta"))));
        rodsPos.then(rodsAdj);
        final var rodsSet = Commands.literal("set");
        rodsSet.then(Commands.argument("ratio", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                .executes(ctx -> devRods(ctx.getSource(),
                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"), "set",
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "ratio"))));
        rodsPos.then(rodsSet);
        dispatcher.register(Commands.literal("cerdev")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("rods").then(rodsPos)));

        // waste：从废物槽提取物品（与漏斗/管道相同的 extractItem 路径），count 可选（默认 64）。
        final var wastePos = Commands.argument("pos", BlockPosArgument.blockPos());
        wastePos.executes(ctx -> devWaste(ctx.getSource(),
                BlockPosArgument.getLoadedBlockPos(ctx, "pos"), 64));
        final var wasteCount = Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1));
        wasteCount.executes(ctx -> devWaste(ctx.getSource(),
                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count")));
        wastePos.then(wasteCount);
        // inject：直接注入废物反应物（诊断构造测试态，与 fill 注水同性质）。
        final var wasteInject = Commands.literal("inject");
        wasteInject.then(Commands.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                .executes(ctx -> devWasteInject(ctx.getSource(),
                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "amount"))));
        wastePos.then(wasteInject);
        dispatcher.register(Commands.literal("cerdev")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("waste").then(wastePos)));

        // item：走真实物品能力路径插入物品（流化器固体进料诊断；item 必填，count 可选默认 1）。
        final var itemPos = Commands.argument("pos", BlockPosArgument.blockPos());
        final var itemArg = Commands.argument("item", ResourceLocationArgument.id());
        itemArg.executes(ctx -> devItem(ctx.getSource(),
                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                ResourceLocationArgument.getId(ctx, "item"), 1));
        final var itemCount = Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1));
        itemCount.executes(ctx -> devItem(ctx.getSource(),
                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                ResourceLocationArgument.getId(ctx, "item"),
                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count")));
        itemArg.then(itemCount);
        itemPos.then(itemArg);
        dispatcher.register(Commands.literal("cerdev")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("item").then(itemPos)));

        // opengui：为在线玩家远程打开机器菜单（与右键相同的 openMenu 路径；自动化客户端诊断用）。
        final var openPos = Commands.argument("pos", BlockPosArgument.blockPos());
        openPos.executes(ctx -> devOpenGui(ctx.getSource(),
                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                ctx.getSource().getPlayerOrException().getGameProfile().getName()));
        openPos.then(Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                .executes(ctx -> devOpenGui(ctx.getSource(),
                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player"))));
        dispatcher.register(Commands.literal("cerdev")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("opengui").then(openPos)));
        CompactExtremeReactor.LOGGER.info("cerdev 诊断指令已注册（仅开发环境）");
    }

    // ------------------------------------------------------------------
    // 子命令实现
    // ------------------------------------------------------------------

    /** fuel：走真实物品能力路径插入燃料（与管道/漏斗完全一致）。 */
    private static int devFuel(CommandSourceStack source, BlockPos pos, ResourceLocation itemId, int count) {
        if (!(machineTile(source.getLevel(), pos) instanceof com.compact.extremereactor.common.tile.CompactReactorTileEntity reactor)) {
            source.sendFailure(Component.literal("目标不是压缩反应堆"));
            return 0;
        }
        final net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(itemId);
        if (item == net.minecraft.world.item.Items.AIR) {
            source.sendFailure(Component.literal("未知物品: " + itemId));
            return 0;
        }
        final net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item, count);
        final net.neoforged.neoforge.items.IItemHandler items = reactor.getItemHandler(null);
        if (items == null) {
            source.sendFailure(Component.literal("反应堆无物品能力"));
            return 0;
        }
        final net.minecraft.world.item.ItemStack remainder = items.insertItem(0, stack, false);
        final int inserted = count - remainder.getCount();
        feedback(source, "fuel %s: 请求 %d，插入 %d".formatted(itemId, count, inserted));
        return inserted > 0 ? 1 : 0;
    }

    /** fill：走真实能力路径（与流体管道完全一致）。 */
    private static int devFill(CommandSourceStack source, BlockPos pos, ResourceLocation fluidId, int amount) {
        final IFluidHandler handler = fluidHandler(source.getLevel(), pos);
        if (handler == null) {
            source.sendFailure(Component.literal("目标方块无流体能力（不是压缩机器或未加载）"));
            return 0;
        }
        final Fluid fluid = BuiltInRegistries.FLUID.get(fluidId);
        if (fluid == null || fluid.defaultFluidState().isEmpty()) {
            source.sendFailure(Component.literal("未知流体: " + fluidId));
            return 0;
        }
        final int accepted = handler.fill(new FluidStack(fluid, amount), IFluidHandler.FluidAction.EXECUTE);
        feedback(source, "fill %s: 请求 %d mB，实际接受 %d mB".formatted(fluidId, amount, accepted));
        return accepted > 0 ? 1 : 0;
    }

    /**
     * drain：走真实能力路径抽取。
     * 无 fluidId 时调用 {@code drain(int)}（反应堆只出蒸汽 / 涡轮机出水）；
     * 带 fluidId 时调用 {@code drain(FluidStack)}，用于抽废液（青化物/品红/赤锶）。
     */
    private static int devDrain(CommandSourceStack source, BlockPos pos, int amount,
                                @org.jetbrains.annotations.Nullable ResourceLocation fluidId) {
        final IFluidHandler handler = fluidHandler(source.getLevel(), pos);
        if (handler == null) {
            source.sendFailure(Component.literal("目标方块无流体能力"));
            return 0;
        }
        final FluidStack drained;
        if (fluidId == null) {
            drained = handler.drain(amount, IFluidHandler.FluidAction.EXECUTE);
        } else {
            final Fluid fluid = BuiltInRegistries.FLUID.get(fluidId);
            if (fluid == null || fluid.defaultFluidState().isEmpty()) {
                source.sendFailure(Component.literal("未知流体: " + fluidId));
                return 0;
            }
            drained = handler.drain(new FluidStack(fluid, amount), IFluidHandler.FluidAction.EXECUTE);
        }
        if (drained.isEmpty()) {
            feedback(source, "drain: 无流体可抽取");
            return 0;
        }
        feedback(source, "drain: 抽取 %s x%d mB".formatted(
                BuiltInRegistries.FLUID.getKey(drained.getFluid()), drained.getAmount()));
        return 1;
    }

    /** active：等价于 GUI 开关按钮的服务端路径。 */
    private static int devActive(CommandSourceStack source, BlockPos pos, boolean on) {
        if (!(machineTile(source.getLevel(), pos) instanceof AbstractCompactMachineTileEntity tile)) {
            source.sendFailure(Component.literal("目标不是压缩机器"));
            return 0;
        }
        final var controller = tile.getController();
        if (controller == null) {
            source.sendFailure(Component.literal("控制器未初始化（initFailed?）"));
            return 0;
        }
        controller.setMachineActive(on);
        tile.setChanged();
        feedback(source, "active=%s".formatted(on));
        return 1;
    }

    /** energy fill：把内部 FE 缓存灌满，供满仓停机回归。不改 isMachineActive。 */
    private static int devEnergyFill(CommandSourceStack source, BlockPos pos) {
        if (!(machineTile(source.getLevel(), pos) instanceof AbstractCompactMachineTileEntity tile)) {
            source.sendFailure(Component.literal("目标不是压缩机器"));
            return 0;
        }
        final var controller = tile.getController();
        if (controller == null) {
            source.sendFailure(Component.literal("控制器未初始化（initFailed?）"));
            return 0;
        }
        final WideAmount inserted = controller.insertEnergy(
                EnergySystem.ForgeEnergy,
                controller.getCapacity(EnergySystem.ForgeEnergy),
                OperationMode.Execute);
        tile.setChanged();
        feedback(source, "energyFill inserted=%d feStored=%d/%d energyFull=%s".formatted(
                inserted.longValue(),
                controller.getEnergyStored(EnergySystem.ForgeEnergy).longValue(),
                controller.getCapacity(EnergySystem.ForgeEnergy).longValue(),
                controller.isEnergyBufferFull()));
        return 1;
    }

    /** energy extract：从内部 FE 缓存抽出能量，腾出空位后满仓停机应自动恢复。 */
    private static int devEnergyExtract(CommandSourceStack source, BlockPos pos, int amount) {
        if (!(machineTile(source.getLevel(), pos) instanceof AbstractCompactMachineTileEntity tile)) {
            source.sendFailure(Component.literal("目标不是压缩机器"));
            return 0;
        }
        final var controller = tile.getController();
        if (controller == null) {
            source.sendFailure(Component.literal("控制器未初始化（initFailed?）"));
            return 0;
        }
        final WideAmount extracted = controller.extractEnergy(
                EnergySystem.ForgeEnergy, WideAmount.from(amount), OperationMode.Execute);
        tile.setChanged();
        feedback(source, "energyExtract extracted=%d feStored=%d/%d energyFull=%s".formatted(
                extracted.longValue(),
                controller.getEnergyStored(EnergySystem.ForgeEnergy).longValue(),
                controller.getCapacity(EnergySystem.ForgeEnergy).longValue(),
                controller.isEnergyBufferFull()));
        return extracted.isZero() ? 0 : 1;
    }

    /**
     * selftest：运行不改写目标存档的回归断言。
     *
     * 该命令只使用公开能力/控制器接口；涡轮机旧 NBT 测试在内存中的临时控制器上执行，
     * 不会调用区块卸载、加载邻区块或修改目标机器的持久化数据。
     */
    private static int devSelfTest(CommandSourceStack source, BlockPos pos) {
        final Level level = source.getLevel();
        if (!(machineTile(level, pos) instanceof AbstractCompactMachineTileEntity tile)) {
            source.sendFailure(Component.literal("目标不是压缩机器"));
            return 0;
        }
        final var controller = tile.getController();
        if (controller == null || tile.isControllerInitFailed()) {
            source.sendFailure(Component.literal("控制器未初始化或已失败"));
            return 0;
        }

        boolean passed = true;
        feedback(source, "=== selftest @%s ===".formatted(pos.toShortString()));
        passed &= selfTestMenuData(source, tile);
        passed &= selfTestFluidRouting(source, tile);
        passed &= selfTestFractionalEnergy(source);
        if (tile instanceof CompactReactorTileEntity || tile instanceof CompactTurbineTileEntity) {
            passed &= selfTestGeneratorCapacity(source, level, pos, tile instanceof CompactTurbineTileEntity);
        }

        if (tile instanceof CompactReactorTileEntity reactor) {
            final int before = reactor.getControlRodInsertionRatio();
            final int delta = before <= 95 ? 5 : -5;
            final int expected = Math.clamp(before + delta, 0, 100);
            final int changed = reactor.adjustControlRodInsertionRatio(delta);
            final int restored = reactor.adjustControlRodInsertionRatio(-delta);
            final boolean rodPassed = changed == expected && restored == before;
            feedback(source, "controlRodRelative=%s (before=%d changed=%d restored=%d)"
                    .formatted(rodPassed ? "PASS" : "FAIL", before, changed, restored));
            passed &= rodPassed;

            final var itemHandler = reactor.getItemHandler(null);
            final boolean invalidItemSlots = itemHandler != null
                    && itemHandler.getSlots() == 2
                    && itemHandler.getSlotLimit(-1) == 0
                    && itemHandler.getSlotLimit(2) == 0
                    && itemHandler.getStackInSlot(-1).isEmpty()
                    && itemHandler.getStackInSlot(2).isEmpty()
                    && itemHandler.extractItem(-1, 1, false).isEmpty()
                    && itemHandler.extractItem(2, 1, false).isEmpty()
                    && !itemHandler.isItemValid(-1, net.minecraft.world.item.ItemStack.EMPTY)
                    && !itemHandler.isItemValid(2, net.minecraft.world.item.ItemStack.EMPTY);
            feedback(source, "reactorInvalidItemSlots=%s".formatted(invalidItemSlots ? "PASS" : "FAIL"));
            passed &= invalidItemSlots;
        }

        final CompactEnergyStorage oldEnergy = new CompactEnergyStorage(controller);
        final int liveEnergy = oldEnergy.getEnergyStored();
        oldEnergy.release();
        final boolean released = oldEnergy.getEnergyStored() == 0
                && oldEnergy.getMaxEnergyStored() == 0
                && oldEnergy.extractEnergy(1, false) == 0
                && !oldEnergy.canExtract();
        feedback(source, "releasedEnergyReference=%s (before=%d)"
                .formatted(released ? "PASS" : "FAIL", liveEnergy));
        passed &= released;

        final CompactFluidizerTileEntity pendingFluidizer = new CompactFluidizerTileEntity(
                pos, com.compact.extremereactor.common.Content.COMPACT_FLUIDIZER.get().defaultBlockState());
        final CompactFluidizerItemHandler pendingItems = new CompactFluidizerItemHandler(pendingFluidizer);
        final boolean pendingSlots = pendingItems.getSlots() == 2
                && pendingItems.getStackInSlot(0).isEmpty()
                && pendingItems.extractItem(0, 1, false).isEmpty()
                && !pendingFluidizer.isControllerReady();
        pendingItems.release();
        final boolean itemLifecycle = pendingSlots && pendingItems.getSlots() == 0;
        feedback(source, "fluidizerPendingItemSlots=%s".formatted(itemLifecycle ? "PASS" : "FAIL"));
        passed &= itemLifecycle;

        if (tile instanceof CompactFluidizerTileEntity) {
            passed &= selfTestFluidizerEnergy(source, level, pos);
        }

        if (tile instanceof CompactTurbineTileEntity) {
            final CompactTurbineController restoredController = new CompactTurbineController(
                    level, pos, CompactConfig.TURBINE_COIL_RADIUS.get(),
                    CompactConfig.TURBINE_SIZE_X.get(), CompactConfig.TURBINE_SIZE_Y.get(),
                    CompactConfig.TURBINE_SIZE_Z.get());
            restoredController.simulateAssembly();
            final CompoundTag saved = restoredController.syncDataTo(new CompoundTag(),
                    level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
            final CompoundTag oldBuffer = saved.getCompound("buffer");
            oldBuffer.put("maxInsert", WideAmount.ZERO.serializeToNBT());
            saved.put("buffer", oldBuffer);
            restoredController.syncDataFrom(saved, level.registryAccess(),
                    ISyncableEntity.SyncReason.FullSync);
            final boolean turbinePassed = restoredController.canInsert()
                    && restoredController.isInductorEngaged();
            feedback(source, "turbineLegacyRestore=%s (canInsert=%s inductor=%s)"
                    .formatted(turbinePassed ? "PASS" : "FAIL",
                            restoredController.canInsert(), restoredController.isInductorEngaged()));
            passed &= turbinePassed;
            passed &= selfTestTurbineCondensation(source, restoredController);
        }

        feedback(source, "=== selftest %s ===".formatted(passed ? "PASS" : "FAIL"));
        return passed ? 1 : 0;
    }

    private static boolean selfTestFractionalEnergy(CommandSourceStack source) {
        final WideEnergyBuffer buffer = new WideEnergyBuffer(EnergySystem.ForgeEnergy,
                WideAmount.from(10000), WideAmount.from(10000), WideAmount.from(10000));
        final CompactEnergyStorage output = new CompactEnergyStorage(buffer);
        final CompactEnergySink input = new CompactEnergySink(buffer);
        boolean passed = true;
        buffer.setEnergyStored(WideAmount.from(123.5d), EnergySystem.ForgeEnergy);
        final boolean extract = output.extractEnergy(1000, true) == 123
                && buffer.getEnergyStored(EnergySystem.ForgeEnergy).doubleValue() == 123.5d
                && output.extractEnergy(1000, false) == 123
                && output.extractEnergy(1000, false) == 0
                && buffer.getEnergyStored(EnergySystem.ForgeEnergy).doubleValue() == 0.5d;
        passed &= extract;
        feedback(source, "fractionalEnergyExtraction=%s".formatted(extract ? "PASS" : "FAIL"));

        buffer.setEnergyStored(WideAmount.from(9000.5d), EnergySystem.ForgeEnergy);
        final boolean insert = input.receiveEnergy(1000, true) == 999
                && buffer.getEnergyStored(EnergySystem.ForgeEnergy).doubleValue() == 9000.5d
                && input.receiveEnergy(1000, false) == 999
                && input.receiveEnergy(1000, false) == 0
                && buffer.getEnergyStored(EnergySystem.ForgeEnergy).doubleValue() == 9999.5d;
        passed &= insert;
        feedback(source, "fractionalEnergyInsertion=%s".formatted(insert ? "PASS" : "FAIL"));

        buffer.setEnergyStored(WideAmount.from(5000), EnergySystem.ForgeEnergy);
        buffer.setMaxTransfer(WideAmount.from(1.5d));
        final boolean transferLimit = output.extractEnergy(1000, false) == 1
                && buffer.getEnergyStored(EnergySystem.ForgeEnergy).longValue() == 4999
                && input.receiveEnergy(1000, false) == 1
                && buffer.getEnergyStored(EnergySystem.ForgeEnergy).longValue() == 5000;
        passed &= transferLimit;
        feedback(source, "fractionalEnergyTransferLimit=%s".formatted(transferLimit ? "PASS" : "FAIL"));
        buffer.setMaxTransfer(WideAmount.from(0.5d));
        final boolean subunitLimit = output.extractEnergy(1000, false) == 0
                && input.receiveEnergy(1000, false) == 0
                && buffer.getEnergyStored(EnergySystem.ForgeEnergy).longValue() == 5000;
        passed &= subunitLimit;
        feedback(source, "subunitEnergyTransferLimit=%s".formatted(subunitLimit ? "PASS" : "FAIL"));
        output.release();
        input.release();
        return passed;
    }

    private static ICompactController testGenerator(Level level, BlockPos pos, int size, boolean turbine) {
        final ICompactController controller = turbine
                ? new CompactTurbineController(level, pos, 1, size, size, size)
                : new CompactReactorController(level, pos, 16, 4, 4, size, size, size);
        controller.simulateAssembly();
        return controller;
    }

    private static boolean selfTestGeneratorCapacity(CommandSourceStack source, Level level, BlockPos pos, boolean turbine) {
        final ICompactController original = testGenerator(level, pos, 3, turbine);
        final ICompactController expanded = testGenerator(level, pos, 9, turbine);
        final ICompactController reduced = testGenerator(level, pos, 3, turbine);
        final String kind = turbine ? "turbine" : "reactor";
        boolean passed = true;
        try {
            final long smallCapacity = original.getCapacity(EnergySystem.ForgeEnergy).longValue();
            final long largeCapacity = expanded.getCapacity(EnergySystem.ForgeEnergy).longValue();
            original.insertEnergy(EnergySystem.ForgeEnergy, WideAmount.from(smallCapacity - 1), OperationMode.Execute);
            final CompoundTag originalTag = original.syncDataTo(new CompoundTag(), level.registryAccess(),
                    ISyncableEntity.SyncReason.FullSync);
            final CompoundTag originalSnapshot = originalTag.copy();
            expanded.syncDataFrom(originalTag, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
            final boolean expansion = largeCapacity > smallCapacity
                    && expanded.getCapacity(EnergySystem.ForgeEnergy).longValue() == largeCapacity
                    && expanded.getEnergyStored(EnergySystem.ForgeEnergy).longValue() == smallCapacity - 1
                    && originalTag.equals(originalSnapshot);
            passed &= expansion;
            feedback(source, "%sCapacityExpansion=%s".formatted(kind, expansion ? "PASS" : "FAIL"));

            final long savedEnergy = largeCapacity - 1;
            expanded.insertEnergy(EnergySystem.ForgeEnergy,
                    WideAmount.from(savedEnergy - smallCapacity + 1), OperationMode.Execute);
            final CompoundTag expandedTag = expanded.syncDataTo(new CompoundTag(), level.registryAccess(),
                    ISyncableEntity.SyncReason.FullSync);
            final CompoundTag expandedSnapshot = expandedTag.copy();
            reduced.syncDataFrom(expandedTag, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
            final boolean shrink = reduced.getCapacity(EnergySystem.ForgeEnergy).longValue() == smallCapacity
                    && reduced.getEnergyStored(EnergySystem.ForgeEnergy).longValue() == savedEnergy
                    && reduced.isEnergyBufferFull() && expandedTag.equals(expandedSnapshot);
            passed &= shrink;
            feedback(source, "%sCapacityShrink=%s".formatted(kind, shrink ? "PASS" : "FAIL"));

            reduced.setMachineActive(true);
            reduced.tick();
            final CompactEnergyStorage output = new CompactEnergyStorage(reduced);
            final boolean simulated = output.extractEnergy(1000, true) == 1000
                    && reduced.getEnergyStored(EnergySystem.ForgeEnergy).longValue() == savedEnergy;
            final boolean executed = output.extractEnergy(1000, false) == 1000
                    && reduced.getEnergyStored(EnergySystem.ForgeEnergy).longValue() == savedEnergy - 1000;
            passed &= simulated && executed;
            feedback(source, "%sOverfullEnergyExtraction=%s".formatted(kind, simulated && executed ? "PASS" : "FAIL"));

            final CompoundTag persisted = reduced.syncDataTo(new CompoundTag(), level.registryAccess(),
                    ISyncableEntity.SyncReason.FullSync);
            original.syncDataFrom(persisted, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
            final boolean roundTrip = original.getCapacity(EnergySystem.ForgeEnergy).longValue() == smallCapacity
                    && original.getEnergyStored(EnergySystem.ForgeEnergy).longValue() == savedEnergy - 1000;
            passed &= roundTrip;
            feedback(source, "%sOverfullEnergyRoundTrip=%s".formatted(kind, roundTrip ? "PASS" : "FAIL"));

            final CompoundTag legacy = expandedTag.copy();
            final CompoundTag legacyBuffer = new CompoundTag();
            legacyBuffer.putDouble("capacity", largeCapacity);
            legacyBuffer.putDouble("energy", savedEnergy + 0.5d);
            legacyBuffer.putDouble("maxInsert", 0.0d);
            legacyBuffer.putDouble("maxExtract", 2000.0d);
            legacy.put("buffer", legacyBuffer);
            final CompoundTag legacySnapshot = legacy.copy();
            reduced.syncDataFrom(legacy, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
            final boolean legacyRestore = reduced.getCapacity(EnergySystem.ForgeEnergy).longValue() == smallCapacity
                    && reduced.getEnergyStored(EnergySystem.ForgeEnergy).doubleValue() == savedEnergy + 0.5d
                    && reduced.canInsert() && legacy.equals(legacySnapshot);
            passed &= legacyRestore;
            feedback(source, "%sLegacyEnergyCapacity=%s".formatted(kind, legacyRestore ? "PASS" : "FAIL"));
        } finally {
            original.releaseFluidHandlers();
            expanded.releaseFluidHandlers();
            reduced.releaseFluidHandlers();
        }
        return passed;
    }

    private static boolean selfTestTurbineCondensation(CommandSourceStack source, CompactTurbineController turbine) {
        try {
            final FluidContainer container = (FluidContainer) turbine.getFluidContainer();
            final Fluid steam = BuiltInRegistries.FLUID.get(
                    ResourceLocation.fromNamespaceAndPath("bigreactors", "steam"));
            final IFluidHandler input = turbine.getFluidHandler(it.zerono.mods.zerocore.lib.data.IoDirection.Input).orElseThrow();
            turbine.setMaxIntakeRate(100);
            turbine.setMachineActive(true);
            turbine.setVentSetting(it.zerono.mods.extremereactors.gamecontent.multiblock.turbine.VentSetting.VentOverflow);
            boolean passed = true;
            for (int amount : new int[]{1, 37, 100, 101, 1000}) {
                container.voidGas();
                container.voidLiquid();
                final int accepted = input.fill(new FluidStack(steam, amount), IFluidHandler.FluidAction.EXECUTE);
                final var optionalMapping = container.getVapor().flatMap(TransitionsRegistry::get).orElse(null);
                final var directMapping = container.mapVapor(vapor -> TransitionsRegistry.get(vapor).orElse(null), null);
                for (int processedTick = 0; processedTick < 20 && container.getGasAmount() > 0; processedTick++) {
                    turbine.tick();
                }
                final boolean conserved = accepted == amount && optionalMapping == directMapping
                        && container.getGasAmount() == 0 && container.getLiquidAmount() == amount;
                passed &= conserved;
                feedback(source, "turbineCondensation=%s (steam=%d water=%d)"
                        .formatted(conserved ? "PASS" : "FAIL", amount, container.getLiquidAmount()));
            }
            container.voidLiquid();
            final double speedBefore = turbine.getRotorAngularSpeed();
            turbine.tick();
            final boolean coasting = speedBefore > 0.0d && turbine.getRotorAngularSpeed() < speedBefore
                    && turbine.getEnergyGeneratedLastTick() > 0.0d;
            passed &= coasting;
            feedback(source, "turbineEmptyTankCoasting=%s".formatted(coasting ? "PASS" : "FAIL"));

            turbine.setVentSetting(it.zerono.mods.extremereactors.gamecontent.multiblock.turbine.VentSetting.VentAll);
            input.fill(new FluidStack(steam, 37), IFluidHandler.FluidAction.EXECUTE);
            turbine.tick();
            final boolean ventAll = container.getGasAmount() == 0 && container.getLiquidAmount() == 0;
            passed &= ventAll;
            feedback(source, "turbineVentAll=%s".formatted(ventAll ? "PASS" : "FAIL"));

            turbine.setVentSetting(it.zerono.mods.extremereactors.gamecontent.multiblock.turbine.VentSetting.DoNotVent);
            final int capacity = container.getCapacity();
            container.insertLiquid(net.minecraft.world.level.material.Fluids.WATER, capacity, OperationMode.Execute);
            input.fill(new FluidStack(steam, 37), IFluidHandler.FluidAction.EXECUTE);
            turbine.tick();
            final boolean fullOutput = container.getGasAmount() == 37 && container.getLiquidAmount() == capacity;
            passed &= fullOutput;
            feedback(source, "turbineFullOutputNoVent=%s".formatted(fullOutput ? "PASS" : "FAIL"));
            container.voidLiquid();
            turbine.tick();
            final boolean resumed = container.getGasAmount() == 0 && container.getLiquidAmount() == 37;
            passed &= resumed;
            feedback(source, "turbineOutputSpaceResume=%s".formatted(resumed ? "PASS" : "FAIL"));
            return passed;
        } finally {
            turbine.releaseFluidHandlers();
        }
    }

    private static boolean selfTestFluidRouting(CommandSourceStack source, AbstractCompactMachineTileEntity tile) {
        final var controller = tile.getController();
        final IFluidHandler combined = tile.getFluidHandler(null);
        if (controller == null || combined == null) {
            feedback(source, "fluidTankRouting=FAIL (capability unavailable)");
            return false;
        }
        final IFluidHandler[] handlers = {
                controller.getFluidHandler(it.zerono.mods.zerocore.lib.data.IoDirection.Input).orElseThrow(),
                controller.getFluidHandler(it.zerono.mods.zerocore.lib.data.IoDirection.Output).orElseThrow()};
        final FluidStack[] candidates = {
                new FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1),
                new FluidStack(BuiltInRegistries.FLUID.get(
                        ResourceLocation.fromNamespaceAndPath("bigreactors", "steam")), 1)};
        boolean passed = true;
        int tank = 0;
        for (IFluidHandler handler : handlers) {
            final int count = handler.getTanks();
            for (int localTank = 0; localTank < count; localTank++, tank++) {
                final FluidStack expected = handler.getFluidInTank(localTank);
                final FluidStack actual = combined.getFluidInTank(tank);
                passed &= expected.isEmpty() ? actual.isEmpty()
                        : expected.getAmount() == actual.getAmount()
                                && FluidStack.isSameFluidSameComponents(expected, actual);
                passed &= combined.getTankCapacity(tank) == handler.getTankCapacity(localTank);
                for (FluidStack candidate : candidates) {
                    passed &= combined.isFluidValid(tank, candidate) == handler.isFluidValid(localTank, candidate);
                }
            }
        }
        final int totalTanks = combined.getTanks();
        passed &= totalTanks == tank + (tile instanceof CompactReactorTileEntity ? 2 : 0);
        for (int invalidTank : new int[]{-1, totalTanks, Integer.MAX_VALUE}) {
            passed &= combined.getFluidInTank(invalidTank).isEmpty()
                    && combined.getTankCapacity(invalidTank) == 0
                    && !combined.isFluidValid(invalidTank, candidates[0]);
        }
        feedback(source, "fluidTankRouting=%s (logicalTanks=%d)".formatted(passed ? "PASS" : "FAIL", totalTanks));
        if (tile instanceof CompactReactorTileEntity) {
            final var pendingReactor = new CompactReactorTileEntity(tile.getBlockPos(), tile.getBlockState()) {
                public int pendingTankCount() {
                    return this.getPendingFluidTankCount();
                }

                public boolean pendingFluidValid(int tankIndex, FluidStack stack) {
                    return this.isPendingFluidValid(tankIndex, stack);
                }
            };
            final var delegate = new java.util.concurrent.atomic.AtomicReference<IFluidHandler>();
            final var pending = new com.compact.extremereactor.common.capability.DeferredFluidHandler(
                    delegate::get, pendingReactor.pendingTankCount(), pendingReactor::pendingFluidValid);
            final int before = pending.getTanks();
            boolean pendingPassed = before == totalTanks && pending.getFluidInTank(3).isEmpty()
                    && pending.getTankCapacity(3) == 0 && !pending.isFluidValid(3, candidates[0])
                    && !pendingReactor.isControllerReady();
            delegate.set(combined);
            pendingPassed &= pending.getTanks() == before
                    && pending.getTankCapacity(3) == combined.getTankCapacity(3);
            pending.release();
            pendingPassed &= pending.getTanks() == 0 && pending.getTankCapacity(3) == 0
                    && pending.getFluidInTank(3).isEmpty() && !pending.isFluidValid(3, candidates[0]);
            feedback(source, "reactorPendingFluidTanks=%s (before=%d ready=%d)"
                    .formatted(pendingPassed ? "PASS" : "FAIL", before, totalTanks));
            passed &= pendingPassed;
        }
        return passed;
    }

    private static boolean selfTestMenuData(CommandSourceStack source, AbstractCompactMachineTileEntity tile) {
        final net.minecraft.world.inventory.AbstractContainerMenu serverMenu;
        final net.minecraft.world.inventory.AbstractContainerMenu clientMenu;
        final java.util.function.IntUnaryOperator serverValue;
        final java.util.function.IntUnaryOperator clientValue;
        final int count;
        if (tile instanceof CompactReactorTileEntity reactor) {
            final var server = new com.compact.extremereactor.common.menu.CompactReactorMenu(1, null, reactor);
            final var client = new com.compact.extremereactor.common.menu.CompactReactorMenu(1, null);
            serverMenu = server;
            clientMenu = client;
            serverValue = server::getData;
            clientValue = client::getData;
            count = com.compact.extremereactor.common.menu.CompactReactorMenu.DATA_COUNT;
        } else if (tile instanceof CompactTurbineTileEntity turbine) {
            final var server = new com.compact.extremereactor.common.menu.CompactTurbineMenu(1, null, turbine);
            final var client = new com.compact.extremereactor.common.menu.CompactTurbineMenu(1, null);
            serverMenu = server;
            clientMenu = client;
            serverValue = server::getData;
            clientValue = client::getData;
            count = com.compact.extremereactor.common.menu.CompactTurbineMenu.DATA_COUNT;
        } else if (tile instanceof CompactFluidizerTileEntity fluidizer) {
            final var server = new com.compact.extremereactor.common.menu.CompactFluidizerMenu(1, null, fluidizer);
            final var client = new com.compact.extremereactor.common.menu.CompactFluidizerMenu(1, null);
            serverMenu = server;
            clientMenu = client;
            serverValue = server::getData;
            clientValue = client::getData;
            count = com.compact.extremereactor.common.menu.CompactFluidizerMenu.DATA_COUNT;
        } else {
            return false;
        }
        serverMenu.setSynchronizer(new net.minecraft.world.inventory.ContainerSynchronizer() {
            @Override
            public void sendInitialData(net.minecraft.world.inventory.AbstractContainerMenu menu,
                                        net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> stacks,
                                        net.minecraft.world.item.ItemStack carried, int[] values) {
                for (int index = 0; index < values.length; index++) {
                    clientMenu.setData(index, (short) values[index]);
                }
            }

            @Override
            public void sendSlotChange(net.minecraft.world.inventory.AbstractContainerMenu menu, int index,
                                       net.minecraft.world.item.ItemStack stack) {
            }

            @Override
            public void sendCarriedChange(net.minecraft.world.inventory.AbstractContainerMenu menu,
                                          net.minecraft.world.item.ItemStack stack) {
            }

            @Override
            public void sendDataChange(net.minecraft.world.inventory.AbstractContainerMenu menu, int index, int value) {
                clientMenu.setData(index, (short) value);
            }
        });
        boolean passed = true;
        for (Runnable broadcast : new Runnable[]{serverMenu::broadcastChanges, serverMenu::broadcastFullState,
                serverMenu::sendAllDataToRemote}) {
            broadcast.run();
            for (int index = 0; index < count; index++) {
                passed &= serverValue.applyAsInt(index) == clientValue.applyAsInt(index);
            }
        }
        feedback(source, "menuDataSync=%s (logicalSlots=%d packedSlots=%d)"
                .formatted(passed ? "PASS" : "FAIL", count, count * 2));
        return passed;
    }

    private static boolean selfTestFluidizerEnergy(CommandSourceStack source, Level level, BlockPos pos) {
        final CompactFluidizerController fluidizer = new CompactFluidizerController(level, pos, 3, 3, 3);
        final int originalCost = Config.COMMON.fluidizer.energyPerRecipeTick.get();
        boolean passed = true;
        try {
            fluidizer.simulateAssembly();
            fluidizer.getItemInputs().setStackInSlot(0, new net.minecraft.world.item.ItemStack(
                    BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("bigreactors", "yellorium_ingot")), 64));
            fluidizer.insertEnergy(EnergySystem.ForgeEnergy, WideAmount.asImmutable(1000), OperationMode.Execute);
            fluidizer.setMachineActive(true);
            for (int cost : new int[]{25, 50, 25}) {
                Config.COMMON.fluidizer.energyPerRecipeTick.set(cost);
                Config.COMMON.fluidizer.energyPerRecipeTick.clearCache();
                final long before = fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).longValue();
                fluidizer.tick();
                final long spent = before - fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).longValue();
                passed &= spent == cost;
                feedback(source, "fluidizerEnergyCost=%s (cost=%d spent=%d)"
                        .formatted(spent == cost ? "PASS" : "FAIL", cost, spent));
            }
            final long blockedEnergy = fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).longValue();
            Config.COMMON.fluidizer.energyPerRecipeTick.set(2000);
            Config.COMMON.fluidizer.energyPerRecipeTick.clearCache();
            fluidizer.tick();
            final boolean insufficientEnergy = fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).longValue() == blockedEnergy
                    && fluidizer.getCurrentTick() == 0
                    && fluidizer.getInputItemAt(0).getCount() == 64
                    && fluidizer.getOutputTank().getFluidInTank(0).isEmpty();
            passed &= insufficientEnergy;
            feedback(source, "fluidizerInsufficientEnergy=%s".formatted(insufficientEnergy ? "PASS" : "FAIL"));

            Config.COMMON.fluidizer.energyPerRecipeTick.set(25);
            Config.COMMON.fluidizer.energyPerRecipeTick.clearCache();
            final Fluid yellorium = BuiltInRegistries.FLUID.get(
                    ResourceLocation.fromNamespaceAndPath("bigreactors", "yellorium"));
            final Fluid blutonium = BuiltInRegistries.FLUID.get(
                    ResourceLocation.fromNamespaceAndPath("bigreactors", "blutonium"));
            fluidizer.getOutputTank().setContent(new FluidStack(yellorium, fluidizer.getOutputTank().getTankCapacity(0)));
            fluidizer.tick();
            final boolean fullOutput = fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).longValue() == blockedEnergy
                    && fluidizer.getCurrentTick() == 0
                    && fluidizer.getInputItemAt(0).getCount() == 64;
            passed &= fullOutput;
            feedback(source, "fluidizerFullOutput=%s".formatted(fullOutput ? "PASS" : "FAIL"));

            fluidizer.getOutputTank().setContent(FluidStack.EMPTY);
            fluidizer.insertEnergy(EnergySystem.ForgeEnergy, WideAmount.asImmutable(1000), OperationMode.Execute);
            final long solidEnergy = fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).longValue();
            for (int processedTick = 0; processedTick < 40; processedTick++) {
                fluidizer.tick();
            }
            final boolean solidComplete = solidEnergy - fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).longValue() == 1000
                    && fluidizer.getOutputTank().getFluidInTank(0).getAmount() == 1000
                    && fluidizer.getInputItemAt(0).getCount() == 63;
            passed &= solidComplete;
            feedback(source, "fluidizerSolidCompletion=%s".formatted(solidComplete ? "PASS" : "FAIL"));

            fluidizer.clearInputs();
            fluidizer.getOutputTank().setContent(FluidStack.EMPTY);
            for (int refill = 0; refill < 4; refill++) {
                fluidizer.insertEnergy(EnergySystem.ForgeEnergy, WideAmount.asImmutable(1000), OperationMode.Execute);
            }
            final IFluidHandler input = fluidizer.getFluidHandler(it.zerono.mods.zerocore.lib.data.IoDirection.Input).orElseThrow();
            final boolean invalidFluidBoundaries = input.getTanks() == 2
                    && input.getFluidInTank(-1).isEmpty()
                    && input.getTankCapacity(-1) == 0
                    && !input.isFluidValid(-1, new FluidStack(yellorium, 1))
                    && input.fill(FluidStack.EMPTY, IFluidHandler.FluidAction.SIMULATE) == 0
                    && input.drain(-1, IFluidHandler.FluidAction.SIMULATE).isEmpty()
                    && input.drain(FluidStack.EMPTY, IFluidHandler.FluidAction.SIMULATE).isEmpty()
                    && input.drain(new FluidStack(yellorium, 1), IFluidHandler.FluidAction.SIMULATE).isEmpty();
            passed &= invalidFluidBoundaries;
            feedback(source, "fluidizerInvalidFluidBoundaries=%s"
                    .formatted(invalidFluidBoundaries ? "PASS" : "FAIL"));
            final int simulatedYellorium = input.fill(
                    new FluidStack(yellorium, 2000), IFluidHandler.FluidAction.SIMULATE);
            final boolean fluidSimulationStable = simulatedYellorium == 2000
                    && fluidizer.getInputFluidAt(0).isEmpty()
                    && fluidizer.getInputFluidAt(1).isEmpty();
            passed &= fluidSimulationStable;
            feedback(source, "fluidizerFluidSimulation=%s"
                    .formatted(fluidSimulationStable ? "PASS" : "FAIL"));
            final int acceptedYellorium = input.fill(new FluidStack(yellorium, 2000), IFluidHandler.FluidAction.EXECUTE);
            final int acceptedBlutonium = input.fill(new FluidStack(blutonium, 1000), IFluidHandler.FluidAction.EXECUTE);
            final long mixingEnergy = fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).longValue();
            for (int processedTick = 0; processedTick < 80; processedTick++) {
                fluidizer.tick();
            }
            final boolean mixingComplete = acceptedYellorium == 2000 && acceptedBlutonium == 1000
                    && mixingEnergy - fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).longValue() == 4000
                    && fluidizer.getOutputTank().getFluidInTank(0).getAmount() == 2000
                    && fluidizer.getInputFluidAt(0).isEmpty() && fluidizer.getInputFluidAt(1).isEmpty();
            passed &= mixingComplete;
            feedback(source, "fluidizerMixingCompletion=%s".formatted(mixingComplete ? "PASS" : "FAIL"));

            fluidizer.clearInputs();
            fluidizer.getOutputTank().setContent(FluidStack.EMPTY);
            fluidizer.getItemInputs().setStackInSlot(1, new net.minecraft.world.item.ItemStack(
                    BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("bigreactors", "yellorium_ingot")), 1));
            fluidizer.insertEnergy(EnergySystem.ForgeEnergy, WideAmount.asImmutable(1000), OperationMode.Execute);
            final long resumedSolidEnergy = fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).longValue();
            for (int processedTick = 0; processedTick < 40; processedTick++) {
                fluidizer.tick();
            }
            final boolean resumedSolid = resumedSolidEnergy - fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).longValue() == 1000
                    && fluidizer.getOutputTank().getFluidInTank(0).getAmount() == 1000
                    && fluidizer.getInputItemAt(1).isEmpty();
            passed &= resumedSolid;
            feedback(source, "fluidizerMixingToSolid=%s".formatted(resumedSolid ? "PASS" : "FAIL"));

            fluidizer.clearInputs();
            fluidizer.getOutputTank().setContent(FluidStack.EMPTY);
            final var duplicateIngredient = BuiltInRegistries.ITEM.get(
                    ResourceLocation.fromNamespaceAndPath("bigreactors", "yellorium_ingot"));
            fluidizer.getItemInputs().setStackInSlot(0, new net.minecraft.world.item.ItemStack(duplicateIngredient, 1));
            fluidizer.getItemInputs().setStackInSlot(1, new net.minecraft.world.item.ItemStack(duplicateIngredient, 1));
            for (int refill = 0; refill < 2; refill++) {
                fluidizer.insertEnergy(EnergySystem.ForgeEnergy, WideAmount.asImmutable(1000), OperationMode.Execute);
            }
            final long duplicateEnergy = fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).longValue();
            for (int processedTick = 0; processedTick < 80; processedTick++) {
                fluidizer.tick();
            }
            final boolean duplicateInputs = duplicateEnergy - fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).longValue() == 2000
                    && fluidizer.getOutputTank().getFluidInTank(0).getAmount() == 2000
                    && fluidizer.getOutputTank().getFluidInTank(0).getFluid() == yellorium
                    && fluidizer.getInputItemAt(0).isEmpty() && fluidizer.getInputItemAt(1).isEmpty();
            passed &= duplicateInputs;
            feedback(source, "fluidizerDuplicateSolidInputs=%s".formatted(duplicateInputs ? "PASS" : "FAIL"));
            passed &= selfTestFluidizerCapacity(source, level, pos, yellorium);
            passed &= selfTestFluidizerEnergyPersistence(source, level, pos);
            passed &= selfTestFluidizerInventoryPersistence(source, level, pos, yellorium, blutonium);
        } finally {
            Config.COMMON.fluidizer.energyPerRecipeTick.set(originalCost);
            Config.COMMON.fluidizer.energyPerRecipeTick.clearCache();
            fluidizer.releaseFluidHandlers();
        }
        return passed;
    }

    private static boolean selfTestFluidizerInventoryPersistence(CommandSourceStack source, Level level, BlockPos pos,
                                                               Fluid firstFluid, Fluid secondFluid) {
        final CompactFluidizerController fluidizer = new CompactFluidizerController(level, pos, 3, 3, 3);
        boolean passed = true;
        try {
            fluidizer.simulateAssembly();
            final var ingredient = BuiltInRegistries.ITEM.get(
                    ResourceLocation.fromNamespaceAndPath("bigreactors", "yellorium_ingot"));
            fluidizer.getItemInputs().setStackInSlot(0, new net.minecraft.world.item.ItemStack(ingredient, 3));
            fluidizer.getItemInputs().setStackInSlot(1, new net.minecraft.world.item.ItemStack(ingredient, 5));
            final IFluidHandler input = fluidizer.getFluidHandler(
                    it.zerono.mods.zerocore.lib.data.IoDirection.Input).orElseThrow();
            if (input.fill(new FluidStack(firstFluid, 250), IFluidHandler.FluidAction.EXECUTE) != 250
                    || input.fill(new FluidStack(secondFluid, 125), IFluidHandler.FluidAction.EXECUTE) != 125) {
                feedback(source, "fluidizerInventoryFixture=FAIL");
                return false;
            }
            final CompoundTag saved = fluidizer.syncDataTo(new CompoundTag(), level.registryAccess(),
                    ISyncableEntity.SyncReason.FullSync);
            for (int slots : new int[]{1, 2, 3, 32767, Integer.MAX_VALUE, 0, -1, Integer.MIN_VALUE}) {
                final CompoundTag malformed = saved.copy();
                for (String key : new String[]{"inv", "fin0", "fin1"}) {
                    malformed.getCompound(key).putInt("Size", slots);
                }
                final CompoundTag snapshot = malformed.copy();
                fluidizer.syncDataFrom(malformed, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
                final CompoundTag restored = fluidizer.syncDataTo(new CompoundTag(), level.registryAccess(),
                        ISyncableEntity.SyncReason.FullSync);
                final boolean fixedSlots = fluidizer.getItemInputs().getSlots() == 2
                        && restored.getCompound("fin0").getInt("Size") == 1
                        && restored.getCompound("fin1").getInt("Size") == 1
                        && fluidizer.getInputItemAt(0).getCount() == 3
                        && fluidizer.getInputItemAt(1).getCount() == 5
                        && fluidizer.getInputFluidAt(0).getAmount() == 250
                        && fluidizer.getInputFluidAt(1).getAmount() == 125
                        && input.getTankCapacity(0) == 8000 && input.getTankCapacity(1) == 8000
                        && malformed.equals(snapshot);
                passed &= fixedSlots;
                feedback(source, "fluidizerInventorySize=%s (saved=%d)"
                        .formatted(fixedSlots ? "PASS" : "FAIL", slots));
            }
            final CompoundTag invalidSlots = new CompoundTag();
            invalidSlots.putString("text", "invalid");
            invalidSlots.put("compound", new CompoundTag());
            invalidSlots.putLong("wrapped", 4294967296L);
            invalidSlots.putDouble("fraction", 0.5d);
            invalidSlots.putDouble("nan", Double.NaN);
            for (String slotType : new String[]{"missing", "text", "compound", "wrapped", "fraction", "nan"}) {
                final CompoundTag malformed = saved.copy();
                for (String key : new String[]{"inv", "fin0", "fin1"}) {
                    final var entries = malformed.getCompound(key).getList("Items", Tag.TAG_COMPOUND);
                    final CompoundTag invalidEntry = entries.getCompound(0).copy();
                    invalidEntry.remove("Slot");
                    if (invalidSlots.contains(slotType)) {
                        invalidEntry.put("Slot", invalidSlots.get(slotType).copy());
                    }
                    invalidEntry.getCompound("Stack").putInt(key.equals("inv") ? "count" : "amount", 13);
                    entries.add(invalidEntry);
                }
                final CompoundTag snapshot = malformed.copy();
                fluidizer.syncDataFrom(malformed, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
                final boolean validSiblings = fluidizer.getInputItemAt(0).getCount() == 3
                        && fluidizer.getInputItemAt(1).getCount() == 5
                        && fluidizer.getInputFluidAt(0).getAmount() == 250
                        && fluidizer.getInputFluidAt(1).getAmount() == 125
                        && malformed.equals(snapshot);
                passed &= validSiblings;
                feedback(source, "fluidizerSlotMetadata=%s (type=%s)"
                        .formatted(validSiblings ? "PASS" : "FAIL", slotType));
            }
            final CompoundTag partial = new CompoundTag();
            partial.put("inv", new CompoundTag());
            partial.put("fin0", new CompoundTag());
            partial.putString("fin1", "invalid");
            final CompoundTag partialSnapshot = partial.copy();
            fluidizer.syncDataFrom(partial, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
            final boolean partialPreserved = fluidizer.getInputItemAt(0).getCount() == 3
                    && fluidizer.getInputItemAt(1).getCount() == 5
                    && fluidizer.getInputFluidAt(0).getAmount() == 250
                    && fluidizer.getInputFluidAt(1).getAmount() == 125
                    && partial.equals(partialSnapshot);
            passed &= partialPreserved;
            feedback(source, "fluidizerInventoryPartialRestore=%s".formatted(partialPreserved ? "PASS" : "FAIL"));

            final CompoundTag empty = saved.copy();
            for (String key : new String[]{"inv", "fin0", "fin1"}) {
                empty.getCompound(key).putInt("Size", Integer.MAX_VALUE);
                empty.getCompound(key).put("Items", new net.minecraft.nbt.ListTag());
            }
            fluidizer.syncDataFrom(empty, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
            final boolean cleared = fluidizer.getInputItemAt(0).isEmpty() && fluidizer.getInputItemAt(1).isEmpty()
                    && fluidizer.getInputFluidAt(0).isEmpty() && fluidizer.getInputFluidAt(1).isEmpty();
            passed &= cleared;
            feedback(source, "fluidizerInventoryEmptyRestore=%s".formatted(cleared ? "PASS" : "FAIL"));
        } finally {
            fluidizer.releaseFluidHandlers();
        }
        return passed;
    }

    private static boolean selfTestFluidizerEnergyPersistence(CommandSourceStack source, Level level, BlockPos pos) {
        final CompactFluidizerController fluidizer = new CompactFluidizerController(level, pos, 3, 3, 3);
        boolean passed = true;
        try {
            final CompoundTag saved = fluidizer.syncDataTo(new CompoundTag(), level.registryAccess(),
                    ISyncableEntity.SyncReason.FullSync);
            final CompoundTag savedBuffer = saved.getCompound("energy");
            savedBuffer.put("capacity", WideAmount.from(1).serializeToNBT());
            savedBuffer.put("energy", WideAmount.from(12000.5d).serializeToNBT());
            savedBuffer.put("maxInsert", WideAmount.MAX_VALUE.serializeToNBT());
            savedBuffer.put("maxExtract", WideAmount.MAX_VALUE.serializeToNBT());
            final CompoundTag savedSnapshot = saved.copy();
            fluidizer.syncDataFrom(saved, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
            final boolean capacity = fluidizer.getCapacity(EnergySystem.ForgeEnergy).longValue() == 50000
                    && fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).doubleValue() == 12000.5d
                    && saved.equals(savedSnapshot);
            passed &= capacity;
            feedback(source, "fluidizerEnergyCapacityRestore=%s".formatted(capacity ? "PASS" : "FAIL"));

            final boolean transfer = fluidizer.insertEnergy(EnergySystem.ForgeEnergy,
                    WideAmount.from(5000), OperationMode.Simulate).longValue() == 1000
                    && fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).doubleValue() == 12000.5d
                    && fluidizer.insertEnergy(EnergySystem.ForgeEnergy,
                    WideAmount.from(5000), OperationMode.Execute).longValue() == 1000
                    && fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).doubleValue() == 13000.5d;
            passed &= transfer;
            feedback(source, "fluidizerEnergyTransferRestore=%s".formatted(transfer ? "PASS" : "FAIL"));

            final CompoundTag legacy = saved.copy();
            final CompoundTag legacyBuffer = new CompoundTag();
            legacyBuffer.putDouble("capacity", 1.0d);
            legacyBuffer.putDouble("energy", 70000.5d);
            legacyBuffer.putDouble("maxInsert", 9999.0d);
            legacyBuffer.putDouble("maxExtract", 9999.0d);
            legacy.put("energy", legacyBuffer);
            final CompoundTag legacySnapshot = legacy.copy();
            fluidizer.syncDataFrom(legacy, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
            final boolean overfull = fluidizer.getCapacity(EnergySystem.ForgeEnergy).longValue() == 50000
                    && fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).doubleValue() == 70000.5d
                    && fluidizer.insertEnergy(EnergySystem.ForgeEnergy,
                    WideAmount.from(1000), OperationMode.Simulate).isZero()
                    && fluidizer.insertEnergy(EnergySystem.ForgeEnergy,
                    WideAmount.from(1000), OperationMode.Execute).isZero()
                    && fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).doubleValue() == 70000.5d
                    && legacy.equals(legacySnapshot);
            passed &= overfull;
            feedback(source, "fluidizerLegacyOverfullEnergy=%s".formatted(overfull ? "PASS" : "FAIL"));

            final CompoundTag wrongType = new CompoundTag();
            wrongType.putString("energy", "invalid");
            fluidizer.syncDataFrom(wrongType, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
            final boolean preserved = fluidizer.getCapacity(EnergySystem.ForgeEnergy).longValue() == 50000
                    && fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).doubleValue() == 70000.5d
                    && wrongType.getString("energy").equals("invalid");
            passed &= preserved;
            feedback(source, "fluidizerWrongEnergyType=%s".formatted(preserved ? "PASS" : "FAIL"));

            final CompoundTag malformed = saved.copy();
            malformed.getCompound("energy").putString("maxInsert", "invalid");
            malformed.getCompound("energy").putString("maxExtract", "invalid");
            final CompoundTag malformedSnapshot = malformed.copy();
            fluidizer.syncDataFrom(malformed, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
            final boolean validEnergy = fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).doubleValue() == 12000.5d
                    && fluidizer.insertEnergy(EnergySystem.ForgeEnergy,
                    WideAmount.from(5000), OperationMode.Simulate).longValue() == 1000
                    && malformed.equals(malformedSnapshot);
            passed &= validEnergy;
            feedback(source, "fluidizerEnergyMalformedSibling=%s".formatted(validEnergy ? "PASS" : "FAIL"));

            malformed.getCompound("energy").put("energy", new CompoundTag());
            final CompoundTag invalidSnapshot = malformed.copy();
            fluidizer.syncDataFrom(malformed, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
            final boolean invalidEnergy = fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).isZero()
                    && fluidizer.getCapacity(EnergySystem.ForgeEnergy).longValue() == 50000
                    && fluidizer.insertEnergy(EnergySystem.ForgeEnergy,
                    WideAmount.from(5000), OperationMode.Simulate).longValue() == 1000
                    && malformed.equals(invalidSnapshot);
            passed &= invalidEnergy;
            feedback(source, "fluidizerMalformedEnergyRestore=%s".formatted(invalidEnergy ? "PASS" : "FAIL"));
        } finally {
            fluidizer.releaseFluidHandlers();
        }
        return passed;
    }

    private static boolean selfTestFluidizerCapacity(CommandSourceStack source, Level level, BlockPos pos, Fluid fluid) {
        final CompactFluidizerController original = new CompactFluidizerController(level, pos, 3, 3, 3);
        final CompactFluidizerController expanded = new CompactFluidizerController(level, pos, 5, 5, 5);
        final CompactFluidizerController reduced = new CompactFluidizerController(level, pos, 3, 3, 3);
        boolean passed = true;
        try {
            original.getOutputTank().setContent(new FluidStack(fluid, 3000));
            final CompoundTag originalTag = original.syncDataTo(new CompoundTag(), level.registryAccess(),
                    ISyncableEntity.SyncReason.FullSync);
            final CompoundTag originalSnapshot = originalTag.copy();
            expanded.syncDataFrom(originalTag, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
            final boolean expansion = expanded.getOutputTank().getCapacity() == 108000
                    && expanded.getOutputTank().getFluidAmount() == 3000
                    && originalTag.equals(originalSnapshot);
            passed &= expansion;
            feedback(source, "fluidizerCapacityExpansion=%s".formatted(expansion ? "PASS" : "FAIL"));

            final CompoundTag partialOutput = new CompoundTag();
            partialOutput.putInt("capacity", 1);
            final CompoundTag partialTag = new CompoundTag();
            partialTag.put("out", partialOutput);
            expanded.syncDataFrom(partialTag, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
            final boolean partialRestore = expanded.getOutputTank().getCapacity() == 108000
                    && expanded.getOutputTank().getFluidAmount() == 3000
                    && partialOutput.getInt("capacity") == 1;
            passed &= partialRestore;
            feedback(source, "fluidizerPartialOutputRestore=%s".formatted(partialRestore ? "PASS" : "FAIL"));

            expanded.getOutputTank().setContent(new FluidStack(fluid, 12000));
            final CompoundTag expandedTag = expanded.syncDataTo(new CompoundTag(), level.registryAccess(),
                    ISyncableEntity.SyncReason.FullSync);
            final CompoundTag expandedSnapshot = expandedTag.copy();
            reduced.syncDataFrom(expandedTag, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
            final boolean shrink = reduced.getOutputTank().getCapacity() == 4000
                    && reduced.getOutputTank().getFluidAmount() == 12000
                    && expandedTag.equals(expandedSnapshot);
            passed &= shrink;
            feedback(source, "fluidizerCapacityShrink=%s".formatted(shrink ? "PASS" : "FAIL"));

            final IFluidHandler output = reduced.getFluidHandler(it.zerono.mods.zerocore.lib.data.IoDirection.Output).orElseThrow();
            final boolean simulatedDrain = output.drain(9000, IFluidHandler.FluidAction.SIMULATE).getAmount() == 9000
                    && reduced.getOutputTank().getFluidAmount() == 12000;
            final boolean executedDrain = output.drain(9000, IFluidHandler.FluidAction.EXECUTE).getAmount() == 9000
                    && reduced.getOutputTank().getFluidAmount() == 3000;
            passed &= simulatedDrain && executedDrain;
            feedback(source, "fluidizerOverfullDrain=%s".formatted(simulatedDrain && executedDrain ? "PASS" : "FAIL"));
        } finally {
            original.releaseFluidHandlers();
            expanded.releaseFluidHandlers();
            reduced.releaseFluidHandlers();
        }
        return passed;
    }

    // ------------------------------------------------------------------
    // dump：汽化链路逐环诊断
    // ------------------------------------------------------------------

    private static int devDump(CommandSourceStack source, BlockPos pos) {
        final ServerLevel level = source.getLevel();
        if (!(machineTile(level, pos) instanceof AbstractCompactMachineTileEntity tile)) {
            source.sendFailure(Component.literal("目标不是压缩机器"));
            return 0;
        }
        final var controller = tile.getController();
        if (controller == null) {
            source.sendFailure(Component.literal("控制器未初始化"));
            return 0;
        }
        feedback(source, "=== dump @%s ===".formatted(pos.toShortString()));
        feedback(source, "active=%s energyFull=%s initFailed=%s feStored=%d/%d".formatted(
                controller.isMachineActive(),
                controller.isEnergyBufferFull(),
                tile.isControllerInitFailed(),
                controller.getEnergyStored(EnergySystem.ForgeEnergy).longValue(),
                controller.getCapacity(EnergySystem.ForgeEnergy).longValue()));

        final var energyCapability = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
        feedback(source, "tileRemoved=%s controllerReady=%s energyCapability=%s capabilityEnergy=%d/%d".formatted(
                tile.isRemoved(), tile.isControllerReady(), energyCapability != null,
                energyCapability == null ? 0 : energyCapability.getEnergyStored(),
                energyCapability == null ? 0 : energyCapability.getMaxEnergyStored()));
        try {
            final Field tickingField = AbstractCompactMachineTileEntity.class.getDeclaredField("TICKING_MACHINES");
            tickingField.setAccessible(true);
            feedback(source, "tickRegistered=%s simulatedExtract=%d".formatted(
                    ((java.util.Set<?>) tickingField.get(null)).contains(tile),
                    controller.extractEnergy(EnergySystem.ForgeEnergy, WideAmount.asImmutable(1000), OperationMode.Simulate).longValue()));
        } catch (ReflectiveOperationException exception) {
            source.sendFailure(Component.literal("tick registry inspection failed: " + exception));
        }
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            final BlockPos neighborPos = pos.relative(side);
            if (!level.isLoaded(neighborPos)) {
                continue;
            }
            final var neighborEnergy = level.getCapability(Capabilities.EnergyStorage.BLOCK, neighborPos, side.getOpposite());
            if (neighborEnergy != null) {
                feedback(source, "energyNeighbor=%s stored=%d accepts=%d".formatted(side,
                        neighborEnergy.getEnergyStored(), neighborEnergy.receiveEnergy(1000, true)));
            }
        }

        // 1) 世界侧能力视图（管道看到的）
        final IFluidHandler worldHandler = fluidHandler(level, pos);
        if (worldHandler != null) {
            for (int tank = 0; tank < worldHandler.getTanks(); tank++) {
                final FluidStack stack = worldHandler.getFluidInTank(tank);
                feedback(source, "worldTank[%d/%d]: %s x%d (cap %d)".formatted(
                        tank, worldHandler.getTanks(),
                        stack.isEmpty() ? "empty" : BuiltInRegistries.FLUID.getKey(stack.getFluid()),
                        stack.getAmount(), worldHandler.getTankCapacity(tank)));
            }
        }

        // 2) 涡轮机专属：转子转速 / 发电量 / 能量缓冲
        if (controller instanceof CompactTurbineController turbine) {
            feedback(source, "rotor=%.2f/%.2f rad/s feLastTick=%.1f feStored=%d/%d".formatted(
                    turbine.getRotorAngularSpeed(), turbine.getMaxRotorAngularSpeed(),
                    turbine.getEnergyGeneratedLastTick(),
                    controller.getEnergyStored(EnergySystem.ForgeEnergy).longValue(),
                    controller.getCapacity(EnergySystem.ForgeEnergy).longValue()));
        }

        // 3) 反应堆专属：反射读 ER 内部 FluidContainer + 注册表解析链
        if (controller instanceof CompactReactorController reactor) {
            try {
                final Field field = MultiblockReactor.class.getDeclaredField("_fluidContainer");
                field.setAccessible(true);
                final FluidContainer fc = (FluidContainer) field.get(reactor);

                feedback(source, "mode=%s heat=%.1fK feLastTick=%.1f".formatted(
                        reactor.getOperationalMode(), reactor.getReactorHeatValue().getAsDouble(),
                        reactor.getEnergyGeneratedLastTick()));
                final Reactant waste = reactor.getWasteReactant();
                feedback(source, "fuel=%d waste=%d wasteReactant=%s".formatted(
                        reactor.getFuelAmount(), reactor.getWasteAmount(),
                        null == waste ? "-" : waste.getName()));
                feedback(source, "container: capacity=%d liquid=%d(%s) gas=%d(%s) freeGas=%d".formatted(
                        fc.getCapacity(), fc.getLiquidAmount(),
                        fc.getLiquid().map(f -> BuiltInRegistries.FLUID.getKey(f).toString()).orElse("-"),
                        fc.getGasAmount(),
                        fc.getGas().map(f -> BuiltInRegistries.FLUID.getKey(f).toString()).orElse("-"),
                        fc.getFreeSpace(FluidType.Gas)));
                feedback(source, "liquidTemp=%.1f vaporizedLastTick=%d".formatted(
                        fc.getLiquidTemperature(300.0d), fc.getLiquidVaporizedLastTick()));

                // 注册表解析链：流体 → Coolant → Vaporization 映射 → Vapor → 流体 tag
                final Fluid liquidFluid = fc.getLiquid().orElse(null);
                final Object coolant = invoke(fc, "getCurrentCoolant");
                final Object vaporization = invoke(fc, "getCurrentVaporization");
                final Object vapor = invoke(fc, "getCurrentVapor");
                feedback(source, "coolant=%s empty=%s".formatted(
                        String.valueOf(coolant), String.valueOf(isApiEmpty(coolant, "Coolant"))));
                feedback(source, "hasCoolantFrom(liquid)=%s".formatted(
                        liquidFluid != null && FluidMappingsRegistry.hasCoolantFrom(liquidFluid)));
                if (coolant != null) {
                    final Optional<?> mapping = TransitionsRegistry.get(
                            (it.zerono.mods.extremereactors.api.coolant.Coolant) coolant);
                    feedback(source, "transitions.get(coolant).isPresent=%s".formatted(mapping.isPresent()));
                    if (mapping.isPresent()) {
                        feedback(source, "vaporization.product=%s empty=%s".formatted(
                                String.valueOf(vapor), String.valueOf(isApiEmpty(vapor, "Vapor"))));
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                CompactExtremeReactor.LOGGER.error("cerdev dump 反射失败 @{}", pos, e);
                source.sendFailure(Component.literal("dump 反射失败: " + e));
            }
        }

        // 4) 流化器专属：模式 / 进度 / 进料 / 产物（ASCII 输出，RCON 中文会变 ?）
        if (controller instanceof CompactFluidizerController fluidizer) {
            feedback(source, "fz mode=%s progress=%.3f tick=%d mult=%d".formatted(
                    fluidizer.getRecipeMode(), fluidizer.getRecipeProgress(),
                    fluidizer.getCurrentTick(), fluidizer.getEnergyUsageMultiplier()));
            feedback(source, "fz item0=%s item1=%s".formatted(
                    fzItemToken(fluidizer.getInputItemAt(0)), fzItemToken(fluidizer.getInputItemAt(1))));
            feedback(source, "fz fin0=%s fin1=%s".formatted(
                    fzFluidToken(fluidizer.getInputFluidAt(0)), fzFluidToken(fluidizer.getInputFluidAt(1))));
            feedback(source, "fz out=%s (cap %d)".formatted(
                    fzFluidToken(fluidizer.getOutputTank().getFluidInTank(0)),
                    fluidizer.getOutputTank().getTankCapacity(0)));
        }
        feedback(source, "=== end dump ===");
        return 1;
    }

    /** 流化器 dump 物品 token（ASCII）：empty 或 <item> x<count>。 */
    private static String fzItemToken(net.minecraft.world.item.ItemStack stack) {
        return stack.isEmpty()
                ? "empty"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }

    /** 流化器 dump 流体 token（ASCII）：empty 或 <fluid> x<amount>。 */
    private static String fzFluidToken(FluidStack stack) {
        return stack.isEmpty()
                ? "empty"
                : BuiltInRegistries.FLUID.getKey(stack.getFluid()) + " x" + stack.getAmount();
    }

    /** Coolant.EMPTY / Vapor.EMPTY 判定：调用静态 EMPTY 字段做引用比较。 */
    private static boolean isApiEmpty(Object apiObject, String className) {
        if (apiObject == null) {
            return true;
        }
        try {
            final Field emptyField = Class.forName(
                    "it.zerono.mods.extremereactors.api.coolant." + className).getDeclaredField("EMPTY");
            emptyField.setAccessible(true);
            return emptyField.get(null) == apiObject;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** 调用 FluidContainer 的 protected getter（getCurrentCoolant 等）。 */
    private static Object invoke(FluidContainer fc, String methodName) throws ReflectiveOperationException {
        final Method method = FluidContainer.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(fc);
    }

    /**
     * rods：控制棒插入比例诊断。read 读取当前值；adj 走 {@link
     * CompactReactorTileEntity#adjustControlRodInsertionRatio(int)}——与 GUI 控制棒包
     * 相同的服务端相对调整路径（含越界钳制）；set 走绝对设置路径。
     */
    private static int devRods(CommandSourceStack source, BlockPos pos, String mode, int value) {
        if (!(machineTile(source.getLevel(), pos) instanceof CompactReactorTileEntity reactor)) {
            source.sendFailure(Component.literal("目标不是压缩反应堆"));
            return 0;
        }
        switch (mode) {
            case "adj" -> feedback(source, "rods: 相对调整 %+d -> %d".formatted(
                    value, reactor.adjustControlRodInsertionRatio(value)));
            case "set" -> {
                reactor.setControlRodInsertionRatio(value);
                feedback(source, "rods: 绝对设置 %d -> %d".formatted(value, reactor.getControlRodInsertionRatio()));
            }
            default -> feedback(source, "rods: 当前插入比例 %d".formatted(reactor.getControlRodInsertionRatio()));
        }
        return 1;
    }

    /** item：走真实物品能力路径插入物品（逐槽尝试，与方块右键路径一致；ASCII 输出供 RCON）。 */
    private static int devItem(CommandSourceStack source, BlockPos pos, ResourceLocation itemId, int count) {
        final AbstractCompactMachineTileEntity tile = machineTile(source.getLevel(), pos);
        if (tile == null) {
            source.sendFailure(Component.literal("目标不是压缩机器"));
            return 0;
        }
        final net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(itemId);
        if (item == net.minecraft.world.item.Items.AIR) {
            source.sendFailure(Component.literal("未知物品: " + itemId));
            return 0;
        }
        final net.neoforged.neoforge.items.IItemHandler items;
        if (tile instanceof CompactReactorTileEntity reactor) {
            items = reactor.getItemHandler(null);
        } else if (tile instanceof CompactFluidizerTileEntity fluidizer) {
            items = fluidizer.getItemHandler(null);
        } else {
            items = null;
        }
        if (items == null) {
            source.sendFailure(Component.literal("目标机器无物品能力"));
            return 0;
        }
        // 逐槽尝试 + 槽内重试：底层 ItemStackHolder.insertItem 槽位严格且容量派生自
        // 槽内现有物品（空槽先收 1 个），与方块右键路径逻辑一致
        net.minecraft.world.item.ItemStack remainder = new net.minecraft.world.item.ItemStack(item, count);
        for (int slot = 0; slot < items.getSlots() && !remainder.isEmpty(); slot++) {
            net.minecraft.world.item.ItemStack previous;
            do {
                previous = remainder;
                remainder = items.insertItem(slot, remainder, false);
            } while (!remainder.isEmpty() && remainder.getCount() < previous.getCount());
        }
        final int inserted = count - remainder.getCount();
        feedback(source, "item %s: request=%d inserted=%d slot0=%d slot1=%d".formatted(
                itemId, count, inserted,
                items.getStackInSlot(0).getCount(),
                items.getSlots() > 1 ? items.getStackInSlot(1).getCount() : -1));
        return inserted > 0 ? 1 : 0;
    }

    /** opengui：为在线玩家远程打开机器菜单（与右键相同的 openMenu 网络路径）。 */
    private static int devOpenGui(CommandSourceStack source, BlockPos pos, String playerName) {
        final AbstractCompactMachineTileEntity tile = machineTile(source.getLevel(), pos);
        if (tile == null) {
            source.sendFailure(Component.literal("目标不是压缩机器"));
            return 0;
        }
        final net.minecraft.server.level.ServerPlayer player =
                source.getServer().getPlayerList().getPlayerByName(playerName);
        if (player == null) {
            source.sendFailure(Component.literal("玩家不在线: " + playerName));
            return 0;
        }
        final net.minecraft.world.MenuProvider provider;
        if (tile instanceof CompactReactorTileEntity reactor) {
            provider = new net.minecraft.world.SimpleMenuProvider(
                    (id, inventory, p) -> new com.compact.extremereactor.common.menu.CompactReactorMenu(id, inventory, reactor),
                    tile.getBlockState().getBlock().getName());
        } else if (tile instanceof CompactTurbineTileEntity turbine) {
            provider = new net.minecraft.world.SimpleMenuProvider(
                    (id, inventory, p) -> new com.compact.extremereactor.common.menu.CompactTurbineMenu(id, inventory, turbine),
                    tile.getBlockState().getBlock().getName());
        } else if (tile instanceof CompactFluidizerTileEntity fluidizer) {
            provider = new net.minecraft.world.SimpleMenuProvider(
                    (id, inventory, p) -> new com.compact.extremereactor.common.menu.CompactFluidizerMenu(id, inventory, fluidizer),
                    tile.getBlockState().getBlock().getName());
        } else {
            source.sendFailure(Component.literal("未知机器类型"));
            return 0;
        }
        player.openMenu(provider);
        feedback(source, "opengui %s -> %s @ %s".formatted(
                tile.getBlockState().getBlock(), playerName, pos.toShortString()));
        return 1;
    }

    /** waste：走真实物品能力路径提取废物（与漏斗/管道完全一致的 extractItem）。 */
    private static int devWaste(CommandSourceStack source, BlockPos pos, int count) {
        if (!(machineTile(source.getLevel(), pos) instanceof CompactReactorTileEntity reactor)) {
            source.sendFailure(Component.literal("目标不是压缩反应堆"));
            return 0;
        }
        final net.neoforged.neoforge.items.IItemHandler items = reactor.getItemHandler(null);
        if (items == null) {
            source.sendFailure(Component.literal("反应堆无物品能力"));
            return 0;
        }
        final net.minecraft.world.item.ItemStack extracted = items.extractItem(1, count, false);
        if (extracted.isEmpty()) {
            feedback(source, "waste: 无废物可提取");
            return 0;
        }
        feedback(source, "waste: 提取 %s x%d（槽内剩余 %d）".formatted(
                BuiltInRegistries.ITEM.getKey(extracted.getItem()), extracted.getCount(),
                items.getStackInSlot(1).getCount()));
        return 1;
    }

    /** waste inject：直接注入废物反应物（诊断构造测试态），走控制器 insertWaste 路径。 */
    private static int devWasteInject(CommandSourceStack source, BlockPos pos, int amount) {
        if (!(machineTile(source.getLevel(), pos) instanceof CompactReactorTileEntity tile)
                || !(tile.getController() instanceof CompactReactorController reactor)) {
            source.sendFailure(Component.literal("目标不是压缩反应堆"));
            return 0;
        }
        final Reactant waste = reactor.getWasteReactant();
        if (waste == null) {
            source.sendFailure(Component.literal("反应堆尚无废物反应物（先烧一点燃料）"));
            return 0;
        }
        final int accepted = reactor.insertWaste(waste, amount);
        feedback(source, "waste inject: 注入 %d/%d 当前废物总量 %d".formatted(
                accepted, amount, reactor.getWasteAmount()));
        return 1;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 世界侧流体能力（与真实管道完全相同的查询路径）。 */
    private static IFluidHandler fluidHandler(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        return level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
    }

    private static AbstractCompactMachineTileEntity machineTile(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof AbstractCompactMachineTileEntity tile ? tile : null;
    }

    /** 反馈到指令源（RCON 可见）并写日志文件。 */
    private static void feedback(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("[cerdev] " + message), false);
        CompactExtremeReactor.LOGGER.info("[cerdev @{}] {}", source.getPosition(), message);
    }
}
