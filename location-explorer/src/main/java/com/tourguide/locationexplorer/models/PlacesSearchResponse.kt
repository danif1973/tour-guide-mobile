package com.tourguide.locationexplorer.models

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Response model for places search.
 * Ported from location_explorer/models.py
 */
@OptIn(InternalSerializationApi::class)
@Serializable
data class PlacesSearchResponse(
    val places: List<PlaceInfo>,
    val queryLocation: Map<String, String>,
    val totalFound: Int,
    val searchRadiusM: Int,
    val filtersApplied: Map<String, String> = emptyMap()
)
