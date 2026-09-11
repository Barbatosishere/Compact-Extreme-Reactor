package com.compact.extremereactor.common.integration.jei;

import net.minecraft.world.item.ItemStack;

/**
 * JEI 展示的燃料→废物配方数据。
 *
 * @param input       燃料物品（如黄铀锭）
 * @param output      废物物品（如蓝晶锭）
 * @param reactivity  反应性（影响辐射效率）
 * @param fissionRate 裂变率（影响燃料消耗速度）
 */
public record FuelRecipe(ItemStack input, ItemStack output, float reactivity, float fissionRate) {
}