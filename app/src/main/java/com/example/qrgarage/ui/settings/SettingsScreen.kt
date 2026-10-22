package com.example.qrgarage.ui.settings

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.qrgarage.BuildConfig
import com.example.qrgarage.data.repo.ExportRepository
import com.example.qrgarage.data.repo.ImportRepository
import com.example.qrgarage.data.settings.AppSettings
import com.example.qrgarage.data.settings.BackupStatus
import com.example.qrgarage.data.settings.SettingsRepository
import com.example.qrgarage.data.settings.ThemeMode
import com.example.qrgarage.ui.UiMessenger
import com.example.qrgarage.ui.components.BackTopBar
import com.example.qrgarage.ui.components.SectionHeader
import com.example.qrgarage.ui.components.contentWidth
import com.example.qrgarage.ui.components.formatQty
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val exporter: ExportRepository,
    private val importer: ImportRepository,
    private val messenger: UiMessenger,
) : ViewModel() {
    val state: StateFlow<AppSettings> =
        settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settings.setTheme(mode) }
    fun setDefaultUnit(unit: String) = viewModelScope.launch { settings.setDefaultUnit(unit) }
    fun setDefaultLowStock(value: Double?) = viewModelScope.launch { settings.setDefaultLowStock(value) }

    fun export(format: ExportRepository.Format, uri: Uri) = viewModelScope.launch {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { exporter.export(format, it) } ?: error("Cannot write file")
        }.onSuccess { messenger.show("Exported $it items") }
            .onFailure { messenger.show("Export failed: ${it.message}") }
    }

    /** SET-OTH-2 / SET-OTH-6: adds items from an export ZIP, CSV or JSON file. */
    fun import(uri: Uri) = viewModelScope.launch {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { importer.import(it, context.cacheDir) } ?: error("Cannot read file")
        }.onSuccess { r ->
            messenger.show(
                buildString {
                    append("Imported ${r.imported} item${if (r.imported == 1) "" else "s"}")
                    if (r.photos > 0) append(" with ${r.photos} photo${if (r.photos == 1) "" else "s"}")
                    if (r.skipped > 0) append(" · ${r.skipped} skipped (already here)")
                    if (r.missingPhotos > 0) append(" · ${r.missingPhotos} photo${if (r.missingPhotos == 1) "" else "s"} missing from the file")
                }
            )
        }.onFailure { messenger.show("Import failed: ${it.message}") }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLocations: () -> Unit,
    onLabels: () -> Unit,
    onBackup: () -> Unit,
    onTrash: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    var editUnit by rememberSaveable { mutableStateOf(false) }
    var editLowStock by rememberSaveable { mutableStateOf(false) }

    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportRepository.Format.CSV.mime)) { uri ->
        uri?.let { vm.export(ExportRepository.Format.CSV, it) }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportRepository.Format.JSON.mime)) { uri ->
        uri?.let { vm.export(ExportRepository.Format.JSON, it) }
    }
    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportRepository.Format.ZIP.mime)) { uri ->
        uri?.let { vm.export(ExportRepository.Format.ZIP, it) }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::import)
    }

    Scaffold(topBar = { BackTopBar("Settings", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).contentWidth()) {
            SectionHeader("Organize")
            NavRow(Icons.Outlined.Place, "Locations", "Zones, shelf units, shelves and bins", onLocations)
            NavRow(Icons.Outlined.QrCode2, "QR labels", "Print or re-print location labels", onLabels)
            NavRow(Icons.Outlined.DeleteOutline, "Trash", "Recover or permanently delete", onTrash)

            SectionHeader("Backup")
            val b = s.backup
            val backupSummary = when {
                !b.connected -> "Not connected"
                b.lastStatus == BackupStatus.FAILED -> "Last backup failed"
                b.lastBackupAt != null -> "Last backup " + DateUtils.getRelativeTimeSpanString(b.lastBackupAt)
                else -> "Connected · no backups yet"
            }
            NavRow(Icons.Outlined.CloudUpload, "Google Drive backup", backupSummary, onBackup)

            SectionHeader("Defaults for new items")
            NavRow(Icons.Outlined.Straighten, "Default unit", s.defaultUnit) { editUnit = true }
            NavRow(Icons.AutoMirrored.Outlined.TrendingDown, "Default low-stock threshold", s.defaultLowStock?.let(::formatQty) ?: "None") { editLowStock = true }

            SectionHeader("Theme")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = s.theme == mode,
                        onClick = { vm.setTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) { Text(mode.label) }
                }
            }

            SectionHeader("Export and import")
            val stamp = LocalDate.now().toString()
            NavRow(Icons.Outlined.FolderZip, "Export with photos (ZIP)", "Items as CSV and JSON plus a photos folder") {
                exportZip.launch("garage-$stamp.zip")
            }
            NavRow(Icons.Outlined.FileDownload, "Export to CSV", "Spreadsheet of all items with locations") {
                exportCsv.launch("garage-$stamp.csv")
            }
            NavRow(Icons.Outlined.FileDownload, "Export to JSON", "Machine-readable copy of all items") {
                exportJson.launch("garage-$stamp.json")
            }
            NavRow(Icons.Outlined.FileUpload, "Import", "Add items from an export ZIP, CSV or JSON file") {
                importFile.launch(arrayOf("application/zip", "text/csv", "text/comma-separated-values", "application/json", "text/plain"))
            }

            SectionHeader("About")
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Inventory2, null) },
                headlineContent = { Text("QR Garage ${BuildConfig.VERSION_NAME}") },
                supportingContent = { Text("Works offline. No analytics or tracking; data leaves this device only through your own Drive backup.") },
            )
        }
    }

    if (editUnit) {
        TextValueDialog("Default unit", s.defaultUnit, KeyboardType.Text, onSave = { vm.setDefaultUnit(it) }, onDismiss = { editUnit = false })
    }
    if (editLowStock) {
        TextValueDialog(
            "Default low-stock threshold", s.defaultLowStock?.let(::formatQty).orEmpty(), KeyboardType.Decimal,
            hint = "Leave empty for none",
            onSave = { vm.setDefaultLowStock(it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v >= 0 }) },
            onDismiss = { editLowStock = false },
        )
    }
}

@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).heightIn(min = 64.dp),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )
}

@Composable
private fun TextValueDialog(
    title: String,
    initial: String,
    keyboardType: KeyboardType,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    hint: String? = null,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, singleLine = true,
                supportingText = hint?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value.trim()); onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}
