package com.compact.extremereactor;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.config.CompactConfig;
import com.compact.extremereactor.common.network.ModPackets;
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

        // 客户端专用初始化（GUI 屏幕注册）
        if (FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(com.compact.extremereactor.client.ClientHandler::registerScreens);
        }

        LOGGER.info("Compact Extreme Reactor initialized");
    }

    /**
     * 注册方块能力（NeoForge 1.21 能力系统：通过事件注册 IBlockCapabilityProvider，
     * 能力查询直接返回对象而非 LazyOptional）。
     * 能量：输出发电量；流体：反应堆=水进/蒸汽出，涡轮机=蒸汽进/水出。
     */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, Content.COMPACT_REACTOR_ENTITY.get(),
                CompactReactorTileEntity::getEnergyStorage);
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, Content.COMPACT_REACTOR_ENTITY.get(),
                CompactReactorTileEntity::getFluidHandler);
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, Content.COMPACT_TURBINE_ENTITY.get(),
                CompactTurbineTileEntity::getEnergyStorage);
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, Content.COMPACT_TURBINE_ENTITY.get(),
                CompactTurbineTileEntity::getFluidHandler);
    }
}
