package moe.scc.newhdusignin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SignCoordinatorTest {
    private val point = Coordinates(30.318, 120.34)
    private fun account(id: String = "a", selected: Boolean = true) = Account(id, id, "12345678", "password", "saved-token", "user-id", selected = selected)
    private class Gateway : SklGateway {
        val tokens = mutableListOf<String>()
        val captchas = mutableListOf<String>()
        val codes = mutableListOf<String>()
        val coordinates = mutableListOf<Coordinates>()
        var logins = 0
        var probes = 0
        var failLogin = false
        val replies = ArrayDeque<SignReply>()
        override suspend fun login(username: String, password: String): LoginResult {
            logins++
            if (failLogin) error("登录失败")
            return LoginResult("new-token", "user-id", "name")
        }
        override suspend fun authState(token: String): AuthState { probes++; return AuthState.VALID }
        override suspend fun sign(account: Account, code: String, coordinates: Coordinates, captcha: CaptchaTicket): SignReply {
            tokens += account.token; captchas += captcha.parameter; codes += code; this.coordinates += coordinates
            return if (replies.isEmpty()) SignReply(Outcome.SUCCESS, "成功", true) else replies.removeFirst()
        }
    }
    private class Captcha : CaptchaSource {
        var acquired = 0
        val completed = mutableListOf<SignReply>()
        override suspend fun acquire(label: String) = CaptchaTicket("one-time-${++acquired}", "ua")
        override suspend fun complete(reply: SignReply) { completed += reply }
    }

    @Test fun `saved token is submitted before any refresh or probe`() = runTest {
        val api = Gateway(); val captcha = Captcha()
        val result = SignCoordinator(api, captcha).sign(listOf(account()), "0123", point, {}, {})
        assertEquals(listOf("saved-token"), api.tokens)
        assertEquals(0, api.logins); assertEquals(0, api.probes)
        assertEquals(listOf("0123"), api.codes)
        assertEquals(Outcome.SUCCESS, result.single().outcome)
    }

    @Test fun `expired token refreshes exactly once and uses a new captcha`() = runTest {
        val api = Gateway(); val captcha = Captcha(); val saved = mutableListOf<Account>()
        api.replies += SignReply(Outcome.AUTH_EXPIRED, "过期")
        val result = SignCoordinator(api, captcha).sign(listOf(account()), "1234", point, { saved += it }, {})
        assertEquals(listOf("saved-token", "new-token"), api.tokens)
        assertEquals(listOf("one-time-1", "one-time-2"), api.captchas)
        assertEquals(1, api.logins); assertEquals("new-token", saved.single().token)
        assertEquals(Outcome.SUCCESS, result.single().outcome)
    }

    @Test fun `second expiry does not loop indefinitely`() = runTest {
        val api = Gateway()
        repeat(2) { api.replies += SignReply(Outcome.AUTH_EXPIRED, "过期") }
        val result = SignCoordinator(api, Captcha()).sign(listOf(account()), "1234", point, {}, {})
        assertEquals(1, api.logins); assertEquals(2, api.tokens.size)
        assertEquals(Outcome.AUTH_EXPIRED, result.single().outcome)
    }

    @Test fun `business or captcha failure does not refresh credentials`() = runTest {
        val api = Gateway()
        api.replies += SignReply(Outcome.FAILURE, "密令错误", true)
        api.replies += SignReply(Outcome.CAPTCHA_REJECTED, "风控未通过")
        val result = SignCoordinator(api, Captcha()).sign(listOf(account("a"), account("b")), "1234", point, {}, {})
        assertEquals(0, api.logins)
        assertEquals(listOf(Outcome.FAILURE, Outcome.CAPTCHA_REJECTED), result.map { it.outcome })
    }

    @Test fun `only selected accounts sign and each gets its own captcha`() = runTest {
        val api = Gateway()
        val result = SignCoordinator(api, Captcha()).sign(listOf(account("a"), account("b", false), account("c")), "1234", point, {}, {})
        assertEquals(listOf("a", "c"), result.map { it.accountId })
        assertEquals(listOf("one-time-1", "one-time-2"), api.captchas)
        assertEquals(listOf(point, point), api.coordinates)
    }

    @Test fun `invalid coordinates stop before captcha login or network`() = runTest {
        for (invalid in listOf(Coordinates(Double.NaN, 120.0), Coordinates(91.0, 120.0), Coordinates(30.0, Double.POSITIVE_INFINITY))) {
            val api = Gateway(); val captcha = Captcha()
            try { SignCoordinator(api, captcha).sign(listOf(account()), "1234", invalid, {}, {}); fail("应拒绝无效坐标") }
            catch (_: IllegalArgumentException) {}
            assertEquals(0, captcha.acquired); assertEquals(0, api.tokens.size); assertEquals(0, api.logins)
        }
    }

    @Test fun `incomplete code stops before any network operation`() = runTest {
        val api = Gateway(); val captcha = Captcha()
        for (code in listOf("", "123", "12345", "abcd")) {
            try { SignCoordinator(api, captcha).sign(listOf(account()), code, point, {}, {}); fail("应拒绝非四位密令") }
            catch (_: IllegalArgumentException) {}
        }
        assertEquals(0, captcha.acquired); assertEquals(0, api.tokens.size)
    }

    @Test fun `refresh failure does not stop later accounts`() = runTest {
        val api = Gateway(); api.failLogin = true
        api.replies += SignReply(Outcome.AUTH_EXPIRED, "过期")
        val result = SignCoordinator(api, Captcha()).sign(listOf(account("a"), account("b")), "1234", point, {}, {})
        assertEquals(listOf(Outcome.FAILURE, Outcome.SUCCESS), result.map { it.outcome })
        assertEquals(2, api.tokens.size)
    }

    @Test fun `cancellation sends no request for waiting accounts`() = runTest {
        val api = Gateway()
        val cancel = object : CaptchaSource {
            override suspend fun acquire(label: String): CaptchaTicket = throw CancellationException("cancel")
            override suspend fun complete(reply: SignReply) {}
        }
        try { SignCoordinator(api, cancel).sign(listOf(account("a"), account("b")), "1234", point, {}, {}); fail("应保留取消信号") }
        catch (_: CancellationException) {}
        assertEquals(0, api.tokens.size)
    }

    @Test fun `captcha timeout records failure and continues to next account`() = runTest {
        val api = Gateway()
        var request = 0
        val captcha = object : CaptchaSource {
            override suspend fun acquire(label: String): CaptchaTicket {
                if (request++ == 0) withTimeout(10) { delay(100) }
                return CaptchaTicket("fresh", "ua")
            }
            override suspend fun complete(reply: SignReply) {}
        }
        val result = SignCoordinator(api, captcha).sign(listOf(account("a"), account("b")), "1234", point, {}, {})
        assertEquals(listOf(Outcome.FAILURE, Outcome.SUCCESS), result.map { it.outcome })
        assertEquals(1, api.tokens.size)
    }

    @Test fun `location resolver chooses a valid fresh live position`() = runTest {
        val live = Coordinates(31.2, 121.5)
        val used = LocationResolver.resolve(true, SavedLocation(alias = "备用", coordinates = point)) { live }
        assertEquals(live, used.coordinates); assertFalse(used.fromSaved); assertEquals("实时定位", used.label)
    }

    @Test fun `live timeout falls back to explicitly selected saved location`() = runTest {
        val backup = SavedLocation(alias = "教学楼", coordinates = point)
        val used = LocationResolver.resolve(true, backup) { throw TimeoutCancellationException("timeout") }
        assertEquals(point, used.coordinates); assertTrue(used.fromSaved); assertEquals("教学楼", used.label)
    }

    @Test fun `live failure without a valid saved location is rejected`() = runTest {
        for (backup in listOf(null, SavedLocation(alias = "损坏", coordinates = Coordinates(Double.NaN, 0.0)))) {
            try { LocationResolver.resolve(true, backup) { error("定位拒绝") }; fail("应拒绝签到") }
            catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun `saved-only mode never invokes live locator`() = runTest {
        var called = false
        val used = LocationResolver.resolve(false, SavedLocation(alias = "图书馆", coordinates = point)) {
            called = true; error("不应尝试实时定位")
        }
        assertFalse(called); assertTrue(used.fromSaved); assertEquals(point, used.coordinates)
    }

    @Test fun `cancellation is not treated as a location failure fallback`() = runTest {
        try { LocationResolver.resolve(true, SavedLocation(alias = "备用", coordinates = point)) { throw CancellationException("cancel") }; fail("取消必须传播") }
        catch (_: CancellationException) {}
    }
}
