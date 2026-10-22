package com.example.qrgarage.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.qrgarage.data.db.ItemRow
import com.example.qrgarage.data.db.LocationEntity
import com.example.qrgarage.data.photos.PhotoStorage
import com.example.qrgarage.data.repo.ItemRepository
import com.example.qrgarage.data.repo.LocationRepository
import com.example.qrgarage.data.repo.displayName
import com.example.qrgarage.ui.UiMessenger
import com.example.qrgarage.ui.components.BackTopBar
import com.example.qrgarage.ui.components.ConfirmDialog
import com.example.qrgarage.ui.components.EmptyState
import com.example.qrgarage.ui.components.SectionHeader
import com.example.qrgarage.ui.components.Thumbnail
import com.example.qrgarage.ui.components.contentWidth
import com.example.qrgarage.ui.components.icon
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val items: ItemRepository,
    private val locations: LocationRepository,
    private val photos: PhotoStorage,
    private val messenger: UiMessenger,
) : ViewModel() {
    val trashedItems: StateFlow<List<ItemRow>> =
        items.observeTrash().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trashedLocations: StateFlow<List<LocationEntity>> =
        locations.observeTrash().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun photoFile(path: String) = photos.fileFor(path)

    fun restoreItem(row: ItemRow) = viewModelScope.launch {
        items.restore(listOf(row.id))
        messenger.show("Restored “${row.name}”")
    }

    fun restoreLocation(loc: LocationEntity) = viewModelScope.launch {
        locations.restore(loc.id)
        messenger.show("Restored “${loc.displayName}”")
    }

    fun deleteItem(row: ItemRow) = viewModelScope.launch { items.deletePermanently(row.id) }

    fun deleteLocation(loc: LocationEntity) = viewModelScope.launch {
        locations.deletePermanently(loc.id, items::deletePermanently)
    }

    fun emptyTrash() = viewModelScope.launch {
        trashedItems.value.forEach { items.deletePermanently(it.id) }
        trashedLocations.value.forEach { locations.deletePermanently(it.id, items::deletePermanently) }
        messenger.show("Trash emptied")
    }
}

@Composable
fun TrashScreen(onBack: () -> Unit, vm: TrashViewModel = hiltViewModel()) {
    val items by vm.trashedItems.collectAsStateWithLifecycle()
    val locations by vm.trashedLocations.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    Scaffold(
        topBar = {
            BackTopBar("Trash", onBack) {
                if (items.isNotEmpty() || locations.isNotEmpty()) {
                    TextButton(
                        onClick = { confirm = Confirm("Permanently delete everything in the trash?") { vm.emptyTrash() } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Empty") }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).contentWidth()) {
            if (items.isEmpty() && locations.isEmpty()) {
                item { EmptyState(Icons.Outlined.DeleteOutline, "Trash is empty", "Deleted items and locations show up here.") }
            }
            if (items.isNotEmpty()) item { SectionHeader("Items") }
            items(items, key = { "i-" + it.id }) { row ->
                ListItem(
                    modifier = Modifier.heightIn(min = 72.dp),
                    leadingContent = { Thumbnail(row.thumbnailPath?.let(vm::photoFile)) },
                    headlineContent = { Text(row.name) },
                    supportingContent = { row.deletedAt?.let { Text("Deleted " + DateUtils.getRelativeTimeSpanString(it)) } },
                    trailingContent = {
                        RowActions(
                            onRestore = { vm.restoreItem(row) },
                            onDelete = { confirm = Confirm("Permanently delete “${row.name}” and its photos?") { vm.deleteItem(row) } },
                        )
                    },
                )
            }
            if (locations.isNotEmpty()) item { SectionHeader("Locations") }
            items(locations, key = { "l-" + it.id }) { loc ->
                ListItem(
                    modifier = Modifier.heightIn(min = 72.dp),
                    leadingContent = { Icon(loc.kind.icon(), null) },
                    headlineContent = { Text(loc.displayName) },
                    supportingContent = {
                        Text("${loc.kind.label} · ${loc.code}" + (loc.deletedAt?.let { " · deleted " + DateUtils.getRelativeTimeSpanString(it) } ?: ""))
                    },
                    trailingContent = {
                        RowActions(
                            onRestore = { vm.restoreLocation(loc) },
                            onDelete = {
                                confirm = Confirm("Permanently delete “${loc.displayName}”? Its printed label will stop working for good.") {
                                    vm.deleteLocation(loc)
                                }
                            },
                        )
                    },
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    confirm?.let { (text, action) ->
        ConfirmDialog(
            title = "Delete forever?", text = text, confirmLabel = "Delete forever", destructive = true,
            onConfirm = action, onDismiss = { confirm = null },
        )
    }
}

private data class Confirm(val text: String, val action: () -> Unit)

@Composable
private fun RowActions(onRestore: () -> Unit, onDelete: () -> Unit) {
    Row {
        IconButton(onClick = onRestore, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.RestoreFromTrash, contentDescription = "Restore")
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.DeleteForever, contentDescription = "Delete forever", tint = MaterialTheme.colorScheme.error)
        }
    }
}
