package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without picks, one of your own photos
 *    (Photos) the same way, or else a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours or days
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener, Rows.Listener, NowPlaying.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps, 133 × 75 with ten
    // (HomeApps.shelfVisible; past that the shelf scrolls).
    private static final float SHELF_PAD = 40, TILE_GAP = 32;
    private static final float TILE_RADIUS = 22;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.14f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, ROWS = 8;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 69, L_TABBAR_H = 68, L_CARDS_TOP = 262, L_CARD_H = 442;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    // Full-bleed hero (Apple TV app): the pick's sharp photo behind everything, its text at the bottom left, and below it
    // the apps as a row of 16:9 tiles that scrolls sideways (the next app peeks in at the right edge).
    private static final float H_CHIP_TOP = 356, H_CHIP_H = 38, H_TITLE_BASE = 520, H_TITLE_SIZE = 124, H_TITLE_FIT = 1180;
    private static final float H_META_BASE = 574, H_DESC_TOP = 596, H_DESC_W = 780, H_BUTTON_TOP = 692, H_BUTTON_H = 72, H_DOTS_Y = 828;
    // The apps peek at the bottom of the hero; focusing them scrolls them up under the top bar.
    private static final float APPS_TITLE_BASE = 906, APPS_TOP = 940, APP_W = 256, APP_H = 144, APPS_VISIBLE = 6, APPS_SCROLL = 654;
    // Top bar, fixed above everything: the tabs in a glass capsule (left), the dollar, the weather, the time and Settings
    // (right). The page scrolls under it, darkening toward the top.
    private static final float BAR_Y = 48, BAR_H = 72, BAR_PAD = 6, TAB_PAD = 30, ROUND = 64, BAR_SCRIM_H = 230;
    // Rows under the apps (trending movies, trending shows): cards in the picks' own 2.35:1, drawn on a layer below the
    // screen that scrolls up with the focus; the background turns into the photo's dark frosted version as it does.
    /** The rows' vertical rhythm (see rowHeight); the first title sits the same 60 px under the apps' tiles. */
    private static final float ROW_TITLE_TO_CARD = 30, ROW_CAPTIONS = 92, ROW_AIR = 60, ROW_TITLE_CAP = 22;
    private static final float ROWS_LAYER_TOP = 1100, ROWS_LAYER_H = 1420, ROW0_TITLE = 1166, ROW0_TOP = 1196, ROW_STEP = 440;
    private static final float ROW_CARD_W = 432, ROW_CARD_H = 243, ROW_GAP = 32, ROW_SCROLL_PAD = 330, AMBIENT_AT = 400;
    /** The quotes' row, under the round buttons (the tabs moved up beside them). */
    private static final float L_QUOTES_ROW = 192;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static String[] tabs;
    private static int tabsGeneration = -1;
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5, Z_ROWS = 6;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
        @Override public boolean holdable(int id) { return (id - 1) / 1000 == GRID && canToggleShelf((id - 1) % 1000); }
        @Override public void hold(int id) { a11yHold(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, rowsLayer;
    /** The rows under the apps ("Recently watched", then Rows'), only the ones with something in them. */
    private static final int MAX_ROWS = 12, MAX_ITEMS = Rows.PER_ROW;
    private final List<List<Recs.Pick>> rowItems = new ArrayList<>();
    private final List<String> rowTitles = new ArrayList<>(), rowSubtitles = new ArrayList<>();
    private final List<Boolean> rowRanked = new ArrayList<>(), rowSquare = new ArrayList<>();
    private final List<Boolean> rowChip = new ArrayList<>();   // rows that mix services: their cards carry its chip
    /**
     * Top 10 rows, as on Netflix: an outlined number as tall as the card, then the title's poster overlapping its right
     * side. Each number takes the width it needs ("1" narrow, "10" wide).
     */
    private static final float RANK_CARD_W = 162, RANK_CARD_H = 243, RANK_SIZE = 334, RANK_OVERLAP = 44;
    /** Spotify's playlists: square covers, larger than the cards (Apple Music's shelf). */
    private static final float SQUARE = 300;
    private Paint rankPaint, rankFill, rowSubPaint, surpriseLabel;
    /** Where each item starts in its row (1080p px, before the row's scroll). */
    private final float[][] rowX = new float[MAX_ROWS][MAX_ITEMS];
    private final float[] rowEnd = new float[MAX_ROWS];   // where the row's last item ends
    private final Spring[][] rowFocus = new Spring[MAX_ROWS][MAX_ITEMS];
    private final RectF[][] rowRects = new RectF[MAX_ROWS][MAX_ITEMS];
    /** Each row's horizontal scroll (1080p px): the focus moves to the third card, then the row slides under it. */
    private final Spring[] rowShift = new Spring[MAX_ROWS];
    private final Rows rowsData;
    private final NowPlaying nowPlaying;
    private String nowKey = "";
    private int rowIndex, rowCol;
    private boolean rowsDirty;
    /** How far the home screen has scrolled down (1920-wide px): 0 on the hero and the apps. */
    private final Spring scrollY = new Spring(0.5f, 1f, 0);
    /** For each app with a pick this hour, that pick's photo: it shows behind the app's logo when focused. */
    private final Map<String, Backdrop> appScene = new HashMap<>();
    private final android.graphics.Matrix coverMatrix = new android.graphics.Matrix();
    // Focus tilt (tvOS): the newly focused tile or card tips toward where the focus came from and springs back; a
    // diagonal highlight slides with it. One pair of springs: only the focused item tilts.
    private final android.graphics.Camera camera3d = new android.graphics.Camera();
    private final android.graphics.Matrix tiltMatrix = new android.graphics.Matrix(), specMatrix = new android.graphics.Matrix();
    private final Spring tiltX = new Spring(0.55f, 0.62f, 0), tiltY = new Spring(0.55f, 0.62f, 0);
    private final Paint specPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient specTile, specCard;
    private float specCardH;
    private int lastDx, lastDy;
    // The focused app's scene (Disney+'s franchise tiles): it comes in after a short pause, then a slow zoom settles.
    private static final long SCENE_DELAY_MS = 280, SCENE_IN_MS = 450, SCENE_ZOOM_MS = 3600;
    private int sceneIndex = -1;
    private long sceneAt;
    private Paint rowTitlePaint, rowCardTitle, rowCardMeta;
    private int zone = Z_CARDS;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    /** topIndex of the dollar, weather and time between the tabs and the round buttons: OK opens the drawer. */
    private static final int GLANCE = 99, SEARCH = 98;   // SEARCH: the magnifier left of the tabs
    private final RectF searchRect = new RectF();
    private final Spring searchFocus = new Spring(0.26f, 0.85f, 0);
    /** The last search's results: a row above the others until the home screen is left for a while. */
    private List<Recs.Pick> searchResults = new ArrayList<>();
    private String searchQuery = "";
    private long searchAt;
    private final Paint searchStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Spring glanceFocus = new Spring(0.26f, 0.85f, 0);
    private final RectF glanceRect = new RectF();
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    // The three tabs, then the round buttons at the top right (up to HomeApps.MAX_TOP).
    private final Spring[] topFocus = new Spring[3 + HomeApps.MAX_TOP];
    private final RectF[] topRects = new RectF[3 + HomeApps.MAX_TOP];
    private final Spring[] cardFocus = new Spring[HERO_MAX];
    private final RectF[] cardRects = new RectF[HERO_MAX];
    /** The hero's titles and Spotify's playlist. */
    private static final int HERO_MAX = Recs.HERO + 1;
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote;
    private Shader lightGradient, heroScrimLeft, heroScrimBottom, heroScrimTop;
    private Paint hEyebrow, hTitle, hMeta, hButton, appsTitle, barTab, barText, barDim, barLabel, barChange, chipText;
    private TextPaint hDesc;
    private StaticLayout heroDesc;
    private Shader barScrim, tileGray;
    private String dollarText = "", dollarChange = "";
    private long dollarAt = -1;
    private final RectF barRect = new RectF();

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
        TILE_STYLE.put("spotify", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("stremio", new int[] {0xFF7B5BF5, 0xFF3B2A8F, 1});
        TILE_STYLE.put("paramount", new int[] {0xFF0064FF, 0xFF00256B, 1});
    }
    /** The V1.6 tiles: a glow of the brand's color rising from the bottom into near-black (glow, middle, edge). */
    static final Map<String, int[]> BRAND_DEEP = new HashMap<>();
    static {
        BRAND_DEEP.put("netflix", new int[] {0xFF6B0D14, 0xFF1A0809, 0xFF0D0D0F});
        BRAND_DEEP.put("youtube", new int[] {0xFF5C1010, 0xFF1A0B0B, 0xFF0F0F10});
        BRAND_DEEP.put("prime", new int[] {0xFF0E4A66, 0xFF0B1A24, 0xFF0A1016});
        BRAND_DEEP.put("max", new int[] {0xFF1A3CFF, 0xFF0A1360, 0xFF060A26});
        BRAND_DEEP.put("disney", new int[] {0xFF1D4FB0, 0xFF0B1D4F, 0xFF060D26});
        BRAND_DEEP.put("globoplay", new int[] {0xFFC22A3C, 0xFF3D0C1A, 0xFF14080C});
        BRAND_DEEP.put("spotify", new int[] {0xFF137A3C, 0xFF0B2A17, 0xFF0A110D});
        BRAND_DEEP.put("appletv", new int[] {0xFF4A4A4E, 0xFF1C1C1F, 0xFF0E0E10});
        BRAND_DEEP.put("stremio", new int[] {0xFF6C4CE0, 0xFF1F1650, 0xFF0D0A1C});
        BRAND_DEEP.put("paramount", new int[] {0xFF0B4FD6, 0xFF0A1A4F, 0xFF060B22});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;
    /** The first app the shelf shows: with more apps than fit, it scrolls with the focus. */
    private int shelfFirst;
    private final int shelfVisible;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private final android.graphics.Path playPath = new android.graphics.Path();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen, shelfEdited;
    private int gridIndex;
    private long toggledAt;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private static final long BURST_MS = 1500;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    /** "Off in 42 min" next to the round buttons while the sleep timer runs (Settings > Sleep Timer); "" otherwise. */
    private String sleepText = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp, dayLow;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private Shader[] tileShaders = new Shader[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private float[] lightShaderW = new float[0];
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private boolean clock24;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText, staleText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] dayText = new String[5], dayMaxText = new String[5], dayMinText = new String[5];
    private boolean textDays;   // the weather card shows the next days (Home Settings → Forecast) and has them
    private final String[] priceText = new String[HomeData.COINS.length], changeText = new String[HomeData.COINS.length];

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            shadows.dither = dark() > 0.5f;
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case ROWS: drawRows(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;
    private static final long APPS_MAX_AGE_MS = 15 * 60_000;
    private final LauncherApps launcherApps;
    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            rowsData.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            updateSleep();
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        rowsData = Rows.get(a);
        nowPlaying = NowPlaying.get(a);
        click = new Click(a);
        shelfVisible = Math.min(HomeApps.shelfVisible(a), (int) APPS_VISIBLE);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        rowsLayer = new Layer(a, ROWS);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 8 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer, rowsLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, platter, tiles, rowsLayer, status, drawerLayer, sheetLayer, toastLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Free My TV");
        drawerLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < HERO_MAX; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        for (int r = 0; r < MAX_ROWS; r++) {
            rowShift[r] = new Spring(0.42f, 1, 0);
            for (int i = 0; i < MAX_ITEMS; i++) {
                rowFocus[r][i] = new Spring(0.34f, 0.78f, 0);
                rowRects[r][i] = new RectF();
            }
        }
        setLightButtons(HomeApps.topButtons());
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        if (!searchResults.isEmpty() && SystemClock.uptimeMillis() - searchAt > 15 * 60_000L) searchResults = new ArrayList<>();
        handler.postDelayed(this::eyeCare, 2500);
        checkNight(false);
        boolean h24 = Prefs.clock24();
        if (h24 != clock24) {   // 12/24 hours changed in the TV's settings: the clock and the drawer's times again
            clock24 = h24;
            clockMinute = -1;
            textPrefs = -1;
        }
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst of 5 to 8, and each one pauses and resumes the home
        // screen: those after the first change nothing, so the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        rowsData.addListener(this);
        nowPlaying.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        rowsData.refreshIfStale();
        Photos.importInbox(getContext(), ownReady);   // photos a computer just sent
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        handler.removeCallbacks(heroAuto);
        handler.postDelayed(heroAuto, HERO_AUTO_MS);
        checkNotices();
        if (!again) reload();
        updatePalette();
        updatePicks();
        updateSleep();      // a timer just set in Settings shows at once, not at the next tick
        if (allAppsOnResume) {
            allAppsOnResume = false;
            reset(false);
            openSheet();
        } else if (sheetOpen) {
            closeSheet();
        }
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(heroAuto);
        handler.removeCallbacks(reloadSoon);
        data.removeListener(this);
        recs.removeListener(this);
        rowsData.removeListener(this);
        nowPlaying.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        if (launcherApps != null) launcherApps.unregisterCallback(packages);
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        zone = picksOn() && !cards.isEmpty() ? Z_CARDS : Z_SHELF;
        shelfIndex = 0;
        cardIndex = rowIndex = rowCol = 0;
        layoutHeroButton();
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The round buttons at the top right (Settings > Top Buttons), with their names in the current language. */
    private void setLightButtons(List<HomeApps.Item> list) {
        lightButtons.clear();
        for (HomeApps.Item it : list) if (it.kind == HomeApps.SETTINGS) lightButtons.add(it);
        if (zone == Z_TOP && topIndex != GLANCE && topIndex != SEARCH && topIndex >= 3 + lightButtons.size()) {
            topIndex = 2 + lightButtons.size();
            if (topIndex < 3 && !picksOn()) zone = Z_SHELF;   // without the picks the tabs can't hold focus
            else if (topIndex < 3) selectTab(topIndex);
            retarget(true);
        }
        layoutLight();
        statusDirty = true;
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    private static String[] tabs() {
        if (tabsGeneration != Ui.generation) {
            tabs = new String[] {Ui.tr("For you"), Ui.tr("Movies"), Ui.tr("Shows")};
            tabsGeneration = Ui.generation;
        }
        return tabs;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    /**
     * Once an evening, after sunset: a reminder that the TV's Eye Care (in Picture) makes the screen easier on tired
     * eyes (TCL calls it Eye Care; here it's Night Shift).
     */
    private void eyeCare() {
        if (!running || !picksOn()) return;
        HomeData.Weather wx = data.weather;
        long now = System.currentTimeMillis();
        if (wx.sunset == 0 || now < wx.sunset) return;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int day = cal.get(java.util.Calendar.YEAR) * 10000 + (cal.get(java.util.Calendar.MONTH) + 1) * 100 + cal.get(java.util.Calendar.DAY_OF_MONTH);
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) < 5 || Prefs.eyeCareDay() == day) return;   // after midnight: the evening's already been
        Prefs.setEyeCareDay(day);
        showToast(Ui.tr("It's dark out: Night Shift, in Picture (the drawer), is easier on the eyes."));
    }

    /** The track changed, or it was paused or played: the hero's first slide follows (only when something changed). */
    @Override public void onNowPlaying() {
        String key = nowPlaying.active() ? nowPlaying.title + "|" + nowPlaying.artist + "|" + nowPlaying.playing + "|" + nowPlaying.artFile : "";
        if (key.equals(nowKey)) return;
        nowKey = key;
        updatePicks();
        heroDirty = true;
        invalidateAll();
    }

    /** What's playing as the hero's first slide: its cover over its own blur; OK opens the full-screen player. */
    private Recs.Pick nowPick() {
        NowPlaying n = nowPlaying;
        if (!n.active()) return null;
        Recs.Pick p = new Recs.Pick();
        p.kind = "nowplaying";
        p.id = (n.title + n.artist).hashCode();
        p.title = n.title;
        p.meta = n.album.isEmpty() || n.album.equals(n.title) ? n.artist : n.artist + " · " + n.album;
        p.overview = "";
        p.pkg = n.pkg;
        p.app = n.pkg != null && n.pkg.startsWith("com.spotify") ? "spotify" : null;
        p.backdrop = n.artFile != null ? n.artFile.getPath() : null;
        p.detailed = true;
        return p;
    }

    /** New lists, or a card's image is ready. */
    @Override public void onRows() {
        updatePicks();
        rowsDirty = true;
        rowsLayer.invalidate();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(860 * kk));
        measure(status, width, Math.round(BAR_SCRIM_H * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(320 * kk));
        measure(rowsLayer, width, height);   // the screen: the rows scroll inside it (drawRows)
        measure(drawerLayer, width, height);   // the screen: the V1.6 panel slides in at the right, the older drawer rises
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, 0, 0);
        place(platter, 0, Math.round(750 * kk));
        place(tiles, 0, Math.round(840 * kk));
        place(rowsLayer, 0, 0);
        place(drawerLayer, 0, 0);
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        Symbols.warm(getContext(), "bell", 26 * k);
        Symbols.warm(getContext(), "bedtime", 21 * k);   // the sleep timer's "Off in", at lQuote's size
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = null;
        hEyebrow = Ui.text(Ui.semibold, 22 * k, 0x9EFFFFFF);
        hTitle = Ui.text(Ui.displayBold, H_TITLE_SIZE * k, 0xFFFFFFFF);
        hTitle.setLetterSpacing(-0.035f);
        hMeta = Ui.text(Ui.medium, 26 * k, 0x9EFFFFFF);
        hDesc = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99EBEBF5));
        hButton = Ui.text(Ui.semibold, 26 * k, INK);
        barTab = Ui.text(Ui.semibold, 24 * k, 0x9EFFFFFF);
        barText = Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF);
        barDim = Ui.text(Ui.medium, 28 * k, 0x9EFFFFFF);
        barLabel = Ui.text(Ui.semibold, 20 * k, 0x61FFFFFF);
        barLabel.setLetterSpacing(0.06f);
        barChange = Ui.text(Ui.semibold, 20 * k, UP_COLOR);
        chipText = Ui.text(Ui.bold, 17 * k, 0xFFFFFFFF);
        barScrim = new LinearGradient(0, 0, 0, BAR_SCRIM_H * k, new int[] {0xFF000000, 0xFF000000, 0xE6000000, 0x00000000},
                new float[] {0, 0.3f, 0.6f, 1}, Shader.TileMode.CLAMP);
        appsTitle = Ui.text(Ui.bold, 30 * k, 0xFFFFFFFF);
        rankPaint = Ui.numbers(Ui.displayHeavy, RANK_SIZE * k, 0x59FFFFFF);
        rankPaint.setLetterSpacing(-0.04f);
        rankPaint.setStyle(Paint.Style.STROKE);
        rankPaint.setStrokeWidth(3 * k);
        rankFill = new Paint(rankPaint);
        rankFill.setStyle(Paint.Style.FILL);
        rankOutlines.clear();
        rowSubPaint = Ui.text(Ui.medium, 22 * k, 0x8CEBEBF5);
        surpriseLabel = Ui.text(Ui.bold, 30 * k, 0xFFFFFFFF);
        surpriseLabel.setLetterSpacing(-0.01f);
        rowCardTitle = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        rowCardTitle.setLetterSpacing(-0.01f);
        rowCardMeta = Ui.text(Ui.medium, 20 * k, 0x9EFFFFFF);
        rowTitlePaint = Ui.text(Ui.bold, 30 * k, 0xFFFFFFFF);
        rowTitlePaint.setLetterSpacing(-0.015f);
        appsTitle.setLetterSpacing(-0.015f);
        for (Paint p : new Paint[] {hEyebrow, hTitle, hMeta, appsTitle}) p.setShadowLayer(18 * k, 0, 2 * k, 0x73000000);
        // Scrims that keep the hero's text readable over any photo: from the left and from the bottom.
        heroScrimLeft = new LinearGradient(0, 0, w * 0.62f, 0, 0xB3000000, 0x00000000, Shader.TileMode.CLAMP);
        heroScrimBottom = new LinearGradient(0, h * 0.38f, 0, h, 0x00000000, 0xF0000000, Shader.TileMode.CLAMP);
        heroScrimTop = new LinearGradient(0, 0, 0, h * 0.3f, 0x8C000000, 0x00000000, Shader.TileMode.CLAMP);
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        dayLow = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        dayLow.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        glanceFocus.target = !sheetOpen && zone == Z_TOP && topIndex == GLANCE ? 1 : 0;
        searchFocus.target = !sheetOpen && zone == Z_TOP && topIndex == SEARCH ? 1 : 0;
        boolean topMoving = step(topFocus, dt) | glanceFocus.step(dt) | searchFocus.step(dt), cardsMoving = step(cardFocus, dt);
        boolean rowsMoving = spinning() || (zone == Z_ROWS && rowCol == 0 && rowIndex < rowItems.size()
                && !rowItems.get(rowIndex).isEmpty() && "surprise".equals(rowItems.get(rowIndex).get(0).kind)), scrolling = scrollY.step(dt);
        for (int r = 0; r < rowItems.size() && r < MAX_ROWS; r++) rowsMoving |= step(rowFocus[r], dt) | rowShift[r].step(dt);
        if (rowsMoving) positionRows();
        boolean tilting = tiltX.step(dt) | tiltY.step(dt);
        if (tilting) {
            if (zone == Z_SHELF) tilesMoving = true;
            else if (zone == Z_ROWS) rowsMoving = true;
        }
        if (sceneIndex >= 0 && sceneIndex < shelf.size() && motionOn() && up - sceneAt < SCENE_DELAY_MS + SCENE_ZOOM_MS + 100
                && shelf.get(sceneIndex).pkg != null && appScene.containsKey(shelf.get(sceneIndex).pkg)) tilesMoving = true;
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || scrolling || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (rowsMoving || rowsDirty) rowsLayer.invalidate();
        if (scrolling) {
            sky.invalidate();       // the photo turns into its frosted version as the page scrolls
            status.invalidate();    // and the bar's scrim comes in
        }
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = rowsDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            if (!picksOn()) heroLayer.invalidate();
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving || rowsMoving || scrolling
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty();
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        boolean panel = picksOn();   // V1.6: a panel at the right over the page, which stays where it is
        float lift = panel ? 0 : -d * shift, page = lift - scrollY.value * k;
        show(heroLayer, top * (1 - smoothstep(60, 360, scrollY.value)), page);
        show(status, top, lift);
        if (realBlur && Math.abs(scrimNow() - scrimDrawn) > 0.002f) invalidate();
        show(platter, home, page);
        show(tiles, home, page);
        show(rowsLayer, home, lift);
        if (rowsPage != scrollY.value) {
            rowsPage = scrollY.value;
            rowsLayer.invalidate();
        }
        float da = home * (panel ? smoothstep(0, 0.25f, dc) : smoothstep(0.1f, 0.6f, dc));
        drawerLayer.setTranslationY(panel ? 0 : (1 - d) * shift);
        drawerLayer.setTranslationX(panel ? (1 - d) * (PANEL_W + 80) * k : 0);
        drawerLayer.setAlpha(da);
        if (panel && Math.abs(dc - drawerDim) > 0.002f) invalidate();   // the page's dimming follows the panel
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private void updateSleep() {
        boolean here = Prefs.sleepShown() && !android.provider.Settings.canDrawOverlays(getContext());   // else SleepOverlay
        int left = here ? SleepTimer.minutesLeft(getContext()) : 0;
        String text = left == 0 ? "" : Ui.trf("Off in {time}", "time", SleepTimer.duration(left));
        if (text.equals(sleepText)) return;
        sleepText = text;
        statusDirty = true;
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean sameRecent(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Recs.Pick p = a.get(i), q = b.get(i);
            if (!(p.title + p.pkg + p.meta).equals(q.title + q.pkg + q.meta)) return false;
        }
        return true;
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        if (!scene.shaded()) {   // Android 8 to 12: a gray image would be one more full-area pass for the flat look
            scene.clearFrost();
            scene.clearGlass();
            nightPair = false;
            return;
        }
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new Shader[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = Math.min(shelf.size(), shelfVisible);
        float gap = TILE_GAP * k;
        // A row of 16:9 tiles (the TV banners' own shape) from the left margin; the clip is the whole screen width, so
        // the next app peeks in at the right edge.
        float tw = APP_W * k, th = APP_H * k, left = MARGIN * k, top = APPS_TOP * k;
        platterRect.set(0, top - 60 * k, w, top + th + 60 * k);
        shelfFirst = Math.max(0, Math.min(shelfFirst, shelf.size() - n));
        float x = left - shelfFirst * (tw + gap), y = top;
        for (int i = 0; i < shelf.size(); i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            snap(tileRects[i]);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Scrolls the shelf, one app at a time, so the focused app is shown: the tiles slide there (unless `snap`). */
    private void revealShelf(boolean snap) {
        int n = Math.min(shelf.size(), shelfVisible);
        int first = Math.max(0, Math.min(Math.max(shelfFirst, shelfIndex - n + 1), Math.min(shelfIndex, shelf.size() - n)));
        if (first == shelfFirst || tileRects.length == 0) return;
        float dx = (shelfFirst - first) * (tileRects[0].width() + TILE_GAP * k);
        for (int i = 0; i < tileRects.length; i++) {
            tileRects[i].offset(dx, 0);
            if (!snap && i < tileSlide.length) tileSlide[i].value -= dx;
        }
        shelfFirst = first;
        tilesDirty = true;
        if (snap) return;
        reorderAnim = true;   // the same slides as moving an app
        wake();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty() || picksOn()) return;   // the apps are a row on the photo: no glass bar
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        boolean clock = !picksOn();
        // At the right margin, under the round buttons: the shelf's glass is narrower and centred with few apps.
        float right = CONTENT_R * k;
        applyTone();
        Paint text = clock ? lClockQuote : lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = 3; i < 3 + lightButtons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        // One read of the list, so n always matches the coins drawn below.
        int[] coins = HomeData.active();
        int n = coins.length;
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = clock ? L_QUOTES_BASE * k : L_QUOTES_ROW * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? MARGIN * k : right - total;
        int coinColor = clock ? text.getColor() : Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade);
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    /** 0 to 1: how much the shelf has the focus (drawTiles works it out, drawShelfTile shades the other tiles by it). */
    private float shelfOn;

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        // How much the shelf has the focus: the focus springs add up to about 1 while it moves from tile to tile, so the
        // other tiles' shade doesn't flicker and fades as the focus leaves the shelf.
        float on = 0;
        for (int i = 0; i < shelf.size(); i++) on += Math.max(0, tileFocus[i].value);
        shelfOn = Math.min(1, on);
        // The whole shelf shows it has the focus too: a rim just outside its glass (dark by day: white wouldn't show on
        // white glass). Here and not on the glass's layer, which only changes with the background.
        if (!shelf.isEmpty() && picksOn()) {
            String title = Ui.tr("Your apps");
            c.drawText(title, MARGIN * k - Ui.leftBearing(appsTitle, title), APPS_TITLE_BASE * k, appsTitle);
        }
        if (shelfOn > 0.01f && !shelf.isEmpty() && !picksOn()) {
            float out = 6 * k, radius = (TILE_RADIUS + SHELF_PAD) * k + out;
            tmp.set(platterRect.left - out, platterRect.top - out, platterRect.right + out, platterRect.bottom + out);
            rim.setShader(null);
            rim.setColor(Ui.alpha(tone(0xFF3A3A3C, 0xFFFFFFFF), tone(0.45f, 0.8f) * shelfOn));
            rim.setStrokeWidth(3 * k);
            c.drawRoundRect(tmp, radius, radius, rim);
            rim.setStrokeWidth(2.5f * k);
        }
        int last = Math.min(shelf.size(), shelfFirst + shelfVisible);
        // Scrolling, tiles slide in and out through the glass's sides (only those that show any part are drawn). Only
        // then: on a shelf that fits, the clip would cut the end tiles' shadows in a straight line.
        boolean scrolls = shelf.size() > shelfVisible;
        if (scrolls) {
            c.save();
            c.clipRect(platterRect.left, 0, platterRect.right, 2 * h);
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                float slid = i < tileSlide.length ? tileSlide[i].value : 0;
                if (tileRects[i].right + slid <= platterRect.left || tileRects[i].left + slid >= platterRect.right) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
        if (scrolls) c.restore();
        // More apps than fit: a small arrow inside the glass's edge on each side that has more, where no tile reaches
        // even focused (inside the screen's safe area).
        float cy = platterRect.centerY(), size = 28 * k, inset = 14 * k;
        int ink = tone(0x99000000, 0xCCFFFFFF);
        if (picksOn()) return;   // the row peeks past the edge instead of showing arrows
        if (last < shelf.size()) Symbols.draw(getContext(), c, "chevron_right", platterRect.right - inset, cy, size, ink);
        if (shelfFirst > 0) {
            c.save();
            c.scale(-1, 1, platterRect.left + inset, cy);
            Symbols.draw(getContext(), c, "chevron_right", platterRect.left + inset, cy, size, ink);
            c.restore();
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        boolean tilted = picksOn() && zone == Z_SHELF && i == shelfIndex && fc > 0.01f && tiltBegin(c, cx, cy);
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new Shader[shelf.size()];
                tileShaderH = bh;
                specTile = null;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                tileGray = null;
            }
            if (tileShaders[i] == null) {
                int[] deep = BRAND_DEEP.get(it.key);
                tileShaders[i] = deep != null && picksOn()
                        ? new android.graphics.RadialGradient(0, bh * 0.65f, bw * 0.95f, deep, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP)
                        : new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            boolean gray = picksOn() && BRAND_DEEP.containsKey(it.key);
            if (gray) {
                // At rest, near-black (the focused tile's own base); focused, the brand's glow comes in.
                if (tileGray == null) tileGray = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xFF141416, 0xFF0C0C0E, Shader.TileMode.CLAMP);
                tileFill.setShader(tileGray);
                tileFill.setAlpha(255);
                c.drawRoundRect(tmp, r0, r0, tileFill);
            }
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(gray ? Math.round(255 * fc) : 255);
            if (!gray || fc > 0.004f) c.drawRoundRect(tmp, r0, r0, tileFill);
            tileFill.setAlpha(255);
            tileFill.setShader(null);
            c.restore();
            Backdrop scene = null;   // no scene photo in the tile: most apps had none, and it hid the brand
            if (scene != null) {
                // Waits SCENE_DELAY_MS (just passing through shows nothing), fades in, then zooms out slowly and settles.
                float t = (SystemClock.uptimeMillis() - sceneAt - SCENE_DELAY_MS) / 1000f;
                float in = motionOn() ? Ui.clamp(t * 1000 / SCENE_IN_MS) : 1;
                float p = motionOn() ? Ui.clamp(t * 1000 / SCENE_ZOOM_MS) : 1, e = 1 - (1 - p) * (1 - p) * (1 - p);
                float a = fc * in;
                if (a > 0.004f) {
                    drawCover(c, scene.wide, scene.wideShader, cell, r, a, 1.28f - 0.24f * e, 0.05f - 0.065f * e);
                    tileFill.setColor(Ui.alpha(0xFF000000, 0.42f * a));
                    c.drawRoundRect(cell, r, r, tileFill);
                }
            }
            drawLogo(c, it, cell, s, picksOn() && BRAND_DEEP.containsKey(it.key) ? 0 : style[2]);   // the brand's own logo, in color
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, it.tileShader, cell, r);
        }
        // Which one has the focus, at a glance: a light rim on it (as on the focused card) and a shade on the others.
        float shade = picksOn() ? 0 : shelfOn * (1 - fc) * (1 - Ui.clamp(l));
        if (shade > 0.01f) {
            tileFill.setShader(null);
            tileFill.setColor(Ui.alpha(0xFF000000, tone(0.34f, 0.48f) * shade));
            c.drawRoundRect(cell, r, r, tileFill);
        }
        if (fc > 0.01f && !picksOn()) {   // V1.6: no white rim or sheen; the lift, the shadow and the glow show the focus
            float inset = 1.25f * k;
            tmp.set(cell.left + inset, cell.top + inset, cell.right - inset, cell.bottom - inset);
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.85f * fc));
            c.drawRoundRect(tmp, r - inset, r - inset, rim);
        }
        if (tilted) c.restore();
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`: the shader of an anti-aliased rounded rectangle. A
     * clipPath there isn't anti-aliased on some GPUs (stair-stepped corners on a MediaTek TV), and on Android 8 to 11 it
     * cost a mask on every frame of a focus animation (measured on a Mi Box 3: most of a 49 ms frame). `shader` is the
     * image's own, made with it (HomeApps.Item, Backdrop) and dropped with it; the canvas does the scaling, so drawing
     * changes nothing in it and allocates nothing.
     */
    private void roundImage(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r) {
        float sx = cell.width() / image.getWidth(), sy = cell.height() / image.getHeight();
        c.save();
        c.translate(cell.left, cell.top);
        c.scale(sx, sy);
        imageFill.setShader(shader);
        c.drawRoundRect(0, 0, image.getWidth(), image.getHeight(), r / sx, r / sy, imageFill);
        imageFill.setShader(null);
        c.restore();
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        boolean rest = s < 1 + FOCUS_GROW / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + FOCUS_GROW;
        Bitmap out = Ui.downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.quickSettings(c);   // picture and sound first: the TV's most used settings
        // V1.6 (the panel): the TV's settings, not the inputs and Live TV (asked: unused here).
        if (!Recs.hasKey(c)) list.addAll(HomeApps.allInputs(c));
        list.addAll(HomeApps.drawerShortcuts(c));
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    /** V1.6's drawer: a panel at the right (the prototype's): the weather, the hours, the quotes, then the shortcuts. */
    private static final float PANEL_W = 640, PANEL_TOP = 40, PANEL_PAD = 44, PANEL_R = 44, PANEL_ITEM_H = 84, PANEL_GAP = 14;
    private final RectF panelRect = new RectF();
    private float panelGridTop;

    private void layoutPanel() {
        float right = (1920 - 40) * k, left = right - PANEL_W * k;
        panelRect.set(left, PANEL_TOP * k, right, (1080 - PANEL_TOP) * k);
        int n = drawerItems.size(), rows = (n + 1) / 2;
        float inner = PANEL_W - 2 * PANEL_PAD, cw = (inner - PANEL_GAP) / 2;
        float bottom = 1080 - PANEL_TOP - PANEL_PAD;
        float ih = Math.min(PANEL_ITEM_H, (bottom - panelContentBottom() - 48 - (rows - 1) * PANEL_GAP) / Math.max(1, rows));
        panelGridTop = bottom - rows * ih - (rows - 1) * PANEL_GAP;
        for (int i = 0; i < n; i++) {
            float x = 1920 - 40 - PANEL_W + PANEL_PAD + (i % 2) * (cw + PANEL_GAP), y = panelGridTop + (i / 2) * (ih + PANEL_GAP);
            drawerRects[i].set(x * k, y * k, (x + cw) * k, (y + ih) * k);
            snap(drawerRects[i]);
        }
        for (HomeApps.Item it : drawerItems) {
            Symbols.warm(getContext(), it.symbol, 30 * k);
            Symbols.warm(getContext(), it.symbol, 30 * k * 1.04f);
        }
        Symbols.warmWeather(getContext(), 32 * k);
        drawerDirty = true;
    }

    /** Where the weather and quotes end (1080p px), for the shortcuts under them. */
    private float panelContentBottom() {
        float y = PANEL_TOP + PANEL_PAD;
        if (Prefs.weather()) y += 430;
        if (Prefs.quotes()) y += 26 + 44 * HomeData.active().length;
        return y;
    }

    private void layoutDrawer() {
        if (picksOn()) {
            layoutPanel();
            return;
        }
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            snap(drawerRects[i]);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.quotes() && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        snap(weatherCard);
        snap(quotesCard);
        // The symbols at their sizes at rest and focused: drawing them never scales one.
        for (HomeApps.Item it : drawerItems) {
            Symbols.warm(getContext(), it.symbol, 34 * k);
            Symbols.warm(getContext(), it.symbol, 34 * k * 1.07f);
        }
        Symbols.warmWeather(getContext(), 52 * k);
        Symbols.warmWeather(getContext(), 30 * k);
        Symbols.warm(getContext(), "sunrise", 22 * k);
        Symbols.warm(getContext(), "sunset", 22 * k);
        drawerDirty = true;
    }

    private boolean drawerFromTop;   // opened from the top bar's dollar/weather/time: closing goes back there

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerFromTop = zone == Z_TOP;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        layoutDrawer();
        drawerIndex = picksOn() || shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (drawerFromTop) {
            zone = Z_TOP;
            topIndex = GLANCE;
            return;
        }
        if (!picksOn() && !shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        if (picksOn()) {
            drawPanel(c);
            return;
        }
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        dayLow.setColor(tone(L_DIM, 0xADFFFFFF));
        pricePaint.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (Prefs.quotes() && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
    }

    private Paint pCity, pTemp, pCond, pDim, pHour, pHourTemp, pCoin, pCode, pPrice, pChange, pHead, pItem, pLine;

    private void panelPaints() {
        if (pCity != null && pCity.getTextSize() == 26 * k) return;
        pCity = Ui.text(Ui.semibold, 26 * k, 0xADEBEBF5);
        pTemp = Ui.numbers(Ui.light, 108 * k, 0xFFFFFFFF);
        pTemp.setLetterSpacing(-0.05f);
        pCond = Ui.text(Ui.medium, 28 * k, 0xFFFFFFFF);
        pDim = Ui.numbers(Ui.regular, 23 * k, 0xADEBEBF5);
        pHour = Ui.numbers(Ui.medium, 22 * k, 0xADEBEBF5);
        pHour.setTextAlign(Paint.Align.CENTER);
        pHourTemp = Ui.numbers(Ui.semibold, 26 * k, 0xFFFFFFFF);
        pHourTemp.setTextAlign(Paint.Align.CENTER);
        pCoin = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);
        pCode = Ui.text(Ui.medium, 18 * k, 0x80EBEBF5);
        pPrice = Ui.numbers(Ui.semibold, 26 * k, 0xFFFFFFFF);
        pPrice.setTextAlign(Paint.Align.RIGHT);
        pChange = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        pChange.setTextAlign(Paint.Align.RIGHT);
        pHead = Ui.text(Ui.bold, 20 * k, 0x80EBEBF5);
        pHead.setLetterSpacing(0.06f);
        pItem = Ui.text(Ui.semibold, 23 * k, 0xFFFFFFFF);
        pLine = new Paint();
        pLine.setColor(0x1FFFFFFF);
    }

    /** The prototype's drawer: a dark glass panel; weather, the next hours, the quotes and a grid of shortcuts. */
    private void drawPanel(Canvas c) {
        panelPaints();
        refreshDrawerText();
        RectF p = panelRect;
        tmp.set(p);
        tmp.offset(0, 30 * k);
        shadow.setColor(0x80000000);
        shadows.draw(c, tmp, PANEL_R * k, SHADOW_BLUR * k, shadow);
        fill.setShader(null);
        fill.setColor(0xF01E1E22);
        c.drawRoundRect(p, PANEL_R * k, PANEL_R * k, fill);
        rim.setShader(null);
        rim.setColor(0x1FFFFFFF);
        rim.setStrokeWidth(1.5f * k);
        c.drawRoundRect(p.left + 0.75f * k, p.top + 0.75f * k, p.right - 0.75f * k, p.bottom - 0.75f * k, PANEL_R * k, PANEL_R * k, rim);
        rim.setStrokeWidth(2.5f * k);
        float x = p.left + PANEL_PAD * k, right = p.right - PANEL_PAD * k, y = p.top + PANEL_PAD * k;
        HomeData.Weather wx = textWeather;
        if (Prefs.weather()) {
            c.drawText(weatherTitle, x, y + 22 * k, pCity);
            if (wx != null && !Double.isNaN(wx.temp)) {
                c.drawText(nowTemp, x - Ui.leftBearing(pTemp, nowTemp), y + 140 * k, pTemp);
                c.drawText(HomeData.condition(wx.code, wx.isDay), x, y + 190 * k, pCond);
                c.drawText(panelLine, x, y + 228 * k, pDim);
                // The next five hours, between two hairlines.
                float top = y + 262 * k, bottom = top + 150 * k;
                c.drawRect(x, top, right, top + Math.max(1, k), pLine);
                c.drawRect(x, bottom, right, bottom + Math.max(1, k), pLine);
                float cw = (right - x) / 5;
                for (int i = 0; i < 5 && i < wx.hours.size(); i++) {
                    float cx = x + cw * (i + 0.5f);
                    c.drawText(hourText[i], cx, top + 44 * k, pHour);
                    Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), cx, top + 78 * k, 32 * k, 0xEBFFFFFF);
                    c.drawText(hourTempText[i], cx, top + 128 * k, pHourTemp);
                }
            } else {
                c.drawText(Ui.tr("No weather data right now."), x, y + 80 * k, pDim);
            }
            y += 430 * k;
        }
        if (Prefs.quotes()) {
            y += 26 * k;
            int[] coins = HomeData.active();
            for (int j = 0; j < coins.length; j++) {
                int i = coins[j];
                float base = y + (30 + 44 * j) * k;
                String name = HomeData.coinName(i);
                c.drawText(name, x, base, pCoin);
                c.drawText(i == 3 ? "USD" : HomeData.COINS[i], x + pCoin.measureText(name) + 10 * k, base, pCode);   // the dollar (priced through USDT)
                double change = data.changes[i];
                float px = right;
                if (!Double.isNaN(change)) {
                    pChange.setColor(change >= 0 ? UP_COLOR : DOWN_COLOR);
                    c.drawText(changeText[i], px, base, pChange);
                    px -= pChange.measureText(changeText[i]) + 12 * k;
                }
                c.drawText(priceText[i], px, base, pPrice);
            }
        }
        String head = Ui.tr("Shortcuts").toUpperCase(Ui.locale());
        c.drawText(head, x, panelGridTop * k - 18 * k, pHead);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawPanelItem(c, i);
        if (top >= 0) drawPanelItem(c, top);
    }

    private boolean night() {
        HomeData.Weather wx = data.weather;
        long now = System.currentTimeMillis();
        return wx.sunset != 0 && (now >= wx.sunset || (wx.sunrise != 0 && now < wx.sunrise));
    }

    /** A shortcut: its symbol and name on a faint fill; focused, white with dark ink, a little larger. */
    private void drawPanelItem(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.04f * f;
        RectF b = drawerRects[i];
        float cx = b.centerX(), cy = b.centerY();
        cell.set(cx - b.width() * s / 2, cy - b.height() * s / 2, cx + b.width() * s / 2, cy + b.height() * s / 2);
        float r = 24 * k * s;
        fill.setShader(null);
        fill.setColor(blend(0x12FFFFFF, 0xFFFFFFFF, f));
        c.drawRoundRect(cell, r, r, fill);
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(off ? 0x6BFFFFFF : 0xFFFFFFFF, INK, f);
        float ix = cell.left + 22 * k * s, size = 30 * k * s, sw = Symbols.width(getContext(), it.symbol, size);
        Symbols.draw(getContext(), c, it.symbol, ix + sw / 2, cy, size, ink);
        pItem.setColor(ink);
        pItem.setTextSize(23 * k * s);
        fitPaint.set(pItem);
        float room = cell.right - 18 * k - (ix + sw + 16 * k * s);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, ix + sw + 16 * k * s, cy + Ui.halfCap(pItem), pItem);
        pItem.setTextSize(23 * k);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours or 5 days, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k;
        if (textDays) {
            float hw = (r.right - 30 * k - hx) / 5;
            for (int i = 0; i < wx.days.size() && i < 5; i++) {
                float hc = hx + hw * (i + 0.5f);
                c.drawText(dayText[i], hc, r.top + 96 * k, hourPaint);
                Symbols.draw(getContext(), c, Symbols.weather(wx.dayCode.get(i), true), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
                // The high, then the low dimmed, centered together (closer than the next day, so the pairs read apart).
                float maxW = hourTemp.measureText(dayMaxText[i]), minW = dayLow.measureText(dayMinText[i]), gap = 6 * k;
                float left = hc - (maxW + gap + minW) / 2;
                c.drawText(dayMaxText[i], left + maxW / 2, r.top + 196 * k, hourTemp);
                c.drawText(dayMinText[i], left + maxW + gap + minW / 2, r.top + 196 * k, dayLow);
            }
        } else {
            float hw = (r.right - 30 * k - hx) / 6;
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                float hc = hx + hw * (i + 0.5f);
                c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
                Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
                c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
            }
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = HomeData.highLow(wx, "   ");
            textDays = Prefs.forecast().equals("days") && !wx.days.isEmpty();   // no days yet (an older forecast, offline): the hours
            for (int i = 0; textDays && i < wx.days.size() && i < 5; i++) {
                dayText[i] = HomeData.weekday(wx.days.get(i));
                dayMaxText[i] = HomeData.degrees(wx.dayMax.get(i));
                dayMinText[i] = HomeData.degrees(wx.dayMin.get(i));
            }
            for (int i = 0; !textDays && i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = Ui.trf("Sunrise {time}", "time", Ui.time(wx.sunrise));
            sunsetText = Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset));
            panelLine = HomeData.highLow(wx, " · ") + (wx.sunset != 0 ? " · " + sunsetText : "");
        }
        long minute = System.currentTimeMillis() / 60_000;
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt;
            textMinute = minute;
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            staleText = stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale);
            for (int i = 0; i < HomeData.COINS.length; i++) {
                priceText[i] = HomeData.brl(data.prices[i]);
                changeText[i] = HomeData.change(data.changes[i]);
            }
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 8 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();
    private String panelLine = "";

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        refreshDrawerText();
        c.drawText(Ui.tr("Crypto"), x, r.top + 54 * k, cardTitle);
        c.drawText(staleText, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(priceText[i], right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(changeText[i], tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        revealShelf(snap);
        int scene = picksOn() && zone == Z_SHELF && !sheetOpen ? shelfIndex : -1;
        if (scene != sceneIndex) {
            sceneIndex = scene;
            sceneAt = SystemClock.uptimeMillis();
        }
        if (!snap && motionOn() && (lastDx != 0 || lastDy != 0) && (zone == Z_SHELF || zone == Z_ROWS)) {
            tiltX.velocity += lastDx * 90;
            tiltY.velocity -= lastDy * 70;
        }
        lastDx = lastDy = 0;
        if (picksOn() && !sheetOpen && zone != Z_DRAWER && zone != WIDGETS) followBackdrop();   // Back, the drawer…: the photo follows
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        boolean rowsChanged = false;
        for (int r = 0; r < MAX_ROWS; r++) rowsChanged |= aim(rowFocus[r], !sheetOpen && zone == Z_ROWS && rowIndex == r ? rowCol : -1, snap);
        if (zone == Z_ROWS && rowIndex < rowItems.size()) {
            int n = rowItems.get(rowIndex).size();
            float[] x = rowX[rowIndex];
            int last = Math.max(0, n - 1), col = Math.min(rowCol, last);
            // The focus moves to the third item, then the row slides under it, until its end reaches the right margin.
            float max = Math.max(0, rowEnd[rowIndex] - (CONTENT_R - MARGIN));
            rowShift[rowIndex].target = Math.max(0, Math.min(max, x[col] - x[Math.min(col, 2)]));
            if (snap) rowShift[rowIndex].snap(rowShift[rowIndex].target);
        }
        rowsDirty |= snap || rowsChanged;
        scrollY.target = scrollTarget();
        if (snap) {
            scrollY.snap(scrollY.target);
            motionDirty = true;
        }
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    /** All Apps from the remote's button. Coming back from another app, resume() opens it (it closes the sheet). */
    void showAllApps() {
        if (!running) {
            allAppsOnResume = true;
            return;
        }
        reset(false);
        openSheet();
    }

    private boolean allAppsOnResume;

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        if (shelfEdited) {     // the grid was kept as it was while open: apps that went to the shelf leave it now
            shelfEdited = false;
            reload();
        }
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i), gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, HomeApps.Item it, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (it.tile != null) roundImage(c, it.tile, it.tileShader, cell, r);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f && !picksOn()) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        // The shelf and All Apps (100 to 500 ms of package queries on a TV) only when they can have changed.
        long up = SystemClock.uptimeMillis();
        final boolean apps = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration
                || up - appsAt > APPS_MAX_AGE_MS;
        if (apps) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = apps ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            final List<HomeApps.Item> top = apps ? HomeApps.topButtons() : null;   // they change with Prefs too
            handler.post(() -> {
                if (top != null) setLightButtons(top);
                if (s != null) setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                if (o != null && !sheetOpen) setOthers(o);
                else if (o != null) appsStale = true;   // All Apps is open: its list comes next time
            });
        });
    }

    /** Apps installed, removed or updated (also while another app is open): the next reload reads them again. */
    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        if (running) reloadSoon();
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    /** Like the prototype: on the hero, the next title comes by itself every 7 s (any key starts the wait over). */
    private static final long HERO_AUTO_MS = 7000;
    private final Runnable heroAuto = new Runnable() {
        @Override public void run() {
            if (!running) return;
            handler.postDelayed(this, HERO_AUTO_MS);
            if (sheetOpen || (zone != Z_CARDS && zone != Z_TOP) || cards.size() < 2 || !motionOn()) return;
            int next = (cardIndex + 1) % cards.size();
            Backdrop b = recs.backdrop(cards.get(next));
            if (b == null) {   // its photo isn't ready yet: a little later
                handler.removeCallbacks(this);
                handler.postDelayed(this, 1000);
                return;
            }
            cardIndex = next;
            layoutHeroButton();
            showBackdrop(b);
            recs.backdrop(cards.get((next + 1) % cards.size()));   // the one after, built ahead
            heroDirty = true;
            spoken.focusMoved();
            invalidateAll();
        }
    };

    boolean onKey(KeyEvent e) {
        wake();
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            handler.removeCallbacks(heroAuto);
            if (running) handler.postDelayed(heroAuto, HERO_AUTO_MS);
        }
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held (~0.5 s) on a shelf app: move mode; on an app in All Apps: on or off the shelf. A timer, since
                // not every remote repeats a held key, or the long-press repeat, whichever comes first; the release
                // after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = sheetOpen ? canToggleShelf(gridIndex) : canReorder();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.back();
    }

    private void move(int dx, int dy) {
        lastDx = dx;
        lastDy = dy;
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default:
                if (picksOn()) {   // the panel's grid: two columns; left from the left column closes it (it came from there)
                    int n = drawerItems.size(), col = drawerIndex % 2;
                    if (dx < 0 && col == 0) {
                        closeDrawer();
                    } else if (dx != 0) {
                        int next = drawerIndex + dx;
                        if (next < 0 || next >= n || next / 2 != drawerIndex / 2) return;
                        drawerIndex = next;
                    } else {
                        int next = drawerIndex + 2 * dy;
                        if (next < 0 || next >= n) return;
                        drawerIndex = next;
                    }
                    break;
                }
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        HomeApps.Item it;
        RectF r;
        Recs.Pick openedPick = null;   // a title: it goes to "Recently watched"
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            openedPick = p;
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex == SEARCH) {
                click.open();
                askSearch();
                return;
            }
            if (topIndex == GLANCE) {
                click.open();
                openDrawer();   // the widgets: the forecast, the prices, the inputs
                retarget(false);
                return;
            }
            if (topIndex < 3) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
        } else if (zone == Z_ROWS) {
            List<Recs.Pick> list = rowList(rowIndex);
            Recs.Pick p = rowCol < list.size() ? list.get(rowCol) : null;
            if (p == null || (p.pkg == null && !"surprise".equals(p.kind))) return;
            if ("surprise".equals(p.kind)) {
                if (surpriseLanded == null || spinning()) {
                    click.open();
                    spin();
                    rowsDirty = true;
                    return;
                }
                p = surpriseLanded;   // landed: OK opens it
            }
            it = p.recentApp ? HomeApps.app(p.pkg, p.title, null) : HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            if (p.recentApp) it.key = p.app;
            else openedPick = p;
            r = rowRects[rowIndex][rowCol];
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p != null && "nowplaying".equals(p.kind)) {
                click.open();
                NowPlayingActivity.open(getContext());
                return;
            }
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            openedPick = p;
            r = cardRects[cardIndex];
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        if (openedPick != null && openedPick.link == null) {
            final Recs.Pick pick = openedPick;
            final RectF from = new RectF(r);
            // Watchmode: the title's own page in its app, looked up now if it wasn't before (about a second).
            if (recs.linkThen(pick, () -> openTitle(pick, from))) return;
        }
        openItem(it, r, openedPick);
    }

    private void openTitle(Recs.Pick p, RectF r) {
        if (!running) return;
        openItem(HomeApps.app(p.pkg, Recs.serviceName(p), p.link), r, p);
    }

    private void openItem(HomeApps.Item it, RectF r, Recs.Pick openedPick) {
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) {
            showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
        } else if (openedPick != null) {
            Recent.add(getContext(), openedPick);
        } else if (it.kind == HomeApps.APP && it.pkg != null) {
            Recent.addApp(getContext(), it.key, it.pkg, it.label);
        }
    }

    // MARK: - Holding OK: All Apps puts the app on the shelf or takes it off; on the shelf, the app is lifted to move

    private void hold() {
        if (sheetOpen) toggleShelf(); else startReorder();
    }

    /** TalkBack's long click on an app in All Apps (once per press, if the remote's hold also arrives). */
    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;     // the remote's release must not open the app
        toggleShelf();
    }

    private boolean canToggleShelf(int i) {
        if (!sheetOpen || i >= others.size()) return false;
        HomeApps.Item it = others.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Puts the focused app at the end of the shelf, or takes it off if it's there; a toast says which. */
    private void toggleShelf() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canToggleShelf(gridIndex)) return;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
        final HomeApps.Item it = others.get(gridIndex);
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            // On the shelf means shown there: installed and within the limit, as in Settings > Shelf.
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            PackageManager pm = c.getPackageManager();
            int max = HomeApps.maxShelf(c), shown = 0;
            boolean on = false;
            for (String p : list) {
                if (shown >= max) break;
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final String text;
            list.remove(it.pkg);
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", it.label);
            } else {
                if (shown >= max) {
                    handler.post(() -> showToast(Ui.tr("The shelf is full. Make room in Free My TV › Shelf.")));
                    return;
                }
                list.add(it.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", it.label);
            }
            Prefs.setShelf(list);   // here, so the next hold reads the new list
            handler.post(() -> {
                shelfEdited = true;
                showToast(text);
                reload();
            });
        });
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            Shader g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                Shader[] shaders = tileShaders.length == n ? new Shader[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = picksOn() ? 0 : 3; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < Math.min(3, cards.size()); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = shelfFirst; i < Math.min(shelf.size(), shelfFirst + shelfVisible); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP:
                if (i == GLANCE) return Ui.tr("Dollar, weather and time. OK opens the widgets.");
                if (i == SEARCH) return Ui.tr("Search");
                if (i >= 3) return label(lightButtons, i - 3);
                return Ui.trf("{name}, tab {i} of {n}", "name", tabs()[i], "i", i + 1, "n", 3);
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : quotesText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i == GLANCE || i == SEARCH) {
                    a11yRect.set(i == GLANCE ? glanceRect : searchRect);
                    dy = status.getTranslationY();
                    break;
                }
                if (i >= 3 + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < 3 ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(3, cards.size())) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : quotesCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.weather()) out.add(0);
        if (Prefs.quotes() && HomeData.active().length > 0) out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= 3 + lightButtons.size() || (i < 3 && !picksOn())) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < 3) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= cards.size()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay)).append(". ")
                .append(Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.max), "min", HomeData.degrees(wx.min)));
        if (Prefs.forecast().equals("days") && !wx.days.isEmpty()) {   // as the card: the hours until there are days
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < wx.days.size() && i < 5; i++) {
                // "Monday: High 25°, low 18°; Tuesday: …": the whole day ("Sun" and "Sat" are words of their own), and
                // which number is the high (the sentence's own end dropped).
                String range = Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.dayMax.get(i)),
                        "min", HomeData.degrees(wx.dayMin.get(i))).replaceAll("[.。।]$", "");
                if (i > 0) list.append("; ");
                list.append(HomeData.weekdayName(wx.days.get(i))).append(": ").append(range);
            }
            s.append(' ').append(Ui.trf("Next days: {list}.", "list", list));
        } else if (!wx.hours.isEmpty()) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                if (i > 0) list.append(", ");
                list.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i)));
            }
            s.append(' ').append(Ui.trf("Next hours: {list}.", "list", list));
        }
        if (wx.sunrise != 0) s.append(' ').append(Ui.trf("talkback|Sunrise {time}", "time", Ui.time(wx.sunrise))).append('.');
        if (wx.sunset != 0) s.append(' ').append(Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset))).append('.');
        return s.toString();
    }

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private String quotesText() {
        StringBuilder s = new StringBuilder(Ui.tr("Crypto"));
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        s.append(", ").append(stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale)).append('.');
        for (int i : HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]));
            if (!Double.isNaN(data.changes[i])) s.append(", ").append(HomeData.change(data.changes[i]));
            s.append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (barTab == null) return;
        // The tabs inside the glass capsule; Settings alone at the right (the inputs and Live TV live in the drawer).
        float y = (BAR_Y + BAR_PAD) * k, ih = (BAR_H - 2 * BAR_PAD) * k, x = (MARGIN + BAR_PAD) * k;
        // The magnifier first, a round button like Settings; the tabs' capsule after it.
        searchRect.set(MARGIN * k, (BAR_Y + BAR_H / 2 - ROUND / 2) * k, (MARGIN + ROUND) * k, (BAR_Y + BAR_H / 2 + ROUND / 2) * k);
        float tabsLeft = picksOn() ? MARGIN + ROUND + 16 : MARGIN;
        x = (tabsLeft + BAR_PAD) * k;
        for (int i = 0; i < 3; i++) {
            float tw = barTab.measureText(tabs()[i]);
            topRects[i].set(x, y, x + tw + 2 * TAB_PAD * k, y + ih);
            snap(topRects[i]);
            x = topRects[i].right + 4 * k;
        }
        barRect.set(tabsLeft * k, BAR_Y * k, x - 4 * k + BAR_PAD * k, (BAR_Y + BAR_H) * k);
        snap(barRect);
        float b = ROUND * k, right = CONTENT_R * k, cy = (BAR_Y + BAR_H / 2) * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[3 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + 16 * k;
        }
        layoutHeroButton();
        layoutRows();
        for (HomeApps.Item it : lightButtons) {
            Symbols.warm(getContext(), it.symbol, 28 * k);
            Symbols.warm(getContext(), it.symbol, 28 * k * 1.08f);
        }
        Symbols.warmWeather(getContext(), 30 * k);
        heroDirty = statusDirty = true;
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        List<Recs.Pick> list = recs.now(TAB_KIND[tab]);
        Recs.Pick listen = tab == 0 ? rowsData.listenNow() : null;
        Recs.Pick now = tab == 0 ? nowPick() : null;
        if (now != null) listen = null;   // music is on: "Now playing" instead of a suggestion
        if (listen != null && list.size() > 1) {   // "For you": the hour's playlist second, among the titles
            list = new ArrayList<>(list);
            list.add(1, listen);
        }
        if (now != null) {
            list = new ArrayList<>(list);
            list.add(0, now);
        }
        if (list.size() > HERO_MAX) list = new ArrayList<>(list.subList(0, HERO_MAX));
        boolean picksChanged = !samePicks(list, cards);
        if (picksChanged) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        // The hero's photo and its neighbours, built ahead (all ten of each tab wouldn't fit in memory).
        for (int d = -1; d <= 2 && !cards.isEmpty(); d++) recs.backdrop(cards.get(((cardIndex + d) % cards.size() + cards.size()) % cards.size()));
        // Picks bring their own background; without them (no TMDB key), your photo if there is one.
        boolean own = !picksOn() && Photos.on(getContext());
        Backdrop b = own ? Photos.backdrop(getContext(), ownReady) : cards.isEmpty() ? null
                : recs.backdrop(cards.get(Math.min(cardIndex, cards.size() - 1)));
        if (b != null || (cards.isEmpty() && !own)) showBackdrop(b);
        layoutHeroButton();
        // The rows never repeat what the hero shows (on the Movies tab, its movies are the hero's: that row goes away).
        List<List<Recs.Pick>> items = new ArrayList<>();
        List<String> titles = new ArrayList<>(), subtitles = new ArrayList<>();
        List<Boolean> ranked = new ArrayList<>(), square = new ArrayList<>();
        List<Boolean> chips = new ArrayList<>();
        if (!searchResults.isEmpty()) {
            items.add(searchResults);
            titles.add(Ui.trf("Results for “{q}”", "q", searchQuery));
            subtitles.add("");
            ranked.add(false);
            square.add(false);
            chips.add(true);
        }
        // Spotify's playlists right under the apps.
        for (Rows.Row row : rowsData.rows()) {
            if (!row.square || items.size() >= MAX_ROWS) continue;
            items.add(row.items);
            titles.add(row.title);
            subtitles.add("");
            ranked.add(false);
            square.add(true);
            chips.add(false);
        }
        List<Recs.Pick> recent = Recent.list(getContext());
        if (!recent.isEmpty()) {
            chips.add(true);
            items.add(recent);
            titles.add(Ui.tr("Recently watched"));
            subtitles.add("");
            ranked.add(false);
            square.add(false);
        }
        // "To watch together": "Surprise me" first, then titles in the genres you two keep coming back to.
        List<Recs.Pick> together = together(recent);
        if (!together.isEmpty()) {
            items.add(together);
            titles.add(Ui.tr("To watch together"));
            subtitles.add(togetherWhy);
            ranked.add(false);
            square.add(false);
            chips.add(true);
        }
        for (Rows.Row row : rowsData.rows()) {
            if (items.size() >= MAX_ROWS) break;
            if (row.square) continue;   // already under the apps
            List<Recs.Pick> l = row.ranked ? new ArrayList<>(row.items) : notIn(row.items, cards);   // ranks stay whole
            if (l.size() > MAX_ITEMS) l = new ArrayList<>(l.subList(0, MAX_ITEMS));
            if (l.isEmpty()) continue;
            items.add(l);
            titles.add(row.title);
            subtitles.add(row.subtitle);
            ranked.add(row.ranked);
            square.add(row.square);
            chips.add(false);
        }
        boolean rowsChanged = !titles.equals(rowTitles) || !subtitles.equals(rowSubtitles) || items.size() != rowItems.size();
        for (int i = 0; !rowsChanged && i < items.size(); i++) rowsChanged = !sameRecent(items.get(i), rowItems.get(i));
        if (rowsChanged) {
            rowItems.clear();
            rowItems.addAll(items);
            rowTitles.clear();
            rowTitles.addAll(titles);
            rowSubtitles.clear();
            rowSubtitles.addAll(subtitles);
            rowRanked.clear();
            rowRanked.addAll(ranked);
            rowSquare.clear();
            rowSquare.addAll(square);
            rowChip.clear();
            rowChip.addAll(chips);
            if (w > 0) layoutRows();
        }
        if (zone == Z_ROWS && rowIndex >= rowItems.size()) rowIndex = Math.max(0, rowItems.size() - 1);
        if (zone == Z_ROWS && rowList(rowIndex).isEmpty()) {
            zone = Z_SHELF;
            retarget(false);
        }
        rowCol = Math.min(rowCol, Math.max(0, rowList(rowIndex).size() - 1));
        int scenes = appScene.size();
        appScene.clear();
        for (Recs.Pick p : recs.wanted()) {
            if (p.pkg == null || appScene.containsKey(p.pkg)) continue;
            Backdrop sb = recs.backdrop(p);
            if (sb != null && sb.wide != null) appScene.put(p.pkg, sb);
        }
        // Only when something changed: this runs every 15 s, and each redraw repaints a hardware layer.
        rowsDirty |= rowsChanged || picksChanged;
        tilesDirty |= appScene.size() != scenes || picksChanged;
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    private static List<Recs.Pick> notIn(List<Recs.Pick> list, List<Recs.Pick> shown) {
        List<Recs.Pick> out = new ArrayList<>();
        for (Recs.Pick p : list) {
            boolean dup = false;
            for (Recs.Pick q : shown) dup |= Recs.key(p).equals(Recs.key(q));
            if (!dup) out.add(p);
        }
        return out;
    }

    private List<Recs.Pick> rowList(int r) {
        return r >= 0 && r < rowItems.size() ? rowItems.get(r) : java.util.Collections.<Recs.Pick>emptyList();
    }

    /** How far row r is below the first (1080p px): a square row is taller. */
    private float rowOffset(int r) {
        float y = 0;
        for (int i = 0; i < r; i++) y += rowHeight(i);
        return y;
    }

    /**
     * One rhythm for every row, from one title's baseline to the next: 30 px to the card, the card, its captions (not
     * under a Top 10's posters, which carry their titles), then 60 px of air above the next title's capitals.
     */
    private float rowHeight(int r) {
        float card = ranked(r) ? RANK_CARD_H : square(r) ? SQUARE : ROW_CARD_H;
        return ROW_TITLE_TO_CARD + card + (ranked(r) ? 0 : ROW_CAPTIONS) + ROW_AIR + ROW_TITLE_CAP;
    }

    // MARK: - To watch together, and Surprise me

    private final Recs.Pick surprise = new Recs.Pick();
    private List<Recs.Pick> surprisePool = new ArrayList<>(), surpriseWheel = new ArrayList<>();
    private Rows.Card lastShown;
    private Recs.Pick surpriseLanded;
    private long surpriseAt;
    private int surpriseFrom;
    private String togetherWhy = "";
    private static final long SPIN_MS = 1700, LANDED_MS = 45_000;
    private static final int SPIN_STEPS = 22;

    /**
     * The genres in "Recently watched" (two at most, the most frequent), and every title in the rows in those genres
     * that you haven't opened. With nothing watched yet, the rows' titles as they come. "Surprise me" leads it.
     */
    private List<Recs.Pick> together(List<Recs.Pick> recent) {
        List<Recs.Pick> all = new ArrayList<>(rowsData.pool());   // a few hundred titles, beyond what the rows show
        // Not the Top 10s: they're Netflix's lists as they are (cartoons too), the rest of the rows are filtered.
        for (Rows.Row row : rowsData.rows()) if (!row.square && !row.ranked) all.addAll(row.items);
        if (all.size() < 6) return new ArrayList<>();
        java.util.Set<String> skip = new java.util.HashSet<>(java.util.Arrays.asList(Ui.tr("Series"), Ui.tr("Movie")));
        for (String s : Recs.SERVICE_NAMES.values()) skip.add(s);
        // The genres the rows know (so "now", a year or a service never count as one), and how common each is there.
        Map<String, Integer> known = new HashMap<>();
        for (Recs.Pick p : all) {
            for (String part : (p.meta == null ? "" : p.meta).split(" · ")) {
                if (!part.isEmpty() && !skip.contains(part) && !Character.isDigit(part.charAt(0))) known.put(part, known.getOrDefault(part, 0) + 1);
            }
        }
        Map<String, Integer> count = new HashMap<>();
        java.util.Set<String> watched = new java.util.HashSet<>();
        for (Recs.Pick p : recent) {
            if (p.recentApp || "playlist".equals(p.kind)) continue;
            watched.add(p.title);
            String meta = p.baseMeta == null ? "" : p.baseMeta;
            for (String part : meta.split(" · ")) {
                if (!known.containsKey(part)) continue;
                count.put(part, count.getOrDefault(part, 0) + 1);
            }
        }
        List<String> genres = new ArrayList<>(count.keySet());
        // The most watched first; a tie goes to the genre with more titles to offer.
        java.util.Collections.sort(genres, (a, b) -> !count.get(b).equals(count.get(a)) ? count.get(b) - count.get(a) : known.get(b) - known.get(a));
        if (genres.size() > 2) genres = genres.subList(0, 2);
        List<Recs.Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Recs.Pick p : all) {
            if (watched.contains(p.title) || !seen.add(Recs.key(p))) continue;
            boolean match = genres.isEmpty();
            for (String g : genres) match |= p.meta != null && java.util.Arrays.asList(p.meta.split(" · ")).contains(g);
            if (match) out.add(p);
        }
        if (out.size() < 4) return new ArrayList<>();
        if (surpriseLanded == null && !spinning()) surprisePool = new ArrayList<>(out);   // a spin keeps its own
        java.util.Collections.shuffle(out, new java.util.Random(System.currentTimeMillis() / 3600_000L));   // the row: a new mix every hour
        togetherWhy = genres.isEmpty() ? "" : Ui.trf("because you watch {genres}", "genres", String.join(Ui.tr(" and "), genres));
        if (out.size() > MAX_ITEMS - 1) out = new ArrayList<>(out.subList(0, MAX_ITEMS - 1));
        surprise.kind = "surprise";
        surprise.id = -1;
        boolean landed = surpriseLanded != null && !spinning();
        surprise.title = landed ? surpriseLanded.title : Ui.tr("Surprise me");
        surprise.meta = landed ? Ui.tr("OK to watch · ← → for another") : spinning() ? Ui.tr("Shuffling…") : Ui.tr("A title for the two of you");
        out.add(0, surprise);
        return out;
    }

    /** 45 s after it lands, "Surprise me" is itself again, ready for another spin. */
    private final Runnable surpriseReset = () -> {
        surpriseLanded = null;
        surpriseAt = 0;
        surprise.title = Ui.tr("Surprise me");
        surprise.meta = Ui.tr("A title for the two of you");
        rowsDirty = true;
        HomeView.this.rowsLayer.invalidate();
    };

    private boolean spinning() {
        return surpriseAt != 0 && SystemClock.uptimeMillis() - surpriseAt < SPIN_MS;
    }

    /** OK on "Surprise me": the covers shuffle, slowing down like a wheel, and land on one. OK again opens it. */
    private void spin() {
        if (surprisePool.isEmpty()) return;
        // The wheel: SPIN_STEPS covers from a random place in the whole pool; the last one is where it lands. Their
        // images are asked for now (they come in during the spin; one not ready yet shows the gradient for a moment).
        List<Recs.Pick> wheel = new ArrayList<>(surprisePool);
        java.util.Collections.shuffle(wheel);
        if (wheel.size() > SPIN_STEPS + 1) wheel = new ArrayList<>(wheel.subList(0, SPIN_STEPS + 1));
        for (Recs.Pick p : wheel) rowsData.card(p);
        // It lands on a title whose image is ready (most of the pool's aren't loaded until they're needed), and the
        // next spin will have more of them.
        Recs.Pick land = wheel.get(SPIN_STEPS % wheel.size());
        List<Recs.Pick> ready = new ArrayList<>();
        for (Recs.Pick p : surprisePool) if (rowsData.cardIfReady(p) != null && !p.title.equals(surprise.title)) ready.add(p);
        if (rowsData.cardIfReady(land) == null && !ready.isEmpty()) land = ready.get((int) (Math.random() * ready.size()));
        wheel.set(SPIN_STEPS % wheel.size(), land);
        surpriseWheel = wheel;
        surpriseFrom = 0;
        surpriseAt = SystemClock.uptimeMillis();
        surpriseLanded = land;
        surprise.title = Ui.tr("Surprise me");
        surprise.meta = Ui.tr("Shuffling…");
        handler.removeCallbacks(surpriseReset);
        handler.postDelayed(surpriseReset, SPIN_MS + LANDED_MS);
        handler.postDelayed(() -> {
            if (surpriseLanded == null) return;
            surprise.title = surpriseLanded.title;
            surprise.meta = Ui.tr("OK to watch · ← → for another");
            click.open();
            rowsDirty = true;
            rowsLayer.invalidate();
        }, SPIN_MS);
    }

    /** The card: a slowly turning gradient of warm and cool light with sparkles; while spinning, the covers. */
    private void drawSurprise(Canvas c, RectF cell, float r, float f) {
        long now = SystemClock.uptimeMillis();
        Rows.Card shown = null;
        if (spinning() || surpriseLanded != null) {
            float u = Math.min(1, (now - surpriseAt) / (float) SPIN_MS);
            int step = (int) (SPIN_STEPS * (1 - Math.pow(1 - u, 3)));
            Recs.Pick p = !spinning() ? surpriseLanded
                    : surpriseWheel.isEmpty() ? null : surpriseWheel.get(step % surpriseWheel.size());
            shown = p == null ? null : spinning() ? rowsData.cardIfReady(p) : rowsData.card(p);   // landed: load it if needed
            if (shown == null && spinning()) shown = lastShown;   // not loaded yet: the previous cover stays a beat longer
            if (shown != null) lastShown = shown;
        }
        if (shown != null) {
            drawCover(c, shown.bitmap, shown.shader, cell, r, 1);
            if (spinning()) {   // a soft veil while it moves: the eye reads motion, not each cover
                tileFill.setShader(null);
                tileFill.setColor(0x33000000);
                c.drawRoundRect(cell, r, r, tileFill);
            }
            return;
        }
        // Premium: near-black, with three soft lights of warm and cool color drifting slowly under it (like the light
        // in Apple's product pages), a fine highlight along the top edge, and the sparkles; no loud gradient.
        float cx = cell.centerX(), cy = cell.centerY(), w = cell.width(), hh = cell.height();
        float t = now / 1000f;
        c.save();
        c.clipRect(cell);
        tileFill.setShader(null);
        tileFill.setColor(0xFF0E0E12);
        c.drawRoundRect(cell, r, r, tileFill);
        orb(c, cx + w * 0.32f * (float) Math.sin(t / 5.3), cy + hh * 0.30f * (float) Math.cos(t / 4.1), w * 0.55f, 0x8C6E44FF);
        orb(c, cx + w * 0.30f * (float) Math.cos(t / 6.7), cy + hh * 0.34f * (float) Math.sin(t / 5.9), w * 0.5f, 0x80FF4F8B);
        orb(c, cx + w * 0.34f * (float) Math.sin(t / 7.9 + 2), cy + hh * 0.28f * (float) Math.cos(t / 6.3 + 1), w * 0.45f, 0x66FFB340);
        c.restore();
        tileFill.setShader(new LinearGradient(0, cell.top, 0, cell.top + hh * 0.5f, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(cell, r, r, tileFill);
        tileFill.setShader(null);
        rim.setShader(null);
        rim.setColor(0x26FFFFFF);
        rim.setStrokeWidth(1.5f * k);
        c.drawRoundRect(cell.left + 0.75f * k, cell.top + 0.75f * k, cell.right - 0.75f * k, cell.bottom - 0.75f * k, r, r, rim);
        rim.setStrokeWidth(2.5f * k);
        Symbols.draw(getContext(), c, "sparkles", cx, cy - 16 * k, (56 + 8 * f) * k, 0xF2FFFFFF);
        String q = Ui.tr("Surprise me");
        c.drawText(q, cx - surpriseLabel.measureText(q) / 2, cy + 56 * k, surpriseLabel);
    }

    private void orb(Canvas c, float x, float y, float radius, int color) {
        tileFill.setShader(new android.graphics.RadialGradient(x, y, radius, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, radius, tileFill);
        tileFill.setShader(null);
    }

    /** Where row r sits on the page (only rows with something in them are kept). */
    private int slot(int r) {
        return r;
    }

    private int nextRow(int from, int dir) {
        for (int r = from + dir; r >= 0 && r < rowItems.size(); r += dir) if (!rowList(r).isEmpty()) return r;
        return -1;
    }

    /** Where the page rests for the focus: the rows scroll up to sit under the top; the hero and the apps don't scroll. */
    private float scrollTarget() {
        if (zone == Z_DRAWER || zone == WIDGETS) return scrollY.target;
        if (zone == Z_SHELF && picksOn()) return APPS_SCROLL;
        return zone == Z_ROWS ? ROW0_TOP + rowOffset(rowIndex) - ROW_SCROLL_PAD : 0;
    }

    /** The background follows the focus: the row card's photo there, the hero's pick everywhere else. */
    private void followBackdrop() {
        if (zone == Z_SHELF) return;   // the apps keep the hero's background
        Recs.Pick rp = zone == Z_ROWS && rowCol < rowList(rowIndex).size() ? rowList(rowIndex).get(rowCol) : null;
        if (rp != null && rp.recentApp) return;
        if (zone == Z_ROWS) {
            Backdrop b = recs.backdropIfOnDisk(rp);   // the hero's titles; the others keep the background
            if (b != null) showBackdrop(b);
            return;
        }
        Recs.Pick p = zone == Z_ROWS ? rp
                : cards.isEmpty() ? null : cards.get(Math.min(cardIndex, cards.size() - 1));
        Backdrop b = p == null ? null : recs.backdrop(p);
        if (b != null) showBackdrop(b);
    }

    private void layoutRows() {
        positionRows();
        rowsDirty = true;
    }

    /** The cards' places on the page, each row shifted by its scroll. */
    private boolean ranked(int r) {
        return r < rowRanked.size() && rowRanked.get(r);
    }

    private boolean square(int r) {
        return r < rowSquare.size() && rowSquare.get(r);
    }

    private final Map<String, android.graphics.Path> rankOutlines = new HashMap<>();

    /**
     * The number's outline at the origin's baseline. The font draws some digits ("1", "4") as overlapping shapes: stroked
     * as they are, their inner edges show, so the shapes are merged into one outline first.
     */
    private android.graphics.Path rankOutline(String n) {
        android.graphics.Path p = rankOutlines.get(n);
        if (p != null) return p;
        android.graphics.Path glyphs = new android.graphics.Path();
        rankFill.getTextPath(n, 0, n.length(), 0, 0, glyphs);
        p = new android.graphics.Path();
        p.op(glyphs, android.graphics.Path.Op.UNION);
        rankOutlines.put(n, p);
        return p;
    }

    /** The rank's width (1080p px), measured in the number's font. */
    private float rankWidth(int r, int i) {
        List<Recs.Pick> l = rowList(r);
        int n = i < l.size() && l.get(i).rank > 0 ? l.get(i).rank : i + 1;
        return rankPaint == null ? RANK_SIZE * 0.6f * String.valueOf(n).length() : rankPaint.measureText(String.valueOf(n)) / k;
    }

    private void positionRows() {
        for (int r = 0; r < MAX_ROWS; r++) {
            float top = (ROW0_TOP + rowOffset(r)) * k, shift = rowShift[r].value;
            boolean rank = ranked(r);
            boolean sq = square(r);
            float cw = rank ? RANK_CARD_W : sq ? SQUARE : ROW_CARD_W, ch = rank ? RANK_CARD_H : sq ? SQUARE : ROW_CARD_H;
            float dy = sq ? 0 : ROW_CARD_H - ch, start = 0;   // a square cover hangs from the row's top, like Apple Music's
            for (int i = 0; i < MAX_ITEMS; i++) {
                rowX[r][i] = start;
                float cardX = rank ? start + rankWidth(r, i) - RANK_OVERLAP : start;
                start = cardX + cw + ROW_GAP;
                if (i == rowList(r).size() - 1) rowEnd[r] = cardX + cw;
                float x = (MARGIN + cardX - shift) * k;
                rowRects[r][i].set(x, top + dy * k, x + cw * k, top + (dy + ch) * k);
                if (rowShift[r].settled()) snap(rowRects[r][i]);
            }
        }
    }

    /** The rows' layer: "Trending movies" and "Trending shows", the focused card drawn last (it grows over the others). */
    private void drawRows(Canvas c) {
        if (!picksOn()) return;
        // The layer is the screen; the page scrolls inside it. Only the rows (and cards) on screen are drawn.
        float page = scrollY.value * k;
        c.save();
        c.translate(0, -page);
        for (int r = 0; r < rowItems.size(); r++) {
            List<Recs.Pick> list = rowList(r);
            float titleY = (ROW0_TITLE + rowOffset(r)) * k;
            if (list.isEmpty() || titleY - 60 * k > page + h || titleY + (ROW_STEP - 40) * k < page) continue;
            String title = rowTitles.get(r), sub = rowSubtitles.get(r);
            c.drawText(title, MARGIN * k - Ui.leftBearing(rowTitlePaint, title), titleY, rowTitlePaint);
            if (!sub.isEmpty()) c.drawText(sub, MARGIN * k + rowTitlePaint.measureText(title) + 14 * k, titleY, rowSubPaint);
            if (ranked(r)) {
                // The numbers first: the cards cover their right edge.
                for (int i = 0; i < list.size(); i++) {
                    RectF b = rowRects[r][i];
                    String n = String.valueOf(list.get(i).rank > 0 ? list.get(i).rank : i + 1);
                    float nx = b.left + RANK_OVERLAP * k - rankPaint.measureText(n);
                    if (b.right < -40 * k || nx > w + 40 * k) continue;
                    float f = Ui.clamp(rowFocus[r][i].value);
                    android.graphics.Path outline = rankOutline(n);
                    c.save();
                    c.translate(nx, b.bottom);
                    if (f > 0.01f) {   // focused: it fills in white
                        rankFill.setColor(Ui.alpha(0xFFFFFFFF, 0.92f * f));
                        c.drawPath(outline, rankFill);
                    }
                    rankPaint.setColor(blend(0x59FFFFFF, 0xEBFFFFFF, f));
                    c.drawPath(outline, rankPaint);
                    c.restore();
                }
            }
            int top = -1;
            for (int i = 0; i < list.size(); i++) if (rowFocus[r][i].value > 0.02f && (top < 0 || rowFocus[r][i].value > rowFocus[r][top].value)) top = i;
            for (int i = 0; i < list.size(); i++) {
                RectF b = rowRects[r][i];
                if (i != top && b.right > -40 * k && b.left < w + 40 * k) drawRowCard(c, r, i, list.get(i));
            }
            if (top >= 0) drawRowCard(c, r, top, list.get(top));
        }
        c.restore();
    }

    private final Map<String, Shader> recentShaders = new HashMap<>();
    private final android.graphics.Matrix recentMatrix = new android.graphics.Matrix();

    private void drawRowCard(Canvas c, int r, int i, Recs.Pick p) {
        RectF base = rowRects[r][i];
        float v = rowFocus[r][i].value, f = Ui.clamp(v), s = 1 + 0.08f * v;
        float bw = base.width(), bh = base.height(), r0 = (square(r) ? 16 : 20) * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        boolean tilted = zone == Z_ROWS && r == rowIndex && i == rowCol && f > 0.01f && tiltBegin(c, cx, cy);
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 16 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, 0.35f + 0.3f * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        if ("surprise".equals(p.kind)) {
            drawSurprise(c, cell, r0 * s, f);
            if (tilted) c.restore();
            drawCaptions(c, base, p, f);
            return;
        }
        Rows.Card card = p.recentApp ? null : (ranked(r) || square(r)) && p.poster != null ? rowsData.poster(p) : rowsData.card(p);
        Backdrop b = card != null || p.recentApp ? null : recs.backdropIfOnDisk(p);
        if (card != null || b != null && b.wide != null) {
            if (card != null) drawCover(c, card.bitmap, card.shader, cell, r0 * s, 1);
            else drawCover(c, b.wide, b.wideShader, cell, r0 * s, 1);
            // Only "Recently watched" mixes services: elsewhere the row's title already names it.
            HomeApps.Item app = r < rowChip.size() && rowChip.get(r) ? shelfItem(p.pkg) : null;
            if (app != null && app.logo != null) {
                float lh = 16 * k * s, lw = Math.min(90 * k * s, app.logo.getWidth() * lh / app.logo.getHeight());
                tmp.set(cell.left + 16 * k * s, cell.bottom - (16 + 34) * k * s, cell.left + 16 * k * s + lw + 22 * k * s, cell.bottom - 16 * k * s);
                fill.setShader(null);
                fill.setColor(0x8C000000);
                c.drawRoundRect(tmp, 10 * k * s, 10 * k * s, fill);
                tmp.set(tmp.left + 11 * k * s, tmp.centerY() - lh / 2, tmp.left + 11 * k * s + lw, tmp.centerY() + lh / 2);
                bitmapPaint.setColorFilter(whiteLogo);
                c.drawBitmap(app.logo, null, tmp, bitmapPaint);
                bitmapPaint.setColorFilter(null);
            }
        } else {
            // An app (or a title whose photo is gone): its brand's deep gradient with the white logo, like the shelf.
            HomeApps.Item app = shelfItem(p.pkg);
            int[] deep = app == null ? null : BRAND_DEEP.get(app.key);
            Shader g = deep == null ? null : recentShaders.get(app.key);
            if (deep != null && g == null) {
                g = new LinearGradient(0, 0, 1, 1, deep, new float[] {0, 0.6f, 1}, Shader.TileMode.CLAMP);
                recentShaders.put(app.key, g);
            }
            if (g != null) {
                recentMatrix.setScale(cell.width(), cell.height());
                recentMatrix.postTranslate(cell.left, cell.top);
                g.setLocalMatrix(recentMatrix);
            }
            fill.setShader(null);
            fill.setColor(0xFF141416);   // like the shelf's tiles at rest
            c.drawRoundRect(cell, r0 * s, r0 * s, fill);
            if (g != null && f > 0.004f) {
                fill.setShader(g);
                fill.setAlpha(Math.round(255 * f));
                c.drawRoundRect(cell, r0 * s, r0 * s, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            if (app != null && app.logo != null) {
                float lh = 64 * k * s, lw = Math.min(240 * k * s, app.logo.getWidth() * lh / app.logo.getHeight());
                lh = app.logo.getHeight() * lw / app.logo.getWidth();
                tmp.set(cell.centerX() - lw / 2, cell.centerY() - lh / 2, cell.centerX() + lw / 2, cell.centerY() + lh / 2);
                c.drawBitmap(app.logo, null, tmp, bitmapPaint);
            }
        }
        if (tilted) c.restore();
        if (ranked(r)) return;   // the poster carries the title
        drawCaptions(c, base, p, f);
    }

    private void drawCaptions(Canvas c, RectF base, Recs.Pick p, float f) {
        float bw = base.width();
        fitPaint.set(rowCardTitle);
        if (p.title != null) c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(),
                base.left, base.bottom + (44 + 12 * f) * k, rowCardTitle);
        if (p.meta != null && !p.meta.isEmpty()) {
            fitPaint.set(rowCardMeta);
            c.drawText(TextUtils.ellipsize(p.meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + (74 + 12 * f) * k, rowCardMeta);
        }
    }

    /** "Remove animations" (or animator scale 0) in the TV's settings: no tilt, no zoom. */
    private static boolean motionOn() {
        return android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    /** Applies the focus tilt around (cx, cy); returns false (nothing applied) when it has settled. */
    private boolean tiltBegin(Canvas c, float cx, float cy) {
        if (Math.abs(tiltX.value) < 0.05f && Math.abs(tiltY.value) < 0.05f) return false;
        camera3d.save();
        camera3d.setLocation(0, 0, -28);
        camera3d.rotateY(-tiltX.value);
        camera3d.rotateX(tiltY.value);
        camera3d.getMatrix(tiltMatrix);
        camera3d.restore();
        tiltMatrix.preTranslate(-cx, -cy);
        tiltMatrix.postTranslate(cx, cy);
        c.save();
        c.concat(tiltMatrix);
        return true;
    }

    /** The specular highlight over a focused item (drawn in its centered, unscaled coordinates), shifted by the tilt. */
    private void drawSpec(Canvas c, LinearGradient shader, float cx, float cy, float s, float bw, float bh, float r0, float f) {
        specMatrix.setTranslate((-tiltX.value * 7 + 0) * k, (tiltY.value * 7) * k);
        shader.setLocalMatrix(specMatrix);
        specPaint.setShader(shader);
        specPaint.setAlpha(Math.round(255 * f));
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        c.drawRoundRect(tmp, r0, r0, specPaint);
        c.restore();
        specPaint.setShader(null);
    }

    private static LinearGradient spec(float bw, float bh) {
        return new LinearGradient(-bw / 2, -bh / 2, bw * 0.15f, bh / 2, new int[] {0x47FFFFFF, 0x0DFFFFFF, 0x00FFFFFF},
                new float[] {0, 0.38f, 0.62f}, Shader.TileMode.CLAMP);
    }

    /** `image` filling `cell` like CSS cover (cropped, never stretched), rounded, at `alpha`. */
    private void drawCover(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r, float alpha) {
        drawCover(c, image, shader, cell, r, alpha, 1, 0);
    }

    /** The same, `zoom` times bigger and shifted by `shift` of the cell's width (the scene's slow zoom). */
    private void drawCover(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r, float alpha, float zoom, float shift) {
        int iw = image.getWidth(), ih = image.getHeight();
        float sc = Math.max(cell.width() / iw, cell.height() / ih) * zoom, lw = cell.width() / sc, lh = cell.height() / sc;
        c.save();
        c.translate(cell.centerX(), cell.centerY());
        c.scale(sc, sc);
        coverMatrix.setTranslate(-iw / 2f + shift * cell.width() / sc, -ih / 2f);
        shader.setLocalMatrix(coverMatrix);
        imageFill.setShader(shader);
        imageFill.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(-lw / 2, -lh / 2, lw / 2, lh / 2, r / sc, r / sc, imageFill);
        imageFill.setAlpha(255);
        imageFill.setShader(null);
        coverMatrix.reset();
        shader.setLocalMatrix(coverMatrix);
        c.restore();
    }

    /** Your photo's background is ready (or new photos came in): draw it. */
    private final Runnable ownReady = () -> {
        if (running) updatePicks();
    };

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = Ui.gradient(h, 0xFFF5F5F7, 0xFFE2E2E7);
            if (nightGradient == null) nightGradient = Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
        float amb = picksOn() ? Ui.clamp(scrollY.value / AMBIENT_AT) : 0;
        if (amb > 0.004f) {
            if (fading && bgFrom != null) drawImage(c, bgFrom.nightImage, amb * (bgTo == null ? 1 - e : 1));
            if (bgTo != null) drawImage(c, bgTo.nightImage, amb * (fading ? e : 1));
        }
        if (picksOn() && heroScrimLeft != null) {
            fill.setColorFilter(null);
            fill.setColor(0xFF000000);
            fill.setShader(heroScrimLeft);
            c.drawRect(screen, fill);
            fill.setShader(heroScrimBottom);
            c.drawRect(screen, fill);
            fill.setShader(heroScrimTop);
            c.drawRect(screen, fill);
            fill.setShader(null);
        }
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (b.image != null && picksOn()) {
            drawImage(c, b.image, alpha);
            return;
        }
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        if (!picksOn()) {
            drawClock(c);
            if (HomeData.coinCount() > 0) drawQuotes(c);
            return;
        }
        drawHero(c);
    }

    /**
     * The focused pick over its own photo, as in the Apple TV app: the service's chip and "Trending", a big title,
     * genre · runtime, two lines of synopsis, and a white "Watch" button; dots under it for left/right.
     */
    private void drawHero(Canvas c) {
        if (cards.isEmpty()) return;
        int i = Math.min(cardIndex, cards.size() - 1);
        Recs.Pick p = cards.get(i);
        float x = MARGIN * k;
        HomeApps.Item app = shelfItem(p.pkg);
        float chipH = H_CHIP_H * k, cy = (H_CHIP_TOP + H_CHIP_H / 2) * k, after = x;
        if (p.pkg != null) {
            boolean logo = app != null && app.logo != null;
            float lh = 18 * k, lw = logo ? app.logo.getWidth() * lh / app.logo.getHeight() : chipText.measureText(Recs.serviceName(p));
            lw = Math.min(lw, 110 * k);
            tmp.set(x, cy - chipH / 2, x + lw + 26 * k, cy + chipH / 2);
            fill.setShader(null);
            fill.setColor(0x8C000000);
            c.drawRoundRect(tmp, 10 * k, 10 * k, fill);
            if (logo) {
                cell.set(x + 13 * k, cy - lh / 2, x + 13 * k + lw, cy + lh / 2);
                bitmapPaint.setColorFilter(whiteLogo);
                c.drawBitmap(app.logo, null, cell, bitmapPaint);
                bitmapPaint.setColorFilter(null);
            } else {
                c.drawText(Recs.serviceName(p), x + 13 * k, cy + Ui.halfCap(chipText), chipText);
            }
            after = tmp.right + 14 * k;
        }
        c.drawText("nowplaying".equals(p.kind) ? (nowPlaying.playing ? Ui.tr("Now playing") : Ui.tr("Paused"))
                : "playlist".equals(p.kind) ? Ui.tr("Listen now") : p.fresh ? Ui.tr("New release") : Ui.tr("Trending"),
                after, cy + Ui.halfCap(hEyebrow), hEyebrow);
        fitPaint.set(hTitle);
        if (p.title != null) c.drawText(TextUtils.ellipsize(p.title, fitPaint, H_TITLE_FIT * k, TextUtils.TruncateAt.END).toString(),
                x - Ui.leftBearing(hTitle, p.title), H_TITLE_BASE * k, hTitle);
        if (!heroMeta.isEmpty()) {
            fitPaint.set(hMeta);
            c.drawText(TextUtils.ellipsize(heroMeta, fitPaint, H_TITLE_FIT * k, TextUtils.TruncateAt.END).toString(), x, H_META_BASE * k, hMeta);
        }
        if (heroDesc != null) {
            c.save();
            c.translate(x, H_DESC_TOP * k);
            heroDesc.draw(c);
            c.restore();
        }
        if (p.pkg != null) {
            RectF r = cardRects[i];
            float f = Ui.clamp(cardFocus[i].value), s = 1 + 0.08f * f, rad = r.height() / 2 * s;
            cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2, r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
            tmp.set(cell);
            tmp.offset(0, (6 + 12 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, 0.2f + 0.3f * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            fill.setShader(null);
            fill.setColor(blend(0xE6FFFFFF, 0xFFFFFFFF, f));
            c.drawRoundRect(cell, rad, rad, fill);
            String label = heroLabel(p);
            float tw = hButton.measureText(label), tri = 20 * k, gap = 14 * k;
            float left = r.centerX() - (tri + gap + tw) / 2, mid = r.centerY();
            c.save();
            c.scale(s, s, r.centerX(), mid);
            playPath.reset();
            playPath.moveTo(left, mid - tri * 0.58f);
            playPath.lineTo(left + tri, mid);
            playPath.lineTo(left, mid + tri * 0.58f);
            playPath.close();
            fill.setColor(INK);
            c.drawPath(playPath, fill);
            c.drawText(label, left + tri + gap, mid + Ui.halfCap(hButton), hButton);
            c.restore();
        }
        int n = cards.size();
        if (n < 2) return;
        float dot = 10 * k, gap = 10 * k, wide = 36 * k, dy = H_DOTS_Y * k;
        float total = wide + (n - 1) * (dot + gap), dx = (w - total) / 2;
        for (int d = 0; d < n; d++) {
            float dw = d == i ? wide : dot;
            tmp.set(dx, dy - dot / 2, dx + dw, dy + dot / 2);
            fill.setColor(d == i ? 0xFFFFFFFF : 0x61FFFFFF);
            c.drawRoundRect(tmp, dot / 2, dot / 2, fill);
            dx += dw + gap;
        }
    }

    private String heroMeta = "";

    /** "Watch on Netflix", or "Listen on Spotify" for a playlist. */
    private static String heroLabel(Recs.Pick p) {
        if ("nowplaying".equals(p.kind)) return Ui.tr("Open the player");
        return Ui.trf("playlist".equals(p.kind) ? "Listen on {app}" : "Watch on {app}", "app", Recs.serviceName(p));
    }

    private HomeApps.Item shelfItem(String pkg) {
        if (pkg == null) return null;
        for (HomeApps.Item it : shelf) if (pkg.equals(it.pkg)) return it;
        return null;
    }

    /** The hero's button, title size and synopsis for the current pick (only when the pick changes, never per frame). */
    private void layoutHeroButton() {
        if (hButton == null) return;
        Recs.Pick p = cards.isEmpty() ? null : cards.get(Math.min(cardIndex, cards.size() - 1));
        String service = p == null || p.pkg == null ? "" : Recs.serviceName(p);
        // The button names the service: the subtitle doesn't again.
        heroMeta = p == null || p.meta == null ? "" : p.meta;
        if (!service.isEmpty() && heroMeta.endsWith(" · " + service)) heroMeta = heroMeta.substring(0, heroMeta.length() - service.length() - 3);
        float bw = hButton.measureText(p == null ? "" : heroLabel(p)) + (20 + 14 + 2 * 38) * k;
        float left = MARGIN * k, top = H_BUTTON_TOP * k;
        for (RectF r : cardRects) {
            r.set(left, top, left + bw, top + H_BUTTON_H * k);
            snap(r);
        }
        // A long title steps down from 124 px until it fits (never below 84).
        hTitle.setTextSize(H_TITLE_SIZE * k);
        if (p != null && p.title != null) {
            float tw = hTitle.measureText(p.title);
            if (tw > H_TITLE_FIT * k) hTitle.setTextSize(Math.max(84 * k, H_TITLE_SIZE * k * H_TITLE_FIT * k / tw));
        }
        String desc = p == null || p.overview == null ? "" : p.overview;
        heroDesc = desc.isEmpty() ? null : StaticLayout.Builder.obtain(desc, 0, desc.length(), hDesc, Math.round(H_DESC_W * k))
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.18f).setIncludePad(false).build();
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || !Ui.language.equals(weatherLineLanguage)) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLineLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + HomeData.highLow(wx, "  ");
        }
        float size = 40 * k, base = L_WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lWeather), size, lWeather.getColor());
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        RectF base = cardRects[i];
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : (i == 0 ? b.wide : b.tall);
        if (img != null) {
            roundImage(c, img, i == 0 ? b.wideShader : b.tallShader, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            if (lightSheens.length != cardRects.length) {
                lightSheens = new LinearGradient[cardRects.length];
                lightRims = new LinearGradient[cardRects.length];
                lightShaderW = new float[cardRects.length];
            }
            if (lightSheens[i] == null || lightShaderW[i] != bw) {
                lightSheens[i] = new LinearGradient(-bw / 2, -bh / 2, 0, bh / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                lightRims[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
                lightShaderW[i] = bw;
            }
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(lightSheens[i]);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(lightRims[i]);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        if (p == null) return;
        fitPaint.set(lCardTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 46 * k, lCardTitle);
        if (i == 0 && p.meta != null && !p.meta.isEmpty()) {
            fitPaint.set(lCardMeta);
            c.drawText(TextUtils.ellipsize(p.meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 80 * k, lCardMeta);
        }
    }

    /**
     * The fixed top bar: the page's scrim as it scrolls under, the tabs in a glass capsule (the selected one lit, the
     * focused one white), and at the right the dollar, the weather, the time and the Settings button.
     */
    // MARK: - Real blur behind the top bar (Android 12+)

    /** Android 12+: the tabs capsule and the round buttons blur what passes under them, as in the V1.6 prototype. */
    private final boolean realBlur = !Ui.LEGACY && android.os.Build.VERSION.SDK_INT >= 31;
    private final android.graphics.RenderNode barBlur = realBlur ? new android.graphics.RenderNode("barBlur") : null;
    private final android.graphics.Path barClip = new android.graphics.Path();
    private final Paint glassRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float barBlurK = -1, rowsPage = -1;
    private float scrimDrawn = -1;

    private float scrimNow() {
        return Ui.clamp(scrollY.value / 200f) * status.getAlpha();
    }

    /** The glass of a bar control: over the real blur, a light veil and a hairline; elsewhere, the liquid glass. */
    private void barGlass(Canvas c, RectF r, float rad, float alpha) {
        if (!realBlur) {
            scene.liquid(c, r, rad, SMOKE, 0.5f, 0.01f, 12 * k, 8 * k, 0.45f, alpha);
            return;
        }
        if (alpha <= 0.004f) return;
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFFFFFFFF, 0.09f * alpha));
        c.drawRoundRect(r, rad, rad, fill);
        glassRim.setStyle(Paint.Style.STROKE);
        glassRim.setStrokeWidth(1.5f * k);
        glassRim.setColor(Ui.alpha(0xFFFFFFFF, 0.14f * alpha));
        c.drawRoundRect(r.left + 0.75f * k, r.top + 0.75f * k, r.right - 0.75f * k, r.bottom - 0.75f * k, rad, rad, glassRim);
    }

    private float drawerDim;

    @Override protected boolean drawChild(Canvas canvas, View child, long time) {
        if (child == drawerLayer && picksOn()) {
            // The page darkens under the panel: its text reads against anything behind it.
            drawerDim = Ui.clamp(drawer.value);
            if (drawerDim > 0.004f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFF000000, 0.6f * drawerDim * (1 - Ui.clamp(sheet.value))));
                canvas.drawRect(0, 0, w, h, fill);
            }
        }
        if (child == status && barBlur != null && picksOn() && status.getVisibility() == VISIBLE && canvas.isHardwareAccelerated()) {
            drawBarBlur(canvas, time);
        }
        return super.drawChild(canvas, child, time);
    }

    /** What is under the bar (sky, hero, shelf, rows: their display lists, not redrawn), blurred and cut to its shapes. */
    private void drawBarBlur(Canvas canvas, long time) {
        if (barBlurK != k) {
            barBlurK = k;
            android.graphics.ColorMatrix saturate = new android.graphics.ColorMatrix();
            saturate.setSaturation(1.8f);
            barBlur.setRenderEffect(android.graphics.RenderEffect.createChainEffect(
                    android.graphics.RenderEffect.createColorFilterEffect(new android.graphics.ColorMatrixColorFilter(saturate)),
                    android.graphics.RenderEffect.createBlurEffect(40 * k, 40 * k, Shader.TileMode.CLAMP)));
        }
        float ty = status.getTranslationY();
        // The scrim goes under the blur here: the capsule shows the content's color, blurred,
        // and the text beside it still reads.
        // updateLayers redraws this view whenever the scrim changes: otherwise its drawing keeps the scrim of the last
        // frame it recorded (a black band left over the hero after scrolling back).
        scrimDrawn = scrimNow();
        if (scrimDrawn > 0.004f) {
            fill.setShader(barScrim);
            fill.setAlpha(Math.round(255 * scrimDrawn));
            canvas.save();
            canvas.translate(0, ty);
            canvas.drawRect(0, 0, w, BAR_SCRIM_H * k, fill);
            canvas.restore();
            fill.setShader(null);
            fill.setAlpha(255);
        }
        barClip.reset();
        barClip.addRoundRect(barRect, barRect.height() / 2, barRect.height() / 2, android.graphics.Path.Direction.CW);
        for (int i = 0; i < lightButtons.size(); i++) {
            RectF r = topRects[3 + i];
            barClip.addCircle(r.centerX(), r.centerY(), r.width() / 2, android.graphics.Path.Direction.CW);
        }
        barClip.addCircle(searchRect.centerX(), searchRect.centerY(), searchRect.width() / 2, android.graphics.Path.Direction.CW);
        int bottom = Math.round(barRect.bottom + 60 * k);
        barBlur.setPosition(0, 0, w, bottom);
        barBlur.setAlpha(status.getAlpha());
        Canvas rc = barBlur.beginRecording();
        rc.translate(0, -ty);
        for (View v : new View[] {sky, heroLayer, platter, tiles, rowsLayer}) {
            if (v.getVisibility() == VISIBLE) super.drawChild(rc, v, time);
        }
        barBlur.endRecording();
        canvas.save();
        canvas.translate(0, ty);
        canvas.clipPath(barClip);
        canvas.drawRenderNode(barBlur);
        canvas.restore();
    }

    private void drawLightButtons(Canvas c) {
        applyTone();
        float scrim = Ui.clamp(scrollY.value / 200f);
        if (scrim > 0.004f && !realBlur) {
            fill.setShader(barScrim);
            fill.setAlpha(Math.round(255 * scrim));
            c.drawRect(0, 0, w, BAR_SCRIM_H * k, fill);
            fill.setShader(null);
            fill.setAlpha(255);
        }
        if (picksOn()) {
            drawSearchButton(c);
            float rad = barRect.height() / 2;
            barGlass(c, barRect, rad, 1);
            float focus = Ui.clamp(topFocus[tab].value);
            for (int i = 0; i < 3; i++) {
                RectF r = topRects[i];
                float f = i == tab ? focus : 0, s = 1 + 0.08f * f, rr = r.height() / 2;
                if (i == tab) {
                    cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2, r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                    fill.setShader(null);
                    fill.setColor(blend(0x29FFFFFF, 0xFFFFFFFF, f));
                    c.drawRoundRect(cell, rr * s, rr * s, fill);
                }
                barTab.setColor(i == tab ? blend(0xFFFFFFFF, INK, f) : 0x9EFFFFFF);
                String t = tabs()[i];
                c.save();
                c.scale(s, s, r.centerX(), r.centerY());
                c.drawText(t, r.centerX() - barTab.measureText(t) / 2, r.centerY() + Ui.halfCap(barTab), barTab);
                c.restore();
            }
        }
        float right = lightButtons.isEmpty() ? CONTENT_R * k : topRects[3].left - 30 * k, cy = (BAR_Y + BAR_H / 2) * k;
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + 0.08f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            barGlass(c, cell, rad, 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            Symbols.draw(getContext(), c, it.symbol, cell.centerX(), cell.centerY(), 28 * k * s, blend(0xEBFFFFFF, INK, f));
        }
        // Time, weather and the dollar, right to left: one focusable group (OK opens the drawer).
        float glanceRight = right + 22 * k;
        float gf = Ui.clamp(glanceFocus.value);
        int textWas = barText.getColor(), dimWas = barDim.getColor(), labelWas = barLabel.getColor();
        if (gf > 0.01f && !glanceRect.isEmpty()) {   // focused: a white pill, the text dark (the prototype's)
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, gf));
            c.drawRoundRect(glanceRect, glanceRect.height() / 2, glanceRect.height() / 2, fill);
            barText.setColor(blend(textWas, INK, gf));
            barDim.setColor(blend(dimWas, INK, gf));
            barLabel.setColor(blend(labelWas, 0xA61C1C1E, gf));
        }
        float base = cy + Ui.halfCap(barText);
        float tw = barText.measureText(clockText);
        c.drawText(clockText, right - tw, base, barText);
        right -= tw + 28 * k;
        HomeData.Weather wx = data.weather;
        if (Prefs.weather() && !Double.isNaN(wx.temp)) {
            String deg = HomeData.degrees(wx.temp), sym = Symbols.weather(wx.code, wx.isDay);
            float dw = barDim.measureText(deg), size = 30 * k, sw = Symbols.width(getContext(), sym, size);
            c.drawText(deg, right - dw, base, barDim);
            Symbols.draw(getContext(), c, sym, right - dw - 10 * k - sw / 2, cy, size, blend(0x9EFFFFFF, INK, gf));
            right -= dw + 10 * k + sw + 24 * k;
            fill.setShader(null);
            fill.setColor(0x2EFFFFFF);
            c.drawRect(right, cy - 16 * k, right + 1.5f * k, cy + 16 * k, fill);
            right -= 24 * k;
        }
        if (!Double.isNaN(data.prices[3])) {
            if (data.pricesAt != dollarAt) {
                dollarAt = data.pricesAt;
                dollarText = HomeData.brl(data.prices[3]);
                dollarChange = Double.isNaN(data.changes[3]) ? "" : HomeData.change(data.changes[3]);
            }
            if (!dollarChange.isEmpty()) {
                barChange.setColor(data.changes[3] >= 0 ? UP_COLOR : DOWN_COLOR);
                float cw = barChange.measureText(dollarChange);
                c.drawText(dollarChange, right - cw, base, barChange);
                right -= cw + 10 * k;
            }
            float pw = barText.measureText(dollarText);
            c.drawText(dollarText, right - pw, base, barText);
            right -= pw + 12 * k;
            String label = Ui.tr("Dollar").toUpperCase(Ui.locale());
            float lw = barLabel.measureText(label);
            c.drawText(label, right - lw, base, barLabel);
            right -= lw + 28 * k;
        }
        glanceRect.set(right + 6 * k, cy - BAR_H / 2 * k + 4 * k, glanceRight, cy + BAR_H / 2 * k - 4 * k);
        barText.setColor(textWas);
        barDim.setColor(dimWas);
        barLabel.setColor(labelWas);
        if (sleepText.isEmpty()) return;
        float sw2 = lQuote.measureText(sleepText), size = lQuote.getTextSize();
        c.drawText(sleepText, right - sw2, cy + Ui.halfCap(lQuote), lQuote);
        Symbols.draw(getContext(), c, "bedtime", right - sw2 - size * 0.9f, cy, size, lQuote.getColor());
    }

    /** The magnifier: a glass circle (white when focused) with the lens drawn as strokes. */
    private void drawSearchButton(Canvas c) {
        float f = Ui.clamp(searchFocus.value), s = 1 + 0.08f * f, rad = searchRect.width() / 2 * s;
        float cx = searchRect.centerX(), cy = searchRect.centerY();
        cell.set(cx - rad, cy - rad, cx + rad, cy + rad);
        barGlass(c, cell, rad, 1 - f);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.drawCircle(cx, cy, rad, fill);
        }
        searchStroke.setStyle(Paint.Style.STROKE);
        searchStroke.setStrokeCap(Paint.Cap.ROUND);
        searchStroke.setStrokeWidth(3 * k * s);
        searchStroke.setColor(blend(0xEBFFFFFF, INK, f));
        float lens = 9 * k * s, lx = cx - 3 * k * s, ly = cy - 3 * k * s;
        c.drawCircle(lx, ly, lens, searchStroke);
        float d = lens * 0.7071f;
        c.drawLine(lx + d, ly + d, lx + d + 8 * k * s, ly + d + 8 * k * s, searchStroke);
    }

    // MARK: - Search

    private void askSearch() {
        android.widget.EditText field = new android.widget.EditText(activity);
        field.setSingleLine(true);
        field.setHint(Ui.tr("A movie or a show"));
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Search your apps"))
                .setView(field)
                .setPositiveButton(Ui.tr("Search"), (d, which) -> search(field.getText().toString()))
                .setNegativeButton(Ui.tr("Cancel"), null)
                .create();
        field.setOnEditorActionListener((v, action, event) -> {
            dialog.dismiss();
            search(field.getText().toString());
            return true;
        });
        dialog.show();
    }

    private void search(String text) {
        String q = text.trim();
        if (q.isEmpty()) return;
        showToast(Ui.trf("Searching for “{q}”…", "q", q));
        rowsData.search(q, results -> {
            if (!running) return;
            if (results.isEmpty()) {
                showToast(Ui.trf("Nothing for “{q}” on your apps.", "q", q));
                return;
            }
            searchResults = results;
            searchQuery = q;
            searchAt = SystemClock.uptimeMillis();
            updatePicks();
            zone = Z_ROWS;
            rowIndex = 0;
            rowCol = 0;
            followBackdrop();
            retarget(false);
        });
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                if (dy > 0) {
                    int r = picksOn() ? nextRow(-1, 1) : -1;
                    if (r < 0) return false;   // no rows: down opens the drawer, as before
                    zone = Z_ROWS;
                    rowIndex = r;
                    rowCol = Math.min(rowCol, rowList(r).size() - 1);
                    followBackdrop();
                    break;
                }
                // Up from an empty shelf too: move()'s older zones (the hero, the corner capsule) aren't drawn any more.
                if (dy == 0) return false;
                if (cards.isEmpty()) {
                    if (!picksOn() && lightButtons.isEmpty()) return true;   // no tabs and no buttons above
                    zone = Z_TOP;
                    topIndex = picksOn() ? tab : 3;
                } else {
                    zone = Z_CARDS;
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    if (cards.size() < 2) return true;
                    cardIndex = (cardIndex + dx + cards.size()) % cards.size();
                    layoutHeroButton();
                    Backdrop b = recs.backdrop(cards.get(cardIndex));
                    if (b != null) showBackdrop(b);
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_ROWS: {
                List<Recs.Pick> list = rowList(rowIndex);
                if (dx != 0) {
                    int next = Math.max(0, Math.min(list.size() - 1, rowCol + dx));
                    if (rowCol == 0 && "surprise".equals(list.get(0).kind) && surpriseLanded != null && !spinning()) {
                        spin();   // landed: ← → shuffle again
                        click.move();
                        rowsDirty = true;
                        return true;
                    }
                    if (next == rowCol) return true;
                    rowCol = next;
                } else {
                    int r = nextRow(rowIndex, dy);
                    if (r >= 0) {
                        rowIndex = r;
                        rowCol = Math.min(rowCol, rowList(r).size() - 1);
                    } else if (dy < 0) {
                        zone = Z_SHELF;
                    } else {
                        openDrawer();   // past the last row: the drawer, as from the apps before
                    }
                }
                followBackdrop();
                break;
            }
            case Z_TOP:
                if (dx != 0) {
                    // Order: the tabs (0-2), the dollar/weather/time (GLANCE), the round buttons (3…).
                    boolean glance = !glanceRect.isEmpty();
                    int next;
                    if (topIndex == SEARCH) next = dx > 0 ? 0 : SEARCH;
                    else if (topIndex == 0 && dx < 0 && picksOn()) next = SEARCH;
                    else if (topIndex == GLANCE) next = dx > 0 ? (lightButtons.isEmpty() ? GLANCE : 3) : (picksOn() ? 2 : GLANCE);
                    else if (topIndex == 2 && dx > 0 && glance) next = GLANCE;
                    else if (topIndex == 3 && dx < 0 && glance) next = GLANCE;
                    else next = Math.max(picksOn() ? 0 : 3, Math.min(2 + lightButtons.size(), topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < 3) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                    }
                } else if (topIndex < 3 && !lightButtons.isEmpty()) {
                    topIndex = 3;   // up from the tabs reaches the corner buttons without walking the row
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    /** Rounds a shape's edges to whole pixels (crisp at rest; a scale animation moves them again). */
    private static void snap(RectF r) {
        r.set(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
