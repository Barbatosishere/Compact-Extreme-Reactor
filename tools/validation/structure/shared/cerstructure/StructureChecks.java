package cerstructure;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.menu.CompactStructureMenu;
import com.compact.extremereactor.common.multiblock.*;
import com.compact.extremereactor.common.structure.*;
import com.compact.extremereactor.common.tile.*;
import com.google.gson.Gson;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import it.zerono.mods.extremereactors.api.reactor.ReactantMappingsRegistry;
import it.zerono.mods.zerocore.lib.data.WideAmount;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;

import java.util.*;

public final class StructureChecks {
    private static final Map<String, Boolean> checks = new LinkedHashMap<>();
    private static final Map<String, Object> details = new LinkedHashMap<>();

    private static void check(String name, boolean passed) { checks.put(name, passed); }

    public static String run(ServerLevel level) {
        checks.clear();
        details.clear();
        Bridge.dropped = 0;
        String error = "";
        try {
            check("empty components stop", !StructureState.calculate(new int[]{0, 0, 0}, new int[]{8, 4, 1}, new int[]{3125, 6250, 25000}).ready());
            check("baseline", StructureState.calculate(new int[]{8, 4, 1}, new int[]{8, 4, 1}, new int[]{3125, 6250, 25000}).scaleUnits() == 100000);
            check("weakest component", StructureState.calculate(new int[]{40, 20, 1}, new int[]{8, 4, 1}, new int[]{3125, 6250, 25000}).scaleUnits() == 100000);
            check("balanced double scale", StructureState.calculate(new int[]{40, 20, 5}, new int[]{8, 4, 1}, new int[]{3125, 6250, 25000}).scaleUnits() == 200000);
            check("reactor shell limits fuel", StructureState.calculate(StructureKind.REACTOR,
                    new int[]{16, 20, 5}, new int[]{8, 4, 1}, new int[]{10000, 6250, 25000}).scaleUnits() == 125000);
            check("reactor control rods limit fuel", StructureState.calculate(StructureKind.REACTOR,
                    new int[]{40, 20, 2}, new int[]{8, 4, 1}, new int[]{3125, 6250, 100000}).scaleUnits() == 125000);
            check("reactor balanced coupling", StructureState.calculate(StructureKind.REACTOR,
                    new int[]{40, 20, 5}, new int[]{8, 4, 1}, new int[]{3125, 6250, 25000}).scaleUnits() == 200000);
            check("bounded scale", StructureState.calculate(new int[]{256}, new int[]{1}, new int[]{100000}).scaleUnits() == 1600000);
            generatorFluidCapacities(level);
            for (StructureKind kind : StructureKind.values()) {
                machine(level, kind);
                structureDrops(level, kind);
            }
            energyTransfers(level);
            BoundaryChecks.run(level, StructureChecks::check, details);
        } catch (Throwable failure) {
            error = failure.toString();
            failure.printStackTrace();
        }
        return new Gson().toJson(Map.of("checks", checks, "details", details, "error", error,
                "passed", error.isEmpty() && checks.values().stream().allMatch(Boolean::booleanValue)));
    }

    private static void initialize(AbstractCompactMachineTileEntity tile) throws Exception {
        var method = AbstractCompactMachineTileEntity.class.getDeclaredMethod("initController");
        method.setAccessible(true);
        method.invoke(tile);
    }

    private static long stored(ICompactController controller) {
        return controller.getEnergyStored(EnergySystem.ForgeEnergy).longValue();
    }

    private static void setEnergy(ICompactController controller, long amount, ServerLevel level) {
        Bridge.setEnergy(controller, amount, level);
        if (stored(controller) != amount) throw new IllegalStateException("Cannot prepare energy=" + amount);
    }

    private static void energyTransfers(ServerLevel level) throws Exception {
        BlockPos pos = new BlockPos(10005, 110, 10005);
        var push = AbstractCompactMachineTileEntity.class.getDeclaredMethod("pushPower", ICompactController.class);
        push.setAccessible(true);
        Direction[] directions = Direction.values();
        var capacity = com.compact.extremereactor.common.config.CompactConfig.FLUIDIZER_ENERGY_CAPACITY;
        var transfer = com.compact.extremereactor.common.config.CompactConfig.FLUIDIZER_ENERGY_TRANSFER;
        int oldCapacity = capacity.get(), oldTransfer = transfer.get();
        capacity.set(1_000_000);
        transfer.set(1_000_000);
        try {
            for (StructureKind kind : List.of(StructureKind.REACTOR, StructureKind.TURBINE)) {
                var block = kind == StructureKind.REACTOR ? Content.COMPACT_REACTOR.get() : Content.COMPACT_TURBINE.get();
                level.setBlockAndUpdate(pos, block.defaultBlockState());
                var source = (AbstractCompactMachineTileEntity) level.getBlockEntity(pos);
                install(source, 1);
                initialize(source);
                source.getController().setMachineActive(false);
                ICompactController[] sinks = new ICompactController[directions.length];
                for (int i = 0; i < directions.length; ++i) {
                    BlockPos neighbor = pos.relative(directions[i]);
                    level.setBlockAndUpdate(neighbor, Content.COMPACT_FLUIDIZER.get().defaultBlockState());
                    var tile = (AbstractCompactMachineTileEntity) level.getBlockEntity(neighbor);
                    install(tile, 2);
                    initialize(tile);
                    sinks[i] = tile.getController();
                    sinks[i].setMachineActive(false);
                }
                for (long energy : new long[]{0, 500_000, 2_500_000, 7_000_000}) {
                    setEnergy(source.getController(), energy, level);
                    for (var sink : sinks) setEnergy(sink, 0, level);
                    long sideLimit = source.getController().extractEnergy(EnergySystem.ForgeEnergy,
                            WideAmount.from(1_000_000), OperationMode.Simulate).longValue();
                    push.invoke(source, source.getController());
                    long remaining = energy, received = 0;
                    boolean perSide = true;
                    for (var sink : sinks) {
                        long expected = Math.min(sideLimit, remaining);
                        perSide &= stored(sink) == expected;
                        remaining -= expected;
                        received += stored(sink);
                    }
                    check(kind.key() + ": six-side FE throughput energy=" + energy, perSide && stored(source.getController()) == remaining);
                    check(kind.key() + ": six-side FE conservation energy=" + energy, received + stored(source.getController()) == energy);
                    details.put(kind.key() + ": FE transfer energy=" + energy,
                            Map.of("sideLimit", sideLimit, "received", received, "remaining", stored(source.getController())));
                }
                for (var sink : sinks) setEnergy(sink, sink.getCapacity(EnergySystem.ForgeEnergy).longValue(), level);
                setEnergy(sinks[0], stored(sinks[0]) - 137, level);
                setEnergy(source.getController(), 7_000_000, level);
                long before = Arrays.stream(sinks).mapToLong(StructureChecks::stored).sum();
                push.invoke(source, source.getController());
                long received = Arrays.stream(sinks).mapToLong(StructureChecks::stored).sum() - before;
                check(kind.key() + ": full receivers and partial acceptance", received == 137 && stored(source.getController()) == 7_000_000 - 137);
            }
        } finally {
            capacity.set(oldCapacity);
            transfer.set(oldTransfer);
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            for (var direction : directions) level.setBlockAndUpdate(pos.relative(direction), Blocks.AIR.defaultBlockState());
        }
    }

    public static ItemStack item(String namespace, String path, int count) {
        return new ItemStack(BuiltInRegistries.ITEM.get(Bridge.id(namespace, path)), count);
    }

    private static void structureDrops(ServerLevel level, StructureKind kind) throws Exception {
        BlockPos pos = new BlockPos(10000 + kind.ordinal(), 100, 10004);
        var block = switch (kind) {
            case REACTOR -> Content.COMPACT_REACTOR.get();
            case TURBINE -> Content.COMPACT_TURBINE.get();
            case FLUIDIZER -> Content.COMPACT_FLUIDIZER.get();
        };
        for (String scenario : List.of("mixed", "full", "covered", "empty")) {
            for (var entity : Bridge.joinedDrops) entity.discard();
            Bridge.joinedDrops.clear();
            level.setBlockAndUpdate(pos.above(), scenario.equals("covered") ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(pos, block.defaultBlockState());
            var tile = (AbstractCompactMachineTileEntity) level.getBlockEntity(pos);
            initialize(tile);
            var inventory = tile.getStructureInventory();
            for (int component = 0; component < kind.componentCount(); ++component) {
                String key = kind.componentKey(component);
                String namespace = key.equals("gold_block") ? "minecraft" : "bigreactors";
                String path = key.startsWith("reactor") || key.startsWith("turbine") ? "basic_" + key : key;
                for (int slot = 0; slot < 4; ++slot) {
                    int count = switch (scenario) {
                        case "full", "covered" -> 64;
                        case "mixed" -> component == 0 ? new int[]{20, 35, 9, 7}[slot]
                                : component == 1 ? new int[]{4, 5, 6, 12}[slot] : 64;
                        default -> 0;
                    };
                    var stack = item(namespace, path, count);
                    if (scenario.equals("mixed") && (component == 0 && slot == 3 || component == 1 && slot < 3)) {
                        Bridge.annotate(stack, component == 1 && slot == 2 ? "other" : "same");
                    }
                    inventory.setItem(component * 4 + slot, stack);
                }
            }
            if (scenario.equals("mixed")) {
                inventory.setItem(12, item("minecraft", "iron_sword", 1));
                var damaged = item("minecraft", "iron_sword", 1);
                damaged.setDamageValue(42);
                inventory.setItem(13, damaged);
                inventory.setItem(14, item("minecraft", "snowball", 16));
                inventory.setItem(15, item("minecraft", "snowball", 1));
            }
            var expected = new ArrayList<ItemStack>();
            for (int slot = 0; slot < inventory.getContainerSize(); ++slot) {
                if (!inventory.getItem(slot).isEmpty()) expected.add(inventory.getItem(slot).copy());
            }
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            var entities = new ArrayList<>(Bridge.joinedDrops);
            var actual = entities.stream().map(ItemEntity::getItem).toList();
            String prefix = kind.key() + ": " + scenario + " structure drops ";
            boolean exact = expected.stream().mapToInt(ItemStack::getCount).sum() == actual.stream().mapToInt(ItemStack::getCount).sum();
            for (ItemStack prototype : expected) {
                exact &= expected.stream().filter(stack -> Bridge.sameStack(stack, prototype)).mapToInt(ItemStack::getCount).sum()
                        == actual.stream().filter(stack -> Bridge.sameStack(stack, prototype)).mapToInt(ItemStack::getCount).sum();
            }
            check(prefix + "exact items and data", exact);
            check(prefix + "valid stack limits", actual.stream().allMatch(stack -> !stack.isEmpty() && stack.getCount() <= stack.getMaxStackSize()));
            int expectedEntities = scenario.equals("mixed") ? 13 : scenario.equals("empty") ? 0 : kind.componentCount() * 4;
            check(prefix + "minimum entity count", entities.size() == expectedEntities);
            double y = pos.getY() + (scenario.equals("covered") ? 0.5 : 1.01);
            check(prefix + "compact placement and motion", entities.stream().allMatch(entity ->
                    entity.getX() == pos.getX() + 0.5 && entity.getZ() == pos.getZ() + 0.5 && entity.getY() == y
                            && entity.getDeltaMovement().x == 0 && entity.getDeltaMovement().z == 0));
            check(prefix + "inventory drained", inventory.isEmpty());
            tile.onBlockRemoved();
            check(prefix + "repeated removal creates no entities", Bridge.joinedDrops.size() == entities.size());
            details.put(prefix + "counts", Map.of("items", actual.stream().mapToInt(ItemStack::getCount).sum(), "entities", entities.size()));
        }
        level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
    }

    public static void install(AbstractCompactMachineTileEntity tile, double scale) {
        var state = tile.getStructureState();
        var inventory = tile.getStructureInventory();
        for (int component = 0; component < tile.getStructureKind().componentCount(); ++component) {
            int target = state.minimum(component) + (int) Math.ceil((scale - 1) * 100000 / state.growth(component));
            String name = tile.getStructureKind().componentKey(component);
            String namespace = name.equals("gold_block") ? "minecraft" : "bigreactors";
            String path = name.startsWith("reactor") || name.startsWith("turbine") ? "basic_" + name : name;
            for (int slot = 0; slot < 4; ++slot) {
                int count = Math.max(0, Math.min(64, target - slot * 64));
                inventory.setItem(component * 4 + slot, count == 0 ? ItemStack.EMPTY : item(namespace, path, count));
            }
        }
    }

    private static ICompactController generator(ServerLevel level, StructureKind kind, int size, double scale) {
        BlockPos anchor = new BlockPos(10000, 100, 10000);
        ICompactController controller = kind == StructureKind.REACTOR
                ? new CompactReactorController(level, anchor, 1, 1, 1, size, size, size, scale)
                : new CompactTurbineController(level, anchor, 1, size, size, size, scale);
        controller.simulateAssembly();
        return controller;
    }

    private static int generatorFluidLimit(ICompactController controller) {
        return controller instanceof CompactReactorController reactor
                ? reactor.getVariant().getMaxFluidCapacity()
                : ((CompactTurbineController) controller).getVariant().getMaxFluidCapacity();
    }

    private static void generatorFluidCapacities(ServerLevel level) {
        for (StructureKind kind : List.of(StructureKind.REACTOR, StructureKind.TURBINE)) {
            String prefix = kind.key() + ": ";
            var small = generator(level, kind, 3, 1);
            int partCapacity = small instanceof CompactReactorController reactor
                    ? reactor.getVariant().getPartFluidCapacity()
                    : ((CompactTurbineController) small).getVariant().getPartFluidCapacity();
            int limit = generatorFluidLimit(small);
            int initial = small.getFluidContainer().getCapacity();
            int expanded = generator(level, kind, 3, 2).getFluidContainer().getCapacity();
            // A 3x3x3 shell has 26 parts; extending it to 3x4x3 adds eight, not another end cap.
            check(prefix + "small structure fluid capacity", initial == Math.min(26L * partCapacity, limit));
            check(prefix + "fluid capacity grows below variant limit",
                    expanded > initial && expanded == Math.min(34L * partCapacity, limit));
            check(prefix + "fractional fluid expansion rounds height up",
                    generator(level, kind, 3, 1.25).getFluidContainer().getCapacity() == expanded);
            // At 1600%, a 9x9x9 shell becomes 9x114x9: 3746 exterior parts.
            check(prefix + "large structure fluid capacity respects variant limit",
                    generator(level, kind, 9, 16).getFluidContainer().getCapacity()
                            == Math.min(3746L * partCapacity, limit));
        }
    }

    private static void machine(ServerLevel level, StructureKind kind) throws Exception {
        String prefix = kind.key() + ": ";
        BlockPos pos = new BlockPos(10000 + kind.ordinal(), 100, 10000);
        var block = switch (kind) {
            case REACTOR -> Content.COMPACT_REACTOR.get();
            case TURBINE -> Content.COMPACT_TURBINE.get();
            case FLUIDIZER -> Content.COMPACT_FLUIDIZER.get();
        };
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        var tile = (AbstractCompactMachineTileEntity) level.getBlockEntity(pos);
        initialize(tile);
        ICompactController baseline = tile.getController();
        baseline.setMachineActive(true);
        CompoundTag idle = Bridge.controllerSave(tile);
        tile.serverTick();
        check(prefix + "incomplete gate preserves state", idle.equals(Bridge.controllerSave(tile)));
        install(tile, 1);
        baseline.setMachineActive(false);
        tile.serverTick();
        check(prefix + "minimum reached without rebuild", tile.getController() == baseline && tile.getStructureState().ready());
        long energyCapacity = baseline.getCapacity(EnergySystem.ForgeEnergy).longValue();
        int fluidCapacity = kind == StructureKind.FLUIDIZER
                ? ((CompactFluidizerController) baseline).getOutputTank().getCapacity()
                : baseline.getFluidContainer().getCapacity();
        install(tile, 2);
        var oldEnergy = tile.getEnergyStorage(null);
        var oldFluid = tile.getFluidHandler(null);
        tile.serverTick();
        initialize(tile);
        var upgraded = tile.getController();
        check(prefix + "scale rebuild", upgraded != baseline && tile.getStructureState().scaleUnits() == 200000);
        check(prefix + "old energy reference inert", oldEnergy.getEnergyStored() == 0 && oldEnergy.extractEnergy(100, false) == 0);
        check(prefix + "old fluid reference inert", oldFluid.getTanks() == 0 || oldFluid.getFluidInTank(0).isEmpty());
        int newFluidCapacity = kind == StructureKind.FLUIDIZER
                ? ((CompactFluidizerController) upgraded).getOutputTank().getCapacity()
                : upgraded.getFluidContainer().getCapacity();
        if (kind == StructureKind.FLUIDIZER) {
            check(prefix + "fluid capacity increased", newFluidCapacity >= fluidCapacity * 2);
        } else {
            check(prefix + "fluid capacity grows within variant limit",
                    newFluidCapacity >= fluidCapacity && newFluidCapacity <= generatorFluidLimit(upgraded));
        }
        details.put(prefix + "capacities", List.of(fluidCapacity, newFluidCapacity));
        check(prefix + "energy capacity", upgraded.getCapacity(EnergySystem.ForgeEnergy).longValue() > energyCapacity);
        long request = kind == StructureKind.FLUIDIZER ? 40000 : energyCapacity + 1000;
        for (int i = 0; i < 50; ++i) upgraded.insertEnergy(EnergySystem.ForgeEnergy, WideAmount.from(request), OperationMode.Execute);
        if (upgraded instanceof CompactReactorController reactor) {
            var mapping = ReactantMappingsRegistry.getFromSolid(item("bigreactors", "yellorium_ingot", 1)).orElseThrow();
            reactor.insertFuel(mapping.getProduct(), ((CompactReactorController) baseline).getFuelCapacity() + 1200, OperationMode.Execute);
            reactor.setControlRodInsertionRatio(0);
            reactor.setMachineActive(true);
            for (int i = 0; i < 120; ++i) reactor.tick();
            reactor.setMachineActive(false);
            reactor.setControlRodInsertionRatio(65);
            Bridge.fill(tile, false, fluidCapacity + 1000);
        } else if (upgraded instanceof CompactTurbineController) {
            Bridge.fill(tile, true, fluidCapacity + 1000);
        } else if (upgraded instanceof CompactFluidizerController fluidizer) {
            fluidizer.getItemInputs().setStackInSlot(0, item("bigreactors", "yellorium_ingot", 64));
            long beforeProcessing = fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).longValue();
            fluidizer.setMachineActive(true);
            for (int i = 0; i < 4; ++i) fluidizer.tick();
            check(prefix + "upgraded recipe consumes FE", fluidizer.getEnergyStored(EnergySystem.ForgeEnergy).longValue() < beforeProcessing);
            check(prefix + "nonzero recipe progress", fluidizer.getCurrentTick() > 0);
            fluidizer.setMachineActive(false);
        }
        CompoundTag before = Bridge.controllerSave(tile);
        long energy = upgraded.getEnergyStored(EnergySystem.ForgeEnergy).longValue();
        int fuel = upgraded.getFuelAmount(), waste = upgraded.getWasteAmount();
        install(tile, 1);
        tile.serverTick();
        initialize(tile);
        var downgraded = tile.getController();
        check(prefix + "shrink keeps energy", downgraded.getEnergyStored(EnergySystem.ForgeEnergy).longValue() == energy);
        check(prefix + "shrink keeps fuel and waste", downgraded.getFuelAmount() == fuel && downgraded.getWasteAmount() == waste);
        CompoundTag after = Bridge.controllerSave(tile);
        before.remove("buffer"); after.remove("buffer");
        if (kind == StructureKind.FLUIDIZER) {
            before.getCompound("out").remove("capacity");
            after.getCompound("out").remove("capacity");
            before.getCompound("energy").remove("capacity");
            after.getCompound("energy").remove("capacity");
            before.getCompound("energy").remove("maxInsert");
            after.getCompound("energy").remove("maxInsert");
            before.getCompound("energy").remove("maxExtract");
            after.getCompound("energy").remove("maxExtract");
        }
        check(prefix + "shrink keeps fluids heat settings progress", before.equals(after));
        if (!before.equals(after)) details.put(prefix + "restore difference", List.of(before.toString(), after.toString()));
        check(prefix + "normal capability reattached", Bridge.energyAvailable(tile));
        CompoundTag saved = Bridge.save(tile);
        AbstractCompactMachineTileEntity copy = switch (kind) {
            case REACTOR -> new CompactReactorTileEntity(pos, block.defaultBlockState());
            case TURBINE -> new CompactTurbineTileEntity(pos, block.defaultBlockState());
            case FLUIDIZER -> new CompactFluidizerTileEntity(pos, block.defaultBlockState());
        };
        copy.setLevel(level);
        Bridge.load(copy, saved);
        initialize(copy);
        check(prefix + "save load retains components", copy.getStructureState().ready() && copy.getStructureState().count(0) == tile.getStructureState().count(0));
        check(prefix + "save load retains controller", Bridge.controllerSave(copy).equals(Bridge.controllerSave(tile)));
        copy.onChunkUnloaded();
        CompoundTag beforeReload = Bridge.controllerSave(tile);
        ++com.compact.extremereactor.common.config.CompactConfig.structureRevision;
        tile.serverTick();
        initialize(tile);
        check(prefix + "config reload rebuilds and preserves state", tile.getController() != downgraded
                && beforeReload.equals(Bridge.controllerSave(tile)));
        tile.getStructureInventory().setItem(4, ItemStack.EMPTY);
        tile.getController().setMachineActive(true);
        CompoundTag beforeMissing = Bridge.controllerSave(tile);
        tile.serverTick();
        check(prefix + "removing required component stops", !tile.getStructureState().ready()
                && beforeMissing.equals(Bridge.controllerSave(tile)));
        tile.getController().setMachineActive(false);
        install(tile, 1);
        menu(level, tile, prefix);
    }

    public static String prepare(ServerLevel level) throws Exception {
        for (var kind : StructureKind.values()) {
            var block = switch (kind) {
                case REACTOR -> Content.COMPACT_REACTOR.get();
                case TURBINE -> Content.COMPACT_TURBINE.get();
                case FLUIDIZER -> Content.COMPACT_FLUIDIZER.get();
            };
            var pos = new BlockPos(10000 + kind.ordinal(), 100, 10000);
            level.setBlockAndUpdate(pos, block.defaultBlockState());
            var tile = (AbstractCompactMachineTileEntity) level.getBlockEntity(pos);
            install(tile, 2);
            initialize(tile);
            var controller = tile.getController();
            controller.setMachineActive(false);
            controller.insertEnergy(EnergySystem.ForgeEnergy, WideAmount.from(1000), OperationMode.Execute);
            if (controller instanceof CompactReactorController reactor) {
                var mapping = ReactantMappingsRegistry.getFromSolid(item("bigreactors", "yellorium_ingot", 1)).orElseThrow();
                reactor.insertFuel(mapping.getProduct(), 18000, OperationMode.Execute);
                reactor.setControlRodInsertionRatio(65);
                Bridge.fill(tile, false, 30000);
            } else if (controller instanceof CompactTurbineController) {
                Bridge.fill(tile, true, 15000);
            } else if (controller instanceof CompactFluidizerController fluidizer) {
                fluidizer.getItemInputs().setStackInSlot(0, item("bigreactors", "yellorium_ingot", 16));
            }
            tile.setChanged();
        }
        return snapshot(level);
    }

    public static String snapshot(ServerLevel level) {
        var result = new LinkedHashMap<String, Object>();
        for (var kind : StructureKind.values()) {
            var tile = (AbstractCompactMachineTileEntity) level.getBlockEntity(new BlockPos(10000 + kind.ordinal(), 100, 10000));
            if (tile == null || !tile.isControllerReady()) { result.put(kind.key(), "pending"); continue; }
            var counts = new ArrayList<Integer>();
            for (int i = 0; i < kind.componentCount(); ++i) counts.add(tile.getStructureState().count(i));
            result.put(kind.key(), Map.of("counts", counts, "scale", tile.getStructureState().scaleUnits(),
                    "controller", Bridge.controllerSave(tile).toString()));
        }
        return new Gson().toJson(result);
    }

    public static String runtime() throws Exception {
        var result = new LinkedHashMap<String, Integer>();
        for (String name : List.of("TICKING_MACHINES", "PENDING_INIT")) {
            var field = AbstractCompactMachineTileEntity.class.getDeclaredField(name);
            field.setAccessible(true);
            result.put(name, ((Set<?>) field.get(null)).size());
        }
        return new Gson().toJson(result);
    }

    private static int total(Player player, AbstractCompactMachineTileEntity tile, ServerLevel level) {
        int count = player.containerMenu.getCarried().getCount();
        for (int i = 0; i < player.getInventory().getContainerSize(); ++i) count += player.getInventory().getItem(i).getCount();
        for (int i = 0; i < 16; ++i) count += tile.getStructureInventory().getItem(i).getCount();
        count += Bridge.dropped;
        return count;
    }

    private static void menu(ServerLevel level, AbstractCompactMachineTileEntity tile, String prefix) {
        Player player = Bridge.player(level);
        player.getInventory().clearContent();
        player.setPos(tile.getBlockPos().getX() + .5, 100, 10000.5);
        var menu = new CompactStructureMenu(17, player.getInventory(), tile);
        player.containerMenu = menu;
        check(prefix + "menu has stable vanilla slots", menu.slots.size() == 52);
        String shell = tile.getStructureKind().componentKey(0);
        if (!shell.startsWith("fluidizer")) shell = "basic_" + shell;
        player.getInventory().setItem(0, item("bigreactors", shell, 64));
        int total = total(player, tile, level);
        menu.quickMoveStack(player, 43);
        check(prefix + "shift insert conserves", total(player, tile, level) == total && player.getInventory().getItem(0).isEmpty());
        menu.quickMoveStack(player, 0);
        check(prefix + "shift extract conserves", total(player, tile, level) == total);
        player.getInventory().setItem(0, item("minecraft", "stone", 1));
        int invalidTotal = total(player, tile, level);
        menu.clicked(4, 0, ClickType.SWAP, player);
        check(prefix + "number key filter", total(player, tile, level) == invalidTotal && player.getInventory().getItem(0).getItem() == Blocks.STONE.asItem());
        player.getInventory().setItem(0, ItemStack.EMPTY);
        menu.setCarried(item("bigreactors", shell, 32));
        total = total(player, tile, level);
        menu.clicked(-999, 0, ClickType.QUICK_CRAFT, player);
        for (int i = 0; i < 4; ++i) menu.clicked(i, 1, ClickType.QUICK_CRAFT, player);
        menu.clicked(-999, 2, ClickType.QUICK_CRAFT, player);
        check(prefix + "drag conserves", total(player, tile, level) == total);
        menu.setCarried(item("bigreactors", shell, 1));
        total = total(player, tile, level);
        menu.clicked(16, 0, ClickType.PICKUP_ALL, player);
        check(prefix + "double click conserves", total(player, tile, level) == total && menu.getCarried().getCount() > 1);
        player.getInventory().add(menu.getCarried());
        menu.setCarried(ItemStack.EMPTY);
        int occupied = 0;
        while (occupied < 4 && !menu.slots.get(occupied).hasItem()) ++occupied;
        menu.clicked(occupied, 1, ClickType.PICKUP, player);
        check(prefix + "right click conserves", total(player, tile, level) == total && !menu.getCarried().isEmpty());
        player.getInventory().add(menu.getCarried());
        menu.setCarried(ItemStack.EMPTY);
        occupied = 0;
        while (occupied < 4 && !menu.slots.get(occupied).hasItem()) ++occupied;
        menu.clicked(occupied, 1, ClickType.THROW, player);
        details.put(prefix + "throw", List.of(total, total(player, tile, level), player.getClass().getName()));
        check(prefix + "throw conserves including drops", total(player, tile, level) == total);
        check(prefix + "near menu valid", menu.stillValid(player));
        player.setPos(0, 100, 0);
        check(prefix + "distant navigation rejected", !menu.stillValid(player) && !menu.clickMenuButton(player, 0));
        player.setPos(tile.getBlockPos().getX() + .5, 100, 10000.5);
        total = total(player, tile, level);
        level.setBlockAndUpdate(tile.getBlockPos(), Blocks.AIR.defaultBlockState());
        tile.onBlockRemoved();
        details.put(prefix + "drop", List.of(total, total(player, tile, level)));
        check(prefix + "removal drops once", total(player, tile, level) == total && tile.getStructureInventory().isEmpty());
        check(prefix + "removed menu invalid", !menu.stillValid(player));
        menu.setCarried(ItemStack.EMPTY);
    }
}
