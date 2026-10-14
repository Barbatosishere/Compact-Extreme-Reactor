package com.compact.extremereactor.common.structure;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

public enum StructureKind {
    REACTOR("reactor", "reactorcasing", "reactorfuelrod", "reactorcontrolrod"),
    TURBINE("turbine", "turbinecasing", "turbinerotorshaft", "turbinerotorblade", "gold_block"),
    FLUIDIZER("fluidizer", "fluidizercasing", "fluidizersolidinjector", "fluidizerfluidinjector");

    private final String key;
    private final String[] components;

    StructureKind(String key, String... components) {
        this.key = key;
        this.components = components;
    }

    public String key() { return this.key; }
    public int componentCount() { return this.components.length; }
    public String componentKey(int component) { return this.components[component]; }

    public boolean accepts(int component, ItemStack stack) {
        if (component < 0 || component >= this.componentCount() || stack.isEmpty()) return false;
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String name = this.components[component];
        if (name.equals("gold_block")) {
            return id.getNamespace().equals("minecraft") && id.getPath().equals(name);
        }
        return id.getNamespace().equals("bigreactors") && (id.getPath().equals(name)
                || id.getPath().equals("basic_" + name) || id.getPath().equals("reinforced_" + name));
    }
}
