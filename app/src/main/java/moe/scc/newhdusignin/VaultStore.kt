package moe.scc.newhdusignin
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypt the whole vault using a non-exportable Android Keystore key. Backups are disabled. */
class VaultStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "accounts-and-locations.vault"))
    private val mutex = Mutex()
    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey("skl-vault-v1", null) as? SecretKey)?.let { return it }
        check(!file.baseFile.exists()) { "本地加密密钥丢失，无法读取已保存账号" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("skl-vault-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    suspend fun load(): Vault = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.baseFile.exists()) return@withLock Vault()
            val bytes = file.readFully()
            require(bytes.size > 29 && bytes[0] == 1.toByte()) { "本地数据格式损坏" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            decode(JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8)))
        }
    }

    suspend fun save(vault: Vault) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(encode(vault).toString().toByteArray(Charsets.UTF_8))
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        }
    }

    private fun encode(vault: Vault) = JSONObject().apply {
        put("accounts", JSONArray().apply { vault.accounts.forEach { a -> put(JSONObject().apply {
            put("id", a.id); put("alias", a.alias); put("username", a.username); put("password", a.password)
            put("token", a.token); put("userId", a.userId); put("userName", a.userName); put("selected", a.selected)
            put("refreshedAt", a.refreshedAt); put("status", a.status)
        }) } })
        put("locations", JSONArray().apply { vault.locations.forEach { l -> put(JSONObject().apply {
            put("id", l.id); put("alias", l.alias); put("latitude", l.coordinates.latitude)
            put("longitude", l.coordinates.longitude); put("capturedAt", l.capturedAt)
            l.coordinates.accuracy?.let { put("accuracy", it.toDouble()) }
        }) } })
        put("locationId", vault.locationId ?: JSONObject.NULL)
    }

    private fun decode(json: JSONObject): Vault {
        val accounts = json.getJSONArray("accounts").let { rows -> List(rows.length()) { i ->
            val a = rows.getJSONObject(i)
            Account(a.getString("id"), a.getString("alias"), a.getString("username"), a.getString("password"),
                a.optString("token"), a.optString("userId"), a.optString("userName"), a.optBoolean("selected", true),
                a.optLong("refreshedAt"), a.optString("status", "尚未登录"))
        } }
        val locations = json.getJSONArray("locations").let { rows -> List(rows.length()) { i ->
            val l = rows.getJSONObject(i)
            val coords = Coordinates(l.getDouble("latitude"), l.getDouble("longitude"),
                if (l.has("accuracy")) l.getDouble("accuracy").toFloat() else null)
            require(coords.isValid()) { "保存的位置坐标损坏" }
            SavedLocation(l.getString("id"), l.getString("alias"), coords, l.optLong("capturedAt"))
        } }
        return Vault(accounts, locations, if (json.isNull("locationId")) null else json.optString("locationId"))
    }
}
