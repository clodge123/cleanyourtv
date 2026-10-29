package com.gathin.tvhome.jarvis;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

/**
 * Voice entrypoint: the system's assist pipeline lands here when the remote's
 * mic button fires (this activity is pinned as the ASSIST handler). Launching
 * through assist is what attaches the Shield remote's mic route to the process
 * — a plain activity start leaves AudioRecord(VOICE_RECOGNITION) silent.
 *
 * Invisible by design: Jarvis's face IS the listening UI.
 */
public final class JarvisVoiceActivity extends Activity {
    private static final String TAG = "Jarvis.Voice";
    private boolean done = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A 1px transparent window: keeps the task alive while capturing.
        View v = new TextView(this);
        ((TextView) v).setText("");
        v.setBackgroundColor(0x01000000);
        setContentView(v);
        getWindow().setStatusBarColor(0);
        Log.d(TAG, "voice session opened");
        JarvisManager jm = JarvisManager.instance;
        if (jm == null) {
            finish();
            return;
        }
        JarvisManager.voiceActivity = this;
        jm.beginListening();
    }

    @Override
    protected void onStop() {
        super.onStop();
        finishListening();
    }

    /** Called by JarvisManager when capture ends (auto-stop timer). */
    public void finishListening() {
        if (done) return;
        done = true;
        JarvisManager jm = JarvisManager.instance;
        if (jm != null) jm.endListening();
        finish();
    }
}
