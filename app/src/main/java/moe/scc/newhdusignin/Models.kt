package moe.scc.newhdusignin
import java.util.UUID

data class Account(
    val id: String = UUID.randomUUID().toString(),
    val alias: String,
    val username: String,
    val password: String,
    val token: String = "",
    val userId: String = "",
    val userName: String = "",
    val selected: Boolean = true,
    val refreshedAt: Long = 0,
    val status: String = "尚未登录"
)

data class Coordinates(val latitude: Double, val longitude: Double, val accuracy: Float? = null) {
    fun isValid() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}

data class SavedLocation(
    val id: String = UUID.randomUUID().toString(),
    val alias: String,
    val coordinates: Coordinates,
    val capturedAt: Long = System.currentTimeMillis()
)

data class Vault(val accounts: List<Account> = emptyList(), val locations: List<SavedLocation> = emptyList(), val locationId: String? = null)
data class LoginResult(val token: String, val userId: String, val userName: String)
enum class AuthState { VALID, EXPIRED }
enum class Outcome { SUCCESS, AUTH_EXPIRED, CAPTCHA_REJECTED, FAILURE }
data class SignReply(val outcome: Outcome, val message: String, val captchaPassed: Boolean = false)
data class CaptchaTicket(val parameter: String, val userAgent: String)
data class AccountResult(val accountId: String, val label: String, val outcome: Outcome?, val message: String)

interface SklGateway {
    suspend fun login(username: String, password: String): LoginResult
    suspend fun authState(token: String): AuthState
    suspend fun sign(account: Account, code: String, coordinates: Coordinates, captcha: CaptchaTicket): SignReply
}

interface CaptchaSource {
    suspend fun acquire(label: String): CaptchaTicket
    suspend fun complete(reply: SignReply)
}
