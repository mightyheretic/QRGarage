# QR Garage

An offline-first Android app that answers "where is it?" for a home garage. You print QR labels for
the places things live (zones, shelf units, shelves, rows and bins), stick them up, and then find
any item by scanning a label, typing a search, or speaking one.

Everything works without a network connection. The only feature that needs one is the optional
Google Drive backup.

## Features

### Home screen
- A large **Scan** button, a **search bar with a mic** for voice search, an **Add** button, and a
  list of recently added or viewed items with their locations.
- Large tap targets throughout, so the app is usable with gloves or dirty hands.

### Locations and QR labels
- Locations form a hierarchy: **Garage → Zone → Shelf unit → Shelf/row → Bin**. An item can sit at
  any level.
- Shelves, rows and bins are created with **scroll-wheel pickers** instead of free text, so labels
  are always uniform, for example **"Shelf A Row 01 Bin 2"**. Shelves run A–Z, then AA, AB and so on.
  Rows are zero-padded (01, 02 …) and bins are plain numbers. The app won't let you create the same
  label twice under the same parent.
- Long location paths wrap only between segments, so "Shelf" is never separated from "A".
- Each QR code encodes the location's permanent ID, not its name. Renaming or moving a location
  never invalidates a printed label.
- Print QR labels for one or many locations as a **PDF label sheet**, with the name and path under
  each code. Several Avery-style sheet presets are included, plus a custom grid. The preset
  dimensions are approximate, so print a test sheet on plain paper first.

### Scanning
- Scanning a location label opens that location with its items. From there you can add an item,
  move items into it, or browse its sublocations.
- The last scanned location becomes the **current location**, and new items are pre-filled with it.
- The scanner has a flashlight toggle and a manual code entry fallback for damaged labels. Unknown
  or invalid codes show a clear message.

### Items
- **Quick add** needs only a name. A photo and description are optional, and **Add another** saves
  and resets the form while keeping the location, for fast bulk entry.
- **More details** adds quantity and unit, tags (with autocomplete), category, purchase date and
  price, notes, and a low-stock threshold.
- 1–3 photos per item, taken with the camera or picked from the gallery. Photos are compressed and
  stored privately on the device.
- The item page shows the name, location path and a large photo, with the details below it. Tap a
  photo to view it full screen.
- **Lending:** record a borrower, the date lent and an optional due date, then mark the item
  returned from the item page.
- Edit or move items, with a change history on each item's page.
- Deletes and moves can be undone from the snackbar. Deleted items and locations go to the
  **Trash**, where they can be recovered or permanently deleted.

### Search
- Full-text search across names, descriptions, tags and notes. Results update as you type, and
  each word is prefix-matched, so `m6 bol` finds "M6 bolts".
- Filter by location (including everything inside it), category, tag, low stock or lent out.
- Each result shows the full location path (for example "Left wall > Shelf A Row 03 > Bin 2"),
  plus the item's thumbnail and name.

### Backup, export and import
- **Google Drive backup** to the app's private Drive storage: back up now, or schedule daily or
  weekly backups, optionally only on Wi-Fi or while charging. You can include or skip photos,
  choose how many backups to keep, and get a notification if a scheduled backup fails.
- **Restore** lists each backup's date, device and item count. A backup from a newer app version
  is refused. The restore is checked before it replaces your data, and the previous data is
  kept until the restore succeeds. Any photos that couldn't be restored are listed by item.
- Backups restore a whole snapshot. They don't sync between devices.
- **Export and import** as CSV or JSON, bundled in a ZIP with photos, as an escape hatch that
  doesn't depend on Drive. Import only adds items and never overwrites existing ones.

### Settings
- Manage locations, print QR labels, configure backup, export and import, open the Trash, set the
  default unit and low-stock threshold, and choose a system, light or dark theme. On Android 12+
  the app uses your wallpaper's dynamic colors.

### Privacy
No analytics or third-party tracking. Your data leaves the device only through your own Drive
backup or an export you make yourself. Camera, microphone and notification permissions are
requested only when you first use the feature that needs them.

## Build and test

```
./gradlew :app:assembleDebug              # build
./gradlew :app:testDebugUnitTest          # JVM tests
./gradlew :app:connectedDebugAndroidTest  # device tests: repositories/search, migrations, backup, UI flow
```

Toolchain: AGP 9.4 (built-in Kotlin), Kotlin 2.4, KSP, compileSdk/targetSdk 37, minSdk 26 (Android 8.0).

## Architecture

A single-module app with a layered design: Compose UI screens, view models, repositories, and the
local database or Google Drive underneath. Built with Jetpack Compose (Material 3), Room, Hilt,
CameraX + ML Kit for scanning, ZXing for QR generation, WorkManager and DataStore.

| Package | Contents |
|---|---|
| `data/db` | Database tables, queries and the full-text search index |
| `data/repo` | Item and location data, location paths and labels, export and import |
| `data/settings` | User settings, the install's device ID and the current location |
| `data/photos` | Photo compression and storage |
| `qr` | QR code contents and generation, PDF label sheets |
| `backup` | Google Drive connection, backup archives, restore and scheduled backups |
| `ui` | Screens: home and search, scan, location, item add/edit/detail, settings |

How some of the key pieces work:
- **Search:** a full-text index covers item names, descriptions, tags and notes. It is updated in
  the same database transaction as every item change, so it never falls out of sync. Long result
  lists load in pages.
- **Location hierarchy:** each location points to its parent, and full paths and nested contents
  are resolved with recursive queries.
- **Undo:** deletes are soft, and the Undo prompt belongs to the app rather than a single screen,
  so it still works after you navigate away.
- **Restore:** the backup is downloaded, validated and staged, then the app restarts. The current
  data is moved aside, the backup is swapped in, and the app rolls back automatically if the
  restored data can't be opened.
- **Database snapshot:** backups take a consistent copy of the database even while the app is in use.

## Google Drive backup setup

Backup needs an OAuth client registered for this app's package and signing key. Until one exists,
**Connect Google Drive** shows "Google sign-in isn't configured for this build".

1. In Google Cloud Console, create a project and enable the **Google Drive API**.
2. Configure the OAuth consent screen and add the scope `.../auth/drive.appdata`.
3. Create an **Android** OAuth client with package `com.example.qrgarage` and your signing SHA-1
   (`./gradlew :app:signingReport`). Create one for debug and one for release.
4. Add your Google account as a test user while the consent screen is in testing mode.

No client ID goes into the app; Play services matches it by package name and SHA-1.

## Known limitations

- Search uses prefix matching only and doesn't tolerate typos.
- Backup archive creation and the restore swap and rollback are covered by tests. The Drive
  upload, list, download and sign-in calls need the OAuth client above and aren't covered by
  automated tests.
