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
 * 数据槽布局（客户端通过 addDataSlots 同步，服务端实时从控制器读取）：
 *   0: posReady 标记（同步完成后为 1）  1-3: 方块坐标 X/Y/Z（供客户端发送指令包）
 *   4: 能量存储  5: 能量容量  6: 燃料量  7: 废物量  8: 燃料容量  9: 控制棒插入比例
 */
public class CompactReactorMenu extends AbstractContainerMenu {

    public static final int DATA_POS_READY = 0;
    public static final int DATA_POS_X = 1;
    public static final int DATA_POS_Y = 2;
    public static final int DATA_POS_Z = 3;
    public static final int DATA_ENERGY = 4;
    public static final int DATA_ENERGY_CAPACITY = 5;
    public static final int DATA_FUEL = 6;
    public static final int DATA_WASTE = 7;
    public static final int DATA_FUEL_CAPACITY = 8;
    public static final int DATA_WASTE_CAPACITY = 8; // 废料与燃料共享同一容器容量
    public static final int DATA_CONTROL_ROD = 9;
    public static final int DATA_ACTIVE = 10;
    public static final int DATA_COUNT = 11;

    private final ContainerData _data;

    @Nullable
    private final CompactReactorTileEntity _tile;

    /** 客户端构造：数据从服务端同步，不持有 TileEntity。 */
    public CompactReactorMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null, new SimpleContainerData(DATA_COUNT));
    }

    /** 服务端构造：数据实时读取自控制器。 */
    public CompactReactorMenu(int containerId, Inventory playerInventory, CompactReactorTileEntity tile) {
        this(containerId, playerInventory, tile, new ReactorData(tile));
    }

    private CompactReactorMenu(int containerId, Inventory playerInventory,
                               @Nullable CompactReactorTileEntity tile, ContainerData data) {
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

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /** 读取同步数据槽。 */
    public int getData(int index) {
        return this._data.get(index);
    }

    /** 服务端实时数据源：从反应堆控制器读取当前状态。 */
    private static class ReactorData implements ContainerData {

        private final CompactReactorTileEntity _tile;

        ReactorData(CompactReactorTileEntity tile) {
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
                        case DATA_FUEL -> controller.getFuelAmount();
                        case DATA_WASTE -> controller.getWasteAmount();
                        case DATA_FUEL_CAPACITY -> controller.getFuelCapacity();
                        case DATA_CONTROL_ROD -> this._tile.getControlRodInsertionRatio();
                        case DATA_ACTIVE -> controller.isMachineActive() ? 1 : 0;
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