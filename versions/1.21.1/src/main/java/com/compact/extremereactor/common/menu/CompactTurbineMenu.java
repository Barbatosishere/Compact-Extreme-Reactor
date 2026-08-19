package com.compact.extremereactor.common.menu;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.multiblock.ICompactController;
import com.compact.extremereactor.common.tile.CompactTurbineTileEntity;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 压缩涡轮机容器：无槽位，纯状态显示。
 *
 * 数据槽布局（客户端通过 addDataSlots 同步）：
 *   0: posReady 标记  1-3: 方块坐标 X/Y/Z
 *   4: 能量存储  5: 能量容量  6: 蒸汽量  7: 水量  8: 流体总容量
 *
 * 蒸汽通过流体能力输入（玩家可用流体管道灌入），本容器只负责显示。
 */
public class CompactTurbineMenu extends AbstractContainerMenu {

    public static final int DATA_POS_READY = 0;
    public static final int DATA_POS_X = 1;
    public static final int DATA_POS_Y = 2;
    public static final int DATA_POS_Z = 3;
    public static final int DATA_ENERGY = 4;
    public static final int DATA_ENERGY_CAPACITY = 5;
    public static final int DATA_STEAM = 6;
    public static final int DATA_WATER = 7;
    public static final int DATA_FLUID_CAPACITY = 8;
    public static final int DATA_POWER = 9;
    public static final int DATA_COUNT = 10;

    private final ContainerData _data;

    @Nullable
    private final CompactTurbineTileEntity _tile;

    /** 客户端构造：数据从服务端同步，不持有 TileEntity。 */
    public CompactTurbineMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null, new SimpleContainerData(DATA_COUNT));
    }

    /** 服务端构造：数据实时读取自控制器。 */
    public CompactTurbineMenu(int containerId, Inventory playerInventory, CompactTurbineTileEntity tile) {
        this(containerId, playerInventory, tile, new TurbineData(tile));
    }

    private CompactTurbineMenu(int containerId, Inventory playerInventory,
                               @Nullable CompactTurbineTileEntity tile, ContainerData data) {
        super(Content.COMPACT_TURBINE_MENU.get(), containerId);
        this._tile = tile;
        this._data = data;

        // 玩家背包（3 行 x 9 列）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }

        this.addDataSlots(data);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // 无机器槽位，禁止快捷移动
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        // 客户端 _tile 为 null（客户端构造不持有 TileEntity），改用同步来的坐标数据做距离校验。
        // 数据尚未同步（DATA_POS_READY != 1）时保持打开，避免 GUI 刚打开就被关闭。
        if (this._tile != null) {
            return player.distanceToSqr(this._tile.getBlockPos().getCenter()) < 64;
        }
        if (this._data.get(DATA_POS_READY) != 1) {
            return true;
        }
        final double x = this._data.get(DATA_POS_X) + 0.5;
        final double y = this._data.get(DATA_POS_Y) + 0.5;
        final double z = this._data.get(DATA_POS_Z) + 0.5;
        return player.distanceToSqr(x, y, z) < 64;
    }

    /** 读取同步数据槽。 */
    public int getData(int index) {
        return this._data.get(index);
    }

    /** 服务端实时数据源：从涡轮机控制器读取当前状态。 */
    private static class TurbineData implements ContainerData {

        private final CompactTurbineTileEntity _tile;

        TurbineData(CompactTurbineTileEntity tile) {
            this._tile = tile;
        }

        @Override
        public int get(int index) {
            // 方块坐标与就绪标记不依赖控制器：保证 GUI 按钮始终可用
            return switch (index) {
                case DATA_POS_READY -> 1;
                case DATA_POS_X -> this._tile.getBlockPos().getX();
                case DATA_POS_Y -> this._tile.getBlockPos().getY();
                case DATA_POS_Z -> this._tile.getBlockPos().getZ();
                default -> {
                    final ICompactController controller = this._tile.getController();
                    if (controller == null) {
                        yield 0;
                    }
                    yield switch (index) {
                        case DATA_ENERGY -> (int) Math.min(controller.getEnergyStored(EnergySystem.ForgeEnergy).longValue(), Integer.MAX_VALUE);
                        case DATA_ENERGY_CAPACITY -> (int) Math.min(controller.getCapacity(EnergySystem.ForgeEnergy).longValue(), Integer.MAX_VALUE);
                        case DATA_STEAM -> controller.getFluidContainer().getGasAmount();
                        case DATA_WATER -> controller.getFluidContainer().getLiquidAmount();
                        case DATA_FLUID_CAPACITY -> controller.getFluidContainer().getCapacity();
                        case DATA_POWER -> (int) Math.min(controller.getEnergyGeneratedLastTick(), (double) Integer.MAX_VALUE);
                        default -> 0;
                    };
                }
            };
        }

        @Override
        public void set(int index, int value) {
            // 服务端数据只读；客户端数据由 SimpleContainerData 处理
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    }
}
