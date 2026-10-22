package com.example.qrgarage.ui.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.example.qrgarage.data.repo.displayName
import com.example.qrgarage.ui.components.BigButton
import com.example.qrgarage.ui.components.EmptyState
import com.example.qrgarage.ui.components.ItemRowView
import com.example.qrgarage.ui.components.LocationPickerDialog
import com.example.qrgarage.ui.components.SectionHeader
import com.example.qrgarage.ui.components.contentWidth
import com.example.qrgarage.ui.UiMessenger
import androidx.compose.material3.ButtonDefaults

@Composable
fun HomeScreen(
    onScan: () -> Unit,
    onAdd: () -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenLocation: (String) -> Unit,
    onSettings: () -> Unit,
    messenger: UiMessenger,
    vm: HomeViewModel = hiltViewModel(),
) {
    val params by vm.params.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val tree by vm.tree.collectAsStateWithLifecycle()
    val current by vm.currentLocation.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val results = vm.results.collectAsLazyPagingItems()
    val keyboard = LocalSoftwareKeyboardController.current

    var pickLocationFilter by remember { mutableStateOf(false) }

    // HOME-2: RecognizerIntent hands audio capture to the system recognizer, so this app needs
    // no microphone permission of its own; offline recognition is preferred (NFR-1).
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) vm.setText(text)
    }
    val startVoice = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "What are you looking for?")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        try {
            voice.launch(intent)
        } catch (_: ActivityNotFoundException) {
            messenger.show("Voice input isn't available on this device")
        }
    }

    Scaffold { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).contentWidth(),
        ) {
            item(key = "header") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("QR Garage", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = onSettings, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", modifier = Modifier.size(28.dp))
                    }
                }
            }
            item(key = "actions") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton(
                        text = "Scan location",
                        icon = Icons.Filled.QrCodeScanner,
                        onClick = onScan,
                        height = 112.dp,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    BigButton(
                        text = "Add item",
                        icon = Icons.Filled.Add,
                        onClick = onAdd,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    )
                    current?.let { loc ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth().clickable { onOpenLocation(loc.id) },
                        ) {
                            Row(Modifier.padding(16.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Current location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(tree.pathString(loc.id), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = params.text,
                        onValueChange = vm::setText,
                        placeholder = { Text("Search items, tags, notes") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            Row {
                                if (params.text.isNotEmpty() || params.hasFilters) {
                                    IconButton(onClick = vm::clearSearch, modifier = Modifier.size(56.dp)) {
                                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                                    }
                                }
                                IconButton(onClick = startVoice, modifier = Modifier.size(56.dp)) {
                                    Icon(Icons.Filled.Mic, contentDescription = "Search by voice")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics { contentDescription = "Search" },
                    )
                    FilterRow(
                        locationLabel = params.locationId?.let { tree[it]?.displayName },
                        categoryId = params.categoryId,
                        tagId = params.tagId,
                        lowStock = params.lowStockOnly,
                        lent = params.lentOutOnly,
                        categories = categories.map { it.id to it.name },
                        tags = tags.map { it.id to it.name },
                        onLocation = { if (params.locationId != null) vm.setLocation(null) else pickLocationFilter = true },
                        onCategory = vm::setCategory,
                        onTag = vm::setTag,
                        onLowStock = vm::toggleLowStock,
                        onLent = vm::toggleLent,
                    )
                }
            }

            if (params.isEmpty) {
                item(key = "recent-header") { SectionHeader("Recent") }
                if (recents.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            Icons.Outlined.History,
                            "Nothing here yet",
                            "Scan a location label or tap Add item. Set up zones, shelves and bins in Settings → Locations.",
                        )
                    }
                }
                items(recents, key = { "r-" + it.id }) { row ->
                    ItemRowView(row, tree.pathString(row.locationId), vm::photoFile, onClick = { onOpenItem(row.id) })
                }
            } else {
                item(key = "results-header") { SectionHeader("Results") }
                items(results.itemCount, key = results.itemKey { "s-" + it.id }) { index ->
                    results[index]?.let { row ->
                        ItemRowView(
                            row, tree.pathString(row.locationId), vm::photoFile,
                            onClick = { onOpenItem(row.id) }, pathAsHeadline = true,
                        )
                    }
                }
                if (results.itemCount == 0 && results.loadState.refresh is LoadState.NotLoading) {
                    item(key = "no-results") {
                        EmptyState(Icons.Outlined.SearchOff, "No matches", "Try fewer letters or remove a filter.")
                    }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (pickLocationFilter) {
        LocationPickerDialog(
            tree = tree,
            title = "Search within",
            onPick = { vm.setLocation(it); pickLocationFilter = false },
            onDismiss = { pickLocationFilter = false },
        )
    }
}

@Composable
private fun FilterRow(
    locationLabel: String?,
    categoryId: String?,
    tagId: String?,
    lowStock: Boolean,
    lent: Boolean,
    categories: List<Pair<String, String>>,
    tags: List<Pair<String, String>>,
    onLocation: () -> Unit,
    onCategory: (String?) -> Unit,
    onTag: (String?) -> Unit,
    onLowStock: () -> Unit,
    onLent: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val chipMod = Modifier.heightIn(min = 48.dp)
        FilterChip(
            selected = locationLabel != null, onClick = onLocation, modifier = chipMod,
            label = { Text(locationLabel?.let { "In: $it" } ?: "Location") },
            trailingIcon = if (locationLabel != null) { { Icon(Icons.Filled.Clear, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
        )
        MenuChip("Category", categoryId, categories, onCategory, chipMod)
        MenuChip("Tag", tagId, tags, onTag, chipMod)
        FilterChip(selected = lowStock, onClick = onLowStock, label = { Text("Low stock") }, modifier = chipMod)
        FilterChip(selected = lent, onClick = onLent, label = { Text("Lent out") }, modifier = chipMod)
    }
}

@Composable
private fun MenuChip(
    label: String,
    selectedId: String?,
    options: List<Pair<String, String>>,
    onSelect: (String?) -> Unit,
    modifier: Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val selectedName = options.firstOrNull { it.first == selectedId }?.second
    Box {
        FilterChip(
            selected = selectedId != null,
            onClick = { if (selectedId != null) onSelect(null) else open = true },
            label = { Text(selectedName ?: label) },
            trailingIcon = if (selectedId != null) { { Icon(Icons.Filled.Clear, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
            modifier = modifier,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(text = { Text("None yet") }, onClick = { open = false }, enabled = false)
            }
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); open = false }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}
