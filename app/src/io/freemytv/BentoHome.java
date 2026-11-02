package io.freemytv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
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
import android.provider.Settings;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The V2 home screen (config.properties home_layout=v2): a greeting and the date at the top, a bento of widgets
 * (tonight's pick with Surprise me, what's playing with its controls, the weather, the dollar), the apps right under it,
 * then Spotify's playlists and Netflix's Top 10s; the background is tonight's pick, blurred and dark. The Gaveta (the
 * TV's shortcuts as a bento) opens under its button at the top; All Apps from it. Drawn on one canvas in 1920×1080
 * units scaled to the screen.
 *
 * Tonight's pick changes each time the home screen comes back and every 5 minutes, never while its card has the focus
 * or shows a drawn title. Surprise me draws from Netflix's picks: the photo flicks through them, slowing down, and lands.
 */
final class BentoHome extends View implements Choreographer.FrameCallback, Recs.Listener, HomeData.Listener,
        NowPlaying.Listener, Rows.Listener {
    // The grid (1920×1080): tonight at the left, music (wide) then the weather and the dollar at the right, the apps under.
    private static final float GX = 80, GY = 160, GAP = 24, RW = 696, LW = 1040, GH = 688, TOPH = 330;
    private static final float RX = GX + RW + GAP, BOTH = GH - TOPH - GAP, HALF = (LW - GAP) / 2;
    private static final float APPS_TOP = 872, APP_W = 256, APP_H = 144, R = 44;
    private static final int APPS_VISIBLE = 7;
    // The rows under the apps: a title, then the cards 48 apart (room for the 1.11× focus); the focused row's title
    // stops where the bento starts (y 160).
    private static final float ROWS_TOP = APPS_TOP + APP_H + 110, ROW_T2C = 56, ROW_GAP = 48, ROW_AIR = 110, CAPS = 92;
    private static final float SQUARE = 300, TOP_W = 380, TOP_H = 214, TOP_NUM = 230, TOP_TUCK = 12;
    private static final int ROW_MAX = 20;
    private static final long TONIGHT_EVERY = 5 * 60_000L, SUR_BACK = 5000;
    // Focus: the bar's buttons, the bento's (Surprise me, then the music's three), an app, a row's card.
    private static final int Z_TOP = 0, Z_BENTO = 1, Z_APPS = 2, Z_ROWS = 3;
    private static final int SUR = 0, PREV = 1, PLAY = 2, NEXT = 3;
    private static final int CTRL = 0, GEAR = 1;
    // The Gaveta: a 4-column bento of 172 px modules under its button.
    private static final float D_CELL = 172, D_GAP = 20, D_PAD = 36, D_HEAD = 66, D_W = 4 * D_CELL + 3 * D_GAP + 2 * D_PAD;
    private static final float D_X = 1920 - 80 - D_W, D_Y = 132, D_H = D_HEAD + 4 * D_CELL + 3 * D_GAP + 2 * D_PAD;
    // All Apps: 5 columns of 300×169 tiles.
    private static final int G_COLS = 5, G_ROWS = 4;
    private static final float G_W = 300, G_H = 169, G_GAP = 40, G_ROW = 239, G_TOP = 200, G_X = (1920 - (G_COLS * G_W + (G_COLS - 1) * G_GAP)) / 2;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    private final Activity activity;
    private final Recs recs;
    private final HomeData data;
    private final NowPlaying np;
    private final Rows rowsData;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private float k = 1;
    private boolean running;
    private long lastNanos;

    private int zone = Z_BENTO, bento = PLAY, top = GEAR, app, row, col;
    private int appFirst;
    private final Spring[] bentoFocus = {new Spring(0.38f, 1, 0), new Spring(0.3f, 0.8f, 0), new Spring(0.3f, 0.8f, 0), new Spring(0.3f, 0.8f, 0)};
    private final Spring[] topFocus = {new Spring(0.3f, 0.85f, 0), new Spring(0.3f, 0.85f, 0)};
    private Spring[] appFocus = new Spring[0];
    private final Spring press = new Spring(0.12f, 1, 0);
    /** The page's scroll (1920-wide px): the rows come up under the greeting. Critically damped, a screen's travel. */
    private final Spring scroll = new Spring(0.62f, 1, 0);
    private List<HomeApps.Item> apps = new ArrayList<>();

    // Tonight's pick, and Surprise me
    private Recs.Pick pick, first;
    private boolean drawing, drawn;
    private long tonightAt;
    private float drawDelay;
    private final Runnable drawStep = this::drawStep;
    private final Runnable surBack = () -> { surLabel = false; invalidate(); };
    private boolean surLabel;   // "Draw another" for a few seconds after a draw lands
    private final Spring land = new Spring(0.6f, 1, 1);
    private Bitmap bgBlur;      // tonight's photo at 48×27: stretched over the screen, the blurred background
    private Object bgFor;

    // Rows: Spotify's playlists, then Netflix's Top 10s
    private final List<Rows.Row> rows = new ArrayList<>();
    private final List<float[]> rowX = new ArrayList<>();   // each card's x in its row (1920 px, before the row's shift)
    private final List<Spring[]> rowFocus = new ArrayList<>();
    private final List<Spring> rowShift = new ArrayList<>();
    private float[] rowY = new float[0];
    private float[] rowEnd = new float[0];

    // The Gaveta
    private static final String[] D_KEY = {"mode", "wifi", "bt", "audio", "saver", "pic", "snd", "apps", "home", "tv"};
    private static final int[][] D_AREA = {{0, 0, 2, 2}, {2, 0, 1, 1}, {3, 0, 1, 1}, {2, 1, 2, 1}, {0, 2, 2, 1}, {2, 2, 1, 1}, {3, 2, 1, 1},
            {0, 3, 1, 1}, {1, 3, 1, 1}, {2, 3, 2, 1}};
    private static final String[] D_SYM = {"picmode", "wifi", "bluetooth", "audioout", "saver", "sunny", "speaker", "apps", "settings", "tv_settings"};
    private static final int[] D_ACCENT = {0xFFFF9F0A, 0xFF0A84FF, 0xFF0A84FF, 0xFFFF375F, 0xFFFFFFFF, 0xFFFFD60A, 0xFFFF375F, 0xFFBF5AF2,
            0xFF8E8E93, 0xFF8E8E93};
    private String[] dName, dSub;
    private final RectF[] dRects = new RectF[D_KEY.length];
    private final Spring[] dFocus = new Spring[D_KEY.length];
    private final Spring dOpen = new Spring(0.45f, 1, 0);
    private final Shader[] dGlow = new Shader[D_KEY.length];
    private boolean drawerOpen;
    private int dIndex;
    private final Map<String, HomeApps.Item> quick = new HashMap<>();

    // All Apps
    private List<HomeApps.Item> others = new ArrayList<>();
    private final Spring gOpen = new Spring(0.45f, 1, 0), gScroll = new Spring(0.4f, 1, 0);
    private Spring[] gFocus = new Spring[0];
    private boolean sheetOpen;
    private int gIndex;

    // The shelf's marks (the brand's symbol, white on transparent; drawn in its color) and colors
    private static final Map<String, Object[]> BRAND = new HashMap<>();   // key → {mark file, color, tint, size (1080p px)}
    static {
        BRAND.put("netflix", new Object[] {"netflix", 0xFFE50914, 0xFFE50914, 64f});
        BRAND.put("spotify", new Object[] {"spotify", 0xFF1ED760, 0xFF1ED760, 64f});
        BRAND.put("youtube", new Object[] {"youtube", 0xFFFF0033, 0xFFFF0033, 72f});
        BRAND.put("stremio", new Object[] {"stremio", 0xFF8B5CF6, 0xFF7B5BF5, 64f});
        BRAND.put("prime", new Object[] {"primevideo", 0xFF1A98FF, 0xFF1A98FF, 124f});
        BRAND.put("disney", new Object[] {"disneyplus", 0xFFF2F6FF, 0xFF1E50E6, 124f});
        BRAND.put("max", new Object[] {"hbo", 0xFFFFFFFF, 0xFF2846FF, 96f});
        BRAND.put("appletv", new Object[] {"apple", 0xFFF5F5F7, 0xFFA0A0AA, 58f});
    }
    private final Map<String, Bitmap> marks = new HashMap<>();
    private final Map<String, Shader> appGlow = new HashMap<>();

    // Paints and cached text (made when the data changes, never in onDraw)
    private Paint greet, greetDim, today, todayDim, label, title, meta, small, big, bigLight, feel, chg, pill, pillDim;
    private Paint rowTitle, rowSub, cardTitle, cardMeta, rank, rankOn, modName, modSub, modBig, sheetTitle, gLabel;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private String greetText = "", nameText = "", dateText = "", timeText = "", tempText = "", feelText = "", sayText = "", dollarText = "", changeText = "";
    private String songTitle = "", songArtist = "", posText = "", leftText = "", pickTitle = "", pickMeta = "", pickService = "";
    private boolean dollarUp;
    private long textMinute = -1, textSecond = -1;
    private final RectF r = new RectF(), tmp = new RectF(), tonightRect = new RectF(), musicRect = new RectF(), weatherRect = new RectF(), dollarRect = new RectF();
    private final RectF surRect = new RectF(), coverRect = new RectF(), panel = new RectF();
    private final RectF[] topRects = {new RectF(), new RectF()};
    private final RectF[] ctlRects = {new RectF(), new RectF(), new RectF()};
    private final Path clip = new Path(), glyph = new Path();
    private final Matrix matrix = new Matrix();
    private Shader tonightShade, musicShade, weatherGlow, upGlow, downGlow, barScrim;
    private Bitmap coverOf, coverBlur;   // the cover, and it at 16 px: stretched over the widget, a blur for free
    private BitmapShader coverShader;

    BentoHome(Activity a) {
        super(a);
        activity = a;
        recs = Recs.get(a);
        data = HomeData.get(a);
        np = NowPlaying.get(a);
        rowsData = Rows.get(a);
        click = new Click(a);
        setFocusable(true);
        for (int i = 0; i < D_KEY.length; i++) {
            dRects[i] = new RectF();
            dFocus[i] = new Spring(0.3f, 0.85f, 0);
        }
        for (HomeApps.Item it : HomeApps.quickSettings(a)) quick.put(it.key, it);
        loadMarks();
        setApps(HomeApps.shelf(a, false));
        setRows();
    }

    // MARK: - Lifecycle

    void resume() {
        running = true;
        recs.addListener(this);
        data.addListener(this);
        np.addListener(this);
        rowsData.addListener(this);
        recs.refreshIfStale();
        data.refreshIfStale();
        rowsData.refreshIfStale();
        newTonight(true);   // the TV came back: a new pick
        handler.post(tick);
        final android.content.Context c = activity;
        LOADER.execute(() -> {   // the apps with their logos, and All Apps' list, off the main thread
            final List<HomeApps.Item> s = HomeApps.shelf(c, true), o = HomeApps.others(c);
            handler.post(() -> {
                setApps(s);
                if (!sheetOpen) setOthers(o);
                invalidate();
            });
        });
        Choreographer.getInstance().postFrameCallback(this);
    }

    void pause() {
        running = false;
        recs.removeListener(this);
        data.removeListener(this);
        np.removeListener(this);
        rowsData.removeListener(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(drawStep);
        drawing = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    /** Home pressed on the home screen: back to the entry state. */
    void reset() {
        zone = Z_BENTO;
        bento = PLAY;
        appFirst = 0;
        drawerOpen = sheetOpen = false;
        scroll.target = 0;
        invalidate();
    }

    /** Every second: the clock, the song's position (read from the player too), the 5-minute pick. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            np.refresh();
            if (System.currentTimeMillis() - tonightAt >= TONIGHT_EVERY) newTonight(false);
            updateText();
            invalidate();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onRecs() {
        if (pick == null) newTonight(true);
        updateText();
        invalidate();
    }

    @Override public void onData() {
        updateText();
        invalidate();
    }

    @Override public void onNowPlaying() {
        updateText();
        invalidate();
    }

    @Override public void onRows() {
        setRows();
        invalidate();
    }

    private void setApps(List<HomeApps.Item> list) {
        apps = list;
        appFocus = new Spring[list.size()];
        for (int i = 0; i < appFocus.length; i++) appFocus[i] = new Spring(0.38f, 0.82f, 0);
        app = Math.min(app, Math.max(0, list.size() - 1));
        glows();
    }

    private void setOthers(List<HomeApps.Item> list) {
        List<HomeApps.Item> all = new ArrayList<>(apps);   // the shelf's apps first, then the others
        all.addAll(list);
        others = all;
        gFocus = new Spring[all.size()];
        for (int i = 0; i < gFocus.length; i++) gFocus[i] = new Spring(0.32f, 0.82f, 0);
        gIndex = Math.min(gIndex, Math.max(0, all.size() - 1));
    }

    /** The playlists' row and Netflix's Top 10s, laid out (each card's x, each row's y). */
    private void setRows() {
        rows.clear();
        for (Rows.Row rw : rowsData.rows()) if (rw.square && !rw.items.isEmpty()) rows.add(rw);
        for (Rows.Row rw : rowsData.rows()) if (rw.ranked && !rw.items.isEmpty()) rows.add(rw);
        rowX.clear();
        rowY = new float[rows.size()];
        rowEnd = new float[rows.size()];
        float y = ROWS_TOP;
        for (int i = 0; i < rows.size(); i++) {
            Rows.Row rw = rows.get(i);
            int n = Math.min(ROW_MAX, rw.items.size());
            float[] xs = new float[n];
            float x = 0;
            for (int j = 0; j < n; j++) {
                if (rw.ranked) {
                    xs[j] = x;
                    x += numberWidth(rw.items.get(j).rank) - TOP_TUCK + TOP_W + ROW_GAP;
                } else {
                    xs[j] = x;
                    x += SQUARE + ROW_GAP;
                }
            }
            rowX.add(xs);
            rowEnd[i] = x - ROW_GAP;
            rowY[i] = y;
            y += ROW_T2C + (rw.ranked ? TOP_H : SQUARE + CAPS) + ROW_AIR;
            if (rowFocus.size() <= i) {
                rowFocus.add(new Spring[0]);
                rowShift.add(new Spring(0.42f, 1, 0));
            }
            if (rowFocus.get(i).length != n) {
                Spring[] s = new Spring[n];
                for (int j = 0; j < n; j++) s[j] = new Spring(0.34f, 0.78f, 0);
                rowFocus.set(i, s);
            }
        }
        if (row >= rows.size()) row = Math.max(0, rows.size() - 1);
        if (zone == Z_ROWS && rows.isEmpty()) zone = Z_APPS;
    }

    private float numberWidth(int n) {
        Paint p = rank != null ? rank : Ui.text(Ui.displayHeavy, TOP_NUM, 0);
        float w = p.measureText(String.valueOf(n)) / (rank != null ? k : 1);
        return w;
    }

    private void loadMarks() {
        for (Map.Entry<String, Object[]> e : BRAND.entrySet()) {
            try (InputStream in = activity.getAssets().open("marks/" + e.getValue()[0] + ".png")) {
                Bitmap b = BitmapFactory.decodeStream(in);
                if (b != null) marks.put(e.getKey(), b.extractAlpha());
            } catch (Exception ignored) {
                // a build without the marks (local/): the app's own logo
            }
        }
    }

    private void glows() {
        appGlow.clear();
        for (HomeApps.Item it : apps) {
            Object[] b = BRAND.get(it.key);
            int c = b != null ? (int) b[2] : HomeView.BRAND_DEEP.containsKey(it.key) ? HomeView.BRAND_DEEP.get(it.key)[0] : 0;
            if (c == 0) continue;
            appGlow.put(it.key, new RadialGradient(0, APP_H * 0.65f * k, APP_W * 0.75f * k, (c & 0x00FFFFFF) | 0x6B000000, 0x00000000, Shader.TileMode.CLAMP));
        }
    }

    // MARK: - Tonight's pick and Surprise me

    /** A new pick for tonight's card, unless it has the focus or shows a drawn title (`force`: the TV came back). */
    private void newTonight(boolean force) {
        if (!force && (drawn || drawing || (zone == Z_BENTO && bento == SUR))) return;
        List<Recs.Pick> list = recs.now();
        if (list.isEmpty()) return;
        Recs.Pick p;
        int tries = 0;
        do { p = list.get(random.nextInt(list.size())); } while (p == pick && list.size() > 1 && ++tries < 8);
        pick = first = p;
        drawn = false;
        tonightAt = System.currentTimeMillis();
        recs.backdrop(pick);
        updateText();
    }

    /** Netflix's titles among the picks (all of them when none is). */
    private List<Recs.Pick> netflix() {
        List<Recs.Pick> out = new ArrayList<>();
        for (Recs.Pick p : recs.wanted()) if ("netflix".equals(p.app)) out.add(p);
        return out.isEmpty() ? recs.wanted() : out;
    }

    private void startDraw() {
        if (drawing) return;
        drawing = true;
        surLabel = false;
        handler.removeCallbacks(surBack);
        drawDelay = 55;
        drawStep();
    }

    private void drawStep() {
        List<Recs.Pick> pool = netflix();
        if (pool.isEmpty()) {
            drawing = false;
            return;
        }
        Recs.Pick p;
        int tries = 0;
        do { p = pool.get(random.nextInt(pool.size())); } while (p == pick && pool.size() > 1 && ++tries < 8);
        pick = p;
        recs.backdrop(p);
        click.move();
        updateText();
        drawDelay *= 1.2f;
        if (drawDelay < 460) {
            handler.postDelayed(drawStep, (long) drawDelay);
        } else {
            drawing = false;
            drawn = true;
            land.value = 1.05f;   // lands with a small settle
            land.target = 1;
            click.open();
            surLabel = true;
            handler.postDelayed(surBack, SUR_BACK);
        }
        invalidate();
    }

    /** Back on the card: tonight's pick again. */
    private boolean resetDraw() {
        if (!drawn && !drawing) return false;
        handler.removeCallbacks(drawStep);
        handler.removeCallbacks(surBack);
        drawing = drawn = surLabel = false;
        pick = first;
        updateText();
        invalidate();
        return true;
    }

    // MARK: - Text, made when something changes

    private void paints() {
        if (greet != null && greet.getTextSize() == 40 * k) return;
        greet = Ui.text(Ui.semibold, 40 * k, 0xFFFFFFFF);
        greetDim = Ui.text(Ui.medium, 40 * k, 0x99FFFFFF);
        today = Ui.numbers(Ui.semibold, 28 * k, 0xFFFFFFFF);
        todayDim = Ui.numbers(Ui.medium, 28 * k, 0x99FFFFFF);
        label = Ui.text(Ui.bold, 28 * k, 0xFFFFFFFF);
        title = Ui.text(Ui.bold, 34 * k, 0xFFFFFFFF);
        meta = Ui.text(Ui.medium, 24 * k, 0xB3FFFFFF);
        small = Ui.numbers(Ui.semibold, 18 * k, 0x8CFFFFFF);
        big = Ui.text(Ui.bold, 48 * k, 0xFFFFFFFF);
        bigLight = Ui.numbers(Ui.light, 96 * k, 0xFFFFFFFF);
        bigLight.setLetterSpacing(-0.04f);
        feel = Ui.text(Ui.semibold, 22 * k, 0xFFA5A3FF);
        chg = Ui.numbers(Ui.bold, 22 * k, 0xFFFF453A);
        pill = Ui.text(Ui.bold, 28 * k, 0xFF1C1C1E);
        pillDim = Ui.text(Ui.medium, 20 * k, 0xA61C1C1E);
        rowTitle = Ui.text(Ui.bold, 30 * k, 0xFFFFFFFF);
        rowSub = Ui.text(Ui.medium, 22 * k, 0x8CEBEBF5);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xFFFFFFFF);
        cardMeta = Ui.text(Ui.medium, 20 * k, 0x99EBEBF5);
        rank = Ui.text(Ui.displayHeavy, TOP_NUM * k, 0x29FFFFFF);
        rank.setLetterSpacing(-0.06f);
        rankOn = Ui.text(Ui.displayHeavy, TOP_NUM * k, 0xEBFFFFFF);
        rankOn.setLetterSpacing(-0.06f);
        modName = Ui.text(Ui.semibold, 21 * k, 0xFFFFFFFF);
        modSub = Ui.text(Ui.medium, 18 * k, 0x99EBEBF5);
        modBig = Ui.text(Ui.bold, 34 * k, 0xFFFFFFFF);
        sheetTitle = Ui.text(Ui.bold, 56 * k, 0xFFFFFFFF);
        gLabel = Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF);
        gLabel.setTextAlign(Paint.Align.CENTER);
        dName = new String[] {Ui.tr("Picture mode"), "Wi-Fi", "Bluetooth", Ui.tr("Audio output"), Ui.tr("Screensaver"), Ui.tr("Picture"),
                Ui.tr("Sound"), Ui.tr("short|Apps"), Ui.tr("short|Home"), Ui.tr("TV Settings")};
        dSub = new String[] {Ui.tr("Movie, standard, game…"), Ui.tr("Network"), "", Ui.tr("Speakers or headphones"), Ui.tr("Show now"),
                "", "", Ui.tr("short|All of them"), Ui.tr("Settings"), "Google TV"};
    }

    private void updateText() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute != textMinute) {
            textMinute = minute;
            Date d = new Date(now);
            int h = Integer.parseInt(new SimpleDateFormat("H", Ui.locale()).format(d));
            greetText = (h >= 5 && h < 12 ? Ui.tr("Good morning") : h >= 12 && h < 18 ? Ui.tr("Good afternoon") : Ui.tr("Good evening")) + ", ";
            nameText = Config.GREETING_NAME.isEmpty() ? Ui.tr("everyone") : Config.GREETING_NAME;
            String wd = new SimpleDateFormat("EEE", Ui.locale()).format(d);
            if (!wd.isEmpty()) wd = wd.substring(0, 1).toUpperCase(Ui.locale()) + wd.substring(1);
            dateText = wd + (wd.endsWith(".") ? "" : ".") + " " + new SimpleDateFormat("dd/MM", Ui.locale()).format(d) + " · ";
            timeText = new SimpleDateFormat("HH:mm", Ui.locale()).format(d);
        }
        HomeData.Weather w = data.weather;
        tempText = Double.isNaN(w.temp) ? "--°" : Math.round(w.temp) + "°";
        feelText = Double.isNaN(w.min) ? "" : Ui.trf("Low {t}°", "t", Math.round(w.min));
        sayText = w.code < 0 ? "" : HomeData.condition(w.code, w.isDay);
        double usd = data.prices[3], ch = data.changes[3];
        dollarText = Double.isNaN(usd) ? "R$ --" : String.format(Ui.locale(), "R$ %.2f", usd);
        dollarUp = !Double.isNaN(ch) && ch >= 0;
        changeText = Double.isNaN(ch) ? "" : (dollarUp ? "▲ " : "▼ ") + String.format(Ui.locale(), "%.1f%%", Math.abs(ch)) + " " + Ui.tr("today");
        if (np.active()) {
            songTitle = np.title;
            songArtist = np.artist;
        } else {
            songTitle = Ui.tr("Nothing playing right now");
            songArtist = "Spotify";
        }
        long pos = np.positionNow() / 1000, len = np.duration / 1000;
        if (pos != textSecond) {
            textSecond = pos;
            posText = time(pos);
            leftText = len > 0 ? "−" + time(Math.max(0, len - pos)) : "";
        }
        if (pick != null) {
            pickTitle = pick.title == null ? "" : pick.title;
            pickMeta = pick.meta == null ? "" : pick.meta;
            pickService = Recs.serviceName(pick);
            Backdrop b = recs.backdrop(first != null ? first : pick);
            if (b != null && b.image != null && bgFor != b.image) {   // the background: tonight's pick, not the draw's flicker
                bgFor = b.image;
                bgBlur = Bitmap.createScaledBitmap(b.image, 48, 27, true);
            }
        }
        if (np.art != coverOf) {
            coverOf = np.art;
            coverShader = coverOf == null ? null : new BitmapShader(coverOf, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            coverBlur = coverOf == null ? null : Bitmap.createScaledBitmap(coverOf, 16, 16, true);
        }
    }

    private static String time(long s) {
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    // MARK: - Layout

    @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
        k = width / 1920f;
        greet = null;
        paints();
        set(tonightRect, GX, GY, RW, GH);
        set(musicRect, RX, GY, LW, TOPH);
        set(weatherRect, RX, GY + TOPH + GAP, HALF, BOTH);
        set(dollarRect, RX + HALF + GAP, GY + TOPH + GAP, HALF, BOTH);
        set(surRect, GX + 24, GY + GH - 24 - 120, RW - 48, 120);
        set(topRects[GEAR], 1920 - 80 - 64, 52, 64, 64);
        set(topRects[CTRL], 1920 - 80 - 64 - 16 - 64, 52, 64, 64);
        set(coverRect, RX + 36, GY + 36, 258, 258);
        float cy = GY + 214 + 42, cx = RX + 342 + (LW - 342 - 48) / 2;
        set(ctlRects[1], cx - 42, cy - 42, 84, 84);
        set(ctlRects[0], cx - 42 - 48 - 76, cy - 38, 76, 76);
        set(ctlRects[2], cx + 42 + 48, cy - 38, 76, 76);
        set(panel, D_X, D_Y, D_W, D_H);
        for (int i = 0; i < D_KEY.length; i++) {
            int[] a = D_AREA[i];
            set(dRects[i], D_X + D_PAD + a[0] * (D_CELL + D_GAP), D_Y + D_PAD + D_HEAD + a[1] * (D_CELL + D_GAP),
                    a[2] * D_CELL + (a[2] - 1) * D_GAP, a[3] * D_CELL + (a[3] - 1) * D_GAP);
            RectF d = dRects[i];
            dGlow[i] = new RadialGradient(d.left, d.top, Math.max(d.width(), d.height()) * 1.1f, alpha(D_ACCENT[i], 0.2f), D_ACCENT[i] & 0x00FFFFFF, Shader.TileMode.CLAMP);
        }
        tonightShade = new LinearGradient(0, (GY + GH * 0.35f) * k, 0, (GY + GH) * k,
                new int[] {0x00000000, 0x99000000, 0xE6000000}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
        musicShade = new LinearGradient(RX * k, 0, (RX + LW) * k, 0, new int[] {0x14000000, 0x66000000}, null, Shader.TileMode.CLAMP);
        weatherGlow = new RadialGradient(weatherRect.right, weatherRect.top - weatherRect.height() * 0.1f, weatherRect.width() * 1.1f, 0x4D8FA2E6, 0x008FA2E6, Shader.TileMode.CLAMP);
        upGlow = new RadialGradient(dollarRect.right, dollarRect.bottom + dollarRect.height() * 0.1f, dollarRect.width() * 1.1f, 0x4230D158, 0x0030D158, Shader.TileMode.CLAMP);
        downGlow = new RadialGradient(dollarRect.right, dollarRect.bottom + dollarRect.height() * 0.1f, dollarRect.width() * 1.1f, 0x4DFF453A, 0x00FF453A, Shader.TileMode.CLAMP);
        barScrim = new LinearGradient(0, 0, 0, 152 * k, new int[] {0xF2000000, 0xD9000000, 0x00000000}, new float[] {0, 0.72f, 1}, Shader.TileMode.CLAMP);
        glows();
        setRows();
        for (String s : D_SYM) Symbols.warm(getContext(), s, 28 * k);
        for (String s : new String[] {"sparkles", "sync", "settings", "controls", "home"}) Symbols.warm(getContext(), s, 32 * k);
        Symbols.warmWeather(getContext(), 64 * k);
        updateText();
    }

    private void set(RectF out, float x, float y, float w, float h) {
        out.set(x * k, y * k, (x + w) * k, (y + h) * k);
    }

    // MARK: - Frames

    @Override public void doFrame(long nanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 0.016f : Math.min(0.05f, (nanos - lastNanos) / 1e9f);
        lastNanos = nanos;
        boolean over = drawerOpen || sheetOpen, moving = false;
        for (int i = 0; i < bentoFocus.length; i++) {
            bentoFocus[i].target = !over && zone == Z_BENTO && bento == i ? 1 : 0;
            moving |= bentoFocus[i].step(dt);
        }
        for (int i = 0; i < 2; i++) {
            topFocus[i].target = (!over && zone == Z_TOP && top == i) || (i == CTRL && drawerOpen) ? 1 : 0;
            moving |= topFocus[i].step(dt);
        }
        for (int i = 0; i < appFocus.length; i++) {
            appFocus[i].target = !over && zone == Z_APPS && app == i ? 1 : 0;
            moving |= appFocus[i].step(dt);
        }
        for (int i = 0; i < rowFocus.size() && i < rows.size(); i++) {
            Spring[] s = rowFocus.get(i);
            for (int j = 0; j < s.length; j++) {
                s[j].target = !over && zone == Z_ROWS && row == i && col == j ? 1 : 0;
                moving |= s[j].step(dt);
            }
            moving |= rowShift.get(i).step(dt);
        }
        for (int i = 0; i < D_KEY.length; i++) {
            dFocus[i].target = drawerOpen && dIndex == i ? 1 : 0;
            moving |= dFocus[i].step(dt);
        }
        for (int i = 0; i < gFocus.length; i++) {
            gFocus[i].target = sheetOpen && gIndex == i ? 1 : 0;
            moving |= gFocus[i].step(dt);
        }
        dOpen.target = drawerOpen ? 1 : 0;
        gOpen.target = sheetOpen ? 1 : 0;
        moving |= dOpen.step(dt) | gOpen.step(dt) | gScroll.step(dt);
        moving |= press.step(dt) | land.step(dt) | scroll.step(dt);
        if (moving) invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (greet == null) return;
        c.drawColor(0xFF000000);
        float s = scroll.value;
        // The background: tonight's pick, blurred; 28% on the home, 12% under the rows
        if (bgBlur != null) {
            r.set(0, 0, getWidth(), getHeight());
            image.setAlpha(Math.round(255 * (0.28f - 0.16f * Math.min(1, s / 400))));
            c.drawBitmap(bgBlur, null, r, image);
            image.setAlpha(255);
        }
        c.save();
        c.translate(0, -s * k);
        if (s < 1000) {
            Backdrop b = pick == null ? null : recs.backdrop(pick);
            drawTonight(c, b);
            drawMusic(c);
            drawWeather(c);
            drawDollar(c);
        }
        drawApps(c);
        drawRows(c, s);
        c.restore();
        if (s > 1) {   // the rows go out under the greeting
            shade.setShader(barScrim);
            shade.setAlpha(Math.round(255 * Math.min(1, s / 200)));
            c.drawRect(0, 0, getWidth(), 152 * k, shade);
            shade.setShader(null);
            shade.setAlpha(255);
        }
        drawTop(c);
        if (dOpen.value > 0.005f) drawDrawer(c);
        if (gOpen.value > 0.005f) drawSheet(c);
    }

    private void drawTop(Canvas c) {
        float y = 96 * k, x = GX * k;
        c.drawText(greetText, x, y, greet);
        c.drawText(nameText, x + greet.measureText(greetText), y, greetDim);
        float right = topRects[CTRL].left - 24 * k;
        float tw = today.measureText(timeText);
        c.drawText(timeText, right - tw, 96 * k, today);
        c.drawText(dateText, right - tw - todayDim.measureText(dateText), 96 * k, todayDim);
        for (int i = 0; i < 2; i++) {
            float f = topFocus[i].value;
            fill.setColor(blend(0x26FFFFFF, 0xFFFFFFFF, f));
            r.set(topRects[i]);
            grow(r, 1 + 0.08f * f);
            c.drawOval(r, fill);
            Symbols.draw(getContext(), c, i == CTRL ? "controls" : "settings", r.centerX(), r.centerY(), 30 * k, f > 0.5f ? 0xFF1C1C1E : 0xFFFFFFFF);
        }
    }

    private void drawTonight(Canvas c, Backdrop b) {
        c.save();
        r.set(tonightRect);
        clip.reset();
        clip.addRoundRect(r, R * k, R * k, Path.Direction.CW);
        c.clipPath(clip);
        fill.setColor(0xFF0E1012);
        c.drawRect(r, fill);
        if (b != null && b.image != null) {
            // The photo covers the card (center crop), with the landing's small settle
            float s = Math.max(r.width() / b.image.getWidth(), r.height() / b.image.getHeight()) * land.value;
            matrix.setScale(s, s);
            matrix.postTranslate(r.centerX() - b.image.getWidth() * s / 2, r.top + r.height() * 0.3f - b.image.getHeight() * s * 0.3f);
            c.drawBitmap(b.image, matrix, image);
        }
        shade.setShader(tonightShade);
        c.drawRect(r, shade);
        shade.setShader(null);
        float x = (GX + 40) * k;
        if (!pickService.isEmpty()) c.drawText(pickService.toUpperCase(Ui.locale()), x, (GY + 352) * k, small);
        drawFit(c, pickTitle, x, (GY + 410) * k, (RW - 80) * k, big);
        drawFit(c, pickMeta, x, (GY + 448) * k, (RW - 80) * k, meta);
        // Surprise me: glass, white when focused, pressed in on OK
        float f = bentoFocus[SUR].value;
        r.set(surRect);
        grow(r, (1 + 0.035f * f) * (zone == Z_BENTO && bento == SUR ? 1 - 0.03f * press.value : 1));
        fill.setColor(blend(0x24FFFFFF, 0xFFFFFFFF, f));
        c.drawRoundRect(r, 30 * k, 30 * k, fill);
        int ink = f > 0.5f ? 0xFF1C1C1E : 0xFFFFFFFF;
        Symbols.draw(getContext(), c, drawing || surLabel ? "sync" : "sparkles", r.left + 50 * k, r.centerY(), 32 * k, ink);
        String t1 = drawing ? Ui.tr("Drawing…") : surLabel ? Ui.tr("Draw another") : Ui.tr("Surprise me");
        String t2 = drawing ? Ui.tr("Among Netflix's titles") : surLabel ? Ui.tr("Or keep this one") : Ui.tr("A Netflix title for the two of you");
        Paint p1 = f > 0.5f ? pill : label, p2 = f > 0.5f ? pillDim : meta;
        c.drawText(t1, r.left + 96 * k, r.centerY() - 6 * k, p1);
        c.drawText(t2, r.left + 96 * k, r.centerY() + 26 * k, p2);
        c.restore();
    }

    private void drawMusic(Canvas c) {
        c.save();
        r.set(musicRect);
        clip.reset();
        clip.addRoundRect(r, R * k, R * k, Path.Direction.CW);
        c.clipPath(clip);
        fill.setColor(0xFF15181C);
        c.drawRect(r, fill);
        if (coverBlur != null) {
            // The cover stretched behind (blurred for free: tiny, scaled up with filtering)
            image.setAlpha(170);
            c.drawBitmap(coverBlur, null, r, image);
            image.setAlpha(255);
        }
        shade.setShader(musicShade);
        c.drawRect(r, shade);
        shade.setShader(null);
        c.restore();
        if (coverShader != null) {
            matrix.setScale(coverRect.width() / coverOf.getWidth(), coverRect.height() / coverOf.getHeight());
            matrix.postTranslate(coverRect.left, coverRect.top);
            coverShader.setLocalMatrix(matrix);
            fill.setShader(coverShader);
            c.drawRoundRect(coverRect, 20 * k, 20 * k, fill);
            fill.setShader(null);
        } else {
            fill.setColor(0x1AFFFFFF);
            c.drawRoundRect(coverRect, 20 * k, 20 * k, fill);
        }
        float x = (RX + 342) * k, w = (LW - 342 - 48) * k;
        drawFit(c, songTitle, x, (GY + 86) * k, w, title);
        drawFit(c, songArtist, x, (GY + 124) * k, w, meta);
        if (np.active() && np.duration > 0) {
            float p = Math.min(1, np.positionNow() / (float) np.duration);
            fill.setColor(0x33FFFFFF);
            r.set(x, (GY + 162) * k, x + w, (GY + 168) * k);
            c.drawRoundRect(r, 3 * k, 3 * k, fill);
            fill.setColor(0xEBFFFFFF);
            r.right = x + w * p;
            c.drawRoundRect(r, 3 * k, 3 * k, fill);
            c.drawText(posText, x, (GY + 196) * k, small);
            c.drawText(leftText, x + w - small.measureText(leftText), (GY + 196) * k, small);
        }
        for (int i = 0; i < 3; i++) {
            float f = bentoFocus[PREV + i].value;
            r.set(ctlRects[i]);
            grow(r, (1 + 0.1f * f) * (zone == Z_BENTO && bento == PREV + i ? 1 - 0.07f * press.value : 1));
            if (f > 0.01f) {
                fill.setColor(alpha(0xFFFFFFFF, f));
                c.drawOval(r, fill);
            }
            int ink = f > 0.5f ? 0xFF1C1C1E : 0xFFFFFFFF;
            if (i == 1) drawPlayPause(c, r.centerX(), r.centerY(), 21 * k, np.playing, ink);
            else drawSkip(c, r.centerX(), r.centerY(), 15 * k, i == 2, ink);
        }
    }

    private void drawWeather(Canvas c) {
        r.set(weatherRect);
        fill.setShader(weatherGlow);
        card(c, r, 0xFF191B26);
        float x = r.left + 40 * k;
        Symbols.draw(getContext(), c, "home", x + 12 * k, r.top + 52 * k, 24 * k, 0xE6FFFFFF);
        c.drawText(Config.CITY.isEmpty() ? Ui.tr("Weather") : Config.CITY, x + 36 * k, r.top + 62 * k, label);
        c.drawText(tempText, x - 4 * k, r.top + 172 * k, bigLight);
        HomeData.Weather w = data.weather;
        if (w.code >= 0) Symbols.draw(getContext(), c, Symbols.weather(w.code, w.isDay), x + bigLight.measureText(tempText) + 60 * k, r.top + 136 * k, 64 * k, 0xFFA5A3FF);
        c.drawText(feelText, x, r.top + 226 * k, feel);
        drawFit(c, sayText, x, r.bottom - 34 * k, r.width() - 70 * k, meta);
    }

    private void drawDollar(Canvas c) {
        r.set(dollarRect);
        fill.setShader(dollarUp ? upGlow : downGlow);
        card(c, r, dollarUp ? 0xFF121C16 : 0xFF1F1416);
        float x = r.left + 40 * k;
        c.drawText(Ui.tr("Dollar"), x, r.top + 62 * k, label);
        c.drawText(dollarText, x - 4 * k, r.top + 166 * k, bigLight);
        chg.setColor(dollarUp ? 0xFF30D158 : 0xFFFF453A);
        c.drawText(changeText, x, r.top + 214 * k, chg);
    }

    /** A widget's card: its base color, then its glow (set on `fill`'s shader), rounded. */
    private void card(Canvas c, RectF rect, int base) {
        Shader glow = fill.getShader();
        fill.setShader(null);
        fill.setColor(base);
        c.drawRoundRect(rect, R * k, R * k, fill);
        fill.setShader(glow);
        c.drawRoundRect(rect, R * k, R * k, fill);
        fill.setShader(null);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * k);
        stroke.setColor(0x1FFFFFFF);
        c.drawRoundRect(rect, R * k, R * k, stroke);
    }

    private void drawApps(Canvas c) {
        int n = Math.min(apps.size() - appFirst, APPS_VISIBLE + 1);
        for (int j = 0; j < n; j++) {
            int i = appFirst + j;
            HomeApps.Item it = apps.get(i);
            float f = appFocus[i].value;
            set(r, GX + j * (APP_W + GAP), APPS_TOP, APP_W, APP_H);
            grow(r, 1 + 0.11f * f * (zone == Z_APPS && app == i ? 1 - 0.3f * press.value : 1));
            if (f > 0.01f) {   // V1.3's lift: a deep shadow under the focused tile
                fill.setColor(alpha(0x99000000, f));
                tmp.set(r);
                tmp.offset(0, 18 * k * f);
                c.drawRoundRect(tmp, 22 * k, 22 * k, fill);
            }
            fill.setColor(0xFF141416);
            c.drawRoundRect(r, 22 * k, 22 * k, fill);
            Shader glow = appGlow.get(it.key);
            if (glow != null) {   // the brand's color rising from the bottom, brighter when focused
                c.save();
                c.translate(r.centerX(), r.centerY());
                tmp.set(-r.width() / 2, -r.height() / 2, r.width() / 2, r.height() / 2);
                fill.setShader(glow);
                fill.setAlpha(Math.round(255 * (0.55f + 0.45f * f)));
                c.drawRoundRect(tmp, 22 * k, 22 * k, fill);
                fill.setShader(null);
                fill.setAlpha(255);
                c.restore();
            }
            Bitmap mark = marks.get(it.key);
            if (mark != null) {
                Object[] b = BRAND.get(it.key);
                float size = (float) b[3] * k * (1 + 0.11f * f), s = size / Math.max(mark.getWidth(), mark.getHeight());
                float mw = mark.getWidth() * s, mh = mark.getHeight() * s;
                tmp.set(r.centerX() - mw / 2, r.centerY() - mh / 2, r.centerX() + mw / 2, r.centerY() + mh / 2);
                tint.setColor((int) b[1]);
                c.drawBitmap(mark, null, tmp, tint);
            } else {
                Bitmap logo = it.logo != null ? it.logo : it.tile;
                if (logo != null) {
                    float s = it.logo != null ? Math.min(r.width() * 0.62f / logo.getWidth(), r.height() * 0.5f / logo.getHeight())
                            : Math.max(r.width() / logo.getWidth(), r.height() / logo.getHeight());
                    float lw = logo.getWidth() * s, lh = logo.getHeight() * s;
                    tmp.set(r.centerX() - lw / 2, r.centerY() - lh / 2, r.centerX() + lw / 2, r.centerY() + lh / 2);
                    c.drawBitmap(logo, null, tmp, image);
                }
            }
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.5f * k);
            stroke.setColor(f > 0.5f ? 0x38FFFFFF : 0x12FFFFFF);
            c.drawRoundRect(r, 22 * k, 22 * k, stroke);
        }
    }

    private void drawRows(Canvas c, float s) {
        for (int i = 0; i < rows.size(); i++) {
            Rows.Row rw = rows.get(i);
            float y = rowY[i];
            if ((y - s) > 1080 + 40 || (y - s) + ROW_T2C + SQUARE + CAPS < -40) continue;   // off screen
            float tx = GX * k, ty = (y + 26) * k;
            c.drawText(rw.title, tx, ty, rowTitle);
            if (rw.subtitle != null && !rw.subtitle.isEmpty()) c.drawText(rw.subtitle, tx + rowTitle.measureText(rw.title) + 14 * k, ty, rowSub);
            float[] xs = rowX.get(i);
            Spring[] fs = rowFocus.get(i);
            float shift = rowShift.get(i).value;
            float top = y + ROW_T2C;
            for (int j = 0; j < xs.length; j++) {
                float x0 = GX + xs[j] - shift;
                if (x0 > 1920 + 40 || x0 + TOP_W + TOP_NUM < -40) continue;
                Recs.Pick p = rw.items.get(j);
                float f = fs[j].value;
                if (rw.ranked) {
                    String n = String.valueOf(p.rank);
                    float nw = numberWidth(p.rank);
                    c.drawText(n, x0 * k, (top + TOP_H) * k, f > 0.5f ? rankOn : rank);
                    set(r, x0 + nw - TOP_TUCK, top, TOP_W, TOP_H);
                    drawCard(c, rowsData.card(p), r, f, 20);
                    if (marks.get("netflix") != null) {   // the service's chip
                        tmp.set(r.left + 16 * k, r.bottom - 52 * k, r.left + 52 * k, r.bottom - 16 * k);
                        fill.setColor(0xB3000000);
                        c.drawRoundRect(tmp, 10 * k, 10 * k, fill);
                        tmp.inset(9 * k, 7 * k);
                        tint.setColor(0xFFE50914);
                        c.drawBitmap(marks.get("netflix"), null, tmp, tint);
                    }
                } else {
                    set(r, x0, top, SQUARE, SQUARE);
                    drawCard(c, rowsData.poster(p), r, f, 16);   // Spotify's cover (its address is the poster's)
                    float cy = (top + SQUARE + 44) * k + 10 * k * f;
                    drawFit(c, p.title == null ? "" : p.title, x0 * k, cy, SQUARE * k, cardTitle);
                    drawFit(c, p.meta == null ? "" : p.meta, x0 * k, cy + 30 * k, SQUARE * k, cardMeta);
                }
            }
        }
    }

    /** A row's card: the image (a shader, rounded), V1.3's lift and shadow when focused. */
    private void drawCard(Canvas c, Rows.Card card, RectF rect, float f, float radius) {
        grow(rect, 1 + 0.11f * f);
        if (f > 0.01f) {
            fill.setColor(alpha(0x99000000, f));
            tmp.set(rect);
            tmp.offset(0, 24 * k * f);
            c.drawRoundRect(tmp, radius * k, radius * k, fill);
        }
        if (card != null) {
            matrix.setScale(rect.width() / card.bitmap.getWidth(), rect.height() / card.bitmap.getHeight());
            matrix.postTranslate(rect.left, rect.top);
            card.shader.setLocalMatrix(matrix);
            fill.setShader(card.shader);
            c.drawRoundRect(rect, radius * k, radius * k, fill);
            fill.setShader(null);
        } else {
            fill.setColor(0xFF1C1C1E);
            c.drawRoundRect(rect, radius * k, radius * k, fill);
        }
        if (f > 0.5f) {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.5f * k);
            stroke.setColor(0x33FFFFFF);
            c.drawRoundRect(rect, radius * k, radius * k, stroke);
        }
    }

    // MARK: - The Gaveta

    private void drawDrawer(Canvas c) {
        float o = dOpen.value;
        fill.setColor(alpha(0x99000000, o));   // the home dims behind it
        c.drawRect(0, 0, getWidth(), getHeight(), fill);
        c.save();
        // It grows out of its button (origin-aware), fading in
        float px = topRects[CTRL].centerX(), py = topRects[CTRL].bottom;
        float sc = 0.6f + 0.4f * o;
        c.scale(sc, sc, px, py);
        int a = Math.round(255 * Math.min(1, o * 1.4f));
        c.saveLayerAlpha(panel.left - 60 * k, panel.top - 60 * k, panel.right + 60 * k, panel.bottom + 90 * k, a);
        fill.setColor(0x80000000);
        tmp.set(panel);
        tmp.offset(0, 30 * k);
        c.drawRoundRect(tmp, 48 * k, 48 * k, fill);
        fill.setColor(0xF2161618);
        c.drawRoundRect(panel, 48 * k, 48 * k, fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * k);
        stroke.setColor(0x1FFFFFFF);
        c.drawRoundRect(panel, 48 * k, 48 * k, stroke);
        c.drawText(Ui.tr("Shortcuts"), panel.left + (D_PAD + 4) * k, panel.top + (D_PAD + 34) * k, label);
        for (int i = 0; i < D_KEY.length; i++) drawModule(c, i);
        c.restore();
        c.restore();
    }

    private void drawModule(Canvas c, int i) {
        float f = dFocus[i].value;
        r.set(dRects[i]);
        grow(r, 1 + 0.05f * f);
        float rad = 34 * k;
        boolean saver = "saver".equals(D_KEY[i]);
        Backdrop b = saver && pick != null ? recs.backdrop(first != null ? first : pick) : null;
        if (b != null && b.wide != null) {
            c.save();
            clip.reset();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.clipPath(clip);
            float s = Math.max(r.width() / b.wide.getWidth(), r.height() / b.wide.getHeight());
            matrix.setScale(s, s);
            matrix.postTranslate(r.centerX() - b.wide.getWidth() * s / 2, r.centerY() - b.wide.getHeight() * s / 2);
            c.drawBitmap(b.wide, matrix, image);
            fill.setColor(0x80000000);
            c.drawRect(r, fill);
            c.restore();
        } else {
            fill.setColor(0x12FFFFFF);
            c.drawRoundRect(r, rad, rad, fill);
            fill.setShader(dGlow[i]);   // its color from the top left corner, fading (made at layout)
            c.drawRoundRect(r, rad, rad, fill);
            fill.setShader(null);
        }
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth((1.5f + 1.5f * f) * k);
        stroke.setColor(blend(0x14FFFFFF, 0x99FFFFFF, f));
        c.drawRoundRect(r, rad, rad, stroke);
        // The symbol in a circle: solid in its color for what's on (Wi-Fi, Bluetooth), tinted for the rest
        boolean on = i == 1 || i == 2;
        float cx = r.left + 52 * k, cy = r.top + 52 * k;
        fill.setColor(saver ? 0x33FFFFFF : on ? D_ACCENT[i] : alpha(D_ACCENT[i], 0.2f));
        c.drawCircle(cx, cy, 28 * k, fill);
        Symbols.draw(getContext(), c, D_SYM[i], cx, cy, 28 * k, saver || on ? 0xFFFFFFFF : D_ACCENT[i]);
        float x = r.left + 24 * k;
        if (i == 0) {   // the big one
            c.drawText(dName[i], x, r.bottom - 66 * k, modBig);
            c.drawText(dSub[i], x, r.bottom - 30 * k, modSub);
            return;
        }
        boolean sub = !dSub[i].isEmpty();
        drawFit(c, dName[i], x, r.bottom - (sub ? 48 : 26) * k, r.width() - 40 * k, modName);
        if (sub) drawFit(c, dSub[i], x, r.bottom - 22 * k, r.width() - 40 * k, modSub);
    }

    private void openDrawer(boolean on) {
        drawerOpen = on;
        if (on) dIndex = 0;
        if (on) click.open(); else click.back();
        invalidate();
    }

    private void drawerMove(int dx, int dy) {
        RectF cur = dRects[dIndex];
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < dRects.length; i++) {
            if (i == dIndex) continue;
            RectF o = dRects[i];
            if (dy > 0 && o.top < cur.bottom - 10) continue;
            if (dy < 0 && o.bottom > cur.top + 10) continue;
            if (dx > 0 && o.left < cur.right - 10) continue;
            if (dx < 0 && o.right > cur.left + 10) continue;
            float ddx = o.centerX() - cur.centerX(), ddy = o.centerY() - cur.centerY();
            float d = dx != 0 ? Math.abs(ddx) + 2 * Math.abs(ddy) : Math.abs(ddy) + 2 * Math.abs(ddx);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best < 0) {
            if (dx < 0) openDrawer(false);
            return;
        }
        dIndex = best;
        click.move();
        invalidate();
    }

    private void drawerOk() {
        String key = D_KEY[dIndex];
        RectF at = dRects[dIndex];
        HomeApps.Item it = null;
        switch (key) {
            case "mode": case "pic": it = quick.get("picture"); break;
            case "snd": it = quick.get("sound"); break;
            case "bt": it = quick.get("bluetooth"); break;
            case "wifi": it = device("wifi", new Intent(Settings.ACTION_WIFI_SETTINGS)); break;
            case "audio": it = device("audio", new Intent(Settings.ACTION_SOUND_SETTINGS)); if (it == null) it = quick.get("sound"); break;
            case "saver": it = device("saver", new Intent().setComponent(new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator"))); break;
            case "apps": openDrawer(false); openSheet(); return;
            case "home": it = HomeApps.settings(); break;
            default: it = HomeApps.action(HomeApps.ANDROID_SETTINGS, "tv-settings", Ui.tr("TV Settings"), "tv_settings"); break;
        }
        click.open();
        if (it == null) return;
        drawerOpen = false;
        HomeApps.open(activity, it, this, Math.round(at.left), Math.round(at.top), Math.round(at.width()), Math.round(at.height()));
    }

    /** A screen of the TV's own (Wi-Fi, sound, the screensaver), only if this TV has it. */
    private HomeApps.Item device(String key, Intent intent) {
        if (activity.getPackageManager().resolveActivity(intent, 0) == null) return null;
        HomeApps.Item it = HomeApps.action(HomeApps.DEVICE, key, key, null);
        it.intent = intent;
        return it;
    }

    // MARK: - All Apps

    private void openSheet() {
        sheetOpen = true;
        gIndex = 0;
        gScroll.snap(0);
        click.open();
        invalidate();
    }

    private void drawSheet(Canvas c) {
        float o = gOpen.value;
        fill.setColor(alpha(0xF20A0A0C, o));
        c.drawRect(0, 0, getWidth(), getHeight(), fill);
        c.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * o));
        c.drawText(Ui.tr("All Apps"), GX * k, 128 * k, sheetTitle);
        c.save();
        c.clipRect(0, (G_TOP - 30) * k, getWidth(), getHeight());
        c.translate(0, -gScroll.value * G_ROW * k + (1 - o) * 30 * k);
        for (int i = 0; i < others.size(); i++) {
            int gr = i / G_COLS, gc = i % G_COLS;
            HomeApps.Item it = others.get(i);
            float f = i < gFocus.length ? gFocus[i].value : 0;
            set(r, G_X + gc * (G_W + G_GAP), G_TOP + gr * G_ROW, G_W, G_H);
            if (r.top - gScroll.value * G_ROW * k > getHeight()) break;
            grow(r, 1 + 0.08f * f);
            if (it.tileShader != null && it.tile != null) {
                matrix.setScale(r.width() / it.tile.getWidth(), r.height() / it.tile.getHeight());
                matrix.postTranslate(r.left, r.top);
                it.tileShader.setLocalMatrix(matrix);
                fill.setShader(it.tileShader);
                c.drawRoundRect(r, 22 * k, 22 * k, fill);
                fill.setShader(null);
            } else {
                fill.setColor(0xFF1C1C1E);
                c.drawRoundRect(r, 22 * k, 22 * k, fill);
                Bitmap logo = it.logo;
                if (logo != null) {
                    float s = Math.min(r.width() * 0.6f / logo.getWidth(), r.height() * 0.5f / logo.getHeight());
                    tmp.set(r.centerX() - logo.getWidth() * s / 2, r.centerY() - logo.getHeight() * s / 2, r.centerX() + logo.getWidth() * s / 2, r.centerY() + logo.getHeight() * s / 2);
                    c.drawBitmap(logo, null, tmp, image);
                }
            }
            if (f > 0.5f) {
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(3 * k);
                stroke.setColor(0xE6FFFFFF);
                c.drawRoundRect(r, 22 * k, 22 * k, stroke);
                c.drawText(it.label == null ? "" : it.label, r.centerX(), r.bottom + 38 * k, gLabel);
            }
        }
        c.restore();
        c.restore();
    }

    private void sheetMove(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int i = gIndex;
        if (dx != 0) {
            int cl = i % G_COLS + dx;
            if (cl < 0 || cl >= G_COLS || i + dx >= n) return;
            i += dx;
        } else {
            i += dy * G_COLS;
            if (i < 0) return;
            if (i >= n) {
                if ((n - 1) / G_COLS == gIndex / G_COLS) return;
                i = n - 1;
            }
        }
        gIndex = i;
        int gr = i / G_COLS;
        float first = gScroll.target;
        if (gr < first) gScroll.target = gr;
        if (gr > first + G_ROWS - 1) gScroll.target = gr - G_ROWS + 1;
        click.move();
        invalidate();
    }

    // MARK: - Glyphs

    private void drawPlayPause(Canvas c, float cx, float cy, float s, boolean playing, int color) {
        fill.setColor(color);
        if (playing) {
            r.set(cx - s * 0.62f, cy - s * 0.8f, cx - s * 0.18f, cy + s * 0.8f);
            c.drawRoundRect(r, s * 0.12f, s * 0.12f, fill);
            r.offset(s * 0.8f, 0);
            c.drawRoundRect(r, s * 0.12f, s * 0.12f, fill);
        } else {
            glyph.reset();
            glyph.moveTo(cx - s * 0.5f, cy - s * 0.85f);
            glyph.lineTo(cx + s * 0.85f, cy);
            glyph.lineTo(cx - s * 0.5f, cy + s * 0.85f);
            glyph.close();
            c.drawPath(glyph, fill);
        }
    }

    private void drawSkip(Canvas c, float cx, float cy, float s, boolean forward, int color) {
        fill.setColor(color);
        glyph.reset();
        float d = forward ? 1 : -1;
        for (int i = 0; i < 2; i++) {
            float x0 = cx + d * (i == 0 ? -s : 0);
            glyph.moveTo(x0, cy - s * 0.75f);
            glyph.lineTo(x0 + d * s, cy);
            glyph.lineTo(x0, cy + s * 0.75f);
            glyph.close();
        }
        c.drawPath(glyph, fill);
    }

    // MARK: - Keys: the arrows, OK and Back

    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ours = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || isArrow(code) || code == KeyEvent.KEYCODE_BACK;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) press.target = 0;
            return ours;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN || !ours) return false;
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        boolean back = code == KeyEvent.KEYCODE_BACK, ok = !back && dx == 0 && dy == 0;
        if (ok && e.getRepeatCount() > 0) return true;
        if (sheetOpen) {
            if (back) { sheetOpen = false; click.back(); }
            else if (ok) {
                press.value = 1;
                press.target = 0;
                if (gIndex < others.size()) {
                    click.open();
                    set(r, G_X + (gIndex % G_COLS) * (G_W + G_GAP), G_TOP + (gIndex / G_COLS - gScroll.target) * G_ROW, G_W, G_H);
                    HomeApps.open(activity, others.get(gIndex), this, Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height()));
                }
            } else sheetMove(dx, dy);
            invalidate();
            return true;
        }
        if (drawerOpen) {
            if (back) openDrawer(false);
            else if (ok) drawerOk();
            else drawerMove(dx, dy);
            return true;
        }
        if (back) {
            if (zone == Z_BENTO && bento == SUR && resetDraw()) click.back();
            else if (zone != Z_BENTO || bento != PLAY) { zone = Z_BENTO; bento = PLAY; scroll.target = 0; click.back(); }
            invalidate();
            return true;
        }
        if (ok) ok(); else move(dx, dy);
        return true;
    }

    private static boolean isArrow(int code) {
        return code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private void move(int dx, int dy) {
        int z = zone, b = bento, a = app, f = appFirst, t = top, rr = row, cc = col;
        if (zone == Z_TOP) {
            if (dx != 0) top = Math.max(CTRL, Math.min(GEAR, top + dx));
            else if (dy > 0) { zone = Z_BENTO; bento = NEXT; }
        } else if (zone == Z_BENTO) {
            if (dy < 0) { zone = Z_TOP; top = GEAR; }
            else if (dy > 0) { zone = Z_APPS; app = bento == SUR ? 0 : Math.min(apps.size() - 1, 3); }
            else if (bento == SUR) { if (dx > 0) bento = PREV; }
            else {
                int n = bento + dx;
                bento = n < PREV ? SUR : Math.min(NEXT, n);
            }
        } else if (zone == Z_APPS) {
            if (dy < 0) { zone = Z_BENTO; bento = app < 3 ? SUR : PLAY; }
            else if (dy > 0) {
                if (!rows.isEmpty()) { zone = Z_ROWS; row = 0; col = 0; }
            } else if (!apps.isEmpty()) {
                app = Math.max(0, Math.min(apps.size() - 1, app + dx));
                if (app >= appFirst + APPS_VISIBLE) appFirst = app - APPS_VISIBLE + 1;
                if (app < appFirst) appFirst = app;
            }
        } else {
            if (dy < 0) {
                if (row == 0) zone = Z_APPS;
                else { row--; col = Math.min(col, rowX.get(row).length - 1); }
            } else if (dy > 0) {
                if (row < rows.size() - 1) { row++; col = Math.min(col, rowX.get(row).length - 1); }
                else { openDrawer(true); return; }   // past the last row: the Gaveta, as on the TV
            } else {
                col = Math.max(0, Math.min(rowX.get(row).length - 1, col + dx));
            }
        }
        // The page: the focused row's title where the bento starts; the rows slide so the focus stays third
        scroll.target = zone == Z_ROWS ? rowY[row] + 4 - GY : 0;
        if (zone == Z_ROWS) {
            float[] xs = rowX.get(row);
            float max = Math.max(0, rowEnd[row] - (1840 - GX));
            rowShift.get(row).target = Math.max(0, Math.min(max, xs[col] - xs[Math.min(col, 2)]));
        }
        if (z != zone || b != bento || a != app || f != appFirst || t != top || rr != row || cc != col) click.move();
        invalidate();
    }

    private void ok() {
        press.value = 1;
        press.target = 0;
        if (zone == Z_TOP) {
            if (top == CTRL) { openDrawer(true); return; }
            click.open();
            HomeApps.open(activity, HomeApps.settings(), this, Math.round(topRects[GEAR].left), Math.round(topRects[GEAR].top),
                    Math.round(topRects[GEAR].width()), Math.round(topRects[GEAR].height()));
        } else if (zone == Z_APPS) {
            click.open();
            if (app < apps.size()) {
                set(r, GX + (app - appFirst) * (APP_W + GAP), APPS_TOP, APP_W, APP_H);
                HomeApps.open(activity, apps.get(app), this, Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height()));
            }
        } else if (zone == Z_ROWS) {
            click.open();
            Recs.Pick p = rows.get(row).items.get(col);
            if (p.pkg != null) {
                HomeApps.Item it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
                HomeApps.open(activity, it, this, 0, 0, getWidth(), getHeight());
            }
        } else if (bento == SUR) {
            click.open();
            startDraw();
        } else if (!np.active()) {
            click.open();
            Intent i = HomeApps.launchIntent(activity.getPackageManager(), "com.spotify.tv.android");
            if (i != null) activity.startActivity(i);
        } else if (bento == PLAY) {
            np.playPause();
        } else if (bento == NEXT) {
            np.next();
        } else {
            np.previous();
        }
        invalidate();
    }

    // MARK: - Helpers

    private void drawFit(Canvas c, String s, float x, float y, float w, Paint p) {
        if (s == null || s.isEmpty()) return;
        if (p.measureText(s) <= w) {
            c.drawText(s, x, y, p);
            return;
        }
        int n = p.breakText(s, true, w - p.measureText("…"), null);
        c.drawText(s.substring(0, Math.max(0, n)) + "…", x, y, p);
    }

    private static void grow(RectF r, float s) {
        float dw = r.width() * (s - 1) / 2, dh = r.height() * (s - 1) / 2;
        r.inset(-dw, -dh);
    }

    private static int alpha(int color, float a) {
        return (Math.round(((color >>> 24) & 255) * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = 0;
        for (int s = 0; s < 32; s += 8) {
            int ca = (a >>> s) & 255, cb = (b >>> s) & 255;
            r |= Math.round(ca + (cb - ca) * t) << s;
        }
        return r;
    }
}
