package io.freemytv;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The next event from your calendar's secret iCal address (config.properties: calendar_ical), for the top bar. Read
 * every 15 minutes. Recurring events are expanded for the daily and weekly rules meetings use (INTERVAL, BYDAY, UNTIL,
 * COUNT, EXDATE); anything rarer shows its first date only. All-day events are left out: the bar is for what's next.
 */
final class Agenda {
    interface Listener { void onCalendar(); }

    /** What the bar shows: the title, and when it starts and ends (ms); empty when nothing is coming. */
    static final class Event {
        final String title;
        final long start, end;

        Event(String title, long start, long end) {
            this.title = title;
            this.start = start;
            this.end = end;
        }
    }

    private static final long EVERY = 15 * 60_000L, AHEAD = 12 * 3600_000L;
    private static Agenda instance;

    static synchronized Agenda get() {
        if (instance == null) instance = new Agenda();
        return instance;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile List<Event> events = new ArrayList<>();
    private volatile long fetchedAt;
    private volatile boolean fetching;

    static boolean on() {
        return !Config.CALENDAR_ICAL.trim().isEmpty();
    }

    /** The event happening now or the next one in the coming 12 hours, or null. */
    Event next() {
        long now = System.currentTimeMillis();
        Event best = null;
        for (Event e : events) {
            if (e.end <= now || e.start - now > AHEAD) continue;
            if (best == null || e.start < best.start) best = e;
        }
        return best;
    }

    void refreshIfStale(Listener done) {
        if (!on() || fetching) return;
        long now = System.currentTimeMillis();
        if (now - fetchedAt < EVERY && now >= fetchedAt) return;
        fetching = true;
        io.execute(() -> {
            try {
                events = parse(download(Config.CALENDAR_ICAL.trim()));
                fetchedAt = System.currentTimeMillis();
            } catch (Exception e) {
                AppLog.w("calendar: " + e.getMessage());
                fetchedAt = System.currentTimeMillis() - EVERY + 60_000L;   // again in a minute
            } finally {
                fetching = false;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(done::onCalendar);
            }
        });
    }

    private static List<String> download(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address.replace("webcal://", "https://")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                // Folded lines (RFC 5545): a line starting with a space or a tab continues the one before.
                if (!lines.isEmpty() && (line.startsWith(" ") || line.startsWith("\t"))) {
                    lines.set(lines.size() - 1, lines.get(lines.size() - 1) + line.substring(1));
                } else {
                    lines.add(line);
                }
            }
        } finally {
            c.disconnect();
        }
        return lines;
    }

    /** The events that reach into the next 12 hours (recurring ones expanded up to there). */
    private static List<Event> parse(List<String> lines) {
        List<Event> out = new ArrayList<>();
        long now = System.currentTimeMillis(), until = now + AHEAD;
        String summary = null, rrule = null, status = null;
        long start = 0, end = 0;
        boolean inEvent = false, allDay = false;
        Set<Long> exdates = new HashSet<>();
        Set<String> cancelledRecurrences = new HashSet<>();
        for (String line : lines) {
            if (line.equals("BEGIN:VEVENT")) {
                inEvent = true;
                summary = rrule = status = null;
                start = end = 0;
                allDay = false;
                exdates = new HashSet<>();
                continue;
            }
            if (!inEvent) continue;
            if (line.equals("END:VEVENT")) {
                inEvent = false;
                if (start == 0 || allDay || "CANCELLED".equals(status)) continue;
                if (end <= start) end = start + 30 * 60_000L;
                String title = summary == null || summary.isEmpty() ? Ui.tr("Event") : summary;
                if (rrule == null) {
                    if (end > now && start < until) out.add(new Event(title, start, end));
                } else {
                    expand(out, title, start, end - start, rrule, exdates, now, until);
                }
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon), value = line.substring(colon + 1);
            String key = name.contains(";") ? name.substring(0, name.indexOf(';')) : name;
            switch (key) {
                case "SUMMARY": summary = unescape(value); break;
                case "STATUS": status = value; break;
                case "RRULE": rrule = value; break;
                case "DTSTART":
                    allDay = name.contains("VALUE=DATE") && !name.contains("DATE-TIME") || value.length() == 8;
                    start = time(name, value);
                    break;
                case "DTEND": end = time(name, value); break;
                case "EXDATE":
                    for (String v : value.split(",")) exdates.add(time(name, v));
                    break;
                default: break;
            }
        }
        return out;
    }

    /** A daily or weekly rule, from its first date until `until` (enough for the bar). */
    private static void expand(List<Event> out, String title, long first, long length, String rule, Set<Long> exdates,
                               long now, long until) {
        String freq = part(rule, "FREQ");
        if (!"DAILY".equals(freq) && !"WEEKLY".equals(freq)) {
            if (first + length > now && first < until) out.add(new Event(title, first, first + length));
            return;
        }
        int interval = 1;
        try {
            if (part(rule, "INTERVAL") != null) interval = Integer.parseInt(part(rule, "INTERVAL"));
        } catch (NumberFormatException ignored) {
        }
        String untilRule = part(rule, "UNTIL");
        long last = untilRule == null ? Long.MAX_VALUE : time("UNTIL", untilRule);
        String countRule = part(rule, "COUNT");
        int count = countRule == null ? Integer.MAX_VALUE : Integer.parseInt(countRule);
        String byDay = part(rule, "BYDAY");
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(first);
        java.util.Calendar firstCal = (java.util.Calendar) cal.clone();
        int made = 0;
        // Day by day from the first date (keeping its time of day), so BYDAY and the week interval line up.
        for (int i = 0; i < 4000 && cal.getTimeInMillis() < until && cal.getTimeInMillis() <= last && made < count; i++) {
            long t = cal.getTimeInMillis();
            boolean match;
            if ("DAILY".equals(freq)) {
                long days = Math.round((t - first) / 86_400_000.0);
                match = days % interval == 0;
            } else {
                long weeks = (long) Math.floor((startOfWeek(cal) - startOfWeek(firstCal)) / (7 * 86_400_000.0) + 0.5);
                boolean day = byDay == null ? cal.get(java.util.Calendar.DAY_OF_WEEK) == firstCal.get(java.util.Calendar.DAY_OF_WEEK)
                        : byDay.contains(DAYS[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]);
                match = day && weeks % interval == 0;
            }
            if (match) {
                made++;
                if (!exdates.contains(t) && t + length > now) out.add(new Event(title, t, t + length));
            }
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
        }
    }

    private static final String[] DAYS = {"SU", "MO", "TU", "WE", "TH", "FR", "SA"};

    private static long startOfWeek(java.util.Calendar c) {
        java.util.Calendar s = (java.util.Calendar) c.clone();
        s.set(java.util.Calendar.HOUR_OF_DAY, 12);
        s.add(java.util.Calendar.DAY_OF_MONTH, -(s.get(java.util.Calendar.DAY_OF_WEEK) - java.util.Calendar.MONDAY + 7) % 7);
        return s.getTimeInMillis();
    }

    private static String part(String rule, String name) {
        for (String p : rule.split(";")) if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        return null;
    }

    /** "20261025T143000Z", "20261025T143000" with TZID=…, or "20261025". */
    private static long time(String name, String value) {
        try {
            TimeZone zone = TimeZone.getDefault();
            int tz = name.indexOf("TZID=");
            if (tz >= 0) {
                String id = name.substring(tz + 5);
                if (id.contains(";")) id = id.substring(0, id.indexOf(';'));
                zone = TimeZone.getTimeZone(id);
            }
            if (value.endsWith("Z")) zone = TimeZone.getTimeZone("UTC");
            String v = value.replace("Z", "");
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(v.length() == 8 ? "yyyyMMdd" : "yyyyMMdd'T'HHmmss", Locale.US);
            f.setTimeZone(zone);
            return f.parse(v).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String unescape(String s) {
        return s.replace("\\,", ",").replace("\\;", ";").replace("\\n", " ").replace("\\N", " ").replace("\\\\", "\\").trim();
    }
}
