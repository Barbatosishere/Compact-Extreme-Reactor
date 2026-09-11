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
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 压缩涡轮机容器：无槽位，纯状态显示（无玩家背包）。
 * 蒸汽通过流体能力输入（玩家可用流体管道灌入），本容器只负责显示。
 *
 * 数据槽布局（逻辑数据由 PackedContainerData 拆成两个 16 位槽同步）：
 *   0-2: 方块坐标 X/Y/Z  3: 能量存储  4: 能量容量  5: 蒸汽量  6: 水量
 *   7: 流体总容量  8: 发电量  9: 初始化失败  10: 转子转速
 *   11: posReady 标记  12: 控制器就绪标记
 */
public class CompactTurbineMenu extends AbstractContainerMenu {

    public static final int DATA_POS_X = 0;
    public static final int DATA_POS_Y = 1;
    public static final int DATA_POS_Z = 2;
    public static final int DATA_ENERGY = 3;
    public static final int DATA_ENERGY_CAPACITY = 4;
    public static final int DATA_STEAM = 5;
    public static final int DATA_WATER = 6;
    public static final int DATA_FLUID_CAPACITY = 7;
    public static final int DATA_POWER = 8;
    /** 控制器初始化失败标志（1=失败，0=正常），用于客户端 GUI 禁用按钮 */
    public static final int DATA_INIT_FAILED = 9;
    /** 涡轮机转子转速（弧度/秒 ×10，int 传输） */
    public static final int DATA_ROTOR_SPEED = 10;
    public static final int DATA_POS_READY = 11;
    /** 控制器已完成初始化标志（1=可显示真实状态，0=仍在排队初始化或不可用）。 */
    public static final int DATA_CONTROLLER_READY = 12;
    public static final int DATA_COUNT = 13;

    private final PackedContainerData _data;

    @Nullable
    private final CompactTurbineTileEntity _tile;

    /** 客户端构造：数据从服务端同步，不持有 TileEntity。 */
    public CompactTurbineMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null,
                new PackedContainerData(new SimpleContainerData(DATA_COUNT * 2), DATA_COUNT, false));
    }

    /** 服务端构造：数据实时读取自控制器。 */
    public CompactTurbineMenu(int containerId, Inventory playerInventory, CompactTurbineTileEntity tile) {
        this(containerId, playerInventory, tile,
                new PackedContainerData(new TurbineData(tile), DATA_COUNT, true));
    }

    private CompactTurbineMenu(int containerId, Inventory playerInventory,
                               @Nullable CompactTurbineTileEntity tile, PackedContainerData data) {
        super(Content.COMPACT_TURBINE_MENU.get(), containerId);
        this._tile = tile;
        this._data = data;

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
            // isRemoved 守卫：方块被破坏时立即关闭 GUI（否则距离校验仍通过，
            // 玩家对着已消失的机器继续操作一个 tick 后才被距离/卸载发现）
            return !this._tile.isRemoved() && player.distanceToSqr(this._tile.getBlockPos().getCenter()) < 64;
        }
        if (this._data.getValue(DATA_POS_READY) != 1) {
            return true;
        }
        final double x = this._data.getValue(DATA_POS_X) + 0.5;
        final double y = this._data.getValue(DATA_POS_Y) + 0.5;
        final double z = this._data.getValue(DATA_POS_Z) + 0.5;
        return player.distanceToSqr(x, y, z) < 64;
    }

    /** 读取同步数据槽。 */
    public int getData(int index) {
        return this._data.getValue(index);
    }

    /** 服务端实时数据源：从涡轮机控制器读取当前状态。 */
    private static class TurbineData implements ContainerData {

        private final CompactTurbineTileEntity _tile;

        TurbineData(CompactTurbineTileEntity tile) {
            this._tile = tile;
        }

        @Override
        public int get(int index) {
            // 方块坐标独立同步；控制器就绪使用单独标志，避免初始化期间显示伪零数据。
            return switch (index) {
                case DATA_POS_READY -> 1;
                case DATA_POS_X -> this._tile.getBlockPos().getX();
                case DATA_POS_Y -> this._tile.getBlockPos().getY();
                case DATA_POS_Z -> this._tile.getBlockPos().getZ();
                case DATA_INIT_FAILED -> this._tile.isControllerInitFailed() ? 1 : 0;
                case DATA_CONTROLLER_READY -> this._tile.isControllerReady() ? 1 : 0;
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
                        case DATA_ROTOR_SPEED -> (int) (controller.getRotorAngularSpeed() * 10.0);
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