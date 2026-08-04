package com.starcity.bridge.ws;

import com.google.gson.JsonObject;
import com.starcity.bridge.StarCityBridge;
import com.starcity.bridge.config.PluginConfig;
import com.starcity.bridge.module.ModuleManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 反向模式 WebSocket 服务端：插件监听端口，等待网站后端主动连接。
 * <p>基于 ServerSocket 手工实现 RFC6455（握手 + 帧编解码），无第三方依赖；
 * 消息协议与旧客户端模式完全一致（request/response/event/ping/pong）。</p>
 */
public class WsServer {

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final StarCityBridge plugin;
    private final PluginConfig config;
    private final ModuleManager modules;
    private final Map<String, CompletableFuture<Message>> pending = new ConcurrentHashMap<String, CompletableFuture<Message>>();

    private ServerSocket serverSocket;
    private volatile boolean running = false;

    public WsServer(StarCityBridge plugin, PluginConfig config, ModuleManager modules) {
        this.plugin = plugin;
        this.config = config;
        this.modules = modules;
    }

    public void start() {
        running = true;
        Thread t = new Thread(this::acceptLoop, "starcity-ws-server");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(config.serverPort());
            plugin.getLogger().info("StarCityBridge 反向模式已启动：等待网站后端连接 ws://" + config.serverHost()
                    + ":" + config.serverPort() + config.serverPath());
        } catch (IOException e) {
            plugin.getLogger().severe("监听失败（端口 " + config.serverPort() + "）: " + e.getMessage());
            return;
        }
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                Thread client = new Thread(() -> handleClient(socket), "starcity-ws-client");
                client.setDaemon(true);
                client.start();
            } catch (IOException e) {
                if (running) {
                    plugin.getLogger().warning("接受连接失败: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        OutputStream out = null;
        try {
            socket.setSoTimeout(60000);
            InputStream in = socket.getInputStream();
            out = socket.getOutputStream();
            if (!handshake(socket, in, out)) {
                socket.close();
                return;
            }
            plugin.getLogger().info("网站后端已连接（反向模式）");
            currentOutput = out;
            while (running) {
                Frame frame = readFrame(in);
                if (frame == null) {
                    break;
                }
                switch (frame.opcode) {
                    case 0x8: // close
                        return;
                    case 0x9: // ping -> pong
                        writeFrame(out, 0xA, frame.payload);
                        break;
                    case 0x1: // text
                        handleMessage(new String(frame.payload, StandardCharsets.UTF_8), out);
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            if (running) {
                plugin.getLogger().warning("客户端连接异常: " + e.getMessage());
            }
        } finally {
            if (currentOutput == out) {
                currentOutput = null;
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** HTTP 握手：校验路径与令牌，返回 101 */
    private boolean handshake(Socket socket, InputStream in, OutputStream out) throws IOException {
        String requestHead = readHead(in);
        String[] lines = requestHead.split("\r\n");
        if (lines.length < 1) {
            return false;
        }
        String requestLine = lines[0];
        String[] parts = requestLine.split(" ");
        if (parts.length < 2 || !"GET".equalsIgnoreCase(parts[0])) {
            writeHttp(out, 405, "Method Not Allowed");
            return false;
        }
        String path = parts[1];
        String queryToken = "";
        int q = path.indexOf('?');
        if (q >= 0) {
            String query = path.substring(q + 1);
            path = path.substring(0, q);
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && "token".equals(kv[0])) {
                    queryToken = URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name());
                }
            }
        }
        if (!config.serverPath().equals(path)) {
            writeHttp(out, 404, "Not Found");
            return false;
        }
        String key = null;
        String authToken = "";
        for (String line : lines) {
            int i = line.indexOf(':');
            if (i <= 0) {
                continue;
            }
            String name = line.substring(0, i).trim().toLowerCase();
            String value = line.substring(i + 1).trim();
            if ("sec-websocket-key".equals(name)) {
                key = value;
            }
            if ("authorization".equals(name) && value.startsWith("Bearer ")) {
                authToken = value.substring(7).trim();
            }
        }
        String token = queryToken.isEmpty() ? authToken : queryToken;
        if (!config.token().equals(token)) {
            writeHttp(out, 401, "Unauthorized");
            plugin.getLogger().warning("拒绝未授权连接（令牌不匹配）");
            return false;
        }
        if (key == null) {
            writeHttp(out, 400, "Bad Request");
            return false;
        }
        String accept = wsAccept(key);
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        return true;
    }

    private static String wsAccept(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest((key + WS_GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            return "";
        }
    }

    private String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1;
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            buf.write(b);
            if (prev == '\r' && b == '\n') {
                state += 2;
            } else if (b == '\n') {
                state++;
            } else if (b != '\r') {
                state = 0;
            }
            prev = b;
            if (buf.size() > 65536) {
                break;
            }
        }
        return buf.toString(StandardCharsets.UTF_8.name());
    }

    private void writeHttp(OutputStream out, int code, String message) throws IOException {
        out.write(("HTTP/1.1 " + code + " " + message + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
    // ===================== 帧编解码（RFC6455） =====================

    private static final class Frame {
        int opcode;
        byte[] payload;
    }

    private Frame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int b1 = in.read();
        if (b1 < 0) {
            return null;
        }
        Frame frame = new Frame();
        frame.opcode = b0 & 0x0f;
        int len = b1 & 0x7f;
        boolean masked = (b1 & 0x80) != 0;
        if (len == 126) {
            len = (in.read() << 8) | in.read();
        } else if (len == 127) {
            long l = 0;
            for (int i = 0; i < 8; i++) {
                l = (l << 8) | (in.read() & 0xff);
            }
            len = (int) l;
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(in, mask);
        }
        if (len < 0 || len > 16 * 1024 * 1024) {
            return frame; // 非法长度，payload 置空
        }
        frame.payload = new byte[len];
        readFully(in, frame.payload);
        if (masked) {
            for (int i = 0; i < frame.payload.length; i++) {
                frame.payload[i] ^= mask[i % 4];
            }
        }
        return frame;
    }

    private void writeFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(0x80 | opcode);
        int len = payload == null ? 0 : payload.length;
        if (len < 126) {
            buf.write(len);
        } else if (len < 65536) {
            buf.write(126);
            buf.write((len >> 8) & 0xff);
            buf.write(len & 0xff);
        } else {
            buf.write(127);
            long l = len;
            for (int i = 7; i >= 0; i--) {
                buf.write((int) ((l >> (8 * i)) & 0xff));
            }
        }
        if (len > 0) {
            buf.write(payload);
        }
        out.write(buf.toByteArray());
        out.flush();
    }

    private void readFully(InputStream in, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = in.read(target, offset, target.length - offset);
            if (read < 0) {
                throw new IOException("连接中断");
            }
            offset += read;
        }
    }

    // ===================== 消息处理 =====================

    private void handleMessage(String json, OutputStream out) {
        try {
            Message message = Message.parse(json);
            String type = message.getType();
            if ("request".equals(type)) {
                JsonObject data = modules.handleRequest(message.getModule(), message.getAction(), message.getPayload());
                if (data == null) {
                    writeFrame(out, 0x1, Message.error(message.getId(), "unsupported action").toJson().getBytes(StandardCharsets.UTF_8));
                } else {
                    writeFrame(out, 0x1, Message.response(message.getId(), data).toJson().getBytes(StandardCharsets.UTF_8));
                }
            } else if ("response".equals(type)) {
                CompletableFuture<Message> future = pending.remove(message.getId());
                if (future != null) {
                    future.complete(message);
                }
            } else if ("ping".equals(type)) {
                writeFrame(out, 0x1, Message.ping().toJson().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("消息处理失败: " + e.getMessage());
        }
    }

    // ===================== 对外接口（供 /site 命令与模块推送使用） =====================

    /** 向网站后端发送请求并等待响应 */
    public CompletableFuture<JsonObject> request(String module, String action, JsonObject payload) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<Message> future = new CompletableFuture<Message>();
        pending.put(id, future);
        Message msg = Message.request(id, module, action, payload);
        sendToClients(msg);
        return future.orTimeout(10, TimeUnit.SECONDS).thenApply(m -> {
            if (m.isOk()) {
                return m.getData();
            }
            throw new java.util.concurrent.CompletionException(new RuntimeException(
                    m.getError() == null ? "后端返回错误" : m.getError()));
        });
    }

    /** 向网站后端推送事件 */
    public void sendEvent(String module, String action, JsonObject payload) {
        sendToClients(Message.event(module, action, payload));
    }

    private void sendToClients(Message msg) {
        // 连接在 handleClient 线程内管理；为保持简单，这里记录最近一次连接并写入。
        // 由于后端为单连接，使用共享字段存储当前输出流。
        OutputStream out = currentOutput;
        if (out == null) {
            plugin.getLogger().warning("网站后端未连接，消息已丢弃: " + msg.getModule() + "/" + msg.getAction());
            return;
        }
        try {
            writeFrame(out, 0x1, msg.toJson().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("发送失败: " + e.getMessage());
        }
    }

    /** 当前已连接后端的输出流（单连接设计） */
    private volatile OutputStream currentOutput;
}