package com.starcity.bridge.module;

import com.google.gson.JsonObject;
import com.starcity.bridge.StarCityBridge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模块管理器：按模块名路由后端请求，并统一注册/卸载模块。
 */
public class ModuleManager {

    private final StarCityBridge plugin;
    private final Map<String, BridgeModule> modules = new LinkedHashMap<>();

    public ModuleManager(StarCityBridge plugin) {
        this.plugin = plugin;
    }

    public void register(BridgeModule module) {
        modules.put(module.name(), module);
        module.onRegister(this);
        plugin.getLogger().info("模块已注册: " + module.name());
    }

    public void disableAll() {
        modules.values().forEach(BridgeModule::onDisable);
        modules.clear();
    }

    /**
     * 处理来自后端 WS 的请求，返回响应数据；模块不存在或不支持时返回 null。
     */
    public JsonObject handleRequest(String moduleName, String action, JsonObject payload) {
        BridgeModule module = modules.get(moduleName);
        if (module == null) {
            return null;
        }
        return module.handleRequest(action, payload);
    }

    public StarCityBridge plugin() {
        return plugin;
    }
}