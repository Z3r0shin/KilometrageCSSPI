# Handoff

State of the work as of 2026-10-22, for picking this up on another machine
(including by another Claude Code session). `CLAUDE.md` covers the codebase
itself; this file covers what is in flight.

## Getting set up elsewhere

```
git clone https://github.com/Z3r0shin/KilometrageCSSPI.git
```

The repo is public. Copy `debug.keystore` to the repo root by hand (it is not
committed; see "Signing" below).

Needs Android Studio (the Rabbit line, build `262.9437.185`, is what this was
built against), JDK 21, and an Android SDK with platform 35. `local.properties`
is gitignored, so Studio will write its own `sdk.dir` on first open. Gradle
wrapper 9.4.1, AGP 9.2.1 — no manual install needed.

Build and verify without Studio:

```
./gradlew :app:compileDebugKotlin --console=plain   # fast type check
./gradlew :app:assembleDebug --console=plain        # APK
```

## Done and verified on hardware

Everything below was checked on the owner's phone (Android 17), not only
compiled.

- **Logging no longer dies permanently.** `LocationService`'s companion owns the
  enable flag and `ensureRunning()`; `MainActivity.onResume` and the (now
  actually registered) `BootReceiver` both call it. `TripEngine`'s origin is
  date-stamped so a stale one cannot start a phantom leg.
- **High-accuracy location inside the work window.** Balanced priority resolved
  to ~100 m, wider than both the 70 m zone radius and the 75 m accuracy filter,
  so every fix was discarded. GNSS now engages; verified on the zone readout.
- **Per-trip P.O.**, typed or scanned. `Trip.po`, `TripStore.setPo()`, a dialog
  off each trip's overflow menu, scanning via Play Services' out-of-process
  scanner (no CAMERA permission). Verified: the `po` field lands in trips.json.
  The scanner has since been replaced; see below.
- **Filled .xlsx export.** Verified end-to-end from the phone: correct month,
  rate, places, P.O. in column J, totals formulas intact.

## Built on 2026-10-05 (laptop)

The phone now runs a build signed with the shared debug key, `debug.keystore`
(kept out of git), so any machine that has a copy can install over it; see
"Signing" below.

The pairing was verified on the phone on 2026-10-06: an aller-retour exports
as one line with M = 2. The lost-trip fix has not been observed in action (no
trip has been logged with the trips screen open since).

- **Aller-retour pairing (column M).** `FormulaireExport.lines()` collapses
  `X -> Y` directly followed by `Y -> X` (same day) into one line with M = 2 and
  N = L * 2. Only the immediately following trip can be the return. Legs with
  two different P.O. numbers are not paired; if only one leg has a P.O. the line
  takes it. The line carries the outbound leg's km. Pairing runs before paging.
  `trips.json` still records both legs. Covered by `FormulaireLinesTest`.
- **Lost trips fixed.** `TripsActivity` kept its own `TripStore` snapshot, so
  saving a P.O. (or deleting) wrote that snapshot over any trip the service had
  logged since the screen opened. That is the likely way a trip vanished on
  2026-10-05. Every `TripStore` change
  now re-reads the file under a shared lock, and the screen reloads on
  `ACTION_TRIPS_CHANGED`.

## The interface (redrawn 2026-10-06)

Every screen was redrawn from mockups the owner approved ("Direction A",
light and dark). What the app does underneath did not change.

- **Four tabs** on a bottom bar: Aujourd'hui (`MainActivity`), Trajets, Lieux
  (list and map as two views of one tab), Réglages. `Nav.kt` explains the back
  stack. Congés is a sub-screen reached from Aujourd'hui and Réglages; its week
  starts on Sunday at the owner's request.
- **Light and dark** from one theme: `res/values/colors.xml` and
  `values-night/colors.xml` define the same names, and layouts use names only.
  Réglages has a Système / Clair / Sombre choice (`Settings.themeMode`).
- **Shared pieces:** `styles.xml` (type scale, buttons), `Fmt.kt` (km, money,
  dates), `TripRow.kt` + `item_trip_line.xml` (one trip line), `PoSheet.kt`
  (the P.O. bottom sheet), `include_bottom_nav.xml`.
- **An aller-retour is one row in the app too**, built with the same
  `FormulaireExport.lines()` the form uses; its P.O. is saved on both legs and
  deleting it deletes both.
- **New on screen, same data:** the month's claim value in dollars, form lines
  used out of 25, a "Sans P.O." filter, recent P.O. numbers, a lamp and a
  manual-entry button on the scan screen, home shown as "Domicile" without its
  address. Export (xlsx and CSV, including "Tous les trajets") lives on Trajets.
- Checked on the phone in both themes by opening every screen; the export was
  re-run from the new Trajets screen. Not exercised: the lamp, manual entry
  from the scan screen, CSV save, adding or importing places, the map's
  footprint detection, and landscape or large-font layouts.
- The mockups are a private canvas, not in the repo.

## The P.O. scanner (rewritten 2026-10-05)

The labels are Code 39, which has no check digit. Play Services' ready-made
scanner returns its first read and closes, and that read was right 1 time in 7
(`012E34567` came back as `G12E34567`, `00E34567`, `000434567`...). It is
gone. `ScanActivity` keeps the camera open (CameraX), decodes every frame with
ZXing, and `ScanVote` accepts a value only when it is exactly 9 letters/digits
and has been read 3 times, at least twice as often as any rival. The owner
confirmed it reads the label correctly. The app now holds the CAMERA
permission. Typing a P.O. by hand has no length rule.

## The claimant's profile: an app anyone on the team can install

Built on the `profil-utilisateur` branch, merged into `main` on 2026-10-22
for 1.3:

- **First launch asks who is claiming** (`ProfileActivity`, `Profile.kt`): name,
  employee number, address, city, postal code, with function and service
  pre-filled OCE / SRM. Editable later from Réglages > Profil. Stored on the
  phone only.
- **Every export writes the form header from that profile** (C13, J13, C14,
  C15, C16, J14, J15). The template asset carries no personal data any more;
  `FormulaireHeaderTest` fails the build if it comes back. The accounting codes
  (I19:N19) are left as the board's template has them, assumed common to the
  team.
- **A fresh install starts with the 128 places** copied from
  `assets/places_seed.json` (outlines included, nobody's home). An existing
  `places.json` is never touched. Regenerate the seed from a phone's
  `places.json` with the `isHome` entry removed. `PlacesSeedTest` fails if a
  seeded place is more than 60 m from its row in `adresses_corrigees.csv`, so
  a position corrected in one must be corrected in the other.
- **A beta notice** (`BetaNotice`) pops up once on first use, over the welcome
  screen, asking for feedback.
- Tested on the emulator as a new install on 2026-10-07: welcome screen,
  seeding, profile editing and an export with a sample profile all pass. Not
  tested: a real phone, location permissions and detection on a new install.
- An existing install shows the welcome screen once after updating to 1.3;
  its places and trips are unaffected.
- To give it to someone: they download the APK from the GitHub release. It is
  signed with the shared debug key, so later builds install over it.

## Missed stops and the zone margin (2026-10-07)

On a drive with four stops and a return, the phone logged a single trip from
the first place to the third: two stops and the return were missed. The cause
below is deduced from the code and the zone data; no fix-by-fix record exists.

All zones are OpenStreetMap outlines, some of them only the building (one
school's is about 4,300 m2). Three things then worked against a
stop: a car parked at the kerb is outside the outline; a single fix outside
reset the dwell timer; and indoor fixes fuzzier than 75 m were thrown away,
so nothing confirmed the stay. Changed in `LocationService` / `PlaceStore`:

- zones are widened by a margin, `Settings.zoneMarginM`, 20 m by default,
  adjustable 0-60 m in Réglages > Détection;
- leaving a zone takes two good fixes outside in a row (`EXIT_FIXES`);
- a fix between 75 and 150 m of accuracy that falls in the current zone
  counts as still being there; it can never enter or leave a zone.

Unit-tested for the geometry only. Installed on 2026-10-07; not yet proven on
the road.

The unlogged return leg had a second cause: a stop at home between two places,
and `TripEngine` cleared the origin on a stop at home, so the next stop became a
fresh start. Home now leaves the origin alone (A -> home -> B logs A -> B), as
CLAUDE.md always said it should. Installed 2026-10-07; not yet seen in action.

## Zones at a neighbour's coordinates, and trips by hand (2026-10-14)

Three places in both place lists carried another place's address and
coordinates, so their zone sat on top of that place and never fired:
LE TOURNESOL ANNEXE (PAT) (on LE TOURNESOL; really 1680 8e Avenue, next to
CENTRE PAUL-GRATTON), CSE (5000 MÉTROPOLITAIN) (on the 5055) and GUY VANIER
(10905 HENRI-BOURASSA) (on LA PASSERELLE). Corrected from OSM / Photon; the
board's matrix already had the right distances (6 km and 1 km for the annexe
from LE TOURNESOL and PAUL-GRATTON). The lists are reference data only, so the
phone's `places.json` still has the old positions until each is moved:
Lieux > the place > "Corriger l'adresse" (an address, or "lat, lng") moves the
zone and looks up its OSM outline there. Other shared coordinates
(ALBATROS / DES ROSERAIES, the two 4975 Amos annexes, the two Provincial
entries) look like genuinely shared buildings and were left alone.

`LocationService` used to read `places.json` once at start, so a zone added
or moved on a screen went unseen until the service restarted. `PlaceStore.refresh()`
now re-reads the file when it changed, on every fix and before every write;
a file caught mid-write keeps the previous zones.

Trajets has "Ajouter un trajet" (`TripSheet`): date, time, any two names (the
place list is suggested, accents ignored), km from the table or typed, and an
aller-retour box that adds the return right after so the form pairs them.
Saved as CONFIRMÉ, so the CSV schema is unchanged. Tried on the phone with a
trip whose km came from the table.

The same sheet edits a line (overflow menu > "Modifier le trajet"), both legs
of an aller-retour at once; the P.O. and status stay, and the trip keeps its
exact time unless the date or time is changed. A line can also be held and
slid among the day's others: dropped, it takes a time halfway between its new
neighbours, since the time is what orders a day and the form. Not while the
"Sans P.O." filter is on, and only within a day. Edit and drag are built and
unit-tested only; not yet tried by hand.

The three zones were moved on the phone on 2026-10-14 by writing places.json
(the annexe and CSE 5000 got their OSM outline, ported from `OsmFootprint`;
OSM has nothing at 10905 Henri-Bourassa, so GUY VANIER is a 70 m square). The
annexe's outline is 37 m from CENTRE PAUL-GRATTON's: with the 20 m margin the
two touch, and the nearer centre wins. "Corriger l'adresse" itself has still
not been exercised.

The seed (`places_seed.json`) kept the old positions until 1.3.1, so fresh
installs got the three zones on the wrong building. 1.3.1 moves them there
too, with the outline `OsmFootprint` picks (or the 70 m square when OSM has
none). Two other places differed between the seed and the CSV:
LE TOURNESOL ANNEXE (RDP) and CANADIAN TIRE PAT. OSM puts both where the
seed has them, so the CSV coordinates were corrected instead.

## The morning start that came late (2026-10-22)

A second phone often missed the start of the morning: location came on only
when the app was opened, and on several days the first trip was logged hours
into the window. Permissions, battery bucket and the service itself were all
fine.

Cause: `LocationService` woke itself at the next window boundary with
`Handler.postDelayed`, which counts uptime, and uptime stops in deep sleep
(that phone spent most of its screen-off time asleep). Opening the app re-ran
the mode check, which is likely why the owner's phone looked fine. 1.2 replaces it
with `WindowAlarm`, an exact `RTC_WAKEUP` alarm allowed while idle
(`USE_EXACT_ALARM`, granted at install), and `BootReceiver` also handles
`MY_PACKAGE_REPLACED`. Built and unit-tested; not yet installed on either
phone, nor seen to fire on time.

## Versions

`versionCode` / `versionName` in `app/build.gradle.kts`; Réglages shows them
at the bottom. Bump both for every build that goes to a phone, and tag the
commit `v<versionName>`. 1.0 (1) was every build up to 2026-10-14; 1.1 (2) adds
trips by hand, editing and reordering; 1.2 (3) wakes at the window start with
an exact alarm; 1.3 (4) asks for the claimant's profile and is the first public
build; 1.3.1 (5) fixes three seeded zones and adds the beta notice.

APKs are not committed (`*.apk` is gitignored). Each version is attached to
the GitHub release for its tag as `Kilometrage-CSSPI-v<versionName>.apk`, a
copy of `app-debug.apk` signed with the shared debug key.

## A place the board's matrix does not have

`Serrupro St-Jean-Baptiste` (1300 boul. Saint-Jean-Baptiste, local 126) was
added on 2026-10-07 at the owner's request. The board's matrix has no row for
it, so its 127 distances were computed: driving distance on the road network
without traffic (public OSRM server), the mean of the two directions, rounded
to the nearest km, and stored as the same value both ways. The same method run
on two existing places matched the board's figure exactly for about 45% of
pairs and within 1 km for about 87%. The three defunct entries have no value.
It is in `km_lookup.json`, both place lists, and the phone's `places.json`
(a 70 m square; opening the map looks for its real outline).

## Open: the board's distance matrix is not symmetric

`km_lookup.json` was checked cell by cell against sheet 2 of the board's
original workbook (kept outside the repo) and is an exact copy (origin = row, destination = column). The
workbook itself has 382 place pairs with a different km in each direction: 266
by 1 km (rounding), 73 by 3 km or more, which look like typing errors (76 vs 6,
54 vs 5; the whole "to WOLSELEY ANJOU" column looks wrong). Nothing was
corrected, because the claim is verified against that same matrix. An
aller-retour line uses the outbound value; the owner has not yet said whether
to keep that rule or to report the errors to the board.

The matrix also spells 31 places differently on its two axes. `KmLookup` now
compares names without accents, spaces or case; before that, every trip *to*
`VITRERIE BRUNELLE RUE SHERBROOKE` got no km.

## Signing: the shared debug keystore, kept out of git

`debug.keystore` at the repo root is the shared debug key, wired into
`app/build.gradle.kts`. It is gitignored: the repo is public, and anyone holding
the key could sign an "update" that installs over every shared copy and reads
its trips. Copy it to each build machine by hand. Every build that goes to a
phone must be signed with it, or installing one build over another fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` -- recoverable only by uninstalling, which
takes the trips and the configured zones with it. Without the file, Gradle
falls back to the machine's own debug key, which is fine for the emulator but
not for a phone. Never sign a release with it.

Before any install that might hit that error, back the phone up first:

```
for f in trips.json places.json; do
  adb exec-out "run-as com.transportlogger cat /data/data/com.transportlogger/files/$f" > $f
done
```

## Things that will waste your time otherwise

- **The rate lives in code**, `FormulaireExport.RATE_PER_KM`, currently `0.68`
  (the template says 0.61; set to 0.65 on 2026-10-05, corrected to 0.68 on 2026-10-07). It is written on every export,
  so the template never needs rebuilding for a rate change.
- **`tools/strip_template.py` regenerates the shipped asset** from the board's
  original workbook, which is never committed (its distance sheet names people
  and their homes). If the board reissues the form, run
  `python tools/strip_template.py <workbook.xlsx> [output.xlsx]` (standard
  library only; without an output it overwrites the asset). It blanks the
  claimant's header cells and every unused shared string, verifies its own
  output and refuses to emit a workbook Excel would call damaged;
  `FormulaireHeaderTest` fails the build if personal data reaches the asset.
  On 2026-10-22 it reproduced the committed asset entry for entry.
- **The CSV export is legacy** but deliberately kept: its 5-column header
  `date,depart,arrivee,km,statut` feeds the owner's own `csv_to_formulaire.py`.
  Do not add columns to it without asking.
- **Trips are edited from Trajets** (overflow menu), not from Aujourd'hui,
  whose rows have no menu.
- **The repo is public.** Never commit personal data: no names, home
  addresses, employee numbers, real P.O. numbers, phone data (`trips.json`,
  `places.json`, which holds the home place), filled forms, APKs or the
  keystore. Write about trips and phones in general terms.

## Driving the phone over adb

`adb` is not on PATH; it is under `$ANDROID_SDK_ROOT/platform-tools`. On Windows
with Git Bash, device paths get mangled into `C:/Program Files/...` — prefix the
command with `MSYS_NO_PATHCONV=1`, and keep Windows-side paths in a separate
call. Only `MainActivity` is exported, so drive the rest of the UI with
`adb shell input tap` using coordinates from `uiautomator dump` — and re-dump
after the keyboard opens, because it shifts dialogs upward.

To write a file into the app's data, `adb push` it to `/data/local/tmp` and
`adb shell run-as com.transportlogger cp` it into place, then compare md5sums.
Do not stream it with `adb exec-in ... tee`: on 2026-10-07 that cut a 94 KB
`places.json` to 12 KB (restored at once from the copy taken beforehand). Stop
the app first and restart it after, since the service keeps places in memory.

Proof worth gathering, beyond "it compiled":

```
adb shell dumpsys activity services com.transportlogger      # service alive and foreground?
adb shell cmd appops get com.transportlogger | grep LOCATION # location actually consumed?
adb shell dumpsys location | grep mStarted                   # did GNSS engage?
adb shell run-as com.transportlogger cat /data/data/com.transportlogger/files/trips.json
```
