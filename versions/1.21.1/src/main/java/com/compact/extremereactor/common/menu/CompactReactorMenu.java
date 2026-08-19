package com.compact.extremereactor.common.menu;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.multiblock.CompactReactorController;
import com.compact.extremereactor.common.multiblock.ICompactController;
import com.compact.extremereactor.common.tile.CompactReactorTileEntity;
import it.zerono.mods.extremereactors.api.reactor.ReactantMappingsRegistry;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 压缩极限反应堆容器：1 个燃料输入槽 + 同步数据（能量/燃料/废物/控制棒）。
 *
 * 数据槽布局（客户端通过 addDataSlots 同步，服务端实时从控制器读取）：
 *   0: posReady 标记（同步完成后为 1）  1-3: 方块坐标 X/Y/Z（供客户端发送指令包）
 *   4: 能量存储  5: 能量容量  6: 燃料量  7: 废物量  8: 燃料容量  9: 控制棒插入比例
 *
 * 燃料槽：玩家放入燃料物品（如黄钇矿铤）后，每 tick 通过
 * ReactantMappingsRegistry 映射为 ER 燃料并注入反应堆（等效于真实反应堆的
 * 固体访问端口）。
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
    public static final int DATA_COUNT = 10;

    private final SimpleContainer _fuelSlot;
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
        this._fuelSlot = new SimpleContainer(1);
        this._data = data;

        // 燃料输入槽（等效反应堆固体访问端口）：仅接受可映射为 ER 燃料的物品
        this.addSlot(new Slot(this._fuelSlot, 0, 8, 35) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return ReactantMappingsRegistry.getFromSolid(stack).isPresent();
            }
        });

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
    public void broadcastChanges() {
        super.broadcastChanges();
        // 服务端：自动把燃料槽中的燃料物品送入反应堆
        if (this._tile == null || this._tile.getController() == null) {
            return;
        }
        final ItemStack stack = this._fuelSlot.getItem(0);
        if (stack.isEmpty()) {
            return;
        }
        ReactantMappingsRegistry.getFromSolid(stack).ifPresent(mapping -> {
            final ICompactController controller = this._tile.getController();
            if (controller instanceof CompactReactorController reactor) {
                // 先检查物品数量足够，再模拟注入确认可完全注入，最后执行注入并消耗物品
                // 使用 Simulate 模式防止部分注入导致燃料复制漏洞
                final int productAmount = mapping.getProductAmount();
                if (stack.getCount() >= mapping.getSourceAmount()) {
                    final int simulated = reactor.insertFuel(mapping.getProduct(), productAmount, OperationMode.Simulate);
                    if (simulated >= productAmount) {
                        reactor.insertFuel(mapping.getProduct(), productAmount, OperationMode.Execute);
                        stack.shrink(mapping.getSourceAmount());
                        this._tile.setChanged();
                    }
                }
            }
        });
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        final Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        final ItemStack stack = slot.getItem();
        if (index == 0) {
            // 燃料槽 → 背包
            if (!this.moveItemStackTo(stack, 1, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 背包 → 燃料槽
            if (!this.moveItemStackTo(stack, 0, 1, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
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

    @Override
    public void removed(Player player) {
        super.removed(player);
        // 服务端：把未消耗的燃料物品归还玩家
        if (!player.level().isClientSide()) {
            final ItemStack leftover = this._fuelSlot.removeItemNoUpdate(0);
            if (!leftover.isEmpty()) {
                player.getInventory().placeItemBackInInventory(leftover);
            }
        }
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
