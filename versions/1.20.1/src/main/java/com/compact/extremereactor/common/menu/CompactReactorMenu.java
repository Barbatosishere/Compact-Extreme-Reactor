package com.compact.extremereactor.common.menu;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.multiblock.ICompactController;
import com.compact.extremereactor.common.tile.CompactReactorTileEntity;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 压缩极限反应堆容器：纯状态显示 + 控制棒调节（无燃料槽/玩家背包）。
 * 燃料通过右键点击方块直接注入。
 *
 * 数据槽布局（逻辑数据由 PackedContainerData 拆成两个 16 位槽同步）：
 *   0-2: 方块坐标 X/Y/Z  3: 能量存储  4: 能量容量  5: 燃料量  6: 废物量
 *   7: 燃料容量  8: 控制棒插入比例  9: 激活状态  10: 发电量  11: 初始化失败
 *   12: 堆芯温度  13: posReady 标记  14: 控制器就绪标记
 */
public class CompactReactorMenu extends AbstractContainerMenu {

    public static final int DATA_POS_X = 0;
    public static final int DATA_POS_Y = 1;
    public static final int DATA_POS_Z = 2;
    public static final int DATA_ENERGY = 3;
    public static final int DATA_ENERGY_CAPACITY = 4;
    public static final int DATA_FUEL = 5;
    public static final int DATA_WASTE = 6;
    public static final int DATA_FUEL_CAPACITY = 7;
    // 废料与燃料共享同一容器容量，废物条渲染直接用 DATA_FUEL_CAPACITY 作分母
    public static final int DATA_CONTROL_ROD = 8;
    public static final int DATA_ACTIVE = 9;
    public static final int DATA_POWER = 10;
    /** 控制器初始化失败标志（1=失败，0=正常），用于客户端 GUI 禁用按钮 */
    public static final int DATA_INIT_FAILED = 11;
    /** 反应堆堆芯温度（摄氏度 ×10，int 传输减少精度损失） */
    public static final int DATA_REACTOR_HEAT = 12;
    public static final int DATA_POS_READY = 13;
    /** 控制器已完成初始化标志（1=可操作，0=仍在排队初始化或不可用）。 */
    public static final int DATA_CONTROLLER_READY = 14;
    public static final int DATA_COUNT = 15;

    private final PackedContainerData _data;

    @Nullable
    private final CompactReactorTileEntity _tile;

    /** 客户端构造：数据从服务端同步，不持有 TileEntity。 */
    public CompactReactorMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null,
                new PackedContainerData(new SimpleContainerData(DATA_COUNT * 2), DATA_COUNT, false));
    }

    /** 服务端构造：数据实时读取自控制器。 */
    public CompactReactorMenu(int containerId, Inventory playerInventory, CompactReactorTileEntity tile) {
        this(containerId, playerInventory, tile,
                new PackedContainerData(new ReactorData(tile), DATA_COUNT, true));
    }

    private CompactReactorMenu(int containerId, Inventory playerInventory,
                               @Nullable CompactReactorTileEntity tile, PackedContainerData data) {
        super(Content.COMPACT_REACTOR_MENU.get(), containerId);
        this._tile = tile;
        this._data = data;

        this.addDataSlots(data);
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

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // 无燃料槽，无需归还物品
    }

    /** 读取同步数据槽。 */
    public int getData(int index) {
        return this._data.getValue(index);
    }

    /** 服务端校验控制包是否仍绑定到玩家当前打开的这个机器。 */
    public boolean isForTile(CompactReactorTileEntity tile) {
        return this._tile == tile && !tile.isRemoved();
    }

    /** 服务端实时数据源：从反应堆控制器读取当前状态。 */
    private static class ReactorData implements ContainerData {

        private final CompactReactorTileEntity _tile;

        ReactorData(CompactReactorTileEntity tile) {
            this._tile = tile;
        }

        @Override
        public int get(int index) {
            // 方块坐标独立同步；控制器就绪使用单独标志，避免初始化期间显示伪零数据并启用按钮。
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
                        case DATA_FUEL -> controller.getFuelAmount();
                        case DATA_WASTE -> controller.getWasteAmount();
                        case DATA_FUEL_CAPACITY -> controller.getFuelCapacity();
                        case DATA_CONTROL_ROD -> this._tile.getControlRodInsertionRatio();
                        case DATA_ACTIVE -> controller.isMachineActive() ? 1 : 0;
                        case DATA_POWER -> (int) Math.min(controller.getEnergyGeneratedLastTick(), (double) Integer.MAX_VALUE);
                        case DATA_REACTOR_HEAT -> (int) (controller.getReactorTemperatureCelsius() * 10.0);
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