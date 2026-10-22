# QR Garage

Offline-first Android app that answers "where is it?" for a home garage. Locations (zone → shelf
unit → shelf → bin) carry printed QR labels; items are found by scanning, text search or voice.
The spec is in [Requirements.md](.agent/instructions/Requirements.md); requirement IDs are referenced in code comments.

## Build and test

```
./gradlew :app:assembleDebug            # build
./gradlew :app:testDebugUnitTest        # JVM tests (QR payload, FTS query builder, location tree)
./gradlew :app:connectedDebugAndroidTest  # device tests: repositories/FTS, migrations, backup, UI flow
```

Toolchain: AGP 9.4 (built-in Kotlin), Kotlin 2.4, KSP, compileSdk/targetSdk 37, minSdk 26.

## Architecture

Single module, layered `UI → ViewModel → Repository → DAO / remote`, with ViewModels exposing `StateFlow`.

| Package | Contents |
|---|---|
| `data/db` | Room entities, DAOs, FTS4 table, `SearchQuery` builder, `DatabaseFactory` |
| `data/repo` | `LocationRepository`, `ItemRepository`, `LocationTree` (in-memory paths), CSV/JSON export |
| `data/settings` | DataStore settings, install `deviceId`, current location |
| `data/photos` | Downscale/compress photos into app-private storage (relative paths in the DB) |
| `qr` | Versioned payload `garageinv://loc/<uuid>?v=1`, ZXing generation, PDF label sheets |
| `backup` | GIS authorization, Drive REST client, archive and restore, WorkManager worker and scheduler |
| `ui/...` | Compose screens: home/search, scan, location, item add/edit/detail, settings |

Key decisions:
- **Search:** FTS4 table (`item_fts`) indexes name, description, tag names and notes. It is rebuilt
  per item inside the same transaction as every write, because tags live in a join table and
  `contentEntity` can't express that. Every term is prefix-matched (`m6 bol` → `m6* bol*`), and
  results are paged with Paging 3.
- **Location hierarchy:** `parentId`, with recursive CTEs for paths and descendants (DB-3). List rows
  resolve paths from an in-memory `LocationTree`.
- **Undo:** soft deletes plus an app-scoped snackbar queue (`UiMessenger`), so Undo still works
  after the screen that triggered it has closed.
- **Restore (BK-4):** the backup is downloaded, validated (manifest, schema version, `integrity_check`)
  and staged, then the app restarts. `RestoreSwap` moves the live DB and photos aside, swaps the
  staged copy in before Room opens, and rolls back automatically if it fails to open or migrate.
- **DB snapshot (BK-1):** `VACUUM INTO` on API 30+. Older devices use a WAL checkpoint (TRUNCATE)
  and then copy the file.

## Google Drive backup setup (required before backup works)

The Authorization API needs an OAuth client registered for this app's package and signing key.
Until one exists, **Connect Google Drive** shows "Google sign-in isn't configured for this build".

1. In Google Cloud Console, create a project and enable the **Google Drive API**.
2. Configure the OAuth consent screen and add the scope `.../auth/drive.appdata`.
3. Create an **Android** OAuth client with package `com.example.qrgarage` and your signing SHA-1
   (`./gradlew :app:signingReport`). Create one for debug and one for release.
4. Add your Google account as a test user while the consent screen is in testing mode.

No client ID goes into the app; Play services matches it by package name and SHA-1.

## Status against Requirements.md

All MUST items are implemented. Notes and gaps:

| Item | Status |
|---|---|
| SEARCH-4 typo tolerance (SHOULD) | **Not done.** Prefix matching only. |
| SET-OTH-2 CSV import (SHOULD) | **Not done.** CSV/JSON *export* is done (SET-OTH-1). |
| ITEM-15 change history (SHOULD) | Done (`change_log` table, shown on the item page). |
| SET-QR-4 label presets (SHOULD) | Done: four Avery-style presets plus a custom grid. Dimensions are approximate, so print a test sheet on plain paper first. |
| ITEM-11 lending | Managed from the item page (Lend out / Mark returned), not the edit form. |
| Drive backup / restore | Archive creation and the restore swap/rollback are covered by tests. The Drive upload, list, download and auth calls are **untested end-to-end** because they need the OAuth client above. |
| NFR-2 performance | Not measured on a real mid-range device yet. |
| Package name | Kept the project's `com.example.qrgarage` (the spec's `com.example.garageinventory` was a placeholder). |
