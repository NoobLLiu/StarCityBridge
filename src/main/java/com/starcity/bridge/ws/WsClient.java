package com.starcity.bridge.ws;

import com.google.gson.JsonObject;
import com.starcity.bridge.StarCityBridge;
import com.starcity.bridge.config.PluginConfig;
import com.starcity.bridge.module.ModuleManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 与网站后端通信的 WebSocket 客户端。
 * <p>基于 JDK 内置 java.net.http.WebSocket，无第三方依赖；
 * 断线自动重连（指数退避），心跳保活，支持 request/response 关联。</p>
 */
public class WsClient {

    private final StarCityBridge plugin;
    private final PluginConfig config;
    private final ModuleManager modules;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final Map<String, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();

    private volatile WebSocket socket;
    private volatile boolean closing = false;
    private int reconnectAttempts = 0;

    public WsClient(StarCityBridge plugin, PluginConfig config, ModuleManager modules) {
        this.plugin = plugin;
        this.config = config;
        this.modules = modules;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "starcity-bridge-ws");
            t.setDaemon(true);
            return t;
        });
    }

    /** 建立连接（失败时自动调度重连） */
    public synchronized void connect() {
        if (closing) {
            return;
        }
        try {
            plugin.getLogger().info("正在连接网站后端: " + config.backendUrl());
            CompletableFuture<WebSocket> future = httpClient.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + config.token())
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(config.backendUrl()), new Listener());
            WebSocket ws = future.get(10, TimeUnit.SECONDS);
            socket = ws;
            reconnectAttempts = 0;
            plugin.getLogger().info("已连接网站后端，开始数据同步");
            // 心跳
            scheduler.scheduleWithFixedDelay(this::sendPing,
                    config.heartbeatSeconds(), config.heartbeatSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "连接后端失败，稍后重试: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (closing) {
            return;
        }
        int delay = Math.min(config.reconnectDelaySeconds() * (reconnectAttempts + 1), 60);
        reconnectAttempts++;
        plugin.getLogger().info("将在 " + delay + " 秒后重连");
        scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    /** 向后端发送请求并等待响应（超时 10 秒） */
    public CompletableFuture<JsonObject> request(String module, String action, JsonObject payload) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<Message> future = new CompletableFuture<>();
        pending.put(id, future);
        send(Message.request(id, module, action, payload));
        return future.orTimeout(10, TimeUnit.SECONDS)
                .thenApply(msg -> {
                    if (msg.isOk()) {
                        return msg.getData();
                    }
                    throw new CompletionException(new RuntimeException(
                            msg.getError() == null ? "后端返回错误" : msg.getError()));
                });
    }

    /** 向后端推送事件 */
    public void sendEvent(String module, String action, JsonObject payload) {
        send(Message.event(module, action, payload));
    }

    private void sendPing() {
        send(Message.ping());
    }

    private void send(Message message) {
        WebSocket ws = socket;
        if (ws == null || ws.isOutputClosed()) {
            plugin.getLogger().warning("WS 未连接，消息已丢弃: " + message.getModule() + "/" + message.getAction());
            return;
        }
        ws.sendText(message.toJson(), true);
    }

    public void close() {
        closing = true;
        if (socket != null && !socket.isOutputClosed()) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "plugin stopping").get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        scheduler.shutdownNow();
        pending.values().forEach(f -> f.completeExceptionally(new RuntimeException("插件关闭")));
        pending.clear();
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            plugin.getLogger().info("WebSocket 已打开");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String json = buffer.toString();
                buffer.setLength(0);
                handleMessage(json);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            plugin.getLogger().warning("连接已关闭(" + statusCode + "): " + reason);
            socket = null;
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            plugin.getLogger().log(Level.WARNING, "WebSocket 错误: " + error.getMessage());
            webSocket.abort();
            socket = null;
            scheduleReconnect();
        }

        private void handleMessage(String json) {
            try {
                Message message = Message.parse(json);
                String type = message.getType();
                if ("request".equals(type)) {
                    handleRequest(message);
                } else if ("response".equals(type)) {
                    CompletableFuture<Message> future = pending.remove(message.getId());
                    if (future != null) {
                        future.complete(message);
                    }
                } else if ("pong".equals(type)) {
                    // 心跳应答，忽略
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "消息解析失败: " + json, e);
            }
        }

        private void handleRequest(Message message) {
            try {
                JsonObject data = modules.handleRequest(message.getModule(), message.getAction(), message.getPayload());
                if (data == null) {
                    send(Message.error(message.getId(), "unsupported action: "
                            + message.getModule() + "/" + message.getAction()));
                } else {
                    send(Message.response(message.getId(), data));
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "处理请求失败: " + message.getModule() + "/" + message.getAction(), e);
                send(Message.error(message.getId(), "internal error"));
            }
        }
    }
}