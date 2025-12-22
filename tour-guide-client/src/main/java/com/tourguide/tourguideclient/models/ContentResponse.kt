package com.tourguide.tourguideclient.models

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Content response model.
 * Ported from tour_guide_client/client.py
 */
@OptIn(InternalSerializationApi::class)
@Serializable
data class ContentResponse(
    val status: Int,
    val content: List<String> = emptyList()
)
