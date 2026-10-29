package com.gathin.tvhome.jarvis;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 16 kHz mono PCM capture for voice. On Shield TV consoles the only live mic
 * path is the remote's, exposed through VOICE_RECOGNITION while a voice session
 * is active; MIC/CAMCORDER streams initialize and hand back digital zero. The
 * remote route attaches 1-2s after assist starts, so init is retried for it.
 */
public final class AudioCapture {
    private static final String TAG = "Jarvis.Audio";
    @Volatile private static boolean capturing = false;
    private static final ByteArrayOutputStream audio = new ByteArrayOutputStream();
    private static final Object lock = new Object();
    /** Streaming hook: PCM chunks while capturing (Talk sessions). */
    @Volatile public static java.util.function.BiConsumer<byte[], Integer> onChunk = null;

    public static boolean start() {
        synchronized (lock) {
            if (capturing) return true;
            int minBuf = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int[] sources = { MediaRecorder.AudioSource.VOICE_RECOGNITION,
                              MediaRecorder.AudioSource.MIC,
                              MediaRecorder.AudioSource.CAMCORDER };
            audio.reset();
            AudioRecord rec = null;
            int usedSource = -1;
            for (int src : sources) {
                AudioRecord candidate = null;
                int attempts = src == MediaRecorder.AudioSource.VOICE_RECOGNITION ? 6 : 1;
                for (int a = 0; a < attempts; a++) {
                    AudioRecord c;
                    try {
                        c = new AudioRecord(src, 16000, AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 4, 8192));
                    } catch (Exception e) {
                        Log.w(TAG, "AudioRecord init threw for source " + src + ": " + e);
                        break;
                    }
                    if (c.getState() == AudioRecord.STATE_INITIALIZED) { candidate = c; break; }
                    c.release();
                    Log.w(TAG, "source " + src + " not initialized (try " + (a + 1) + ")");
                    try { Thread.sleep(250); } catch (InterruptedException ie) { break; }
                }
                if (candidate == null) continue;
                int probeLimit = src == MediaRecorder.AudioSource.VOICE_RECOGNITION ? 12 : 2;
                int peak = probeSource(candidate, probeLimit);
                // Remote-mic route: accept even when silent, the stream attaches
                // after the probe window; gateway VAD filters the audio.
                if (peak > 100 || src == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                    rec = candidate;
                    usedSource = src;
                    break;
                }
                Log.w(TAG, "source " + src + " silent (peak=" + peak + "), trying next");
                try { candidate.stop(); } catch (Exception ignored) { }
                candidate.release();
                audio.reset();
            }
            final AudioRecord recorder = rec;
            if (recorder == null) return false;
            final int uSrc = usedSource;
            Log.i(TAG, "capturing live, source=" + uSrc + " bytes=" + audio.size());
            capturing = true;
            new Thread(() -> {
                byte[] buf = new byte[3200];
                int logged = 0;
                try {
                    while (capturing) {
                        int n = recorder.read(buf, 0, buf.size);
                        if (n > 0) {
                            synchronized (lock) { audio.write(buf, 0, n); }
                            java.util.function.BiConsumer<byte[], Integer> cb = onChunk;
                            if (cb != null) cb.accept(java.util.Arrays.copyOf(buf, n), n);
                            if (logged < 4) {
                                Log.i(TAG, "read n=" + n + " peak=" + peakOf(buf, n) + " bytes=" + audio.size());
                                logged++;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "capture loop error: " + e);
                }
                try { recorder.stop(); } catch (Exception ignored) { }
                recorder.release();
            }).start();
            return true;
        }
    }

    /** Streaming capture (Talk sessions): chunks via onChunk, no local buffer. */
    public static boolean startStreaming() {
        java.util.function.BiConsumer<byte[], Integer> prev = onChunk;
        onChunk = (b, n) -> {
            if (prev != null) prev.accept(b, n);
            JarvisView ampSink = JarvisManager.panel;
            if (ampSink != null) ampSink.amplitude = Math.max(0f, Math.min(1f, peakOf(b, n) / 16000f));
        };
        boolean ok = start();
        if (!ok) onChunk = prev;
        return ok;
    }

    /** Stop capture, return base64 WAV (16 kHz mono 16-bit), null if silent. */
    public static String stopAndRead() {
        synchronized (lock) { capturing = false; }
        try { Thread.sleep(250); } catch (InterruptedException ignored) { }
        byte[] pcm;
        synchronized (lock) { pcm = audio.toByteArray(); }
        if (pcm.length < 3200) return null;
        return Base64.encodeToString(wavOf(pcm, 16000), Base64.NO_WRAP);
    }

    public static void cancel() {
        synchronized (lock) { capturing = false; }
    }

    private static int peakOf(byte[] buf, int n) {
        int peak = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int s = ((buf[i + 1] & 0xFF) << 8 | (buf[i] & 0xFF));
            int v = Math.abs((short) s);
            if (v > peak) peak = v;
        }
        return peak;
    }

    private static int probeSource(AudioRecord rec, int maxReads) {
        byte[] buf = new byte[3200];
        int peak = 0;
        try {
            rec.startRecording();
            Thread.sleep(150);
            int reads = 0;
            while (reads < maxReads) {
                int n = rec.read(buf, 0, buf.size);
                if (n > 0) {
                    synchronized (lock) { audio.write(buf, 0, n); }
                    peak = Math.max(peak, peakOf(buf, n));
                    if (peak > 100) return peak;
                }
                reads++;
            }
        } catch (Exception e) {
            Log.w(TAG, "probe error: " + e);
        }
        return peak;
    }

    private static byte[] wavOf(byte[] pcm, int rate) {
        int total = pcm.length + 44;
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes());
        out.putInt(total - 8);
        out.put("WAVE".getBytes());
        out.put("fmt ".getBytes());
        out.putInt(16);
        out.putShort((short) 1);
        out.putShort((short) 1);
        out.putInt(rate);
        out.putInt(rate * 2);
        out.putShort((short) 2);
        out.putShort((short) 16);
        out.put("data".getBytes());
        out.putInt(pcm.length);
        out.put(pcm);
        return out.array();
    }
}
