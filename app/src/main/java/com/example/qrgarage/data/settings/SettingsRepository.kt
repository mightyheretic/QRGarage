package com.example.qrgarage.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

enum class BackupSchedule(val label: String) { OFF("Off"), DAILY("Daily"), WEEKLY("Weekly") }

enum class BackupStatus { NEVER, RUNNING, SUCCESS, FAILED }

/** BK-8: what the last successful backup carried. */
data class BackupPhotoSummary(val included: Boolean, val count: Int, val missing: Int = 0)

data class BackupSettings(
    val connected: Boolean = false,
    val accountEmail: String? = null,
    val schedule: BackupSchedule = BackupSchedule.OFF,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val includePhotos: Boolean = true,
    val keepCount: Int = 5,
    val lastBackupAt: Long? = null,
    val lastStatus: BackupStatus = BackupStatus.NEVER,
    val lastError: String? = null,
    val lastPhotos: BackupPhotoSummary? = null,
)

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val defaultUnit: String = "pcs",
    val defaultLowStock: Double? = null,
    val labelPresetId: String? = null,
    val backup: BackupSettings = BackupSettings(),
)

@Singleton
class SettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    private object Keys {
        val deviceId = stringPreferencesKey("device_id")
        val theme = stringPreferencesKey("theme")
        val defaultUnit = stringPreferencesKey("default_unit")
        val defaultLowStock = doublePreferencesKey("default_low_stock")
        val labelPreset = stringPreferencesKey("label_preset")
        val currentLocation = stringPreferencesKey("current_location_id")
        val bkConnected = booleanPreferencesKey("backup_connected")
        val bkEmail = stringPreferencesKey("backup_email")
        val bkSchedule = stringPreferencesKey("backup_schedule")
        val bkWifiOnly = booleanPreferencesKey("backup_wifi_only")
        val bkChargingOnly = booleanPreferencesKey("backup_charging_only")
        val bkIncludePhotos = booleanPreferencesKey("backup_include_photos")
        val bkKeep = intPreferencesKey("backup_keep")
        val bkLastAt = longPreferencesKey("backup_last_at")
        val bkLastStatus = stringPreferencesKey("backup_last_status")
        val bkLastError = stringPreferencesKey("backup_last_error")
        val bkLastPhotosIncluded = booleanPreferencesKey("backup_last_photos_included")
        val bkLastPhotoCount = intPreferencesKey("backup_last_photo_count")
        val bkLastPhotosMissing = intPreferencesKey("backup_last_photos_missing")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            theme = p[Keys.theme].toEnum(ThemeMode.SYSTEM),
            defaultUnit = p[Keys.defaultUnit] ?: "pcs",
            defaultLowStock = p[Keys.defaultLowStock],
            labelPresetId = p[Keys.labelPreset],
            backup = BackupSettings(
                connected = p[Keys.bkConnected] ?: false,
                accountEmail = p[Keys.bkEmail],
                schedule = p[Keys.bkSchedule].toEnum(BackupSchedule.OFF),
                wifiOnly = p[Keys.bkWifiOnly] ?: true,
                chargingOnly = p[Keys.bkChargingOnly] ?: false,
                includePhotos = p[Keys.bkIncludePhotos] ?: true,
                keepCount = p[Keys.bkKeep] ?: 5,
                lastBackupAt = p[Keys.bkLastAt],
                lastStatus = p[Keys.bkLastStatus].toEnum(BackupStatus.NEVER),
                lastError = p[Keys.bkLastError],
                lastPhotos = p[Keys.bkLastPhotosIncluded]?.let { included ->
                    BackupPhotoSummary(included, p[Keys.bkLastPhotoCount] ?: 0, p[Keys.bkLastPhotosMissing] ?: 0)
                },
            ),
        )
    }.distinctUntilChanged()

    /** ITEM-2 / LOC-3: the most recently scanned location, pre-filled when adding items. */
    val currentLocationId: Flow<String?> =
        store.data.map { it[Keys.currentLocation] }.distinctUntilChanged()

    suspend fun setCurrentLocation(id: String?) = store.edit {
        if (id == null) it.remove(Keys.currentLocation) else it[Keys.currentLocation] = id
    }

    private val deviceIdMutex = Mutex()
    @Volatile private var cachedDeviceId: String? = null

    /** Random ID generated once per install (section 8). */
    suspend fun deviceId(): String = cachedDeviceId ?: deviceIdMutex.withLock {
        cachedDeviceId ?: run {
            var id = store.data.first()[Keys.deviceId]
            if (id == null) {
                id = UUID.randomUUID().toString()
                store.edit { it[Keys.deviceId] = id }
            }
            cachedDeviceId = id
            id
        }
    }

    suspend fun setTheme(mode: ThemeMode) = store.edit { it[Keys.theme] = mode.name }
    suspend fun setDefaultUnit(unit: String) = store.edit { it[Keys.defaultUnit] = unit.trim() }
    suspend fun setDefaultLowStock(value: Double?) = store.edit {
        if (value == null) it.remove(Keys.defaultLowStock) else it[Keys.defaultLowStock] = value
    }
    suspend fun setLabelPreset(id: String) = store.edit { it[Keys.labelPreset] = id }

    suspend fun setBackupConnected(connected: Boolean, email: String?) = store.edit {
        it[Keys.bkConnected] = connected
        if (email == null) it.remove(Keys.bkEmail) else it[Keys.bkEmail] = email
    }
    suspend fun setBackupSchedule(s: BackupSchedule) = store.edit { it[Keys.bkSchedule] = s.name }
    suspend fun setBackupWifiOnly(v: Boolean) = store.edit { it[Keys.bkWifiOnly] = v }
    suspend fun setBackupChargingOnly(v: Boolean) = store.edit { it[Keys.bkChargingOnly] = v }
    suspend fun setBackupIncludePhotos(v: Boolean) = store.edit { it[Keys.bkIncludePhotos] = v }
    suspend fun setBackupKeepCount(v: Int) = store.edit { it[Keys.bkKeep] = v.coerceIn(1, 50) }

    suspend fun recordBackupStatus(
        status: BackupStatus,
        error: String? = null,
        at: Long? = null,
        photos: BackupPhotoSummary? = null,
    ) = store.edit {
        it[Keys.bkLastStatus] = status.name
        if (error == null) it.remove(Keys.bkLastError) else it[Keys.bkLastError] = error
        if (at != null) it[Keys.bkLastAt] = at
        if (photos != null) {
            it[Keys.bkLastPhotosIncluded] = photos.included
            it[Keys.bkLastPhotoCount] = photos.count
            it[Keys.bkLastPhotosMissing] = photos.missing
        }
    }

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
