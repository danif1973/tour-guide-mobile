package com.tourguide.locationexplorer.models

import kotlinx.serialization.Serializable

/**
 * Error response model.
 * Ported from location_explorer/models.py
 */
@Serializable
data class ErrorResponse(
    val error: String,
    val detail: String? = null
)


