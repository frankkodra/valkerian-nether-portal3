package Portal.code;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

@Mod.EventBusSubscriber
public class OverlayLogger {
    private static final CopyOnWriteArrayList<String> logMessages = new CopyOnWriteArrayList<>();
    private static final int MAX_MESSAGES = 10;

    public static void log(String message) {
        System.out.println("PortalSkies: " + message); // Always log to console
        logMessages.add(message);
        while (logMessages.size() > MAX_MESSAGES) {
            logMessages.remove(0);
        }
    }

    @SubscribeEvent
    public static void onRenderGameOverlay(RenderGuiEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        int y = 50;

        for (String message : logMessages) {
            guiGraphics.drawString(mc.font, message, 10, y, 0xFFFFFF);
            y += 10;
        }
    }

    public static void clear() {
        logMessages.clear();
    }
}


