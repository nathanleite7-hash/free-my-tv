package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * "Recently watched": what was opened from this home screen, newest first. Google TV keeps its own "Continue watching"
 * where another home screen can't read it (the TV's Watch Next table stays empty), so this is the honest version:
 * titles opened from the hero or the rows, and apps opened from the shelf. Kept on the TV only, in recent.json.
 */
final class Recent {
    static final int MAX = 9;
    private static final String FILE = "recent.json";

    private Recent() {}

    /** A title opened from the hero or a row. */
    static void add(Context c, Recs.Pick p) {
        if (p == null || p.pkg == null || p.title == null) return;
        JSONObject o = new JSONObject();
        try {
            o.put("key", Recs.key(p));
            o.put("id", p.id);
            o.put("kind", p.kind);
            o.put("title", p.title);
            o.put("meta", p.baseMeta != null ? p.baseMeta : p.meta);
            o.put("app", p.app);
            o.put("pkg", p.pkg);
            o.put("link", p.link);
            o.put("backdrop", p.backdrop);
            o.put("at", System.currentTimeMillis());
        } catch (org.json.JSONException e) {
            return;
        }
        put(c, o);
    }

    /** An app opened from the shelf (YouTube, Spotify…). */
    static void addApp(Context c, String key, String pkg, String label) {
        if (pkg == null || label == null) return;
        JSONObject o = new JSONObject();
        try {
            o.put("key", "app:" + pkg);
            o.put("title", label);
            o.put("app", key);
            o.put("pkg", pkg);
            o.put("isApp", true);
            o.put("at", System.currentTimeMillis());
        } catch (org.json.JSONException e) {
            return;
        }
        put(c, o);
    }

    /** Newest first; an app's card says when it was opened in its meta line. */
    static List<Recs.Pick> list(Context c) {
        List<Recs.Pick> out = new ArrayList<>();
        JSONArray a = read(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Recs.Pick p = new Recs.Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind", null);
            p.title = o.optString("title");
            p.app = o.optString("app", null);
            p.pkg = o.optString("pkg", null);
            p.link = o.isNull("link") ? null : o.optString("link", null);
            p.backdrop = o.isNull("backdrop") ? null : o.optString("backdrop", null);
            p.recentApp = o.optBoolean("isApp");
            String ago = ago(o.optLong("at"));
            String meta = o.optString("meta");
            p.baseMeta = meta;
            p.meta = p.recentApp ? Ui.trf("Opened {ago}", "ago", ago) : meta.isEmpty() ? ago : ago + " · " + meta;
            out.add(p);
        }
        return out;
    }

    private static String ago(long at) {
        long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
        if (minutes < 1) return Ui.tr("now");
        if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
        if (minutes < 48 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
        return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
    }

    private static synchronized void put(Context c, JSONObject item) {
        JSONArray old = read(c), next = new JSONArray();
        next.put(item);
        String key = item.optString("key");
        for (int i = 0; i < old.length() && next.length() < MAX; i++) {
            JSONObject o = old.optJSONObject(i);
            if (o != null && !key.equals(o.optString("key"))) next.put(o);
        }
        try {
            File tmp = new File(c.getFilesDir(), FILE + ".tmp");
            java.nio.file.Files.write(tmp.toPath(), next.toString().getBytes(StandardCharsets.UTF_8));
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(new File(c.getFilesDir(), FILE));
        } catch (java.io.IOException e) {
            AppLog.w("recent: " + e.getMessage());
        }
    }

    private static synchronized JSONArray read(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return new JSONArray();
        try {
            return new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONArray();
        }
    }
}
