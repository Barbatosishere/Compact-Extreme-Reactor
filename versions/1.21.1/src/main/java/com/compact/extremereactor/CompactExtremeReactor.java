package com.compact.extremereactor;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.config.CompactConfig;
import com.compact.extremereactor.common.network.ModPackets;
import com.compact.extremereactor.common.tile.AbstractCompactMachineTileEntity;
import com.compact.extremereactor.common.tile.CompactFluidizerTileEntity;
import com.compact.extremereactor.common.tile.CompactReactorTileEntity;
import com.compact.extremereactor.common.tile.CompactTurbineTileEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 注册压缩反应堆、涡轮机和流化器及其配置、网络与能力。 */
@Mod(CompactExtremeReactor.MODID)
public final class CompactExtremeReactor {

    public static final String MODID = "compactextremereactor";

    public static final Logger LOGGER = LogManager.getLogger();

    public CompactExtremeReactor(IEventBus modEventBus, ModContainer modContainer) {

        // 注册方块 / 物品 / 方块实体 / 创造模式标签 / 菜单类型
        Content.register(modEventBus);
        modEventBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Reloading event) -> {
            if (event.getConfig().getSpec() == CompactConfig.SPEC) ++CompactConfig.structureRevision;
        });

        // 注册自定义网络数据包（GUI → 服务端指令）
        modEventBus.addListener(ModPackets::registerPayloads);

        // 注册方块能力：能量输出 + 流体进料出料（NeoForge 1.21 新能力系统）
        modEventBus.addListener(CompactExtremeReactor::registerCapabilities);

        // 注册模组配置
        modContainer.registerConfig(ModConfig.Type.COMMON, CompactConfig.SPEC);

        // 注册全局 ServerTickEvent 处理器，驱动所有压缩机器每 tick 运行
        //（1.21.1 的 setblock 路径不注册方块 ticker，因此用全局事件替代）
        AbstractCompactMachineTileEntity.registerServerTickHandler();
        // 注册玩家登出清理处理器，回收 ModPackets 限速 Map 中的历史玩家条目
        com.compact.extremereactor.common.network.ModPackets.registerPlayerCleanupHandler();

        // 开发环境专用诊断指令（/cerdev）：生产 jar 不注册，dev/RunServer 环境可用
        if (!FMLEnvironment.production) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    com.compact.extremereactor.common.command.DevCommands::onRegisterCommands);
        }

        // 客户端专用初始化（GUI 屏幕注册）
        if (FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(com.compact.extremereactor.client.ClientHandler::registerScreens);
        }

        // 打印运行时依赖版本（便于玩家诊断 ER/ZeroCore 升级兼容性问题）
        // mods.toml 的 [base, 2.5.0) 范围保证 2.5.0+ 不会加载此 mod，但若版本被绕过（如
        // 手工编辑 JAR）启动仍会进入，错误消息会指引玩家查看此行日志
        LOGGER.info("Compact Extreme Reactor initialized. Required dependencies:");
        net.neoforged.fml.ModList.get().getModContainerById("bigreactors").ifPresentOrElse(
                container -> LOGGER.info("  - bigreactors {}", container.getModInfo().getVersion()),
                () -> LOGGER.warn("  - bigreactors NOT FOUND (this should be impossible; mandatory dependency)"));
        net.neoforged.fml.ModList.get().getModContainerById("zerocore").ifPresentOrElse(
                container -> LOGGER.info("  - zerocore {}", container.getModInfo().getVersion()),
                () -> LOGGER.warn("  - zerocore NOT FOUND (this should be impossible; mandatory dependency)"));
    }

    /** 注册三类机器的能量、流体和物品能力。 */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, Content.COMPACT_REACTOR_ENTITY.get(),
                CompactReactorTileEntity::getEnergyStorage);
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, Content.COMPACT_REACTOR_ENTITY.get(),
                CompactReactorTileEntity::getFluidHandler);
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, Content.COMPACT_REACTOR_ENTITY.get(),
                CompactReactorTileEntity::getItemHandler);
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, Content.COMPACT_TURBINE_ENTITY.get(),
                CompactTurbineTileEntity::getEnergyStorage);
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, Content.COMPACT_TURBINE_ENTITY.get(),
                CompactTurbineTileEntity::getFluidHandler);
        // 流化器：能量只进不出（CompactEnergySink）+ 流体进料/产物 + 固体原料物品
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, Content.COMPACT_FLUIDIZER_ENTITY.get(),
                CompactFluidizerTileEntity::getEnergyStorage);
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, Content.COMPACT_FLUIDIZER_ENTITY.get(),
                CompactFluidizerTileEntity::getFluidHandler);
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, Content.COMPACT_FLUIDIZER_ENTITY.get(),
                CompactFluidizerTileEntity::getItemHandler);
    }
}
