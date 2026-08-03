package com.starcity.bridge.module;

import com.google.gson.JsonObject;

/**
 * 数据整合模块接口：每个插件对接（如登录插件 AuthMe）实现为一个模块，
 * 处理来自网站后端的请求，并可向后端推送事件。
 */
public interface BridgeModule {

    /** 模块名（消息路由用），如 "authme" */
    String name();

    /**
     * 处理后端请求。
     *
     * @param action  动作名
     * @param payload 请求数据
     * @return 响应数据；返回 null 表示不支持该动作
     */
    JsonObject handleRequest(String action, JsonObject payload);

    /** 模块注册时调用（可注册 Bukkit 事件监听等） */
    default void onRegister(ModuleManager manager) {
    }

    /** 插件卸载时调用 */
    default void onDisable() {
    }
}