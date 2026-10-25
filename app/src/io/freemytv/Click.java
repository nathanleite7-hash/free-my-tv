package io.freemytv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The home screen's navigation sounds, "Velvet" from the V1.6 prototype: soft sine notes (G4 to move, a C major
 * chord to open, a falling F to go back) through a warm low-pass and a short, quiet echo, so notes bloom instead of hit.
 * Rendered once into WAVs in the cache (the same graph the prototype builds with Web Audio) and played through
 * SoundPool. Only on the home screen, never inside apps.
 */
final class Click {
    private static final int RATE = 48_000;
    /** Bump when the sounds change (the cached WAVs are rendered again). */
    private static final int VERSION = 3;
    /** Web Audio's levels (0.05 for a move) scaled up to use the WAV's range; the play volume brings them back. */
    private static final float GAIN = 10f;
    /** The notes swell in over 30 ms (the prototype's 12 ms read as a tap). */
    private static final double ATTACK = 0.03;
    private SoundPool pool;
    private int move, open, back;

    Click(Context c) {
        try {
            pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            // {start Hz, end Hz, seconds, peak, delay}
            // Softer than the prototype's (asked for): no bright upper partials, a slower attack, a darker filter.
            move = pool.load(wav(c, "move", new float[][] {{392, 388, 0.16f, 0.04f, 0}}), 1);
            open = pool.load(wav(c, "open", new float[][] {{523.25f, 523.25f, 0.3f, 0.04f, 0},
                    {783.99f, 783.99f, 0.32f, 0.022f, 0.04f}, {261.63f, 261.63f, 0.26f, 0.02f, 0}}), 1);
            back = pool.load(wav(c, "back", new float[][] {{349.23f, 329.63f, 0.18f, 0.036f, 0}}), 1);
        } catch (IOException | RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
            release();
        }
    }

    void move() { play(move); }
    void open() { play(open); }
    void back() { play(back); }

    private void play(int id) {
        if (pool != null && Prefs.sounds()) pool.play(id, 0.38f, 0.38f, 1, 0, 1f);
    }

    void release() {
        if (pool != null) pool.release();
        pool = null;
    }

    /** The notes (Web Audio's exponential pitch and gain ramps), then the low-pass and the echo, as 16-bit mono. */
    private static String wav(Context c, String name, float[][] notes) throws IOException {
        File f = new File(c.getCacheDir(), "velvet-" + name + "-v" + VERSION + ".wav");
        if (f.exists() && f.length() > 44) return f.getPath();
        float end = 0;
        for (float[] n : notes) end = Math.max(end, n[4] + n[2]);
        int count = Math.round(RATE * (end + 0.45f));   // room for the echo's tail
        double[] dry = new double[count];
        for (float[] n : notes) {
            double f0 = n[0], f1 = n[1], dur = n[2], peak = n[3], at = n[4], phase = 0;
            int from = (int) Math.round(at * RATE), to = Math.min(count, (int) Math.round((at + dur + 0.05) * RATE));
            for (int i = from; i < to; i++) {
                double t = (double) (i - from) / RATE, u = Math.min(1, t / dur);
                phase += 2 * Math.PI * f0 * Math.pow(f1 / f0, u) / RATE;
                double g = t < ATTACK ? 0.0001 * Math.pow(peak / 0.0001, t / ATTACK)
                        : t < dur ? peak * Math.pow(0.0001 / peak, (t - ATTACK) / (dur - ATTACK)) : 0;
                dry[i] += Math.sin(phase) * g;
            }
        }
        // Low-pass at 1500 Hz, Q 0.4 (the RBJ biquad Web Audio uses; the prototype's was at 2400).
        double w0 = 2 * Math.PI * 1500 / RATE, alpha = Math.sin(w0) / (2 * 0.4), cos = Math.cos(w0), a0 = 1 + alpha;
        double b0 = (1 - cos) / 2 / a0, b1 = (1 - cos) / a0, b2 = b0, a1 = -2 * cos / a0, a2 = (1 - alpha) / a0;
        double[] lp = new double[count];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < count; i++) {
            double y = b0 * dry[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = dry[i]; y2 = y1; y1 = y;
            lp[i] = y;
        }
        // Echo: 85 ms delay fed back at 0.22, mixed in at 0.18.
        int d = Math.round(0.085f * RATE);
        double[] delayed = new double[count];
        for (int i = d; i < count; i++) delayed[i] = lp[i - d] + 0.22 * delayed[i - d];
        ByteBuffer b = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + count * 2).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes("US-ASCII")).putInt(count * 2);
        for (int i = 0; i < count; i++) {
            double s = Math.max(-1, Math.min(1, (lp[i] + 0.18 * delayed[i]) * GAIN));
            b.putShort((short) Math.round(s * Short.MAX_VALUE));
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
        return f.getPath();
    }
}
