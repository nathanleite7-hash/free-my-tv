package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The V2 screensaver (home_layout=v2): a photo full bleed, drifting slowly, a new one every 20 s crossfading over 6.5 s,
 * dimmed by Settings' brightness; the date and time small at the top right (the home's); while music plays, the song
 * as a headline at the bottom ("Letreiro"): its cover, the title, the artist and a hairline across the screen with the
 * time gone and left. The photos come from Settings' source: the picks (TMDB), Unsplash, public-domain paintings
 * (Wikimedia Commons) or none, a slow gradient drawn here.
 */
final class SaverV2 extends View implements Choreographer.FrameCallback, NowPlaying.Listener {
    private static final long SHOW_MS = 20_000, FADE_MS = 6_500, DRIFT_MS = 40_000;
    /** Unsplash's photos (city at night, aerials): hotlinked, as its guidelines ask, with the photographer's name. */
    private static final String[][] UNSPLASH = {
        {"1603793510575-a8cf24361baa", "Polina Kuzovkova"}, {"1517462035531-76bc910a6903", "Denys Nevozhai"},
        {"1620662892011-f5c2d523fae2", "Shio Yang"}, {"1620353540061-38ac5848aaef", "Shio Yang"},
        {"1493514789931-586cb221d7a7", "Andre Benz"}, {"1472146936668-d987bf0a6e38", "Peter Nguyen"},
        {"1540542134-b61d688f1441", "Lisheng Chang"}, {"1506747111041-18b1844bf60f", "Arturo Castaneyra"},
        {"1599060052009-24d6d0b0161c", "Hiep Duong"}, {"1493515322954-4fa727e97985", "Andre Benz"},
        {"1507211222203-4d522e372607", "Yiran Ding"}, {"1722153023306-a0618a3340c6", "CHE-CHI LIN"},
        {"1628850627071-42b3fb16533d", "masahiro miyagi"}, {"1642845355300-5b81d31720bf", "masahiro miyagi"},
        {"1600998837340-4887228e311f", "Hiep Duong"}, {"1551641506-ee5bf4cb45f1", "Jezael Melgoza"},
        {"1752486345389-ed3b299f34af", "Red Shuheart"}, {"1660478481785-cb609142d9ab", "ayumi kubo"},
        {"1651242069238-aa18ccb6fbcc", "ayumi kubo"},
    };

    /** A photo to show: where it comes from (a file, or an address to download) and its credit. */
    private static final class Source {
        final File file;
        final String url, credit;

        Source(File file, String url, String credit) {
            this.file = file;
            this.url = url;
            this.credit = credit;
        }
    }

    private static final class Slide {
        final Bitmap image, small;   // small: 1/24 of it, stretched back up, the blur at the bottom
        final String credit;
        long at;

        Slide(Bitmap image, String credit) {
            this.image = image;
            this.credit = credit;
            small = Bitmap.createScaledBitmap(image, Math.max(1, image.getWidth() / 24), Math.max(1, image.getHeight() / 24), true);
        }
    }

    private final Recs recs;
    private final NowPlaying np;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Source> sources = new ArrayList<>();
    private int next;
    private Slide current, incoming;
    private boolean running, loading, animated;
    private long startedAt;
    private float k = 1;
    private Paint title, artist, times, today, todayDim, credit;
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private Shader bottomShade, blurMask, topShade;
    private final Paint maskPaint = new Paint();
    private Shader[] blobs = new Shader[0];
    private final Matrix matrix = new Matrix(), blurMatrix = new Matrix();
    private final RectF r = new RectF();
    private final Path clip = new Path();
    private Bitmap coverOf;
    private BitmapShader coverShader;
    private String dateText = "", timeText = "", posText = "", leftText = "";
    private long textAt = -1;

    SaverV2(Context c) {
        super(c);
        recs = Recs.get(c);
        np = NowPlaying.get(c);
    }

    void start() {
        running = true;
        startedAt = SystemClock.uptimeMillis();
        np.addListener(this);
        np.prime();
        sources();
        load();
        Choreographer.getInstance().postFrameCallback(this);
    }

    void stop() {
        running = false;
        np.removeListener(this);
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacksAndMessages(null);
    }

    @Override public void onNowPlaying() {
        invalidate();
    }

    // MARK: - Photos

    private void sources() {
        sources.clear();
        String src = Prefs.saverSource();
        animated = "animated".equals(src);
        if (animated) return;
        if ("titles".equals(src)) {
            for (Recs.Pick p : recs.showcase()) {
                File f = recs.readyImage(p);
                if (f != null) sources.add(new Source(f, null, ""));
            }
        } else if ("aesthetic".equals(src)) {
            for (String[] u : UNSPLASH) {
                sources.add(new Source(null, "https://images.unsplash.com/photo-" + u[0] + "?w=1920&q=80&fm=jpg&fit=max",
                        Ui.trf("Photo by {name} · Unsplash", "name", u[1])));
            }
        }
        Collections.shuffle(sources);
        // Paintings: the list is fetched once (in the background) and kept
        if ("painters".equals(src)) worker.execute(() -> {
            List<Source> list = paintings();
            handler.post(() -> {
                sources.addAll(list);
                if (current == null) load();
            });
        });
    }

    /** Public-domain paintings (Wikimedia Commons, landscape ones; app/assets/paintings.json, made from Wikidata). */
    private List<Source> paintings() {
        List<Source> out = new ArrayList<>();
        try (InputStream in = getContext().getAssets().open("paintings.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bytes.write(buf, 0, n);
            JSONArray list = new JSONArray(bytes.toString("UTF-8"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject a = list.getJSONObject(i);
                out.add(new Source(null, a.getString("url"), a.optString("title") + " · " + a.optString("artist")));
            }
            Collections.shuffle(out);
        } catch (Exception e) {
            AppLog.w("paintings: " + e.getMessage());
        }
        return out;
    }

    /** A source's first photo address (Settings' preview): Unsplash's or the paintings'; null for the others. */
    static String firstUrl(Context c, String source) {
        if ("aesthetic".equals(source)) return "https://images.unsplash.com/photo-" + UNSPLASH[0][0] + "?w=1920&q=80&fm=jpg&fit=max";
        if (!"painters".equals(source)) return null;
        try (InputStream in = c.getAssets().open("paintings.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bytes.write(buf, 0, n);
            return new JSONArray(bytes.toString("UTF-8")).getJSONObject(0).getString("url");
        } catch (Exception e) {
            return null;
        }
    }

    /** The photo at `url`, downloaded once into the cache (the screensaver and Settings share it). */
    static File photoFile(Context c, String url) throws Exception {
        File f = new File(new File(c.getCacheDir(), "saver"), Integer.toHexString(url.hashCode()) + ".jpg");
        if (f.exists()) return f;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", "FreeMyTV");
        File part = new File(f.getPath() + ".part");
        try (InputStream in = h.getInputStream(); FileOutputStream o = new FileOutputStream(part)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } finally {
            h.disconnect();
        }
        //noinspection ResultOfMethodCallIgnored
        part.renameTo(f);
        return f;
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** The next photo, decoded at the screen's size in the background; it crossfades in once ready. */
    private void load() {
        if (animated || loading || sources.isEmpty() || !running) return;
        loading = true;
        final Source s = sources.get(next++ % sources.size());
        final int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        worker.execute(() -> {
            Bitmap b = null;
            try {
                File f = s.file != null ? s.file : photoFile(getContext(), s.url);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= w * 1.15f && o.outHeight / (sample * 2) >= h * 1.15f) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                b = BitmapFactory.decodeFile(f.getPath(), o);
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("screensaver photo: " + e.getMessage());
            }
            final Bitmap ready = b;
            handler.post(() -> {
                loading = false;
                if (!running) return;
                if (ready == null) {
                    handler.postDelayed(this::load, 2000);
                    return;
                }
                Slide sl = new Slide(ready, s.credit);
                sl.at = SystemClock.uptimeMillis();
                if (current == null) current = sl; else incoming = sl;
                handler.postDelayed(this::load, current == sl ? 2000 : SHOW_MS);
                invalidate();
            });
        });
    }

    // MARK: - Frames and drawing

    @Override public void doFrame(long nanos) {
        if (!running) return;
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        k = w / 1920f;
        title = Ui.text(Ui.displayBold, 72 * k, 0xFFFFFFFF);
        title.setLetterSpacing(-0.03f);
        artist = Ui.text(Ui.medium, 30 * k, 0xBFFFFFFF);
        times = Ui.numbers(Ui.semibold, 20 * k, 0x9EFFFFFF);
        today = Ui.numbers(Ui.semibold, 28 * k, 0xFFFFFFFF);
        todayDim = Ui.numbers(Ui.medium, 28 * k, 0x99FFFFFF);
        credit = Ui.text(Ui.medium, 20 * k, 0xB3FFFFFF);
        for (Paint p : new Paint[] {title, artist, today, todayDim, credit}) p.setShadowLayer(20 * k, 0, 2 * k, 0x59000000);
        blurMask = new LinearGradient(0, h * 0.5f, 0, h * 0.92f, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
        maskPaint.setShader(blurMask);
        topShade = new LinearGradient(0, 0, 0, 180 * k, 0x59000000, 0x00000000, Shader.TileMode.CLAMP);
        maskPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        bottomShade = new LinearGradient(0, h * 0.45f, 0, h, new int[] {0x00000000, 0x2E000000, 0x8C000000}, new float[] {0, 0.4f, 1}, Shader.TileMode.CLAMP);
        int[] colors = {0xFF5B3FD1, 0xFFD1467A, 0xFF1FA6A0, 0xFFE39A3B};
        blobs = new Shader[colors.length];
        for (int i = 0; i < colors.length; i++) blobs[i] = new RadialGradient(0, 0, w * 0.45f, (colors[i] & 0x00FFFFFF) | 0x9E000000, 0, Shader.TileMode.CLAMP);
        if (current == null) load();
    }

    @Override protected void onDraw(Canvas c) {
        if (title == null) return;
        long now = SystemClock.uptimeMillis();
        c.drawColor(0xFF0D0B16);
        if (animated) drawBlobs(c, now);
        if (current != null) {
            float fade = incoming == null ? 0 : Math.min(1, (now - incoming.at) / (float) FADE_MS);
            fade = fade * fade * (3 - 2 * fade);   // ease in and out
            drawSlide(c, current, now, 1);
            if (incoming != null) drawSlide(c, incoming, now, fade);
            if (incoming != null && fade >= 1) {
                current = incoming;
                incoming = null;
            }
        }
        int dim = Math.round(255 * (1 - Prefs.saverBright() / 100f));
        if (dim > 0) c.drawColor(dim << 24);
        shade.setShader(bottomShade);
        c.drawRect(0, 0, getWidth(), getHeight(), shade);
        shade.setShader(topShade);   // the credit and the clock stay readable on bright photos
        c.drawRect(0, 0, getWidth(), 180 * k, shade);
        text(now);
        float right = getWidth() - 120 * k;
        float tw = today.measureText(timeText);
        c.drawText(timeText, right - tw, 98 * k, today);
        c.drawText(dateText, right - tw - todayDim.measureText(dateText), 98 * k, todayDim);
        Slide shown = incoming != null ? incoming : current;
        if (shown != null && !shown.credit.isEmpty()) c.drawText(shown.credit, 120 * k, 98 * k, credit);
        if (np.active() && np.playing) drawMusic(c);
    }

    private void drawSlide(Canvas c, Slide s, long now, float alpha) {
        // A slow zoom and drift that never stops (the one going out doesn't snap back)
        double t = ((now - startedAt) % (2 * DRIFT_MS)) / (double) DRIFT_MS;
        float p = (float) (t <= 1 ? t : 2 - t);
        p = p * p * (3 - 2 * p);
        float zoom = 1.04f + 0.08f * p;
        float base = Math.max(getWidth() / (float) s.image.getWidth(), getHeight() / (float) s.image.getHeight()) * zoom;
        matrix.setScale(base, base);
        matrix.postTranslate((getWidth() - s.image.getWidth() * base) / 2 - getWidth() * 0.015f * p,
                (getHeight() - s.image.getHeight() * base) / 2 - getHeight() * 0.01f * p);
        image.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(s.image, matrix, image);
        image.setAlpha(255);
        // The bottom melts into a soft blur of the photo itself: its tiny copy scaled up (filtered), masked by a gradient
        if (blurMask == null) return;
        float up = s.image.getWidth() / (float) s.small.getWidth();
        blurMatrix.set(matrix);
        blurMatrix.preScale(up, up);
        int layer = c.saveLayerAlpha(0, getHeight() * 0.5f, getWidth(), getHeight(), Math.round(255 * alpha));
        c.drawBitmap(s.small, blurMatrix, image);
        c.drawRect(0, getHeight() * 0.5f, getWidth(), getHeight(), maskPaint);
        c.restoreToCount(layer);
    }

    private void drawBlobs(Canvas c, long now) {
        float w = getWidth(), h = getHeight();
        double t = (now - startedAt) / 1000.0;
        float[][] path = {{0.25f, 0.3f, 38}, {0.75f, 0.7f, 46}, {0.55f, 0.25f, 52}, {0.3f, 0.8f, 60}};
        for (int i = 0; i < blobs.length; i++) {
            double ph = Math.sin(t * 2 * Math.PI / path[i][2]);
            float x = w * (path[i][0] + 0.18f * (float) ph), y = h * (path[i][1] + 0.15f * (float) Math.cos(t * 2 * Math.PI / (path[i][2] * 1.3)));
            matrix.setTranslate(x, y);
            blobs[i].setLocalMatrix(matrix);
            fill.setShader(blobs[i]);
            c.drawRect(0, 0, w, h, fill);
        }
        fill.setShader(null);
    }

    private void drawMusic(Canvas c) {
        if (np.art != coverOf) {
            coverOf = np.art;
            coverShader = coverOf == null ? null : new BitmapShader(coverOf, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        float left = 120 * k, right = getWidth() - 120 * k, bottom = getHeight() - 168 * k;
        float cs = 160 * k;
        r.set(left, bottom - cs, left + cs, bottom);
        fill.setColor(0x73000000);
        c.drawRoundRect(r.left, r.top + 16 * k, r.right, r.bottom + 16 * k, 22 * k, 22 * k, fill);
        if (coverShader != null) {
            matrix.setScale(cs / coverOf.getWidth(), cs / coverOf.getHeight());
            matrix.postTranslate(r.left, r.top);
            coverShader.setLocalMatrix(matrix);
            fill.setShader(coverShader);
            c.drawRoundRect(r, 22 * k, 22 * k, fill);
            fill.setShader(null);
        }
        float tx = left + cs + 36 * k, tw = right - tx;
        fit(c, np.title, tx, bottom - 58 * k, tw, title);
        fit(c, np.artist, tx, bottom - 4 * k, tw, artist);
        float y = getHeight() - 112 * k;
        fill.setColor(0x38FFFFFF);
        c.drawRoundRect(left, y - 2 * k, right, y + 2 * k, 2 * k, 2 * k, fill);
        if (np.duration > 0) {
            float p = Math.min(1, np.positionNow() / (float) np.duration);
            fill.setColor(0xF2FFFFFF);
            c.drawRoundRect(left, y - 2 * k, left + (right - left) * p, y + 2 * k, 2 * k, 2 * k, fill);
            c.drawText(posText, left, y + 44 * k, times);
            c.drawText(leftText, right - times.measureText(leftText), y + 44 * k, times);
        }
    }

    private void text(long now) {
        long sec = System.currentTimeMillis() / 1000;
        if (sec == textAt) return;
        textAt = sec;
        Date d = new Date();
        String wd = new SimpleDateFormat("EEE", Ui.locale()).format(d);
        if (!wd.isEmpty()) wd = wd.substring(0, 1).toUpperCase(Ui.locale()) + wd.substring(1);
        dateText = wd + (wd.endsWith(".") ? "" : ".") + " " + new SimpleDateFormat("dd/MM", Ui.locale()).format(d) + " · ";
        timeText = new SimpleDateFormat("HH:mm", Ui.locale()).format(d);
        long pos = np.positionNow() / 1000, len = np.duration / 1000;
        posText = (pos / 60) + ":" + (pos % 60 < 10 ? "0" : "") + (pos % 60);
        long left = Math.max(0, len - pos);
        leftText = len > 0 ? "−" + (left / 60) + ":" + (left % 60 < 10 ? "0" : "") + (left % 60) : "";
        np.refresh();   // the song and its position from the player itself, once a second
    }

    private static void fit(Canvas c, String s, float x, float y, float w, Paint p) {
        if (s == null || s.isEmpty()) return;
        if (p.measureText(s) <= w) {
            c.drawText(s, x, y, p);
            return;
        }
        int n = p.breakText(s, true, w - p.measureText("…"), null);
        c.drawText(s.substring(0, Math.max(0, n)) + "…", x, y, p);
    }
}
