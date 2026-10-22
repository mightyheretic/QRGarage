package com.example.qrgarage.ui.location

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.example.qrgarage.data.db.SearchParams
import com.example.qrgarage.data.photos.PhotoStorage
import com.example.qrgarage.data.repo.ItemRepository
import com.example.qrgarage.data.repo.LocationRepository
import com.example.qrgarage.data.repo.LocationTree
import com.example.qrgarage.data.repo.displayName
import com.example.qrgarage.ui.UiMessenger
import com.example.qrgarage.ui.components.BackTopBar
import com.example.qrgarage.ui.components.BigButton
import com.example.qrgarage.ui.components.ItemRowView
import com.example.qrgarage.ui.components.contentWidth
import com.example.qrgarage.ui.navigation.MoveItemsRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class MoveItemsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val items: ItemRepository,
    locations: LocationRepository,
    private val photos: PhotoStorage,
    private val messenger: UiMessenger,
) : ViewModel() {
    val targetId = savedStateHandle.toRoute<MoveItemsRoute>().locationId

    val query = MutableStateFlow("")
    val selected = MutableStateFlow<Set<String>>(emptySet())
    val tree: StateFlow<LocationTree> =
        locations.tree.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationTree.EMPTY)

    val results = query.debounce(200).flatMapLatest { items.search(SearchParams(text = it)) }.cachedIn(viewModelScope)

    fun photoFile(path: String) = photos.fileFor(path)

    fun toggle(id: String) = selected.update { if (id in it) it - id else it + id }

    /** ITEM-12/14: moves the selection and offers undo. */
    fun move(onDone: () -> Unit) = viewModelScope.launch {
        val previous = items.move(selected.value, targetId)
        val name = tree.value[targetId]?.displayName ?: "location"
        if (previous.isNotEmpty()) {
            messenger.showUndo("Moved ${previous.size} item${if (previous.size == 1) "" else "s"} to $name") {
                items.undoMove(previous)
            }
        }
        onDone()
    }
}

@Composable
fun MoveItemsScreen(onBack: () -> Unit, vm: MoveItemsViewModel = hiltViewModel()) {
    val query by vm.query.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val tree by vm.tree.collectAsStateWithLifecycle()
    val results = vm.results.collectAsLazyPagingItems()
    val target = tree[vm.targetId]

    Scaffold(
        topBar = { BackTopBar("Move items to ${target?.displayName ?: "…"}", onBack) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                BigButton(
                    text = if (selected.isEmpty()) "Select items to move" else "Move ${selected.size} here",
                    icon = Icons.AutoMirrored.Filled.DriveFileMove,
                    onClick = { vm.move(onBack) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.navigationBarsPadding().contentWidth().padding(16.dp).fillMaxWidth(),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).contentWidth()) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Find items to move") },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 64.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(results.itemCount, key = results.itemKey { it.id }) { index ->
                    val row = results[index] ?: return@items
                    val alreadyHere = row.locationId == vm.targetId
                    ItemRowView(
                        row = row,
                        path = if (alreadyHere) "Already here" else tree.pathString(row.locationId),
                        photoFile = vm::photoFile,
                        onClick = { if (!alreadyHere) vm.toggle(row.id) },
                        trailing = {
                            Checkbox(
                                checked = row.id in selected,
                                onCheckedChange = { vm.toggle(row.id) },
                                enabled = !alreadyHere,
                            )
                        },
                    )
                }
            }
        }
    }
}
