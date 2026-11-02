package com.phone.doctor.fm;

import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;
import android.util.Base64;

import java.io.ByteArrayOutputStream;

/** When the user invokes the assistant, grab the screen they were looking at and open the chat with it attached. */
public class AssistSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new Session(this);
    }

    static final class Session extends VoiceInteractionSession {
        private final Handler h = new Handler(Looper.getMainLooper());
        private boolean launched;
        private final Runnable fallback = new Runnable() { public void run() { launch(); } };

        Session(Context c) { super(c); }

        @Override
        public void onShow(Bundle args, int flags) {
            super.onShow(args, flags);
            launched = false;
            Core.clearAssist();
            h.postDelayed(fallback, 1200);
        }

        @Override
        public void onHandleAssist(Bundle data, AssistStructure structure, AssistContent content) {
            if (structure == null) return;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < structure.getWindowNodeCount() && sb.length() < 6000; i++)
                walk(structure.getWindowNodeAt(i).getRootViewNode(), sb, 0);
            Core.setAssist(Core.assistB64, sb.toString());
        }

        @Override
        public void onHandleScreenshot(Bitmap shot) {
            if (shot != null) {
                try {
                    int w = shot.getWidth(), hgt = shot.getHeight(), mx = Math.max(w, hgt);
                    Bitmap b = mx > 1024 ? Bitmap.createScaledBitmap(shot, w * 1024 / mx, hgt * 1024 / mx, true) : shot;
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    b.compress(Bitmap.CompressFormat.JPEG, 70, bo);
                    Core.setAssist(Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP), Core.assistText);
                } catch (Exception ignored) { }
            }
            h.removeCallbacks(fallback);
            h.postDelayed(fallback, 150);
        }

        private void walk(AssistStructure.ViewNode n, StringBuilder sb, int depth) {
            if (n == null || sb.length() > 6000 || depth > 30) return;
            CharSequence t = n.getText(), d = n.getContentDescription();
            if (t != null && t.length() > 0) sb.append(t.length() > 150 ? t.subSequence(0, 150) : t).append("\n");
            else if (d != null && d.length() > 0) sb.append("[").append(d).append("]\n");
            for (int i = 0; i < n.getChildCount(); i++) walk(n.getChildAt(i), sb, depth + 1);
        }

        private void launch() {
            if (launched) return;
            launched = true;
            Intent i = new Intent(getContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("assist", true);
            try { startAssistantActivity(i); } catch (Exception e) { getContext().startActivity(i); }
            hide();
        }
    }
}
