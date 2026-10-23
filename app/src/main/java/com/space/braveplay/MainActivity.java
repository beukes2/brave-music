package com.space.braveplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Brave music launcher.
 *
 * <p>Big "MUSIC ON / MUSIC OFF" button at the top. While ON the app plays one song after
 * another, never repeating a song within an hour, keeps the volume audible and Bluetooth on,
 * and checks every 20 s that the music is still running. When every link has been played it
 * finds a new song of the same genre.</p>
 *
 * <p>While OFF it only opens links that you tap - nothing is started or forced.</p>
 *
 * <p>Also drivable from adb:</p>
 * <pre>
 *   adb shell am start -n com.space.braveplay/.MainActivity --ei index 3 --ez music true
 *   adb shell am start -n com.space.braveplay/.MainActivity -e url "https://..."
 * </pre>
 */
public class MainActivity extends Activity {

    private static final int REQ_BLUETOOTH = 41;

    private TextView status;
    private Button musicButton;
    private ListView list;
    private TrackAdapter adapter;
    private List<Track> tracks = new ArrayList<Track>();

    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener libraryListener =
            new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(android.content.SharedPreferences sp,
                                                      String key) {
                    if (Library.KEY_URL.equals(key) || Library.KEY_MUSIC_ON.equals(key)) {
                        refreshList();
                        refreshUi();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#101014"));

        TextView header = new TextView(this);
        header.setText("Brave Music");
        header.setTextColor(Color.WHITE);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        header.setPadding(dp(20), dp(18), dp(20), dp(0));
        root.addView(header);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(Color.parseColor("#9AA0A6"));
        status.setPadding(dp(20), dp(4), dp(20), dp(10));
        root.addView(status);

        musicButton = new Button(this);
        musicButton.setAllCaps(false);
        musicButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        musicButton.setTypeface(null, Typeface.BOLD);
        musicButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMusicMode(!isMusicOn());
            }
        });
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96));
        buttonParams.setMargins(dp(12), 0, dp(12), dp(10));
        root.addView(musicButton, buttonParams);

        list = new ListView(this);
        list.setDivider(null);
        list.setPadding(dp(12), 0, dp(12), dp(8));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(dp(12), dp(4), dp(12), dp(16));

        Button addButton = new Button(this);
        addButton.setText("Add link");
        addButton.setAllCaps(false);
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddDialog();
            }
        });
        footer.addView(addButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button settingsButton = new Button(this);
        settingsButton.setText("Auto-play settings");
        settingsButton.setAllCaps(false);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "No accessibility settings found",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        footer.addView(settingsButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(footer);
        setContentView(root);

        adapter = new TrackAdapter();
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                selectTrack(position);
            }
        });

        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < Library.builtinCount()) {
                    return false;
                }
                confirmRemove(position);
                return true;
            }
        });

        // Only react to launch extras on a genuinely new launch. When Android recreates the
        // activity after a process death it replays the old intent (including --ez music),
        // and acting on that would silently switch music mode on again.
        if (savedInstanceState == null) {
            handleIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Library.prefs(this).registerOnSharedPreferenceChangeListener(libraryListener);
        refreshList();
        refreshUi();
        requestBluetoothPermission();
    }

    @Override
    protected void onPause() {
        Library.prefs(this).unregisterOnSharedPreferenceChangeListener(libraryListener);
        super.onPause();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        boolean hasMusic = intent.hasExtra("music");
        if (hasMusic) {
            Library.prefs(this).edit()
                    .putBoolean(Library.KEY_MUSIC_ON, intent.getBooleanExtra("music", false))
                    .apply();
        }

        String url = intent.getStringExtra("url");
        if (!TextUtils.isEmpty(url)) {
            String normalized = normalize(url);
            Track track = Library.findByUrl(this, normalized);
            if (track == null) {
                track = new Track(Urls.videoId(normalized), normalized, "", "", 0);
                Library.add(this, track);
            }
            Radio.play(this, track);
            List<Track> added = new ArrayList<Track>();
            added.add(track);
            fetchTitles(added);
        } else {
            int index = intent.getIntExtra("index", 0);
            if (index >= 1) {
                selectTrack(index - 1);
            }
        }
        // Extra handling is one-shot: don't act on these again after a recreation.
        intent.removeExtra("music");
        intent.removeExtra("index");
        intent.removeExtra("url");
        refreshList();
        refreshUi();
    }

    /** User picked this song on purpose: play it even if it was played recently. */
    private void selectTrack(int position) {
        if (position < 0 || position >= tracks.size()) {
            return;
        }
        Radio.play(this, tracks.get(position));
        refreshUi();
    }

    private void setMusicMode(boolean on) {
        Library.prefs(this).edit().putBoolean(Library.KEY_MUSIC_ON, on).apply();
        if (!on) {
            refreshUi();
            Toast.makeText(this, "Music off", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean alreadyPlaying = isMusicActive() && !TextUtils.isEmpty(
                Library.prefs(this).getString(Library.KEY_URL, ""));
        if (alreadyPlaying) {
            refreshUi();
            Toast.makeText(this, "Music on - keeping this song", Toast.LENGTH_SHORT).show();
            return;
        }

        status.setText("Music ON - picking a song...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Track next = Radio.startFresh(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (next == null) {
                            Toast.makeText(MainActivity.this,
                                    "Could not find anything to play", Toast.LENGTH_LONG).show();
                        } else {
                            Radio.play(MainActivity.this, next);
                        }
                        refreshUi();
                    }
                });
            }
        }, "start-music").start();
    }

    private void refreshList() {
        tracks = Library.all(this);
        adapter.notifyDataSetChanged();
    }

    private void refreshUi() {
        boolean music = isMusicOn();
        musicButton.setText(music
                ? "\u25B6  MUSIC ON\ntap to stop"
                : "\u25A0  MUSIC OFF\ntap to play");
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(music ? "#16A34A" : "#24242C"));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(2), Color.parseColor(music ? "#22C55E" : "#3A3A46"));
        musicButton.setBackground(bg);
        musicButton.setTextColor(music ? Color.WHITE : Color.parseColor("#E8EAED"));

        boolean helper = PlayClickerService.isEnabled(this);
        if (!helper) {
            status.setText("Auto-play helper off \u2013 enable \"Brave auto-play\" in Accessibility settings.");
            status.setTextColor(Color.parseColor("#FFB4A2"));
            return;
        }
        status.setTextColor(Color.parseColor("#9AA0A6"));
        if (!music) {
            status.setText("Music OFF \u2013 links open in Brave, nothing is played automatically.");
            return;
        }
        String now = nowPlaying();
        status.setText(now == null
                ? "Music ON \u2013 starting..."
                : "Music ON \u00B7 now: " + now
                + "\nNo repeats for 60 min \u00B7 volume kept up \u00B7 checked every 20 s.");
    }

    private String nowPlaying() {
        Track track = Library.current(this);
        return track == null ? null : track.displayTitle();
    }

    private boolean isMusicOn() {
        return Library.prefs(this).getBoolean(Library.KEY_MUSIC_ON, false);
    }

    private boolean isMusicActive() {
        try {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            return audio != null && audio.isMusicActive();
        } catch (Exception e) {
            return false;
        }
    }

    private void showAddDialog() {
        final EditText input = new EditText(this);
        input.setHint("https://www.youtube.com/watch?v=...");
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setTextColor(Color.BLACK);
        input.setHintTextColor(Color.parseColor("#888888"));
        int pad = dp(16);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle("Add a link")
                .setView(input)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        List<Track> added = new ArrayList<Track>();
                        // Accepts one link or several pasted links (separated by spaces/newlines).
                        for (String part : input.getText().toString().split("\\s+")) {
                            String url = normalize(part);
                            if (url == null) {
                                continue;
                            }
                            Track track = Library.findByUrl(MainActivity.this, url);
                            if (track == null) {
                                track = new Track(Urls.videoId(url), url, "", "", 0);
                                Library.add(MainActivity.this, track);
                            }
                            added.add(track);
                        }
                        if (added.isEmpty()) {
                            Toast.makeText(MainActivity.this, "No link entered",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        refreshList();
                        Toast.makeText(MainActivity.this,
                                added.size() == 1 ? "Added - looking up its title..."
                                        : added.size() + " links added",
                                Toast.LENGTH_SHORT).show();
                        fetchTitles(added);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Looks up real song titles for freshly added links and refreshes the list. */
    private void fetchTitles(final List<Track> added) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (Track track : added) {
                    Track full = Discover.fetchMetadata(track.url);
                    if (!TextUtils.isEmpty(full.title)) {
                        Library.updateMeta(MainActivity.this, full);
                        // Keep the running player in sync with the real length.
                        String current = Library.prefs(MainActivity.this)
                                .getString(Library.KEY_URL, "");
                        if (track.url.equals(current) && full.durationSec > 0) {
                            Library.prefs(MainActivity.this).edit()
                                    .putInt(Library.KEY_DURATION, full.durationSec)
                                    .putString(Library.KEY_TITLE, full.title)
                                    .apply();
                        }
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshList();
                    }
                });
            }
        }, "fetch-titles").start();
    }

    private void confirmRemove(final int position) {
        new AlertDialog.Builder(this)
                .setTitle("Remove this link?")
                .setMessage(tracks.get(position).displayTitle())
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Library.removeAt(MainActivity.this, position);
                        refreshList();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String url = raw.trim();
        if (url.isEmpty()) {
            return null;
        }
        if (!url.matches("(?i)^https?://.*")) {
            url = "https://" + url;
        }
        return url;
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT},
                    REQ_BLUETOOTH);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Two-line rows: song title on top, channel + length below. */
    private final class TrackAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return tracks.size();
        }

        @Override
        public Object getItem(int position) {
            return tracks.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView number;
            TextView title;
            TextView subtitle;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                number = (TextView) row.getChildAt(0);
                LinearLayout column = (LinearLayout) row.getChildAt(1);
                title = (TextView) column.getChildAt(0);
                subtitle = (TextView) column.getChildAt(1);
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(14), dp(12), dp(14), dp(12));
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);

                number = new TextView(MainActivity.this);
                number.setTextColor(Color.parseColor("#6B7280"));
                number.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                number.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
                row.addView(number, new LinearLayout.LayoutParams(dp(34),
                        ViewGroup.LayoutParams.MATCH_PARENT));

                LinearLayout column = new LinearLayout(MainActivity.this);
                column.setOrientation(LinearLayout.VERTICAL);
                row.addView(column, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                title = new TextView(MainActivity.this);
                title.setTextColor(Color.WHITE);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                title.setMaxLines(2);
                title.setEllipsize(TextUtils.TruncateAt.END);
                column.addView(title);

                subtitle = new TextView(MainActivity.this);
                subtitle.setTextColor(Color.parseColor("#8B93A1"));
                subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                subtitle.setMaxLines(1);
                subtitle.setEllipsize(TextUtils.TruncateAt.END);
                subtitle.setPadding(0, dp(3), 0, 0);
                column.addView(subtitle);
            }

            Track track = tracks.get(position);
            number.setText(String.valueOf(position + 1));
            title.setText(track.displayTitle());
            subtitle.setText(track.displayAuthor()
                    + (track.durationSec > 0 ? "  \u00B7  " + duration(track.durationSec) : "  \u00B7  live"));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor(position % 2 == 0 ? "#1B1B22" : "#17171D"));
            bg.setCornerRadius(dp(10));
            bg.setStroke(dp(1), Color.parseColor("#2A2A34"));
            row.setBackground(bg);

            boolean current = isCurrent(track);
            title.setTextColor(current ? Color.parseColor("#4ADE80") : Color.WHITE);
            return row;
        }

        private boolean isCurrent(Track track) {
            String url = Library.prefs(MainActivity.this).getString(Library.KEY_URL, "");
            return track.url.equals(url);
        }

        private String duration(int seconds) {
            int minutes = seconds / 60;
            int rest = seconds % 60;
            return minutes + ":" + (rest < 10 ? "0" : "") + rest;
        }
    }
}
