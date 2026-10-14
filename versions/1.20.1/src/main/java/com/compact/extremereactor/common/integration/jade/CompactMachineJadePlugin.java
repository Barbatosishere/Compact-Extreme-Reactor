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

/** 注册 Jade HUD；API 不兼容时记录警告并禁用对应集成。 */
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