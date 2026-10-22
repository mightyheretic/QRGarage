package com.example.qrgarage.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.qrgarage.ui.UiMessenger
import com.example.qrgarage.ui.home.HomeScreen
import com.example.qrgarage.ui.item.ItemDetailScreen
import com.example.qrgarage.ui.item.ItemEditScreen
import com.example.qrgarage.ui.location.LocationScreen
import com.example.qrgarage.ui.location.MoveItemsScreen
import com.example.qrgarage.ui.scan.ScanScreen
import com.example.qrgarage.ui.settings.BackupScreen
import com.example.qrgarage.ui.settings.LabelsScreen
import com.example.qrgarage.ui.settings.LocationsScreen
import com.example.qrgarage.ui.settings.SettingsScreen
import com.example.qrgarage.ui.settings.TrashScreen

@Composable
fun AppNavHost(nav: NavHostController, messenger: UiMessenger, modifier: Modifier = Modifier) {
    val back: () -> Unit = { nav.popBackStack() }

    NavHost(nav, startDestination = HomeRoute, modifier = modifier) {
        composable<HomeRoute> {
            HomeScreen(
                onScan = { nav.navigate(ScanRoute()) },
                onAdd = { nav.navigate(ItemEditRoute()) },
                onOpenItem = { nav.navigate(ItemRoute(it)) },
                onOpenLocation = { nav.navigate(LocationRoute(it)) },
                onSettings = { nav.navigate(SettingsRoute) },
                messenger = messenger,
            )
        }
        composable<ScanRoute> {
            ScanScreen(
                onResolved = { id, pick ->
                    if (pick) {
                        nav.previousBackStackEntry?.savedStateHandle?.set(PICKED_LOCATION_KEY, id)
                        nav.popBackStack()
                    } else {
                        nav.navigate(LocationRoute(id)) { popUpTo<ScanRoute> { inclusive = true } }
                    }
                },
                onClose = back,
            )
        }
        composable<LocationRoute> {
            LocationScreen(
                onBack = back,
                onOpenLocation = { nav.navigate(LocationRoute(it)) },
                onOpenItem = { nav.navigate(ItemRoute(it)) },
                onAddItemHere = { nav.navigate(ItemEditRoute(locationId = it)) },
                onMoveItemsHere = { nav.navigate(MoveItemsRoute(it)) },
                onPrintLabel = { nav.navigate(LabelsRoute(it)) },
            )
        }
        composable<MoveItemsRoute> { MoveItemsScreen(onBack = back) }
        composable<ItemRoute> {
            ItemDetailScreen(
                onBack = back,
                onEdit = { nav.navigate(ItemEditRoute(itemId = it)) },
                onOpenLocation = { nav.navigate(LocationRoute(it)) },
                onScanForMove = { nav.navigate(ScanRoute(pick = true)) },
            )
        }
        composable<ItemEditRoute> {
            ItemEditScreen(onClose = back, onScanForLocation = { nav.navigate(ScanRoute(pick = true)) })
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = back,
                onLocations = { nav.navigate(LocationsRoute) },
                onLabels = { nav.navigate(LabelsRoute()) },
                onBackup = { nav.navigate(BackupRoute) },
                onTrash = { nav.navigate(TrashRoute) },
            )
        }
        composable<LocationsRoute> {
            LocationsScreen(
                onBack = back,
                onOpenLocation = { nav.navigate(LocationRoute(it)) },
                onPrintLabel = { nav.navigate(LabelsRoute(it)) },
            )
        }
        composable<LabelsRoute> { LabelsScreen(onBack = back) }
        composable<BackupRoute> { BackupScreen(onBack = back) }
        composable<TrashRoute> { TrashScreen(onBack = back) }
    }
}
