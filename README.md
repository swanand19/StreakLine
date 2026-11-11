# Streakline

A personal challenge tracker for Android (10 / 21 / 30 / 60 / 75 / 90-day or any custom length).
Fully offline. No accounts, no analytics, and **no INTERNET permission**, so the app physically cannot talk to a server.

## What it does

- **Challenges** with a name, length, start date and a list of daily tasks.
- **Two kinds of task:** *Tick* (did it / didn't) and *Count* (e.g. 50 push-ups, 20 pages, 8 glasses, 8,000 steps) with − / + buttons or typed values.
- **Categories:** Fitness, Mind, Habit, Diet, Other (colour-coded).
- **Weekday schedules per task** (e.g. gym Mon–Sat only). Days with nothing scheduled count as rest days and don't break a streak.
- **Today screen:** concentric activity rings (one per category) with tasks done, a week strip of daily rings, and every running challenge with its day number and streak. Arrows or the week strip let you go back and fix past days.
- **Quick entry for counts:** tap a count task for a big ring with +1/+5/+10 buttons, or use the +step button on the row.
- **Progress screen:** completion ring, streaks, a daily completion chart, a daily amounts chart per count task (with target line, total, average, best day), the calendar grid, and per-task hit rates.
- **Strict mode** (for 75 Hard style rules): flags a missed day and offers a restart from Day 1.
- **Templates:** Fit in 30, Learn daily, Clean eating, Better habits, Hard 75.
- **Reminders:** real Android notifications at up to 4 times a day, listing the tasks still left (e.g. "3 tasks left today: Push-ups (30/50 reps), Walk, Water"). Stays quiet when everything is done. Includes a test button.
- **Backup:** export / import a JSON file through Android's file picker (you choose where it goes).

## Build and install (Android Studio, ~5 minutes)

1. Install [Android Studio](https://developer.android.com/studio) (it bundles JDK 17 and the Android SDK).
2. **File → Open** → select this `android` folder. Let Gradle sync (first sync downloads the Android build plugin once).
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
   Output: `app/build/outputs/apk/debug/app-debug.apk`
4. Copy the APK to your phone (USB, or plug in the phone with USB debugging on and press ▶ Run).
5. On the phone, open the APK and allow "Install unknown apps" for your file manager when asked.

Command line alternative (with the Android SDK installed and `ANDROID_HOME` set):

```bash
./gradlew assembleDebug
```

### No PC handy? Build on GitHub

Push this folder to a **private** GitHub repo. The included workflow (`.github/workflows/build-apk.yml`) builds the APK on every push. Open the **Actions** tab → latest run → download `streakline-apk`.

## Where your data lives

- Everything is one JSON file in the app's private storage (`/data/data/com.streakline.app/files/state.json`).
- `allowBackup="false"`, so Android won't copy it to Google's cloud backup either.
- **Uninstalling the app deletes the data.** Use *Settings → Export backup* before uninstalling or changing phones, then *Import backup* on the new install.
- Updating the app (installing a newer APK over the old one) keeps your data, as long as it's signed with the same key. Debug builds from the same PC use the same debug key.

## Project layout

```
app/src/main/assets/index.html          the whole UI (HTML/CSS/JS, no libraries)
app/src/main/java/com/streakline/app/
    MainActivity.java                   WebView host + JS bridge (save/load, backup, reminder)
    Reminders.java                      daily alarm + notification
    ReminderReceiver.java, BootReceiver.java
```

No third-party dependencies. Java only. minSdk 24 (Android 7.0), targetSdk 34.

## Changing things

The UI is plain HTML/JS in `assets/index.html`. You can open it in Chrome on your PC to tweak it (it falls back to `localStorage` outside the app), then rebuild.

- Add or edit templates: the `TEMPLATES` array.
- Categories: the `CATS` object plus a `--c-<name>` colour token in the CSS.
- Data format: `{ challenges: [...], logs: { <challengeId>: { "YYYY-MM-DD": { t: { <taskId>: true | number }, note } } }, settings }`.

## Notes

- Reminders use exact alarms and are re-armed after a reboot, an app update or a clock change. Some phone brands (Xiaomi, Oppo, Vivo, Realme) also kill background alarms for "battery saving": if reminders don't arrive, set Streakline's battery usage to "No restrictions" in Android settings.
- On Android 13+ the app asks for notification permission when you add your first reminder.
- The UI font is Barlow Condensed (SIL Open Font License), embedded in the app so it works offline.
