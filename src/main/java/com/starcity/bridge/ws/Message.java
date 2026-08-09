package com.starcity.bridge.ws;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 与网站后端交换的消息信封。
 * 类型：request（后端→插件请求）、response（插件→后端应答）、event（插件→后端事件推送）、ping/pong（心跳）。
 * 应答在顶层携带 ok/data/error 字段。
 */
public class Message {

    private static final Gson GSON = new Gson();

    private String id;
    private String type;
    private String module;
    private String action;
    private JsonElement payload;
    private boolean ok;
    private JsonElement data;
    private String error;

    public static Message parse(String json) {
        return GSON.fromJson(json, Message.class);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static Message request(String id, String module, String action, JsonObject payload) {
        Message m = new Message();
        m.id = id;
        m.type = "request";
        m.module = module;
        m.action = action;
        m.payload = payload;
        return m;
    }

    public static Message response(String id, JsonObject data) {
        Message m = new Message();
        m.id = id;
        m.type = "response";
        m.ok = true;
        m.data = data;
        return m;
    }

    public static Message error(String id, String error) {
        Message m = new Message();
        m.id = id;
        m.type = "response";
        m.ok = false;
        m.error = error;
        return m;
    }

    public static Message event(String module, String action, JsonObject payload) {
        Message m = new Message();
        m.type = "event";
        m.module = module;
        m.action = action;
        m.payload = payload;
        return m;
    }

    public static Message ping() {
        Message m = new Message();
        m.type = "ping";
        return m;
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public String getModule() { return module; }
    public String getAction() { return action; }
    public JsonObject getPayload() {
        return payload == null || payload.isJsonNull() ? new JsonObject() : payload.getAsJsonObject();
    }
    public boolean isOk() { return ok; }
    public JsonObject getData() {
        return data == null || data.isJsonNull() ? null : data.getAsJsonObject();
    }
    public String getError() { return error; }
}