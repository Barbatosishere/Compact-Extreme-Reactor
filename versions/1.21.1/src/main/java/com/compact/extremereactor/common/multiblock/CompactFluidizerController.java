package com.compact.extremereactor.common.multiblock;

import com.compact.extremereactor.common.capability.CompactFluidizerInputFluidHandler;
import it.zerono.mods.extremereactors.config.Config;
import it.zerono.mods.extremereactors.gamecontent.Content;
import it.zerono.mods.extremereactors.gamecontent.multiblock.fluidizer.recipe.FluidizerFluidMixingRecipe;
import it.zerono.mods.extremereactors.gamecontent.multiblock.fluidizer.recipe.FluidizerSolidMixingRecipe;
import it.zerono.mods.extremereactors.gamecontent.multiblock.fluidizer.recipe.FluidizerSolidRecipe;
import it.zerono.mods.extremereactors.gamecontent.multiblock.fluidizer.recipe.IFluidizerRecipe;
import it.zerono.mods.zerocore.lib.IActivableMachine;
import it.zerono.mods.zerocore.lib.data.IoDirection;
import it.zerono.mods.zerocore.lib.data.WideAmount;
import it.zerono.mods.zerocore.lib.data.geometry.CuboidBoundingBox;
import it.zerono.mods.zerocore.lib.data.nbt.ISyncableEntity;
import it.zerono.mods.zerocore.lib.data.stack.IStackHolder;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import it.zerono.mods.zerocore.lib.energy.WideEnergyBuffer;
import it.zerono.mods.zerocore.lib.fluid.FluidStackHolder;
import it.zerono.mods.zerocore.lib.fluid.FluidTank;
import it.zerono.mods.zerocore.lib.fluid.handler.FluidHandlerPolicyWrapper;
import it.zerono.mods.zerocore.lib.item.inventory.ItemStackHolder;
import it.zerono.mods.zerocore.lib.multiblock.IMultiblockController;
import it.zerono.mods.zerocore.lib.multiblock.IMultiblockPart;
import it.zerono.mods.zerocore.lib.multiblock.cuboid.AbstractCuboidMultiblockController;
import it.zerono.mods.zerocore.lib.multiblock.validation.IMultiblockValidator;
import it.zerono.mods.zerocore.lib.recipe.ModRecipe;
import it.zerono.mods.zerocore.lib.recipe.holder.IHeldRecipe;
import it.zerono.mods.zerocore.lib.recipe.holder.IRecipeHolder;
import it.zerono.mods.zerocore.lib.recipe.holder.RecipeHolder;
import it.zerono.mods.zerocore.lib.recipe.ingredient.IRecipeIngredientSource;
import it.zerono.mods.zerocore.lib.recipe.ingredient.RecipeIngredientSourceWrapper;
import it.zerono.mods.zerocore.lib.recipe.result.FluidStackRecipeResult;
import it.zerono.mods.zerocore.lib.recipe.result.IRecipeResultTarget;
import it.zerono.mods.zerocore.lib.recipe.result.RecipeResultTargetWrapper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 压缩流化器控制器：在单个方块内完整模拟一个 Extreme Reactors 流化器（Fluidizer）多方块。
 *
 * <p>与 {@link CompactReactorController}/{@link CompactTurbineController} 不同，本类<b>不继承</b>
 * ER2 的 {@code MultiblockFluidizer}：其 {@code onMachineAssembled()} 在注入器部件数量为 0 时
 * 直接抛出 {@code IllegalStateException}（压缩机器没有真实注入器部件），且其核心状态
 * （能量缓冲、输出罐、配方持有器）全部为私有字段无法复用。因此改为继承 ZeroCore 的
 * {@link AbstractCuboidMultiblockController} 骨架，复用 ER2 <b>公开</b>的配方体系：</p>
 *
 * <ul>
 *   <li>配方查找：{@code Content.Recipes.FLUIDIZER_RECIPE_TYPE}（与 ER2 同源数据包配方）；</li>
 *   <li>配方实例：{@code FluidizerSolidRecipe / FluidizerSolidMixingRecipe / FluidizerFluidMixingRecipe}
 *       及其公开的 {@code HeldRecipe}（消费/产出逻辑与 ER2 完全一致）；</li>
 *   <li>配方进度：ZeroCore {@link RecipeHolder}（tick 计数、能耗回调、NBT 同步）；</li>
 *   <li>进料/产出：{@link RecipeIngredientSourceWrapper} 与 {@link RecipeResultTargetWrapper}。</li>
 * </ul>
 *
 * <p>三种配方模式（对应真实多方块的注入器布局，压缩机器按当前原料动态选择）：</p>
 * <ul>
 *   <li>{@code Solid}（40 tick）：单一固体进料槽 → 流体；</li>
 *   <li>{@code SolidMixing}（80 tick）：两个固体进料槽混合 → 流体；</li>
 *   <li>{@code FluidMixing}（80 tick）：两个流体进料罐混合 → 流体。</li>
 * </ul>
 *
 * <p>能耗：每 tick 消耗 {@code Config.COMMON.fluidizer.energyPerRecipeTick} FE × 倍率
 * （倍率 = ceil(结果流体量 / 1000)，与 ER2 公式一致）；能量不足时进度清零并暂停（不耗电）。</p>
 */
public class CompactFluidizerController
        extends AbstractCuboidMultiblockController<CompactFluidizerController>
        implements ICompactController, IActivableMachine {

    /** FE 缓冲容量：与 ER2 MultiblockFluidizer.ENERGY_CAPACITY 一致（固定 50k）。 */
    public static final WideAmount ENERGY_CAPACITY = WideAmount.asImmutable(50_000);

    /** 流体进料罐容量（mB）：与 ER2 FluidizerFluidInjectorEntity.MAX_CAPACITY 一致（8 桶）。 */
    public static final int FLUID_INPUT_CAPACITY = 8_000;

    /** 模拟配方模式。Invalid = 没有可用的原料组合。 */
    public enum RecipeMode {
        Solid(IFluidizerRecipe.Type.Solid),
        SolidMixing(IFluidizerRecipe.Type.SolidMixing),
        FluidMixing(IFluidizerRecipe.Type.FluidMixing),
        Invalid(IFluidizerRecipe.Type.Invalid);

        RecipeMode(IFluidizerRecipe.Type type) {
            this._type = type;
        }

        public IFluidizerRecipe.Type getType() {
            return this._type;
        }

        private final IFluidizerRecipe.Type _type;
    }

    // NBT 键（与 ER2 存档键名保持一致的用同名：active/out/energy/recipetick）
    private static final String NBT_ACTIVE = "active";
    private static final String NBT_ENERGY = "energy";
    private static final String NBT_OUTPUT = "out";
    private static final String NBT_ITEMS = "inv";
    private static final String NBT_FLUID_IN_0 = "fin0";
    private static final String NBT_FLUID_IN_1 = "fin1";
    private static final String NBT_RECIPE_SOLID = "rt_solid";
    private static final String NBT_RECIPE_SOLID_MIXING = "rt_solidmixing";
    private static final String NBT_RECIPE_FLUID_MIXING = "rt_fluidmixing";

    private final BlockPos _anchor;
    private final int _sizeX;
    private final int _sizeY;
    private final int _sizeZ;

    private final WideEnergyBuffer _energyBuffer;
    private final FluidTank _outputTank;
    private final IFluidHandler _outputFluidHandler;
    private final IRecipeResultTarget<FluidStackRecipeResult> _fluidTarget;

    /** 固体进料：2 槽（对应真实多方块的两个固体注入器）。 */
    private final ItemStackHolder _itemInputs;
    private final IRecipeIngredientSource<ItemStack>[] _itemSources;

    /** 流体进料：2 罐（对应真实多方块的两个流体注入器），每罐 8000 mB。 */
    private final FluidStackHolder _fluidInputs0;
    private final FluidStackHolder _fluidInputs1;
    private final IRecipeIngredientSource<FluidStack>[] _fluidSources;
    private final CompactFluidizerInputFluidHandler _inputFluidHandler;

    /** 三种模式的配方持有器（tick 计数进度持久化在各自 NBT 子键中）。 */
    private final RecipeHolder<FluidizerSolidRecipe> _solidHolder;
    private final RecipeHolder<FluidizerSolidMixingRecipe> _solidMixingHolder;
    private final RecipeHolder<FluidizerFluidMixingRecipe> _fluidMixingHolder;

    private RecipeMode _mode;

    /** 配方持有器侧的原料变化标志：由 {@link RecipeHolder} 回调消费并触发配方重建。 */
    private boolean _ingredientsChanged;

    /** 模式重算标志：tick 顶部消费，与持有器侧标志分离避免时序耦合。 */
    private volatile boolean _modeDirty;

    /** 变更回调（由 TileEntity 经 setFluidDirtyCallback 注册，原料/产物变化后触发 setChanged 落盘）。 */
    private volatile Runnable _changedCallback;

    @SuppressWarnings("unchecked")
    public CompactFluidizerController(Level level, BlockPos anchor, int sizeX, int sizeY, int sizeZ) {
        super(level);

        this._anchor = anchor.immutable();
        this._sizeX = sizeX;
        this._sizeY = sizeY;
        this._sizeZ = sizeZ;

        this._energyBuffer = new WideEnergyBuffer(EnergySystem.ForgeEnergy, ENERGY_CAPACITY, WideAmount.asImmutable(1000));

        // 输出罐容量 = 内部体积 × 4000 mB，与 ER2 onMachineAssembled 公式一致（9x9x9 → 7³×4000 = 1,372,000 mB）
        final int interior = Math.max(1, (sizeX - 2)) * Math.max(1, (sizeY - 2)) * Math.max(1, (sizeZ - 2));
        this._outputTank = new FluidTank(0).setOnContentsChangedListener(this::onOutputTankChanged);
        this._outputTank.setCapacity(interior * 4000);
        this._outputFluidHandler = FluidHandlerPolicyWrapper.outputOnly(this._outputTank);
        this._fluidTarget = RecipeResultTargetWrapper.wrap(this._outputTank);

        this._itemInputs = new ItemStackHolder(2, ($, stack) -> isValidSolidIngredient(stack))
                .setOnContentsChangedListener(this::onInputsChanged);
        this._itemSources = new IRecipeIngredientSource[]{
                RecipeIngredientSourceWrapper.wrap(this._itemInputs, 0),
                RecipeIngredientSourceWrapper.wrap(this._itemInputs, 1)};

        this._fluidInputs0 = this.createFluidInput();
        this._fluidInputs1 = this.createFluidInput();
        this._fluidSources = new IRecipeIngredientSource[]{
                RecipeIngredientSourceWrapper.wrap(this._fluidInputs0, 0),
                RecipeIngredientSourceWrapper.wrap(this._fluidInputs1, 0)};
        this._inputFluidHandler = new CompactFluidizerInputFluidHandler(this._fluidInputs0, this._fluidInputs1,
                CompactFluidizerController::isValidFluidIngredient);

        this._solidHolder = RecipeHolder.builder(this::solidRecipeFactory, recipe -> recipe.getRecipeType().getTicks())
                .onHasIngredientsChanged(() -> this.consumeIngredientsChanged())
                .onCanProcess(this::canProcessRecipe)
                .onRecipeTickProcessed(this::onRecipeTickProcessed)
                .build();
        this._solidMixingHolder = RecipeHolder.builder(this::solidMixingRecipeFactory, recipe -> recipe.getRecipeType().getTicks())
                .onHasIngredientsChanged(() -> this.consumeIngredientsChanged())
                .onCanProcess(this::canProcessRecipe)
                .onRecipeTickProcessed(this::onRecipeTickProcessed)
                .build();
        this._fluidMixingHolder = RecipeHolder.builder(this::fluidMixingRecipeFactory, recipe -> recipe.getRecipeType().getTicks())
                .onHasIngredientsChanged(() -> this.consumeIngredientsChanged())
                .onCanProcess(this::canProcessRecipe)
                .onRecipeTickProcessed(this::onRecipeTickProcessed)
                .build();

        this._mode = RecipeMode.Invalid;
        this._ingredientsChanged = false;
    }

    // ------------------------------------------------------------------
    // 公开查询（菜单 / Jade / 诊断指令）
    // ------------------------------------------------------------------

    public RecipeMode getRecipeMode() {
        return this._mode;
    }

    /** 当前配方进度（0.0 ~ 1.0）。 */
    public double getRecipeProgress() {
        return switch (this._mode) {
            case Solid -> this.progressOf(this._solidHolder);
            case SolidMixing -> this.progressOf(this._solidMixingHolder);
            case FluidMixing -> this.progressOf(this._fluidMixingHolder);
            case Invalid -> 0.0d;
        };
    }

    /** 当前配方能耗倍率（ceil(结果量/1000)，无配方时为 1）。 */
    public int getEnergyUsageMultiplier() {
        return switch (this._mode) {
            case Solid -> this._solidHolder.getCurrentRecipe().map(h -> h.getRecipe().getEnergyUsageMultiplier()).orElse(1);
            case SolidMixing -> this._solidMixingHolder.getCurrentRecipe().map(h -> h.getRecipe().getEnergyUsageMultiplier()).orElse(1);
            case FluidMixing -> this._fluidMixingHolder.getCurrentRecipe().map(h -> h.getRecipe().getEnergyUsageMultiplier()).orElse(1);
            case Invalid -> 1;
        };
    }

    /** 当前配方还需的 tick 数（用于 GUI 显示预计耗时；无配方返回 -1）。 */
    public int getCurrentTick() {
        return switch (this._mode) {
            case Solid -> this.currentTickOf(this._solidHolder);
            case SolidMixing -> this.currentTickOf(this._solidMixingHolder);
            case FluidMixing -> this.currentTickOf(this._fluidMixingHolder);
            case Invalid -> -1;
        };
    }

    /** 输出罐（供 GUI / Jade 显示）。 */
    public FluidTank getOutputTank() {
        return this._outputTank;
    }

    /** 固体进料槽存储（供物品能力 fail-closed 包装）。 */
    public ItemStackHolder getItemInputs() {
        return this._itemInputs;
    }

    /** 固体进料槽预览堆（槽 0 / 1）。 */
    public ItemStack getInputItemAt(int slot) {
        return slot >= 0 && slot < 2 ? this._itemInputs.getStackAt(slot) : ItemStack.EMPTY;
    }

    /** 流体进料罐内容（罐 0 / 1）。 */
    public FluidStack getInputFluidAt(int tank) {
        return switch (tank) {
            case 0 -> this._fluidInputs0.getStackAt(0);
            case 1 -> this._fluidInputs1.getStackAt(0);
            default -> FluidStack.EMPTY;
        };
    }

    /** 清空全部进料（GUI"清空进料"按钮 / 诊断指令）。 */
    public void clearInputs() {
        this._itemInputs.setStackInSlot(0, ItemStack.EMPTY);
        this._itemInputs.setStackInSlot(1, ItemStack.EMPTY);
        this._fluidInputs0.setStackAt(0, FluidStack.EMPTY);
        this._fluidInputs1.setStackAt(0, FluidStack.EMPTY);
        this._ingredientsChanged = true;
        this._modeDirty = true;
        this._solidHolder.invalidateRecipe();
        this._solidMixingHolder.invalidateRecipe();
        this._fluidMixingHolder.invalidateRecipe();
        this.markChanged();
    }

    /** 固体进料槽容量上限（用于 GUI/管道校验展示）。 */
    public int getItemSlotCapacity(int slot) {
        return slot >= 0 && slot < 2 ? this._itemInputs.getSlotLimit(slot) : 0;
    }

    // ------------------------------------------------------------------
    // 原料校验（静态：管道/方块右键在控制器初始化前也可调用）
    // ------------------------------------------------------------------

    /** 物品是否可作为流化器进料（Solid 或 SolidMixing 配方匹配，忽略数量）。 */
    public static boolean isValidSolidIngredient(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return Content.Recipes.FLUIDIZER_RECIPE_TYPE.get().findFirst(recipe ->
                (recipe instanceof FluidizerSolidRecipe solid && solid.matchIgnoreAmount(stack))
                        || (recipe instanceof FluidizerSolidMixingRecipe mixing && mixing.matchIgnoreAmount(stack))
        ).isPresent();
    }

    /** 流体是否可作为流化器进料（FluidMixing 配方匹配，忽略数量）。 */
    public static boolean isValidFluidIngredient(FluidStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return Content.Recipes.FLUIDIZER_RECIPE_TYPE.get().findFirst(recipe ->
                recipe instanceof FluidizerFluidMixingRecipe mixing && mixing.matchIgnoreAmount(stack)
        ).isPresent();
    }

    // ------------------------------------------------------------------
    // 模拟装配与 tick
    // ------------------------------------------------------------------

    /** 模拟多方块"装配"：触发基类装配回调并按当前原料确定配方模式。 */
    @Override
    public void simulateAssembly() {
        this.onMachineAssembled();
        this._modeDirty = true;
    }

    /** 每个服务端游戏刻驱动一次流化器逻辑（模式重算 + 配方处理）。 */
    @Override
    public void tick() {
        if (this._modeDirty) {
            this._modeDirty = false;
            this.updateMode();
        }
        if (this.isMachineActive()) {
            this.processActiveHolder();
        }
    }

    /**
     * 按当前进料状态确定配方模式；模式切换时废弃所有已持有配方
     * （进度清零，与真实多方块重建注入器布局的语义一致）。
     */
    private void updateMode() {
        final RecipeMode newMode;
        if (!this._fluidInputs0.isEmpty(0) && !this._fluidInputs1.isEmpty(0)) {
            // 流体混合优先：两罐同时有流体即按 FluidMixing 处理（流体可见性最高）
            newMode = RecipeMode.FluidMixing;
        } else if (!this._itemInputs.isEmpty(0) && !this._itemInputs.isEmpty(1)) {
            newMode = RecipeMode.SolidMixing;
        } else if (!this._itemInputs.isEmpty(0) || !this._itemInputs.isEmpty(1)) {
            newMode = RecipeMode.Solid;
        } else {
            newMode = RecipeMode.Invalid;
        }
        if (newMode != this._mode) {
            this._mode = newMode;
            this._solidHolder.invalidateRecipe();
            this._solidMixingHolder.invalidateRecipe();
            this._fluidMixingHolder.invalidateRecipe();
        }
    }

    /** 驱动当前模式的配方持有器处理一 tick（等价于 ER2 updateServer 的 processRecipe）。 */
    private boolean processActiveHolder() {
        return switch (this._mode) {
            case Solid -> this.processHolder(this._solidHolder);
            case SolidMixing -> this.processHolder(this._solidMixingHolder);
            case FluidMixing -> this.processHolder(this._fluidMixingHolder);
            case Invalid -> false;
        };
    }

    private <R extends ModRecipe & IFluidizerRecipe> boolean processHolder(RecipeHolder<R> holder) {
        return holder.getCurrentRecipe().map(IHeldRecipe::processRecipe).orElse(false);
    }

    private double progressOf(RecipeHolder<? extends ModRecipe> holder) {
        return holder.getCurrentRecipe().map(IHeldRecipe::getProgress).orElse(0.0d);
    }

    private int currentTickOf(RecipeHolder<? extends ModRecipe> holder) {
        return holder.getCurrentRecipe().map(IHeldRecipe::getCurrentTick).orElse(-1);
    }

    // ------------------------------------------------------------------
    // 配方工厂与回调（RecipeHolder 绑定）
    // ------------------------------------------------------------------

    private IHeldRecipe<FluidizerSolidRecipe> solidRecipeFactory(IRecipeHolder<FluidizerSolidRecipe> holder) {
        // 消费原料变化标志（与 ER2 工厂一致）：之后持有器持有新配方直到原料再次变化
        this._ingredientsChanged = false;
        final IRecipeIngredientSource<ItemStack> source = this.pickSolidSource();
        return Content.Recipes.FLUIDIZER_RECIPE_TYPE.get()
                .findFirst(recipe -> FluidizerSolidRecipe.lookup(recipe, source))
                .map(recipe -> (FluidizerSolidRecipe) recipe)
                .map(recipe -> new FluidizerSolidRecipe.HeldRecipe(recipe, holder, source, this._fluidTarget))
                .orElse(null);
    }

    private IHeldRecipe<FluidizerSolidMixingRecipe> solidMixingRecipeFactory(IRecipeHolder<FluidizerSolidMixingRecipe> holder) {
        this._ingredientsChanged = false;
        final IRecipeIngredientSource<ItemStack> source1 = this._itemSources[0];
        final IRecipeIngredientSource<ItemStack> source2 = this._itemSources[1];
        return Content.Recipes.FLUIDIZER_RECIPE_TYPE.get()
                .findFirst(recipe -> FluidizerSolidMixingRecipe.lookup(recipe, source1, source2))
                .map(recipe -> (FluidizerSolidMixingRecipe) recipe)
                .map(recipe -> new FluidizerSolidMixingRecipe.HeldRecipe(recipe, holder, source1, source2, this._fluidTarget))
                .orElse(null);
    }

    private IHeldRecipe<FluidizerFluidMixingRecipe> fluidMixingRecipeFactory(IRecipeHolder<FluidizerFluidMixingRecipe> holder) {
        this._ingredientsChanged = false;
        final IRecipeIngredientSource<FluidStack> source1 = this._fluidSources[0];
        final IRecipeIngredientSource<FluidStack> source2 = this._fluidSources[1];
        return Content.Recipes.FLUIDIZER_RECIPE_TYPE.get()
                .findFirst(recipe -> FluidizerFluidMixingRecipe.lookup(recipe, source1, source2))
                .map(recipe -> (FluidizerFluidMixingRecipe) recipe)
                .map(recipe -> new FluidizerFluidMixingRecipe.HeldRecipe(recipe, holder, source1, source2, this._fluidTarget))
                .orElse(null);
    }

    /** Solid 模式的进料源：优先槽 0，槽 0 空时用槽 1（真实多方块只有注入器 0 参与 Solid 模式）。 */
    private IRecipeIngredientSource<ItemStack> pickSolidSource() {
        return this._itemSources[this._itemInputs.isEmpty(0) ? 1 : 0];
    }

    private boolean consumeIngredientsChanged() {
        final boolean changed = this._ingredientsChanged;
        this._ingredientsChanged = false;
        return changed;
    }

    /** 配方能否处理一 tick（与 ER2 canProcessRecipe 语义一致）。 */
    private boolean canProcessRecipe(IFluidizerRecipe recipe) {
        return this.isMachineActive()
                && this.areIngredientsAvailable(recipe)
                && this._energyBuffer.getEnergyStored().longValue() >= (long) Config.COMMON.fluidizer.energyPerRecipeTick.get()
                        * recipe.getEnergyUsageMultiplier()
                && this._fluidTarget.countStorableResults(recipe.getResult()) > 0;
    }

    private boolean areIngredientsAvailable(IFluidizerRecipe recipe) {
        return switch (this._mode) {
            case Solid -> recipe instanceof FluidizerSolidRecipe solid
                    && solid.match(this.pickSolidSource().getIngredient());
            case SolidMixing -> recipe instanceof FluidizerSolidMixingRecipe mixing
                    && mixing.match(this._itemSources[0].getIngredient(), this._itemSources[1].getIngredient());
            case FluidMixing -> recipe instanceof FluidizerFluidMixingRecipe mixing
                    && mixing.match(this._fluidSources[0].getIngredient(), this._fluidSources[1].getIngredient());
            case Invalid -> false;
        };
    }

    /** 配方处理一 tick 的能耗（ER2 公式：energyPerRecipeTick × ceil(结果量/1000)）。 */
    private void onRecipeTickProcessed(int currentTick) {
        final long energyPerTick = Config.COMMON.fluidizer.energyPerRecipeTick.get();
        this._energyBuffer.extractEnergy(EnergySystem.ForgeEnergy,
                WideAmount.from(energyPerTick * (long) this.getEnergyUsageMultiplier()),
                OperationMode.Execute);
    }

    // ------------------------------------------------------------------
    // 进料变化监听
    // ------------------------------------------------------------------

    private void onInputsChanged(IStackHolder.ChangeType changeType, int slot) {
        if (changeType.fullChange() && this.calledByLogicalServer()) {
            this._ingredientsChanged = true;
            this._modeDirty = true;
            this.markChanged();
        }
    }

    private void onFluidInputsChanged(IStackHolder.ChangeType changeType, int slot) {
        this.onInputsChanged(changeType, slot);
    }

    private void onOutputTankChanged(IStackHolder.ChangeType changeType, int index) {
        // 产物变化只影响落盘时机，不影响配方模式
        if (this.calledByLogicalServer()) {
            this.markChanged();
        }
    }

    private void markChanged() {
        final Runnable callback = this._changedCallback;
        if (null != callback) {
            callback.run();
        }
    }

    private FluidStackHolder createFluidInput() {
        final FluidStackHolder holder = new FluidStackHolder(1, ($, stack) -> isValidFluidIngredient(stack));
        holder.setMaxCapacity(FLUID_INPUT_CAPACITY);
        holder.setOnContentsChangedListener(this::onFluidInputsChanged);
        return holder;
    }

    // ------------------------------------------------------------------
    // ICompactController / IWideEnergyStorage2
    // ------------------------------------------------------------------

    @Override
    public void setMachineActive(boolean active) {
        this._active = active;
    }

    @Override
    public boolean isMachineActive() {
        return this._active;
    }

    /**
     * 流化器是能量消费方："满仓"是正常待机而非停机状态。
     * 覆写为 false 避免GUI/Jade 显示与消费型机器无关的"能量已满"三态。
     * （流化器的暂停条件是"能量不足"，由配方处理逻辑内部处理。）
     */
    @Override
    public boolean isEnergyBufferFull() {
        return false;
    }

    @Override
    public WideAmount insertEnergy(EnergySystem system, WideAmount maxAmount, OperationMode mode) {
        return this._energyBuffer.insertEnergy(system, maxAmount, mode);
    }

    @Override
    public WideAmount extractEnergy(EnergySystem system, WideAmount maxAmount, OperationMode mode) {
        // 能量消费方：不允许提取（与 ER2 MultiblockFluidizer 一致）
        return WideAmount.ZERO;
    }

    @Override
    public WideAmount getEnergyStored(EnergySystem system) {
        return this._energyBuffer.getEnergyStored();
    }

    @Override
    public WideAmount getCapacity(EnergySystem system) {
        return this._energyBuffer.getCapacity(system);
    }

    @Override
    public EnergySystem getEnergySystem() {
        return this._energyBuffer.getEnergySystem();
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public void setFluidDirtyCallback(Runnable callback) {
        this._changedCallback = callback;
    }

    @Override
    public Optional<IFluidHandler> getFluidHandler(IoDirection direction) {
        return switch (direction) {
            case Input -> Optional.of(this._inputFluidHandler);
            case Output -> Optional.of(this._outputFluidHandler);
        };
    }

    @Override
    public it.zerono.mods.extremereactors.gamecontent.multiblock.common.IFluidContainer getFluidContainer() {
        // 流化器没有 ER 的 FluidContainer（冷却剂/蒸汽容器）；进料/产物走本类自有储罐
        return null;
    }

    // ------------------------------------------------------------------
    // NBT 持久化
    // ------------------------------------------------------------------

    @Override
    public void syncDataFrom(CompoundTag tag, HolderLookup.Provider registries, ISyncableEntity.SyncReason reason) {
        super.syncDataFrom(tag, registries, reason);

        this.syncBooleanElementFrom(NBT_ACTIVE, tag, registries, active -> this._active = active);
        this.syncChildDataEntityFrom(this._energyBuffer, NBT_ENERGY, tag, registries, reason);
        this.syncChildDataEntityFrom(this._outputTank, NBT_OUTPUT, tag, registries, reason);
        this.syncChildDataEntityFrom(this._itemInputs, NBT_ITEMS, tag, registries, reason);
        this.syncChildDataEntityFrom(this._fluidInputs0, NBT_FLUID_IN_0, tag, registries, reason);
        this.syncChildDataEntityFrom(this._fluidInputs1, NBT_FLUID_IN_1, tag, registries, reason);

        this.updateMode();
        // 先确定模式，再 refresh 和恢复进度，避免首个 tick 切换模式时清除刚读入的进度。
        this._solidHolder.refresh();
        this.syncChildDataEntityFrom(this._solidHolder, NBT_RECIPE_SOLID, tag, registries, reason);
        this._solidMixingHolder.refresh();
        this.syncChildDataEntityFrom(this._solidMixingHolder, NBT_RECIPE_SOLID_MIXING, tag, registries, reason);
        this._fluidMixingHolder.refresh();
        this.syncChildDataEntityFrom(this._fluidMixingHolder, NBT_RECIPE_FLUID_MIXING, tag, registries, reason);

        this._modeDirty = true;
    }

    @Override
    public CompoundTag syncDataTo(CompoundTag tag, HolderLookup.Provider registries, ISyncableEntity.SyncReason reason) {
        tag = super.syncDataTo(tag, registries, reason);

        this.syncBooleanElementTo(NBT_ACTIVE, tag, registries, this.isMachineActive());
        this.syncChildDataEntityTo(this._energyBuffer, NBT_ENERGY, tag, registries, reason);
        this.syncChildDataEntityTo(this._outputTank, NBT_OUTPUT, tag, registries, reason);
        this.syncChildDataEntityTo(this._itemInputs, NBT_ITEMS, tag, registries, reason);
        this.syncChildDataEntityTo(this._fluidInputs0, NBT_FLUID_IN_0, tag, registries, reason);
        this.syncChildDataEntityTo(this._fluidInputs1, NBT_FLUID_IN_1, tag, registries, reason);
        this.syncChildDataEntityTo(this._solidHolder, NBT_RECIPE_SOLID, tag, registries, reason);
        this.syncChildDataEntityTo(this._solidMixingHolder, NBT_RECIPE_SOLID_MIXING, tag, registries, reason);
        this.syncChildDataEntityTo(this._fluidMixingHolder, NBT_RECIPE_FLUID_MIXING, tag, registries, reason);

        return tag;
    }

    // ------------------------------------------------------------------
    // 多方块"谎报"区（压缩机器没有真实部件，框架状态固定为已装配）
    // ------------------------------------------------------------------

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean isAssembled() {
        return true;
    }

    @Override
    public boolean isDisassembled() {
        return false;
    }

    @Override
    public Optional<BlockPos> getReferenceCoord() {
        return Optional.of(this._anchor);
    }

    @Override
    public CuboidBoundingBox getBoundingBox() {
        return new CuboidBoundingBox(this._anchor, this._anchor.offset(this._sizeX - 1, this._sizeY - 1, this._sizeZ - 1));
    }

    @Override
    public void forBoundingBoxCoordinates(BiConsumer<BlockPos, BlockPos> consumer) {
        consumer.accept(this.getBoundingBox().getMin(), this.getBoundingBox().getMax());
    }

    @Override
    public void forBoundingBoxCoordinates(BiConsumer<BlockPos, BlockPos> consumer,
                                          Function<BlockPos, BlockPos> minRemapper,
                                          Function<BlockPos, BlockPos> maxRemapper) {
        consumer.accept(minRemapper.apply(this.getBoundingBox().getMin()),
                maxRemapper.apply(this.getBoundingBox().getMax()));
    }

    @Override
    public <T> T mapBoundingBoxCoordinates(BiFunction<BlockPos, BlockPos, T> mapper, T defaultValue) {
        return mapper.apply(this.getBoundingBox().getMin(), this.getBoundingBox().getMax());
    }

    @Override
    public <T> T mapBoundingBoxCoordinates(BiFunction<BlockPos, BlockPos, T> mapper, T defaultValue,
                                           Function<BlockPos, BlockPos> minRemapper,
                                           Function<BlockPos, BlockPos> maxRemapper) {
        return mapper.apply(minRemapper.apply(this.getBoundingBox().getMin()),
                maxRemapper.apply(this.getBoundingBox().getMax()));
    }

    // ------------------------------------------------------------------
    // AbstractMultiblockController 抽象方法（无部件骨架桩）
    // ------------------------------------------------------------------

    @Override
    protected void onPartAdded(IMultiblockPart<CompactFluidizerController> newPart) {
    }

    @Override
    protected void onPartRemoved(IMultiblockPart<CompactFluidizerController> oldPart) {
    }

    @Override
    protected void onMachineRestored() {
    }

    @Override
    protected void onMachinePaused() {
    }

    @Override
    protected void onMachineDisassembled() {
        // 与 ER2 一致：机器拆散即视为停机（压缩机器不会真正拆散，防御性覆盖）
        this._active = false;
    }

    @Override
    protected int getMinimumNumberOfPartsForAssembledMachine() {
        // 与 ER2 Fluidizer 一致（3x3x3 外壳 - 1 控制器 = 26）
        return 26;
    }

    @Override
    protected int getMaximumXSize() {
        return Config.COMMON.fluidizer.maxFluidizerSize.get();
    }

    @Override
    protected int getMaximumZSize() {
        return Config.COMMON.fluidizer.maxFluidizerSize.get();
    }

    @Override
    protected int getMaximumYSize() {
        return Config.COMMON.fluidizer.maxFluidizerHeight.get();
    }

    @Override
    protected boolean isMachineWhole(IMultiblockValidator validatorCallback) {
        // 压缩机器永远"完整"：框架装配校验只对真实多方块有意义
        return true;
    }

    @Override
    protected void onAssimilate(IMultiblockController<CompactFluidizerController> assimilated) {
    }

    @Override
    protected void onAssimilated(IMultiblockController<CompactFluidizerController> assimilator) {
    }

    @Override
    protected boolean updateServer() {
        // 压缩机器的 tick() 直接驱动配方处理；本方法仅为满足框架契约
        return this.processActiveHolder();
    }

    @Override
    protected void updateClient() {
    }

    @Override
    protected boolean isBlockGoodForFrame(Level world, int x, int y, int z, IMultiblockValidator validatorCallback) {
        return false;
    }

    @Override
    protected boolean isBlockGoodForTop(Level world, int x, int y, int z, IMultiblockValidator validatorCallback) {
        return false;
    }

    @Override
    protected boolean isBlockGoodForBottom(Level world, int x, int y, int z, IMultiblockValidator validatorCallback) {
        return false;
    }

    @Override
    protected boolean isBlockGoodForSides(Level world, int x, int y, int z, IMultiblockValidator validatorCallback) {
        return false;
    }

    @Override
    protected boolean isBlockGoodForInterior(Level world, int x, int y, int z, IMultiblockValidator validatorCallback) {
        return false;
    }

    // ------------------------------------------------------------------
    // 内部状态
    // ------------------------------------------------------------------

    private boolean _active;
}
