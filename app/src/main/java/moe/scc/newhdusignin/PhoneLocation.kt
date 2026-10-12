package moe.scc.newhdusignin
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PhoneLocation(private val context: Context) {
    fun permitted(): Boolean = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    suspend fun current(): Coordinates = withContext(Dispatchers.Main.immediate) {
        check(permitted()) { "请允许定位，或选择已保存的位置" }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = manager.getProviders(true).filter {
            it == LocationManager.NETWORK_PROVIDER || (precise && it == LocationManager.GPS_PROVIDER)
        }
        check(providers.isNotEmpty()) { "手机定位已关闭，请开启定位或使用保存的位置" }
        // Fresh only: no stale last-known coordinate is silently presented as live location.
        withTimeout(15000) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos > 60_000_000_000L) return
                        val coords = Coordinates(location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy else null)
                        if (continuation.isActive && coords.isValid()) {
                            manager.removeUpdates(this)
                            continuation.resume(coords)
                        }
                    }
                    @Deprecated("Legacy Android callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                try { providers.forEach { manager.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) } }
                catch (error: Exception) {
                    manager.removeUpdates(listener)
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    }
}
