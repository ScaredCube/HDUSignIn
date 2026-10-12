package moe.scc.newhdusignin
import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private const val BASE = "https://skl.hdu.edu.cn"
private const val SSO = "sso.hdu.edu.cn"
private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
private data class HttpReply(val status: Int, val text: String, val location: URI?) {
    val json: JSONObject? get() = try { JSONObject(text) } catch (_: Exception) { null }
}

/** Direct school requests. Every login owns its cookie jar; credentials never enter the captcha WebView. */
class SklApi : SklGateway {
    private val random = SecureRandom()
    private val alphabet = "useandom-26T198340PX75pxJACKVERYMINDBUSHWOLF_GQZbfghjklqvwyzrict"
    private fun ticket(): String = CharArray(21) { alphabet[random.nextInt(64)] }.concatToString()
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun query(values: Map<String, String>) = values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    private fun school(uri: URI) {
        require(uri.scheme == "https" && (uri.host == "hdu.edu.cn" || uri.host?.endsWith(".hdu.edu.cn") == true)
            && (uri.port == -1 || uri.port == 443) && uri.userInfo == null) { "学校登录跳转地址不受信任" }
    }

    private fun request(uri: URI, method: String = "GET", token: String = "", body: String? = null,
        jar: CookieManager? = null, userAgent: String = UA, ssoPost: Boolean = false): HttpReply {
        school(uri)
        if (ssoPost) require(uri.host == SSO) { "拒绝向非 SSO 地址发送登录信息" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12000
            connection.readTimeout = 15000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json, text/html, */*")
            connection.setRequestProperty("User-Agent", userAgent.replace(Regex("[\r\n]"), " ").take(500))
            if (uri.host == "skl.hdu.edu.cn") {
                connection.setRequestProperty("skl-ticket", ticket())
                connection.setRequestProperty("Referer", "$BASE/index.html")
                if (token.isNotBlank()) connection.setRequestProperty("X-Auth-Token", token)
            }
            jar?.get(uri, emptyMap())?.forEach { (name, values) -> connection.setRequestProperty(name, values.joinToString("; ")) }
            if (method == "POST") connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            if (ssoPost) {
                connection.setRequestProperty("Origin", "https://$SSO")
                connection.setRequestProperty("Referer", uri.toString())
            }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            jar?.put(uri, connection.headerFields.filterKeys { it != null })
            val text = (if (status >= 400) connection.errorStream else connection.inputStream)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    check(output.size() + size <= 2 * 1024 * 1024) { "学校接口响应过大" }
                    output.write(buffer, 0, size)
                }
                output.toString("UTF-8")
            } ?: ""
            return HttpReply(status, text, connection.getHeaderField("Location")?.let { uri.resolve(it) })
        } finally { connection.disconnect() }
    }

    private fun api(path: String, token: String = "", values: Map<String, String> = emptyMap(),
        method: String = "GET", userAgent: String = UA): HttpReply =
        request(URI("$BASE$path${if (values.isEmpty()) "" else "?${query(values)}"}"), method, token, userAgent = userAgent)

    private fun userInfo(token: String) = api("/api/userinfo", token, mapOf("type" to "", "index" to "index.html"))

    override suspend fun authState(token: String): AuthState = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext AuthState.EXPIRED
        val response = userInfo(token)
        if (response.status in listOf(301, 302, 303, 401, 403) || response.json?.optString("url")?.isNotBlank() == true)
            return@withContext AuthState.EXPIRED
        check(response.status == 200 && response.json?.optString("id")?.isNotBlank() == true) { "无法校验登录态，请检查网络后重试" }
        AuthState.VALID
    }

    @SuppressLint("GetInstance") // School SSO protocol mandates ECB; local credential storage uses AES-GCM.
    override suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        require(username.matches(Regex("[0-9]{6,20}")) && password.isNotEmpty() && password.length <= 128) { "学号或密码格式不正确" }
        val jar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        val entry = userInfo("")
        val loginUrl = entry.json?.optString("url")?.takeIf { it.isNotBlank() } ?: error("学校未返回登录地址")
        var uri = URI(loginUrl)
        var response = request(uri, jar = jar)
        repeat(10) {
            if (response.status in 300..399 && response.location != null) {
                uri = response.location!!
                response = request(uri, jar = jar)
            }
        }
        require(uri.host == SSO && response.status == 200) { "无法打开学校 SSO 登录页" }
        fun element(id: String): String = Regex("<[^>]+id=[\"']${Regex.escape(id)}[\"'][^>]*>([^<]*)<", RegexOption.IGNORE_CASE)
            .find(response.text)?.groupValues?.get(1)?.trim() ?: ""
        val flow = element("login-page-flowkey")
        val cryptoKey = element("login-croypto")
        check(flow.isNotBlank() && cryptoKey.isNotBlank()) { "登录页需要验证码或已改版，无法自动登录" }
        val key = Base64.getDecoder().decode(cryptoKey)
        require(key.size in listOf(16, 24, 32)) { "学校登录密钥格式异常" }
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)))
        val form = query(mapOf("username" to username, "password" to encrypted, "type" to "UsernamePassword",
            "_eventId" to "submit", "geolocation" to "", "execution" to flow, "captcha_code" to "", "croypto" to cryptoKey))
        response = request(uri, "POST", body = form, jar = jar, ssoPost = true)
        check(response.status in listOf(301, 302, 303) && response.location != null) { "登录失败，请检查密码；学校登录验证码暂不支持" }
        var token = ""
        repeat(12) {
            val redirect = response.location
            if (redirect != null) {
                school(redirect)
                tokenFrom(redirect)?.let { token = it }
                if (token.isBlank()) { uri = redirect; response = request(uri, jar = jar) }
            }
        }
        check(token.isNotBlank()) { "登录完成但未获取到上课啦 token" }
        val probe = userInfo(token)
        val user = probe.json
        check(probe.status == 200 && user?.optString("id")?.isNotBlank() == true && user.optString("url").isBlank()) { "学校返回的登录态无效" }
        val profile = user!!
        val displayName = profile.optString("userName").ifBlank { profile.optString("teacherName") }
        LoginResult(token, profile.get("id").toString(), displayName)
    }

    private fun tokenFrom(uri: URI): String? {
        val hash = uri.rawFragment ?: return null
        val parameters = hash.substringAfter('?', hash)
        return parameters.split('&').firstNotNullOfOrNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.size == 2 && pieces[0] == "token") URLDecoder.decode(pieces[1], "UTF-8").takeIf { it.isNotBlank() } else null
        }
    }

    override suspend fun sign(account: Account, code: String, coordinates: Coordinates, captcha: CaptchaTicket): SignReply = withContext(Dispatchers.IO) {
        require(coordinates.isValid() && code.matches(Regex("[0-9]{4}"))) { "签到码或坐标无效" }
        require(account.token.isNotBlank() && account.userId.isNotBlank() && captcha.parameter.isNotBlank()) { "登录态或验证码参数缺失" }
        // Match the existing school's endpoint: POST with query parameters, not a fabricated captcha.
        val response = api("/api/ali-nvc/captcha-verify", account.token, mapOf(
            "captchaVerifyParam" to captcha.parameter, "userid" to account.userId, "code" to code,
            "latitude" to coordinates.latitude.toString(), "longitude" to coordinates.longitude.toString(),
            "t" to System.currentTimeMillis().toString()), "POST", captcha.userAgent)
        val json = response.json
        val captchaCode = json?.optString("captchaVerifyCode").orEmpty()
        val passed = json?.optBoolean("captchaVerifyResult", false) == true
        val dto = json?.optJSONObject("checkCodeDto")
        if (response.status == 200 && passed && dto != null && (!dto.has("success") || dto.optBoolean("success")))
            return@withContext SignReply(Outcome.SUCCESS, "签到成功", true)
        if (response.status in listOf(301, 302, 303, 401, 403) || json?.optString("url")?.isNotBlank() == true) {
            // 401 also means a wrong code on this endpoint: prove token expiry before relogging.
            if (authState(account.token) == AuthState.EXPIRED)
                return@withContext SignReply(Outcome.AUTH_EXPIRED, "登录态已过期", passed)
        }
        if (captchaCode.isNotBlank() && captchaCode != "T001")
            return@withContext SignReply(Outcome.CAPTCHA_REJECTED, "验证码未通过（$captchaCode），请重新签到")
        val message = json?.optString("msg")?.takeIf { it.isNotBlank() }
            ?: json?.optString("message")?.takeIf { it.isNotBlank() }
            ?: when (response.status) {
                401 -> "签到码错误、签到未开始或课程不匹配"
                414 -> "验证码参数过长，学校网关拒绝请求"
                in 500..599 -> "学校接口暂时不可用（${response.status}）"
                else -> "签到失败（HTTP ${response.status}）"
            }
        SignReply(Outcome.FAILURE, message.take(240), passed)
    }
}
