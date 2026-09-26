package com.compact.extremereactor.common.integration.jade;

import com.compact.extremereactor.CompactExtremeReactor;
import com.compact.extremereactor.common.block.CompactFluidizerBlock;
import com.compact.extremereactor.common.block.CompactReactorBlock;
import com.compact.extremereactor.common.block.CompactTurbineBlock;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade HUD 集成。
 *
 * <p>防御：Jade 编译时使用 1.21 端 15.x 版的 API；玩家若装旧版 Jade 12-14，
 * 运行时 {@code registerBlockDataProvider} 签名变化会抛 {@code NoSuchMethodError}。
 * 整个 register 方法 try/catch 兜底，记录 WARN 后不传播，模组主功能（GUI/NBT 持久化）
 * 仍可用。</p>
 */
@WailaPlugin
public class CompactMachineJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        try {
            // 注册服务端数据提供器（负责收集数据）
            registration.registerBlockDataProvider(
                    CompactMachineProvider.ServerProvider.INSTANCE,
                    CompactReactorBlock.class);
            registration.registerBlockDataProvider(
                    CompactMachineProvider.ServerProvider.INSTANCE,
                    CompactTurbineBlock.class);
            registration.registerBlockDataProvider(
                    CompactMachineProvider.ServerProvider.INSTANCE,
                    CompactFluidizerBlock.class);
        } catch (Throwable t) {
            CompactExtremeReactor.LOGGER.warn("Jade 服务端注册失败（API 不兼容？），HUD 集成已禁用", t);
        }
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        try {
            // 注册客户端 HUD 提供器（负责显示数据）
            registration.registerBlockComponent(
                    CompactMachineProvider.INSTANCE,
                    CompactReactorBlock.class);
            registration.registerBlockComponent(
                    CompactMachineProvider.INSTANCE,
                    CompactTurbineBlock.class);
            registration.registerBlockComponent(
                    CompactMachineProvider.INSTANCE,
                    CompactFluidizerBlock.class);
        } catch (Throwable t) {
            CompactExtremeReactor.LOGGER.warn("Jade 客户端注册失败（API 不兼容？），HUD 集成已禁用", t);
        }
    }
}