package com.starcity.bridge;

import com.starcity.bridge.command.BridgeCommand;
import com.starcity.bridge.backup.ConsistentBackupScheduler;
import com.starcity.bridge.config.PluginConfig;
import com.starcity.bridge.module.ModuleManager;
import com.starcity.bridge.module.authme.AuthMeModule;
import com.starcity.bridge.module.market.MarketModule;
import com.starcity.bridge.module.residence.ResidenceBridgeModule;
import com.starcity.bridge.module.team.TeamModule;
import com.starcity.bridge.module.ticket.TicketModule;
import com.starcity.bridge.web.HttpApiServer;
import com.google.gson.Gson;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * StarCityBridge 数据整合插件主类。
 * <p>插件本身即网站后端：内建 HTTP REST API（web.HttpApiServer），
 * 统一对接 AuthMe/市场/团队/领地等插件模块，供前端直接调用。</p>
 */
public final class StarCityBridge extends JavaPlugin {

    private static StarCityBridge instance;
    private static final Gson GSON = new Gson();

    private PluginConfig pluginConfig;
    private ModuleManager moduleManager;
    private HttpApiServer httpServer;
    private ConsistentBackupScheduler backupScheduler;

    public static StarCityBridge getInstance() {
        return instance;
    }

    public PluginConfig config() {
        return pluginConfig;
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
        moduleManager.register(new TicketModule(this));
        moduleManager.register(new ResidenceBridgeModule(this));
        if (pluginConfig.authMeEnabled()) {
            moduleManager.register(new AuthMeModule(this));
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

        getLogger().info("StarCityBridge 已启用，网页后端: http://" + pluginConfig.webApiHost() + ":" + pluginConfig.webApiPort() + "/api");
    }

    @Override
    public void onDisable() {
        if (backupScheduler != null) {
            backupScheduler.stop();
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