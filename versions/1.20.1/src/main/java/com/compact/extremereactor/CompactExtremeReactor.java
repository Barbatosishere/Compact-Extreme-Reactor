package com.compact.extremereactor;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.config.CompactConfig;
import com.compact.extremereactor.common.network.ModPackets;
import com.compact.extremereactor.common.tile.AbstractCompactMachineTileEntity;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
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

    public CompactExtremeReactor() {
        // Forge 1.20.1 通过无参构造器反射实例化 mod，mod 总线从上下文获取
        final IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册方块 / 物品 / 方块实体 / 创造模式标签 / 菜单类型
        Content.register(modEventBus);

        // 注册自定义网络数据包（GUI → 服务端指令）
        modEventBus.addListener(ModPackets::onCommonSetup);

        // 注册方块能力：能量输出 + 流体进料出料（Forge 1.20 AttachCapabilitiesEvent）。
        // 注意：该事件由游戏总线（MinecraftForge.EVENT_BUS）发布，注册到 mod 总线将永远收不到。
        MinecraftForge.EVENT_BUS.addGenericListener(BlockEntity.class, CompactExtremeReactor::attachCapabilities);

        // 注册模组配置（47.1.x 分支以 addConfig 替代 registerConfig）
        net.minecraftforge.fml.ModList.get().getModContainerById(MODID)
                .ifPresent(c -> c.addConfig(new ModConfig(ModConfig.Type.COMMON, CompactConfig.SPEC, c)));

        // 客户端专用初始化（GUI 屏幕注册）
        if (FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(com.compact.extremereactor.client.ClientHandler::registerScreens);
        }

        LOGGER.info("Compact Extreme Reactor initialized");
    }

    /**
     * 注册方块能力（Forge 1.20 能力系统：AttachCapabilitiesEvent + ICapabilityProvider）。
     * LazyOptional 由 TileEntity 缓存单实例并在 invalidateCaps 时失效，
     * 避免每次查询新建包装对象导致相邻设备缓存失效通知收不到。
     * 能量：输出发电量；流体：反应堆=水进/蒸汽出，涡轮机=蒸汽进/水出。
     */
    private static void attachCapabilities(AttachCapabilitiesEvent<BlockEntity> event) {
        if (event.getObject() instanceof AbstractCompactMachineTileEntity tile) {
            event.addCapability(new ResourceLocation(MODID, "energy"), new ICapabilityProvider() {
                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
                    return capability == ForgeCapabilities.ENERGY
                            ? tile.getEnergyCapability(side).cast()
                            : LazyOptional.empty();
                }
            });
            event.addCapability(new ResourceLocation(MODID, "fluid"), new ICapabilityProvider() {
                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
                    return capability == ForgeCapabilities.FLUID_HANDLER
                            ? tile.getFluidCapability(side).cast()
                            : LazyOptional.empty();
                }
            });
        }
    }
}
