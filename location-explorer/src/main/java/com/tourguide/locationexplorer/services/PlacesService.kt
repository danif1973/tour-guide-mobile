package com.tourguide.locationexplorer.services

import com.tourguide.locationexplorer.models.PlaceInfo

/**
 * Interface for services that search for nearby places.
 * This abstracts the data provider (Overpass, Geoapify, etc.).
 */
interface PlacesService {
    /**
     * Search for places near coordinates.
     *
     * @param lat Latitude of the center point
     * @param lng Longitude of the center point
     * @param radiusM Search radius in meters
     * @param speedKmh Current speed in km/h (optional, for adjusting radius)
     * @return List of found places
     */
    suspend fun searchPlacesByCoordinates(
        lat: Double,
        lng: Double,
        radiusM: Int,
        speedKmh: Float = 50.0f
    ): List<PlaceInfo>
}
