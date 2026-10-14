package cerstructure;

import com.compact.extremereactor.common.menu.CompactStructureMenu;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.nio.file.*;
import java.util.*;

@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = "cerstructure", value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class ClientProbe {
    private static final Path OUT = Path.of(System.getProperty("cer.structure.output", "."));
    private static final Gson GSON = new Gson();
    private static int lastId = -1;
    private static long lastStatus;

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void tick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        try {
            Files.createDirectories(OUT);
            Path command = OUT.resolve("command.json");
            if (Files.exists(command)) {
                JsonObject json = JsonParser.parseString(Files.readString(command)).getAsJsonObject();
                int id = json.get("id").getAsInt();
                if (id != lastId) {
                    lastId = id;
                    var reply = new LinkedHashMap<String, Object>();
                    reply.put("id", id);
                    try { execute(json); reply.put("success", true); }
                    catch (Exception error) { reply.put("success", false); reply.put("error", error.toString()); }
                    reply.put("status", status());
                    write("reply.json", reply);
                }
            }
            if (System.nanoTime() - lastStatus > 200_000_000L) {
                lastStatus = System.nanoTime();
                write("status.json", status());
            }
        } catch (Exception error) {
            write("error.json", Map.of("error", error.toString()));
        }
    }

    private static void write(String name, Object value) {
        try { Files.writeString(OUT.resolve(name), GSON.toJson(value)); }
        catch (Exception error) { error.printStackTrace(); }
    }

    private static Map<String, Object> status() {
        var mc = Minecraft.getInstance();
        var result = new LinkedHashMap<String, Object>();
        result.put("connected", mc.player != null);
        result.put("screen", mc.screen == null ? "" : mc.screen.getClass().getSimpleName());
        result.put("width", mc.getWindow().getGuiScaledWidth());
        result.put("height", mc.getWindow().getGuiScaledHeight());
        if (mc.player != null) {
            result.put("slots", mc.player.containerMenu.slots.size());
            result.put("carried", mc.player.containerMenu.getCarried().getCount());
            if (mc.player.containerMenu instanceof CompactStructureMenu menu) {
                result.put("kind", menu.getKind().key());
                result.put("scale", menu.getData(2));
                result.put("counts", java.util.stream.IntStream.range(0, menu.getKind().componentCount()).map(menu::count).toArray());
                result.put("contents", menu.slots.stream().map(slot -> slot.getItem().getCount()).toList());
            }
        }
        return result;
    }

    private static void execute(JsonObject json) {
        var mc = Minecraft.getInstance();
        switch (json.get("action").getAsString()) {
            case "open" -> {
                BlockPos pos = new BlockPos(json.get("x").getAsInt(), 100, 10000);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
            }
            case "page" -> mc.gameMode.handleInventoryButtonClick(mc.player.containerMenu.containerId, json.get("button").getAsInt());
            case "click" -> mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, json.get("slot").getAsInt(),
                    json.get("button").getAsInt(), ClickType.valueOf(json.get("type").getAsString()), mc.player);
            case "close" -> mc.player.closeContainer();
            case "clear_toasts" -> mc.getToasts().clear();
            case "resize" -> GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), json.get("width").getAsInt(), json.get("height").getAsInt());
            case "screenshot" -> {
                mc.getToasts().clear();
                Screenshot.grab(OUT.toFile(), json.get("name").getAsString(), mc.getMainRenderTarget(), component -> {});
            }
            case "stop" -> mc.stop();
            default -> throw new IllegalArgumentException("Unknown action");
        }
    }
}
