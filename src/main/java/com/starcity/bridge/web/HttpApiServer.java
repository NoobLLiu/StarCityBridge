package com.starcity.bridge.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.starcity.bridge.StarCityBridge;
import com.starcity.bridge.config.PluginConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 插件内建网页后端（HTTP REST API，前端直接调用，代替旧的 Go 后端转发）。
 *
 * <p>统一响应格式与旧后端一致：{ code, message, data }，code=0 表示成功。</p>
 */
public final class HttpApiServer {

    private final StarCityBridge plugin;
    private final PluginConfig cfg;
    private final TokenService tokens;
    private final List<Route> portalRoutes = new ArrayList<>();
    private final List<Route> adminRoutes = new ArrayList<>();
    private HttpServer server;
    private ExecutorService executor;

    public HttpApiServer(StarCityBridge plugin, PluginConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.tokens = new TokenService(cfg.webApiTokenSecret());
        buildRoutes();
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(cfg.webApiHost(), cfg.webApiPort()), 0);
        server.createContext("/api", this::handle);
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "starcity-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.start();
        plugin.getLogger().info("网页后端已启动: http://" + cfg.webApiHost() + ":" + cfg.webApiPort() + "/api");
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    public TokenService tokens() {
        return tokens;
    }

    // ===================== 路由 =====================

    private void buildRoutes() {
        // 门户（玩家令牌）
        portal( "GET",  "/api/market/items",                 "market", "list_items_page");
        portal( "GET",  "/api/market/items/:item_id",          "market", "item_detail_full");
        portal( "GET",  "/api/market/orderbook/:item_id",      "market", "order_book");
        portal( "GET",  "/api/market/info",                   "market", "market_info");
        portal( "GET",  "/api/market/me/orders",              "market", "my_orders");
        portal( "GET",  "/api/market/me/trades",              "market", "my_trades");
        portal( "GET",  "/api/market/me/warehouse",           "market", "my_warehouse");
        portal( "POST", "/api/market/order",                  "market", "place_order");
        portal( "POST", "/api/market/order/:order_id/cancel",  "market", "cancel");
        portal( "POST", "/api/market/trade",                  "market", "trade");
        portal( "POST", "/api/market/warehouse/deposit",      "market", "warehouse_deposit");
        portal( "POST", "/api/market/warehouse/withdraw",     "market", "warehouse_withdraw");
        portal( "POST", "/api/market/exchange",               "market", "exchange");

        portal( "GET",  "/api/team",                          "team", "list");
        portal( "GET",  "/api/team/me",                       "team", "my_team");
        portal( "GET",  "/api/team/search",                   "team", "search");
        portal( "GET",  "/api/team/:tid",                     "team", "detail");
        portal( "GET",  "/api/team/:tid/members",             "team", "members");
        portal( "GET",  "/api/team/:tid/applications",        "team", "applications");
        portal( "GET",  "/api/team/:tid/funds",               "team", "funds");
        portal( "GET",  "/api/team/:tid/logs",                "team", "logs");
        portal( "GET",  "/api/team/:tid/messages",            "team", "messages");
        portal( "POST", "/api/team/create",                   "team", "create");
        portal( "POST", "/api/team/:tid/join",                "team", "join");
        portal( "POST", "/api/team/:tid/application/accept",  "team", "accept_application");
        portal( "POST", "/api/team/:tid/application/reject",  "team", "reject_application");
        portal( "POST", "/api/team/:tid/member/promote",      "team", "promote");
        portal( "POST", "/api/team/:tid/member/demote",       "team", "demote");
        portal( "POST", "/api/team/:tid/member/remove",       "team", "remove_member");
        portal( "POST", "/api/team/quit",                     "team", "quit");
        portal( "POST", "/api/team/:tid/rename",              "team", "rename");
        portal( "POST", "/api/team/:tid/notice",              "team", "set_notice");
        portal( "POST", "/api/team/:tid/public",              "team", "set_public");
        portal( "POST", "/api/team/:tid/friendly-fire",       "team", "set_friendly_fire");
        portal( "POST", "/api/team/:tid/disband",             "team", "disband");
        portal( "POST", "/api/team/:tid/funds/deposit",       "team", "deposit_funds");
        portal( "POST", "/api/team/:tid/funds/withdraw",      "team", "withdraw_funds");
        portal( "POST", "/api/team/:tid/message",             "team", "post_message");
        portal( "GET",  "/api/tickets",                      "ticket", "list_mine");
        portal( "POST", "/api/tickets",                      "ticket", "create");
        portal( "GET",  "/api/tickets/:id",                  "ticket", "detail");
        portal( "POST", "/api/tickets/:id/reply",            "ticket", "reply");

        // 管理（admin 令牌 / is_op 玩家）
        admin("GET",  "/api/admin/team/all",                "team", "all");
        admin("POST", "/api/admin/team/sync-names",         "team", "admin_sync_names");
        admin("POST", "/api/admin/team/reload",             "team", "admin_reload");
        admin("POST", "/api/admin/team/:tid/disband",       "team", "admin_disband");
        admin("GET",  "/api/admin/market/stats",            "market", "market_stats");
        admin("POST", "/api/admin/market/:item_id/suspend",  "market", "admin_suspend");
        admin("POST", "/api/admin/market/tax",              "market", "admin_set_tax");
        admin("POST", "/api/admin/market/announcement",     "market", "admin_announcement");
        admin("GET",  "/api/admin/tickets",                  "ticket", "admin_list");
        admin("GET",  "/api/admin/tickets/:id",              "ticket", "admin_detail");
        admin("POST", "/api/admin/tickets/:id/reply",        "ticket", "admin_reply");
        admin("POST", "/api/admin/tickets/:id/status",       "ticket", "admin_status");
    }

    private void portal(String method, String path, String module, String action) {
        portalRoutes.add(new Route(method, path, module, action));
    }

    private void admin(String method, String path, String module, String action) {
        adminRoutes.add(new Route(method, path, module, action));
    }

    // ===================== 请求处理 =====================

    private void handle(HttpExchange ex) throws IOException {
        applyCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 204, new JsonObject(), "success");
            return;
        }
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod().toUpperCase();
            Map<String, String> query = query(ex);

            if ("GET".equals(method) && "/api/health".equals(path)) {
                sendOk(ex, health());
                return;
            }
            if ("GET".equals(method) && "/api/settings/public".equals(path)) {
                sendOk(ex, settingsPublic());
                return;
            }
            if ("POST".equals(method) && "/api/auth/login".equals(path)) {
                login(ex, body(ex));
                return;
            }
            if ("GET".equals(method) && "/api/auth/me".equals(path)) {
                Auth au = auth(ex, false);
                if (au == null) { send(ex, 401, null, "未登录或令牌无效"); return; }
                sendOk(ex, me(au));
                return;
            }

            Route r = match(portalRoutes, method, path);
            if (r != null) {
                Auth au = auth(ex, false);
                if (au == null) { send(ex, 401, null, "未登录或令牌无效"); return; }
                dispatch(ex, r, payload(r, query, body(ex), au, null));
                return;
            }

            Route ar = match(adminRoutes, method, path);
            if (ar != null) {
                Auth au = auth(ex, true);
                if (au == null) { send(ex, 401, null, "需要管理员令牌"); return; }
                dispatch(ex, ar, payload(ar, query, body(ex), au, au.isOp() ? "true" : null));
                return;
            }

            send(ex, 404, null, "接口不存在: " + method + " " + path);
        } catch (Exception e) {
            plugin.getLogger().warning("网页后端请求异常: " + e.getMessage());
            send(ex, 500, null, "服务器繁忙");
        } finally {
            ex.close();
        }
    }

    private void dispatch(HttpExchange ex, Route r, JsonObject payload) throws IOException {
        String realAction = resolveAction(r, payload);
        JsonObject resp = plugin.modules().handleRequest(r.module, realAction, payload);
        if (resp == null) {
            send(ex, 404, null, "模块 " + r.module + " 不支持动作 " + r.action);
            return;
        }
        boolean ok = resp.has("ok") && resp.get("ok").getAsBoolean();
        JsonElement data = resp.get("data");
        String message = resp.has("message") ? resp.get("message").getAsString() : "";
        send(ex, ok ? 200 : 400, data == null || data.isJsonNull() ? null : data.getAsJsonObject(), message);
    }

    private void login(HttpExchange ex, JsonObject body) throws IOException {
        if (body == null) { send(ex, 400, null, "缺少请求参数"); return; }
        JsonObject in = new JsonObject();
        in.addProperty("email", str(body, "email"));
        in.addProperty("password", str(body, "password"));
        JsonObject resp = plugin.modules().handleRequest("authme", "login_check", in);
        if (resp == null) { send(ex, 404, null, "authme 模块未启用"); return; }
        boolean valid = resp.has("valid") && resp.get("valid").getAsBoolean();
        if (!valid) {
            send(ex, 401, null, "邮箱或密码错误");
            return;
        }
        String player = str(resp, "player");
        String playerUuid = str(resp, "player_uuid");
        String email = str(resp, "email");
        boolean isOp = resp.has("is_op") && resp.get("is_op").getAsBoolean();
        String token = tokens.issuePlayer(player, playerUuid, email, isOp, cfg.webApiTokenTtlSeconds());
        JsonObject out = new JsonObject();
        out.addProperty("token", token);
        out.addProperty("player", player);
        out.addProperty("player_uuid", playerUuid);
        out.addProperty("email", email);
        out.addProperty("is_op", isOp);
        sendOk(ex, out);
    }

    private JsonObject me(Auth au) {
        JsonObject out = new JsonObject();
        out.addProperty("player", au.player);
        out.addProperty("player_uuid", au.playerUuid);
        out.addProperty("email", au.email);
        out.addProperty("is_op", au.isOp());
        out.addProperty("sub", au.sub);
        return out;
    }

    private JsonObject health() {
        JsonObject d = new JsonObject();
        d.addProperty("status", "ok");
        d.addProperty("plugin", "StarCityBridge");
        d.addProperty("version", plugin.getDescription().getVersion());
        d.addProperty("online_players", plugin.getServer().getOnlinePlayers().size());
        d.addProperty("time", System.currentTimeMillis());
        return d;
    }

    private JsonObject settingsPublic() {
        JsonObject d = new JsonObject();
        d.addProperty("server_name", "StarCity");
        d.addProperty("web_backend", "plugin");
        d.addProperty("plugin_version", plugin.getDescription().getVersion());
        return d;
    }

    // ===================== 认证 =====================

    private Auth auth(HttpExchange ex, boolean requireAdmin) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        String bearer = null;
        if (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            bearer = h.substring(7).trim();
        }
        if (bearer == null) {
            Map<String, String> q = query(ex);
            if (q.containsKey("token")) bearer = q.get("token");
        }
        if (requireAdmin) {
            String adminTok = ex.getRequestHeaders().getFirst("X-Admin-Token");
            if (adminTok == null) {
                Map<String, String> q = query(ex);
                adminTok = q.get("admin_token");
            }
            JsonObject ap = tokens.verifyAdmin(adminTok);
            if (ap != null) return new Auth("admin", "", "", "", true, null);
            JsonObject pp = tokens.verifyPlayer(bearer);
            if (pp != null && pp.has("is_op") && pp.get("is_op").getAsBoolean()) {
                return new Auth("player", str(pp, "player"), str(pp, "player_uuid"), str(pp, "email"), true, pp);
            }
            return null;
        }
        JsonObject pp = tokens.verifyPlayer(bearer);
        if (pp == null) return null;
        return new Auth("player", str(pp, "player"), str(pp, "player_uuid"), str(pp, "email"), pp.has("is_op") && pp.get("is_op").getAsBoolean(), pp);
    }


    /** 复合市场动作：根据请求体/路径二次分发到 MarketModule 的具体 action。 */
    private static String resolveAction(Route r, JsonObject payload) {
        String act = r.action;
        String type = str(payload, "type").toLowerCase(java.util.Locale.ROOT);
        switch (act) {
            case "place_order":
                return str(payload, "item_base64").isEmpty() ? (type.equals("sell") ? "place_sell" : "place_buy") : "place_sell_item";
            case "trade":
                return switch (type) {
                    case "market_buy" -> "market_buy";
                    case "market_sell" -> "market_sell";
                    case "quick_sell" -> "quick_sell";
                    default -> "market_buy";
                };
            case "warehouse_deposit":
                return type.equals("hand") ? "warehouse_deposit_hand" : "deposit_money";
            case "warehouse_withdraw":
                return switch (type) {
                    case "money" -> "warehouse_withdraw_money";
                    case "item" -> "warehouse_withdraw_item";
                    default -> "warehouse_withdraw_all";
                };
            case "exchange":
                return type.equals("m2d") ? "exchange_m2d" : "exchange_d2m";
            default:
                return act;
        }
    }
    // ===================== 载荷 =====================

    private JsonObject payload(Route r, Map<String, String> query, JsonObject body, Auth au, String forceAdmin) {
        JsonObject p = body == null ? new JsonObject() : body.deepCopy();
        if (au != null && !au.playerUuid.isEmpty()) p.addProperty("player_uuid", au.playerUuid);
        if (au != null && !au.player.isEmpty() && !p.has("player")) p.addProperty("player", au.player);
        if (forceAdmin != null) p.addProperty("admin", true);
        // 把查询参数并入（GET 查询）
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (!p.has(e.getKey())) p.addProperty(e.getKey(), e.getValue());
        }
        // 动态路由参数
        if (r.params != null) {
            for (Map.Entry<String, String> e : r.params.entrySet()) p.addProperty(e.getKey(), e.getValue());
        }
        // 复合动作：根据 body/路径二次分发到具体 module 动作
        return p;
    }

    // ===================== 工具 =====================

    private JsonObject body(HttpExchange ex) {
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = in.readAllBytes();
            if (buf.length == 0) return new JsonObject();
            return JsonParser.parseString(new String(buf, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return q;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? urlDecode(pair.substring(0, eq)) : urlDecode(pair);
            String v = eq >= 0 ? urlDecode(pair.substring(eq + 1)) : "";
            q.put(k, v);
        }
        return q;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private Route match(List<Route> routes, String method, String path) {
        String[] segs = path.split("/");
        for (Route r : routes) {
            if (!r.method.equals(method)) continue;
            String[] want = r.patternSegments;
            if (want.length != segs.length) continue;
            boolean ok = true;
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 0; i < segs.length; i++) {
                String w = want[i];
                if (w.startsWith(":")) params.put(w.substring(1), segs[i]);
                else if (!w.equals(segs[i])) { ok = false; break; }
            }
            if (ok) return r.with(params);
        }
        return null;
    }

    private void applyCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", cfg.webApiCorsOrigin());
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type,Authorization,X-Admin-Token");
        ex.getResponseHeaders().set("Access-Control-Max-Age", "86000");
    }

    private void sendOk(HttpExchange ex, JsonObject data) throws IOException {
        send(ex, 200, data, "success");
    }

    private void send(HttpExchange ex, int status, JsonObject data, String message) throws IOException {
        JsonObject out = new JsonObject();
        out.addProperty("code", status == 200 ? 0 : status);
        out.addProperty("message", message == null ? "" : message);
        out.add("data", data == null ? new JsonObject() : data);
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    // ===================== 结构 =====================

    private record Auth(String sub, String player, String playerUuid, String email, boolean isOp, JsonObject raw) {
    }

    private static final class Route {
        final String method;
        final String[] patternSegments;
        final String module;
        final String action;
        Map<String, String> params;

        Route(String method, String path, String module, String action) {
            this.method = method;
            this.patternSegments = path.split("/");
            this.module = module;
            this.action = action;
        }

        Route with(Map<String, String> params) {
            Route r = new Route(method, String.join("/", patternSegments), module, action);
            r.params = params;
            return r;
        }
    }
}
