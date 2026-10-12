@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package moe.scc.newhdusignin
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun coordinateText(coords: Coordinates) = String.format(Locale.US, "%.6f, %.6f", coords.latitude, coords.longitude)
private fun timeText(time: Long) = if (time <= 0) "尚未刷新" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(time))

@Composable
fun SklApp(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val challenge by vm.captcha.challenge.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var accountEditor by rememberSaveable { mutableStateOf<String?>(null) }
    var locationEditor by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteLocation by rememberSaveable { mutableStateOf<String?>(null) }
    var locationCallback by remember { mutableStateOf<(Coordinates) -> Unit>({}) }
    var permissionForSign by remember { mutableStateOf(false) }
    var autoLocated by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (permissionForSign) { permissionForSign = false; vm.sign() }
        else if (grants.values.any { it }) vm.locate(locationCallback)
        else vm.message("未授权定位，请选择保存的位置；没有可用位置时不会发送签到请求")
    }
    fun locate(callback: (Coordinates) -> Unit = {}) {
        locationCallback = callback
        if (vm.hasLocationPermission()) vm.locate(callback)
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(vm) {
        vm.state.map { it.message }.filterNotNull().collect { message ->
            vm.clearMessage()
            snackbar.showSnackbar(message)
        }
    }
    LaunchedEffect(state.loaded) {
        if (state.loaded && !autoLocated) {
            autoLocated = true
            if (state.liveMode) locate()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text(listOf("HDUSignIn", "账号", "位置")[tab], fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                listOf("签到" to Icons.Outlined.TaskAlt, "账号" to Icons.Outlined.PeopleOutline, "位置" to Icons.Outlined.LocationOn)
                    .forEachIndexed { index, (title, icon) ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icon, title) }, label = { Text(title) })
                    }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state.fatalError != null -> EmptyCard(Icons.Outlined.Lock, "本地数据无法读取", state.fatalError!!)
                !state.loaded -> CircularProgressIndicator(Modifier.padding(48.dp))
                else -> Column(Modifier.widthIn(max = 620.dp).fillMaxSize()) {
                    if (state.busy && state.showProgress && !state.batchRunning) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(state.operation, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    when (tab) {
                        0 -> SignPage(state, vm, { locate() }, { tab = 1 }, { tab = 2 }, {
                            if (state.liveMode && !vm.hasLocationPermission()) {
                                permissionForSign = true
                                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            } else vm.sign()
                        })
                        1 -> AccountPage(state, vm, { accountEditor = "new" }, { accountEditor = it }, { deleteAccount = it })
                        2 -> LocationPage(state, vm, { locationEditor = "new" }, { locationEditor = it }, { deleteLocation = it })
                    }
                }
            }
        }
    }
    if (accountEditor != null) {
        val existing = state.vault.accounts.find { it.id == accountEditor }
        AccountEditor(existing, state.busy, { if (!state.busy) accountEditor = null }) { alias, username, password ->
            vm.saveAccount(existing, alias, username, password) { accountEditor = null }
        }
    }
    if (locationEditor != null) {
        val existing = state.vault.locations.find { it.id == locationEditor }
        LocationEditor(existing, state.busy, state.locating, { callback -> locate(callback) }, { if (!state.busy) locationEditor = null }) { alias, latitude, longitude ->
            vm.saveLocation(existing, alias, latitude, longitude) { locationEditor = null }
        }
    }
    deleteAccount?.let { id -> ConfirmDelete("删除账号", "将移除该账号在本机保存的密码和登录态。", { deleteAccount = null }) {
        vm.deleteAccount(id); deleteAccount = null
    } }
    deleteLocation?.let { id -> ConfirmDelete("删除位置", "该位置将从保存列表和签到位置选项中移除。", { deleteLocation = null }) {
        vm.deleteLocation(id); deleteLocation = null
    } }
    challenge?.let { key(it.id) { CaptchaDialog(it) { vm.cancelBatch() } } }
}

@Composable
private fun SignPage(state: AppState, vm: AppViewModel, locate: () -> Unit, accounts: () -> Unit, locations: () -> Unit, sign: () -> Unit) {
    val count = state.vault.accounts.count { it.selected }
    val view = LocalView.current
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("准备好，轻松签到", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("同一签到码，逐个处理所选账号", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AssistChip(onClick = accounts, label = { Text("$count 个账号") }, leadingIcon = { Icon(Icons.Outlined.PeopleOutline, null, Modifier.size(16.dp)) })
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("输入四位签到码", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) { index ->
                        val active = state.code.length == index && !state.busy
                        Box(Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(if (active) 2.dp else 1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center) {
                            Text(state.code.getOrNull(index)?.toString() ?: "·", fontSize = 32.sp, fontWeight = FontWeight.Medium,
                                color = if (index < state.code.length) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
        LocationSelector(state, vm, locate, locations)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("clear", "0", "back")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { key ->
                        FilledTonalButton(onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            when (key) {
                                "clear" -> vm.clearCode()
                                "back" -> vm.backspace()
                                else -> {
                                    val was = vm.state.value.code.length
                                    vm.digit(key)
                                    if (was == 3 && vm.state.value.code.length == 4 && !vm.state.value.busy && !vm.state.value.locating) sign()
                                }
                            }
                        },
                            enabled = !state.busy, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            when (key) {
                                "clear" -> Text("清空", style = MaterialTheme.typography.labelLarge)
                                "back" -> Icon(Icons.AutoMirrored.Outlined.Backspace, "删除一位")
                                else -> Text(key, fontSize = 23.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
        if (state.batchRunning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.operation, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = vm::cancelBatch, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("停止批量签到") }
        } else {
            Button(onClick = sign, enabled = !state.busy && !state.locating && count > 0 && state.code.length == 4,
                modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Outlined.TaskAlt, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("为 $count 个账号签到", fontSize = 16.sp)
            }
        }
        if (state.results.isNotEmpty()) {
            Text("本次结果", style = MaterialTheme.typography.titleMedium)
            state.usedLocation?.let { used ->
                Text("本次位置：${used.label} · ${coordinateText(used.coordinates)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.results.forEach { result ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (result.outcome == Outcome.SUCCESS) Icons.Outlined.CheckCircle else if (result.outcome == null) Icons.Outlined.Schedule else Icons.Outlined.ErrorOutline,
                            null, tint = if (result.outcome == Outcome.SUCCESS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Column { Text(result.label, fontWeight = FontWeight.Medium); Text(result.message, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ScaredCube/HDUSignIn")))
        }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text("在 GitHub 上查看 / 反馈问题", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun LocationSelector(state: AppState, vm: AppViewModel, locate: () -> Unit, manage: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val saved = state.vault.locations.find { it.id == state.vault.locationId }
    val title = if (state.liveMode) "实时定位" else saved?.alias ?: "选择位置"
    val subtitle = if (state.liveMode) {
        when {
            state.locating -> "正在获取定位…"
            state.liveCoordinates != null -> coordinateText(state.liveCoordinates!!)
            else -> "打开应用自动定位"
        }
    } else saved?.coordinates?.let(::coordinateText) ?: "无可用坐标时拒绝签到"
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Outlined.LocationOn, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ExpandMore, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("实时定位") },
                leadingIcon = { Icon(if (state.liveMode) Icons.Outlined.Check else Icons.Outlined.MyLocation, null) },
                onClick = { expanded = false; vm.liveMode(true); locate() })
            state.vault.locations.forEach { item ->
                DropdownMenuItem(text = { Column { Text(item.alias); Text(coordinateText(item.coordinates), style = MaterialTheme.typography.bodySmall) } },
                    leadingIcon = { Icon(if (!state.liveMode && item.id == saved?.id) Icons.Outlined.Check else Icons.Outlined.LocationOn, null) },
                    onClick = { expanded = false; vm.selectLocation(item.id) })
            }
            DropdownMenuItem(text = { Text("管理位置") }, onClick = { expanded = false; manage() }, leadingIcon = { Icon(Icons.Outlined.EditLocationAlt, null) })
        }
    }
}

@Composable
private fun AccountPage(state: AppState, vm: AppViewModel, add: () -> Unit, edit: (String) -> Unit, delete: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("管理你的签到账号", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("勾选的账号参与批量签到，密码与登录态加密保存在本机。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = add, enabled = !state.busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(4.dp)); Text("新增账号") }
            OutlinedButton(onClick = { vm.refreshAccounts() }, enabled = !state.busy && state.vault.accounts.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("刷新全部")
            }
        }
        if (state.vault.accounts.isEmpty()) EmptyCard(Icons.Outlined.PeopleOutline, "还没有账号", "新增并登录账号后，即可开始签到。")
        else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("已选择 ${state.vault.accounts.count { it.selected }} / ${state.vault.accounts.size}", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { vm.selectAll(state.vault.accounts.any { !it.selected }) }, enabled = !state.busy) {
                    Text(if (state.vault.accounts.all { it.selected }) "取消全选" else "全选")
                }
            }
            state.vault.accounts.forEach { account ->
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(account.selected, { vm.selectAccount(account.id, it) }, enabled = !state.busy)
                            Column(Modifier.weight(1f)) {
                                Text(account.alias, style = MaterialTheme.typography.titleMedium)
                                Text(account.username, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { edit(account.id) }, enabled = !state.busy) { Icon(Icons.Outlined.Edit, "编辑 ${account.alias}") }
                        }
                        Text(account.status, style = MaterialTheme.typography.bodySmall,
                            color = if (account.status.startsWith("刷新失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("上次刷新 ${timeText(account.refreshedAt)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = { vm.refreshAccounts(account.id) }, enabled = !state.busy) { Icon(Icons.Outlined.Refresh, "刷新 ${account.alias}") }
                            IconButton(onClick = { delete(account.id) }, enabled = !state.busy) { Icon(Icons.Outlined.DeleteOutline, "删除 ${account.alias}") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationPage(state: AppState, vm: AppViewModel, add: () -> Unit, edit: (String) -> Unit, delete: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("把常用位置留在这里", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("用手机定位新增位置，也可以编辑别名和经纬度。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = add, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.AddLocationAlt, null); Spacer(Modifier.width(8.dp)); Text("新增位置")
        }
        if (state.vault.locations.isEmpty()) EmptyCard(Icons.Outlined.Explore, "还没有保存的位置", "添加一个常用位置，实时定位不可用时仍有可选坐标。")
        state.vault.locations.forEach { location ->
            val selected = location.id == state.vault.locationId
            Surface(shape = RoundedCornerShape(24.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(42.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.LocationOn, null, tint = MaterialTheme.colorScheme.primary) }
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(location.alias, style = MaterialTheme.typography.titleMedium)
                            Text(coordinateText(location.coordinates), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { edit(location.id) }, enabled = !state.busy) { Icon(Icons.Outlined.Edit, "编辑 ${location.alias}") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.selectLocation(location.id) }, enabled = !state.busy && !selected) {
                            if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp))
                            Text(if (selected) "当前签到位置" else "用于签到")
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { delete(location.id) }, enabled = !state.busy) { Icon(Icons.Outlined.DeleteOutline, "删除 ${location.alias}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountEditor(existing: Account?, busy: Boolean, dismiss: () -> Unit, save: (String, String, String) -> Unit) {
    var alias by rememberSaveable(existing?.id) { mutableStateOf(existing?.alias ?: "") }
    var username by rememberSaveable(existing?.id) { mutableStateOf(existing?.username ?: "") }
    // Passwords stay only in transient memory until securely saved, never in saved-instance state.
    var password by remember(existing?.id) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (existing == null) "新增并登录账号" else "编辑账号") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (existing != null) OutlinedTextField(alias, { alias = it }, label = { Text("账号别名") }, singleLine = true, enabled = !busy)
            OutlinedTextField(username, { username = it.filter(Char::isDigit).take(20) }, label = { Text("学号") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy)
            OutlinedTextField(password, { password = it }, label = { Text(if (existing == null) "密码" else "新密码（留空保留）") }, singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy,
                trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "切换密码可见性") } })
            Text(if (existing == null) "账号名称将自动取自登录结果。密码仅加密保存在本机，用于登录态过期后的自动刷新。" else "修改学号或密码时将重新登录；只修改别名会保留当前登录态。",
                style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = { save(alias, username, password) }, enabled = !busy && username.length >= 6 && (existing != null || password.isNotEmpty())) { Text(if (busy) "正在处理…" else "保存") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } })
}

@Composable
private fun LocationEditor(existing: SavedLocation?, busy: Boolean, locating: Boolean, locate: ((Coordinates) -> Unit) -> Unit, dismiss: () -> Unit,
    save: (String, String, String) -> Unit) {
    var alias by rememberSaveable(existing?.id) { mutableStateOf(existing?.alias ?: "") }
    var latitude by rememberSaveable(existing?.id) { mutableStateOf(existing?.coordinates?.latitude?.toString() ?: "") }
    var longitude by rememberSaveable(existing?.id) { mutableStateOf(existing?.coordinates?.longitude?.toString() ?: "") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (existing == null) "新增位置" else "编辑位置") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(alias, { alias = it }, label = { Text("位置别名") }, placeholder = { Text("例如：教学楼 A") }, singleLine = true, enabled = !busy)
            OutlinedButton(onClick = { locate { point -> latitude = point.latitude.toString(); longitude = point.longitude.toString() } }, enabled = !busy && !locating,
                modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.MyLocation, null); Spacer(Modifier.width(8.dp)); Text(if (locating) "正在获取定位…" else "使用手机当前位置") }
            if (locating) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(latitude, { latitude = it }, label = { Text("纬度（−90～90）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !busy)
            OutlinedTextField(longitude, { longitude = it }, label = { Text("经度（−180～180）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !busy)
            Text("手机定位返回 WGS84 坐标。手动编辑时请使用相同坐标系。", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { save(alias, latitude, longitude) }, enabled = !busy && !locating && alias.isNotBlank() && latitude.isNotBlank() && longitude.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } })
}

@Composable
fun EmptyCard(icon: ImageVector, title: String, description: String) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 12.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConfirmDelete(title: String, description: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(description) },
        confirmButton = { TextButton(onClick = confirm) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
