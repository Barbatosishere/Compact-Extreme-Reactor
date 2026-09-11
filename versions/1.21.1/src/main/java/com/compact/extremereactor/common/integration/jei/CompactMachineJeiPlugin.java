package com.compact.extremereactor.common.integration.jei;

import com.compact.extremereactor.CompactExtremeReactor;
import com.compact.extremereactor.common.Content;
import it.zerono.mods.extremereactors.api.IMapping;
import it.zerono.mods.extremereactors.api.reactor.Reactant;
import it.zerono.mods.extremereactors.api.reactor.ReactantMappingsRegistry;
import it.zerono.mods.extremereactors.api.reactor.Reaction;
import it.zerono.mods.extremereactors.api.reactor.ReactionsRegistry;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@JeiPlugin
public class CompactMachineJeiPlugin implements IModPlugin {

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            CompactExtremeReactor.MODID, "jei_plugin");

    public static final RecipeType<FuelRecipe> FUEL_RECIPE_TYPE =
            RecipeType.create(CompactExtremeReactor.MODID, "fuel", FuelRecipe.class);

    @Override
    public ResourceLocation getPluginUid() {
        return ID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new FuelRecipeCategory(registration.getJeiHelpers()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addRecipes(FUEL_RECIPE_TYPE, buildFuelRecipes());
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(
                new ItemStack(Content.COMPACT_REACTOR.get()), FUEL_RECIPE_TYPE);
    }

    /** 从 ER2 的 ReactionsRegistry 读取所有燃料→废物反应，转换为 JEI 配方。 */
    private List<FuelRecipe> buildFuelRecipes() {
        final List<FuelRecipe> recipes = new ArrayList<>();
        for (Reaction reaction : ReactionsRegistry.getReactions()) {
            // 单 reaction 包 try-catch：第三方 mod 添加非法 Reactant 时仅跳过该 entry，
            // 不影响整体 JEI 加载（避免一个坏配方污染所有配方显示）
            try {
                final Reactant source = reaction.getSource();
                final Reactant product = reaction.getProduct();
                final ItemStack input = getSolidStack(source, 1);
                final ItemStack output = getSolidStack(product, 1);
                if (!input.isEmpty() && !output.isEmpty()) {
                    recipes.add(new FuelRecipe(input, output, reaction.getReactivity(), reaction.getFissionRate()));
                }
            } catch (Throwable t) {
                com.compact.extremereactor.CompactExtremeReactor.LOGGER.warn("JEI 燃料配方注册失败，跳过 reaction {}",
                        reaction, t);
            }
        }
        return recipes;
    }

    /** 将 Reactant 反向映射为物品堆（取第一个映射）。 */
    private static ItemStack getSolidStack(Reactant reactant, int count) {
        final Optional<List<IMapping<Reactant, TagKey<net.minecraft.world.item.Item>>>> mapsOpt =
                ReactantMappingsRegistry.getToSolid(reactant);
        if (mapsOpt.isEmpty() || mapsOpt.get().isEmpty()) {
            return ItemStack.EMPTY;
        }
        return ReactantMappingsRegistry.getSolidStackFrom(mapsOpt.get().get(0), count);
    }
}