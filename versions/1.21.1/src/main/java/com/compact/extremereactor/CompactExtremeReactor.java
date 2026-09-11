package com.compact.extremereactor;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.config.CompactConfig;
import com.compact.extremereactor.common.network.ModPackets;
import com.compact.extremereactor.common.tile.AbstractCompactMachineTileEntity;
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

/**
 * Compact Extreme Reactor 主类。
 *
 * 本模组将 Extreme Reactors 的大型多方块机器（反应堆 / 涡轮机）压缩为单个紧凑方块：
 * 压缩方块的 TileEntity 直接持有并驱动 ER 的多方块控制器（MultiblockReactor /
 * MultiblockTurbine 的子类），在内部模拟出等效的多方块部件数据（燃料棒数量、
 * 转子叶片、线圈等），从而复用 ER 完整的反应堆/涡轮机逻辑而不需要真实的多方块结构。
 */
@Mod(CompactExtremeReactor.MODID)
public final class CompactExtremeReactor {

    public static final String MODID = "compactextremereactor";

    public static final Logger LOGGER = LogManager.getLogger();

    public CompactExtremeReactor(IEventBus modEventBus, ModContainer modContainer) {

        // 注册方块 / 物品 / 方块实体 / 创造模式标签 / 菜单类型
        Content.register(modEventBus);

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

    /**
     * 注册方块能力（NeoForge 1.21 能力系统：通过事件注册 IBlockCapabilityProvider，
     * 能力查询直接返回对象而非 LazyOptional）。
     * 能量：输出发电量；流体：反应堆=水进/蒸汽出，涡轮机=蒸汽进/水出。
     * 物品：反应堆=燃料进/废物出（管道用）。
     */
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
    }
}
