package com.starcity.bridge.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 插件内建网页后端使用的无状态签名令牌（简易 JWT，HMAC-SHA256）。
 * token 形如 v1.<base64(json payload)>.<base64(hmac(secret, body))>。
 */
public final class TokenService {

    private static final Gson GSON = new Gson();
    private static final String VERSION = "v1";

    private final String secret;

    public TokenService(String secret) {
        this.secret = secret == null || secret.isBlank() ? "default" : secret;
    }

    /** 玩家令牌：payload 含 player/player_uuid/email/is_op/iat/exp */
    public String issuePlayer(String player, String playerUuid, String email, boolean isOp, long ttlSeconds) {
        JsonObject p = new JsonObject();
        p.addProperty("sub", "player");
        p.addProperty("player", player);
        p.addProperty("player_uuid", playerUuid == null ? "" : playerUuid);
        p.addProperty("email", email == null ? "" : email);
        p.addProperty("is_op", isOp);
        p.addProperty("iat", System.currentTimeMillis() / 1000L);
        p.addProperty("exp", System.currentTimeMillis() / 1000L + Math.max(60, ttlSeconds));
        return sign(p);
    }

    /** 管理员令牌 */
    public String issueAdmin(long ttlSeconds) {
        JsonObject p = new JsonObject();
        p.addProperty("sub", "admin");
        p.addProperty("exp", System.currentTimeMillis() / 1000L + Math.max(60, ttlSeconds));
        return sign(p);
    }

    /** 校验玩家令牌，成功返回 payload，否则 null。 */
    public JsonObject verifyPlayer(String token) {
        JsonObject p = verify(token);
        return p != null && !Boolean.TRUE.equals(isExpired(p)) && "player".equals(str(p, "sub")) ? p : null;
    }

    /** 校验管理员令牌，成功返回 payload，否则 null。 */
    public JsonObject verifyAdmin(String token) {
        JsonObject p = verify(token);
        return p != null && !Boolean.TRUE.equals(isExpired(p)) && "admin".equals(str(p, "sub")) ? p : null;
    }

    /** 校验任意令牌有效性（不区分角色），失败返回 null。 */
    public JsonObject verify(String token) {
        if (token == null || token.isBlank()) return null;
        String[] parts = token.split("\\.", 3);
        if (parts.length != 3 || !VERSION.equals(parts[0])) return null;
        String signInput = parts[0] + "." + parts[1];
        try {
            byte[] expected = Base64.getUrlDecoder().decode(parts[2]);
            byte[] actual = hmac(secret.getBytes(StandardCharsets.UTF_8), signInput.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, actual)) return null;
            JsonObject payload = GSON.fromJson(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8), JsonObject.class);
            if (payload == null) return null;
            return payload;
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(JsonObject payload) {
        String body = VERSION + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        String sig = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(secret.getBytes(StandardCharsets.UTF_8), body.getBytes(StandardCharsets.UTF_8)));
        return body + "." + sig;
    }

    private static boolean isExpired(JsonObject p) {
        return p.has("exp") && p.get("exp").getAsLong() < System.currentTimeMillis() / 1000L;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }
}
