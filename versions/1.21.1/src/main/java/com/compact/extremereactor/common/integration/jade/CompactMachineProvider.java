package com.compact.extremereactor.common.integration.jade;

import com.compact.extremereactor.CompactExtremeReactor;
import com.compact.extremereactor.common.multiblock.ICompactController;
import com.compact.extremereactor.common.tile.AbstractCompactMachineTileEntity;
import com.compact.extremereactor.common.tile.CompactReactorTileEntity;
import com.compact.extremereactor.common.tile.CompactTurbineTileEntity;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.config.IPluginConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * Jade HUD 提供器（客户端）：显示压缩机器的运行状态、能量、燃料/废物等信息。
 * 数据由 {@link ServerProvider} 在服务端收集并写入 {@link CompoundTag}，
 * 客户端通过 {@link BlockAccessor#getServerData()} 读取。
 */
public enum CompactMachineProvider implements IBlockComponentProvider {

    INSTANCE;

    private static final ResourceLocation ID = ResourceLocation.parse("compactextremereactor:machine");

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        final CompoundTag data = accessor.getServerData();
        if (data.isEmpty()) {
            return;
        }
        // 控制器初始化失败：显示"未初始化"红色提示，避免误导显示"已停止"
        if (!data.getBoolean("Initialized")) {
            tooltip.add(Component.translatable("gui.compactextremereactor.init_failed")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        // 运行状态：走 translatable，由 lang 文件控制实际显示文本（en_us/zh_cn 都已定义）
        final boolean active = data.getBoolean("Active");
        tooltip.add((active
                ? Component.translatable("gui.compactextremereactor.status_on")
                : Component.translatable("gui.compactextremereactor.status_off"))
                .withStyle(active ? ChatFormatting.GREEN : ChatFormatting.RED));

        // 能量
        final long energy = data.getLong("Energy");
        final long capacity = data.getLong("EnergyCapacity");
        if (capacity > 0) {
            tooltip.add(Component.translatable("jade.compactextremereactor.energy",
                    formatEnergy(energy, capacity)));
        }

        // 发电量
        final double power = data.getDouble("Power");
        if (power > 0) {
            tooltip.add(Component.translatable("gui.compactextremereactor.power", (int) power));
        }

        // 反应堆：燃料/废物
        final int fuel = data.getInt("Fuel");
        if (fuel > 0) {
            tooltip.add(Component.translatable("gui.compactextremereactor.fuel", fuel));
        }
        final int waste = data.getInt("Waste");
        if (waste > 0) {
            tooltip.add(Component.translatable("gui.compactextremereactor.waste", waste));
        }

        // 涡轮机：蒸汽/水
        final int steam = data.getInt("Steam");
        if (steam > 0) {
            tooltip.add(Component.translatable("gui.compactextremereactor.steam", steam));
        }
        final int water = data.getInt("Water");
        if (water > 0) {
            tooltip.add(Component.translatable("gui.compactextremereactor.water", water));
        }
    }

    private String formatEnergy(long energy, long capacity) {
        // 使用 StringBuilder 替代 String.format：避免每帧创建 Formatter + StringBuilder + String 三对象
        final StringBuilder sb = ENERGY_BUILDER.get();
        sb.setLength(0);
        if (energy >= 1_000_000) {
            // 大数值除以 1000 换算为 kFE，单位须同步标注
            sb.append(energy / 1000).append(" / ").append(capacity / 1000).append(" kFE");
        } else {
            sb.append(energy).append(" / ").append(capacity).append(" FE");
        }
        return sb.toString();
    }

    /** 可复用的 StringBuilder（每线程 1 个）：Jade tooltip 每帧调用 formatEnergy 时避免分配 Formatter */
    private static final ThreadLocal<StringBuilder> ENERGY_BUILDER = ThreadLocal.withInitial(StringBuilder::new);

    @Override
    public ResourceLocation getUid() {
        return ID;
    }

    // ------------------------------------------------------------------
    // 服务端数据提供器
    // ------------------------------------------------------------------

    /**
     * 服务端数据提供器：在服务端线程上读取控制器状态，写入 {@link CompoundTag}，
     * 由 Jade 自动同步到客户端。
     */
    public enum ServerProvider implements IServerDataProvider<BlockAccessor> {

        INSTANCE;

        private static final ResourceLocation ID = ResourceLocation.parse("compactextremereactor:machine_server");

        @Override
        public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof AbstractCompactMachineTileEntity tile)) {
                return;
            }
            // 初始化失败标志：tooltip 据此显示"未初始化"而非误导的"已停止"
            // 标记 Initialized=false 时整个 tag 不读，appendTooltip 提前 return
            if (tile.isControllerInitFailed()) {
                tag.putBoolean("Initialized", false);
                return;
            }
            final ICompactController controller = tile.getController();
            if (controller == null) {
                tag.putBoolean("Initialized", false);
                return;
            }

            // 异常隔离：ER 内部任何抛 NPE 不应污染 Jade 主线程
            try {
                tag.putBoolean("Initialized", true);
                tag.putBoolean("Active", controller.isMachineActive());
                tag.putLong("Energy", controller.getEnergyStored(EnergySystem.ForgeEnergy).longValue());
                tag.putLong("EnergyCapacity", controller.getCapacity(EnergySystem.ForgeEnergy).longValue());
                tag.putDouble("Power", controller.getEnergyGeneratedLastTick());

                // 反应堆特有数据
                if (tile instanceof CompactReactorTileEntity reactorTile) {
                    tag.putInt("Fuel", controller.getFuelAmount());
                    tag.putInt("Waste", controller.getWasteAmount());
                }

                // 涡轮机特有数据
                if (tile instanceof CompactTurbineTileEntity turbineTile) {
                    tag.putInt("Steam", controller.getFluidContainer().getGasAmount());
                    tag.putInt("Water", controller.getFluidContainer().getLiquidAmount());
                }
            } catch (Throwable t) {
                CompactExtremeReactor.LOGGER.warn("Jade appendServerData 异常 @{}", tile.getBlockPos(), t);
                tag.putBoolean("Initialized", false);
            }
        }

        @Override
        public ResourceLocation getUid() {
            return ID;
        }
    }
}