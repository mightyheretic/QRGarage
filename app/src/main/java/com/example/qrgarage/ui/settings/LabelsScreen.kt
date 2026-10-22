package com.example.qrgarage.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.qrgarage.data.db.LocationEntity
import com.example.qrgarage.data.repo.LocationRepository
import com.example.qrgarage.data.repo.LocationTree
import com.example.qrgarage.data.repo.displayName
import com.example.qrgarage.data.settings.SettingsRepository
import com.example.qrgarage.qr.LabelContent
import com.example.qrgarage.qr.LabelPdfWriter
import com.example.qrgarage.qr.LabelSheetSpec
import com.example.qrgarage.qr.Paper
import com.example.qrgarage.qr.QrGenerator
import com.example.qrgarage.qr.QrPayload
import com.example.qrgarage.ui.UiMessenger
import com.example.qrgarage.ui.components.BackTopBar
import com.example.qrgarage.ui.components.BigButton
import com.example.qrgarage.ui.components.SectionHeader
import com.example.qrgarage.ui.components.contentWidth
import com.example.qrgarage.ui.components.icon
import com.example.qrgarage.ui.navigation.LabelsRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject

data class CustomGrid(val paper: Paper = Paper.LETTER, val columns: Int = 3, val rows: Int = 5)

@HiltViewModel
class LabelsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    locations: LocationRepository,
    private val settings: SettingsRepository,
    private val messenger: UiMessenger,
) : ViewModel() {
    val tree: StateFlow<LocationTree> =
        locations.tree.stateIn(viewModelScope, SharingStarted.Eagerly, LocationTree.EMPTY)

    val selected = MutableStateFlow(setOfNotNull(savedStateHandle.toRoute<LabelsRoute>().preselectId))
    val custom = MutableStateFlow(CustomGrid())
    private val presetId = MutableStateFlow<String?>(null)

    /** SET-QR-4: the chosen sheet, from a preset or the custom grid. */
    val spec: StateFlow<LabelSheetSpec> = combine(
        settings.settings.map { it.labelPresetId }, presetId, custom,
    ) { saved, chosen, grid ->
        val id = chosen ?: saved
        if (id == CUSTOM) LabelSheetSpec.custom(grid.paper, grid.columns, grid.rows) else LabelSheetSpec.byId(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LabelSheetSpec.DEFAULT)

    private val _print = Channel<File>(Channel.BUFFERED)
    val printJobs = _print.receiveAsFlow()
    private val _share = Channel<Uri>(Channel.BUFFERED)
    val shares = _share.receiveAsFlow()

    fun choosePreset(id: String) {
        presetId.value = id
        if (id != CUSTOM) viewModelScope.launch { settings.setLabelPreset(id) }
    }

    fun toggle(id: String) = selected.update { if (id in it) it - id else it + id }
    fun selectAll() { selected.value = tree.value.byId.keys }
    fun selectNone() { selected.value = emptySet() }

    fun savePdf(uri: Uri) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { LabelPdfWriter.write(labels(), spec.value, it) }
                    ?: error("Cannot write file")
            }
        }.onSuccess { messenger.show("Saved label sheet") }
            .onFailure { messenger.show("Couldn't save PDF: ${it.message}") }
    }

    fun print() = viewModelScope.launch { _print.send(writeTemp()) }

    fun share() = viewModelScope.launch {
        val file = writeTemp()
        _share.send(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
    }

    private suspend fun writeTemp(): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        File(dir, "qr-labels.pdf").also { f -> f.outputStream().use { LabelPdfWriter.write(labels(), spec.value, it) } }
    }

    /** SET-QR-2: each label carries the QR payload, the name and the path printed under it. */
    private fun labels(): List<LabelContent> {
        val t = tree.value
        return t.flatten().map { it.location }.filter { it.id in selected.value }.map { loc ->
            val parentPath = t.path(loc.id).dropLast(1).joinToString(" › ") { it.displayName }
            LabelContent(QrPayload.forLocation(loc.id), loc.displayName, parentPath, loc.code)
        }
    }

    companion object {
        const val CUSTOM = "custom"
    }
}

@Composable
fun LabelsScreen(onBack: () -> Unit, vm: LabelsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val tree by vm.tree.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val spec by vm.spec.collectAsStateWithLifecycle()
    val custom by vm.custom.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<LocationEntity?>(null) }
    var presetMenu by rememberSaveable { mutableStateOf(false) }
    val nodes = remember(tree) { tree.flatten() }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let(vm::savePdf)
    }
    LaunchedEffect(Unit) {
        vm.printJobs.collect { file ->
            val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            pm.print("QR Garage labels", PdfFilePrintAdapter(file), PrintAttributes.Builder().build())
        }
    }
    LaunchedEffect(Unit) {
        vm.shares.collect { uri ->
            val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Share labels"))
        }
    }

    Scaffold(
        topBar = {
            BackTopBar("QR labels", onBack) {
                IconButton(onClick = vm::share, enabled = selected.isNotEmpty(), modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Share, contentDescription = "Share PDF")
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.navigationBarsPadding().contentWidth().padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton(
                        "Save PDF", Icons.Outlined.Save, { save.launch("qr-labels.pdf") }, Modifier.weight(1f),
                        enabled = selected.isNotEmpty(), colors = ButtonDefaults.filledTonalButtonColors(),
                    )
                    BigButton("Print", Icons.Filled.Print, vm::print, Modifier.weight(1f), enabled = selected.isNotEmpty())
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).contentWidth()) {
            item(key = "sheet") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Label sheet", Modifier.padding(start = 0.dp))
                    OutlinedButton(onClick = { presetMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(spec.name, Modifier.weight(1f))
                        DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                            LabelSheetSpec.PRESETS.forEach { p ->
                                DropdownMenuItem(text = { Text(p.name) }, onClick = { vm.choosePreset(p.id); presetMenu = false }, modifier = Modifier.heightIn(min = 52.dp))
                            }
                            DropdownMenuItem(text = { Text("Custom grid…") }, onClick = { vm.choosePreset(LabelsViewModel.CUSTOM); presetMenu = false }, modifier = Modifier.heightIn(min = 52.dp))
                        }
                    }
                    if (spec.id == LabelsViewModel.CUSTOM) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            Paper.entries.forEachIndexed { i, p ->
                                SegmentedButton(
                                    selected = custom.paper == p,
                                    onClick = { vm.custom.update { it.copy(paper = p) } },
                                    shape = SegmentedButtonDefaults.itemShape(i, Paper.entries.size),
                                ) { Text(p.label) }
                            }
                        }
                        Stepper("Columns", custom.columns, 1..6) { v -> vm.custom.update { it.copy(columns = v) } }
                        Stepper("Rows", custom.rows, 1..12) { v -> vm.custom.update { it.copy(rows = v) } }
                    }
                    Text(
                        "${selected.size} selected · ${spec.perSheet} labels per sheet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = vm::selectAll, modifier = Modifier.heightIn(min = 48.dp)) { Text("Select all") }
                        TextButton(onClick = vm::selectNone, modifier = Modifier.heightIn(min = 48.dp)) { Text("Select none") }
                    }
                }
            }
            items(nodes, key = { it.location.id }) { node ->
                val loc = node.location
                ListItem(
                    modifier = Modifier.padding(start = (node.depth * 16).dp).heightIn(min = 64.dp).clickable { vm.toggle(loc.id) },
                    leadingContent = { Checkbox(checked = loc.id in selected, onCheckedChange = { vm.toggle(loc.id) }) },
                    headlineContent = { Text(loc.displayName) },
                    supportingContent = { Text("${loc.kind.label} · ${loc.code}") },
                    trailingContent = {
                        IconButton(onClick = { preview = loc }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.QrCode2, contentDescription = "Show QR for ${loc.displayName}")
                        }
                    },
                )
            }
        }
    }

    preview?.let { loc ->
        val bitmap = remember(loc.id) { QrGenerator.bitmap(QrPayload.forLocation(loc.id), 720).asImageBitmap() }
        AlertDialog(
            onDismissRequest = { preview = null },
            icon = { Icon(loc.kind.icon(), null) },
            title = { Text(loc.displayName) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Image(bitmap, contentDescription = "QR code for ${loc.displayName}", filterQuality = FilterQuality.None, modifier = Modifier.size(240.dp))
                    Text(tree.pathString(loc.id), style = MaterialTheme.typography.bodyMedium)
                    Text("Code ${loc.code}", style = MaterialTheme.typography.titleMedium)
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { onChange((value - 1).coerceIn(range)) }, modifier = Modifier.size(56.dp)) { Text("−") }
        Text("$value", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { onChange((value + 1).coerceIn(range)) }, modifier = Modifier.size(56.dp)) { Text("+") }
    }
}

/** Streams an already-rendered PDF to the Android print framework. */
private class PdfFilePrintAdapter(private val file: File) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) return callback.onLayoutCancelled()
        val info = PrintDocumentInfo.Builder("qr-labels.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>?, destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?, callback: WriteResultCallback,
    ) {
        try {
            FileInputStream(file).use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}
