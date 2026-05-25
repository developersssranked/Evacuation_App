package com.thirdapp.evacuation.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.thirdapp.evacuation.model.Coord
import com.thirdapp.evacuation.model.UserLocation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class LocationProvider(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long = 4000L, minIntervalMs: Long = 2000L): Flow<UserLocation> =
        callbackFlow {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(minIntervalMs)
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    trySend(
                        UserLocation(
                            coord = Coord(loc.latitude, loc.longitude),
                            accuracyMeters = loc.accuracy,
                            timestamp = loc.time
                        )
                    )
                }
            }

            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    trySend(
                        UserLocation(
                            coord = Coord(loc.latitude, loc.longitude),
                            accuracyMeters = loc.accuracy,
                            timestamp = loc.time
                        )
                    )
                }
            }

            client.requestLocationUpdates(request, callback, context.mainLooper)
            awaitClose { client.removeLocationUpdates(callback) }
        }
}
