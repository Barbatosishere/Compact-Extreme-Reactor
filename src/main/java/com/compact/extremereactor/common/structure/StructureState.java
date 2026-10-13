package com.compact.extremereactor.common.structure;

import com.compact.extremereactor.common.config.CompactConfig;

public final class StructureState {
    private static final int SCALE_UNIT = 100_000;
    public static final int MAX_SCALE = 16;
    private final int[] counts, minimums, growth;
    private final int scale, bottleneck;
    private final boolean ready;

    private StructureState(int[] counts, int[] minimums, int[] growth, int scale, int bottleneck, boolean ready) {
        this.counts = counts;
        this.minimums = minimums;
        this.growth = growth;
        this.scale = scale;
        this.bottleneck = bottleneck;
        this.ready = ready;
    }

    public static StructureState read(StructureKind kind, StructureInventory inventory) {
        int n = kind.componentCount();
        int[] counts = new int[n], minimums = new int[n], growth = new int[n];
        for (int i = 0; i < n; ++i) {
            counts[i] = inventory.countComponent(i);
            minimums[i] = CompactConfig.COMPONENT_MINIMUM[kind.ordinal()][i].get();
            growth[i] = (int) Math.round(CompactConfig.COMPONENT_GROWTH[kind.ordinal()][i].get() * 1000);
        }
        return calculate(kind, counts, minimums, growth);
    }

    public static StructureState calculate(int[] counts, int[] minimums, int[] growth) {
        return calculate(null, counts, minimums, growth);
    }

    public static StructureState calculate(StructureKind kind, int[] counts, int[] minimums, int[] growth) {
        int scale = MAX_SCALE * SCALE_UNIT, bottleneck = 0;
        boolean ready = true;
        double shortage = 1;
        int[] supports = new int[counts.length];
        for (int i = 0; i < counts.length; ++i) {
            if (counts[i] < minimums[i]) {
                ready = false;
                double ratio = (double) counts[i] / minimums[i];
                if (ratio < shortage) { shortage = ratio; bottleneck = i; }
            } else {
                supports[i] = supportScale(counts[i], minimums[i], growth[i]);
            }
        }
        if (ready) {
            for (int i = 0; i < supports.length; ++i) {
                if (supports[i] < scale) { scale = supports[i]; bottleneck = i; }
            }
            if (kind == StructureKind.REACTOR && supports.length >= 3) {
                int fuel = counts[1];
                int shellFuel = linkedCount(counts[0], minimums[0], minimums[1]);
                int rodFuel = linkedCount(counts[2], minimums[2], minimums[1]);
                int linkedFuel = Math.min(fuel, Math.min(shellFuel, rodFuel));
                int linkedScale = supportScale(linkedFuel, minimums[1], growth[1]);
                if (linkedScale < scale) {
                    scale = linkedScale;
                    bottleneck = shellFuel <= rodFuel ? 0 : 2;
                }
            }
        }
        return new StructureState(counts.clone(), minimums.clone(), growth.clone(), ready ? scale : 0, bottleneck, ready);
    }

    private static int supportScale(int count, int minimum, int growth) {
        return (int) Math.min(MAX_SCALE * (long) SCALE_UNIT,
                SCALE_UNIT + (long) Math.max(0, count - minimum) * growth);
    }

    private static int linkedCount(int sourceCount, int sourceMinimum, int targetMinimum) {
        long extra = Math.max(0L, (long) sourceCount - sourceMinimum) * targetMinimum / sourceMinimum;
        return (int) Math.min(Integer.MAX_VALUE, (long) targetMinimum + extra);
    }

    public int count(int component) { return this.counts[component]; }
    public int minimum(int component) { return this.minimums[component]; }
    public int growth(int component) { return this.growth[component]; }
    public int scaleUnits() { return this.scale; }
    public double simulationScale() { return Math.max(1, this.scale / (double) SCALE_UNIT); }
    public int bottleneck() { return this.bottleneck; }
    public boolean ready() { return this.ready; }

    public int scaled(int baseline) {
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE, Math.ceil(baseline * this.simulationScale())));
    }

    public int height(int baseline) { return this.scaled(baseline - 2) + 2; }
}
