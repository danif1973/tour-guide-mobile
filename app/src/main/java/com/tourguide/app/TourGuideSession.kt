package com.tourguide.app

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class TourGuideSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return MainScreen(carContext)
    }
}
