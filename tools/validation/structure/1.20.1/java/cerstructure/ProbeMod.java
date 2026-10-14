package cerstructure;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.RegisterCommandsEvent;

@Mod("cerstructure")
public final class ProbeMod {
    public ProbeMod() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(this::register);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(Bridge::onJoin);
    }
    private void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cerstructure").requires(source -> source.hasPermission(2))
                .executes(context -> {
                    String result = StructureChecks.run(context.getSource().getLevel());
                    context.getSource().sendSuccess(() -> Component.literal("CERSTRUCTURE " + result), false);
                    return 1;
                })
                .then(Commands.literal("setup").executes(context -> {
                    try {
                        String result = StructureChecks.prepare(context.getSource().getLevel());
                        context.getSource().sendSuccess(() -> Component.literal("CERSTRUCTURE " + result), false);
                    } catch (Exception error) { throw new RuntimeException(error); }
                    return 1;
                }))
                .then(Commands.literal("snapshot").executes(context -> {
                    String result = StructureChecks.snapshot(context.getSource().getLevel());
                    context.getSource().sendSuccess(() -> Component.literal("CERSTRUCTURE " + result), false);
                    return 1;
                }))
                .then(Commands.literal("runtime").executes(context -> {
                    try {
                        String result = StructureChecks.runtime();
                        context.getSource().sendSuccess(() -> Component.literal("CERSTRUCTURE " + result), false);
                    } catch (Exception error) { throw new RuntimeException(error); }
                    return 1;
                })));
    }
}
