package com.compact.extremereactor.common;

import com.compact.extremereactor.CompactExtremeReactor;
import com.compact.extremereactor.common.block.CompactReactorBlock;
import com.compact.extremereactor.common.block.CompactTurbineBlock;
import com.compact.extremereactor.common.menu.CompactReactorMenu;
import com.compact.extremereactor.common.menu.CompactTurbineMenu;
import com.compact.extremereactor.common.tile.CompactReactorTileEntity;
import com.compact.extremereactor.common.tile.CompactTurbineTileEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 模组注册表：方块、物品、方块实体、创造模式标签。
 */
public final class Content {

    private Content() {
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CompactExtremeReactor.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CompactExtremeReactor.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CompactExtremeReactor.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CompactExtremeReactor.MODID);
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, CompactExtremeReactor.MODID);

    // 压缩反应堆方块：内部模拟整个反应堆多方块
    public static final DeferredBlock<CompactReactorBlock> COMPACT_REACTOR = BLOCKS.register("compact_reactor",
            () -> new CompactReactorBlock(BlockBehaviour.Properties.of().strength(5.0F, 30.0F)));

    // 压缩涡轮机方块：内部模拟整个涡轮机多方块
    public static final DeferredBlock<CompactTurbineBlock> COMPACT_TURBINE = BLOCKS.register("compact_turbine",
            () -> new CompactTurbineBlock(BlockBehaviour.Properties.of().strength(5.0F, 30.0F)));

    public static final DeferredItem<BlockItem> COMPACT_REACTOR_ITEM = ITEMS.registerSimpleBlockItem("compact_reactor", COMPACT_REACTOR);
    public static final DeferredItem<BlockItem> COMPACT_TURBINE_ITEM = ITEMS.registerSimpleBlockItem("compact_turbine", COMPACT_TURBINE);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CompactReactorTileEntity>> COMPACT_REACTOR_ENTITY =
            BLOCK_ENTITY_TYPES.register("compact_reactor", () ->
                    BlockEntityType.Builder.of(CompactReactorTileEntity::new, COMPACT_REACTOR.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CompactTurbineTileEntity>> COMPACT_TURBINE_ENTITY =
            BLOCK_ENTITY_TYPES.register("compact_turbine", () ->
                    BlockEntityType.Builder.of(CompactTurbineTileEntity::new, COMPACT_TURBINE.get()).build(null));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB = CREATIVE_TABS.register(
            "main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.compactextremereactor"))
                    .icon(() -> COMPACT_REACTOR_ITEM.toStack())
                    .displayItems((params, output) -> {
                        output.accept(COMPACT_REACTOR_ITEM.get());
                        output.accept(COMPACT_TURBINE_ITEM.get());
                    })
                    .build());

    // 菜单类型：容器构造器使用 (containerId, playerInventory) 客户端版本（数据经 addDataSlots 同步）
    // NeoForge 1.21 的 MenuType 构造器需要 MenuSupplier + FeatureFlagSet 两个参数
    public static final DeferredHolder<MenuType<?>, MenuType<CompactReactorMenu>> COMPACT_REACTOR_MENU =
            MENU_TYPES.register("compact_reactor", () -> new MenuType<>(CompactReactorMenu::new, FeatureFlags.VANILLA_SET));
    public static final DeferredHolder<MenuType<?>, MenuType<CompactTurbineMenu>> COMPACT_TURBINE_MENU =
            MENU_TYPES.register("compact_turbine", () -> new MenuType<>(CompactTurbineMenu::new, FeatureFlags.VANILLA_SET));

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        MENU_TYPES.register(modEventBus);
    }
}
