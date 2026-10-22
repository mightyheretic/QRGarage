package com.example.qrgarage.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.outlined.Garage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.qrgarage.data.db.LocationEntity
import com.example.qrgarage.data.db.LocationKind
import com.example.qrgarage.data.repo.DeleteBlockers
import com.example.qrgarage.data.repo.DuplicateLocationException
import com.example.qrgarage.data.repo.LocationInput
import com.example.qrgarage.data.repo.LocationRepository
import com.example.qrgarage.data.repo.LocationTree
import com.example.qrgarage.data.repo.displayName
import com.example.qrgarage.ui.UiMessenger
import com.example.qrgarage.ui.components.BackTopBar
import com.example.qrgarage.ui.components.ConfirmDialog
import com.example.qrgarage.ui.components.EditLocationDialog
import com.example.qrgarage.ui.components.LocationPickerDialog
import com.example.qrgarage.ui.components.contentWidth
import com.example.qrgarage.ui.components.icon
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocationsViewModel @Inject constructor(
    private val locations: LocationRepository,
    private val messenger: UiMessenger,
) : ViewModel() {
    val tree: StateFlow<LocationTree> =
        locations.tree.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationTree.EMPTY)

    /** Ids and shelf group keys the user collapsed; everything else is shown expanded. */
    val collapsed = MutableStateFlow<Set<String>>(emptySet())

    /** Set when a delete was refused because the location still has contents (LOC-7). */
    val blocked = MutableStateFlow<Pair<LocationEntity, DeleteBlockers>?>(null)

    fun toggle(id: String) = collapsed.update { if (id in it) it - id else it + id }

    fun add(parentId: String?, input: LocationInput) = viewModelScope.launch {
        try {
            locations.create(input, parentId)
            parentId?.let { p -> collapsed.update { it - p } }
        } catch (e: DuplicateLocationException) {
            messenger.show(e.message.orEmpty())
        }
    }

    fun edit(id: String, input: LocationInput) = viewModelScope.launch {
        try {
            locations.update(id, input)
        } catch (e: DuplicateLocationException) {
            messenger.show(e.message.orEmpty())
        }
    }

    fun move(id: String, newParent: String?) = viewModelScope.launch {
        if (!locations.move(id, newParent)) messenger.show("A location can't be moved inside itself")
    }

    fun delete(loc: LocationEntity) = viewModelScope.launch {
        val blockers = locations.deleteBlockers(loc.id)
        if (!blockers.isEmpty) {
            blocked.value = loc to blockers
            return@launch
        }
        locations.softDelete(loc.id)
        messenger.showUndo("Deleted “${loc.displayName}”") { locations.restore(loc.id) }
    }

    fun reassignAndDelete(loc: LocationEntity, targetId: String) = viewModelScope.launch {
        blocked.value = null
        if (locations.reassignAndDelete(loc.id, targetId)) {
            messenger.show("Moved contents to ${tree.value[targetId]?.displayName} and deleted “${loc.displayName}”")
        } else {
            messenger.show("Can't move contents into a location inside “${loc.displayName}”")
        }
    }
}

private sealed interface LocDialog {
    data class Add(val parentId: String?, val kind: LocationKind) : LocDialog
    data class Edit(val loc: LocationEntity) : LocDialog
    data class Move(val loc: LocationEntity) : LocDialog
    data class Delete(val loc: LocationEntity) : LocDialog
}

@Composable
fun LocationsScreen(
    onBack: () -> Unit,
    onOpenLocation: (String) -> Unit,
    onPrintLabel: (String) -> Unit,
    vm: LocationsViewModel = hiltViewModel(),
) {
    val tree by vm.tree.collectAsStateWithLifecycle()
    val collapsed by vm.collapsed.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<LocDialog?>(null) }
    var reassignPicker by rememberSaveable { mutableStateOf(false) }

    val entries = remember(tree, collapsed) { tree.flattenGrouped { it !in collapsed } }
    val singleRoot = tree.roots.singleOrNull()

    Scaffold(
        topBar = { BackTopBar("Locations", onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    dialog = LocDialog.Add(singleRoot?.id, singleRoot?.kind?.childKind ?: LocationKind.GARAGE)
                },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(if (singleRoot != null) "Add to ${singleRoot.displayName}" else "Add location") },
                modifier = Modifier.heightIn(min = 64.dp),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).contentWidth()) {
            items(entries, key = { it.key }) { entry ->
                val loc = entry.location
                val isOpen = entry.key !in collapsed
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    // Indent inside the leading slot so every row's container spans the full width.
                    modifier = Modifier
                        .heightIn(min = 64.dp)
                        .clickable { if (loc != null) menu = true else vm.toggle(entry.key) },
                    leadingContent = {
                        Box(Modifier.padding(start = (entry.depth * 20).dp)) {
                            if (entry.expandable) {
                                IconButton(onClick = { vm.toggle(entry.key) }, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        if (isOpen) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                                        contentDescription = if (isOpen) "Collapse ${entry.label}" else "Expand ${entry.label}",
                                    )
                                }
                            } else {
                                Box(Modifier.size(48.dp).padding(12.dp)) { Icon((loc?.kind ?: LocationKind.SHELF).icon(), null) }
                            }
                        }
                    },
                    headlineContent = { Text(entry.label) },
                    supportingContent = {
                        Text(if (loc != null) "${loc.kind.label} · ${loc.code}" else "${entry.members.size} locations")
                    },
                    trailingContent = {
                        if (loc != null) Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${loc.displayName}")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                MenuRow("Open", Icons.AutoMirrored.Filled.OpenInNew) { menu = false; onOpenLocation(loc.id) }
                                MenuRow("Add inside", Icons.Filled.CreateNewFolder) { menu = false; dialog = LocDialog.Add(loc.id, loc.kind.childKind) }
                                MenuRow("Rename / change type", Icons.Filled.Edit) { menu = false; dialog = LocDialog.Edit(loc) }
                                MenuRow("Move", Icons.AutoMirrored.Filled.DriveFileMove) { menu = false; dialog = LocDialog.Move(loc) }
                                MenuRow("Print label", Icons.Filled.QrCode2) { menu = false; onPrintLabel(loc.id) }
                                MenuRow("Delete", Icons.Filled.Delete) { menu = false; dialog = LocDialog.Delete(loc) }
                            }
                        }
                    },
                )
            }
            item(key = "add-root") {
                TextButton(
                    onClick = { dialog = LocDialog.Add(null, LocationKind.GARAGE) },
                    modifier = Modifier.padding(16.dp).heightIn(min = 56.dp),
                ) {
                    Icon(Icons.Outlined.Garage, null)
                    Text("  Add a separate garage or site")
                }
            }
            item(key = "space") { Spacer(Modifier.height(96.dp)) }
        }
    }

    when (val d = dialog) {
        is LocDialog.Add -> EditLocationDialog(
            title = d.parentId?.let { "Add inside ${tree[it]?.displayName}" } ?: "Add top-level location",
            initialKind = d.kind,
            suggestedWheels = remember(d) { tree.suggestWheels(d.parentId, d.kind) },
            isTaken = { tree.labelTaken(d.parentId, it) },
            onSave = { vm.add(d.parentId, it) },
            onDismiss = { dialog = null },
        )
        is LocDialog.Edit -> EditLocationDialog(
            title = "Edit location", initialKind = d.loc.kind, existing = d.loc,
            isTaken = { tree.labelTaken(d.loc.parentId, it, excludeId = d.loc.id) },
            onSave = { vm.edit(d.loc.id, it) },
            onDismiss = { dialog = null },
        )
        is LocDialog.Move -> LocationPickerDialog(
            tree = tree, title = "Move “${d.loc.displayName}” into",
            excluded = setOf(d.loc.id), allowTopLevel = true, selectedId = d.loc.parentId,
            onPick = { vm.move(d.loc.id, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is LocDialog.Delete -> ConfirmDialog(
            title = "Delete “${d.loc.displayName}”?",
            text = "It moves to the trash. Its printed label stops working until it's restored.",
            confirmLabel = "Delete", destructive = true,
            onConfirm = { vm.delete(d.loc) },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }

    blocked?.let { (loc, b) ->
        if (reassignPicker) {
            LocationPickerDialog(
                tree = tree, title = "Move contents of “${loc.displayName}” to",
                excluded = setOf(loc.id), selectedId = loc.parentId,
                onPick = { target -> reassignPicker = false; target?.let { vm.reassignAndDelete(loc, it) } },
                onDismiss = { reassignPicker = false },
            )
        } else {
            AlertDialog(
                onDismissRequest = { vm.blocked.value = null },
                title = { Text("“${loc.displayName}” isn't empty") },
                text = {
                    Text(
                        buildString {
                            append("It still contains ")
                            if (b.itemCount > 0) append("${b.itemCount} item${if (b.itemCount == 1) "" else "s"}")
                            if (b.itemCount > 0 && b.childCount > 0) append(" and ")
                            if (b.childCount > 0) append("${b.childCount} sublocation${if (b.childCount == 1) "" else "s"}")
                            append(". Choose where to move them before deleting.")
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { reassignPicker = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose destination") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.blocked.value = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun MenuRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, null) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 52.dp),
    )
}
