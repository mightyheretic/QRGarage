package com.example.qrgarage

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.rememberNavController
import com.example.qrgarage.backup.RestoreReport
import com.example.qrgarage.backup.RestoreSwap
import com.example.qrgarage.data.repo.LocationRepository
import com.example.qrgarage.data.settings.AppSettings
import com.example.qrgarage.data.settings.SettingsRepository
import com.example.qrgarage.ui.UiMessenger
import com.example.qrgarage.ui.navigation.AppNavHost
import com.example.qrgarage.ui.theme.QrGarageTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext context: Context,
    settings: SettingsRepository,
    locations: LocationRepository,
    messenger: UiMessenger,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> =
        settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            locations.ensureRoot()
            RestoreSwap.consumeFailure(context)?.let {
                messenger.show("Restore failed and was rolled back: $it")
            }
            RestoreReport.consumeAnnouncement(context)?.let { messenger.show(it.summary) }
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var messenger: UiMessenger
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val loaded = settings ?: return@setContent
            QrGarageTheme(loaded.theme) {
                val nav = rememberNavController()
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(Unit) {
                    messenger.messages.collect { msg ->
                        val result = snackbar.showSnackbar(
                            message = msg.text,
                            actionLabel = msg.actionLabel,
                            withDismissAction = msg.actionLabel != null,
                            duration = if (msg.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) messenger.runAction(msg)
                    }
                }
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { _ ->
                    AppNavHost(nav, messenger, Modifier.fillMaxSize())
                }
            }
        }
    }
}
