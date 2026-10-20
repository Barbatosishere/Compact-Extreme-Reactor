package cerstructure;

import com.compact.extremereactor.common.multiblock.CompactFluidizerController;
import com.compact.extremereactor.common.multiblock.CompactReactorController;
import com.compact.extremereactor.common.multiblock.CompactTurbineController;
import com.compact.extremereactor.common.multiblock.ICompactController;
import it.zerono.mods.extremereactors.gamecontent.multiblock.fluidizer.recipe.FluidizerFluidMixingRecipe;
import it.zerono.mods.zerocore.lib.data.IoDirection;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import it.zerono.mods.zerocore.lib.data.WideAmount;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import it.zerono.mods.zerocore.lib.recipe.ModRecipeType;
import it.zerono.mods.zerocore.lib.recipe.ingredient.FluidStackRecipeIngredient;
import it.zerono.mods.zerocore.lib.recipe.result.FluidStackRecipeResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.Fluid;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** Additional regressions for non-default capacities and custom recipe inputs. */
public final class BoundaryChecks {
    public static void run(ServerLevel level, BiConsumer<String, Boolean> check, Map<String, Object> details) throws Exception {
        for (boolean turbine : List.of(false, true)) smallGenerator(level, turbine, check, details);
        sameFluidRecipe(level, check, details);
        coexistingFluidRecipes(level, check);
    }

    private static ICompactController generator(ServerLevel level, boolean turbine, double scale) {
        var anchor = new BlockPos(10000, 100, 10000);
        ICompactController controller = turbine
                ? new CompactTurbineController(level, anchor, 1, 3, 3, 3, scale)
                : new CompactReactorController(level, anchor, 1, 1, 1, 3, 3, 3, scale);
        controller.simulateAssembly();
        controller.setMachineActive(false);
        return controller;
    }

    private static void smallGenerator(ServerLevel level, boolean turbine,
                                       BiConsumer<String, Boolean> check, Map<String, Object> details) {
        String prefix = (turbine ? "turbine" : "reactor") + ": small tank ";
        var large = generator(level, turbine, 16);
        var input = large.getFluidHandler(IoDirection.Input).orElseThrow();
        var fluid = turbine ? BuiltInRegistries.FLUID.get(Bridge.id("bigreactors", "steam")) : Fluids.WATER;
        int capacity = large.getFluidContainer().getCapacity();
        var resource = Bridge.fluid(fluid, Integer.MAX_VALUE);
        CompoundTag empty = Bridge.saveController(large, level);
        int simulated = input.fill(resource, Bridge.action(true));
        check.accept(prefix + "simulation is exact and read only", simulated == capacity
                && empty.equals(Bridge.saveController(large, level)) && resource.getAmount() == Integer.MAX_VALUE);
        int inserted = input.fill(resource, Bridge.action(false));
        check.accept(prefix + "fill clamps to capacity", inserted == capacity);
        CompoundTag saved = Bridge.saveController(large, level);
        CompoundTag original = saved.copy();
        var small = generator(level, turbine, 1);
        int newCapacity = small.getFluidContainer().getCapacity();
        Bridge.loadController(small, saved, level);
        int stored = turbine ? small.getFluidContainer().getGasAmount() : small.getFluidContainer().getLiquidAmount();
        details.put(prefix + "capacities and retained amount", List.of(capacity, newCapacity, stored));
        check.accept(prefix + "shrink preserves excess contents and new capacity",
                newCapacity < capacity && small.getFluidContainer().getCapacity() == newCapacity && stored == inserted);
        check.accept(prefix + "load leaves caller NBT unchanged", saved.equals(original));
        CompoundTag overfull = Bridge.saveController(small, level);
        var smallInput = small.getFluidHandler(IoDirection.Input).orElseThrow();
        check.accept(prefix + "overfull rejects fill without discarding contents",
                smallInput.fill(Bridge.fluid(fluid, 1000), Bridge.action(true)) == 0
                        && smallInput.fill(Bridge.fluid(fluid, 1000), Bridge.action(false)) == 0
                        && overfull.equals(Bridge.saveController(small, level)));
    }

    private static FluidizerFluidMixingRecipe mixingRecipe(Fluid first, Fluid second) throws Exception {
        return mixingRecipe(first, 1000, second, 1000);
    }

    private static FluidizerFluidMixingRecipe mixingRecipe(Fluid first, int firstAmount, Fluid second, int secondAmount) throws Exception {
        var ingredient1 = FluidStackRecipeIngredient.from(first, firstAmount);
        var ingredient2 = FluidStackRecipeIngredient.from(second, secondAmount);
        var result = FluidStackRecipeResult.from(Bridge.fluid(Fluids.LAVA, 1000));
        var constructor = FluidizerFluidMixingRecipe.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (FluidizerFluidMixingRecipe) (constructor.getParameterCount() == 4
                ? constructor.newInstance(Bridge.id("cerstructure", "boundary-mixing"), ingredient1, ingredient2, result)
                : constructor.newInstance(ingredient1, ingredient2, result));
    }

    private static void poweredTicks(CompactFluidizerController controller) {
        for (int tick = 0; tick < 500; ++tick) {
            controller.insertEnergy(EnergySystem.ForgeEnergy, WideAmount.from(50_000), OperationMode.Execute);
            controller.tick();
        }
    }

    private static CompactFluidizerController fluidizer(ServerLevel level) {
        var controller = new CompactFluidizerController(level, new BlockPos(10000, 100, 10000), 3, 3, 3);
        controller.simulateAssembly();
        controller.setMachineActive(true);
        return controller;
    }

    private static void coexistingFluidRecipes(ServerLevel level, BiConsumer<String, Boolean> check) throws Exception {
        var waterRecipe = mixingRecipe(Fluids.WATER, Fluids.WATER);
        var lavaRecipe = mixingRecipe(Fluids.LAVA, Fluids.LAVA);
        var mixedRecipe = mixingRecipe(Fluids.WATER, 500, Fluids.LAVA, 1500);
        var type = Bridge.fluidizerRecipeType();
        var cache = ModRecipeType.class.getDeclaredField("_cache");
        cache.setAccessible(true);
        Object previous = cache.get(type);
        try {
            cache.set(type, List.of(waterRecipe, lavaRecipe));
            check.accept("fluidizer: unrelated recipe makes lava a globally valid ingredient",
                    CompactFluidizerController.isValidFluidIngredient(Bridge.fluid(Fluids.LAVA, 1)));
            for (int occupiedTank = 0; occupiedTank < 2; ++occupiedTank) {
                var controller = fluidizer(level);
                var input = controller.getFluidHandler(IoDirection.Input).orElseThrow();
                input.fill(Bridge.fluid(Fluids.WATER, 1000), Bridge.action(false));
                if (occupiedTank == 1) {
                    CompoundTag reversed = Bridge.saveController(controller, level);
                    reversed.put("fin1", reversed.getCompound("fin0").copy());
                    reversed.remove("fin0");
                    controller = fluidizer(level);
                    Bridge.loadController(controller, reversed, level);
                    input = controller.getFluidHandler(IoDirection.Input).orElseThrow();
                }
                String prefix = "fluidizer: unrelated recipe with occupied tank " + occupiedTank + " ";
                check.accept(prefix + "starts with exactly one water input",
                        input.getFluidInTank(occupiedTank).getAmount() == 1000
                                && input.getFluidInTank(1 - occupiedTank).isEmpty());
                CompoundTag before = Bridge.saveController(controller, level);
                var lava = Bridge.fluid(Fluids.LAVA, 1000);
                check.accept(prefix + "rejects incompatible tank validity",
                        !input.isFluidValid(1 - occupiedTank, lava));
                int simulated = input.fill(lava, Bridge.action(true));
                int inserted = input.fill(lava, Bridge.action(false));
                check.accept(prefix + "rejects incompatible simulated and executed fill without mutation",
                        simulated == 0 && inserted == 0 && lava.getAmount() == 1000
                                && before.equals(Bridge.saveController(controller, level)));
                check.accept(prefix + "still accepts matching partner",
                        input.isFluidValid(1 - occupiedTank, Bridge.fluid(Fluids.WATER, 1))
                                && input.fill(Bridge.fluid(Fluids.WATER, 1000), Bridge.action(false)) == 1000);
                poweredTicks(controller);
                check.accept(prefix + "same-fluid recipe still processes exactly",
                        controller.getOutputTank().getFluidInTank(0).getAmount() == 1000
                                && input.getFluidInTank(0).isEmpty() && input.getFluidInTank(1).isEmpty());
            }

            var controller = fluidizer(level);
            var input = controller.getFluidHandler(IoDirection.Input).orElseThrow();
            input.fill(Bridge.fluid(Fluids.WATER, 500), Bridge.action(false));
            cache.set(type, List.of(waterRecipe, lavaRecipe, mixedRecipe));
            check.accept("fluidizer: added mixed recipe immediately permits compatible different fluid",
                    input.isFluidValid(1, Bridge.fluid(Fluids.LAVA, 1)));
            CompoundTag before = Bridge.saveController(controller, level);
            check.accept("fluidizer: coexisting mixed recipe simulation is exact and read only",
                    input.fill(Bridge.fluid(Fluids.LAVA, 1500), Bridge.action(true)) == 1500
                            && before.equals(Bridge.saveController(controller, level)));
            check.accept("fluidizer: coexisting mixed recipe accepts exact partner",
                    input.fill(Bridge.fluid(Fluids.LAVA, 1500), Bridge.action(false)) == 1500);
            poweredTicks(controller);
            check.accept("fluidizer: coexisting asymmetric mixed recipe consumes and produces exactly",
                    controller.getOutputTank().getFluidInTank(0).getAmount() == 1000
                            && input.getFluidInTank(0).isEmpty() && input.getFluidInTank(1).isEmpty());

            input.fill(Bridge.fluid(Fluids.WATER, 500), Bridge.action(false));
            cache.set(type, List.of(waterRecipe, lavaRecipe));
            before = Bridge.saveController(controller, level);
            check.accept("fluidizer: removed mixed recipe immediately rejects incompatible partner",
                    !input.isFluidValid(1, Bridge.fluid(Fluids.LAVA, 1))
                            && input.fill(Bridge.fluid(Fluids.LAVA, 1500), Bridge.action(true)) == 0
                            && input.fill(Bridge.fluid(Fluids.LAVA, 1500), Bridge.action(false)) == 0
                            && before.equals(Bridge.saveController(controller, level)));

            cache.set(type, List.of(waterRecipe, mixedRecipe));
            var reverseOrder = fluidizer(level);
            var reverseInput = reverseOrder.getFluidHandler(IoDirection.Input).orElseThrow();
            check.accept("fluidizer: empty tank validity follows recipe ingredient order",
                    !reverseInput.isFluidValid(0, Bridge.fluid(Fluids.LAVA, 1))
                            && reverseInput.isFluidValid(1, Bridge.fluid(Fluids.LAVA, 1)));
            before = Bridge.saveController(reverseOrder, level);
            check.accept("fluidizer: second ingredient first simulation is exact and read only",
                    reverseInput.fill(Bridge.fluid(Fluids.LAVA, 1500), Bridge.action(true)) == 1500
                            && before.equals(Bridge.saveController(reverseOrder, level)));
            check.accept("fluidizer: second ingredient first fills its compatible tank",
                    reverseInput.fill(Bridge.fluid(Fluids.LAVA, 1500), Bridge.action(false)) == 1500
                            && reverseInput.getFluidInTank(0).isEmpty()
                            && reverseInput.getFluidInTank(1).getAmount() == 1500);
            check.accept("fluidizer: first ingredient can complete reversed insertion order",
                    reverseInput.isFluidValid(0, Bridge.fluid(Fluids.WATER, 1))
                            && reverseInput.fill(Bridge.fluid(Fluids.WATER, 500), Bridge.action(false)) == 500);
            poweredTicks(reverseOrder);
            check.accept("fluidizer: reversed insertion order processes exact mixed recipe",
                    reverseOrder.getOutputTank().getFluidInTank(0).getAmount() == 1000
                            && reverseInput.getFluidInTank(0).isEmpty() && reverseInput.getFluidInTank(1).isEmpty());
        } finally {
            cache.set(type, previous);
        }
    }

    private static void sameFluidRecipe(ServerLevel level, BiConsumer<String, Boolean> check,
                                         Map<String, Object> details) throws Exception {
        var recipe = mixingRecipe(Fluids.WATER, Fluids.WATER);
        var type = Bridge.fluidizerRecipeType();
        var cache = ModRecipeType.class.getDeclaredField("_cache");
        cache.setAccessible(true);
        Object previous = cache.get(type);
        try {
            cache.set(type, List.of(recipe));
            var controller = new CompactFluidizerController(level, new BlockPos(10000, 100, 10000), 3, 3, 3);
            controller.simulateAssembly();
            controller.setMachineActive(true);
            var input = controller.getFluidHandler(IoDirection.Input).orElseThrow();
            check.accept("fluidizer: custom same-fluid recipe is valid in ER",
                    recipe.match(Bridge.fluid(Fluids.WATER, 1000), Bridge.fluid(Fluids.WATER, 1000)));
            int first = input.fill(Bridge.fluid(Fluids.WATER, 1000), Bridge.action(false));
            CompoundTag beforeSimulation = Bridge.saveController(controller, level);
            int secondSimulation = input.fill(Bridge.fluid(Fluids.WATER, 1000), Bridge.action(true));
            check.accept("fluidizer: same-fluid simulation is read only",
                    beforeSimulation.equals(Bridge.saveController(controller, level)));
            check.accept("fluidizer: same-fluid recipe accepts either ingredient tank",
                    input.isFluidValid(1, Bridge.fluid(Fluids.WATER, 1))
                            && !input.isFluidValid(1, Bridge.fluid(Fluids.LAVA, 1000)));
            int second = input.fill(Bridge.fluid(Fluids.WATER, 1000), Bridge.action(false));
            details.put("fluidizer: same-fluid input", List.of(first, secondSimulation, second));
            check.accept("fluidizer: same-fluid mixing can fill both required inputs",
                    first == 1000 && secondSimulation == 1000 && second == 1000
                            && input.getFluidInTank(0).getAmount() == 1000
                            && input.getFluidInTank(1).getAmount() == 1000);
            CompoundTag filled = Bridge.saveController(controller, level);
            check.accept("fluidizer: same-fluid simulation respects remaining capacity",
                    input.fill(Bridge.fluid(Fluids.WATER, Integer.MAX_VALUE), Bridge.action(true)) == 14000
                            && filled.equals(Bridge.saveController(controller, level)));
            poweredTicks(controller);
            details.put("fluidizer: same-fluid output and remaining inputs", List.of(
                    controller.getOutputTank().getFluidInTank(0).getAmount(),
                    input.getFluidInTank(0).getAmount(), input.getFluidInTank(1).getAmount()));
            check.accept("fluidizer: custom same-fluid recipe produces exact result",
                    controller.getOutputTank().getFluidInTank(0).getAmount() == 1000
                            && input.getFluidInTank(0).isEmpty()
                            && input.getFluidInTank(1).isEmpty());

            var reversed = new CompactFluidizerController(level, new BlockPos(10000, 100, 10000), 3, 3, 3);
            reversed.simulateAssembly();
            CompoundTag reversedTag = beforeSimulation.copy();
            reversedTag.put("fin1", reversedTag.getCompound("fin0").copy());
            reversedTag.remove("fin0");
            Bridge.loadController(reversed, reversedTag, level);
            var reversedInput = reversed.getFluidHandler(IoDirection.Input).orElseThrow();
            check.accept("fluidizer: same-fluid recipe fills empty first tank after restore",
                    reversedInput.isFluidValid(0, Bridge.fluid(Fluids.WATER, 1))
                            && reversedInput.fill(Bridge.fluid(Fluids.WATER, 1000), Bridge.action(true)) == 1000
                            && reversedInput.fill(Bridge.fluid(Fluids.WATER, 1000), Bridge.action(false)) == 1000
                            && reversedInput.getFluidInTank(0).getAmount() == 1000
                            && reversedInput.getFluidInTank(1).getAmount() == 1000);

            cache.set(type, List.of(mixingRecipe(Fluids.WATER, 500, Fluids.WATER, 1500)));
            var bulk = new CompactFluidizerController(level, new BlockPos(10000, 100, 10000), 3, 3, 3);
            bulk.simulateAssembly();
            bulk.setMachineActive(true);
            var bulkInput = bulk.getFluidHandler(IoDirection.Input).orElseThrow();
            var exactBatch = Bridge.fluid(Fluids.WATER, 2000);
            CompoundTag beforeBulk = Bridge.saveController(bulk, level);
            check.accept("fluidizer: asymmetric same-fluid bulk simulation is exact and read only",
                    bulkInput.fill(exactBatch, Bridge.action(true)) == 2000
                            && beforeBulk.equals(Bridge.saveController(bulk, level)) && exactBatch.getAmount() == 2000);
            check.accept("fluidizer: asymmetric same-fluid bulk fill distributes exact ingredients",
                    bulkInput.fill(exactBatch, Bridge.action(false)) == 2000
                            && bulkInput.getFluidInTank(0).getAmount() == 500
                            && bulkInput.getFluidInTank(1).getAmount() == 1500 && exactBatch.getAmount() == 2000);
            poweredTicks(bulk);
            check.accept("fluidizer: asymmetric same-fluid recipe produces exact result",
                    bulk.getOutputTank().getFluidInTank(0).getAmount() == 1000
                            && bulkInput.getFluidInTank(0).isEmpty() && bulkInput.getFluidInTank(1).isEmpty());
            CompoundTag beforeOverflow = Bridge.saveController(bulk, level);
            var oversized = Bridge.fluid(Fluids.WATER, Integer.MAX_VALUE);
            check.accept("fluidizer: same-fluid oversized simulation fills at most two tanks",
                    bulkInput.fill(oversized, Bridge.action(true)) == 16000
                            && beforeOverflow.equals(Bridge.saveController(bulk, level)));
            check.accept("fluidizer: same-fluid oversized fill preserves capacity and caller resource",
                    bulkInput.fill(oversized, Bridge.action(false)) == 16000
                            && bulkInput.getFluidInTank(0).getAmount() == 8000
                            && bulkInput.getFluidInTank(1).getAmount() == 8000
                            && oversized.getAmount() == Integer.MAX_VALUE
                            && bulkInput.fill(Bridge.fluid(Fluids.WATER, 1), Bridge.action(false)) == 0);

            cache.set(type, List.of(mixingRecipe(Fluids.WATER, Fluids.LAVA)));
            int toppedUp = input.fill(Bridge.fluid(Fluids.WATER, 8000), Bridge.action(false));
            CompoundTag ordinary = Bridge.saveController(controller, level);
            check.accept("fluidizer: reloaded different-fluid recipe reserves second tank",
                    toppedUp == 8000 && !input.isFluidValid(1, Bridge.fluid(Fluids.WATER, 1))
                            && input.fill(Bridge.fluid(Fluids.WATER, 1000), Bridge.action(true)) == 0
                            && input.fill(Bridge.fluid(Fluids.WATER, 1000), Bridge.action(false)) == 0
                            && ordinary.equals(Bridge.saveController(controller, level)));
            check.accept("fluidizer: reloaded recipe accepts different second input",
                    input.isFluidValid(1, Bridge.fluid(Fluids.LAVA, 1))
                            && input.fill(Bridge.fluid(Fluids.LAVA, 1000), Bridge.action(false)) == 1000);
            poweredTicks(controller);
            check.accept("fluidizer: reloaded different-fluid recipe produces exact result",
                    controller.getOutputTank().getFluidInTank(0).getAmount() == 2000
                            && input.getFluidInTank(0).getAmount() == 7000 && input.getFluidInTank(1).isEmpty());
            cache.set(type, List.of());
            check.accept("fluidizer: removed recipes immediately reject input",
                    !input.isFluidValid(0, Bridge.fluid(Fluids.WATER, 1000))
                            && input.fill(Bridge.fluid(Fluids.WATER, 1000), Bridge.action(false)) == 0);
        } finally {
            cache.set(type, previous);
        }
    }
}
