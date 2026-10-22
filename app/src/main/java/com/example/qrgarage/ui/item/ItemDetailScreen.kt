package com.example.qrgarage.ui.item

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.qrgarage.data.photos.PhotoStorage
import com.example.qrgarage.data.repo.ItemDetail
import com.example.qrgarage.data.repo.ItemRepository
import com.example.qrgarage.data.repo.LocationRepository
import com.example.qrgarage.data.repo.LocationTree
import com.example.qrgarage.data.repo.displayName
import com.example.qrgarage.ui.UiMessenger
import com.example.qrgarage.ui.components.BackTopBar
import com.example.qrgarage.ui.components.BigButton
import com.example.qrgarage.ui.components.ConfirmDialog
import com.example.qrgarage.ui.components.InfoRow
import com.example.qrgarage.ui.components.LocationPickerDialog
import com.example.qrgarage.ui.components.PhotoImage
import com.example.qrgarage.ui.components.SectionHeader
import com.example.qrgarage.ui.components.Thumbnail
import com.example.qrgarage.ui.components.contentWidth
import com.example.qrgarage.ui.components.formatQty
import com.example.qrgarage.ui.navigation.ItemRoute
import com.example.qrgarage.ui.navigation.PICKED_LOCATION_KEY
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal
import javax.inject.Inject

@HiltViewModel
class ItemDetailViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val items: ItemRepository,
    locations: LocationRepository,
    private val photos: PhotoStorage,
    private val messenger: UiMessenger,
) : ViewModel() {
    val id = savedStateHandle.toRoute<ItemRoute>().id

    /** null until loaded; a missing item is reported as [Loaded] with a null detail. */
    data class Loaded(val detail: ItemDetail?)

    val detail: StateFlow<Loaded?> = items.observeDetail(id)
        .map { Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val tree: StateFlow<LocationTree> =
        locations.tree.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationTree.EMPTY)

    init {
        // HOME-3: viewing an item puts it in "recent".
        viewModelScope.launch { items.markViewed(id) }
        viewModelScope.launch {
            savedStateHandle.getStateFlow<String?>(PICKED_LOCATION_KEY, null).filterNotNull().collect {
                savedStateHandle.remove<String>(PICKED_LOCATION_KEY)
                moveTo(it)
            }
        }
    }

    fun photoFile(path: String): File = photos.fileFor(path)

    /** ITEM-12/14: move with undo. */
    fun moveTo(locationId: String) = viewModelScope.launch {
        val previous = items.move(listOf(id), locationId)
        if (previous.isNotEmpty()) {
            messenger.showUndo("Moved to ${tree.value[locationId]?.displayName ?: "new location"}") { items.undoMove(previous) }
        }
    }

    /** ITEM-13: soft delete with an undo snackbar; the item also stays in Trash. */
    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val name = detail.value?.detail?.item?.name ?: "Item"
        items.softDelete(listOf(id))
        messenger.showUndo("Moved “$name” to trash") { items.restore(listOf(id)) }
        onDone()
    }

    fun lend(borrower: String, dueAt: Long?) = viewModelScope.launch {
        items.lend(id, borrower, System.currentTimeMillis(), dueAt)
    }

    fun markReturned() = viewModelScope.launch { items.markReturned(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenLocation: (String) -> Unit,
    onScanForMove: () -> Unit,
    vm: ItemDetailViewModel = hiltViewModel(),
) {
    val loaded by vm.detail.collectAsStateWithLifecycle()
    val tree by vm.tree.collectAsStateWithLifecycle()
    var moving by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var lending by rememberSaveable { mutableStateOf(false) }
    var viewing by rememberSaveable { mutableStateOf<Int?>(null) }
    val d = loaded?.detail

    LaunchedEffect(loaded) { if (loaded != null && d == null) onBack() }

    Scaffold(
        topBar = {
            BackTopBar(d?.item?.name ?: "", onBack) {
                if (d != null && d.item.deletedAt == null) {
                    IconButton(onClick = { onEdit(d.item.id) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }
            }
        },
    ) { padding ->
        if (d == null) return@Scaffold
        val item = d.item
        // ITEM-17: name, location path, photo, then the details below the photo.
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).contentWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (item.deletedAt != null) {
                Text("This item is in the trash.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }
            Text(
                item.name,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("detail-name"),
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("detail-location"),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Place, null)
                    Column(
                        Modifier.weight(1f).padding(horizontal = 12.dp).clickable { onOpenLocation(item.locationId) },
                    ) {
                        Text("Location", style = MaterialTheme.typography.labelMedium)
                        Text(tree.fullPath(item.locationId), style = MaterialTheme.typography.titleMedium)
                    }
                    if (item.deletedAt == null) {
                        TextButton(onClick = { moving = true }, modifier = Modifier.heightIn(min = 56.dp)) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Move")
                        }
                    }
                }
            }

            // ITEM-16 / PHOTO-4 / PHOTO-5: first photo large, the rest as thumbnails beneath.
            if (d.photos.isNotEmpty()) {
                val files = d.photos.map { vm.photoFile(it.relativePath) }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhotoImage(
                        files.first(),
                        contentDescription = "Photo of ${item.name}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(onClickLabel = "View full screen") { viewing = 0 }
                            .testTag("detail-photo"),
                    )
                    if (files.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            files.drop(1).forEachIndexed { i, f ->
                                Thumbnail(
                                    f, size = 80.dp,
                                    modifier = Modifier.clickable(onClickLabel = "View photo ${i + 2} full screen") { viewing = i + 1 },
                                )
                            }
                        }
                    }
                }
            }

            if (item.description.isNotBlank()) {
                Text(item.description, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            item.quantity?.let { q ->
                val low = item.lowStockThreshold?.let { q <= it } == true
                InfoRow("Quantity", "${formatQty(q)} ${item.unit.orEmpty()}".trim() + if (low) "  · Low stock" else "")
            }
            if (d.tags.isNotEmpty()) {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.tags.forEach { AssistChip(onClick = {}, label = { Text(it.name) }) }
                }
            }
            d.category?.let { InfoRow("Category", it.name) }
            item.purchaseDate?.let { InfoRow("Purchased", formatDate(it)) }
            item.purchasePriceCents?.let { InfoRow("Price", BigDecimal.valueOf(it, 2).toPlainString()) }
            if (item.notes.isNotBlank()) {
                SectionHeader("Notes")
                Text(item.notes, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
            }
            item.lowStockThreshold?.let { InfoRow("Low stock at", formatQty(it)) }

            // ITEM-11: lent-out tracking.
            if (item.deletedAt == null) {
                SectionHeader("Lending")
                val loan = d.activeLoan
                if (loan != null) {
                    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Handshake, null)
                                Text(" Lent to ${loan.borrower}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Text("Since ${DateUtils.formatDateTime(null, loan.lentAt, DateUtils.FORMAT_SHOW_DATE)}")
                            loan.dueAt?.let {
                                val overdue = it < System.currentTimeMillis()
                                Text(
                                    "Due ${formatDate(it)}" + if (overdue) " (overdue)" else "",
                                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            BigButton("Mark returned", null, vm::markReturned, Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { lending = true },
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Icon(Icons.Outlined.Handshake, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Lend out")
                    }
                }
            }

            // ITEM-15: change history.
            if (d.history.isNotEmpty()) {
                SectionHeader("History")
                d.history.forEach { h ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(h.summary, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            DateUtils.getRelativeTimeSpanString(h.timestamp).toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (moving) {
        LocationPickerDialog(
            tree = tree,
            title = "Move to",
            selectedId = d?.item?.locationId,
            onPick = { id -> id?.let(vm::moveTo); moving = false },
            onDismiss = { moving = false },
            onScanInstead = { moving = false; onScanForMove() },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete item?",
            text = "It moves to the trash. You can undo or recover it from Settings → Trash.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { vm.delete(onBack) },
            onDismiss = { confirmDelete = false },
        )
    }
    if (lending) LendDialog(onLend = { b, due -> vm.lend(b, due); lending = false }, onDismiss = { lending = false })
    viewing?.let { start ->
        val photos = d?.photos.orEmpty()
        if (photos.isNotEmpty()) {
            PhotoViewer(photos.map { vm.photoFile(it.relativePath) }, start, d?.item?.name.orEmpty(), onDismiss = { viewing = null })
        }
    }
}

/** PHOTO-5: full-screen photo viewer; swipe between an item's photos. */
@Composable
private fun PhotoViewer(files: List<File>, start: Int, itemName: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start.coerceIn(files.indices)) { files.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                PhotoImage(
                    files[page],
                    contentDescription = "Photo ${page + 1} of ${files.size} of $itemName",
                    contentScale = ContentScale.Fit,
                    containerColor = Color.Black,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            FilledTonalIconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(16.dp).size(56.dp),
            ) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            if (files.size > 1) {
                Text(
                    "${pager.currentPage + 1} / ${files.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LendDialog(onLend: (String, Long?) -> Unit, onDismiss: () -> Unit) {
    var borrower by rememberSaveable { mutableStateOf("") }
    var due by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickingDue by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lend out") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = borrower, onValueChange = { borrower = it },
                    label = { Text("Borrower") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { pickingDue = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Filled.CalendarMonth, null)
                    Spacer(Modifier.width(8.dp))
                    Text(due?.let { "Due ${formatDate(it)}" } ?: "Due date (optional)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onLend(borrower, due) }, enabled = borrower.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Lend") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
    if (pickingDue) {
        val state = rememberDatePickerState(initialSelectedDateMillis = due)
        DatePickerDialog(
            onDismissRequest = { pickingDue = false },
            confirmButton = { TextButton(onClick = { due = state.selectedDateMillis; pickingDue = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickingDue = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}
