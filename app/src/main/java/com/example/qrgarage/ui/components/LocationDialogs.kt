package com.example.qrgarage.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Garage
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.example.qrgarage.data.db.LocationEntity
import com.example.qrgarage.data.repo.LocationInput
import com.example.qrgarage.data.repo.LocationLabel
import com.example.qrgarage.data.repo.displayName
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.example.qrgarage.data.db.LocationKind
import com.example.qrgarage.data.repo.LocationTree

fun LocationKind.icon(): ImageVector = when (this) {
    LocationKind.GARAGE -> Icons.Outlined.Garage
    LocationKind.ZONE -> Icons.Outlined.Place
    LocationKind.SHELF_UNIT -> Icons.Outlined.ViewModule
    LocationKind.SHELF -> Icons.Outlined.TableRows
    LocationKind.BIN -> Icons.Outlined.Inventory2
}

/**
 * Tree picker for choosing a location. [excluded] ids (and their subtrees) cannot be chosen,
 * e.g. a location cannot be moved into itself.
 */
@Composable
fun LocationPickerDialog(
    tree: LocationTree,
    title: String,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    excluded: Set<String> = emptySet(),
    selectedId: String? = null,
    allowTopLevel: Boolean = false,
    onScanInstead: (() -> Unit)? = null,
) {
    var expanded by remember {
        mutableStateOf<Set<String>>(
            buildSet {
                tree.roots.forEach { add(it.id) }
                selectedId?.let { id ->
                    val path = tree.path(id)
                    path.dropLast(1).forEach { add(it.id) }
                    path.forEach { loc -> tree.groupKeyOf(loc.id)?.let(::add) }
                }
            }
        )
    }
    val entries = remember(tree, expanded) { tree.flattenGrouped(expanded) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                if (allowTopLevel) {
                    item {
                        ListItem(
                            headlineContent = { Text("Top level (new garage)") },
                            modifier = Modifier.clickable { onPick(null) }.heightIn(min = 56.dp),
                        )
                    }
                }
                items(entries, key = { it.key }) { entry ->
                    val loc = entry.location
                    fun isBlocked(id: String) = excluded.any { tree.isSelfOrDescendant(id, it) }
                    val blocked = if (loc != null) isBlocked(loc.id) else entry.members.all { isBlocked(it.id) }
                    val isOpen = entry.key in expanded
                    val toggle = { expanded = if (isOpen) expanded - entry.key else expanded + entry.key }
                    ListItem(
                        // Indent inside the leading slot so every row's container spans the full width.
                        modifier = Modifier
                            .heightIn(min = 56.dp)
                            .clickable(enabled = loc == null || !blocked) { if (loc != null) onPick(loc.id) else toggle() },
                        leadingContent = {
                            Box(Modifier.padding(start = (entry.depth * 16).dp)) {
                                if (entry.expandable) {
                                    IconButton(onClick = toggle) {
                                        Icon(
                                            if (isOpen) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                                            contentDescription = if (isOpen) "Collapse ${entry.label}" else "Expand ${entry.label}",
                                        )
                                    }
                                } else {
                                    Icon((loc?.kind ?: LocationKind.SHELF).icon(), contentDescription = null, modifier = Modifier.padding(12.dp))
                                }
                            }
                        },
                        headlineContent = {
                            Text(
                                entry.label,
                                color = when {
                                    blocked -> MaterialTheme.colorScheme.outline
                                    loc != null && loc.id == selectedId -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        supportingContent = {
                            Text(if (loc != null) "${loc.kind.label} · ${loc.code}" else "${entry.members.size} locations")
                        },
                    )
                }
            }
        },
        confirmButton = {
            if (onScanInstead != null) {
                TextButton(onClick = onScanInstead, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Scan label")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

private val WHEEL_KINDS = setOf(LocationKind.SHELF, LocationKind.BIN)

/**
 * Add or edit a location. Shelf and bin locations default to scroll wheels (LOC-9) whose
 * selections generate the label (LOC-13); free text stays available as an option. [isTaken]
 * reports a generated label already used by a sibling (LOC-15), which blocks saving.
 */
@Composable
fun EditLocationDialog(
    title: String,
    initialKind: LocationKind,
    onSave: (LocationInput) -> Unit,
    onDismiss: () -> Unit,
    existing: LocationEntity? = null,
    suggestedWheels: LocationLabel.Parts? = null,
    isTaken: (String) -> Boolean = { false },
) {
    val startWheels = existing?.wheels ?: suggestedWheels?.takeIf { existing == null }
    var name by rememberSaveable { mutableStateOf(existing?.takeIf { it.wheels == null }?.name.orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: initialKind) }
    var useWheels by rememberSaveable {
        mutableStateOf(if (existing != null) startWheels != null else initialKind in WHEEL_KINDS)
    }
    var shelf by rememberSaveable { mutableIntStateOf(startWheels?.let { LocationLabel.shelfIndex(it.shelf) } ?: 0) }
    // Row and bin hold wheel values (1-based). Shelf/row needs shelf + row; bin needs all three.
    var row by rememberSaveable { mutableIntStateOf(startWheels?.row ?: 1) }
    var bin by rememberSaveable { mutableIntStateOf(startWheels?.bin ?: 1) }

    val shelves = remember { List(LocationLabel.SHELF_COUNT, LocationLabel::shelfLetters) }
    val rows = remember(row) { (1..maxOf(LocationLabel.NUMBER_COUNT, row)).map(LocationLabel::rowText) }
    val bins = remember(bin) { (1..maxOf(LocationLabel.NUMBER_COUNT, bin)).map(LocationLabel::binText) }

    val showWheels = useWheels && kind in WHEEL_KINDS
    val parts = LocationLabel.Parts(LocationLabel.shelfLetters(shelf), row, bin.takeIf { kind == LocationKind.BIN })
    val taken = showWheels && isTaken(parts.label)
    val input = LocationInput(kind, name, parts.takeIf { showWheels })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Type", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocationKind.entries.forEach { k ->
                        FilterChip(
                            selected = k == kind,
                            onClick = {
                                if (k in WHEEL_KINDS && kind !in WHEEL_KINDS && name.isBlank()) useWheels = true
                                kind = k
                            },
                            label = { Text(k.label) },
                            leadingIcon = { Icon(k.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                if (showWheels) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WheelPicker(shelves, shelf, { shelf = it }, "Shelf", Modifier.weight(1f))
                        WheelPicker(rows, row - 1, { row = it + 1 }, "Row", Modifier.weight(1f))
                        if (kind == LocationKind.BIN) {
                            WheelPicker(bins, bin - 1, { bin = it + 1 }, "Bin", Modifier.weight(1f))
                        }
                    }
                    // LOC-13: the label is generated, never typed.
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("Label", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                LocationLabel.unbreakable(parts.label),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.testTag("generated-label"),
                            )
                            if (taken) {
                                Text(
                                    "“${parts.label}” already exists here. Pick different wheel values.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    TextButton(onClick = { useWheels = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Type a custom name instead")
                    }
                } else {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Name") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (kind in WHEEL_KINDS) {
                        TextButton(onClick = { useWheels = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Use shelf / row / bin wheels")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(input); onDismiss() },
                enabled = if (showWheels) !taken else name.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

/**
 * A vertical scroll wheel that snaps to the centered value. Tapping a value also selects it.
 * [onSelect] fires once scrolling settles.
 */
@Composable
fun WheelPicker(
    values: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 56.dp,
    visibleCount: Int = 3,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex.coerceIn(values.indices))
    val fling = rememberSnapFlingBehavior(state, SnapPosition.Center)
    val scope = rememberCoroutineScope()
    val centered by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: state.firstVisibleItemIndex
        }
    }
    val currentOnSelect by rememberUpdatedState(onSelect)
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to centered }
            .filter { !it.first }
            .map { it.second }
            .distinctUntilChanged()
            .collect { currentOnSelect(it) }
    }
    LaunchedEffect(selectedIndex) {
        if (!state.isScrollInProgress && centered != selectedIndex && selectedIndex in values.indices) {
            state.scrollToItem(selectedIndex)
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box(Modifier.height(itemHeight * visibleCount).fillMaxWidth()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(itemHeight),
            ) {}
            LazyColumn(
                state = state,
                flingBehavior = fling,
                contentPadding = PaddingValues(vertical = itemHeight * (visibleCount / 2)),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize().testTag("wheel-$label"),
            ) {
                itemsIndexed(values) { i, v ->
                    val selected = i == centered
                    Box(
                        Modifier
                            .height(itemHeight)
                            .fillMaxWidth()
                            .clickable(onClickLabel = "Select $label $v") { scope.launch { state.animateScrollToItem(i) } }
                            .semantics { this.selected = selected },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            v,
                            style = if (selected) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.Bold else null,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
