package com.gathin.tvhome.jarvis;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.gathin.tvhome.MainActivity;

import java.util.Locale;

/**
 * Jarvis: the launcher's native OpenClaw brain.
 *
 * Owns the gateway connection (node role for overlay.hero/speak commands,
 * operator role for chat + Talk sessions), the animated hero panel, the mic
 * capture pipeline, and TTS output. Everything lives inside the launcher
 * process — no overlay windows, no side-channel apps.
 */
public final class JarvisManager implements GatewayNode.Listener {
    private static final String TAG = "Jarvis";
    public static volatile JarvisManager instance;

    /** Panel reference for amplitude feedback (set while attached). */
    public static volatile JarvisView panel;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JarvisView view;
    private GatewayNode gateway;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean listening;
    private long listenStart;
    private int lastSpokenHash = 0;

    // hero band geometry, normalized; updated by MainActivity on layout
    private float bandX = 0.035f, bandY = 0.17f, bandW = 0.93f, bandH = 0.26f;
    private boolean bandReady = false;

    private JarvisManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
        seedFromConfigFile();
        initTts();
        connect();
    }

    /**
     * First-run provisioning: jarvis.json in the app's external files dir
     * (same folder tools/theme.sh writes into): {"gatewayUrl":"wss://...",
     * "bootstrapToken":"..."}. Mirrors the launcher's config.json overlay.
     */
    private void seedFromConfigFile() {
        if (prefs.contains(GatewayNode.PREF_URL)) return;
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            java.io.File f = dir != null ? new java.io.File(dir, "jarvis.json") : null;
            if (f == null || !f.exists()) return;
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject j = new org.json.JSONObject(text);
            SharedPreferences.Editor ed = prefs.edit();
            String url = j.optString("gatewayUrl", "");
            String boot = j.optString("bootstrapToken", "");
            if (!url.isEmpty()) ed.putString(GatewayNode.PREF_URL, url);
            if (!boot.isEmpty()) ed.putString(GatewayNode.PREF_BOOTSTRAP, boot);
            ed.apply();
            Log.i(TAG, "jarvis prefs seeded from jarvis.json");
        } catch (Exception e) {
            Log.w(TAG, "jarvis.json seed failed: " + e);
        }
    }

    public static void init(MainActivity activity, FrameLayout root) {
        if (instance != null) return;
        synchronized (JarvisManager.class) {
            if (instance != null) return;
            JarvisManager m = new JarvisManager(activity);
            instance = m;
            m.attach(activity, root);
        }
    }

    private void attach(MainActivity activity, FrameLayout root) {
        view = new JarvisView(activity);
        panel = view;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.START);
        root.addView(view, lp);
        view.setVisibility(View.INVISIBLE);
        applyBand();
    }

    private void initTts() {
        tts = new TextToSpeech(ctx, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.US);
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                Log.i(TAG, "tts ready=" + ttsReady);
            }
        });
    }

    private void connect() {
        gateway = new GatewayNode(ctx, prefs, this);
        gateway.start();
    }

    // ---------- hero panel ----------

    /** Static band geometry (normalized), anchored by MainActivity's fixed dock math. */
    public void setBand(float x, float y, float w, float h) {
        bandX = x; bandY = y; bandW = w; bandH = h;
        bandReady = true;
        applyBand();
    }

    private void applyBand() {
        JarvisView v = view;
        if (v == null || !bandReady) return;
        int sw = ctx.getResources().getDisplayMetrics().widthPixels;
        int sh = ctx.getResources().getDisplayMetrics().heightPixels;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        lp.width = (int) (bandW * sw);
        lp.height = (int) (bandH * sh);
        lp.leftMargin = (int) (bandX * sw);
        lp.topMargin = (int) (bandY * sh);
        v.setLayoutParams(lp);
    }

    public void showPanel(String mode, String title, String text, String colorHex, String state) {
        JarvisView v = view;
        if (v == null) return;
        v.setContent(mode, title, text, colorHex);
        if (state != null) v.setState(state);
        v.setVisibility(View.VISIBLE);
    }

    public void hidePanel() {
        if (view != null) view.setVisibility(View.INVISIBLE);
    }

    public void setFaceState(String state) {
        JarvisView v = view;
        if (v != null) v.setState(state);
    }

    // ---------- voice flow ----------

    /** Mic button: stream into a gateway Talk session, fall back to voice note. */
    /** The assist activity that opened this listening window, if any. */
    static volatile JarvisVoiceActivity voiceActivity;

    public void beginListening() {
        if (listening) return;
        listening = true;
        listenStart = System.currentTimeMillis();
        setFaceState(JarvisView.STATE_LISTENING);
        if (view != null && view.getVisibility() != View.VISIBLE) {
            showPanel("face", "", "Listening…", "#7FD1FF", JarvisView.STATE_LISTENING);
        }
        boolean streamed = gateway != null && gateway.opConnected()
                && gateway.startTalkTranscription()
                && AudioCapture.startStreaming();
        if (!streamed) {
            AudioCapture.cancel();
            boolean ok = AudioCapture.start();
            if (!ok) {
                listening = false;
                setFaceState(JarvisView.STATE_IDLE);
                speak("Microphone unavailable.");
            }
            scheduleAutoStop(8000);
        } else {
            scheduleAutoStop(8000);
        }
    }

    private void scheduleAutoStop(long ms) {
        handler.postDelayed(this::endListening, ms);
    }

    public void endListening() {
        if (!listening) return;
        listening = false;
        JarvisVoiceActivity va = voiceActivity;
        voiceActivity = null;
        if (va != null) handler.post(va::finishListening);
        AudioCapture.cancel();
        if (gateway != null) gateway.closeTalkSession();
        String wav = AudioCapture.stopAndRead();
        if (wav != null) {
            setFaceState(JarvisView.STATE_THINKING);
            if (view != null) showPanel("face", "", "Processing…", "#FFC866", JarvisView.STATE_THINKING);
            gateway.sendVoiceNote(wav, "audio/wav");
        } else {
            setFaceState(JarvisView.STATE_IDLE);
        }
    }

    // ---------- GatewayNode.Listener ----------

    @Override public void onNodeConnected() {
        Log.i(TAG, "gateway node channel ready");
    }

    @Override
    public void onInvoke(String invokeId, String command, com.google.gson.JsonObject params) {
        try {
            switch (command) {
                case "overlay.hero": {
                    String mode = str(params, "mode");
                    if ("hide".equals(mode)) {
                        handler.post(this::hidePanel);
                    } else {
                        String title = str(params, "title");
                        String text = str(params, "text");
                        String color = str(params, "color");
                        String state = str(params, "state");
                        double value = params.has("value") ? params.get("value").getAsDouble() : 0;
                        handler.post(() -> {
                            JarvisView v = view;
                            if (v != null) v.value = value;
                            showPanel(mode == null ? "face" : mode,
                                    title == null ? "" : title,
                                    text == null ? "" : text,
                                    color == null ? "#7FD1FF" : color, state);
                        });
                    }
                    gateway.respond(invokeId, true, new com.google.gson.JsonObject(), null);
                    break;
                }
                case "speak": {
                    String text = str(params, "text");
                    if (text != null) {
                        handler.post(() => speak(text));
                        gateway.respond(invokeId, true, new com.google.gson.JsonObject(), null);
                    } else {
                        gateway.respond(invokeId, false, null, "missing text");
                    }
                    break;
                }
                case "device.info": {
                    com.google.gson.JsonObject p = new com.google.gson.JsonObject();
                    p.addProperty("model", android.os.Build.MODEL);
                    p.addProperty("manufacturer", android.os.Build.MANUFACTURER);
                    p.addProperty("sdk", android.os.Build.VERSION.SDK_INT);
                    gateway.respond(invokeId, true, p, null);
                    break;
                }
                default:
                    gateway.respond(invokeId, false, null, "unsupported command: " + command);
            }
        } catch (Exception e) {
            gateway.respond(invokeId, false, null, String.valueOf(e.getMessage()));
        }
    }

    @Override
    public void onChatReply(String text, boolean final_) {
        if (final_ && text.hashCode() != lastSpokenHash) {
            lastSpokenHash = text.hashCode();
            handler.post(() -> {
                setFaceState(JarvisView.STATE_SPEAKING);
                if (view != null && view.getVisibility() == View.VISIBLE) {
                    showPanel("face", "JARVIS", text, "#7FD1FF", JarvisView.STATE_SPEAKING);
                }
                speak(text);
            });
        }
    }

    @Override
    public void onTranscript(String text) {
        handler.post(() -> {
            setFaceState(JarvisView.STATE_THINKING);
            if (view != null && view.getVisibility() == View.VISIBLE) {
                showPanel("face", "", text, "#7FD1FF", JarvisView.STATE_THINKING);
            }
            // Feed the transcript into the chat session so the agent answers it.
            if (gateway != null && !text.isBlank()) gateway.sendChatText(text);
        });
    }

    @Override
    public void onTranscriptDelta(String text) {
        handler.post(() -> {
            setFaceState(JarvisView.STATE_LISTENING);
            if (view != null && view.getVisibility() == View.VISIBLE) {
                showPanel("face", "", text, "#7FD1FF", JarvisView.STATE_LISTENING);
            }
        });
    }

    @Override
    public void onAgentReply(String text, boolean final_) {
        handler.post(() -> {
            setFaceState(JarvisView.STATE_SPEAKING);
            if (view != null && view.getVisibility() == View.VISIBLE) {
                showPanel("face", "JARVIS", text, "#7FD1FF", JarvisView.STATE_SPEAKING);
            }
        });
    }

    // ---------- TTS ----------

    public void speak(String text) {
        if (!ttsReady || tts == null) return;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis");
    }

    public void shutdown() {
        if (gateway != null) gateway.stop();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        panel = null;
        instance = null;
    }

    private static String str(com.google.gson.JsonObject o, String k) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return null;
        try { return o.get(k).getAsString(); } catch (Exception e) { return null; }
    }
}
