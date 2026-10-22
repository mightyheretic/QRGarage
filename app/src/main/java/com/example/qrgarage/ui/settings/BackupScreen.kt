package com.example.qrgarage.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.qrgarage.backup.BackupManager
import com.example.qrgarage.backup.BackupScheduler
import com.example.qrgarage.backup.DriveAuth
import com.example.qrgarage.backup.DriveClient
import com.example.qrgarage.backup.RemoteBackup
import com.example.qrgarage.backup.RestoreReport
import com.example.qrgarage.data.settings.BackupSchedule
import com.example.qrgarage.data.settings.BackupSettings
import com.example.qrgarage.data.settings.BackupStatus
import com.example.qrgarage.data.settings.SettingsRepository
import com.example.qrgarage.ui.UiMessenger
import com.example.qrgarage.ui.components.BackTopBar
import com.example.qrgarage.ui.components.BigButton
import com.example.qrgarage.ui.components.ConfirmDialog
import com.example.qrgarage.ui.components.SectionHeader
import com.example.qrgarage.ui.components.contentWidth
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface RestoreListState {
    data object Hidden : RestoreListState
    data object Loading : RestoreListState
    data class Loaded(val backups: List<RemoteBackup>) : RestoreListState
    data class Error(val message: String) : RestoreListState
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val auth: DriveAuth,
    private val drive: DriveClient,
    private val backups: BackupManager,
    private val scheduler: BackupScheduler,
    private val messenger: UiMessenger,
) : ViewModel() {
    val state: StateFlow<BackupSettings> =
        settings.settings.map { it.backup }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupSettings())
    val running: StateFlow<Boolean> =
        scheduler.manualRunning.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val connecting = MutableStateFlow(false)
    val restoreList = MutableStateFlow<RestoreListState>(RestoreListState.Hidden)
    val restoring = MutableStateFlow(false)
    /** BK-9: the last restore's photo audit, until the user dismisses it. */
    val restoreReport = MutableStateFlow(RestoreReport.read(context))

    fun dismissRestoreReport() {
        RestoreReport.dismiss(context)
        restoreReport.value = null
    }

    private val _consent = Channel<IntentSenderRequest>(Channel.BUFFERED)
    val consentRequests = _consent.receiveAsFlow()
    private val _restart = Channel<Unit>(Channel.CONFLATED)
    val restartRequests = _restart.receiveAsFlow()

    /** SET-BK-1 */
    fun connect() = viewModelScope.launch {
        connecting.value = true
        runCatching { auth.authorize() }
            .onSuccess { handle(it) }
            .onFailure { fail(it) }
    }

    fun onConsentResult(data: Intent?) = viewModelScope.launch {
        runCatching { auth.resultFromIntent(data) }
            .onSuccess { handle(it) }
            .onFailure { fail(it) }
    }

    private suspend fun handle(outcome: DriveAuth.Outcome) {
        when (outcome) {
            is DriveAuth.Outcome.NeedsConsent -> _consent.send(IntentSenderRequest.Builder(outcome.pendingIntent).build())
            is DriveAuth.Outcome.Authorized -> {
                val email = runCatching { drive.accountEmail(outcome.accessToken) }.getOrNull()
                settings.setBackupConnected(true, email)
                scheduler.apply(settings.settings.first().backup)
                connecting.value = false
                messenger.show("Google Drive connected")
            }
        }
    }

    private fun fail(t: Throwable) {
        connecting.value = false
        messenger.show(describe(t))
    }

    fun disconnect() = viewModelScope.launch {
        val token = (runCatching { auth.authorize() }.getOrNull() as? DriveAuth.Outcome.Authorized)?.accessToken
        auth.revoke(state.value.accountEmail, token)
        settings.setBackupConnected(false, null)
        scheduler.apply(settings.settings.first().backup)
        messenger.show("Disconnected from Google Drive")
    }

    fun backUpNow() = scheduler.backUpNow()

    fun setSchedule(s: BackupSchedule) = update { settings.setBackupSchedule(s) }
    fun setWifiOnly(v: Boolean) = update { settings.setBackupWifiOnly(v) }
    fun setChargingOnly(v: Boolean) = update { settings.setBackupChargingOnly(v) }
    fun setIncludePhotos(v: Boolean) = update { settings.setBackupIncludePhotos(v) }
    fun setKeep(v: Int) = update { settings.setBackupKeepCount(v) }

    private fun update(block: suspend () -> Unit) = viewModelScope.launch {
        block()
        scheduler.apply(settings.settings.first().backup)
    }

    /** SET-BK-6 */
    fun loadBackups() = viewModelScope.launch {
        restoreList.value = RestoreListState.Loading
        restoreList.value = runCatching { backups.listBackups() }
            .fold({ RestoreListState.Loaded(it) }, { RestoreListState.Error(describe(it)) })
    }

    fun hideBackups() { restoreList.value = RestoreListState.Hidden }

    fun restore(backup: RemoteBackup) = viewModelScope.launch {
        restoring.value = true
        runCatching { backups.stageRestore(backup.fileId) }
            .onSuccess { _restart.send(Unit) }
            .onFailure {
                restoring.value = false
                restoreList.value = RestoreListState.Hidden
                messenger.show(describe(it))
            }
    }

    private fun describe(t: Throwable): String = when {
        t is ApiException && t.statusCode == CommonStatusCodes.DEVELOPER_ERROR ->
            "Google sign-in isn't configured for this build (OAuth client missing). See README."
        t is ApiException && t.statusCode == CommonStatusCodes.CANCELED -> "Sign-in cancelled"
        t is ApiException && t.statusCode == CommonStatusCodes.NETWORK_ERROR -> "No network connection"
        t is java.net.UnknownHostException -> "No network connection"
        else -> t.message ?: t.javaClass.simpleName
    }
}

@Composable
fun BackupScreen(onBack: () -> Unit, vm: BackupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val s by vm.state.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val connecting by vm.connecting.collectAsStateWithLifecycle()
    val list by vm.restoreList.collectAsStateWithLifecycle()
    val restoring by vm.restoring.collectAsStateWithLifecycle()
    val report by vm.restoreReport.collectAsStateWithLifecycle()
    var confirmRestore by remember { mutableStateOf<RemoteBackup?>(null) }
    var pendingSchedule by remember { mutableStateOf<BackupSchedule?>(null) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.onConsentResult(r.data) else vm.connecting.value = false
    }
    LaunchedEffect(Unit) { vm.consentRequests.collect { consent.launch(it) } }
    LaunchedEffect(Unit) { vm.restartRequests.collect { restartApp(context) } }

    // NFR-3: notifications are requested only when the user turns on scheduled backups.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingSchedule?.let(vm::setSchedule)
        pendingSchedule = null
    }
    val chooseSchedule: (BackupSchedule) -> Unit = { schedule ->
        val needsPrompt = schedule != BackupSchedule.OFF && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPrompt) {
            pendingSchedule = schedule
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else vm.setSchedule(schedule)
    }

    Scaffold(topBar = { BackTopBar("Backup", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).contentWidth().padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // BK-6: backup is not sync.
            Card(Modifier.padding(16.dp).fillMaxWidth()) {
                Row(Modifier.padding(16.dp)) {
                    Icon(Icons.Outlined.Info, null)
                    Text(
                        "Backups restore a whole snapshot. They are not sync: restoring replaces everything on this device. " +
                            "Backups are stored in a hidden app folder in your own Google Drive.",
                        Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            report?.let { RestoreReportCard(it, onDismiss = vm::dismissRestoreReport) }

            ListItem(
                leadingContent = { Icon(if (s.connected) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff, null) },
                headlineContent = { Text(if (s.connected) "Connected to Google Drive" else "Not connected") },
                supportingContent = s.accountEmail?.let { { Text(it) } },
            )
            Box(Modifier.padding(horizontal = 16.dp)) {
                if (s.connected) {
                    OutlinedButton(onClick = vm::disconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Disconnect") }
                } else {
                    BigButton(
                        if (connecting) "Connecting…" else "Connect Google Drive", null, vm::connect,
                        Modifier.fillMaxWidth(), enabled = !connecting,
                    )
                }
            }

            if (s.connected) {
                SectionHeader("Back up")
                BigButton(
                    if (running || s.lastStatus == BackupStatus.RUNNING) "Backing up…" else "Back up now", null, vm::backUpNow,
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(), enabled = !running,
                )
                val status = when (s.lastStatus) {
                    BackupStatus.NEVER -> "No backups yet"
                    BackupStatus.RUNNING -> "Backup in progress"
                    BackupStatus.SUCCESS -> "Last backup succeeded"
                    BackupStatus.FAILED -> "Last attempt failed: ${s.lastError ?: "unknown error"}"
                }
                ListItem(
                    leadingContent = { if (s.lastStatus == BackupStatus.FAILED) Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text(status) },
                    supportingContent = s.lastBackupAt?.let { at ->
                        {
                            Column {
                                Text("Last successful backup: " + DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME))
                                // BK-8: photo count and whether photos were included.
                                s.lastPhotos?.let { p ->
                                    Text(
                                        when {
                                            !p.included -> "Photos not included"
                                            else -> "${p.count} photo${if (p.count == 1) "" else "s"} included"
                                        },
                                        modifier = Modifier.testTag("backup-photo-status"),
                                    )
                                    if (p.included && p.missing > 0) {
                                        Text(
                                            "${p.missing} photo${if (p.missing == 1) " was" else "s were"} already missing on this device and couldn't be backed up",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    },
                )

                SectionHeader("Schedule")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    BackupSchedule.entries.forEachIndexed { i, sch ->
                        SegmentedButton(
                            selected = s.schedule == sch, onClick = { chooseSchedule(sch) },
                            shape = SegmentedButtonDefaults.itemShape(i, BackupSchedule.entries.size),
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) { Text(sch.label) }
                    }
                }
                SwitchRow("Wi-Fi only", "Wait for an unmetered network", s.wifiOnly, vm::setWifiOnly)
                SwitchRow("Only while charging", null, s.chargingOnly, vm::setChargingOnly)
                SwitchRow("Include photos", "Larger backups, but photos come back on restore", s.includePhotos, vm::setIncludePhotos)
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Backups to keep", style = MaterialTheme.typography.bodyLarge)
                        Text("Older ones are deleted from Drive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { vm.setKeep(s.keepCount - 1) }, modifier = Modifier.size(56.dp)) { Text("−") }
                    Text("${s.keepCount}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { vm.setKeep(s.keepCount + 1) }, modifier = Modifier.size(56.dp)) { Text("+") }
                }

                SectionHeader("Restore")
                BigButton(
                    "Restore from a backup", Icons.Outlined.Restore, vm::loadBackups,
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(), colors = ButtonDefaults.filledTonalButtonColors(),
                )
            }
        }
    }

    when (val l = list) {
        RestoreListState.Hidden -> Unit
        else -> AlertDialog(
            onDismissRequest = { if (!restoring) vm.hideBackups() },
            title = { Text(if (restoring) "Restoring…" else "Choose a backup") },
            text = {
                when {
                    restoring -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Text("Downloading and checking the backup. The app restarts when it's ready.", Modifier.padding(start = 16.dp))
                    }
                    l is RestoreListState.Loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    l is RestoreListState.Error -> Text(l.message)
                    l is RestoreListState.Loaded && l.backups.isEmpty() -> Text("No backups found in this Google account.")
                    l is RestoreListState.Loaded -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(l.backups, key = { it.fileId }) { b ->
                            ListItem(
                                modifier = Modifier.heightIn(min = 72.dp).clickable(enabled = b.isCompatible) { confirmRestore = b },
                                headlineContent = { Text(DateUtils.formatDateTime(context, b.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)) },
                                supportingContent = {
                                    Text(
                                        buildString {
                                            append(b.deviceName)
                                            if (b.isThisDevice) append(" (this device)")
                                            b.itemCount?.let { append(" · $it items") }
                                            when {
                                                b.includesPhotos == false -> append(" · no photos")
                                                b.photoCount != null -> append(" · ${b.photoCount} photo${if (b.photoCount == 1) "" else "s"}")
                                            }
                                            b.sizeBytes?.let { append(" · " + Formatter.formatShortFileSize(context, it)) }
                                            if (!b.isCompatible) append("\nMade by a newer app version. Update QR Garage to restore it.")
                                        },
                                        color = if (b.isCompatible) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                    )
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!restoring) TextButton(onClick = vm::hideBackups, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
            },
        )
    }
    confirmRestore?.let { b ->
        ConfirmDialog(
            title = "Replace all data?",
            text = "Everything on this device will be replaced by the backup from " +
                DateUtils.formatDateTime(context, b.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME) +
                ". Your current data is kept until the restore succeeds.",
            confirmLabel = "Restore",
            destructive = true,
            onConfirm = { vm.restore(b) },
            onDismiss = { confirmRestore = null },
        )
    }
}

/** BK-9: lists photos that a restore could not bring back. */
@Composable
private fun RestoreReportCard(report: RestoreReport, onDismiss: () -> Unit) {
    val problem = report.missing.isNotEmpty()
    Card(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().testTag("restore-report"),
        colors = if (problem) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (problem) Icons.Outlined.ErrorOutline else Icons.Outlined.CloudDone, null)
                Text("Last restore", Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.titleMedium)
            }
            Text(report.summary, style = MaterialTheme.typography.bodyMedium)
            if (problem) {
                Text("Missing photos:", style = MaterialTheme.typography.labelLarge)
                report.missing.take(20).forEach { Text("• ${it.itemName}", style = MaterialTheme.typography.bodyMedium) }
                if (report.missing.size > 20) Text("…and ${report.missing.size - 20} more", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Their items are intact; open each item and add a new photo, or restore a backup made with “Include photos” on.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text("Dismiss") }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.heightIn(min = 64.dp).clickable { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

/** Restarts the process so the staged restore is swapped in before the database reopens (BK-4). */
fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    val restart = Intent.makeRestartActivityTask(launch.component)
    context.startActivity(restart)
    Runtime.getRuntime().exit(0)
}
