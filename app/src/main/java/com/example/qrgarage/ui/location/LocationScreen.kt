package com.example.qrgarage.ui.location

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.qrgarage.data.db.ItemRow
import com.example.qrgarage.data.db.LocationEntity
import com.example.qrgarage.data.photos.PhotoStorage
import com.example.qrgarage.data.repo.DuplicateLocationException
import com.example.qrgarage.data.repo.ItemRepository
import com.example.qrgarage.data.repo.LocationInput
import com.example.qrgarage.data.repo.LocationRepository
import com.example.qrgarage.data.repo.LocationTree
import com.example.qrgarage.data.repo.displayName
import com.example.qrgarage.data.settings.SettingsRepository
import com.example.qrgarage.ui.UiMessenger
import com.example.qrgarage.ui.components.BackTopBar
import com.example.qrgarage.ui.components.BigButton
import com.example.qrgarage.ui.components.EditLocationDialog
import com.example.qrgarage.ui.components.EmptyState
import com.example.qrgarage.ui.components.ItemRowView
import com.example.qrgarage.ui.components.SectionHeader
import com.example.qrgarage.ui.components.contentWidth
import com.example.qrgarage.ui.components.icon
import com.example.qrgarage.ui.navigation.LocationRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class LocationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    items: ItemRepository,
    private val locations: LocationRepository,
    private val settings: SettingsRepository,
    private val photos: PhotoStorage,
    private val messenger: UiMessenger,
) : ViewModel() {
    val id = savedStateHandle.toRoute<LocationRoute>().id

    val location: StateFlow<LocationEntity?> =
        locations.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val tree: StateFlow<LocationTree> =
        locations.tree.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationTree.EMPTY)
    val items: StateFlow<List<ItemRow>> =
        items.observeInLocation(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val isCurrent: StateFlow<Boolean> = settings.currentLocationId.map { it == id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun photoFile(path: String): File = photos.fileFor(path)

    fun setCurrent() = viewModelScope.launch { settings.setCurrentLocation(id) }

    fun addChild(input: LocationInput) = viewModelScope.launch {
        try {
            locations.create(input, id)
        } catch (e: DuplicateLocationException) {
            messenger.show(e.message.orEmpty())
        }
    }
}

@Composable
fun LocationScreen(
    onBack: () -> Unit,
    onOpenLocation: (String) -> Unit,
    onOpenItem: (String) -> Unit,
    onAddItemHere: (String) -> Unit,
    onMoveItemsHere: (String) -> Unit,
    onPrintLabel: (String) -> Unit,
    vm: LocationViewModel = hiltViewModel(),
) {
    val location by vm.location.collectAsStateWithLifecycle()
    val tree by vm.tree.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val isCurrent by vm.isCurrent.collectAsStateWithLifecycle()
    var addingChild by rememberSaveable { mutableStateOf(false) }
    val loc = location

    Scaffold(
        topBar = {
            BackTopBar(loc?.displayName ?: "Location", onBack) {
                if (loc != null) {
                    IconButton(onClick = { onPrintLabel(loc.id) }) {
                        Icon(Icons.Filled.QrCode2, contentDescription = "Print label")
                    }
                }
            }
        },
    ) { padding ->
        if (loc == null || loc.deletedAt != null) {
            EmptyState(Icons.Outlined.Inventory2, "Location not found", "It may have been deleted.", Modifier.padding(padding))
            return@Scaffold
        }
        val children = tree.children(loc.id)
        LazyColumn(Modifier.fillMaxSize().padding(padding).contentWidth()) {
            item(key = "path") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        tree.path(loc.id).forEachIndexed { i, seg ->
                            if (i > 0) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { if (seg.id != loc.id) onOpenLocation(seg.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(seg.displayName)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(loc.kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("${loc.kind.label} · code ${loc.code}", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        if (isCurrent) {
                            AssistChip(onClick = {}, label = { Text("Current location") }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) })
                        } else {
                            TextButton(onClick = vm::setCurrent, modifier = Modifier.heightIn(min = 48.dp)) { Text("Make current") }
                        }
                    }
                }
            }
            item(key = "actions") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton("Add item here", Icons.Filled.Add, { onAddItemHere(loc.id) }, Modifier.fillMaxWidth())
                    BigButton(
                        "Move items here", Icons.AutoMirrored.Filled.DriveFileMove, { onMoveItemsHere(loc.id) },
                        Modifier.fillMaxWidth(), colors = ButtonDefaults.filledTonalButtonColors(),
                    )
                }
            }
            item(key = "sub-header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("Inside ${loc.displayName}", Modifier.weight(1f))
                    TextButton(onClick = { addingChild = true }, modifier = Modifier.heightIn(min = 48.dp).padding(end = 8.dp)) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                        Text("  Add ${loc.kind.childKind.label.lowercase()}")
                    }
                }
            }
            items(children, key = { "c-" + it.id }) { child ->
                val count = tree.children(child.id).size
                ListItem(
                    modifier = Modifier.clickable { onOpenLocation(child.id) }.heightIn(min = 64.dp),
                    leadingContent = { Icon(child.kind.icon(), contentDescription = null) },
                    headlineContent = { Text(child.displayName) },
                    supportingContent = { Text(child.kind.label + if (count > 0) " · $count inside" else "") },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                )
            }
            item(key = "items-header") { SectionHeader("Items (${items.size})") }
            if (items.isEmpty()) {
                item(key = "items-empty") {
                    Text(
                        "No items directly in this location.",
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(items, key = { "i-" + it.id }) { row ->
                ItemRowView(row, path = null, photoFile = vm::photoFile, onClick = { onOpenItem(row.id) })
            }
            item(key = "bottom") { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (addingChild && loc != null) {
        EditLocationDialog(
            title = "Add inside ${loc.displayName}",
            initialKind = loc.kind.childKind,
            suggestedWheels = remember(tree) { tree.suggestWheels(loc.id, loc.kind.childKind) },
            isTaken = { tree.labelTaken(loc.id, it) },
            onSave = vm::addChild,
            onDismiss = { addingChild = false },
        )
    }
}
