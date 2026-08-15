package com.compact.extremereactor.common.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 压缩机器模拟参数配置。
 *
 * 单方块模拟需要向 ER 控制器"谎报"多方块规模（部件数量、尺寸等），
 * 这些参数直接决定输出功率、燃料/蒸汽容量等数值，请在配置中调整。
 */
public final class CompactConfig {

    private CompactConfig() {
    }

    public static final ForgeConfigSpec SPEC;
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

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        // ------------------------------------------------------------------
        // 反应堆模拟参数（对应真实多方块的部件数量与内部尺寸）
        // ------------------------------------------------------------------
        REACTOR_FUEL_RODS = builder.comment("模拟的燃料棒数量（燃料容量 = 数量 x 每棒容量）")
                .defineInRange("reactor.fuelRods", 16, 1, 1000);
        REACTOR_CONTROL_RODS = builder.comment("模拟的控制棒数量（影响辐射与功率计算）")
                .defineInRange("reactor.controlRods", 4, 1, 100);
        REACTOR_POWER_TAPS = builder.comment("模拟的功率输出接口数量（影响能量缓冲容量）")
                .defineInRange("reactor.powerTaps", 4, 1, 100);
        REACTOR_SIZE_X = builder.comment("模拟的反应堆内部尺寸 X（影响体积与流体容量）")
                .defineInRange("reactor.sizeX", 9, 3, 64);
        REACTOR_SIZE_Y = builder.comment("模拟的反应堆内部尺寸 Y")
                .defineInRange("reactor.sizeY", 9, 3, 64);
        REACTOR_SIZE_Z = builder.comment("模拟的反应堆内部尺寸 Z")
                .defineInRange("reactor.sizeZ", 9, 3, 64);

        // ------------------------------------------------------------------
        // 涡轮机模拟参数（对应真实多方块的转子与线圈规模）
        // ------------------------------------------------------------------
        TURBINE_COIL_RADIUS = builder.comment("模拟的感应线圈半径（影响发电效率）")
                .defineInRange("turbine.coilRadius", 3, 1, 16);
        TURBINE_SIZE_X = builder.comment("模拟的涡轮机内部尺寸 X（影响流体容量）")
                .defineInRange("turbine.sizeX", 9, 3, 64);
        TURBINE_SIZE_Y = builder.comment("模拟的涡轮机内部尺寸 Y（转轴高度）")
                .defineInRange("turbine.sizeY", 11, 3, 64);
        TURBINE_SIZE_Z = builder.comment("模拟的涡轮机内部尺寸 Z")
                .defineInRange("turbine.sizeZ", 9, 3, 64);

        SPEC = builder.build();
    }
}
