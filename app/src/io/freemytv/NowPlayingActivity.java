package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

/**
 * What's playing, full screen, in the manner of Apple Music on Apple TV: the cover large on the left (it settles back
 * a little when paused), the track on the right with its progress and the controls, all over a slowly moving field of
 * the cover's own colors. The remote: ← → choose a control, OK uses it, Play/Pause anywhere, Back closes.
 */
public final class NowPlayingActivity extends Activity {
    private NowPlayingView view;

    static void open(Context c) {
        c.startActivity(new Intent(c, NowPlayingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        view = new NowPlayingView(this, this, false);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.start();
    }

    @Override protected void onPause() {
        view.stop();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        return view.key(e) || super.dispatchKeyEvent(e);
    }

    static final class NowPlayingView extends View implements Choreographer.FrameCallback, NowPlaying.Listener {
        private static final int PREV = 0, PLAY = 1, NEXT = 2, OPEN = 3;
        private final NowPlayingActivity activity;   // null in the screensaver
        private final boolean ambient;               // the screensaver: no controls, a clock, slower drift
        private final NowPlaying np;
        private final Click click;
        private final Shadows shadows = new Shadows();
        private final Spring artScale = new Spring(0.55f, 0.82f, 1);   // Apple Music: the cover steps back when paused
        private final Spring[] focus = {new Spring(0.26f, 0.85f, 0), new Spring(0.26f, 0.85f, 1), new Spring(0.26f, 0.85f, 0), new Spring(0.26f, 0.85f, 0)};
        private final Spring[] colors = new Spring[9];   // the background's three colors, eased when the track changes
        private final RectF tmp = new RectF(), art = new RectF();
        private final Matrix matrix = new Matrix();
        private final Path icon = new Path();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), blob = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Paint eyebrow, artistPaint, albumPaint, timePaint, timeRight, buttonPaint, clockPaint;
        private TextPaint titlePaint;
        private StaticLayout titleLayout;
        private String titleFor = "";
        private Bitmap cover, blurred;
        private BitmapShader coverShader;
        private int focused = PLAY;
        private boolean running;
        private long startedAt, lastNanos, lastDraw, lastRefresh;
        private int w, h;
        private float k = 1;

        NowPlayingView(Context c, NowPlayingActivity a, boolean ambient) {
            super(c);
            activity = a;
            this.ambient = ambient;
            np = NowPlaying.get(c);
            click = ambient ? null : new Click(c);
            for (int i = 0; i < colors.length; i++) colors[i] = new Spring(1.6f, 1, 0);
            setFocusable(true);
        }

        void start() {
            running = true;
            startedAt = SystemClock.uptimeMillis();
            np.addListener(this);
            onNowPlaying();
            for (Spring s : colors) s.snap(s.target);
            Choreographer.getInstance().postFrameCallback(this);
        }

        void stop() {
            running = false;
            np.removeListener(this);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void onNowPlaying() {
            if (np.art != cover) {
                cover = np.art;
                coverShader = cover == null ? null : new BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                // A blur for free: the cover at 16 px, stretched to the screen with filtering.
                blurred = cover == null ? null : Bitmap.createScaledBitmap(cover, 16, 16, true);
            }
            for (int i = 0; i < 3; i++) {
                int c = np.palette[i];
                colors[i * 3].target = (c >> 16) & 255;
                colors[i * 3 + 1].target = (c >> 8) & 255;
                colors[i * 3 + 2].target = c & 255;
            }
            artScale.target = np.playing || !np.active() ? 1 : 0.86f;
            if (!np.active()) focused = OPEN;
            else if (focused == OPEN) focused = PLAY;
            invalidate();
        }

        @Override public void doFrame(long nanos) {
            if (!running) return;
            // 30 frames a second: the background moves slowly, the progress a pixel at a time.
            if (nanos - lastRefresh > 1_000_000_000L) {   // once a second, the song and its position from the session itself
                lastRefresh = nanos;
                np.refresh();
            }
            if (nanos - lastDraw > 30_000_000L) {
                lastDraw = nanos;
                invalidate();
            }
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
            w = width;
            h = height;
            k = w / 1920f;
            eyebrow = Ui.text(Ui.bold, 20 * k, 0x99FFFFFF);
            eyebrow.setLetterSpacing(0.08f);
            titlePaint = new TextPaint(Ui.text(Ui.displayBold, 72 * k, 0xFFFFFFFF));
            titlePaint.setLetterSpacing(-0.02f);
            artistPaint = Ui.text(Ui.medium, 38 * k, 0xC7FFFFFF);
            albumPaint = Ui.text(Ui.regular, 27 * k, 0x80FFFFFF);
            timePaint = Ui.numbers(Ui.medium, 22 * k, 0x8CFFFFFF);
            timeRight = Ui.numbers(Ui.medium, 22 * k, 0x8CFFFFFF);
            timeRight.setTextAlign(Paint.Align.RIGHT);
            buttonPaint = Ui.text(Ui.semibold, 28 * k, 0xFF1C1C1E);
            clockPaint = Ui.numbers(Ui.displaySemibold, 64 * k, 0xB3FFFFFF);
            clockPaint.setLetterSpacing(-0.03f);
            shade.setColor(0xFF000000);
            titleFor = "";
        }

        boolean key(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) {
                return e.getKeyCode() != KeyEvent.KEYCODE_BACK && e.getKeyCode() != KeyEvent.KEYCODE_HOME;
            }
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (!np.active()) return true;
                    int next = Math.max(PREV, Math.min(NEXT, focused + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
                    if (next != focused) {
                        focused = next;
                        if (click != null) click.move();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (e.getRepeatCount() > 0 || click == null) return true;
                    click.open();
                    if (focused == PREV) np.previous();
                    else if (focused == NEXT) np.next();
                    else if (focused == PLAY) np.playPause();
                    else openSpotify();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    np.playPause();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    np.next();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    np.previous();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (click != null) click.back();
                    if (activity != null) activity.finish();
                    return true;
                default:
                    return false;
            }
        }

        private void openSpotify() {
            Intent i = getContext().getPackageManager().getLaunchIntentForPackage(np.pkg != null ? np.pkg : "com.spotify.tv.android");
            if (i != null) getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 30f : Math.min(0.1f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            artScale.step(dt);
            for (int i = 0; i < focus.length; i++) {
                focus[i].target = i == focused ? 1 : 0;
                focus[i].step(dt);
            }
            for (Spring s : colors) s.step(dt);
            float t = (SystemClock.uptimeMillis() - startedAt) / 1000f;
            drawBackground(c, t);
            drawArt(c);
            drawText(c);
        }

        private int color(int i) {
            return 0xFF000000 | (clamp(colors[i * 3].value) << 16) | (clamp(colors[i * 3 + 1].value) << 8) | clamp(colors[i * 3 + 2].value);
        }

        private static int clamp(float v) {
            return Math.max(0, Math.min(255, Math.round(v)));
        }

        /**
         * A living field of the cover's colors: its blur, slowly drifting and breathing, under two large glows that
         * wander on long, unequal loops (they never line up the same way twice), then a vignette.
         */
        private void drawBackground(Canvas c, float t) {
            c.drawColor(blendBlack(color(2), 0.55f));
            if (blurred != null) {
                float s = 1.35f + 0.08f * (float) Math.sin(t * 2 * Math.PI / 47);
                float dx = w * 0.06f * (float) Math.sin(t * 2 * Math.PI / 61), dy = h * 0.05f * (float) Math.cos(t * 2 * Math.PI / 53);
                tmp.set(w / 2f - w * s / 2 + dx, h / 2f - w * s / 2 + dy, w / 2f + w * s / 2 + dx, h / 2f + w * s / 2 + dy);
                image.setAlpha(150);
                c.drawBitmap(blurred, null, tmp, image);
                image.setAlpha(255);
            }
            glow(c, color(0), w * (0.25f + 0.12f * (float) Math.sin(t * 2 * Math.PI / 37)),
                    h * (0.3f + 0.15f * (float) Math.cos(t * 2 * Math.PI / 43)), w * 0.55f, 0.55f);
            glow(c, color(1), w * (0.78f + 0.1f * (float) Math.cos(t * 2 * Math.PI / 41)),
                    h * (0.72f + 0.14f * (float) Math.sin(t * 2 * Math.PI / 59)), w * 0.6f, 0.5f);
            // Vignette: the edges fall into black, the middle keeps the color.
            blob.setShader(new RadialGradient(w * 0.5f, h * 0.5f, w * 0.75f, new int[] {0x00000000, 0x33000000, 0xB3000000},
                    new float[] {0, 0.6f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, blob);
            blob.setShader(null);
        }

        private void glow(Canvas c, int color, float x, float y, float r, float alpha) {
            int a = Math.round(255 * alpha) << 24, rgb = color & 0xFFFFFF;
            blob.setShader(new RadialGradient(x, y, r, new int[] {a | rgb, (a / 3 & 0xFF000000) | rgb, rgb}, new float[] {0, 0.5f, 1},
                    Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r, blob);
            blob.setShader(null);
        }

        private static int blendBlack(int c, float f) {
            return 0xFF000000 | (Math.round(((c >> 16) & 255) * (1 - f)) << 16) | (Math.round(((c >> 8) & 255) * (1 - f)) << 8)
                    | Math.round((c & 255) * (1 - f));
        }

        /** The cover: large, rounded, on a deep shadow; a little smaller while paused. */
        private void drawArt(Canvas c) {
            float size = 600 * k * artScale.value, cx = 520 * k, cy = 540 * k, r = 22 * k;
            art.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
            tmp.set(art);
            tmp.inset(20 * k, 0);
            tmp.offset(0, 36 * k * artScale.value);
            shade.setColor(0x8C000000);
            shadows.draw(c, tmp, r, 60 * k, shade);
            if (coverShader != null) {
                matrix.setScale(art.width() / cover.getWidth(), art.height() / cover.getHeight());
                matrix.postTranslate(art.left, art.top);
                coverShader.setLocalMatrix(matrix);
                image.setShader(coverShader);
                c.drawRoundRect(art, r, r, image);
                image.setShader(null);
            } else {
                fill.setColor(0x26FFFFFF);
                c.drawRoundRect(art, r, r, fill);
                note(c, art.centerX(), art.centerY(), art.width() * 0.22f);
            }
        }

        /** A music note, for a track without a cover. */
        private void note(Canvas c, float cx, float cy, float s) {
            fill.setColor(0x80FFFFFF);
            c.drawCircle(cx - s * 0.25f, cy + s * 0.45f, s * 0.28f, fill);
            c.drawRect(cx - s * 0.02f, cy - s * 0.6f, cx + s * 0.06f, cy + s * 0.45f, fill);
            c.drawRect(cx - s * 0.02f, cy - s * 0.6f, cx + s * 0.45f, cy - s * 0.42f, fill);
        }

        private void drawText(Canvas c) {
            float x = 960 * k, width = 820 * k;
            boolean active = np.active();
            String head = !active ? Ui.tr("Nothing playing") : np.playing ? Ui.tr("Now playing") : Ui.tr("Paused");
            if (active && !np.app.isEmpty()) head += "  ·  " + np.app;
            c.drawText(head.toUpperCase(Ui.locale()), x, 330 * k, eyebrow);
            String title = active ? np.title : Ui.tr("Nothing playing right now");
            if (!title.equals(titleFor) || titleLayout == null) {
                titleFor = title;
                titleLayout = StaticLayout.Builder.obtain(title, 0, title.length(), titlePaint, Math.round(width))
                        .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(0, 0.95f).setIncludePad(false).build();
            }
            c.save();
            c.translate(x - Ui.leftBearing(titlePaint, title), 360 * k);
            titleLayout.draw(c);
            c.restore();
            float y = 360 * k + titleLayout.getHeight() + 56 * k;
            fitText(c, active ? np.artist : Ui.tr("Play something on Spotify and it shows here."), x, y, width, artistPaint);
            if (active && !np.album.isEmpty() && !np.album.equals(np.title)) fitText(c, np.album, x, y + 46 * k, width, albumPaint);
            if (!active) {
                drawOpenButton(c, x, 720 * k);
                return;
            }
            // Progress: a thin bar, the time gone on the left, what's left on the right.
            float barY = 716 * k, bh = 8 * k;
            tmp.set(x, barY, x + width, barY + bh);
            fill.setColor(0x33FFFFFF);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            if (np.duration > 0) {
                float p = Math.min(1, np.positionNow() / (float) np.duration);
                tmp.right = x + Math.max(bh, width * p);
                fill.setColor(0xE6FFFFFF);
                c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
                c.drawText(time(np.positionNow()), x, barY + 50 * k, timePaint);
                c.drawText("-" + time(np.duration - np.positionNow()), x + width, barY + 50 * k, timeRight);
            }
            if (ambient) {   // the screensaver: the time instead of the controls
                c.drawText(Ui.clock(System.currentTimeMillis()), x, 880 * k + Ui.halfCap(clockPaint), clockPaint);
                return;
            }
            // The controls, centered under the bar.
            float cy = 880 * k, mid = x + width / 2;
            control(c, PREV, mid - 170 * k, cy, 42 * k);
            control(c, PLAY, mid, cy, 58 * k);
            control(c, NEXT, mid + 170 * k, cy, 42 * k);
        }

        private void fitText(Canvas c, String s, float x, float y, float width, Paint p) {
            c.drawText(TextUtils.ellipsize(s, new TextPaint(p), width, TextUtils.TruncateAt.END).toString(), x, y, p);
        }

        /** A round control: glass at rest; focused, white with a dark symbol, a little larger. */
        private void control(Canvas c, int which, float cx, float cy, float r) {
            float f = Ui.clamp(focus[which].value), s = 1 + 0.1f * f, rr = r * s;
            if (f > 0.01f) {
                tmp.set(cx - rr, cy - rr + 10 * k * f, cx + rr, cy + rr + 10 * k * f);
                shade.setColor(Ui.alpha(0xFF000000, 0.35f * f));
                shadows.draw(c, tmp, rr, 24 * k, shade);
            }
            fill.setColor(blend(0x29FFFFFF, 0xFFFFFFFF, f));
            c.drawCircle(cx, cy, rr, fill);
            fill.setColor(blend(0xFFFFFFFF, 0xFF1C1C1E, f));
            float u = r * 0.36f * s;
            icon.reset();
            if (which == PLAY && np.playing) {
                c.drawRoundRect(cx - u * 0.75f, cy - u, cx - u * 0.2f, cy + u, u * 0.12f, u * 0.12f, fill);
                c.drawRoundRect(cx + u * 0.2f, cy - u, cx + u * 0.75f, cy + u, u * 0.12f, u * 0.12f, fill);
                return;
            }
            if (which == PLAY) {
                triangle(cx - u * 0.6f + u * 0.15f, cy, u * 1.7f, u, false);
            } else {
                boolean back = which == PREV;
                float d = back ? -1 : 1;
                triangle(cx - d * u * 0.55f, cy, u * 1.1f, u * 0.8f, back);
                triangle(cx + d * u * 0.35f, cy, u * 1.1f, u * 0.8f, back);
            }
            c.drawPath(icon, fill);
        }

        /** A play triangle pointing right (or left), its back edge at x. */
        private void triangle(float x, float cy, float length, float half, boolean left) {
            float d = left ? -1 : 1;
            icon.moveTo(x - d * length / 2, cy - half);
            icon.lineTo(x + d * length / 2, cy);
            icon.lineTo(x - d * length / 2, cy + half);
            icon.close();
        }

        private void drawOpenButton(Canvas c, float x, float top) {
            float f = Ui.clamp(focus[OPEN].value), s = 1 + 0.06f * f;
            String label = Ui.tr("Open Spotify");
            float bw = buttonPaint.measureText(label) + 80 * k, bh = 76 * k;
            tmp.set(x, top, x + bw, top + bh);
            float cx = tmp.centerX(), cy = tmp.centerY();
            tmp.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(tmp, bh / 2 * s, bh / 2 * s, fill);
            c.drawText(label, cx - buttonPaint.measureText(label) / 2, cy + Ui.halfCap(buttonPaint), buttonPaint);
        }

        private static String time(long ms) {
            long s = Math.max(0, ms / 1000);
            return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }
    }
}
