package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen's rows under the apps, Netflix first: its official Top 10 in the TV's country (Netflix publishes the
 * weekly lists on Tudum), its popular movies and shows, then what's popular on the TV's other services. Built from
 * TMDB's lists (one request a row; no per-title details), saved, and fetched again every 6 hours.
 * Card images are TMDB's w780, kept small (480×270) and only in GPU memory, a few dozen at a time.
 */
final class Rows {
    static final class Row {
        final String title, subtitle;   // subtitle: the small text beside the title ("Top 10" + "movies on Netflix today")
        final List<Recs.Pick> items;
        final boolean ranked;            // a Top 10: big numbers beside the cards
        final boolean square;            // square covers (Spotify's playlists)

        Row(String title, String subtitle, List<Recs.Pick> items, boolean ranked) {
            this(title, subtitle, items, ranked, false);
        }

        Row(String title, String subtitle, List<Recs.Pick> items, boolean ranked, boolean square) {
            this.title = title;
            this.subtitle = subtitle;
            this.items = items;
            this.ranked = ranked;
            this.square = square;
        }
    }

    /** A card's image, ready to draw. */
    static final class Card {
        final Bitmap bitmap;
        final BitmapShader shader;

        Card(Bitmap bitmap) {
            this.bitmap = bitmap;
            shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
    }

    interface Listener { void onRows(); }

    static final int PER_ROW = 20;
    private static final long EVERY = 6 * 3600_000L, TOP10_EVERY = 24 * 3600_000L;
    private static final int CACHE_VERSION = 10, CARDS = 36, FILES = 160;
    private static final int CARD_W = 480, CARD_H = 270;
    private static final String IMAGES = "https://image.tmdb.org/t/p/w780", POSTERS = "https://image.tmdb.org/t/p/w342";
    private static final int POSTER_W = 180, POSTER_H = 270, SQUARE = 320;
    private static final String TOP10 = "https://www.netflix.com/tudum/top10/data/all-weeks-countries.tsv";

    /** Letters outside the Latin alphabet. */
    private static final java.util.regex.Pattern UNREADABLE = java.util.regex.Pattern.compile("[\\p{IsAlphabetic}&&[^\\p{IsLatin}]]");

    private static Rows instance;

    static synchronized Rows get(Context c) {
        if (instance == null) instance = new Rows(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor(), images = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Card> cards = new LinkedHashMap<>(16, 0.75f, true);   // least recently drawn first
    private final Set<String> loading = new HashSet<>(), failed = new HashSet<>();
    private volatile List<Row> rows = new ArrayList<>();
    private volatile boolean fetching;
    private volatile long fetchedAt, failedAt;

    private Rows(Context c) {
        context = c;
        cache = c.getSharedPreferences("rows", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("rows", null);
            if (saved != null && Ui.language.equals(cache.getString("language", ""))) rows = parse(new JSONArray(saved));
            fetchedAt = cache.getLong("at", 0);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    List<Row> rows() {
        if (!Recs.hasKey(context)) return new ArrayList<>();
        // The playlists' row is saved with every playlist; this hour's are picked from them.
        List<Row> out = new ArrayList<>(rows);
        for (int i = 0; i < out.size(); i++) {
            Row r = out.get(i);
            if (r.square) out.set(i, new Row(r.title, r.subtitle, playlistsNow(r.items), false, true));
        }
        return out;
    }

    void refreshIfStale() {
        if (!Recs.hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        if (failedAt != 0 && now - failedAt < 10 * 60_000L && now >= failedAt) return;
        boolean stale = rows.isEmpty() || now - fetchedAt > EVERY || now < fetchedAt
                || !Ui.language.equals(cache.getString("language", ""));
        if (!stale) return;
        fetching = true;
        io.execute(() -> {
            try {
                List<Row> next = fetch();
                if (next.isEmpty()) throw new IOException("no rows");
                rows = next;
                fetchedAt = System.currentTimeMillis();
                failedAt = 0;
                cache.edit().putString("rows", toJson(next).toString()).putLong("at", fetchedAt)
                        .putString("language", Ui.language).apply();
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("rows: " + e.getMessage());
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    // MARK: - Lists

    private List<Row> fetch() throws Exception {
        Recs recs = Recs.get(context);
        String lang = Recs.tmdbLanguage(), region = Recs.REGION;
        // The TV's services by TMDB provider id, Netflix first.
        Map<Integer, String[]> services = new LinkedHashMap<>();
        JSONArray providers = recs.tmdb("/watch/providers/movie?language=" + lang + "&watch_region=" + region).getJSONArray("results");
        int netflixId = -1;
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = recs.tvService(o.optString("provider_name"), o.optInt("provider_id"));
            if (s == null) continue;
            boolean dup = false;
            for (String[] t : services.values()) dup |= t[1].equals(s[1]);
            if (dup) continue;
            services.put(o.getInt("provider_id"), s);
            if (s[1].equals("netflix")) netflixId = o.getInt("provider_id");
        }
        Map<Integer, String> movieGenres = genres(recs, "movie", lang), tvGenres = genres(recs, "tv", lang);
        List<Row> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // Netflix first, the other apps in between: Netflix's Top 10s, then its rows taking turns with Spotify's
        // playlists and one row per other service.
        List<Row> netflix = new ArrayList<>(), others = new ArrayList<>();
        if (netflixId >= 0) {
            String[] nf = services.get(netflixId);
            List<Recs.Pick>[] top = top10(recs, lang, nf, movieGenres, tvGenres);
            add(out, seen, new Row(Ui.tr("Top 10"), Ui.tr("movies on Netflix today"), top[0], true));
            add(out, seen, new Row(Ui.tr("Top 10"), Ui.tr("shows on Netflix today"), top[1], true));
            netflix.add(new Row(Ui.tr("New on Netflix"), "", fresh(recs, netflixId, nf, lang, movieGenres, tvGenres), false));
            netflix.add(new Row(Ui.tr("Popular movies on Netflix"), "", discover(recs, "movie", netflixId, nf, lang, movieGenres, PER_ROW, false), false));
            netflix.add(new Row(Ui.tr("Popular shows on Netflix"), "", discover(recs, "tv", netflixId, nf, lang, tvGenres, PER_ROW, false), false));
        }
        List<Recs.Pick> playlists = spotify();
        if (!playlists.isEmpty()) others.add(new Row(Ui.tr("Playlists on Spotify"), "", playlists, false, true));
        for (Map.Entry<Integer, String[]> e : services.entrySet()) {
            if (e.getKey() == netflixId) continue;
            // Movies and shows taking turns: "Movie"/"Series" stays in their subtitles here.
            List<Recs.Pick> both = new ArrayList<>();
            List<Recs.Pick> m = discover(recs, "movie", e.getKey(), e.getValue(), lang, movieGenres, 10, true);
            List<Recs.Pick> s = discover(recs, "tv", e.getKey(), e.getValue(), lang, tvGenres, 10, true);
            for (int i = 0; i < 10; i++) {
                if (i < s.size()) both.add(s.get(i));
                if (i < m.size()) both.add(m.get(i));
            }
            others.add(new Row(Ui.trf("Popular on {app}", "app", Recs.SERVICE_NAMES.get(e.getValue()[1])), "", both, false));
        }
        for (int i = 0; i < Math.max(netflix.size(), others.size()); i++) {
            if (i < netflix.size()) add(out, seen, netflix.get(i));
            if (i < others.size()) add(out, seen, others.get(i));
        }
        return out;
    }

    private static void add(List<Row> out, Set<String> seen, String title, List<Recs.Pick> items) {
        add(out, seen, new Row(title, "", items, false));
    }

    private static void add(List<Row> out, Set<String> seen, Row row) {
        List<Recs.Pick> kept = new ArrayList<>();
        for (Recs.Pick p : row.items) {
            boolean first = seen.add(Recs.key(p));
            if (first || row.ranked) kept.add(p);   // a Top 10 keeps all ten (its numbers are the ranks)
        }
        if (kept.size() >= 3) out.add(new Row(row.title, row.subtitle, kept, row.ranked, row.square));   // a square row keeps the whole set
    }

    private static Map<Integer, String> genres(Recs recs, String kind, String lang) throws Exception {
        Map<Integer, String> out = new HashMap<>();
        JSONArray a = recs.tmdb("/genre/" + kind + "/list?language=" + lang).getJSONArray("genres");
        for (int i = 0; i < a.length(); i++) out.put(a.getJSONObject(i).getInt("id"), Recs.genreName(a.getJSONObject(i).optString("name")));
        return out;
    }

    /** Popular on one service (TMDB's discover: what it has in the TV's country, by popularity). */
    private static List<Recs.Pick> discover(Recs recs, String kind, int provider, String[] s, String lang,
                                            Map<Integer, String> genres, int max, boolean showKind) throws Exception {
        String q = "/discover/" + kind + "?language=" + lang + "&watch_region=" + Recs.REGION
                + "&with_watch_providers=" + provider + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&vote_count.gte=" + (kind.equals("movie") ? 50 : 20)
                + (Config.HIDE_ANIMATION ? "&without_genres=16" : "");
        List<Recs.Pick> out = new ArrayList<>();
        for (int page = 1; page <= 2 && out.size() < max; page++) {
            JSONArray a = recs.tmdb(q + "&page=" + page).getJSONArray("results");
            for (int i = 0; i < a.length() && out.size() < max; i++) {
                Recs.Pick p = pick(a.getJSONObject(i), kind, s, genres, showKind);
                if (p != null) out.add(p);
            }
        }
        return out;
    }

    /** New on the service: movies from the past 4 months and shows that premiered in the past 6 (not old shows with a new episode). */
    private static List<Recs.Pick> fresh(Recs recs, int provider, String[] s, String lang, Map<Integer, String> movieGenres,
                                         Map<Integer, String> tvGenres) throws Exception {
        String common = "&language=" + lang + "&watch_region=" + Recs.REGION + "&with_watch_providers=" + provider
                + "&with_watch_monetization_types=flatrate&include_adult=false&sort_by=popularity.desc"
                + (Config.HIDE_ANIMATION ? "&without_genres=16" : "");
        JSONArray m = recs.tmdb("/discover/movie?primary_release_date.gte=" + day(-120) + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray t = recs.tmdb("/discover/tv?first_air_date.gte=" + day(-180) + "&first_air_date.lte=" + day(0) + common).getJSONArray("results");
        List<Recs.Pick> out = new ArrayList<>();
        for (int i = 0; i < 10 && out.size() < PER_ROW; i++) {
            if (i < t.length()) {
                Recs.Pick p = pick(t.getJSONObject(i), "tv", s, tvGenres, true);
                if (p != null) out.add(p);
            }
            if (i < m.length()) {
                Recs.Pick p = pick(m.getJSONObject(i), "movie", s, movieGenres, true);
                if (p != null) out.add(p);
            }
        }
        return out;
    }

    /**
     * A TMDB list entry. Its subtitle says only what the row doesn't: the genre and the year, and "Movie"/"Series" in
     * rows that mix both. The service is the row's (its title says it).
     */
    private static Recs.Pick pick(JSONObject o, String kind, String[] s, Map<Integer, String> genres, boolean showKind) {
        return pick(o, kind, s, genres, showKind, true);
    }

    /** `filter`: false for Netflix's Top 10 and the search, which show what they show. */
    private static Recs.Pick pick(JSONObject o, String kind, String[] s, Map<Integer, String> genres, boolean showKind, boolean filter) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return null;
        if (filter && !Recs.wanted(o)) return null;   // no anime or cartoons, nothing from East or South Asia
        JSONArray g = o.optJSONArray("genre_ids");
        if (Config.HIDE_ANIMATION) for (int i = 0; g != null && i < g.length(); i++) if (g.optInt(i) == 16) return null;
        Recs.Pick p = new Recs.Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        if (!Ui.language.matches("(ja|zh|ko|ru|uk|el|hi|th|ar|he).*") && UNREADABLE.matcher(p.title).find()) {
            return null;   // no translation in the TV's language (a Telugu title on a Brazilian TV)
        }
        p.backdrop = backdrop;
        String poster = o.optString("poster_path", "");
        if (!poster.isEmpty() && !"null".equals(poster)) p.poster = poster;
        p.overview = o.optString("overview");
        p.app = s[1];
        p.pkg = s[2];
        p.detailed = true;
        List<String> parts = new ArrayList<>();
        if (showKind) parts.add(kind.equals("movie") ? Ui.tr("Movie") : Ui.tr("Series"));
        String genre = g != null && g.length() > 0 ? genres.get(g.optInt(0)) : null;
        if (genre != null) parts.add(genre);
        String date = o.optString(kind.equals("movie") ? "release_date" : "first_air_date", "");
        if (date.length() >= 4) parts.add(date.substring(0, 4));
        p.meta = String.join(" · ", parts);
        return p;
    }

    // MARK: - Search (the top bar's magnifier)

    /**
     * Movies and shows matching the text that are on one of the TV's services, best matches first. On a background
     * thread: about one TMDB request per result. Calls `done` on the main thread (an empty list when nothing matched).
     */
    void search(String query, java.util.function.Consumer<List<Recs.Pick>> done) {
        io.execute(() -> {
            List<Recs.Pick> out = new ArrayList<>();
            try {
                Recs recs = Recs.get(context);
                String lang = Recs.tmdbLanguage();
                Map<Integer, String> movieGenres = genres(recs, "movie", lang), tvGenres = genres(recs, "tv", lang);
                JSONArray found = recs.tmdb("/search/multi?include_adult=false&language=" + lang + "&query="
                        + URLEncoder.encode(query, "UTF-8")).getJSONArray("results");
                for (int i = 0; i < found.length() && i < 12 && out.size() < PER_ROW; i++) {
                    JSONObject o = found.getJSONObject(i);
                    String kind = o.optString("media_type");
                    if (!kind.equals("movie") && !kind.equals("tv")) continue;
                    JSONObject where = recs.tmdb("/" + kind + "/" + o.optInt("id") + "/watch/providers").optJSONObject("results");
                    JSONObject here = where == null ? null : where.optJSONObject(Recs.REGION);
                    JSONArray flat = here == null ? null : here.optJSONArray("flatrate");
                    String[] s = null;
                    for (int j = 0; flat != null && j < flat.length() && s == null; j++) {
                        s = recs.tvService(flat.getJSONObject(j).optString("provider_name"), flat.getJSONObject(j).optInt("provider_id"));
                    }
                    if (s == null) continue;   // not on any of the TV's apps
                    Recs.Pick p = pick(o, kind, s, kind.equals("movie") ? movieGenres : tvGenres, true, false);
                    if (p != null) out.add(p);
                }
            } catch (Exception e) {
                AppLog.w("search: " + e.getMessage());
            }
            main.post(() -> done.accept(out));
        });
    }

    // MARK: - Spotify (its editorial playlists: names and covers from the public oEmbed, no account)

    /** {playlist, style}: house, lo-fi, R&B, pop and chill (what's played in this house). A new selection every hour. */
    private static final String[][] PLAYLISTS = {
        {"37i9dQZF1DX2TRYkJECvfC", "House"}, {"37i9dQZF1DXa8NOEUWPn9W", "House"}, {"37i9dQZF1DX0AMssoUKCz7", "House"},
        {"37i9dQZF1DX0r3x8OtiwEM", "House"}, {"37i9dQZF1DX4dyzvuaRJ0n", "Dance"}, {"37i9dQZF1DX8tZsk68tuDw", "Dance"},
        {"37i9dQZF1DXaXB8fQg7xif", "Dance"},
        {"37i9dQZF1DWWQRwui0ExPn", "Lo-fi"}, {"37i9dQZF1DX8Uebhn9wzrS", "Lo-fi"}, {"37i9dQZF1DX9RwfGbeGQwP", "Lo-fi"},
        {"37i9dQZF1DWYoYGBbGKurt", "Lo-fi"}, {"37i9dQZF1DXdLK5wjKyhVm", "Lo-fi"},
        {"37i9dQZF1DX2UgsUIg75Vg", "R&B"}, {"37i9dQZF1DX4SBhb3fqCJd", "R&B"}, {"37i9dQZF1DWYmmr74INQlb", "R&B"},
        {"37i9dQZF1DX6VDO8a6cQME", "R&B"}, {"37i9dQZF1DX7FY5ma9162x", "R&B"}, {"37i9dQZF1DWUzFXarNiofw", "R&B"},
        {"37i9dQZF1DWUa8ZRTfalHk", "Pop"}, {"37i9dQZF1DXcBWIGoYBM5M", "Pop"}, {"37i9dQZF1DWTwnEm1IYyoj", "Pop"},
        {"37i9dQZF1DXbYM3nMM0oPk", "Pop"}, {"37i9dQZF1DWVlYsZJXqdym", "Pop"}, {"37i9dQZF1DX3rxVfibe1L0", "Pop"},
        {"37i9dQZF1DXdPec7aLTmlC", "Pop"}, {"37i9dQZF1DWWEcRhUVtL8n", "Pop"}, {"37i9dQZF1DX4fpCWaHOned", "Pop"},
        {"37i9dQZF1DX889U0CL85jj", "Chill"}, {"37i9dQZF1DX4WYpdgoIcn6", "Chill"}, {"37i9dQZF1DWTvNyxOwkztu", "Chill"},
        {"37i9dQZF1DXc8kgYqQLMfH", "Chill"}, {"37i9dQZF1DX1s9knjP51Oa", "Chill"},
    };
    /** Styles in the order they take turns, and how many of each an hour shows. */
    private static final String[] STYLES = {"House", "Lo-fi", "R&B", "Pop", "Dance", "Chill"};
    private static final int[] PER_HOUR = {3, 3, 3, 3, 1, 1};

    /** The hero's "Listen now": this hour's first playlist, its cover as the background (Recs builds it). */
    Recs.Pick listenNow() {
        for (Row r : rows()) {
            if (!r.square || r.items.isEmpty()) continue;
            Recs.Pick p = r.items.get((int) (System.currentTimeMillis() / 3600_000L % r.items.size()));
            Recs.Pick h = new Recs.Pick();
            h.id = p.id;
            h.kind = "playlist";
            h.title = p.title;
            h.backdrop = p.poster;
            h.app = p.app;
            h.pkg = p.pkg;
            h.link = p.link;
            h.detailed = true;
            h.meta = Ui.trf("{style} · Playlist", "style", p.meta);
            h.overview = Ui.trf("A {style} playlist for right now, on Spotify.", "style", p.meta.toLowerCase(Ui.locale()));
            return h;
        }
        return null;
    }

    /** This hour's playlists from the whole set: each style starts somewhere else every hour, the styles taking turns. */
    private static List<Recs.Pick> playlistsNow(List<Recs.Pick> all) {
        long hour = System.currentTimeMillis() / 3600_000L;
        List<List<Recs.Pick>> picked = new ArrayList<>();
        for (int s = 0; s < STYLES.length; s++) {
            List<Recs.Pick> style = new ArrayList<>();
            for (Recs.Pick p : all) if (STYLES[s].equals(p.meta)) style.add(p);
            List<Recs.Pick> now = new ArrayList<>();
            for (int i = 0; i < PER_HOUR[s] && i < style.size(); i++) {
                now.add(style.get((int) ((hour * PER_HOUR[s] + i) % style.size())));
            }
            picked.add(now);
        }
        List<Recs.Pick> out = new ArrayList<>();
        for (int i = 0; i < 3; i++) for (List<Recs.Pick> l : picked) if (i < l.size()) out.add(l.get(i));
        return out;
    }

    private List<Recs.Pick> spotify() {
        List<Recs.Pick> out = new ArrayList<>();
        String pkg = "com.spotify.tv.android";
        if (HomeApps.launchIntent(context.getPackageManager(), pkg) == null) return out;
        for (String[] pl : PLAYLISTS) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://open.spotify.com/oembed?url="
                        + URLEncoder.encode("https://open.spotify.com/playlist/" + pl[0], "UTF-8")).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                JSONObject o;
                try (InputStream in = c.getInputStream()) {
                    o = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
                } finally {
                    c.disconnect();
                }
                Recs.Pick p = new Recs.Pick();
                p.id = pl[0].hashCode();
                p.kind = "playlist";
                p.title = o.optString("title");
                p.poster = o.optString("thumbnail_url");
                p.app = "spotify";
                p.pkg = pkg;
                p.link = "spotify:playlist:" + pl[0];
                p.meta = pl[1];   // the style: what the cover doesn't say
                p.detailed = true;
                if (!p.title.isEmpty() && p.poster.startsWith("https://")) out.add(p);
            } catch (Exception e) {
                AppLog.w("spotify playlist " + pl[0] + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toByteArray();
    }

    // MARK: - Netflix's Top 10 (Tudum's weekly lists, the TV's country)

    /** {movies, shows}: this week's Top 10 on Netflix, each title found on TMDB. Kept for a day. */
    @SuppressWarnings("unchecked")
    private List<Recs.Pick>[] top10(Recs recs, String lang, String[] nf, Map<Integer, String> movieGenres,
                                    Map<Integer, String> tvGenres) {
        List<Recs.Pick>[] out = new List[] {new ArrayList<Recs.Pick>(), new ArrayList<Recs.Pick>()};
        try {
            String saved = cache.getString("top10", null);
            long at = cache.getLong("top10At", 0), now = System.currentTimeMillis();
            String[][] names;
            if (saved != null && now - at < TOP10_EVERY && now >= at) {
                JSONArray a = new JSONArray(saved);
                names = new String[][] {strings(a.getJSONArray(0)), strings(a.getJSONArray(1))};
            } else {
                names = readTop10(Recs.REGION);
                cache.edit().putString("top10", new JSONArray().put(new JSONArray(java.util.Arrays.asList(names[0])))
                        .put(new JSONArray(java.util.Arrays.asList(names[1]))).toString()).putLong("top10At", now).apply();
            }
            for (int c = 0; c < 2; c++) {
                String kind = c == 0 ? "movie" : "tv";
                for (int i = 0; i < names[c].length; i++) {
                    JSONArray found = recs.tmdb("/search/" + kind + "?language=" + lang + "&query="
                            + URLEncoder.encode(names[c][i], "UTF-8")).getJSONArray("results");
                    // The exact title first (Tudum's names are the English ones), then TMDB's best match.
                    JSONObject best = null;
                    for (int j = 0; j < found.length() && j < 10 && best == null; j++) {
                        JSONObject o = found.getJSONObject(j);
                        String a = o.optString(kind.equals("movie") ? "original_title" : "original_name");
                        String b = o.optString(kind.equals("movie") ? "title" : "name");
                        if (names[c][i].equalsIgnoreCase(a) || names[c][i].equalsIgnoreCase(b)) best = o;
                    }
                    for (int j = 0; j < found.length() && j < 3; j++) {
                        Recs.Pick p = pick(best != null ? best : found.getJSONObject(j), kind, nf,
                                c == 0 ? movieGenres : tvGenres, false, false);
                        if (p != null) {
                            p.rank = i + 1;   // Tudum's place, even when a title before it wasn't found
                            out[c].add(p);
                            break;
                        }
                        if (best != null) break;
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w("netflix top 10: " + e.getMessage());
        }
        return out;
    }

    private static String[] strings(JSONArray a) {
        String[] s = new String[a.length()];
        for (int i = 0; i < s.length; i++) s[i] = a.optString(i);
        return s;
    }

    /**
     * Reads Tudum's file (sorted by country, the newest week first) until the country's block ends: about 3 MB for
     * Brazil. The show list names each show once (its newest season).
     */
    private static String[][] readTop10(String country) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(TOP10).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        List<String> films = new ArrayList<>(), shows = new ArrayList<>();
        String week = null;
        try (InputStream in = c.getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean inside = false;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length < 7) continue;
                if (!f[1].equals(country)) {
                    if (inside) break;
                    continue;
                }
                inside = true;
                if (week == null) week = f[2];
                if (!f[2].equals(week)) break;   // only the newest week
                List<String> list = f[3].equals("Films") ? films : shows;
                if (!list.contains(f[5])) list.add(f[5]);
            }
        } finally {
            c.disconnect();
        }
        if (films.isEmpty() && shows.isEmpty()) throw new IOException("no Top 10 for " + country);
        return new String[][] {films.toArray(new String[0]), shows.toArray(new String[0])};
    }

    private static String day(int offset) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_YEAR, offset);
        return String.format(java.util.Locale.US, "%04d-%02d-%02d", cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH));
    }

    // MARK: - Card images

    /** The card's image if it's ready; otherwise null, and it's loaded in the background (onRows when it is). */
    Card card(Recs.Pick p) {
        return p == null || p.backdrop == null ? null : image(p.backdrop, false);
    }

    /** The Top 10's portrait card: the title's poster (TMDB's w342, kept at 180×270). */
    Card poster(Recs.Pick p) {
        return p == null || p.poster == null ? null : image(p.poster, true);
    }

    private Card image(String path, boolean poster) {
        String key = (poster ? "p:" : "") + path;
        synchronized (cards) {
            Card ready = cards.get(key);
            if (ready != null || failed.contains(key) || !loading.add(key)) return ready;
        }
        images.execute(() -> {
            Card made = null;
            try {
                made = load(path, poster);
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("row image: " + e.getMessage());
            }
            synchronized (cards) {
                loading.remove(key);
                if (made == null) {
                    failed.add(key);
                } else {
                    cards.put(key, made);
                    // The least recently drawn go (the GPU frees them once nothing draws them).
                    while (cards.size() > CARDS) cards.remove(cards.keySet().iterator().next());
                }
            }
            notifyListeners();
        });
        return null;
    }

    private Card load(String path, boolean poster) throws IOException {
        File dir = new File(context.getCacheDir(), "rows");
        File f = new File(dir, (poster ? "p" : "") + path.replaceAll("[^A-Za-z0-9._-]", ""));
        if (!f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File part = new File(f.getPath() + ".part");
            String url = path.startsWith("https://") ? path : (poster ? POSTERS : IMAGES) + path;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            } finally {
                c.disconnect();
            }
            if (!part.renameTo(f)) throw new IOException("couldn't save the image");
            prune(dir);
        } else {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) throw new IOException("unreadable image");
        boolean square = path.startsWith("https://");   // Spotify's covers
        int w = Math.round((square ? SQUARE : poster ? POSTER_W : CARD_W) * Backdrop.W / 1920f);
        int h = Math.round((square ? SQUARE : poster ? POSTER_H : CARD_H) * Backdrop.W / 1920f);
        Bitmap small = Ui.downscale(b, w, h);
        if (small != b) b.recycle();
        return new Card(Backdrop.gpu(small));
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= FILES) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRows(); });
    }

    // MARK: - Saved lists

    private static JSONArray toJson(List<Row> rows) throws org.json.JSONException {
        JSONArray a = new JSONArray();
        for (Row r : rows) {
            JSONArray items = new JSONArray();
            for (Recs.Pick p : r.items) {
                items.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title)
                        .put("backdrop", p.backdrop).put("meta", p.meta).put("overview", p.overview)
                        .put("app", p.app).put("pkg", p.pkg).put("rank", p.rank).put("poster", p.poster == null ? "" : p.poster).put("link", p.link == null ? "" : p.link));
            }
            a.put(new JSONObject().put("title", r.title).put("subtitle", r.subtitle).put("ranked", r.ranked).put("square", r.square).put("items", items));
        }
        return a;
    }

    private static List<Row> parse(JSONArray a) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null) continue;
            List<Recs.Pick> items = new ArrayList<>();
            JSONArray l = r.optJSONArray("items");
            for (int j = 0; l != null && j < l.length(); j++) {
                JSONObject o = l.optJSONObject(j);
                if (o == null) continue;
                Recs.Pick p = new Recs.Pick();
                p.id = o.optInt("id");
                p.kind = o.optString("kind");
                p.title = o.optString("title");
                p.backdrop = o.optString("backdrop");
                p.meta = o.optString("meta");
                p.overview = o.optString("overview");
                p.app = o.optString("app");
                p.pkg = o.optString("pkg");
                p.rank = o.optInt("rank");
                String link = o.optString("link");
                if (!link.isEmpty()) p.link = link;
                String poster = o.optString("poster");
                if (!poster.isEmpty()) p.poster = poster;
                p.detailed = true;
                items.add(p);
            }
            out.add(new Row(r.optString("title"), r.optString("subtitle"), items, r.optBoolean("ranked"), r.optBoolean("square")));
        }
        return out;
    }
}
