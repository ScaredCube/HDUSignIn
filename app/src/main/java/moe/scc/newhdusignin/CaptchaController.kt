package moe.scc.newhdusignin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.UUID

class CaptchaChallenge(val label: String) {
    val id: String = UUID.randomUUID().toString()
    val ticket = CompletableDeferred<CaptchaTicket>()
    val reply = CompletableDeferred<SignReply>()
    val acknowledged = CompletableDeferred<Unit>()
}

class CaptchaController : CaptchaSource {
    private val mutable = MutableStateFlow<CaptchaChallenge?>(null)
    val challenge = mutable.asStateFlow()

    override suspend fun acquire(label: String): CaptchaTicket {
        val next = CaptchaChallenge(label)
        check(mutable.value == null) { "验证码请求仍在处理中" }
        mutable.value = next
        try { return withTimeout(120000) { next.ticket.await() } }
        catch (error: Throwable) { if (mutable.value === next) mutable.value = null; throw error }
    }

    override suspend fun complete(reply: SignReply) {
        val current = mutable.value ?: return
        current.reply.complete(reply)
        try { withTimeout(2000) { current.acknowledged.await() } }
        catch (_: kotlinx.coroutines.TimeoutCancellationException) { /* WebView may have detached. */ }
        finally { if (mutable.value === current) mutable.value = null }
    }

    fun cancel() {
        mutable.value?.ticket?.cancel(CancellationException("已取消批量签到"))
        mutable.value?.acknowledged?.complete(Unit)
        mutable.value = null
    }
}
