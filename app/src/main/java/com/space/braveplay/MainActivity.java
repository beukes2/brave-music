package com.space.braveplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
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
    private static final int REQ_PROJECTION = 42;
    private static final long UNLOCK_HOLD_MS = 2000L;
    private static final String STATE_LOCKED = "screen_locked";

    private TextView status;
    private Button musicButton;
    private Button saveButton;
    private Button lockButton;
    private ListView list;
    private TrackAdapter adapter;
    private List<Track> tracks = new ArrayList<Track>();
    private final List<View> lockableViews = new ArrayList<View>();
    private boolean projectionRequested;
    private long lastProjectionRequestAt;

    /**
     * Screen lock. All controls except the lock button are disabled so a sleeve, rain drop or
     * handlebar bump cannot stop the music or skip a song. Music and the Bluetooth media
     * buttons are not touched at all; they live in the background services.
     */
    private boolean locked;
    private boolean holdingToUnlock;
    private boolean unlockHoldCompleted;

    private final Runnable unlockHoldRunnable = new Runnable() {
        @Override
        public void run() {
            if (!locked || !holdingToUnlock) {
                return;
            }
            unlockHoldCompleted = true;
            lockButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            setLocked(false);
        }
    };

    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener libraryListener =
            new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(android.content.SharedPreferences sp,
                                                      String key) {
                    if (Library.KEY_URL.equals(key) || Library.KEY_MUSIC_ON.equals(key)
                            || Library.KEY_CUSTOM.equals(key)) {
                        refreshList();
                        refreshUi();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logs.init(this);

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

        lockButton = new Button(this);
        lockButton.setAllCaps(false);
        lockButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        lockButton.setTypeface(null, Typeface.BOLD);
        lockButton.setLongClickable(false);
        lockButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (locked) {
                    showUnlockHint();
                } else {
                    setLocked(true);
                }
            }
        });
        lockButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        if (locked) {
                            holdingToUnlock = true;
                            unlockHoldCompleted = false;
                            lockButton.setText("\uD83D\uDD12  KEEP HOLDING\n2 s to unlock");
                            lockButton.postDelayed(unlockHoldRunnable, UNLOCK_HOLD_MS);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        if (holdingToUnlock) {
                            lockButton.removeCallbacks(unlockHoldRunnable);
                            boolean completed = unlockHoldCompleted;
                            holdingToUnlock = false;
                            unlockHoldCompleted = false;
                            if (!completed) {
                                refreshLockUi();
                                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                                    showUnlockHint();
                                }
                            }
                            return true;
                        }
                        if (!locked && event.getActionMasked() == MotionEvent.ACTION_UP) {
                            setLocked(true);
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        LinearLayout.LayoutParams lockParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        lockParams.setMargins(dp(12), 0, dp(12), dp(8));
        root.addView(lockButton, lockParams);

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
        settingsButton.setText("Settings");
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

        Button logsButton = new Button(this);
        logsButton.setText("Logs");
        logsButton.setAllCaps(false);
        logsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogs();
            }
        });
        footer.addView(logsButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        saveButton = new Button(this);
        saveButton.setText("Save");
        saveButton.setAllCaps(false);
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSaving();
            }
        });
        footer.addView(saveButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(footer);
        lockableViews.add(musicButton);
        lockableViews.add(list);
        lockableViews.add(addButton);
        lockableViews.add(settingsButton);
        lockableViews.add(logsButton);
        lockableViews.add(saveButton);
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
        } else {
            locked = savedInstanceState.getBoolean(STATE_LOCKED, false);
        }
        refreshLockUi();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_LOCKED, locked);
    }

    @Override
    public void onBackPressed() {
        if (locked) {
            showUnlockHint();
            return;
        }
        super.onBackPressed();
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
        Library.removeLocalOnlyMissing(this);
        refreshList();
        refreshUi();
        requestBluetoothPermission();
        // Capture is always on (independent of MUSIC ON). Android forgets the consent when
        // the process dies, so ask again whenever the app comes back to the foreground.
        maybeRequestSaving();
    }

    @Override
    protected void onPause() {
        cancelUnlockHold();
        Library.prefs(this).unregisterOnSharedPreferenceChangeListener(libraryListener);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (lockButton != null) {
            lockButton.removeCallbacks(unlockHoldRunnable);
        }
        super.onDestroy();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        Logs.i("BravePlayUI", "intent music=" + intent.hasExtra("music")
                + " index=" + intent.getIntExtra("index", 0)
                + " url=" + intent.getStringExtra("url")
                + " record=" + intent.hasExtra("record"));
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
        if (intent.getBooleanExtra("record", false)) {
            status.post(new Runnable() {
                @Override
                public void run() {
                    toggleSaving();
                }
            });
        }
        // Extra handling is one-shot: don't act on these again after a recreation.
        intent.removeExtra("music");
        intent.removeExtra("index");
        intent.removeExtra("url");
        intent.removeExtra("record");
        refreshList();
        refreshUi();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) {
            return;
        }
        projectionRequested = false;
        if (resultCode == RESULT_OK && data != null) {
            RecorderService.start(this, resultCode, data);
            Toast.makeText(this, "Capture on - every video you play in Brave is saved",
                    Toast.LENGTH_SHORT).show();
            Logs.i("BravePlayUI", "always-on capture enabled");
            status.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshUi();
                }
            }, 800L);
        } else {
            // Keep the preference true: the permission is only temporarily missing, and the
            // app will ask again instead of silently deciding that saving is off.
            Toast.makeText(this, "Capture paused - tap Save to allow saving",
                    Toast.LENGTH_LONG).show();
            Logs.w("BravePlayUI", "capture consent denied; will retry");
        }
        refreshUi();
    }

    /**
     * Save is an always-on status/consent control. Android requires a fresh capture consent
     * for each app process, so tapping it (or opening the app) only asks for that permission;
     * it never turns capture off.
     */
    private void toggleSaving() {
        if (locked) {
            showUnlockHint();
            return;
        }
        if (RecorderService.isRunning()) {
            Toast.makeText(this, "Capture is always on while Brave Music is allowed",
                    Toast.LENGTH_SHORT).show();
            refreshUi();
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        if (RecorderService.isRunning() || projectionRequested) {
            return;
        }
        try {
            MediaProjectionManager manager = (MediaProjectionManager)
                    getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                Toast.makeText(this, "Screen capture is not available",
                        Toast.LENGTH_LONG).show();
                return;
            }
            projectionRequested = true;
            lastProjectionRequestAt = System.currentTimeMillis();
            startActivityForResult(manager.createScreenCaptureIntent(), REQ_PROJECTION);
        } catch (Throwable t) {
            projectionRequested = false;
            Logs.w("BravePlayUI", "projection request: " + t);
            Toast.makeText(this, "Could not ask for capture permission",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Capture is always wanted. Android drops the capture token when the app process dies or
     * a reboot happens, so ask for it again whenever the app is open, regardless of MUSIC ON.
     */
    private void maybeRequestSaving() {
        if (RecorderService.isRunning() || projectionRequested || locked) {
            return;
        }
        if (System.currentTimeMillis() - lastProjectionRequestAt < 10 * 60_000L) {
            return;
        }
        status.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !isDestroyed() && !RecorderService.isRunning()) {
                    requestProjection();
                }
            }
        }, 700L);
    }

    private String savedSummary() {
        int count = LocalStore.count(this);
        if (count == 0) {
            return "No songs or videos saved locally yet";
        }
        long mb = LocalStore.totalBytes(this) / (1024 * 1024);
        return count + " item" + (count == 1 ? "" : "s") + " saved locally (" + mb + " MB)";
    }

    /** User picked this song on purpose: play it even if it was played recently. */
    private void selectTrack(int position) {
        if (locked) {
            showUnlockHint();
            return;
        }
        if (position < 0 || position >= tracks.size()) {
            return;
        }
        Track track = tracks.get(position);
        Logs.i("BravePlayUI", "user picked #" + (position + 1) + " \"" + track.displayTitle() + "\"");
        Radio.play(this, track);
        refreshUi();
    }

    private void setMusicMode(boolean on) {
        if (locked) {
            showUnlockHint();
            return;
        }
        Library.prefs(this).edit().putBoolean(Library.KEY_MUSIC_ON, on).apply();
        Logs.i("BravePlayUI", "music mode " + (on ? "ON" : "OFF"));
        if (!on) {
            KeepAliveService.stop(this);
            // MUSIC OFF stops the radio/local player only; capture keeps watching Brave.
            LocalPlayer.stop();
            refreshUi();
            Toast.makeText(this, "Music off - capture is still on", Toast.LENGTH_SHORT).show();
            return;
        }
        KeepAliveService.start(this);

        // A saved copy is the most reliable way to keep playing: start it directly.
        Track current = Library.current(this);
        if (current != null && LocalStore.has(this, current.id)) {
            Radio.play(this, current);
            refreshUi();
            Toast.makeText(this, "Music on - playing saved copy", Toast.LENGTH_SHORT).show();
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

    private void showUnlockHint() {
        Toast.makeText(this,
                "Screen locked - hold the lock button for 2 seconds to unlock",
                Toast.LENGTH_SHORT).show();
    }

    private void setLocked(boolean value) {
        if (locked == value) {
            return;
        }
        locked = value;
        refreshLockUi();
        if (value) {
            lockButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        Logs.i("BravePlayUI", value ? "screen locked" : "screen unlocked");
        Toast.makeText(this, value
                        ? "Locked - music keeps playing; Bluetooth buttons still work"
                        : "Unlocked",
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Disables every interactive view except the lock button, and keeps the screen awake
     * while locked. The radio, local player and Bluetooth media session are not affected.
     */
    private void refreshLockUi() {
        if (lockButton == null) {
            return;
        }
        lockButton.setText(locked
                ? "\uD83D\uDD12  LOCKED\nhold 2 s to unlock"
                : "\uD83D\uDD13  LOCK\n(tap to lock)");
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(locked ? "#B45309" : "#24242C"));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(2), Color.parseColor(locked ? "#F59E0B" : "#3A3A46"));
        lockButton.setBackground(bg);
        lockButton.setTextColor(locked ? Color.WHITE : Color.parseColor("#E8EAED"));
        lockButton.setEnabled(true);

        for (View view : lockableViews) {
            view.setEnabled(!locked);
            view.setAlpha(locked ? 0.35f : 1f);
        }

        if (locked) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** A hold that was not completed (for example the user pressed Home) must not stick. */
    private void cancelUnlockHold() {
        if (lockButton != null) {
            lockButton.removeCallbacks(unlockHoldRunnable);
        }
        if (holdingToUnlock) {
            holdingToUnlock = false;
            unlockHoldCompleted = false;
            refreshLockUi();
        }
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

        boolean saving = RecorderService.isRunning();
        if (saveButton != null) {
            saveButton.setText(saving ? "Save: on" : "Save: paused");
        }
        String capture = saving
                ? "Capture on: every video you play in Brave is saved locally."
                : "Capture paused: tap Save so Android can allow saving.";

        boolean helper = PlayClickerService.isEnabled(this);
        if (!helper) {
            status.setText("Auto-play helper off \u2013 enable \"Brave auto-play\" in Accessibility settings."
                    + "\n" + capture
                    + "\n" + savedSummary());
            status.setTextColor(Color.parseColor("#FFB4A2"));
            return;
        }
        status.setTextColor(Color.parseColor("#9AA0A6"));
        if (!music) {
            status.setText("Music OFF \u2013 links open in Brave, nothing is played automatically."
                    + "\n" + capture
                    + "\n" + savedSummary());
            return;
        }
        String now = nowPlaying();
        status.setText(now == null
                ? "Music ON \u2013 starting..."
                : "Music ON \u00B7 now: " + now
                + "\n" + capture
                + "\n" + savedSummary()
                + "\nNo repeats for 60 min \u00B7 volume kept up \u00B7 checked every 5 s.");
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
        if (locked) {
            showUnlockHint();
            return;
        }
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
                        Logs.i("BravePlayUI", "added " + added.size() + " link(s)");
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

    private void showLogs() {
        if (locked) {
            showUnlockHint();
            return;
        }
        final String text = Logs.tail(this, 400);
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextColor(Color.parseColor("#E8EAED"));
        view.setBackgroundColor(Color.parseColor("#101014"));
        int pad = dp(12);
        view.setPadding(pad, pad, pad, pad);
        view.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);

        new AlertDialog.Builder(this)
                .setTitle("Log - last 400 lines")
                .setView(scroll)
                .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            ClipboardManager clipboard =
                                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            clipboard.setPrimaryClip(
                                    ClipData.newPlainText("Brave Music log", text));
                            Toast.makeText(MainActivity.this, "Log copied",
                                    Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "Copy failed",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void confirmRemove(final int position) {
        if (locked) {
            showUnlockHint();
            return;
        }
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
        List<String> wanted = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(android.Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(android.Manifest.permission.RECORD_AUDIO);
        }
        if (!wanted.isEmpty()) {
            requestPermissions(wanted.toArray(new String[0]), REQ_BLUETOOTH);
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
            String saved = LocalStore.has(MainActivity.this, track.id)
                    ? "  \u00B7  saved" : "";
            subtitle.setText(track.displayAuthor()
                    + (track.durationSec > 0 ? "  \u00B7  " + duration(track.durationSec)
                    : "  \u00B7  live")
                    + saved);

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
