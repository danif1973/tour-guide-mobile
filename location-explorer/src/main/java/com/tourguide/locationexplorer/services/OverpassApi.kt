package com.tourguide.locationexplorer.services

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit API interface for Overpass API.
 */
interface OverpassApi {
    @POST("interpreter")
    suspend fun query(@Body query: String): Response<OverpassResponse>
}

/**
 * Overpass API response model.
 */
@Serializable
data class OverpassResponse(
    val version: Double? = null,
    val generator: String? = null,
    val elements: List<OverpassElement> = emptyList()
)

/**
 * Overpass element (node, way, or relation).
 */
@Serializable
data class OverpassElement(
    val type: String,
    val id: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val center: OverpassCenter? = null,
    val tags: Map<String, String> = emptyMap()
)

/**
 * Center coordinates for ways and relations.
 */
@Serializable
data class OverpassCenter(
    val lat: Double,
    val lon: Double
)
