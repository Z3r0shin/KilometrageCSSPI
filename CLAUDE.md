# CSSPI Transport (Kilometrage)

Android GPS trip logger in Kotlin. Package `com.transportlogger`. Single
`:app` module, Gradle Kotlin DSL, no Compose — views + XML layouts.

Repo: https://github.com/Z3r0shin/KilometrageCSSPI (public).

See `HANDOFF.md` for what is in flight and what is still to build.

## Build and verify

Windows host; use the Gradle wrapper from the project root:

```
./gradlew :app:compileDebugKotlin --console=plain   # fast syntax/type check
./gradlew :app:assembleDebug --console=plain        # full debug APK
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Bump
`versionCode` and `versionName` in `app/build.gradle.kts` for every build that
goes to a phone, and tag the commit `v<versionName>`. APKs are never committed:
attach the build to the GitHub release for its tag as
`Kilometrage-CSSPI-v<versionName>.apk`.

Phone builds must be signed with the shared `debug.keystore` at the repo root.
It is gitignored (a public signing key would let anyone ship an "update" over
users' installs); copy it to a new machine by hand. Without it Gradle signs
with the machine's own debug key, fine for the emulator only.

`adb` is not on PATH. Prepend the SDK platform-tools directory:

```
export PATH="$PATH:$ANDROID_SDK_ROOT/platform-tools"   # bash
```

## Running on the phone

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.transportlogger/.MainActivity
adb exec-out screencap -p > /tmp/shot.png     # visual check
adb logcat -d -s TransportLogger:V AndroidRuntime:E
```

Location features need the phone to grant background location and
notification permissions manually; `adb shell pm grant` covers the
runtime ones but not "Allow all the time".

## Code map

- `TripEngine.kt` — dwell detection and trip segmentation (dwell = 150s).
- `LocationService.kt` — foreground service feeding the engine. It decides
  which zone a fix is in: zones are widened by a margin (Réglages, 20 m by
  default), leaving a zone takes two good fixes outside, and a fuzzy indoor
  fix can only confirm the zone already entered.
- `TripStore.kt` — `trips.json`, the source of truth. CSV is export-only.
- `PlaceStore.kt` — the places and their zones, kept in `places.json` on the
  device. `assets/adresses_corrigees.csv` lists the 128 fixed places with
  coordinates as reference data; no code reads it, so a new place must also be
  added on the phone (in the app, or in `places.json`). A fresh install is
  seeded from `assets/places_seed.json` (the 128 places with outlines, no home).
- `Profile.kt` / `ProfileActivity` — the claimant (name, employee number,
  address), asked on first launch and stored on the phone only; every export
  writes the form header from it.
- `KmLookup.kt` — distances from `assets/km_lookup.json`, keyed by exact
  place name with accent/space normalization.
- `Settings.kt` — preferences plus the days-off / half-day calendar.
- `FormulaireExport.kt` — fills the paper claim form. Copies every zip entry of
  `assets/formulaire_template.xlsx` verbatim except the sheet, writing values
  into cells that keep their own style. 25 trips per form, then a new file.
- `tools/strip_template.py` — regenerates that asset from the board's original
  workbook (never committed; pass its path): drops the distance-matrix sheet,
  the dropdowns and the lookup formulas, blanks personal data, keeps all the
  artwork and print setup. Rerun it if the board reissues the form.
- Work window (Réglages, 07:30 – 16:15 by default, weekdays, minus days off):
  `LocationService` asks for location only inside it and closes the open leg
  at the end; nothing is exported automatically. `WindowAlarm`, an exact
  alarm, wakes it at each boundary (a Handler delay stalls in deep sleep);
  `BootReceiver` restarts it after a reboot or an update.
- Screens: four tabs on a bottom bar (`Nav.kt`) — `MainActivity` (Aujourd'hui),
  `TripsActivity` (Trajets, with the exports), `PlacesActivity` / `MapActivity`
  (Lieux, list and map), `SettingsActivity` (Réglages) — plus `CalendarActivity`
  (congés) and `ScanActivity` (P.O. barcode).
- Shared UI: `TripRow.kt` (one trip line), `PoSheet.kt` (P.O. bottom sheet),
  `TripSheet.kt` (a trip added by hand), `Fmt.kt` (display formats),
  `res/values/styles.xml`.

## Domain rules that are easy to break

- Home is an invisible bridge: legs to or from the home place are never
  logged and do not reset the trip origin, so `A → home → B` yields one
  paid `A → B` trip.
- CSV export schema is `date,depart,arrivee,km,statut` (`TripStore.toCsv`).
  An external script of the user's merges this into an .xlsx form, so column
  order is contractual.
- `PLACE_NAMES_REFERENCE.csv` at the repo root is the canonical name list
  that script keys on. Place names in assets must stay in sync with it.
- Three matrix-only entries (`STE-ALICE`, `BÉTONEL NOTRE-DAME`,
  `CLUB OUTILS 5725 BOUL MÉTROPOLITAIN`) are defunct duplicates. Ignore
  them; they need no address.
- The claim form's columns are fixed: A JOUR, B départ, E arrivée, J Requête
  (the P.O.), L kilometres, M 1 = aller / 2 = aller-retour, N total. Rows 23-47
  only — 25 per form.
- The km rate lives in `FormulaireExport.RATE_PER_KM` and is written on every
  export, so the template is never rebuilt for a rate change.

## Interface rules

- Light and dark share one theme. Layouts and code use the colour names in
  `res/values/colors.xml` (each redefined in `values-night`), never a literal
  hex; the scan screen, always dark, is the one exception.
- Text uses the `Text.*` styles, buttons the `Button.*` styles. Icons are the
  stroke vectors in `res/drawable/ic_*.xml`; no emoji in the UI.
- The app lists trips the way the form does: build rows with
  `FormulaireExport.lines()`, so an aller-retour is one row everywhere.
- The home place is shown as "Domicile"; its name and address stay off screen.

## Privacy

The repo is public. Never commit personal data: names, home addresses,
employee numbers, real P.O. numbers, data pulled off a phone (`trips.json`,
`places.json` — it holds the home place), filled forms, the board's original
workbook (its distance sheet names people and their homes), APKs, or the
keystore. In docs and commit messages, describe trips and phones in general
terms: no routes, dates of personal movements or device details.
`FormulaireHeaderTest` fails the build if personal data reaches the template.
The claimant's identity comes from the profile entered on the phone.
