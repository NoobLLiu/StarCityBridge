package com.starcity.bridge.module.ticket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.starcity.bridge.StarCityBridge;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 工单存储：以插件 data 目录下的 tickets.json 持久化。
 * 本文件为网页后端（插件内建）保存的工单数据，与服务器内运行状态解耦，可离线读写。
 */
public final class TicketStore {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StarCityBridge plugin;
    private final File file;
    private final List<Ticket> tickets = new ArrayList<>();
    private long nextId = 1;

    public TicketStore(StarCityBridge plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tickets.json");
        load();
    }

    // ===================== 读写 =====================

    public synchronized List<Ticket> listMine(String uuid) {
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : tickets) {
            if (t.authorUuid.equalsIgnoreCase(uuid)) out.add(t.copy());
        }
        sortNewest(out);
        return out;
    }

    public synchronized List<Ticket> listAll() {
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : tickets) out.add(t.copy());
        sortNewest(out);
        return out;
    }

    public synchronized Ticket get(long id) {
        for (Ticket t : tickets) if (t.id == id) return t.copy();
        return null;
    }

    public synchronized Ticket create(String authorUuid, String authorName, String subject, String content) {
        Ticket t = new Ticket(nextId++, authorUuid, authorName, subject, content, STATUS_OPEN, now(), now());
        tickets.add(t);
        save();
        return t.copy();
    }

    /** 追加回复；返回 null 表示工单不存在。 */
    public synchronized Ticket reply(long id, String authorUuid, String authorName, String content) {
        Ticket t = find(id);
        if (t == null) return null;
        t.replies.add(new Reply(authorUuid, authorName, content, now()));
        t.updatedAt = now();
        save();
        return t.copy();
    }

    /** 修改状态；返回 null 表示工单不存在。 */
    public synchronized Ticket status(long id, String status) {
        Ticket t = find(id);
        if (t == null) return null;
        String s = STATUS_CLOSED.equalsIgnoreCase(status) ? STATUS_CLOSED : STATUS_OPEN;
        t.status = s;
        t.updatedAt = now();
        save();
        return t.copy();
    }

    // ===================== JSON 序列化 =====================

    public synchronized JsonObject toJson(Ticket t) {
        JsonObject o = new JsonObject();
        o.addProperty("id", t.id);
        o.addProperty("author_uuid", t.authorUuid);
        o.addProperty("author_name", t.authorName);
        o.addProperty("subject", t.subject);
        o.addProperty("content", t.content);
        o.addProperty("status", t.status);
        o.addProperty("created_at", t.createdAt);
        o.addProperty("updated_at", t.updatedAt);
        JsonArray arr = new JsonArray();
        for (Reply r : t.replies) {
            JsonObject ro = new JsonObject();
            ro.addProperty("author_uuid", r.authorUuid);
            ro.addProperty("author_name", r.authorName);
            ro.addProperty("content", r.content);
            ro.addProperty("created_at", r.createdAt);
            arr.add(ro);
        }
        o.add("replies", arr);
        return o;
    }

    public synchronized List<Ticket> tickets() {
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : tickets) out.add(t.copy());
        return out;
    }

    // ===================== 内部 =====================

    private Ticket find(long id) {
        for (Ticket t : tickets) if (t.id == id) return t;
        return null;
    }

    private static void sortNewest(List<Ticket> list) {
        list.sort(Comparator.comparing((Ticket t) -> t.updatedAt).reversed().thenComparing(t -> t.id, Comparator.reverseOrder()));
    }

    private static String now() {
        return LocalDateTime.now().format(FMT);
    }

    private void load() {
        if (!file.exists()) return;
        try (FileReader r = new FileReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = com.google.gson.JsonParser.parseReader(r).getAsJsonObject();
            nextId = root.has("next_id") ? root.get("next_id").getAsLong() : 1L;
            JsonArray arr = root.getAsJsonArray("tickets");
            for (int i = 0; i < arr.size(); i++) {
                JsonObject o = arr.get(i).getAsJsonObject();
                Ticket t = new Ticket();
                t.id = o.get("id").getAsLong();
                t.authorUuid = s(o, "author_uuid");
                t.authorName = s(o, "author_name");
                t.subject = s(o, "subject");
                t.content = s(o, "content");
                t.status = s(o, "status");
                t.createdAt = s(o, "created_at");
                t.updatedAt = s(o, "updated_at");
                if (t.status.isEmpty()) t.status = STATUS_OPEN;
                if (o.has("replies")) {
                    JsonArray rs = o.getAsJsonArray("replies");
                    for (int j = 0; j < rs.size(); j++) {
                        JsonObject ro = rs.get(j).getAsJsonObject();
                        t.replies.add(new Reply(s(ro, "author_uuid"), s(ro, "author_name"), s(ro, "content"), s(ro, "created_at")));
                    }
                }
                tickets.add(t);
                if (t.id >= nextId) nextId = t.id + 1;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("读取工单数据失败: " + e.getMessage());
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("next_id", nextId);
        JsonArray arr = new JsonArray();
        for (Ticket t : tickets) arr.add(toJson(t));
        root.add("tickets", arr);
        try {
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(file, StandardCharsets.UTF_8)) {
                w.write(plugin.gson().toJson(root));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("保存工单数据失败: " + e.getMessage());
        }
    }

    private static String s(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    // ===================== 数据模型 =====================

    public static final class Ticket {
        public long id;
        public String authorUuid = "";
        public String authorName = "";
        public String subject = "";
        public String content = "";
        public String status = STATUS_OPEN;
        public String createdAt = "";
        public String updatedAt = "";
        public final List<Reply> replies = new ArrayList<>();

        public Ticket() {}
        public Ticket(long id, String authorUuid, String authorName, String subject, String content, String status, String createdAt, String updatedAt) {
            this.id = id;
            this.authorUuid = authorUuid;
            this.authorName = authorName;
            this.subject = subject;
            this.content = content;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public Ticket copy() {
            Ticket t = new Ticket(id, authorUuid, authorName, subject, content, status, createdAt, updatedAt);
            for (Reply r : replies) t.replies.add(new Reply(r.authorUuid, r.authorName, r.content, r.createdAt));
            return t;
        }
    }

    public static final class Reply {
        public String authorUuid;
        public String authorName;
        public String content;
        public String createdAt;

        public Reply() {}
        public Reply(String authorUuid, String authorName, String content, String createdAt) {
            this.authorUuid = authorUuid;
            this.authorName = authorName;
            this.content = content;
            this.createdAt = createdAt;
        }
    }
}
