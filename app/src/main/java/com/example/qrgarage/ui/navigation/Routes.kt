package com.example.qrgarage.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute

/** [pick] = return the scanned location to the previous screen instead of opening it. */
@Serializable data class ScanRoute(val pick: Boolean = false)

@Serializable data class LocationRoute(val id: String)

@Serializable data class ItemRoute(val id: String)

@Serializable data class ItemEditRoute(val itemId: String? = null, val locationId: String? = null)

@Serializable data class MoveItemsRoute(val locationId: String)

@Serializable data object SettingsRoute

@Serializable data object LocationsRoute

@Serializable data class LabelsRoute(val preselectId: String? = null)

@Serializable data object BackupRoute

@Serializable data object TrashRoute

/** SavedStateHandle key used by [ScanRoute] in pick mode. */
const val PICKED_LOCATION_KEY = "picked_location_id"
