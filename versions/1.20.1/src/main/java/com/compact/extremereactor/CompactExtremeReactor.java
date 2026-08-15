package com.compact.extremereactor;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.config.CompactConfig;
import com.compact.extremereactor.common.network.ModPackets;
import com.compact.extremereactor.common.tile.AbstractCompactMachineTileEntity;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

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
        modEventBus.addListener(ModPackets::onCommonSetup);

        // 注册方块能力：能量输出 + 流体进料出料（Forge 1.20 AttachCapabilitiesEvent）
        modEventBus.addGenericListener(BlockEntity.class, CompactExtremeReactor::attachCapabilities);

        // 注册模组配置（47.1.x 分支以 addConfig 替代 registerConfig）
        modContainer.addConfig(new ModConfig(ModConfig.Type.COMMON, CompactConfig.SPEC, modContainer));

        // 客户端专用初始化（GUI 屏幕注册）
        if (FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(com.compact.extremereactor.client.ClientHandler::registerScreens);
        }

        LOGGER.info("Compact Extreme Reactor initialized");
    }

    /**
     * 注册方块能力（Forge 1.20 能力系统：AttachCapabilitiesEvent + ICapabilityProvider，
     * 能力查询返回 LazyOptional 包装）。
     * 通过 ICapabilityProvider 用 LazyOptional 包装能力对象。
     * 能量：输出发电量；流体：反应堆=水进/蒸汽出，涡轮机=蒸汽进/水出。
     */
    private static void attachCapabilities(AttachCapabilitiesEvent<BlockEntity> event) {
        if (event.getObject() instanceof AbstractCompactMachineTileEntity tile) {
            event.addCapability(new ResourceLocation(MODID, "energy"), new ICapabilityProvider() {
                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
                    if (capability == ForgeCapabilities.ENERGY) {
                        final IEnergyStorage storage = tile.getEnergyStorage(side);
                        return storage == null ? LazyOptional.empty() : LazyOptional.of(() -> storage).cast();
                    }
                    return LazyOptional.empty();
                }
            });
            event.addCapability(new ResourceLocation(MODID, "fluid"), new ICapabilityProvider() {
                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
                    if (capability == ForgeCapabilities.FLUID_HANDLER) {
                        final IFluidHandler fluidHandler = tile.getFluidHandler(side);
                        return fluidHandler == null ? LazyOptional.empty() : LazyOptional.of(() -> fluidHandler).cast();
                    }
                    return LazyOptional.empty();
                }
            });
        }
    }
}
