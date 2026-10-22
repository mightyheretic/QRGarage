package com.example.qrgarage.ui.item

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.qrgarage.data.photos.PhotoStorage
import com.example.qrgarage.ui.components.BackTopBar
import com.example.qrgarage.ui.components.BigButton
import com.example.qrgarage.ui.components.LocationPickerDialog
import com.example.qrgarage.ui.components.Thumbnail
import com.example.qrgarage.ui.components.contentWidth
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditScreen(
    onClose: () -> Unit,
    onScanForLocation: () -> Unit,
    vm: ItemEditViewModel = hiltViewModel(),
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val tree by vm.tree.collectAsStateWithLifecycle()
    val allTags by vm.allTags.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pickingLocation by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    val nameFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { vm.closed.collect { onClose() } }
    LaunchedEffect(form.loaded) { if (form.loaded && vm.isNew) runCatching { nameFocus.requestFocus() } }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCapture?.let(Uri::parse)
        if (ok && uri != null) vm.addPhoto(uri)
        pendingCapture = null
    }
    val launchCamera = {
        val uri = vm.newCaptureUri()
        pendingCapture = uri.toString()
        takePicture.launch(uri)
    }
    // NFR-3: camera permission is requested the first time the user takes a photo.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addPhoto(uri)
    }

    Scaffold(
        topBar = { BackTopBar(if (vm.isNew) "Add item" else "Edit item", onClose) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.navigationBarsPadding().contentWidth().padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (vm.isNew) {
                        BigButton(
                            "Add another", null, { vm.save(addAnother = true) }, Modifier.weight(1f),
                            enabled = !form.saving, colors = ButtonDefaults.filledTonalButtonColors(),
                        )
                    }
                    BigButton("Save", null, { vm.save(addAnother = false) }, Modifier.weight(1f), enabled = !form.saving)
                }
            }
        },
    ) { padding ->
        if (!form.loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .contentWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { v -> vm.update { it.copy(name = v) } },
                label = { Text("Name *") },
                isError = form.nameError,
                supportingText = if (form.nameError) { { Text("A name is required") } } else null,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
            )

            // PHOTO-1: up to three photos from camera or gallery.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                form.photos.forEach { path ->
                    Box {
                        Thumbnail(vm.photoFile(path), size = 88.dp)
                        FilledTonalIconButton(
                            onClick = { vm.removePhoto(path) },
                            modifier = Modifier.align(Alignment.TopEnd).size(36.dp),
                        ) { Icon(Icons.Filled.Close, contentDescription = "Remove photo", modifier = Modifier.size(18.dp)) }
                    }
                }
                if (form.addingPhoto) CircularProgressIndicator(Modifier.size(32.dp))
                if (form.photos.size < PhotoStorage.MAX_PER_ITEM && !form.addingPhoto) {
                    FilledTonalIconButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                launchCamera()
                            } else cameraPermission.launch(Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.size(72.dp),
                    ) { Icon(Icons.Filled.PhotoCamera, contentDescription = "Take photo", modifier = Modifier.size(32.dp)) }
                    FilledTonalIconButton(
                        onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.size(72.dp),
                    ) { Icon(Icons.Filled.PhotoLibrary, contentDescription = "Choose photo from gallery", modifier = Modifier.size(32.dp)) }
                }
            }

            OutlinedTextField(
                value = form.description,
                onValueChange = { v -> vm.update { it.copy(description = v) } },
                label = { Text("Description") },
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            // ITEM-2: pre-filled from the current location, changeable by list or scan.
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Place, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("Location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            form.locationId?.let { tree.fullPath(it) } ?: "…",
                            style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { pickingLocation = true }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Change") }
                }
            }

            // ITEM-3: advanced fields are collapsed by default.
            TextButton(
                onClick = { vm.update { it.copy(showMore = !it.showMore) } },
                modifier = Modifier.heightIn(min = 56.dp),
            ) {
                Icon(if (form.showMore) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                Spacer(Modifier.width(8.dp))
                Text(if (form.showMore) "Fewer details" else "More details")
            }

            if (form.showMore) {
                form.numberError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = form.quantity,
                        onValueChange = { v -> vm.update { it.copy(quantity = v) } },
                        label = { Text("Quantity") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = form.unit,
                        onValueChange = { v -> vm.update { it.copy(unit = v) } },
                        label = { Text("Unit") }, singleLine = true,
                        placeholder = { Text("pcs, boxes, m…") },
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = form.lowStock,
                    onValueChange = { v -> vm.update { it.copy(lowStock = v) } },
                    label = { Text("Low-stock threshold") }, singleLine = true,
                    supportingText = { Text("Flag as low stock when quantity is at or below this") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                // ITEM-6: tags with autocomplete from existing tags.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = form.tagInput,
                        onValueChange = { v ->
                            if (v.endsWith(",")) vm.addTag(v) else vm.update { it.copy(tagInput = v) }
                        },
                        label = { Text("Tags") }, singleLine = true,
                        placeholder = { Text("Type and press enter") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { vm.addTag() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val suggestions = remember(form.tagInput, allTags, form.tags) {
                        val q = form.tagInput.trim()
                        if (q.isEmpty()) emptyList() else allTags.map { it.name }
                            .filter { it.startsWith(q, ignoreCase = true) && form.tags.none { t -> t.equals(it, true) } }
                            .take(6)
                    }
                    if (suggestions.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            suggestions.forEach { s ->
                                SuggestionChip(onClick = { vm.addTag(s) }, label = { Text(s) }, modifier = Modifier.heightIn(min = 48.dp))
                            }
                        }
                    }
                    if (form.tags.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            form.tags.forEach { t ->
                                InputChip(
                                    selected = false, onClick = { vm.removeTag(t) }, label = { Text(t) },
                                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove tag $t", Modifier.size(18.dp)) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                    }
                }

                // ITEM-7: category; typing a new name creates it on save.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = form.category,
                        onValueChange = { v -> vm.update { it.copy(category = v) } },
                        label = { Text("Category") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val matches = remember(form.category, categories) {
                        categories.filter {
                            it.name.startsWith(form.category.trim(), ignoreCase = true) && !it.name.equals(form.category.trim(), true)
                        }.take(6)
                    }
                    if (matches.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            matches.forEach { c ->
                                SuggestionChip(onClick = { vm.update { it.copy(category = c.name) } }, label = { Text(c.name) }, modifier = Modifier.heightIn(min = 48.dp))
                            }
                        }
                    }
                }

                // ITEM-8: purchase date and price.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Icon(Icons.Filled.CalendarMonth, null)
                        Spacer(Modifier.width(8.dp))
                        Text(form.purchaseDate?.let(::formatDate) ?: "Purchase date", maxLines = 1)
                    }
                    if (form.purchaseDate != null) {
                        IconButton(onClick = { vm.update { it.copy(purchaseDate = null) } }, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear purchase date")
                        }
                    }
                    OutlinedTextField(
                        value = form.price,
                        onValueChange = { v -> vm.update { it.copy(price = v) } },
                        label = { Text("Price") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }

                OutlinedTextField(
                    value = form.notes,
                    onValueChange = { v -> vm.update { it.copy(notes = v) } },
                    label = { Text("Notes") },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Lending is tracked from the item's page after saving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (pickingLocation) {
        LocationPickerDialog(
            tree = tree,
            title = "Choose location",
            selectedId = form.locationId,
            onPick = { id -> id?.let(vm::setLocation); pickingLocation = false },
            onDismiss = { pickingLocation = false },
            onScanInstead = { pickingLocation = false; onScanForLocation() },
        )
    }
    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = form.purchaseDate ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    vm.update { it.copy(purchaseDate = state.selectedDateMillis) }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/** Date pickers return UTC midnight; format in UTC so the day never shifts. */
fun formatDate(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))
