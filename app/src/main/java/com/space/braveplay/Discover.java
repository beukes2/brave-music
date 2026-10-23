package com.space.braveplay;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds new songs that fit the current one.
 *
 * <p>When the playlist has been exhausted (everything was played in the last hour) the app
 * asks YouTube for songs of a similar artist/genre, picks one at random that was not played
 * recently, fetches its real title and length, and adds it to the list.</p>
 */
public final class Discover {

    private static final String TAG = "BravePlayDiscover";

    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36";

    private static final Pattern VIDEO_ID =
            Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"");
    private static final Pattern LENGTH =
            Pattern.compile("\"lengthSeconds\":\"(\\d+)\"");
    private static final Pattern OG_TITLE =
            Pattern.compile("<meta property=\"og:title\" content=\"([^\"]*)\"");
    private static final Pattern API_KEY =
            Pattern.compile("\"INNERTUBE_API_KEY\":\"([^\"]+)\"");
    private static final Pattern CLIENT_VERSION =
            Pattern.compile("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"");

    private static final int MAX_CANDIDATES = 6;

    private Discover() {
    }

    /**
     * Blocking (network!) - call from a worker thread.
     *
     * @return a new track, already saved to the library, or null when nothing was found.
     */
    public static Track findSimilar(Context context, Track seed) {
        if (seed == null) {
            return null;
        }
        for (String query : queriesFor(seed)) {
            List<String> ids = search(query, seed.id);
            if (ids.isEmpty()) {
                continue;
            }
            Collections.shuffle(ids);
            int tried = 0;
            for (String id : ids) {
                if (id.equals(seed.id) || Library.playedRecently(context, id)) {
                    continue;
                }
                if (tried++ >= MAX_CANDIDATES) {
                    break;
                }
                Track track = fetch(id);
                if (track == null || Library.playedRecently(context, track.id)) {
                    continue;
                }
                Library.add(context, track);
                Log.i(TAG, "discovered \"" + track.title + "\" (" + track.id + ") for query "
                        + query);
                return track;
            }
        }
        Log.i(TAG, "no new song found for " + seed.id);
        return null;
    }

    private static List<String> queriesFor(Track seed) {
        List<String> queries = new ArrayList<String>();
        String author = cleanAuthor(seed.author);
        if (!author.isEmpty()) {
            queries.add(author + " mix");
        }
        String title = cleanTitle(seed.title);
        if (!title.isEmpty()) {
            queries.add(title + " radio");
        }
        if (queries.isEmpty()) {
            queries.add("music mix");
        }
        return queries;
    }

    private static String cleanAuthor(String author) {
        if (author == null) {
            return "";
        }
        String value = author;
        if (value.endsWith(" - Topic")) {
            value = value.substring(0, value.length() - " - Topic".length());
        }
        if (value.endsWith("VEVO")) {
            value = value.substring(0, value.length() - "VEVO".length());
        }
        return value.trim();
    }

    private static String cleanTitle(String title) {
        if (title == null) {
            return "";
        }
        String value = title;
        int cut = value.indexOf('|');
        if (cut > 12) {
            value = value.substring(0, cut);
        }
        value = value.replaceAll("\\((?i:official|lyrics?|audio|video|hd|hq)[^)]*\\)", " ");
        value = value.replaceAll("\\[(?i:official|lyrics?|audio|video|hd|hq)[^\\]]*\\]", " ");
        value = value.replaceAll("\\s+", " ").trim();
        if (value.length() > 70) {
            value = value.substring(0, 70).trim();
        }
        return value;
    }

    // ------------------------------------------------------------------ search

    /** Calls YouTube's own search endpoint and returns the video ids it found. */
    private static List<String> search(String query, String seedId) {
        Set<String> ids = new LinkedHashSet<String>();
        try {
            String key = null;
            String version = null;
            try {
                String page = get("https://m.youtube.com/watch?v=" + seedId);
                key = match(page, API_KEY);
                version = match(page, CLIENT_VERSION);
            } catch (Exception e) {
                Log.w(TAG, "search page: " + e);
            }
            if (key == null || version == null) {
                return new ArrayList<String>(ids);
            }

            String body = "{\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\""
                    + version + "\",\"hl\":\"en\",\"gl\":\"US\"}},\"query\":\""
                    + jsonEscape(query) + "\"}";
            String response = post("https://www.youtube.com/youtubei/v1/search?key="
                    + URLEncoder.encode(key, "UTF-8") + "&prettyPrint=false", body);
            Matcher matcher = VIDEO_ID.matcher(response);
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
            Log.i(TAG, "search \"" + query + "\" -> " + ids.size() + " ids");
        } catch (Exception e) {
            Log.w(TAG, "search failed: " + e);
        }
        return new ArrayList<String>(ids);
    }

    /** Fetches title, channel and length of one video. Blocking (network!). */
    private static Track fetch(String id) {
        String title = "";
        String author = "";
        int duration = 0;

        try {
            String page = get("https://www.youtube.com/watch?v=" + id);
            title = unescape(match(page, OG_TITLE));
            String seconds = match(page, LENGTH);
            if (seconds != null) {
                duration = Integer.parseInt(seconds);
            }
        } catch (Exception e) {
            Log.w(TAG, "watch page " + id + ": " + e);
        }

        try {
            String json = get("https://www.youtube.com/oembed?format=json&url="
                    + URLEncoder.encode("https://www.youtube.com/watch?v=" + id, "UTF-8"));
            JSONObject object = new JSONObject(json);
            if (title.isEmpty()) {
                title = object.optString("title");
            }
            author = object.optString("author_name");
        } catch (Exception e) {
            Log.w(TAG, "oembed " + id + ": " + e);
        }

        if (title == null || title.trim().isEmpty()) {
            title = id;
        }
        return new Track(id, "https://www.youtube.com/watch?v=" + id, title, author, duration);
    }

    // ------------------------------------------------------------------ http

    private static String get(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("User-Agent", UA);
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        try {
            return read(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static String post(String address, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("User-Agent", UA);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        try {
            OutputStream out = connection.getOutputStream();
            out.write(body.getBytes("UTF-8"));
            out.flush();
            out.close();
            return read(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream input) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, "UTF-8"), 16384);
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            sb.append(buffer, 0, read);
            if (sb.length() > 4_000_000) {
                break;
            }
        }
        reader.close();
        return sb.toString();
    }

    private static String match(String input, Pattern pattern) {
        if (input == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String unescape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c < 0x20) {
                sb.append(String.format(Locale.US, "\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Fills in title/author/length for a link the user added. Blocking (network!). */
    public static Track fetchMetadata(String url) {
        String id = Urls.videoId(url);
        String title = "";
        String author = "";
        int duration = 0;
        try {
            String page = get("https://www.youtube.com/watch?v=" + id);
            title = unescape(match(page, OG_TITLE));
            String seconds = match(page, LENGTH);
            if (seconds != null) {
                duration = Integer.parseInt(seconds);
            }
        } catch (Exception e) {
            Log.w(TAG, "metadata page: " + e);
        }
        try {
            String json = get("https://www.youtube.com/oembed?format=json&url="
                    + URLEncoder.encode(url, "UTF-8"));
            JSONObject object = new JSONObject(json);
            if (title.isEmpty()) {
                title = object.optString("title");
            }
            author = object.optString("author_name");
        } catch (Exception e) {
            Log.w(TAG, "metadata oembed: " + e);
        }
        return new Track(id, url, title, author, duration);
    }
}
