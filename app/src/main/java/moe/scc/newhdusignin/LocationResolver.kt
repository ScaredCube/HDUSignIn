package moe.scc.newhdusignin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

data class UsedLocation(val coordinates: Coordinates, val label: String, val fromSaved: Boolean)

/** A single validated location is frozen before any account/captcha/sign-in operation. */
object LocationResolver {
    suspend fun resolve(live: Boolean, saved: SavedLocation?, locate: suspend () -> Coordinates): UsedLocation {
        if (!live) return savedLocation(saved)
        return try {
            val point = locate()
            check(point.isValid()) { "实时定位返回无效坐标" }
            UsedLocation(point, "实时定位", false)
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            savedLocation(saved)
        }
    }

    private fun savedLocation(saved: SavedLocation?): UsedLocation {
        require(saved != null && saved.coordinates.isValid()) {
            "没有可用的实时定位或选中的保存位置，已拒绝发送签到请求"
        }
        return UsedLocation(saved.coordinates, saved.alias, true)
    }
}
