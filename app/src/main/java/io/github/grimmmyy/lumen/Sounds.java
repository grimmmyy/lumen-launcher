package io.github.grimmmyy.lumen;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Optional interface sounds (Layout > Sounds, off by default): a soft tick when
 * focus moves and a brief pop on select. Synthesized once into two tiny WAV
 * files, so there are no audio assets. Skipped while a direction key is held.
 * (Off by default because TVs often delay HDMI audio, which makes UI sounds lag.)
 */
final class Sounds {
    private static final int RATE = 44_100;
    private static volatile boolean enabled;
    private static SoundPool pool;
    private static int tick, pop;
    private static long lastTick;

    private Sounds() {}

    static void setEnabled(Context ctx, boolean on) {
        enabled = on;
        if (!on || pool != null) return;
        Context app = ctx.getApplicationContext();
        LumenApp.io().execute(() -> {
            try {
                File dir = new File(app.getCacheDir(), "sounds");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File t = new File(dir, "tick_v1.wav");
                File p = new File(dir, "pop_v1.wav");
                if (!t.isFile()) write(t, tone(2200, 2200, 0.018, 0.35));
                if (!p.isFile()) write(p, tone(880, 1320, 0.06, 0.45));
                SoundPool sp = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
                tick = sp.load(t.getPath(), 1);
                pop = sp.load(p.getPath(), 1);
                pool = sp;
            } catch (IOException | RuntimeException ignored) {
                // no sounds then
            }
        });
    }

    static void focus() {
        SoundPool sp = pool;
        if (!enabled || sp == null || Motion.fast()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTick < 60) return;
        lastTick = now;
        sp.play(tick, 0.18f, 0.18f, 0, 0, 1f);
    }

    static void select() {
        SoundPool sp = pool;
        if (enabled && sp != null) sp.play(pop, 0.25f, 0.25f, 1, 0, 1f);
    }

    /** A sine sweep with a fast attack and exponential decay, as 16-bit PCM. */
    private static short[] tone(double f0, double f1, double seconds, double amp) {
        int n = (int) (RATE * seconds);
        short[] s = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            double f = f0 + (f1 - f0) * t;
            phase += 2 * Math.PI * f / RATE;
            double env = Math.min(1, i / (RATE * 0.002)) * Math.exp(-5 * t);
            s[i] = (short) (Math.sin(phase) * env * amp * Short.MAX_VALUE);
        }
        return s;
    }

    private static void write(File f, short[] pcm) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length * 2).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(pcm.length * 2);
        for (short v : pcm) b.putShort(v);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
    }
}
