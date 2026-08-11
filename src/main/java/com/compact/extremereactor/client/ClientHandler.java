package com.compact.extremereactor.client;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.client.screen.CompactReactorScreen;
import com.compact.extremereactor.client.screen.CompactTurbineScreen;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 客户端处理器。
 *
 * 负责客户端专属内容：在 MOD 事件总线上注册 GUI 屏幕。
 */
public final class ClientHandler {

    private ClientHandler() {
    }

    /** 注册容器对应的 GUI 屏幕（MOD 事件总线，仅客户端）。 */
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(Content.COMPACT_REACTOR_MENU.get(), CompactReactorScreen::new);
        event.register(Content.COMPACT_TURBINE_MENU.get(), CompactTurbineScreen::new);
    }
}
