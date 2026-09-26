package com.compact.extremereactor.common.command;

import com.compact.extremereactor.CompactExtremeReactor;
import com.compact.extremereactor.common.capability.CompactEnergyStorage;
import com.compact.extremereactor.common.config.CompactConfig;
import com.compact.extremereactor.common.multiblock.CompactFluidizerController;
import com.compact.extremereactor.common.multiblock.CompactReactorController;
import com.compact.extremereactor.common.multiblock.CompactTurbineController;
import com.compact.extremereactor.common.tile.AbstractCompactMachineTileEntity;
import com.compact.extremereactor.common.tile.CompactFluidizerTileEntity;
import com.compact.extremereactor.common.tile.CompactReactorTileEntity;
import com.compact.extremereactor.common.tile.CompactTurbineTileEntity;
import com.mojang.brigadier.CommandDispatcher;
import it.zerono.mods.extremereactors.api.coolant.FluidMappingsRegistry;
import it.zerono.mods.extremereactors.api.coolant.TransitionsRegistry;
import it.zerono.mods.extremereactors.api.reactor.Reactant;
import it.zerono.mods.extremereactors.gamecontent.multiblock.common.FluidContainer;
import it.zerono.mods.extremereactors.gamecontent.multiblock.common.FluidType;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.MultiblockReactor;
import it.zerono.mods.zerocore.lib.data.WideAmount;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import it.zerono.mods.zerocore.lib.data.nbt.ISyncableEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
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
        }

        feedback(source, "=== selftest %s ===".formatted(passed ? "PASS" : "FAIL"));
        return passed ? 1 : 0;
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
