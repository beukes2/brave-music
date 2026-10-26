package com.space.braveplay;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Playlist + play history.
 *
 * Built-in links live in {@link Urls}; links added by the user and songs found
 * automatically are stored in shared preferences. History enforces "no repeat within an
 * hour" (user taps are allowed to override it).
 */
public final class Library {

    public static final String PREFS = "brave_play";

    public static final String KEY_MUSIC_ON = "music_on";
    public static final String KEY_URL = "current_url";
    public static final String KEY_ID = "current_id";
    public static final String KEY_TITLE = "current_title";
    public static final String KEY_DURATION = "current_duration";
    public static final String KEY_OPEN_AT = "open_at";
    public static final String KEY_PLAYED_MS = "played_ms";
    public static final String KEY_PLAYING_SINCE = "playing_since";
    /** Changes on every explicit play, so replaying the same URL still resets progress. */
    public static final String KEY_SESSION = "play_session";
    /** User wants songs recorded and played from local storage. */
    public static final String KEY_RECORD_LOCAL = "record_local";

    /** Repeat window: the same song is not chosen again for an hour. */
    public static final long REPEAT_WINDOW_MS = 60 * 60 * 1000L;

    private static final String KEY_CUSTOM = "custom_tracks";
    private static final String KEY_HISTORY = "history";

    /** id, title, author, duration (s). 0 = live stream. */
    private static final String[][] META = {
            {"rFZHOHl-L8A", "lofi hip hop radio \uD83D\uDCDA beats to relax/study to", "Lofi Girl", "0"},
            {"fJzu0yls8Bk", "Coastal Town", "Kupla - Topic", "155"},
            {"B4rQ_CA2Yy0", "Sun Is Shining", "Axwell /\\ Ingrosso - Topic", "255"},
            {"uq-N56mddbE", "THYLACINE - Piany Pianino [Transsiberian Album]", "Thylacine", "273"},
            {"7o7zpdB7yOk", "Worakls - Red Dressed Feat. Eiv\u00F8r (Ben B\u00F6hmer Remix)", "Dancecode Electronics", "359"},
            {"yKUutUd9BiA", "THYLACINE - Shark Island (Official Music Video)", "Thylacine", "204"},
            {"uXXsxlE00TE", "Ivan Gough & Feenixpawl ft. Georgi Kay - In My Mind (Axwell Radio Edit)", "Axtone", "186"},
            {"bvC_0foemLY", "Robin Schulz - Sugar (feat. Francesco Yates) (OFFICIAL MUSIC VIDEO)", "Robin Schulz", "224"},
            {"bpOSxM0rNPM", "Arctic Monkeys - Do I Wanna Know? (Official Video)", "Official Arctic Monkeys", "266"},
            {"PyUNDr0EcAk", "Mr. Blue Sky | Electric Light Orchestra | Pomplamoose", "PomplamooseMusic", "274"},
            {"ts3-csnz14c", "Kezwik - Let Go (feat. Mimi Page) - (Kairo Kingdom remix)", "Mimi Page", "240"},
            {"gfiqW1WaGbw", "Les Yeux Noirs (Dark Eyes) | Django Reinhardt | Pomplamoose ft. The Vignes Rooftop Revival", "PomplamooseMusic", "179"},
            {"p5x6m91CzDA", "The Elegance of Silence - Relaxing French Songs", "Musical Salon de la France", "3837"},
            {"cPlgoNY2xqw", "Comedown (Remastered 2014)", "Bush - Topic", "327"},
            {"Q9pw_cY8uyk", "Belinda Carlisle - (We Want) The Same Thing (Official HD Music Video)", "Belinda Carlisle", "254"},
            {"BRftDZxg4dU", "Undeadmau5 - Deadmau5 continuous mix", "Meller", "6856"},
            {"UDXbPAHMZck", "Switchfoot - Darkness (Live)", "Switchfoot", "226"},
            {"cd4nSM9gMrw", "Just Tonight", "Jimmy Eat World - Topic", "206"},
            {"HEe3xfWfkG8", "Les Chevaliers du Ciel HD Promo", "BanjyNL", "225"},
            {"kANyUpQPr3s", "Reignite (feat. Malukah) (Edit)", "Headhunterz - Topic", "347"},
            {"QFmFS-PQe1s", "OVERWERK - Odyssey(remix)", "freesongpromotion", "268"},
            {"d0W4EVslhVQ", "odyssey-overwerk (MLG REMIX) (HD)", "13themlg", "289"},
            {"TNHafuFbxLc", "The Kings of Connaught - The Rocky Road to Dublin", "The Kings Of Connaught", "206"},
            {"L__TrWhq4Uk", "Whiskey in the Jar", "The Dubliners - Topic", "179"},
            {"8nW4jypamrA", "Stromae - Alors on Danse (Lyrics) [TikTok Song]", "Unique Sound", "208"},
            {"A3PDXmYoF5U", "GoPro HERO3: Almost as Epic as the HERO3+", "GoPro", "308"},
            {"GUEZCxBcM78", "The HD HERO2: Almost as Rad as the HERO3", "GoPro", "311"},
            {"HwPOyqnqNEw", "The Hobbit: An Unexpected Journey | Misty Mountains (Film Scene) | Richard Armitage & The Dwarf Cast", "WaterTower Music", "105"},
    };

    private static Map<String, String[]> metaById;

    private Library() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized Map<String, String[]> meta() {
        if (metaById == null) {
            Map<String, String[]> map = new HashMap<String, String[]>();
            for (String[] row : META) {
                map.put(row[0], new String[]{row[1], row[2], row[3]});
            }
            metaById = map;
        }
        return metaById;
    }

    public static int builtinCount() {
        return Urls.count();
    }

    public static List<Track> all(Context context) {
        List<Track> out = new ArrayList<Track>();
        for (String url : Urls.URLS) {
            out.add(builtin(url));
        }
        out.addAll(custom(context));
        return out;
    }

    public static Track builtin(String url) {
        String id = Urls.videoId(url);
        String[] meta = meta().get(id);
        if (meta == null) {
            return new Track(id, url, id, "", 0);
        }
        return new Track(id, url, meta[0], meta[1], parseInt(meta[2]));
    }

    public static List<Track> custom(Context context) {
        List<Track> out = new ArrayList<Track>();
        for (String line : prefs(context).getString(KEY_CUSTOM, "").split("\n")) {
            Track track = parseLine(line);
            if (track != null) {
                out.add(track);
            }
        }
        return out;
    }

    public static Track findById(Context context, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (Track track : all(context)) {
            if (track.id.equals(id)) {
                return track;
            }
        }
        return null;
    }

    public static Track findByUrl(Context context, String url) {
        if (url == null) {
            return null;
        }
        for (Track track : all(context)) {
            if (track.url.equals(url)) {
                return track;
            }
        }
        return null;
    }

    /** Currently playing track, synth esised from prefs when it is not in the list. */
    public static Track current(Context context) {
        SharedPreferences p = prefs(context);
        String url = p.getString(KEY_URL, "");
        if (url.isEmpty()) {
            return null;
        }
        Track found = findByUrl(context, url);
        if (found != null) {
            return found;
        }
        String id = p.getString(KEY_ID, Urls.videoId(url));
        return new Track(id, url, p.getString(KEY_TITLE, ""), "", p.getInt(KEY_DURATION, 0));
    }

    public static void add(Context context, Track track) {
        if (track == null || track.id.isEmpty()) {
            return;
        }
        if (findById(context, track.id) != null) {
            return;
        }
        List<Track> custom = custom(context);
        custom.add(track);
        saveCustom(context, custom);
    }

    public static void updateMeta(Context context, Track track) {
        List<Track> custom = custom(context);
        boolean changed = false;
        for (int i = 0; i < custom.size(); i++) {
            if (custom.get(i).id.equals(track.id)) {
                custom.set(i, track);
                changed = true;
            }
        }
        if (changed) {
            saveCustom(context, custom);
        }
    }

    /**
     * Removes YouTube Shorts/clips (a few seconds long) that were discovered before the
     * Music/duration filter existed. They are never songs for the radio.
     */
    public static void cleanupShorts(Context context) {
        List<Track> custom = custom(context);
        List<Track> kept = new ArrayList<Track>();
        int removed = 0;
        for (Track track : custom) {
            if (track.durationSec > 0 && track.durationSec < 45) {
                removed++;
                Logs.i("BravePlayLibrary", "removing clip \"" + track.displayTitle()
                        + "\" (" + track.durationSec + "s)");
            } else {
                kept.add(track);
            }
        }
        if (removed > 0) {
            saveCustom(context, kept);
            Logs.i("BravePlayLibrary", "removed " + removed + " clip(s) from the library");
        }
    }

    public static void removeAt(Context context, int position) {
        List<Track> custom = custom(context);
        int index = position - builtinCount();
        if (index < 0 || index >= custom.size()) {
            return;
        }
        custom.remove(index);
        saveCustom(context, custom);
    }

    // -------------------------------------------------------------- history

    public static void recordPlay(Context context, String id) {
        Map<String, Long> history = history(context);
        history.put(id, System.currentTimeMillis());
        saveHistory(context, history);
    }

    public static boolean playedRecently(Context context, String id) {
        Long at = history(context).get(id);
        return at != null && System.currentTimeMillis() - at < REPEAT_WINDOW_MS;
    }

    /** Next entry after {@code current} that has not been played in the last hour. */
    public static Track nextEligible(Context context, Track current) {
        List<Track> tracks = all(context);
        if (tracks.isEmpty()) {
            return null;
        }
        int start = 0;
        if (current != null) {
            for (int i = 0; i < tracks.size(); i++) {
                if (tracks.get(i).id.equals(current.id)) {
                    start = i + 1;
                    break;
                }
            }
        }
        for (int step = 0; step < tracks.size(); step++) {
            Track candidate = tracks.get((start + step) % tracks.size());
            if (current != null && candidate.id.equals(current.id)) {
                continue;
            }
            if (!playedRecently(context, candidate.id)) {
                return candidate;
            }
        }
        return null;
    }

    /** Least recently played track; used only when discovery fails. */
    public static Track oldestPlayed(Context context) {
        List<Track> tracks = all(context);
        Map<String, Long> history = history(context);
        Track best = null;
        long bestAt = Long.MAX_VALUE;
        for (Track track : tracks) {
            Long at = history.get(track.id);
            long when = at == null ? 0L : at;
            if (when < bestAt) {
                bestAt = when;
                best = track;
            }
        }
        return best;
    }

    // -------------------------------------------------------------- storage

    private static void saveCustom(Context context, List<Track> tracks) {
        StringBuilder sb = new StringBuilder();
        for (Track track : tracks) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(track.id).append('\t')
                    .append(track.url).append('\t')
                    .append(clean(track.title)).append('\t')
                    .append(clean(track.author)).append('\t')
                    .append(track.durationSec);
        }
        prefs(context).edit().putString(KEY_CUSTOM, sb.toString()).apply();
    }

    private static Track parseLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        String[] parts = line.split("\t", -1);
        if (parts.length < 5) {
            return null;
        }
        String id = parts[0].trim();
        String url = parts[1].trim();
        if (id.isEmpty() || url.isEmpty()) {
            return null;
        }
        return new Track(id, url, parts[2], parts[3], parseInt(parts[4]));
    }

    private static Map<String, Long> history(Context context) {
        Map<String, Long> out = new HashMap<String, Long>();
        long cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        for (String line : prefs(context).getString(KEY_HISTORY, "").split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length != 2) {
                continue;
            }
            try {
                long at = Long.parseLong(parts[1]);
                if (at > cutoff) {
                    out.put(parts[0], at);
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return out;
    }

    private static void saveHistory(Context context, Map<String, Long> history) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> entry : history.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(entry.getKey()).append('\t').append(entry.getValue());
        }
        prefs(context).edit().putString(KEY_HISTORY, sb.toString()).apply();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ');
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
