package moe.scc.newhdusignin
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AppState(
    val vault: Vault = Vault(),
    val loaded: Boolean = false,
    val fatalError: String? = null,
    val message: String? = null,
    val busy: Boolean = false,
    val showProgress: Boolean = false,
    val batchRunning: Boolean = false,
    val operation: String = "",
    val code: String = "",
    val liveMode: Boolean = true,
    val liveCoordinates: Coordinates? = null,
    val locating: Boolean = false,
    val usedLocation: UsedLocation? = null,
    val results: List<AccountResult> = emptyList()
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = VaultStore(application)
    private val gateway: SklGateway = SklApi()
    private val location = PhoneLocation(application)
    val captcha = CaptchaController()
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private var batch: Job? = null
    private var locationJob: Job? = null

    init {
        viewModelScope.launch {
            try { val vault = store.load(); mutable.update { it.copy(vault = vault, loaded = true) } }
            catch (_: Exception) { mutable.update { it.copy(fatalError = "无法读取加密数据。请勿覆盖原数据；若已清除系统密钥，需要重新安装并重新添加账号。") } }
        }
    }

    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun message(text: String) { mutable.update { it.copy(message = text) } }
    fun digit(value: String) {
        if (!mutable.value.busy && value.matches(Regex("[0-9]"))) mutable.update { it.copy(code = (it.code + value).take(4)) }
    }
    fun backspace() { if (!mutable.value.busy) mutable.update { it.copy(code = it.code.dropLast(1)) } }
    fun clearCode() { if (!mutable.value.busy) mutable.update { it.copy(code = "") } }
    fun liveMode(live: Boolean) { if (!mutable.value.busy) mutable.update { it.copy(liveMode = live) } }

    private suspend fun mutateVault(transform: (Vault) -> Vault) = mutex.withLock {
        val next = transform(mutable.value.vault)
        store.save(next)
        mutable.update { it.copy(vault = next) }
    }

    private fun action(label: String, showProgress: Boolean = false, block: suspend () -> Unit) {
        if (mutable.value.busy || !mutable.value.loaded || mutable.value.fatalError != null) return
        mutable.update { it.copy(busy = true, operation = label, showProgress = showProgress) }
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message(error.message ?: "操作失败") }
            finally { mutable.update { it.copy(busy = false, operation = "", showProgress = false) } }
        }
    }

    fun selectAccount(id: String, selected: Boolean) = action("保存选择") {
        mutateVault { v -> v.copy(accounts = v.accounts.map { if (it.id == id) it.copy(selected = selected) else it }) }
    }
    fun selectAll(selected: Boolean) = action("保存选择") {
        mutateVault { it.copy(accounts = it.accounts.map { a -> a.copy(selected = selected) }) }
    }
    fun selectLocation(id: String) = action("选择位置") {
        mutateVault { it.copy(locationId = id) }
        mutable.update { it.copy(liveMode = false) }
    }

    fun saveAccount(existing: Account?, alias: String, username: String, password: String, onSaved: () -> Unit) = action("正在登录并保存账号", showProgress = true) {
        val name = username.trim()
        require(name.matches(Regex("[0-9]{6,20}"))) { "请输入 6–20 位数字学号" }
        require(mutable.value.vault.accounts.none { it.username == name && it.id != existing?.id }) { "该学号已保存，请编辑已有账号" }
        val secret = if (password.isBlank() && existing?.username == name) existing.password else password
        require(secret.isNotEmpty() && secret.length <= 128) { "请输入账号密码" }
        val account = if (existing != null && existing.username == name && password.isBlank()) {
            existing.copy(alias = alias.trim().ifBlank { existing.userName.ifBlank { name } })
        } else {
            val login = gateway.login(name, secret)
            val displayName = if (existing == null) login.userName.trim().ifBlank { name }
                else alias.trim().ifBlank { login.userName.trim().ifBlank { name } }
            Account(id = existing?.id ?: java.util.UUID.randomUUID().toString(), alias = displayName,
                username = name, password = secret, token = login.token, userId = login.userId, userName = login.userName,
                selected = existing?.selected ?: true, refreshedAt = System.currentTimeMillis(), status = "登录态有效")
        }
        mutateVault { it.copy(accounts = if (existing == null) it.accounts + account else it.accounts.map { old -> if (old.id == account.id) account else old }) }
        message("账号已保存")
        onSaved()
    }

    fun deleteAccount(id: String) = action("删除账号") {
        mutateVault { it.copy(accounts = it.accounts.filterNot { a -> a.id == id }) }
        mutable.update { it.copy(results = it.results.filterNot { r -> r.accountId == id }) }
    }

    fun refreshAccounts(onlyId: String? = null) = action("正在刷新登录态", showProgress = true) {
        val accounts = mutable.value.vault.accounts.filter { onlyId == null || it.id == onlyId }
        var success = 0
        for ((index, account) in accounts.withIndex()) {
            mutable.update { it.copy(operation = "刷新 ${index + 1}/${accounts.size} · ${account.alias}") }
            val updated = try {
                val login = gateway.login(account.username, account.password)
                success++
                account.copy(token = login.token, userId = login.userId, userName = login.userName,
                    refreshedAt = System.currentTimeMillis(), status = "登录态已刷新")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { account.copy(status = "刷新失败：${error.message ?: "网络异常"}") }
            mutateVault { it.copy(accounts = it.accounts.map { old -> if (old.id == updated.id) updated else old }) }
        }
        message("刷新完成：$success 成功，${accounts.size - success} 失败")
    }

    fun saveLocation(existing: SavedLocation?, alias: String, latitude: String, longitude: String, onSaved: () -> Unit) = action("保存位置") {
        val coords = Coordinates(latitude.toDoubleOrNull() ?: Double.NaN, longitude.toDoubleOrNull() ?: Double.NaN)
        require(coords.isValid()) { "请输入有效坐标：纬度 −90～90，经度 −180～180" }
        require(alias.trim().isNotBlank()) { "请填写位置别名" }
        val saved = SavedLocation(existing?.id ?: java.util.UUID.randomUUID().toString(), alias.trim(), coords)
        mutateVault { v -> v.copy(locations = if (existing == null) v.locations + saved else v.locations.map { if (it.id == saved.id) saved else it },
            locationId = v.locationId ?: saved.id) }
        message("位置已保存")
        onSaved()
    }

    fun deleteLocation(id: String) = action("删除位置") {
        mutateVault { v -> val remaining = v.locations.filterNot { it.id == id }
            v.copy(locations = remaining, locationId = if (v.locationId == id) remaining.firstOrNull()?.id else v.locationId) }
    }

    fun hasLocationPermission() = location.permitted()
    fun locate(onResult: (Coordinates) -> Unit = {}) {
        if (mutable.value.locating || mutable.value.busy) return
        mutable.update { it.copy(locating = true, liveCoordinates = null) }
        locationJob = viewModelScope.launch {
            try {
                val coordinates = location.current()
                mutable.update { it.copy(liveCoordinates = coordinates) }
                onResult(coordinates)
            } catch (_: TimeoutCancellationException) { message("定位超时，请使用保存的位置或稍后重试") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message(error.message ?: "定位失败") }
            finally { mutable.update { it.copy(locating = false) }; locationJob = null }
        }
    }

    fun sign() {
        val snapshot = mutable.value
        if (snapshot.busy || snapshot.locating || !snapshot.loaded) return
        if (snapshot.code.length != 4) { message("请输入完整的四位签到码"); return }
        val selected = snapshot.vault.accounts.filter { it.selected }
        if (selected.isEmpty()) { message("请在账号页勾选至少一个账号"); return }
        val saved = snapshot.vault.locations.find { it.id == snapshot.vault.locationId }
        if (!snapshot.liveMode && saved == null) { message("请先添加并选择一个位置"); return }
        mutable.update { it.copy(busy = true, batchRunning = true, operation = "准备签到", usedLocation = null,
            results = selected.map { a -> AccountResult(a.id, a.alias, null, "等待签到") }) }
        batch = viewModelScope.launch {
            try {
                if (snapshot.liveMode) mutable.update { it.copy(operation = "获取实时定位") }
                val used = LocationResolver.resolve(snapshot.liveMode, saved) { location.current() }
                if (snapshot.liveMode && used.fromSaved) message("实时定位不可用，本次使用保存位置：${used.label}")
                mutable.update { it.copy(operation = "正在批量签到", usedLocation = used,
                    liveCoordinates = if (!used.fromSaved) used.coordinates else null) }
                val results = SignCoordinator(gateway, captcha).sign(selected, snapshot.code, used.coordinates,
                    saveAccount = { account -> mutateVault { it.copy(accounts = it.accounts.map { old -> if (old.id == account.id) account else old }) } },
                    progress = { result -> mutable.update { it.copy(results = it.results.map { old -> if (old.accountId == result.accountId) result else old }) } })
                val successes = results.count { it.outcome == Outcome.SUCCESS }
                message("签到完成：$successes 成功，${results.size - successes} 失败")
                if (successes == results.size) mutable.update { it.copy(code = "") }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(results = it.results.map { r -> if (r.outcome == null) r.copy(outcome = Outcome.FAILURE, message = "已取消，未确认签到结果") else r }) }
                message("已停止批量签到；已发送的请求可能仍由学校处理")
                throw cancelled
            } catch (error: Exception) {
                val text = error.message ?: "签到失败"
                mutable.update { it.copy(results = it.results.map { r -> if (r.outcome == null) r.copy(outcome = Outcome.FAILURE, message = text) else r }) }
                message(text)
            } finally {
                captcha.cancel()
                mutable.update { it.copy(busy = false, batchRunning = false, operation = "") }
            }
        }
    }

    fun cancelBatch() { batch?.cancel(); captcha.cancel() }
    override fun onCleared() { captcha.cancel(); locationJob?.cancel(); super.onCleared() }
}
