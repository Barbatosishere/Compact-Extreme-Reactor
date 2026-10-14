package com.compact.extremereactor.common.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** 压缩机器模拟参数。 */
public final class CompactConfig {

    private CompactConfig() {
    }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue[][] COMPONENT_MINIMUM = new ForgeConfigSpec.IntValue[3][];
    public static final ForgeConfigSpec.DoubleValue[][] COMPONENT_GROWTH = new ForgeConfigSpec.DoubleValue[3][];
    public static volatile int structureRevision;
    public static final ForgeConfigSpec.BooleanValue REACTOR_AUTO_START;
    public static final ForgeConfigSpec.IntValue REACTOR_FUEL_RODS;
    public static final ForgeConfigSpec.IntValue REACTOR_CONTROL_RODS;
    public static final ForgeConfigSpec.IntValue REACTOR_POWER_TAPS;
    public static final ForgeConfigSpec.IntValue REACTOR_SIZE_X;
    public static final ForgeConfigSpec.IntValue REACTOR_SIZE_Y;
    public static final ForgeConfigSpec.IntValue REACTOR_SIZE_Z;
    public static final ForgeConfigSpec.IntValue TURBINE_COIL_RADIUS;
    public static final ForgeConfigSpec.IntValue TURBINE_SIZE_X;
    public static final ForgeConfigSpec.IntValue TURBINE_SIZE_Y;
    public static final ForgeConfigSpec.IntValue TURBINE_SIZE_Z;
    public static final ForgeConfigSpec.IntValue FLUIDIZER_SIZE_X;
    public static final ForgeConfigSpec.IntValue FLUIDIZER_SIZE_Y;
    public static final ForgeConfigSpec.IntValue FLUIDIZER_SIZE_Z;
    public static final ForgeConfigSpec.IntValue FLUIDIZER_ENERGY_CAPACITY;
    public static final ForgeConfigSpec.IntValue FLUIDIZER_ENERGY_TRANSFER;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        REACTOR_AUTO_START = builder.comment("放置后是否自动启动反应堆（false=需手动点击开关激活）")
                .define("reactor.autoStart", false);

        REACTOR_FUEL_RODS = builder.comment("模拟的燃料棒数量（燃料容量 = 数量 x 每棒容量）")
                .defineInRange("reactor.fuelRods", 16, 1, 200);
        REACTOR_CONTROL_RODS = builder.comment("模拟的控制棒数量（影响辐射与功率计算）")
                .defineInRange("reactor.controlRods", 4, 1, 100);
        REACTOR_POWER_TAPS = builder.comment("模拟的功率输出接口数量（影响能量缓冲容量）")
                .defineInRange("reactor.powerTaps", 4, 1, 100);
        REACTOR_SIZE_X = builder.comment("模拟的反应堆内部尺寸 X（影响体积与流体容量）")
                .defineInRange("reactor.sizeX", 9, 3, 32);
        REACTOR_SIZE_Y = builder.comment("模拟的反应堆内部尺寸 Y")
                .defineInRange("reactor.sizeY", 9, 3, 32);
        REACTOR_SIZE_Z = builder.comment("模拟的反应堆内部尺寸 Z")
                .defineInRange("reactor.sizeZ", 9, 3, 32);

        TURBINE_COIL_RADIUS = builder.comment("模拟的感应线圈半径（影响发电效率）")
                .defineInRange("turbine.coilRadius", 3, 1, 16);
        TURBINE_SIZE_X = builder.comment("模拟的涡轮机内部尺寸 X（影响流体容量）")
                .defineInRange("turbine.sizeX", 9, 3, 32);
        TURBINE_SIZE_Y = builder.comment("模拟的涡轮机内部尺寸 Y（转轴高度）")
                .defineInRange("turbine.sizeY", 11, 3, 32);
        TURBINE_SIZE_Z = builder.comment("模拟的涡轮机内部尺寸 Z")
                .defineInRange("turbine.sizeZ", 9, 3, 32);

        FLUIDIZER_SIZE_X = builder.comment("模拟的流化器内部尺寸 X（影响产物流体罐容量）")
                .defineInRange("fluidizer.sizeX", 9, 3, 32);
        FLUIDIZER_SIZE_Y = builder.comment("模拟的流化器内部尺寸 Y")
                .defineInRange("fluidizer.sizeY", 9, 3, 32);
        FLUIDIZER_SIZE_Z = builder.comment("模拟的流化器内部尺寸 Z")
                .defineInRange("fluidizer.sizeZ", 9, 3, 32);

        int[][] minimums = {{8, 4, 1}, {8, 2, 8, 4}, {8, 2, 2}};
        FLUIDIZER_ENERGY_CAPACITY = builder.comment("Baseline FE buffer capacity.")
                .defineInRange("fluidizer.energyCapacity", 50_000, 1_000, 1_000_000);
        FLUIDIZER_ENERGY_TRANSFER = builder.comment("Baseline FE input per tick; scales with installed structure.")
                .defineInRange("fluidizer.energyTransfer", 1_000, 1, 1_000_000);
        double[][] growth = {{3.125, 6.25, 25}, {3.125, 12.5, 3.125, 6.25}, {3.125, 12.5, 12.5}};
        for (var kind : com.compact.extremereactor.common.structure.StructureKind.values()) {
            int k = kind.ordinal();
            COMPONENT_MINIMUM[k] = new ForgeConfigSpec.IntValue[kind.componentCount()];
            COMPONENT_GROWTH[k] = new ForgeConfigSpec.DoubleValue[kind.componentCount()];
            for (int i = 0; i < kind.componentCount(); ++i) {
                String path = kind.key() + ".structure." + kind.componentKey(i);
                COMPONENT_MINIMUM[k][i] = builder.comment("Installed items required to operate at baseline.")
                        .defineInRange(path + ".minimum", minimums[k][i], 1, 256);
                COMPONENT_GROWTH[k][i] = builder.comment("Additional supported scale percent per item above minimum; weakest component limits scale. Maximum scale 1600%.")
                        .defineInRange(path + ".growthPercent", growth[k][i], 0.001, 100.0);
            }
        }
        SPEC = builder.build();
    }
}
