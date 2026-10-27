# Brave Music

Small Android app that keeps YouTube music playing in **Brave** on the phone, one song
after another, without repeats. It also **saves every song/video played in Brave** –
whether the radio started it or you opened it yourself – and plays saved copies locally,
so the music keeps going with the screen off and the phone locked – no browser needed.
A **screen LOCK** ignores accidental touches while the phone is mounted or in a pocket.

## Saved music and videos (offline copies)

Capture is **always on** once the one-time consent is granted, independent of the big
MUSIC ON/OFF button. The app watches Brave's media session and records the audio into its
private storage, one `.m4a` file per video (`files/music/<video-id>.m4a`). Radio songs
keep their video id; a video that is not in the list gets a stable `auto-<hash>` id and a
`local:` URL, and is added to the list automatically when the recording is saved. Saved
rows say **saved**; the next time the song is chosen, the saved copy is played instead of
opening Brave.

- Android requires a **screen/audio capture consent** for this. The app asks for it
  automatically whenever it comes to the foreground, regardless of MUSIC ON/OFF (and
  again after the app process is restarted or the phone reboots). Tap **Start now /
  Allow**. The **Save** button at the bottom is an always-on status/consent control:
  `Save: on`, or `Save: paused` when Android needs the tap again. Tapping it while it is
  already on does **not** stop capture; denying the dialog keeps saving enabled and the
  app retries later.
- Only complete copies are kept. When Brave publishes the length, at least **70 %** of
  the video must have been watched; otherwise the file is discarded. For an unknown
  length the minimum is **30 s** and the file is closed on a pause, session change or
  long idle. A recording that starts more than **20 s** into a video is treated as a
  fragment and discarded. A looping page is closed at the end of the first pass instead
  of saving repeated audio. The built-in endless lofi stream is skipped; ordinary
  unknown-length videos are still saved. The first video after granting consent is
  usually discarded if it was already half over.
- Saved music/videos play through the phone's normal media output (speaker, Bluetooth,
  car), with a wake lock, and need neither Brave nor the accessibility service. That is
  why they keep playing when the screen is off and the phone is locked. A saved
  `local:` row never opens Brave, even if its file is missing – it is skipped instead.
- Files are capped at **2 GB total**; the oldest are deleted first, and a deleted
  `local:` video is removed from the list too. Nothing is uploaded.
- Saved songs still obey the one-hour no-repeat rule and still show on the car display.

If Android ever says the capture token is stale (for example after a reboot), open the
app and accept the prompt again. The app cannot show that system dialog from the
background.

## The big toggle

- **MUSIC ON** (big green button) – plays the playlist in Brave, tapping
  **“Tap to unmute” / “Play”** as soon as the player appears. While it is on the app:
  - plays one song after another and **never picks the same song twice within an hour**
    (tapping a row yourself always plays it, even if it was played recently),
  - when every link has been played, **finds another song of the same genre** on YouTube,
    adds it to the list and plays it,
  - plays saved copies instead of Brave. Recording is **not** tied to MUSIC ON: the
    capture service saves anything you play in Brave in the background (see above),
  - keeps the **media volume above 0** (checked every 5 s and whenever the volume
    changes, plus the media stream is unmuted) – turning the volume to zero is undone,
  - keeps **Bluetooth on** (Android 13+ blocks apps from enabling it directly, so the app
    falls back to flipping the Bluetooth quick-settings tile),
  - **checks every 5 seconds** that music is still playing (logged as a heartbeat every
    20 s); after **8 seconds of real silence** it reloads the page or reopens the current
    video in Brave until the stream is back. A freshly opened page gets a longer 30 s
    grace so a slow load is not restarted over and over,
  - **steering-wheel / headset buttons work**: next and previous drive the app's own
    rotation, play/pause is forwarded to Brave or the local player. The song title also
    shows on the car display. (Android sends media buttons to the app that is producing
    audio, so the app keeps an inaudible track running while MUSIC ON; it never requests
    audio focus and never makes a sound. This needs the one-time *notification access*
    that `install.ps1` grants; without it the buttons stay with Brave.),
  - holds a partial wake lock and both apps are battery-whitelisted by `install.ps1`.
- **MUSIC OFF** (big grey button) – the radio/keeper does nothing: it only opens links
  you tap (no auto-play taps, no volume changes, no Bluetooth changes, no rotation), and
  a locally playing song is stopped. **Capture stays on** and videos you play in Brave
  are still saved.

## Screen lock for cycling

The **LOCK** button below the status line is a touch guard for the phone mount. Tap it
once and all other controls on the app are disabled (MUSIC ON/OFF, every row in the song
list, Add link, Settings, Logs and Save). The button turns amber and reads **LOCKED**.
Touching anywhere else on the screen does nothing: it cannot stop the music, skip a song,
scroll the list or open a dialog. The system Back gesture is ignored as well.

- **Unlock:** press and hold the same button for **2 seconds** (it shows “hold 2 s to
  unlock” while held). A short tap just shows a reminder; it does not unlock.
- The screen is kept awake while locked, so the app stays visible on the mount. Locking
  does not touch the player, the radio or the Bluetooth media session; next/previous and
  play/pause from the car, headset or steering wheel keep working normally. A media button
  that picks an unsaved song may still open Brave, exactly as it normally does.
- Capture keeps running while locked. The lock survives a rotation/activity rebuild; a
  fresh app start begins unlocked.

The app cannot block the phone's power button or system navigation, only touches inside
Brave Music.


The list shows the real **song titles** (and channel + length). Built-in titles are stored
in the app; titles of links you add are looked up automatically.

Retries back off from 20 s up to 90 seconds so a dead link cannot spam new tabs. Live
streams (no length) are never cut off by the rotation.

## Use it

- On the phone: open **Brave Music**, tap the big **MUSIC ON** button. Tap a row to play
  that song immediately; the list turns the current song green.
- Tap **LOCK** under the status line to ignore accidental screen touches while cycling;
  hold it for 2 seconds to unlock. See [Screen lock for cycling](#screen-lock-for-cycling).
- If Android asks for the *Nearby devices* permission on first launch, allow it – that is
  the Bluetooth access used to keep Bluetooth on. `install.ps1` grants it over adb too.
- **Add link** (bottom-left) stores extra links without rebuilding; long-press an added
  row to delete it. Several links can be pasted at once (separated by spaces/newlines).
- **Save** (bottom-right, next to Logs) is an always-on status/consent control. It shows
  `Save: on` while capture is running and `Save: paused` when Android needs the consent
  tap again; tapping a paused button asks for it. Tapping `Save: on` only shows a
  reminder – it does not stop capture. The consent prompt appears by itself whenever the
  app comes to the foreground, even with MUSIC OFF. Videos you open in Brave yourself are
  saved too, not just the radio's songs.
- **Logs** (next to Save) shows the last 400 lines of the app log and can copy them.
  The same log lives in `files/app.log` (rolling, 1 MB):

  ```powershell
  adb shell run-as com.space.braveplay cat files/app.log
  ```

  Every tick, track change, media button, tap, reload, volume/Bluetooth action, recording
  and error is written there, so a problem can be investigated long after it happened.
- Saved files live in the app's private storage. List, copy or remove them with:

  ```powershell
  adb shell run-as com.space.braveplay ls -l files/music
  adb exec-out run-as com.space.braveplay cat files/music/<video-id>.m4a > song.m4a
  adb shell run-as com.space.braveplay rm files/music/<video-id>.m4a
  ```

- From the PC:

  ```powershell
  .\play.ps1 -Index 3       # opens #3 and turns music mode on
  ```

  or directly:

  ```powershell
  adb shell am start -n com.space.braveplay/.MainActivity --ei index 3 --ez music true
  adb shell am start -n com.space.braveplay/.MainActivity --ez music false
  adb shell am start -n com.space.braveplay/.MainActivity -e url "https://www.youtube.com/watch?v=..."
  ```

## Edit the built-in list

`app/src/main/java/com/space/braveplay/Urls.java` – add/remove entries in the `URLS`
array, then run `.\install.ps1`. Titles, channels and lengths for the built-ins live in
the `META` table in `Library.java` (an unknown id simply shows its video id). Links added
in the app itself are kept in shared preferences and need no rebuild.

## Build and install

```powershell
.\install.ps1
```

Builds the debug APK, installs it, enables the accessibility service via adb and opens
the app. Needs the Android SDK at `%LOCALAPPDATA%\Android\Sdk`, JDK 17 and a connected
phone with USB debugging on.

It also grants **notification access** to the app. That permission is only used to read
Brave's playback state and to forward play/pause back to Brave when you press the car's
buttons – no notification content is read or stored. It grants **microphone**
(`RECORD_AUDIO`) for Android's playback-capture API; the app only captures Brave's own
audio while the capture consent is active, never the microphone itself.

## How it works

- `MainActivity` – big toggle, song list with real titles and **saved** markers, “Add
  link”, “Save”, logs, and the cycling **LOCK** guard (tap to lock, 2 s hold to unlock;
  disables every other control and keeps the screen awake while locked).
- `Library` – built-in list, links added by you, songs found automatically, and the
  one-hour play history (shared preferences `brave_play`).
- `Radio` – picks the next song: next entry that was not played in the last hour, else a
  newly discovered song, else the least recently played one. Plays the saved `.m4a` if
  there is one, otherwise opens the song in Brave. A `local:` row with no file is skipped
  rather than opened in Brave.
- `Discover` – asks YouTube for songs of the same artist/genre (using the song's author
  and title), checks their real title/length and stores the one that is picked.
- `PlayClickerService` – accessibility service that only watches Brave. It taps exactly
  these labels when they appear: `Tap to unmute`, `Unmute`, `Play video`, `Play`, plus
  cookie/consent buttons. Once playing, the control is labelled “Pause”, so it will not
  pause anything. It also times each song and starts the next one when it is over (or when
  YouTube's end screen with “Replay” appears). It owns a `MediaSession` for the car's
  next/previous/play/pause buttons and a silent keep-alive track so Android hands it the
  media keys. While a saved copy is playing it pauses Brave and forwards the buttons to
  the local player.
- `RecorderService` – foreground service (`mediaProjection`) that watches Brave's media
  session about once a second, independent of MUSIC ON. It follows title/artist/duration
  changes, starts a file 3 s after a video becomes audible, adopts a length that YouTube
  publishes a few seconds late, and closes the file on end, pause, loop, session loss or
  track change. It keeps a file only when at least 30 s **and** (for a known length) 70 %
  of the video was watched; starts later than 20 s, skipped fragments and the built-in
  live stream are discarded. Finished non-library videos are added to the list as
  `local:auto-…` rows. It keeps the newest 2 GB and deletes the oldest files first.
- `AacRecorder` – `AudioPlaybackCapture` filtered to Brave's UID, encoded to AAC-LC
  128 kbit/s stereo in an `.m4a` container.
- `LocalPlayer` / `LocalStore` – `MediaPlayer` with a partial wake lock. This path needs
  no browser, no accessibility and no unlocked screen. Local-only rows are removed when
  their files are pruned or missing, and are never opened in Brave.
- `KeepAliveService` – low-priority foreground service that keeps the process alive.
- The keeper checks every 5 s. While playing it reads Brave's own media session (speed 0
  means Chromium paused, even though it still says "playing"), so the silent keep-alive
  track cannot hide silence. If Brave is in front it hits the toolbar reload button (same
  tab); if not, it reopens the current URL in Brave. Retries back off from 20 s up to
  90 seconds so a dead link cannot spam new tabs. A pause pressed from the car is
  respected and is not fought by the silence keeper. It never taps “Play” while Brave's
  session says it is already playing.

If the accessibility service gets turned off (some OEM battery managers do that), links
still open in Brave but playback stays muted/paused and songs are not rotated – re-enable
**Settings → Accessibility → Brave auto-play**, or rerun `install.ps1`.

## Notes

- YouTube's mobile page can take 20–30 s to render on a cold start; startup scans cover
  that window, then the 5 s keeper takes over.
- A video that really has no audio (rare) can trigger reload/reopen attempts; the
  backoff keeps that from turning into a reload loop.
- Switching back to the app (or any other app) does not stop Brave; the music keeps
  playing in the background.

### Why it used to stop, and what changed

Two separate problems caused the long silences on 25–26 October:

1. The controller tapped **Play** on a page that was already playing. YouTube treated
   that as a new start, the live stream died, and no further recovery happened while the
   phone was locked. The controller now asks Brave's media session first and **never taps
   Play while it says playing**; on silence it tries the session's `play()` before any
   tap or reload.
2. With the **screen off and the phone locked**, Android gave the accessibility service no
   Brave window, so a dead stream could not be recovered for over an hour. Saved local
   copies avoid this completely: `MediaPlayer` needs no window and no unlock.

On Honor/Huawei phones, also set **Settings → Apps → App launch → Brave Music** *and*
**Brave** to **Manage manually**, with *Auto-launch*, *Secondary launch* and *Run in
background* enabled. Honor's PowerGenie can force-stop Brave even when battery
optimisation is off (it did so on 26 October). Smart Lock (**Settings → Security → Smart
Lock → Trusted devices → MY-CAR**) keeps the phone unlocked in the car; the app does not
disable the lock screen itself.

After a reboot, or if Android kills the app process, Android requires the capture consent
again. Open the app (MUSIC ON or OFF) and accept the prompt; the foreground services make
this rare, but Android does not allow an app to grant it from the background. This is the
one part of “always-on capture” Android will not let the app do without a tap.

