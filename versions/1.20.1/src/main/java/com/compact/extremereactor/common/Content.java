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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 模组注册表：方块、物品、方块实体、创造模式标签、菜单类型。
 */
public final class Content {

    private Content() {
    }

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, CompactExtremeReactor.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, CompactExtremeReactor.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, CompactExtremeReactor.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CompactExtremeReactor.MODID);
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, CompactExtremeReactor.MODID);

    // 压缩极限反应堆方块：内部模拟整个反应堆多方块
    public static final RegistryObject<CompactReactorBlock> COMPACT_REACTOR = BLOCKS.register("compact_reactor",
            () -> new CompactReactorBlock(BlockBehaviour.Properties.of().strength(5.0F, 30.0F)));

    // 压缩涡轮机方块：内部模拟整个涡轮机多方块
    public static final RegistryObject<CompactTurbineBlock> COMPACT_TURBINE = BLOCKS.register("compact_turbine",
            () -> new CompactTurbineBlock(BlockBehaviour.Properties.of().strength(5.0F, 30.0F)));

    public static final RegistryObject<BlockItem> COMPACT_REACTOR_ITEM = ITEMS.register("compact_reactor",
            () -> new BlockItem(COMPACT_REACTOR.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> COMPACT_TURBINE_ITEM = ITEMS.register("compact_turbine",
            () -> new BlockItem(COMPACT_TURBINE.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<CompactReactorTileEntity>> COMPACT_REACTOR_ENTITY =
            BLOCK_ENTITY_TYPES.register("compact_reactor", () ->
                    BlockEntityType.Builder.of(CompactReactorTileEntity::new, COMPACT_REACTOR.get()).build(null));

    public static final RegistryObject<BlockEntityType<CompactTurbineTileEntity>> COMPACT_TURBINE_ENTITY =
            BLOCK_ENTITY_TYPES.register("compact_turbine", () ->
                    BlockEntityType.Builder.of(CompactTurbineTileEntity::new, COMPACT_TURBINE.get()).build(null));

    public static final RegistryObject<CreativeModeTab> CREATIVE_TAB = CREATIVE_TABS.register(
            "main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.compactextremereactor"))
                    .icon(() -> new ItemStack(COMPACT_REACTOR_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(COMPACT_REACTOR_ITEM.get());
                        output.accept(COMPACT_TURBINE_ITEM.get());
                    })
                    .build());

    // 菜单类型：容器构造器使用 (containerId, playerInventory) 客户端版本（数据经 addDataSlots 同步）
    // 1.20.1 的 MenuType 构造器需要 MenuSupplier + FeatureFlagSet 两个参数
    public static final RegistryObject<MenuType<CompactReactorMenu>> COMPACT_REACTOR_MENU =
            MENU_TYPES.register("compact_reactor", () -> new MenuType<>(CompactReactorMenu::new, FeatureFlags.VANILLA_SET));
    public static final RegistryObject<MenuType<CompactTurbineMenu>> COMPACT_TURBINE_MENU =
            MENU_TYPES.register("compact_turbine", () -> new MenuType<>(CompactTurbineMenu::new, FeatureFlags.VANILLA_SET));

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        MENU_TYPES.register(modEventBus);
    }
}
