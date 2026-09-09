package com.compact.extremereactor.common.capability;

import com.compact.extremereactor.common.multiblock.CompactReactorController;
import it.zerono.mods.extremereactors.api.IMapping;
import it.zerono.mods.extremereactors.api.reactor.Reactant;
import it.zerono.mods.extremereactors.api.reactor.ReactantMappingsRegistry;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/** Combined reactor fluid capability: coolant in, vapor out, and reactant fuel in. */
public final class CompactReactorFluidHandler implements IFluidHandler {
    private final CompactReactorController _reactor;
    private final IFluidHandler _coolant;
    private final IFluidHandler _vapor;
    @Nullable private Runnable _dirtyCallback;
    private boolean _released;

    public CompactReactorFluidHandler(CompactReactorController reactor,
                                      IFluidHandler coolant,
                                      IFluidHandler vapor,
                                      @Nullable Runnable dirtyCallback) {
        this._reactor = reactor;
        this._coolant = coolant;
        this._vapor = vapor;
        this._dirtyCallback = dirtyCallback;
    }

    public void setDirtyCallback(@Nullable Runnable callback) { this._dirtyCallback = callback; }
    public void release() { this._released = true; this._dirtyCallback = null; }

    @Override public int getTanks() {
        return this._released ? 0 : this._coolant.getTanks() + this._vapor.getTanks() + 1;
    }

    @Override public @NotNull FluidStack getFluidInTank(int tank) {
        if (this._released || tank < 0 || tank >= this.getTanks()) return FluidStack.EMPTY;
        if (tank < this._coolant.getTanks()) return this._coolant.getFluidInTank(tank);
        tank -= this._coolant.getTanks();
        if (tank < this._vapor.getTanks()) return this._vapor.getFluidInTank(tank);
        tank -= this._vapor.getTanks();
        return tank == 0 ? this.fuelFluid() : FluidStack.EMPTY;
    }

    @Override public int getTankCapacity(int tank) {
        if (this._released || tank < 0 || tank >= this.getTanks()) return 0;
        if (tank < this._coolant.getTanks()) return this._coolant.getTankCapacity(tank);
        tank -= this._coolant.getTanks();
        if (tank < this._vapor.getTanks()) return this._vapor.getTankCapacity(tank);
        tank -= this._vapor.getTanks();
        return tank == 0 ? this.fuelFluidCapacity() : 0;
    }

    @Override public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        if (this._released || tank < 0 || tank >= this.getTanks() || stack.isEmpty()) return false;
        if (tank < this._coolant.getTanks()) return this._coolant.isFluidValid(tank, stack);
        tank -= this._coolant.getTanks();
        if (tank < this._vapor.getTanks()) return false;
        tank -= this._vapor.getTanks();
        if (tank != 0) return false;
        return this.fuelMapping(stack).map(mapping ->
                mapping.getProduct().getType().isFuel()
                        && mapping.getSourceAmount() > 0
                        && mapping.getProductAmount() > 0)
                .orElse(false);
    }

    @Override public int fill(FluidStack resource, FluidAction action) {
        if (this._released || resource.isEmpty()) return 0;
        final Optional<IMapping<TagKey<Fluid>, Reactant>> mapping = this.fuelMapping(resource);
        if (mapping.isEmpty()) return this._coolant.fill(resource, action);
        final IMapping<TagKey<Fluid>, Reactant> fuelMapping = mapping.get();
        final Reactant reactant = fuelMapping.getProduct();
        final int sourceAmount = fuelMapping.getSourceAmount();
        final int productAmount = fuelMapping.getProductAmount();
        if (sourceAmount <= 0 || productAmount <= 0) return 0;
        final int availableBatches = resource.getAmount() / sourceAmount;
        final int requestedBatches = Math.min(
                availableBatches,
                Integer.MAX_VALUE / productAmount);
        if (requestedBatches <= 0) return 0;
        final int fuelRequested = requestedBatches * productAmount;
        final int fuelAccepted = this._reactor.insertFuel(
                reactant,
                fuelRequested,
                OperationMode.Simulate);
        final int acceptedBatches = Math.min(
                requestedBatches,
                Math.max(0, fuelAccepted) / productAmount);
        if (acceptedBatches <= 0) return 0;
        final int acceptedFluid = acceptedBatches * sourceAmount;
        if (action == FluidAction.EXECUTE) {
            final int inserted = this._reactor.insertFuel(
                    reactant,
                    acceptedBatches * productAmount,
                    OperationMode.Execute);
            final int insertedBatches = Math.min(
                    acceptedBatches,
                    Math.max(0, inserted) / productAmount);
            if (inserted > 0 && this._dirtyCallback != null) this._dirtyCallback.run();
            return insertedBatches * sourceAmount;
        }
        return acceptedFluid;
    }

    @Override public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
        return this._released ? FluidStack.EMPTY : this._vapor.drain(resource, action);
    }
    @Override public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
        return this._released ? FluidStack.EMPTY : this._vapor.drain(maxDrain, action);
    }

    private Optional<IMapping<TagKey<Fluid>, Reactant>> fuelMapping(FluidStack stack) {
        return ReactantMappingsRegistry.getFromFluid(stack)
                .filter(mapping -> mapping.getProduct().getType().isFuel());
    }

    private FluidStack fuelFluid() {
        final int fuelAmount = this._reactor.getFuelAmount();
        return this.currentFuelFluidMapping()
                .map(mapping -> ReactantMappingsRegistry.getFluidStackFrom(mapping,
                        mappedProductAmount(mapping, fuelAmount)))
                .orElse(FluidStack.EMPTY);
    }

    private int fuelFluidCapacity() {
        final int fuelCapacity = Math.max(0, this._reactor.getFuelCapacity());
        if (fuelCapacity == 0) {
            return 0;
        }
        return this.currentFuelFluidMapping()
                .map(mapping -> mappedProductAmount(mapping, fuelCapacity))
                .orElseGet(() -> ReactantMappingsRegistry.getToFluidMap().entrySet().stream()
                        .filter(entry -> entry.getKey().getType().isFuel())
                        .flatMap(entry -> entry.getValue().stream())
                        .filter(CompactReactorFluidHandler::isValidMapping)
                        .mapToInt(mapping -> mappedProductAmount(mapping, fuelCapacity))
                        .max()
                        .orElse(0));
    }

    private Optional<IMapping<Reactant, TagKey<Fluid>>> currentFuelFluidMapping() {
        return this._reactor.getFuel()
                .flatMap(reactant -> ReactantMappingsRegistry.getToFluid(reactant)
                        .flatMap(list -> list.stream()
                                .filter(CompactReactorFluidHandler::isValidMapping)
                                .findFirst()));
    }

    private static boolean isValidMapping(IMapping<?, ?> mapping) {
        return mapping.getSourceAmount() > 0 && mapping.getProductAmount() > 0;
    }

    private static int mappedProductAmount(IMapping<?, ?> mapping, int sourceAmount) {
        if (!isValidMapping(mapping) || sourceAmount <= 0) {
            return 0;
        }
        final long units = sourceAmount / mapping.getSourceAmount();
        return (int) Math.min(units * mapping.getProductAmount(), Integer.MAX_VALUE);
    }
}
