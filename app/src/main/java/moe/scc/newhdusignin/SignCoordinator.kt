package moe.scc.newhdusignin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** One frozen code/location/account snapshot per batch; no empty coordinates or token preflight. */
class SignCoordinator(private val gateway: SklGateway, private val captcha: CaptchaSource) {
    suspend fun sign(
        accounts: List<Account>,
        code: String,
        coordinates: Coordinates,
        saveAccount: suspend (Account) -> Unit,
        progress: (AccountResult) -> Unit
    ): List<AccountResult> {
        require(code.matches(Regex("[0-9]{4}"))) { "请输入完整的四位签到码" }
        require(coordinates.isValid()) { "位置坐标无效，已拒绝发送签到请求" }
        val selected = accounts.filter { it.selected }
        require(selected.isNotEmpty()) { "请先在账号页勾选至少一个账号" }
        return selected.map { original ->
            val label = original.alias.ifBlank { original.username }
            progress(AccountResult(original.id, label, null, "正在签到"))
            try {
                var account = original
                if (account.token.isBlank() || account.userId.isBlank()) {
                    progress(AccountResult(account.id, label, null, "正在登录"))
                    account = refreshed(account)
                    saveAccount(account)
                }
                var reply = attempt(account, label, code, coordinates)
                if (reply.outcome == Outcome.AUTH_EXPIRED) {
                    progress(AccountResult(account.id, label, null, "登录态过期，正在刷新并重试"))
                    account = refreshed(account)
                    saveAccount(account)
                    // The first captcha may have been consumed. Always acquire a new one.
                    reply = attempt(account, "$label · 重试", code, coordinates)
                }
                AccountResult(account.id, label, reply.outcome, reply.message).also(progress)
            } catch (_: TimeoutCancellationException) {
                AccountResult(original.id, label, Outcome.FAILURE, "验证码等待超时，请重新签到").also(progress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AccountResult(original.id, label, Outcome.FAILURE, error.message ?: "请求失败").also(progress)
            }
        }
    }

    private suspend fun refreshed(account: Account): Account {
        val login = gateway.login(account.username, account.password)
        return account.copy(token = login.token, userId = login.userId, userName = login.userName,
            refreshedAt = System.currentTimeMillis(), status = "登录态已刷新")
    }

    private suspend fun attempt(account: Account, label: String, code: String, coordinates: Coordinates): SignReply {
        val ticket = captcha.acquire(label)
        try {
            val reply = gateway.sign(account, code, coordinates, ticket)
            captcha.complete(reply)
            return reply
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            captcha.complete(SignReply(Outcome.FAILURE, error.message ?: "请求失败"))
            throw error
        }
    }
}
