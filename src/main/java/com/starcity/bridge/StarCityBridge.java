package com.starcity.bridge;

import com.starcity.bridge.command.BridgeCommand;
import com.starcity.bridge.backup.ConsistentBackupScheduler;
import com.starcity.bridge.config.PluginConfig;
import com.starcity.bridge.module.ModuleManager;
import com.starcity.bridge.module.authme.AuthMeModule;
import com.starcity.bridge.module.market.MarketModule;
import com.starcity.bridge.module.residence.ResidenceBridgeModule;
import com.starcity.bridge.module.team.TeamModule;
import com.starcity.bridge.web.HttpApiServer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.logging.Level;
import com.starcity.bridge.ws.WsClient;
import com.starcity.bridge.ws.WsServer;
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
    private WsServer wsServer;
    private HttpApiServer httpServer;
    private ConsistentBackupScheduler backupScheduler;

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


    /** 是否处于静音模式（命令不返回消息，配置持久化，重启仍生效） */
    public boolean isQuietMode() {
        return pluginConfig.quietMode();
    }

    /** 切换静音模式并保存到 config.yml（同时切换日志级别） */
    public synchronized boolean toggleQuietMode() {
        boolean next = !pluginConfig.quietMode();
        getConfig().set("settings.quiet_mode", next);
        saveConfig();
        pluginConfig = PluginConfig.from(getConfig());
        getLogger().setLevel(next ? Level.OFF : Level.INFO);
        return next;
    }
    public java.util.concurrent.CompletableFuture<JsonObject> request(String module, String action, JsonObject payload) {
        if (wsServer != null) {
            return wsServer.request(module, action, payload);
        }
        return wsClient.request(module, action, payload);
    }

    /** 向网站后端推送事件（兼容两种连接模式） */
    public void sendEvent(String module, String action, JsonObject payload) {
        if (wsServer != null) {
            wsServer.sendEvent(module, action, payload);
        } else {
            wsClient.sendEvent(module, action, payload);
        }
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
        // 静音模式：日志级别随配置（重启后仍生效）
        getLogger().setLevel(pluginConfig.quietMode() ? Level.OFF : Level.INFO);
        moduleManager.register(new MarketModule(this));
        moduleManager.register(new TeamModule(this));
        moduleManager.register(new ResidenceBridgeModule(this));
        if (pluginConfig.authMeEnabled()) {
            moduleManager.register(new AuthMeModule(this));
        }

        if ("server".equalsIgnoreCase(pluginConfig.connectionMode())) {
            wsServer = new WsServer(this, pluginConfig, moduleManager);
            wsServer.start();
        } else {
            wsClient = new WsClient(this, pluginConfig, moduleManager);
            wsClient.connect();
        }

        if (pluginConfig.webApiEnabled()) {
            httpServer = new HttpApiServer(this, pluginConfig);
            try {
                httpServer.start();
            } catch (Exception e) {
                getLogger().warning("网页后端启动失败: " + e.getMessage());
            }
        }

        BridgeCommand command = new BridgeCommand(this);
        PluginCommand site = getCommand("site");
        if (site != null) {
            site.setExecutor(command);
            site.setTabCompleter(command);
        }

        backupScheduler = new ConsistentBackupScheduler(this, pluginConfig);
        backupScheduler.start();

        getLogger().info("StarCityBridge 已启用，后端: " + pluginConfig.backendUrl());
    }

    @Override
    public void onDisable() {
        if (backupScheduler != null) {
            backupScheduler.stop();
        }
        if (wsClient != null) {
            wsClient.close();
        }
        if (wsServer != null) {
            wsServer.stop();
        }
        if (httpServer != null) {
            httpServer.stop();
        }
        if (moduleManager != null) {
            moduleManager.disableAll();
        }
        instance = null;
    }
}
