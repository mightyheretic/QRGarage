package com.example.qrgarage.ui.scan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.qrgarage.data.db.LocationEntity
import com.example.qrgarage.data.repo.LocationRepository
import com.example.qrgarage.data.repo.ScanResolution
import com.example.qrgarage.data.settings.SettingsRepository
import com.example.qrgarage.ui.navigation.ScanRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScanUiState(
    val busy: Boolean = false,
    val error: String? = null,
    val trashed: LocationEntity? = null,
) {
    val paused get() = busy || error != null || trashed != null
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val locations: LocationRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val pickMode: Boolean = savedStateHandle.toRoute<ScanRoute>().pick

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val _resolved = Channel<String>(Channel.BUFFERED)
    /** Emits the id of a successfully scanned location, exactly once. */
    val resolved = _resolved.receiveAsFlow()

    private var done = false

    /** Called from the camera analyzer thread's callback and from manual entry. */
    fun onCode(raw: String) {
        if (done || _state.value.paused) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val r = locations.resolveScan(raw)) {
                is ScanResolution.Found -> finish(r.location.id)
                is ScanResolution.InTrash -> _state.update { ScanUiState(trashed = r.location) }
                is ScanResolution.Error -> _state.update { ScanUiState(error = r.message) }
            }
        }
    }

    fun restoreTrashed() {
        val loc = _state.value.trashed ?: return
        viewModelScope.launch {
            locations.restore(loc.id)
            finish(loc.id)
        }
    }

    fun dismiss() = _state.update { ScanUiState() }

    private suspend fun finish(id: String) {
        done = true
        // LOC-3: the scanned location becomes the "current location".
        settings.setCurrentLocation(id)
        _resolved.send(id)
    }
}
