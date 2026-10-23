# Brave Music

Small Android app that keeps YouTube music playing in **Brave** on the phone, one song
after another, without repeats.

## The big toggle

- **MUSIC ON** (big green button) – plays the playlist in Brave, tapping
  **“Tap to unmute” / “Play”** as soon as the player appears. While it is on the app:
  - plays one song after another and **never picks the same song twice within an hour**
    (tapping a row yourself always plays it, even if it was played recently),
  - when every link has been played, **finds another song of the same genre** on YouTube,
    adds it to the list and plays it,
  - keeps the **media volume above 0** (checked every 5 s and whenever the volume
    changes, plus the media stream is unmuted) – turning the volume to zero is undone,
  - keeps **Bluetooth on** (Android 13+ blocks apps from enabling it directly, so the app
    falls back to flipping the Bluetooth quick-settings tile),
  - **checks every 5 seconds** that music is still playing (logged as a heartbeat every
    20 s); after **8 seconds of real silence** it reloads the page or reopens the current
    video in Brave until the stream is back. A freshly opened page gets a longer 30 s
    grace so a slow load is not restarted over and over,
  - **steering-wheel / headset buttons work**: next and previous drive the app's own
    rotation, play/pause is forwarded to Brave. The song title also shows on the car
    display. (Android sends media buttons to the app that is producing audio, so the app
    keeps an inaudible track running while MUSIC ON; it never requests audio focus and
    never makes a sound. This needs the one-time *notification access* that `install.ps1`
    grants; without it the buttons stay with Brave.),
  - holds a partial wake lock and both apps are battery-whitelisted by `install.ps1`.
- **MUSIC OFF** (big grey button) – the app only opens links you tap; the keeper does
  nothing at all (no taps, no volume changes, no Bluetooth changes, nothing started in
  the background).

The list shows the real **song titles** (and channel + length). Built-in titles are stored
in the app; titles of links you add are looked up automatically.

Retries back off from 20 s up to 90 seconds so a dead link cannot spam new tabs. Live
streams (no length) are never cut off by the rotation.

## Use it

- On the phone: open **Brave Music**, tap the big **MUSIC ON** button. Tap a row to play
  that song immediately; the list turns the current song green.
- If Android asks for the *Nearby devices* permission on first launch, allow it – that is
  the Bluetooth access used to keep Bluetooth on. `install.ps1` grants it over adb too.
- **Add link** (bottom-left) stores extra links without rebuilding; long-press an added
  row to delete it. Several links can be pasted at once (separated by spaces/newlines).
- **Logs** (bottom-right) shows the last 400 lines of the app log and can copy them.
  The same log lives in `files/app.log` (rolling, 512 KB):

  ```powershell
  adb shell run-as com.space.braveplay cat files/app.log
  ```

  Every tick, track change, media button, tap, reload, volume/Bluetooth action and error
  is written there, so a problem can be investigated long after it happened.
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
buttons – no notification content is read or stored.

## How it works

- `MainActivity` – big toggle, song list with real titles, “Add link”.
- `Library` – built-in list, links added by you, songs found automatically, and the
  one-hour play history (shared preferences `brave_play`).
- `Radio` – picks the next song: next entry that was not played in the last hour, else a
  newly discovered song, else the least recently played one. Opens it in Brave.
- `Discover` – asks YouTube for songs of the same artist/genre (using the song's author
  and title), checks their real title/length and stores the one that is picked.
- `PlayClickerService` – accessibility service that only watches Brave. It taps exactly
  these labels when they appear: `Tap to unmute`, `Unmute`, `Play video`, `Play`, plus
  cookie/consent buttons. Once playing, the control is labelled “Pause”, so it will not
  pause anything. It also times each song and starts the next one when it is over (or when
  YouTube's end screen with “Replay” appears). It owns a `MediaSession` for the car's
  next/previous/play/pause buttons and a silent keep-alive track so Android hands it the
  media keys.
- The keeper checks every 5 s. While playing it reads Brave's own media session (speed 0
  means Chromium paused, even though it still says "playing"), so the silent keep-alive
  track cannot hide silence. If Brave is in front it hits the toolbar reload button (same
  tab); if not, it reopens the current URL in Brave. Retries back off from 20 s up to
  90 seconds so a dead link cannot spam new tabs. A pause pressed from the car is
  respected and is not fought by the silence keeper.

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
