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

/** 通过 Jade 同步并显示机器状态。 */
public enum CompactMachineProvider implements IBlockComponentProvider {

    INSTANCE;

    private static final ResourceLocation ID = ResourceLocation.parse("compactextremereactor:machine");

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        final CompoundTag data = accessor.getServerData();
        if (data.isEmpty()) {
            return;
        }
        // 控制器初始化失败：红字；仍在排队初始化：黄字（世界加载中的正常过渡态）
        if (!data.getBoolean("Initialized")) {
            if (data.getBoolean("InitFailed")) {
                tooltip.add(Component.translatable("gui.compactextremereactor.init_failed")
                        .withStyle(ChatFormatting.RED));
            } else {
                tooltip.add(Component.translatable("gui.compactextremereactor.controller_initializing")
                        .withStyle(ChatFormatting.YELLOW));
            }
            return;
        }

        // 运行状态：满仓优先（与 GUI 三态一致），不覆盖玩家开关本身
        final boolean energyFull = data.getBoolean("EnergyFull");
        final boolean active = data.getBoolean("Active");
        if (energyFull) {
            tooltip.add(Component.translatable("gui.compactextremereactor.status_energy_full")
                    .withStyle(ChatFormatting.GOLD));
        } else {
            tooltip.add((active
                    ? Component.translatable("gui.compactextremereactor.status_on")
                    : Component.translatable("gui.compactextremereactor.status_off"))
                    .withStyle(active ? ChatFormatting.GREEN : ChatFormatting.RED));
        }

        final long energy = data.getLong("Energy");
        final long capacity = data.getLong("EnergyCapacity");
        if (capacity > 0) {
            tooltip.add(Component.translatable("jade.compactextremereactor.energy",
                    formatEnergy(energy, capacity)));
        }

        final double power = data.getDouble("Power");
        if (power > 0) {
            tooltip.add(Component.translatable("gui.compactextremereactor.power", (int) power));
        }

        final int fuel = data.getInt("Fuel");
        if (fuel > 0) {
            tooltip.add(Component.translatable("gui.compactextremereactor.fuel", fuel));
        }
        final int waste = data.getInt("Waste");
        if (waste > 0) {
            tooltip.add(Component.translatable("gui.compactextremereactor.waste", waste));
        }

        final int steam = data.getInt("Steam");
        if (steam > 0) {
            tooltip.add(Component.translatable("gui.compactextremereactor.steam", steam));
        }
        final int water = data.getInt("Water");
        if (water > 0) {
            tooltip.add(Component.translatable("gui.compactextremereactor.water", water));
        }

        if (data.contains("RecipeMode")) {
            final String modeName = data.getString("RecipeMode");
            tooltip.add(Component.translatable("gui.compactextremereactor.fluidizer_mode",
                    Component.translatable("gui.compactextremereactor.fluidizer_mode_" + modeName.toLowerCase())));
            final int progress = data.getInt("Progress");
            if (progress > 0) {
                tooltip.add(Component.translatable("gui.compactextremereactor.fluidizer_progress", progress));
            }
            final int output = data.getInt("FluidizerOutput");
            if (output > 0) {
                tooltip.add(Component.translatable("gui.compactextremereactor.fluidizer_output",
                        output, data.getInt("FluidizerOutputCapacity")));
            }
        }
    }

    private String formatEnergy(long energy, long capacity) {
        return energy >= 1_000_000
                ? energy / 1000 + " / " + capacity / 1000 + " kFE"
                : energy + " / " + capacity + " FE";
    }

    @Override
    public ResourceLocation getUid() {
        return ID;
    }

    // 服务端数据提供器

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
            // 三态区分：初始化失败（红字）与仍在排队初始化（黄字），都不读后续数据
            if (tile.isControllerInitFailed()) {
                tag.putBoolean("Initialized", false);
                tag.putBoolean("InitFailed", true);
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
                tag.putBoolean("EnergyFull", controller.isEnergyBufferFull());
                tag.putLong("Energy", controller.getEnergyStored(EnergySystem.ForgeEnergy).longValue());
                tag.putLong("EnergyCapacity", controller.getCapacity(EnergySystem.ForgeEnergy).longValue());
                tag.putDouble("Power", controller.getEnergyGeneratedLastTick());

                if (tile instanceof CompactReactorTileEntity) {
                    tag.putInt("Fuel", controller.getFuelAmount());
                    tag.putInt("Waste", controller.getWasteAmount());
                }

                if (tile instanceof CompactTurbineTileEntity) {
                    tag.putInt("Steam", controller.getFluidContainer().getGasAmount());
                    tag.putInt("Water", controller.getFluidContainer().getLiquidAmount());
                }

                if (tile instanceof com.compact.extremereactor.common.tile.CompactFluidizerTileEntity
                        && controller instanceof com.compact.extremereactor.common.multiblock.CompactFluidizerController fluidizer) {
                    tag.putInt("FluidizerOutput", fluidizer.getOutputTank().getFluidInTank(0).getAmount());
                    tag.putInt("FluidizerOutputCapacity", fluidizer.getOutputTank().getTankCapacity(0));
                    tag.putString("RecipeMode", fluidizer.getRecipeMode().name());
                    tag.putInt("Progress", (int) Math.round(fluidizer.getRecipeProgress() * 100.0d));
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