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
        final String title;
        final List<Recs.Pick> items;

        Row(String title, List<Recs.Pick> items) {
            this.title = title;
            this.items = items;
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
    private static final int CACHE_VERSION = 3, CARDS = 36, FILES = 160;
    private static final int CARD_W = 480, CARD_H = 270;
    private static final String IMAGES = "https://image.tmdb.org/t/p/w780";
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
        return Recs.hasKey(context) ? rows : new ArrayList<>();
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
        if (netflixId >= 0) {
            String[] nf = services.get(netflixId);
            List<Recs.Pick>[] top = top10(recs, lang, nf, movieGenres, tvGenres);
            add(out, seen, Ui.tr("Top 10 movies on Netflix today"), top[0]);
            add(out, seen, Ui.tr("Top 10 shows on Netflix today"), top[1]);
            add(out, seen, Ui.tr("Popular movies on Netflix"), discover(recs, "movie", netflixId, nf, lang, movieGenres, PER_ROW));
            add(out, seen, Ui.tr("Popular shows on Netflix"), discover(recs, "tv", netflixId, nf, lang, tvGenres, PER_ROW));
            add(out, seen, Ui.tr("New on Netflix"), fresh(recs, netflixId, nf, lang, movieGenres, tvGenres));
        }
        // The other services: a few of each, movies and shows taking turns.
        List<List<Recs.Pick>> others = new ArrayList<>();
        for (Map.Entry<Integer, String[]> e : services.entrySet()) {
            if (e.getKey() == netflixId) continue;
            List<Recs.Pick> both = new ArrayList<>();
            List<Recs.Pick> m = discover(recs, "movie", e.getKey(), e.getValue(), lang, movieGenres, 4);
            List<Recs.Pick> t = discover(recs, "tv", e.getKey(), e.getValue(), lang, tvGenres, 4);
            for (int i = 0; i < 4; i++) {
                if (i < t.size()) both.add(t.get(i));
                if (i < m.size()) both.add(m.get(i));
            }
            others.add(both);
        }
        List<Recs.Pick> mixed = new ArrayList<>();
        for (int i = 0; i < 8 && mixed.size() < PER_ROW; i++) {
            for (List<Recs.Pick> l : others) if (i < l.size() && mixed.size() < PER_ROW) mixed.add(l.get(i));
        }
        add(out, seen, netflixId >= 0 ? Ui.tr("Popular on your other apps") : Ui.tr("Popular on your apps"), mixed);
        return out;
    }

    private static void add(List<Row> out, Set<String> seen, String title, List<Recs.Pick> items) {
        List<Recs.Pick> kept = new ArrayList<>();
        for (Recs.Pick p : items) if (seen.add(Recs.key(p))) kept.add(p);
        if (kept.size() >= 3) out.add(new Row(title, kept));
    }

    private static Map<Integer, String> genres(Recs recs, String kind, String lang) throws Exception {
        Map<Integer, String> out = new HashMap<>();
        JSONArray a = recs.tmdb("/genre/" + kind + "/list?language=" + lang).getJSONArray("genres");
        for (int i = 0; i < a.length(); i++) out.put(a.getJSONObject(i).getInt("id"), Recs.genreName(a.getJSONObject(i).optString("name")));
        return out;
    }

    /** Popular on one service (TMDB's discover: what it has in the TV's country, by popularity). */
    private static List<Recs.Pick> discover(Recs recs, String kind, int provider, String[] s, String lang,
                                            Map<Integer, String> genres, int max) throws Exception {
        String q = "/discover/" + kind + "?language=" + lang + "&watch_region=" + Recs.REGION
                + "&with_watch_providers=" + provider + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&vote_count.gte=" + (kind.equals("movie") ? 50 : 20)
                + (Config.HIDE_ANIMATION ? "&without_genres=16" : "");
        List<Recs.Pick> out = new ArrayList<>();
        for (int page = 1; page <= 2 && out.size() < max; page++) {
            JSONArray a = recs.tmdb(q + "&page=" + page).getJSONArray("results");
            for (int i = 0; i < a.length() && out.size() < max; i++) {
                Recs.Pick p = pick(a.getJSONObject(i), kind, s, genres, null);
                if (p != null) out.add(p);
            }
        }
        return out;
    }

    /** New on the service: movies from the past 3 months and shows with an episode in the past month. */
    private static List<Recs.Pick> fresh(Recs recs, int provider, String[] s, String lang, Map<Integer, String> movieGenres,
                                         Map<Integer, String> tvGenres) throws Exception {
        String common = "&language=" + lang + "&watch_region=" + Recs.REGION + "&with_watch_providers=" + provider
                + "&with_watch_monetization_types=flatrate&include_adult=false&sort_by=popularity.desc"
                + (Config.HIDE_ANIMATION ? "&without_genres=16" : "");
        JSONArray m = recs.tmdb("/discover/movie?primary_release_date.gte=" + day(-90) + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray t = recs.tmdb("/discover/tv?air_date.gte=" + day(-30) + "&air_date.lte=" + day(0) + common).getJSONArray("results");
        List<Recs.Pick> out = new ArrayList<>();
        for (int i = 0; i < 10 && out.size() < PER_ROW; i++) {
            if (i < t.length()) {
                Recs.Pick p = pick(t.getJSONObject(i), "tv", s, tvGenres, null);
                if (p != null) out.add(p);
            }
            if (i < m.length()) {
                Recs.Pick p = pick(m.getJSONObject(i), "movie", s, movieGenres, null);
                if (p != null) out.add(p);
            }
        }
        return out;
    }

    /** "Movie · Action · 2025 · Netflix" (or "#3 on Netflix · …" for the Top 10), from a TMDB list entry. */
    private static Recs.Pick pick(JSONObject o, String kind, String[] s, Map<Integer, String> genres, String rank) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return null;
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
        p.overview = o.optString("overview");
        p.app = s[1];
        p.pkg = s[2];
        p.detailed = true;
        List<String> parts = new ArrayList<>();
        if (rank != null) parts.add(rank);
        parts.add(kind.equals("movie") ? Ui.tr("Movie") : Ui.tr("Series"));
        String genre = g != null && g.length() > 0 ? genres.get(g.optInt(0)) : null;
        if (genre != null) parts.add(genre);
        String date = o.optString(kind.equals("movie") ? "release_date" : "first_air_date", "");
        if (date.length() >= 4 && rank == null) parts.add(date.substring(0, 4));
        if (rank == null) parts.add(Recs.serviceName(p));
        p.meta = String.join(" · ", parts);
        return p;
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
                                c == 0 ? movieGenres : tvGenres, Ui.trf("#{n} on Netflix", "n", i + 1));
                        if (p != null) {
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
        if (p == null || p.backdrop == null) return null;
        String key = p.backdrop;
        synchronized (cards) {
            Card ready = cards.get(key);
            if (ready != null || failed.contains(key) || !loading.add(key)) return ready;
        }
        images.execute(() -> {
            Card made = null;
            try {
                made = load(key);
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

    private Card load(String path) throws IOException {
        File dir = new File(context.getCacheDir(), "rows");
        File f = new File(dir, path.replace("/", ""));
        if (!f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File part = new File(f.getPath() + ".part");
            HttpURLConnection c = (HttpURLConnection) new URL(IMAGES + path).openConnection();
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
        int w = Math.round(CARD_W * Backdrop.W / 1920f), h = Math.round(CARD_H * Backdrop.W / 1920f);
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
                        .put("app", p.app).put("pkg", p.pkg));
            }
            a.put(new JSONObject().put("title", r.title).put("items", items));
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
                p.detailed = true;
                items.add(p);
            }
            out.add(new Row(r.optString("title"), items));
        }
        return out;
    }
}
