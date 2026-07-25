package by.ster.wazeissues.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await

data class Fix(val lon: Double, val lat: Double)

object LocationFix {
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Fix {
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        val loc =
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
                ?: fused.lastLocation.await()
                ?: error("No GPS fix yet — wait a moment and try again")
        return Fix(loc.longitude, loc.latitude)
    }
}
