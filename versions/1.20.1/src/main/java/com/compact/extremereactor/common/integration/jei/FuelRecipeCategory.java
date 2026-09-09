package com.compact.extremereactor.common.integration.jei;

import com.compact.extremereactor.common.Content;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * JEI 配方类别：压缩反应堆燃料→废物反应。
 *
 * 显示：输入端（燃料物品）→ 箭头 → 输出端（废物物品）
 * 下方显示反应性和裂变率数值。
 */
public class FuelRecipeCategory implements IRecipeCategory<FuelRecipe> {

    private static final int WIDTH = 140;
    private static final int HEIGHT = 55;

    private final IDrawable icon;

    public FuelRecipeCategory(IJeiHelpers helpers) {
        this.icon = helpers.getGuiHelper().createDrawableItemStack(
                new ItemStack(Content.COMPACT_REACTOR.get()));
    }

    @Override
    public RecipeType<FuelRecipe> getRecipeType() {
        return CompactMachineJeiPlugin.FUEL_RECIPE_TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei.compactextremereactor.fuel_recipe");
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public IDrawable getIcon() {
        return this.icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, FuelRecipe recipe, IFocusGroup focuses) {
        // 输入槽：燃料物品
        builder.addSlot(RecipeIngredientRole.INPUT, 18, 10)
                .addItemStack(recipe.input());

        // 输出槽：废物物品
        builder.addSlot(RecipeIngredientRole.OUTPUT, 94, 10)
                .addItemStack(recipe.output());
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, FuelRecipe recipe, IFocusGroup focuses) {
        // 箭头：输入 → 输出
        builder.addRecipeArrow().setPosition(56, 10);

        // 反应性
        builder.addText(Component.translatable("jei.compactextremereactor.reactivity",
                String.format("%.2f", recipe.reactivity())),
                18, 35);

        // 裂变率
        builder.addText(Component.translatable("jei.compactextremereactor.fission_rate",
                String.format("%.4f", recipe.fissionRate())),
                18, 45);
    }
}