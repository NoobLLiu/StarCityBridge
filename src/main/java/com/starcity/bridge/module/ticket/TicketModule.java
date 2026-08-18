package com.starcity.bridge.module.ticket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.starcity.bridge.StarCityBridge;
import com.starcity.bridge.module.BridgeModule;

import java.util.List;

/**
 * 工单模块：插件内建网页后端提供工单功能（门户：玩家提交/查看/回复自己的工单；
 * 管理：全部工单、回复、改状态）。数据保存在 data/tickets.json。
 */
public class TicketModule implements BridgeModule {

    private final StarCityBridge plugin;
    private final TicketStore store;

    public TicketModule(StarCityBridge plugin) {
        this.plugin = plugin;
        this.store = new TicketStore(plugin);
    }

    @Override
    public String name() {
        return "ticket";
    }

    @Override
    public JsonObject handleRequest(String action, JsonObject payload) {
        try {
            return switch (action) {
                case "create" -> create(payload);
                case "list_mine" -> listMine(payload);
                case "detail" -> detail(payload);
                case "reply" -> reply(payload);
                case "admin_list" -> adminList(payload);
                case "admin_detail" -> adminDetail(payload);
                case "admin_reply" -> adminReply(payload);
                case "admin_status" -> adminStatus(payload);
                default -> null;
            };
        } catch (Exception e) {
            plugin.getLogger().warning("[ticket] 执行失败: " + action + " -> " + e.getMessage());
            return result(false, "工单操作失败: " + e.getMessage(), null);
        }
    }

    private JsonObject create(JsonObject payload) {
        String uuid = playerUuid(payload);
        String subject = s(payload, "subject").trim();
        String content = s(payload, "content").trim();
        if (uuid.isEmpty() || subject.isEmpty() || content.isEmpty()) {
            return result(false, "缺少玩家UUID/标题/内容", null);
        }
        TicketStore.Ticket t = store.create(uuid, s(payload, "player"), subject, content);
        return result(true, "", store.toJson(t));
    }

    private JsonObject listMine(JsonObject payload) {
        String uuid = playerUuid(payload);
        List<TicketStore.Ticket> list = store.listMine(uuid);
        return result(true, "", ticketsData(list));
    }

    private JsonObject detail(JsonObject payload) {
        String uuid = playerUuid(payload);
        long id = longField(payload, "id", 0);
        TicketStore.Ticket t = store.get(id);
        if (t == null || !t.authorUuid.equalsIgnoreCase(uuid)) {
            return result(false, "工单不存在或无权限查看", null);
        }
        return result(true, "", store.toJson(t));
    }

    private JsonObject reply(JsonObject payload) {
        String uuid = playerUuid(payload);
        long id = longField(payload, "id", 0);
        TicketStore.Ticket t = store.get(id);
        if (t == null || !t.authorUuid.equalsIgnoreCase(uuid)) {
            return result(false, "工单不存在或无权限回复", null);
        }
        String content = s(payload, "content").trim();
        if (content.isEmpty()) return result(false, "回复内容不能为空", null);
        TicketStore.Ticket updated = store.reply(id, uuid, s(payload, "player"), content);
        return result(true, "", store.toJson(updated));
    }

    private JsonObject adminList(JsonObject payload) {
        if (!admin(payload)) return result(false, "需要管理员权限", null);
        return result(true, "", ticketsData(store.listAll()));
    }

    private JsonObject adminDetail(JsonObject payload) {
        if (!admin(payload)) return result(false, "需要管理员权限", null);
        TicketStore.Ticket t = store.get(longField(payload, "id", 0));
        if (t == null) return result(false, "工单不存在", null);
        return result(true, "", store.toJson(t));
    }

    private JsonObject adminReply(JsonObject payload) {
        if (!admin(payload)) return result(false, "需要管理员权限", null);
        long id = longField(payload, "id", 0);
        String content = s(payload, "content").trim();
        TicketStore.Ticket t = store.get(id);
        if (t == null) return result(false, "工单不存在", null);
        if (content.isEmpty()) return result(false, "回复内容不能为空", null);
        return result(true, "", store.toJson(store.reply(id, "ADMIN", "管理员", content)));
    }

    private JsonObject adminStatus(JsonObject payload) {
        if (!admin(payload)) return result(false, "需要管理员权限", null);
        long id = longField(payload, "id", 0);
        TicketStore.Ticket t = store.status(id, s(payload, "status"));
        if (t == null) return result(false, "工单不存在", null);
        return result(true, "", store.toJson(t));
    }

    // ===================== 工具 =====================

    private JsonObject ticketsData(List<TicketStore.Ticket> list) {
        JsonArray arr = new JsonArray();
        for (TicketStore.Ticket t : list) arr.add(store.toJson(t));
        JsonObject data = new JsonObject();
        data.add("tickets", arr);
        data.addProperty("total", arr.size());
        return data;
    }

    private String playerUuid(JsonObject payload) {
        return s(payload, "player_uuid");
    }

    private static boolean admin(JsonObject payload) {
        return payload.has("admin") && payload.get("admin").getAsBoolean();
    }

    private static String s(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static long longField(JsonObject o, String key, long def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : def;
    }

    private static JsonObject result(boolean ok, String message, JsonObject data) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", ok);
        out.addProperty("message", message == null ? "" : message);
        out.add("data", data == null ? new JsonObject() : data);
        return out;
    }
}
