package com.tourguide.locationexplorer.models

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Data classes for parsing the Overpass API JSON response.
 */

@OptIn(InternalSerializationApi::class)
@Serializable
data class OverpassResponse(
    val elements: List<OverpassElement> = emptyList()
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class OverpassElement(
    val type: String,
    val id: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val center: OverpassCenter? = null,
    val tags: Map<String, String> = emptyMap()
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class OverpassCenter(
    val lat: Double,
    val lon: Double
)
