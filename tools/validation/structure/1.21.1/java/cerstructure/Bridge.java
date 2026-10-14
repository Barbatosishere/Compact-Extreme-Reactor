package cerstructure;

import com.compact.extremereactor.common.tile.AbstractCompactMachineTileEntity;
import it.zerono.mods.zerocore.lib.data.nbt.ISyncableEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

public final class Bridge {
    public static boolean sameStack(net.minecraft.world.item.ItemStack first, net.minecraft.world.item.ItemStack second) {
        return net.minecraft.world.item.ItemStack.isSameItemSameComponents(first, second);
    }
    public static void annotate(net.minecraft.world.item.ItemStack stack, String marker) {
        var tag = new CompoundTag();
        tag.putString("cer-drop-marker", marker);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
    }
    public static void setEnergy(com.compact.extremereactor.common.multiblock.ICompactController controller, long amount, ServerLevel level) {
        var tag = controller.syncDataTo(new CompoundTag(), level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
        String key = controller instanceof com.compact.extremereactor.common.multiblock.CompactFluidizerController ? "energy" : "buffer";
        tag.getCompound(key).put("energy", it.zerono.mods.zerocore.lib.data.WideAmount.from(amount).serializeToNBT());
        controller.syncDataFrom(tag, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
    }
    public static int dropped;
    public static final java.util.List<net.minecraft.world.entity.item.ItemEntity> joinedDrops = new java.util.ArrayList<>();
    public static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item
                && item.getX() > 9990 && item.getX() < 10010 && item.getZ() > 9990 && item.getZ() < 10010) {
            dropped += item.getItem().getCount();
            joinedDrops.add(item);
        }
    }
    public static ResourceLocation id(String namespace, String path) { return ResourceLocation.fromNamespaceAndPath(namespace, path); }
    public static CompoundTag save(AbstractCompactMachineTileEntity tile) { return tile.saveWithFullMetadata(tile.getLevel().registryAccess()); }
    public static void load(AbstractCompactMachineTileEntity tile, CompoundTag tag) throws Exception {
        var method = AbstractCompactMachineTileEntity.class.getDeclaredMethod("loadAdditional", CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
        method.setAccessible(true);
        method.invoke(tile, tag, tile.getLevel().registryAccess());
    }
    public static CompoundTag controllerSave(AbstractCompactMachineTileEntity tile) {
        var tag = new CompoundTag();
        tile.getController().syncDataTo(tag, tile.getLevel().registryAccess(), ISyncableEntity.SyncReason.FullSync);
        return tag;
    }
    public static void fill(AbstractCompactMachineTileEntity tile, boolean steam, int count) {
        var fluid = steam ? BuiltInRegistries.FLUID.get(id("bigreactors", "steam")) : Fluids.WATER;
        tile.getFluidHandler(null).fill(new FluidStack(fluid, count), IFluidHandler.FluidAction.EXECUTE);
    }
    public static Player player(ServerLevel level) { return FakePlayerFactory.getMinecraft(level); }
    public static boolean energyAvailable(AbstractCompactMachineTileEntity tile) {
        return tile.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, tile.getBlockPos(), null) != null;
    }
}
