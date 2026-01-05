package com.tourguide.tourguideclient.utils

import android.content.Context
import android.content.Intent
import com.tourguide.tourguideclient.ui.TourGuideActivity

fun Context.broadcastDebug(tag: String, message: String) {
    val intent = Intent(TourGuideActivity.ACTION_DEBUG_LOG)
    intent.putExtra("tag", tag)
    intent.putExtra("message", message)
    intent.setPackage(packageName)
    sendBroadcast(intent)
}
