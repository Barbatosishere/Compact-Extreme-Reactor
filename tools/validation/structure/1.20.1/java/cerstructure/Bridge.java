package cerstructure;

import com.compact.extremereactor.common.tile.AbstractCompactMachineTileEntity;
import it.zerono.mods.zerocore.lib.data.nbt.ISyncableEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.common.util.FakePlayerFactory;

public final class Bridge {
    public static it.zerono.mods.zerocore.lib.recipe.ModRecipeType<it.zerono.mods.zerocore.lib.recipe.ModRecipe> fluidizerRecipeType() {
        return it.zerono.mods.extremereactors.gamecontent.Content.Recipes.FLUIDIZER_RECIPE_TYPE;
    }
    public static FluidStack fluid(net.minecraft.world.level.material.Fluid fluid, int amount) {
        return new FluidStack(fluid, amount);
    }
    public static IFluidHandler.FluidAction action(boolean simulate) {
        return simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE;
    }
    public static CompoundTag saveController(com.compact.extremereactor.common.multiblock.ICompactController controller, ServerLevel level) {
        return controller.syncDataTo(new CompoundTag(), ISyncableEntity.SyncReason.FullSync);
    }
    public static void loadController(com.compact.extremereactor.common.multiblock.ICompactController controller, CompoundTag tag, ServerLevel level) {
        controller.syncDataFrom(tag, ISyncableEntity.SyncReason.FullSync);
    }
    public static boolean sameStack(net.minecraft.world.item.ItemStack first, net.minecraft.world.item.ItemStack second) {
        return net.minecraft.world.item.ItemStack.isSameItemSameTags(first, second);
    }
    public static void annotate(net.minecraft.world.item.ItemStack stack, String marker) {
        stack.getOrCreateTag().putString("cer-drop-marker", marker);
    }
    public static void setEnergy(com.compact.extremereactor.common.multiblock.ICompactController controller, long amount, ServerLevel level) {
        var tag = controller.syncDataTo(new CompoundTag(), ISyncableEntity.SyncReason.FullSync);
        String key = controller instanceof com.compact.extremereactor.common.multiblock.CompactFluidizerController ? "energy" : "buffer";
        tag.getCompound(key).put("energy", it.zerono.mods.zerocore.lib.data.WideAmount.from(amount).serializeTo(new CompoundTag()));
        controller.syncDataFrom(tag, ISyncableEntity.SyncReason.FullSync);
    }
    public static int dropped;
    public static final java.util.List<net.minecraft.world.entity.item.ItemEntity> joinedDrops = new java.util.ArrayList<>();
    public static void onJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item
                && item.getX() > 9990 && item.getX() < 10010 && item.getZ() > 9990 && item.getZ() < 10010) {
            dropped += item.getItem().getCount();
            joinedDrops.add(item);
        }
    }
    public static ResourceLocation id(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }
    public static CompoundTag save(AbstractCompactMachineTileEntity tile) { return tile.saveWithFullMetadata(); }
    public static void load(AbstractCompactMachineTileEntity tile, CompoundTag tag) throws Exception { tile.load(tag); }

    public static CompoundTag controllerSave(AbstractCompactMachineTileEntity tile) {
        var tag = new CompoundTag();
        tile.getController().syncDataTo(tag, ISyncableEntity.SyncReason.FullSync);
        return tag;
    }
    public static void fill(AbstractCompactMachineTileEntity tile, boolean steam, int count) {
        var fluid = steam ? BuiltInRegistries.FLUID.get(id("bigreactors", "steam")) : Fluids.WATER;
        tile.getFluidHandler(null).fill(new FluidStack(fluid, count), IFluidHandler.FluidAction.EXECUTE);
    }
    public static Player player(ServerLevel level) { return FakePlayerFactory.getMinecraft(level); }
    public static boolean energyAvailable(AbstractCompactMachineTileEntity tile) {
        return tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY).isPresent();
    }
}
