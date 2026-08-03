package com.starcity.bridge;

import com.starcity.bridge.command.BridgeCommand;
import com.starcity.bridge.config.PluginConfig;
import com.starcity.bridge.module.ModuleManager;
import com.starcity.bridge.module.authme.AuthMeModule;
import com.starcity.bridge.module.market.MarketModule;
import com.google.gson.Gson;
import com.starcity.bridge.ws.WsClient;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * StarCityBridge 数据整合插件主类。
 * <p>职责：启动 WS 客户端连接网站后端，注册各插件对接模块，
 * 统一在服务器与网站之间交换数据。</p>
 */
public final class StarCityBridge extends JavaPlugin {

    private static StarCityBridge instance;
    private static final Gson GSON = new Gson();

    private PluginConfig pluginConfig;
    private ModuleManager moduleManager;
    private WsClient wsClient;

    public static StarCityBridge getInstance() {
        return instance;
    }

    public PluginConfig config() {
        return pluginConfig;
    }

    public WsClient wsClient() {
        return wsClient;
    }

    public Gson gson() {
        return GSON;
    }

    public ModuleManager modules() {
        return moduleManager;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadConfig();
        pluginConfig = PluginConfig.from(getConfig());

        moduleManager = new ModuleManager(this);
        moduleManager.register(new MarketModule(this));
        if (pluginConfig.authMeEnabled()) {
            moduleManager.register(new AuthMeModule(this));
        }

        wsClient = new WsClient(this, pluginConfig, moduleManager);
        wsClient.connect();

        BridgeCommand command = new BridgeCommand(this);
        PluginCommand site = getCommand("site");
        if (site != null) {
            site.setExecutor(command);
            site.setTabCompleter(command);
        }

        getLogger().info("StarCityBridge 已启用，后端: " + pluginConfig.backendUrl());
    }

    @Override
    public void onDisable() {
        if (wsClient != null) {
            wsClient.close();
        }
        if (moduleManager != null) {
            moduleManager.disableAll();
        }
        instance = null;
    }
}