package com.example.qrgarage.ui.item

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.qrgarage.data.db.CategoryEntity
import com.example.qrgarage.data.db.TagEntity
import com.example.qrgarage.data.photos.PhotoStorage
import com.example.qrgarage.data.repo.ItemDraft
import com.example.qrgarage.data.repo.ItemRepository
import com.example.qrgarage.data.repo.LocationRepository
import com.example.qrgarage.data.repo.LocationTree
import com.example.qrgarage.data.settings.SettingsRepository
import com.example.qrgarage.ui.UiMessenger
import com.example.qrgarage.ui.components.formatQty
import com.example.qrgarage.ui.navigation.ItemEditRoute
import com.example.qrgarage.ui.navigation.PICKED_LOCATION_KEY
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

data class ItemForm(
    val loaded: Boolean = false,
    val name: String = "",
    val description: String = "",
    val locationId: String? = null,
    val photos: List<String> = emptyList(),
    val showMore: Boolean = false,
    val quantity: String = "",
    val unit: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val tagInput: String = "",
    val purchaseDate: Long? = null,
    val price: String = "",
    val notes: String = "",
    val lowStock: String = "",
    val nameError: Boolean = false,
    val numberError: String? = null,
    val addingPhoto: Boolean = false,
    val saving: Boolean = false,
)

private const val TAG = "ItemEdit"

@HiltViewModel
class ItemEditViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val items: ItemRepository,
    private val locations: LocationRepository,
    private val settings: SettingsRepository,
    private val photoStorage: PhotoStorage,
    private val messenger: UiMessenger,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ItemEditRoute>()
    val isNew = route.itemId == null

    private val _form = MutableStateFlow(ItemForm())
    val form: StateFlow<ItemForm> = _form.asStateFlow()

    val tree: StateFlow<LocationTree> =
        locations.tree.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationTree.EMPTY)
    val allTags: StateFlow<List<TagEntity>> =
        items.tags.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories: StateFlow<List<CategoryEntity>> =
        items.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _closed = Channel<Unit>(Channel.CONFLATED)
    val closed = _closed.receiveAsFlow()

    /** Photo files imported in this session but not yet saved; deleted if the form is abandoned. */
    private val unsavedPhotos = mutableSetOf<String>()

    init {
        viewModelScope.launch { load() }
        // Result of ScanRoute(pick = true) or the picker, delivered via this entry's SavedStateHandle.
        viewModelScope.launch {
            savedStateHandle.getStateFlow<String?>(PICKED_LOCATION_KEY, null).filterNotNull().collect {
                setLocation(it)
                savedStateHandle.remove<String>(PICKED_LOCATION_KEY)
            }
        }
    }

    private suspend fun load() {
        val defaults = settings.settings.first()
        val id = route.itemId
        if (id == null) {
            val loc = locations.liveOrRoot(route.locationId ?: settings.currentLocationId.first())
            _form.update {
                it.copy(
                    loaded = true,
                    locationId = loc.id,
                    unit = defaults.defaultUnit,
                    lowStock = defaults.defaultLowStock?.let(::formatQty).orEmpty(),
                )
            }
            return
        }
        val item = items.get(id) ?: return _closed.send(Unit)
        val tags = items.tagsFor(id).map { it.name }
        val photos = items.photosFor(id).map { it.relativePath }
        val categoryName = item.categoryId?.let { cid -> items.categories.first().firstOrNull { it.id == cid }?.name }.orEmpty()
        val hasAdvanced = item.quantity != null || tags.isNotEmpty() || categoryName.isNotEmpty() ||
            item.purchaseDate != null || item.purchasePriceCents != null || item.notes.isNotEmpty() || item.lowStockThreshold != null
        _form.value = ItemForm(
            loaded = true,
            name = item.name,
            description = item.description,
            locationId = item.locationId,
            photos = photos,
            showMore = hasAdvanced,
            quantity = item.quantity?.let(::formatQty).orEmpty(),
            unit = item.unit ?: defaults.defaultUnit,
            category = categoryName,
            tags = tags,
            purchaseDate = item.purchaseDate,
            price = item.purchasePriceCents?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty(),
            notes = item.notes,
            lowStock = item.lowStockThreshold?.let(::formatQty).orEmpty(),
        )
    }

    fun update(transform: (ItemForm) -> ItemForm) = _form.update { transform(it).copy(nameError = false, numberError = null) }

    fun setLocation(id: String) = _form.update { it.copy(locationId = id) }

    fun addTag(raw: String = _form.value.tagInput) {
        val names = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        _form.update { f ->
            val merged = (f.tags + names).distinctBy { it.lowercase() }
            f.copy(tags = merged, tagInput = "")
        }
    }

    fun removeTag(name: String) = _form.update { it.copy(tags = it.tags - name) }

    fun addPhoto(uri: Uri) {
        if (_form.value.photos.size >= PhotoStorage.MAX_PER_ITEM) return
        _form.update { it.copy(addingPhoto = true) }
        viewModelScope.launch {
            runCatching { photoStorage.import(uri) }
                .onSuccess { path ->
                    unsavedPhotos += path
                    _form.update { it.copy(photos = (it.photos + path).take(PhotoStorage.MAX_PER_ITEM), addingPhoto = false) }
                }
                .onFailure { e ->
                    Log.w(TAG, "Photo import failed for $uri", e)
                    _form.update { it.copy(addingPhoto = false) }
                    messenger.show("Couldn't add that photo")
                }
        }
    }

    fun removePhoto(path: String) = _form.update { it.copy(photos = it.photos - path) }

    fun newCaptureUri(): Uri = photoStorage.newCaptureUri()
    fun photoFile(path: String): File = photoStorage.fileFor(path)

    /** ITEM-4: [addAnother] saves and resets the form, keeping the location sticky. */
    fun save(addAnother: Boolean) {
        val f = _form.value
        if (f.saving) return
        if (f.name.isBlank()) {
            _form.update { it.copy(nameError = true) }
            return
        }
        val quantity = parseNumber(f.quantity) ?: return numberError("Quantity must be a number")
        val lowStock = parseNumber(f.lowStock) ?: return numberError("Low-stock threshold must be a number")
        val priceCents = parsePrice(f.price) ?: return numberError("Price must be a number like 12.50")
        val locationId = f.locationId ?: return
        val pendingTag = f.tagInput.trim()
        val tags = if (pendingTag.isNotEmpty()) (f.tags + pendingTag).distinctBy { it.lowercase() } else f.tags

        val categoryName = f.category.trim()
        val existingCategory = categories.value.firstOrNull { it.name.equals(categoryName, ignoreCase = true) }
        _form.update { it.copy(saving = true) }
        viewModelScope.launch {
            items.save(
                ItemDraft(
                    id = route.itemId,
                    name = f.name,
                    description = f.description,
                    locationId = locationId,
                    quantity = quantity.value,
                    unit = f.unit.takeIf { quantity.value != null },
                    categoryId = existingCategory?.id,
                    newCategoryName = categoryName.takeIf { existingCategory == null && it.isNotEmpty() },
                    purchaseDate = f.purchaseDate,
                    purchasePriceCents = priceCents.value,
                    notes = f.notes,
                    lowStockThreshold = lowStock.value,
                    tags = tags,
                    photos = f.photos,
                )
            )
            unsavedPhotos.clear()
            messenger.show("Saved “${f.name.trim()}”")
            if (addAnother) {
                val defaults = settings.settings.first()
                _form.value = ItemForm(
                    loaded = true,
                    locationId = locationId,
                    showMore = f.showMore,
                    unit = defaults.defaultUnit,
                    lowStock = defaults.defaultLowStock?.let(::formatQty).orEmpty(),
                )
            } else {
                _closed.send(Unit)
            }
        }
    }

    override fun onCleared() {
        // Photos imported for a form that was never saved would otherwise be orphaned.
        unsavedPhotos.forEach(photoStorage::delete)
        photoStorage.clearCaptureCache()
    }

    private fun numberError(msg: String) = _form.update { it.copy(numberError = msg, showMore = true) }

    /** Wraps a nullable parse result so "blank" (null value) differs from "invalid" (null wrapper). */
    private data class Parsed<T>(val value: T?)

    private fun parseNumber(text: String): Parsed<Double>? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return Parsed(null)
        return t.toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }?.let { Parsed(it) }
    }

    private fun parsePrice(text: String): Parsed<Long>? {
        val t = text.trim().replace(',', '.').removePrefix("$").removePrefix("€").removePrefix("£")
        if (t.isEmpty()) return Parsed(null)
        return runCatching {
            Parsed(BigDecimal(t).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact())
        }.getOrNull()?.takeIf { (it.value ?: 0) >= 0 }
    }
}
