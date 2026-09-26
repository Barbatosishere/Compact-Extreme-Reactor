package com.compact.extremereactor.common.integration.jade;

import com.compact.extremereactor.CompactExtremeReactor;
import com.compact.extremereactor.common.block.CompactFluidizerBlock;
import com.compact.extremereactor.common.block.CompactReactorBlock;
import com.compact.extremereactor.common.block.CompactTurbineBlock;
import com.compact.extremereactor.common.tile.CompactFluidizerTileEntity;
import com.compact.extremereactor.common.tile.CompactReactorTileEntity;
import com.compact.extremereactor.common.tile.CompactTurbineTileEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade HUD 集成。
 *
 * <p>防御：Jade 编译时使用 1.20.1 端 11.x 版的 API；玩家若装 Jade 12+（不同签名），
 * 运行时 {@code registerBlockDataProvider} 会抛 {@code NoSuchMethodError}。
 * 整个 register 方法 try/catch 兜底，记录 WARN 后不传播，模组主功能（GUI/NBT 持久化）
 * 仍可用。</p>
 */
@WailaPlugin
public class CompactMachineJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        try {
            // 1.20.1 服务端注册：第二个参数是 BlockEntity 类
            registration.registerBlockDataProvider(
                    CompactMachineProvider.ServerProvider.INSTANCE,
                    CompactReactorTileEntity.class);
            registration.registerBlockDataProvider(
                    CompactMachineProvider.ServerProvider.INSTANCE,
                    CompactTurbineTileEntity.class);
            registration.registerBlockDataProvider(
                    CompactMachineProvider.ServerProvider.INSTANCE,
                    CompactFluidizerTileEntity.class);
        } catch (Throwable t) {
            CompactExtremeReactor.LOGGER.warn("Jade 服务端注册失败（API 不兼容？），HUD 集成已禁用", t);
        }
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        try {
            // 客户端注册：第二个参数是 Block 类
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