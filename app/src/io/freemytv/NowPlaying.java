package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the TV is playing (Spotify first), read from Android's media sessions, and its controls. Needs MediaListener
 * allowed as a notification listener; without it, nothing is playing as far as the home screen knows.
 */
final class NowPlaying {
    interface Listener { void onNowPlaying(); }

    private static NowPlaying instance;

    static synchronized NowPlaying get(Context c) {
        if (instance == null) instance = new NowPlaying(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private MediaSessionManager sessions;
    private MediaController controller;
    private boolean started;

    // What's playing, read on the main thread.
    String title = "", artist = "", album = "", app = "";
    String pkg;
    Bitmap art;              // the cover, or null
    File artFile;            // the same, saved (the hero's background is built from a file)
    int[] palette = {0xFF2C2C2E, 0xFF1C1C1E, 0xFF3A3A3C};   // its colors: the full-screen view's background
    boolean playing;
    long duration, position, positionAt;
    float speed = 1;
    private String artFor = "";

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { read(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { read(); }
        @Override public void onSessionDestroyed() { pick(); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener changed = list -> pick();

    private NowPlaying(Context c) {
        context = c;
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        start();
    }

    /** Reads the sessions now (the screensaver decides at once whether music is playing). */
    void prime() {
        start();
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Something is playing or paused with a track on it. */
    boolean active() {
        return controller != null && !title.isEmpty();
    }

    /** Where the track is now (ms), counting from the last report. */
    long positionNow() {
        if (!playing) return position;
        long p = position + (long) ((SystemClock.elapsedRealtime() - positionAt) * speed);
        return duration > 0 ? Math.min(duration, p) : p;
    }

    void playPause() {
        if (controller == null) return;
        if (playing) controller.getTransportControls().pause(); else controller.getTransportControls().play();
    }

    void next() { if (controller != null) controller.getTransportControls().skipToNext(); }

    void previous() { if (controller != null) controller.getTransportControls().skipToPrevious(); }

    private void start() {
        if (started) return;
        started = true;
        try {
            sessions = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(context, MediaListener.class);
            sessions.addOnActiveSessionsChangedListener(changed, me, main);
            pick();
        } catch (SecurityException e) {
            // Not allowed as a notification listener (yet): no "Now playing".
            AppLog.w("now playing: media sessions not allowed");
            sessions = null;
        }
    }

    /** The session to show: a playing one (Spotify first), else a paused Spotify. */
    private void pick() {
        if (sessions == null) return;
        List<MediaController> list;
        try {
            list = sessions.getActiveSessions(new ComponentName(context, MediaListener.class));
        } catch (SecurityException e) {
            return;
        }
        MediaController best = null;
        int bestScore = -1;
        for (MediaController m : list) {
            PlaybackState s = m.getPlaybackState();
            boolean on = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            boolean spotify = m.getPackageName().startsWith("com.spotify");
            if (!on && !spotify) continue;
            int score = (on ? 2 : 0) + (spotify ? 1 : 0);
            if (score > bestScore) {
                best = m;
                bestScore = score;
            }
        }
        if (controller != null && (best == null || !controller.getSessionToken().equals(best.getSessionToken()))) {
            controller.unregisterCallback(callback);
        }
        if (best != null && (controller == null || !controller.getSessionToken().equals(best.getSessionToken()))) {
            best.registerCallback(callback, main);
        }
        controller = best;
        read();
    }

    private void read() {
        MediaController m = controller;
        MediaMetadata md = m == null ? null : m.getMetadata();
        PlaybackState s = m == null ? null : m.getPlaybackState();
        title = md == null ? "" : text(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        artist = md == null ? "" : text(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        album = md == null ? "" : text(md, MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
        duration = md == null ? 0 : Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
        pkg = m == null ? null : m.getPackageName();
        app = pkg == null ? "" : pkg.startsWith("com.spotify") ? "Spotify" : appName(pkg);
        playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        position = s == null ? 0 : Math.max(0, s.getPosition());
        positionAt = s == null ? SystemClock.elapsedRealtime() : s.getLastPositionUpdateTime();
        if (positionAt <= 0) positionAt = SystemClock.elapsedRealtime();
        speed = s == null || s.getPlaybackSpeed() <= 0 ? 1 : s.getPlaybackSpeed();
        // The cover can come after the text (a placeholder first): its address or its pixels are part of the key.
        String uri = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        Bitmap bmp = md == null ? null : md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        String key = title + "|" + artist + "|" + album + "|" + uri + "|" + (bmp == null ? 0 : bmp.getGenerationId() + "x" + bmp.getWidth());
        if (md != null && !key.equals(artFor)) {
            artFor = key;
            loadArt(md, key);
        }
        notifyListeners();
    }

    private String appName(String p) {
        try {
            return String.valueOf(context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(p, 0)));
        } catch (Exception e) {
            return "";
        }
    }

    private static String text(MediaMetadata md, String a, String b) {
        CharSequence t = md.getText(a);
        if (t == null || t.length() == 0) t = md.getText(b);
        return t == null ? "" : t.toString();
    }

    /** The cover: from the metadata, or downloaded from its address; then its colors, and a copy on disk. */
    private void loadArt(MediaMetadata md, String key) {
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        String uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        if (uri == null) uri = md.getString(MediaMetadata.METADATA_KEY_ART_URI);
        if (uri == null) uri = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        final Bitmap given = b == null ? null : b.copy(Bitmap.Config.ARGB_8888, false);
        final String address = uri;
        io.execute(() -> {
            Bitmap cover = given;
            if (cover == null && address != null && address.startsWith("http")) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    try (InputStream in = c.getInputStream()) {
                        cover = BitmapFactory.decodeStream(in);
                    } finally {
                        c.disconnect();
                    }
                } catch (Exception e) {
                    AppLog.w("now playing art: " + e.getMessage());
                }
            }
            int[] colors = cover == null ? null : colors(cover);
            File file = null;
            if (cover != null) {
                File dir = new File(context.getCacheDir(), "nowplaying");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File[] old = dir.listFiles();
                for (int i = 0; old != null && i < old.length; i++) //noinspection ResultOfMethodCallIgnored
                    old[i].delete();
                file = new File(dir, Integer.toHexString(key.hashCode()) + ".png");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    cover.compress(Bitmap.CompressFormat.PNG, 100, out);
                } catch (Exception e) {
                    file = null;
                }
            }
            final Bitmap ready = cover;
            final File saved = file;
            main.post(() -> {
                if (!key.equals(artFor)) return;
                art = ready;
                artFile = saved;
                if (colors != null) palette = colors;
                notifyListeners();
            });
        });
    }

    /**
     * Three of the cover's colors for the background: the most saturated, the most common and a darker third, all
     * kept deep enough for white text over them.
     */
    private static int[] colors(Bitmap cover) {
        Bitmap s = Bitmap.createScaledBitmap(cover, 12, 12, true);
        int[] px = new int[144];
        s.getPixels(px, 0, 12, 0, 0, 12, 12);
        if (s != cover) s.recycle();
        int vivid = px[0], common = px[0];
        float bestSat = -1, bestCount = -1;
        float[] hsv = new float[3];
        for (int p : px) {
            android.graphics.Color.colorToHSV(p, hsv);
            float sat = hsv[1] * (0.3f + hsv[2]);
            if (sat > bestSat) {
                bestSat = sat;
                vivid = p;
            }
            int count = 0;
            for (int q : px) if (Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) + Math.abs((p & 255) - (q & 255)) < 60) count++;
            if (count > bestCount) {
                bestCount = count;
                common = p;
            }
        }
        return new int[] {deep(vivid, 0.62f), deep(common, 0.5f), deep(blend(vivid, common), 0.3f)};
    }

    private static int blend(int a, int b) {
        return 0xFF000000 | ((((a >> 16) & 255) + ((b >> 16) & 255)) / 2 << 16) | ((((a >> 8) & 255) + ((b >> 8) & 255)) / 2 << 8)
                | ((a & 255) + (b & 255)) / 2;
    }

    /** At most `value` bright (HSV), so white text stays readable over it. */
    private static int deep(int c, float value) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(c, hsv);
        hsv[2] = Math.min(hsv[2], value);
        hsv[1] = Math.min(1, hsv[1] * 1.15f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onNowPlaying();
    }
}
