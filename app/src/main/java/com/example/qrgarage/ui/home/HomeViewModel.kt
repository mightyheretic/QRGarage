package com.example.qrgarage.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.qrgarage.data.db.CategoryEntity
import com.example.qrgarage.data.db.ItemRow
import com.example.qrgarage.data.db.LocationEntity
import com.example.qrgarage.data.db.SearchParams
import com.example.qrgarage.data.db.TagEntity
import com.example.qrgarage.data.photos.PhotoStorage
import com.example.qrgarage.data.repo.ItemRepository
import com.example.qrgarage.data.repo.LocationRepository
import com.example.qrgarage.data.repo.LocationTree
import com.example.qrgarage.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    items: ItemRepository,
    locations: LocationRepository,
    settings: SettingsRepository,
    private val photos: PhotoStorage,
) : ViewModel() {

    private val _params = MutableStateFlow(SearchParams())
    val params: StateFlow<SearchParams> = _params.asStateFlow()

    /** SEARCH-5: debounced; Room executes the paged query on its own executor. */
    val results: Flow<PagingData<ItemRow>> = _params
        .debounce { if (it.text.isBlank()) 0L else DEBOUNCE_MS }
        .distinctUntilChanged()
        .flatMapLatest { p -> if (p.isEmpty) flowOf(PagingData.empty()) else items.search(p) }
        .cachedIn(viewModelScope)

    val recents: StateFlow<List<ItemRow>> =
        items.observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tree: StateFlow<LocationTree> =
        locations.tree.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationTree.EMPTY)

    val currentLocation: StateFlow<LocationEntity?> =
        combine(settings.currentLocationId, tree) { id, t -> id?.let { t[it] } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tags: StateFlow<List<TagEntity>> =
        items.tags.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories: StateFlow<List<CategoryEntity>> =
        items.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun photoFile(path: String): File = photos.fileFor(path)

    fun setText(text: String) = _params.update { it.copy(text = text) }
    fun setLocation(id: String?) = _params.update { it.copy(locationId = id) }
    fun setCategory(id: String?) = _params.update { it.copy(categoryId = id) }
    fun setTag(id: String?) = _params.update { it.copy(tagId = id) }
    fun toggleLowStock() = _params.update { it.copy(lowStockOnly = !it.lowStockOnly) }
    fun toggleLent() = _params.update { it.copy(lentOutOnly = !it.lentOutOnly) }
    fun clearSearch() = _params.update { SearchParams() }

    private companion object {
        const val DEBOUNCE_MS = 200L
    }
}
