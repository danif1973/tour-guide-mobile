package com.tourguide.locationexplorer.models

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Model representing a place found by the location service.
 * Ported from location_explorer/models.py
 */
@OptIn(InternalSerializationApi::class)
@Serializable
data class PlaceInfo(
    val name: String,
    val lat: Double,
    val lng: Double,
    val placeType: String,
    val category: String,
    val importance: Float,
    val rank: Int,
    val distanceM: Float? = null,
    val tags: Map<String, String> = emptyMap(),
    val osmId: Long? = null,
    val osmType: String? = null
)
