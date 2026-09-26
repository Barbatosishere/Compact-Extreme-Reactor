package com.compact.extremereactor.common.menu;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.multiblock.CompactFluidizerController;
import com.compact.extremereactor.common.tile.CompactFluidizerTileEntity;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 压缩流化器容器：纯状态显示 + 开关/清空进料控制（无玩家背包槽位）。
 * 固体原料通过右键点击方块或物品能力（管道）注入。
 *
 * 数据槽布局（逻辑数据由 PackedContainerData 拆成两个 16 位槽同步）：
 *   0-2: 方块坐标 X/Y/Z  3: 能量存储  4: 能量容量
 *   5: 进料槽0物品数  6: 进料槽1物品数  7: 产物流体量(mB)  8: 产物容量(mB)
 *   9: 配方进度(0-1000)  10: 激活状态  11: 初始化失败  12: posReady 标记
 *   13: 控制器就绪标记  14: 配方模式序数  15: 进料罐0流体量  16: 进料罐1流体量
 */
public class CompactFluidizerMenu extends AbstractContainerMenu {

    public static final int DATA_POS_X = 0;
    public static final int DATA_POS_Y = 1;
    public static final int DATA_POS_Z = 2;
    public static final int DATA_ENERGY = 3;
    public static final int DATA_ENERGY_CAPACITY = 4;
    public static final int DATA_ITEM_COUNT_0 = 5;
    public static final int DATA_ITEM_COUNT_1 = 6;
    public static final int DATA_OUTPUT_MB = 7;
    public static final int DATA_OUTPUT_CAPACITY_MB = 8;
    /** 配方进度，0-1000（千分比，避免 0-100 粒度过粗）。 */
    public static final int DATA_PROGRESS = 9;
    public static final int DATA_ACTIVE = 10;
    public static final int DATA_INIT_FAILED = 11;
    public static final int DATA_POS_READY = 12;
    public static final int DATA_CONTROLLER_READY = 13;
    /** {@link CompactFluidizerController.RecipeMode} 序数。 */
    public static final int DATA_RECIPE_MODE = 14;
    public static final int DATA_FLUID_IN_0_MB = 15;
    public static final int DATA_FLUID_IN_1_MB = 16;
    public static final int DATA_COUNT = 17;

    private final PackedContainerData _data;

    @Nullable
    private final CompactFluidizerTileEntity _tile;

    /** 客户端构造：数据从服务端同步，不持有 TileEntity。 */
    public CompactFluidizerMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null,
                new PackedContainerData(new SimpleContainerData(DATA_COUNT * 2), DATA_COUNT, false));
    }

    /** 服务端构造：数据实时读取自控制器。 */
    public CompactFluidizerMenu(int containerId, Inventory playerInventory, CompactFluidizerTileEntity tile) {
        this(containerId, playerInventory, tile,
                new PackedContainerData(new FluidizerData(tile), DATA_COUNT, true));
    }

    private CompactFluidizerMenu(int containerId, Inventory playerInventory,
                                 @Nullable CompactFluidizerTileEntity tile, PackedContainerData data) {
        super(Content.COMPACT_FLUIDIZER_MENU.get(), containerId);
        this._tile = tile;
        this._data = data;

        this.addDataSlots(data);
    }

    @Override
    public boolean stillValid(Player player) {
        // 客户端 _tile 为 null（客户端构造不持有 TileEntity），改用同步来的坐标数据做距离校验。
        // 数据尚未同步（DATA_POS_READY != 1）时保持打开，避免 GUI 刚打开就被关闭。
        if (this._tile != null) {
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

    /** 读取同步数据槽。 */
    public int getData(int index) {
        return this._data.getValue(index);
    }

    /** 服务端校验控制包是否仍绑定到玩家当前打开的这个机器。 */
    public boolean isForTile(CompactFluidizerTileEntity tile) {
        return this._tile == tile && !tile.isRemoved();
    }

    /** 服务端实时数据源：从流化器控制器读取当前状态。 */
    private static class FluidizerData implements ContainerData {

        private final CompactFluidizerTileEntity _tile;

        FluidizerData(CompactFluidizerTileEntity tile) {
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
                    final com.compact.extremereactor.common.multiblock.ICompactController controller =
                            this._tile.getController();
                    if (!(controller instanceof CompactFluidizerController fluidizer)) {
                        yield 0;
                    }
                    yield switch (index) {
                        case DATA_ENERGY -> (int) Math.min(
                                fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).longValue(), Integer.MAX_VALUE);
                        case DATA_ENERGY_CAPACITY -> (int) Math.min(
                                fluidizer.getCapacity(EnergySystem.ForgeEnergy).longValue(), Integer.MAX_VALUE);
                        case DATA_ITEM_COUNT_0 -> fluidizer.getInputItemAt(0).getCount();
                        case DATA_ITEM_COUNT_1 -> fluidizer.getInputItemAt(1).getCount();
                        case DATA_OUTPUT_MB -> fluidizer.getOutputTank().getFluidInTank(0).getAmount();
                        case DATA_OUTPUT_CAPACITY_MB -> fluidizer.getOutputTank().getTankCapacity(0);
                        case DATA_PROGRESS -> (int) Math.round(fluidizer.getRecipeProgress() * 1000.0d);
                        case DATA_ACTIVE -> fluidizer.isMachineActive() ? 1 : 0;
                        case DATA_RECIPE_MODE -> fluidizer.getRecipeMode().ordinal();
                        case DATA_FLUID_IN_0_MB -> fluidizer.getInputFluidAt(0).getAmount();
                        case DATA_FLUID_IN_1_MB -> fluidizer.getInputFluidAt(1).getAmount();
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
