package com.tourguide.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Service that manages the lifecycle of the Android Auto app.
 */
class TourGuideCarService : CarAppService() {
    
    override fun onCreateSession(): Session {
        return TourGuideSession()
    }

    override fun createHostValidator(): HostValidator {
        // For testing purposes, we allow all hosts.
        // In production, this should be stricter.
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }
}
