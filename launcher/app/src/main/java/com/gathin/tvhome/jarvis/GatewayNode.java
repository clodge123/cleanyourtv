package com.gathin.tvhome.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * OpenClaw gateway client for the launcher, in one connection per role:
 *
 *  - node role:  pairs as a TV node (commands: overlay.hero / speak / device.info),
 *                receives node.invoke.request events, answers node.invoke.result.
 *  - operator role: chat.send (voice notes -> STT on the gateway, replies come back
 *                as chat/session.message events) and Talk sessions
 *                (talk.session.create / appendAudio / close, transcript.* events).
 *
 * Protocol: websocket, connect.challenge -> signed v3 payload -> hello-ok, tokens
 * persisted for reconnect. Mirrors the ShieldClaw node app so the gateway treats
 * this launcher build as an ordinary node+operator client.
 */
public final class GatewayNode {
    private static final String TAG = "Jarvis.Gateway";
    public static final String PREF_URL = "jarvisGatewayUrl";
    public static final String PREF_BOOTSTRAP = "jarvisBootstrapToken";
    public static final String PREF_DEVICE_TOKEN = "jarvisDeviceToken";
    public static final String PREF_OP_TOKEN = "jarvisOpToken";
    /** The chat session this TV owns; voice notes land here, replies stream back. */
    public static final String SESSION_KEY = "shieldclaw-tv";

    public interface Listener {
        void onNodeConnected();
        /** A gateway command (overlay.hero, speak, ...) arrived. Reply via {@link #respond}. */
        void onInvoke(String invokeId, String command, JsonObject params);
        /** Final assistant text for the chat session. */
        void onChatReply(String text, boolean final_);
        /** Finalized user transcript from a Talk session. */
        void onTranscript(String text);
        /** Partial user transcript (live caption). */
        void onTranscriptDelta(String text);
        /** Realtime-mode agent speech (already spoken gateway-side). */
        void onAgentReply(String text, boolean final_);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Listener listener;

    // ---- node channel ----
    private WebSocket nodeWs;
    private volatile boolean nodeConnected;
    private boolean useBootstrap = true;
    private String nodeNonce;
    private long nodeTs;

    // ---- operator channel ----
    private WebSocket opWs;
    private volatile boolean opConnected;
    private String opNonce;
    private long opTs;
    private long opEpoch = 0L;

    private final Gson gson = new Gson();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicLong seq = new AtomicLong(1);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private long nodeReconnectMs = 1000L;
    private long opReconnectMs = 1000L;
    private final ConcurrentHashMap<String, JsonParser> okHandlersUnused = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.function.Consumer<JsonObject>> okHandlers =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> invokeNodes = new ConcurrentHashMap<>();
    private final ArrayList<Object[]> pendingVoice = new ArrayList<>();
    private final StringBuilder deltaText = new StringBuilder();
    private int lastSpokenHash = 0;
    private int lastDeltaHash = 0;

    // active Talk session
    volatile String talkSessionId;
    private long talkSessionEpoch = -1L;
    private volatile boolean talkAcked = false;
    private final ArrayDeque<String> audioBacklog = new ArrayDeque<>();
    private static final int BACKLOG_CAP = 80; // ~8s of 100ms frames

    private static final class ArrayDeque<E> extends java.util.ArrayDeque<E> { }

    public GatewayNode(Context ctx, SharedPreferences prefs, Listener l) {
        context = ctx;
        this.prefs = prefs;
        listener = l;
    }

    public boolean nodeConnected() { return nodeConnected; }
    public boolean opConnected() { return opConnected; }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        connectNode();
        connectOperator();
    }

    public void stop() {
        started.set(false);
        if (nodeWs != null) nodeWs.close(1000, "stop");
        if (opWs != null) opWs.close(1000, "stop");
        nodeWs = null; opWs = null;
        nodeConnected = false; opConnected = false;
    }

    // ================= node channel =================

    private void connectNode() {
        if (!started.get()) return;
        String url = prefs.getString(PREF_URL, null);
        if (url == null) return;
        nodeWs = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) { handleNodeFrame(text); }
            @Override public void onFailure(WebSocket ws, Throwable t, Response r) {
                nodeConnected = false;
                schedule(true);
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                nodeConnected = false;
                schedule(true);
            }
        });
    }

    private void schedule(boolean node) {
        if (!started.get()) return;
        if (node) {
            handler.postDelayed(() -> { nodeReconnectMs = Math.min(nodeReconnectMs * 2, 30000L); connectNode(); }, nodeReconnectMs);
        } else {
            handler.postDelayed(() -> { opReconnectMs = Math.min(opReconnectMs * 2, 30000L); connectOperator(); }, opReconnectMs);
        }
    }

    private void handleNodeFrame(String text) {
        JsonObject frame;
        try { frame = JsonParser.parseString(text).getAsJsonObject(); } catch (Exception e) { return; }
        String type = str(frame, "type");
        if ("event".equals(type)) {
            String event = str(frame, "event");
            JsonObject p = obj(frame, "payload");
            if ("connect.challenge".equals(event)) {
                nodeNonce = str(p, "nonce");
                nodeTs = p != null && p.has("ts") ? p.get("ts").getAsLong() : System.currentTimeMillis();
                sendNodeConnect();
            } else if ("node.invoke.request".equals(event)) {
                String id = str(p, "id");
                String command = str(p, "command");
                String nodeId = str(p, "nodeId");
                if (nodeId != null) invokeNodes.put(id, nodeId);
                String paramsJson = p != null && p.has("paramsJSON") && !p.get("paramsJSON").isJsonNull()
                        ? p.get("paramsJSON").getAsString() : null;
                JsonObject params = paramsJson != null ? parse(paramsJson) : new JsonObject();
                listener.onInvoke(id, command, params);
            } else if ("chat".equals(event)) {
                routeChat(p);
            }
        } else if ("res".equals(type)) {
            if (frame.has("ok") && frame.get("ok").getAsBoolean()) {
                JsonObject payload = obj(frame, "payload");
                if (payload != null && "hello-ok".equals(str(payload, "type"))) {
                    nodeConnected = true;
                    nodeReconnectMs = 1000L;
                    useBootstrap = false;
                    JsonObject auth = obj(payload, "auth");
                    if (auth != null) {
                        String dt = str(auth, "deviceToken");
                        if (dt != null) prefs.edit().putString(PREF_DEVICE_TOKEN, dt).apply();
                        JsonArray toks = auth.getAsJsonArray("deviceTokens");
                        if (toks != null) {
                            for (int i = 0; i < toks.size(); i++) {
                                JsonObject o = toks.get(i).getAsJsonObject();
                                if ("operator".equals(str(o, "role"))) {
                                    String t = str(o, "deviceToken");
                                    if (t != null) prefs.edit().putString(PREF_OP_TOKEN, t).apply();
                                }
                            }
                        }
                    }
                    listener.onNodeConnected();
                    startPresenceBeacon();
                }
            } else {
                JsonObject err = obj(frame, "error");
                JsonObject details = err != null ? obj(err, "details") : null;
                String code = details != null ? str(details, "code") : "";
                Log.w(TAG, "node res error: " + (err != null ? str(err, "message") : "?") + " code=" + code);
                if ("PAIRING_REQUIRED".equals(code)) {
                    useBootstrap = true;
                }
                schedule(true);
            }
        }
    }

    private void sendNodeConnect() {
        if (nodeNonce == null) return;
        Identity.Keys k = Identity.get(context);
        String token = prefs.getString(PREF_DEVICE_TOKEN, null);
        String signingToken = useBootstrap ? prefs.getString(PREF_BOOTSTRAP, null) : token;
        List<String> scopes = java.util.Collections.emptyList();
        String payload = Identity.buildV3Payload(k.deviceId, "openclaw-android", "node", "node",
                scopes, nodeTs, signingToken, nodeNonce, "android", "");
        String signature = Identity.sign(k, payload);
        JsonObject req = new JsonObject();
        req.addProperty("type", "req");
        req.addProperty("id", "c" + seq.getAndIncrement());
        req.addProperty("method", "connect");
        JsonObject params = new JsonObject();
        params.addProperty("minProtocol", 4);
        params.addProperty("maxProtocol", 4);
        JsonObject client = new JsonObject();
        client.addProperty("id", "openclaw-android");
        client.addProperty("version", "0.1.0");
        client.addProperty("platform", "android");
        client.addProperty("mode", "node");
        params.add("client", client);
        params.addProperty("role", "node");
        params.add("scopes", new JsonArray());
        JsonArray caps = new JsonArray();
        caps.add("voice"); caps.add("screen"); caps.add("notifications");
        params.add("caps", caps);
        JsonArray cmds = new JsonArray();
        cmds.add("overlay.hero"); cmds.add("overlay.show"); cmds.add("overlay.hide");
        cmds.add("overlay.face"); cmds.add("overlay.pip"); cmds.add("overlay.canvas");
        cmds.add("speak"); cmds.add("device.info");
        params.add("commands", cmds);
        params.add("permissions", new JsonObject());
        JsonObject auth = new JsonObject();
        if (useBootstrap) auth.addProperty("bootstrapToken", prefs.getString(PREF_BOOTSTRAP, ""));
        else if (token != null) auth.addProperty("token", token);
        params.add("auth", auth);
        params.addProperty("locale", "en-US");
        params.addProperty("userAgent", "cleanyourtv-jarvis/1.0");
        JsonObject dev = new JsonObject();
        dev.addProperty("id", k.deviceId);
        dev.addProperty("publicKey", Base64.encodeToString(k.rawPublicKey, Base64.NO_WRAP));
        dev.addProperty("signature", signature);
        dev.addProperty("signedAt", nodeTs);
        dev.addProperty("nonce", nodeNonce);
        params.add("device", dev);
        req.add("params", params);
        send(nodeWs, req);
    }

    /** Answer a node.invoke.request. Called by the listener via onInvoke. */
    public void respond(String invokeId, boolean ok, JsonObject payload, String error) {
        JsonObject res = new JsonObject();
        res.addProperty("type", "req");
        res.addProperty("id", "r" + seq.getAndIncrement());
        res.addProperty("method", "node.invoke.result");
        String nid = invokeNodes.remove(invokeId);
        params.addProperty("nodeId", nid == null ? "" : nid);
        params.addProperty("ok", ok);
        if (payload != null) res.add("payload", payload);
        if (error != null) {
            JsonObject e = new JsonObject();
            e.addProperty("message", error);
            params.add("error", e);
        }
        res.add("params", params);
        send(nodeWs, res);
    }

    private void startPresenceBeacon() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (nodeConnected && nodeWs != null) {
                    JsonObject req = new JsonObject();
                    req.addProperty("type", "req");
                    req.addProperty("id", "p" + seq.getAndIncrement());
                    req.addProperty("method", "node.presence.alive");
                    req.add("params", new JsonObject());
                    send(nodeWs, req);
                }
                handler.postDelayed(this, 30_000L);
            }
        }, 30_000L);
    }

    // ================= operator channel =================

    private void connectOperator() {
        if (!started.get()) return;
        String url = prefs.getString(PREF_URL, null);
        if (url == null) return;
        opEpoch++;
        opWs = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) { handleOpFrame(text); }
            @Override public void onFailure(WebSocket ws, Throwable t, Response r) {
                opConnected = false;
                schedule(false);
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                opConnected = false;
                schedule(false);
            }
        });
    }

    private void handleOpFrame(String text) {
        JsonObject frame;
        try { frame = JsonParser.parseString(text).getAsJsonObject(); } catch (Exception e) { return; }
        String type = str(frame, "type");
        if ("event".equals(type)) {
            String event = str(frame, "event");
            JsonObject p = obj(frame, "payload");
            if ("connect.challenge".equals(event)) {
                opNonce = str(p, "nonce");
                opTs = p != null && p.has("ts") ? p.get("ts").getAsLong() : System.currentTimeMillis();
                sendOpConnect();
            } else if ("session.message".equals(event)) {
                routeTranscriptReply(p);
            } else if ("chat".equals(event)) {
                routeChat(p);
            } else if (event != null && event.startsWith("transcript.")) {
                handleTranscript(event, p);
            }
        } else if ("res".equals(type)) {
            String id = str(frame, "id");
            if (frame.has("ok") && frame.get("ok").getAsBoolean()) {
                JsonObject payload = obj(frame, "payload");
                if (payload != null && "hello-ok".equals(str(payload, "type"))) {
                    opConnected = true;
                    opReconnectMs = 1000L;
                    subscribeSessions();
                    flushPending();
                }
                if (id != null) {
                    java.util.function.Consumer<JsonObject> h = okHandlers.remove(id);
                    if (h != null) h.accept(payload != null ? payload : new JsonObject());
                }
            } else {
                JsonObject err = obj(frame, "error");
                Log.w(TAG, "op res error: " + (err != null ? str(err, "message") : "?"));
                if (id != null) okHandlers.remove(id);
            }
        }
    }

    private void sendOpConnect() {
        if (opNonce == null) return;
        Identity.Keys k = Identity.get(context);
        String opToken = prefs.getString(PREF_OP_TOKEN, null);
        List<String> scopes = Arrays.asList("operator.read", "operator.write");
        String payload = Identity.buildV3Payload(k.deviceId, "openclaw-android", "ui", "operator",
                scopes, opTs, opToken, opNonce, "android", "");
        String signature = Identity.sign(k, payload);
        JsonObject req = new JsonObject();
        req.addProperty("type", "req");
        req.addProperty("id", "oc" + seq.getAndIncrement());
        req.addProperty("method", "connect");
        JsonObject params = new JsonObject();
        params.addProperty("minProtocol", 4);
        params.addProperty("maxProtocol", 4);
        JsonObject client = new JsonObject();
        client.addProperty("id", "openclaw-android");
        client.addProperty("version", "0.1.0");
        client.addProperty("platform", "android");
        client.addProperty("mode", "ui");
        params.add("client", client);
        params.addProperty("role", "operator");
        JsonArray sc = new JsonArray();
        for (String s : scopes) sc.add(s);
        params.add("scopes", sc);
        JsonObject auth = new JsonObject();
        if (opToken != null) auth.addProperty("token", opToken);
        params.add("auth", auth);
        params.addProperty("locale", "en-US");
        params.addProperty("userAgent", "cleanyourtv-jarvis/1.0");
        JsonObject dev = new JsonObject();
        dev.addProperty("id", k.deviceId);
        dev.addProperty("publicKey", Base64.encodeToString(k.rawPublicKey, Base64.NO_WRAP));
        dev.addProperty("signature", signature);
        dev.addProperty("signedAt", opTs);
        dev.addProperty("nonce", opNonce);
        params.add("device", dev);
        req.add("params", params);
        send(opWs, req);
    }

    private void subscribeSessions() {
        JsonObject sub = req("sessions.subscribe", new JsonObject());
        send(opWs, sub);
        JsonObject msg = req("sessions.messages.subscribe", new JsonObject());
        JsonObject params = new JsonObject();
        params.addProperty("key", SESSION_KEY);
        msg.add("params", params);
        send(opWs, msg);
    }

    /** Send a captured voice note (base64 WAV) into the TV's chat session. */
    public void sendVoiceNote(String base64Wav, String mime) {
        JsonObject r = req("chat.send", new JsonObject());
        JsonObject params = new JsonObject();
        params.addProperty("message", "");
        params.addProperty("sessionKey", SESSION_KEY);
        params.addProperty("idempotencyKey", "v" + System.currentTimeMillis() + "-" + seq.get());
        JsonArray att = new JsonArray();
        JsonObject a = new JsonObject();
        a.addProperty("type", "audio");
        a.addProperty("mimeType", mime);
        a.addProperty("content", base64Wav);
        att.add(a);
        params.add("attachments", att);
        r.add("params", params);
        if (opConnected) send(opWs, r);
        else synchronized (pendingVoice) { pendingVoice.add(new Object[]{base64Wav, mime}); }
    }

    public void sendChatText(String text) {
        JsonObject r = req("chat.send", new JsonObject());
        JsonObject params = new JsonObject();
        params.addProperty("message", text);
        params.addProperty("sessionKey", SESSION_KEY);
        params.addProperty("idempotencyKey", "m" + System.currentTimeMillis() + "-" + seq.get());
        r.add("params", params);
        if (opConnected) send(opWs, r);
    }

    private void flushPending() {
        List<Object[]> items;
        synchronized (pendingVoice) {
            items = new ArrayList<>(pendingVoice);
            pendingVoice.clear();
        }
        for (Object[] it : items) sendVoiceNote((String) it[0], (String) it[1]);
    }

    // ---------- Talk sessions ----------

    /** Open a transcription session (gateway-side whisper). */
    public boolean startTalkTranscription() {
        if (!opConnected) return false;
        JsonObject r = req("talk.session.create", new JsonObject());
        JsonObject params = new JsonObject();
        params.addProperty("mode", "transcription");
        params.addProperty("transport", "gateway-relay");
        params.addProperty("brain", "none");
        r.add("params", params);
        final long epoch = opEpoch;
        okHandlers.put(r.get("id").getAsString(), payload -> {
            talkSessionId = str(payload, "sessionId");
            talkSessionEpoch = epoch;
            talkAcked = true;
            Log.d(TAG, "talk session=" + talkSessionId);
            List<String> backlog = new ArrayList<>();
            synchronized (audioBacklog) {
                backlog.addAll(audioBacklog);
                audioBacklog.clear();
            }
            for (String b : backlog) sendAudioFrame(b);
        });
        send(opWs, r);
        return true;
    }

    public void appendAudio(String pcmBase64) {
        if (talkSessionId == null) return;
        if (talkSessionEpoch != opEpoch) {
            // Connection replaced: session died with the old socket. Re-open.
            talkSessionId = null;
            talkAcked = false;
            startTalkTranscription();
            return;
        }
        if (!talkAcked) {
            synchronized (audioBacklog) {
                audioBacklog.addLast(pcmBase64);
                while (audioBacklog.size() > BACKLOG_CAP) audioBacklog.removeFirst();
            }
            return;
        }
        sendAudioFrame(pcmBase64);
    }

    private void sendAudioFrame(String pcmBase64) {
        String sid = talkSessionId;
        if (sid == null) return;
        JsonObject r = req("talk.session.appendAudio", new JsonObject());
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sid);
        params.addProperty("audioBase64", pcmBase64);
        params.addProperty("format", "pcm16");
        params.addProperty("timestamp", System.currentTimeMillis());
        r.add("params", params);
        send(opWs, r);
    }

    public void closeTalkSession() {
        String sid = talkSessionId;
        if (sid != null && talkSessionEpoch == opEpoch) {
            JsonObject r = req("talk.session.close", new JsonObject());
            JsonObject params = new JsonObject();
            params.addProperty("sessionId", sid);
            r.add("params", params);
            send(opWs, r);
        }
        talkSessionId = null;
        talkAcked = false;
    }

    // ---------- event routing ----------

    private void routeChat(JsonObject p) {
        if (p == null) return;
        String state = str(p, "state");
        if ("delta".equals(state)) {
            String t = str(p, "text");
            if (t != null) { deltaText.append(t); listener.onAgentReply(t, false); }
        } else if ("final".equals(state)) {
            String text = extractText(p);
            String full = deltaText.length() > 0 ? deltaText + text : text;
            deltaText.setLength(0);
            if (full != null && !full.isBlank() && full.hashCode() != lastSpokenHash) {
                lastSpokenHash = full.hashCode();
                listener.onChatReply(full, true);
            }
        }
    }

    private void routeTranscriptReply(JsonObject p) {
        if (p == null) return;
        String key = str(p, "sessionKey");
        if (key != null && !key.equals(SESSION_KEY) && !key.endsWith(":" + SESSION_KEY)) return;
        JsonObject msg = obj(p, "message");
        if (msg == null || !"assistant".equals(str(msg, "role"))) return;
        var content = msg.getAsJsonArray("content");
        if (content == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.size(); i++) {
            JsonObject part = content.get(i).getAsJsonObject();
            if ("text".equals(str(part, "type"))) {
                String t = str(part, "text");
                if (t != null) sb.append(t);
            }
        }
        String full = sb.toString();
        if (!full.isBlank() && full.hashCode() != lastSpokenHash) {
            lastSpokenHash = full.hashCode();
            listener.onChatReply(full, true);
        }
    }

    private void handleTranscript(String event, JsonObject p) {
        String sid = p != null ? str(p, "sessionId") : null;
        if (sid != null && talkSessionId != null && !sid.equals(talkSessionId)) return;
        String text = p != null ? (str(p, "text") != null ? str(p, "text") : str(p, "delta")) : "";
        boolean final_ = event.endsWith(".done");
        if (text == null || text.isEmpty()) return;
        if (final_) {
            lastDeltaHash = 0;
            listener.onTranscript(text);
        } else {
            int h = text.hashCode();
            if (h != lastDeltaHash) {
                lastDeltaHash = h;
                listener.onTranscriptDelta(text);
            }
        }
    }

    private static String extractText(JsonObject p) {
        JsonObject msg = obj(p, "message");
        StringBuilder sb = new StringBuilder();
        if (msg != null) {
            var content = msg.getAsJsonArray("content");
            if (content != null) {
                for (int i = 0; i < content.size(); i++) {
                    JsonObject part = content.get(i).getAsJsonObject();
                    if ("text".equals(str(part, "type"))) {
                        String t = str(part, "text");
                        if (t != null) sb.append(t);
                    }
                }
            }
        }
        String t = sb.length() > 0 ? sb.toString() : str(p, "text");
        return t == null ? "" : t;
    }

    // ---------- wire helpers ----------

    private JsonObject req(String method, JsonObject params) {
        JsonObject r = new JsonObject();
        r.addProperty("type", "req");
        r.addProperty("id", "j" + seq.getAndIncrement());
        r.addProperty("method", method);
        r.add("params", params);
        return r;
    }

    private static void send(WebSocket ws, JsonObject obj) {
        if (ws != null) ws.send(obj.toString());
    }

    private static String str(JsonObject o, String k) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return null;
        try { return o.get(k).getAsString(); } catch (Exception e) { return null; }
    }

    private static JsonObject obj(JsonObject o, String k) {
        if (o == null || !o.has(k) || !o.get(k).isJsonObject()) return null;
        return o.getAsJsonObject(k);
    }

    private static JsonObject parse(String s) {
        try { return JsonParser.parseString(s).getAsJsonObject(); } catch (Exception e) { return new JsonObject(); }
    }
}
