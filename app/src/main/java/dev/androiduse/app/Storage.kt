package dev.androiduse.app

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class UseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Stores.init(this)
        Stores.sessions().firstOrNull()?.let { previous ->
            val it = if (previous.status in setOf("RUNNING", "PAUSED", "WAITING")) Stores.loadSession(previous.id) ?: previous else previous
            if (it.status in setOf("RUNNING", "PAUSED", "WAITING")) {
                it.status = "INTERRUPTED"
                it.interruptInteractions()
                it.summary = "Android stopped the previous process. Review its last action before starting a new task."
                it.log("system", it.summary)
                Stores.saveSession(it)
            }
            AppState.session = it
        }
    }
}

object Vault {
    private const val ALIAS = "android-use-local-v1"
    @Synchronized private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun decrypt(text: String): String {
        val bytes = Base64.decode(text, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}

data class RunEvent(val kind: String, val text: String, val time: Long = System.currentTimeMillis(), val details: String = "", val state: String = "", val attachments: List<Attachment> = emptyList())
class Session(val task: String, val id: String = UUID.randomUUID().toString(), val started: Long = System.currentTimeMillis()) {
    @Volatile var status = "RUNNING"
    @Volatile var summary = ""
    @Volatile var step = 0
    @Volatile var input = 0
    @Volatile var cached = 0
    @Volatile var output = 0
    @Volatile var cacheWrite = 0
    var provider = ""
    var model = ""
    var endpoint = ""
    var reasoning = "default"
    var pending: JSONObject? = null
    var conversation = Conversation("openai")
    val events = CopyOnWriteArrayList<RunEvent>()
    fun log(kind: String, text: String, attachments: List<Attachment> = emptyList()) { events.add(RunEvent(kind, text.take(if (kind in setOf("user", "agent", "question", "result")) 16000 else 3000), attachments = attachments)); AppState.changed() }
    fun startInteraction(call: ToolCall): Int {
        val title = when (call.name) {
            "observe" -> "Read screen"; "screenshot" -> "Take screenshot"; "tap" -> "Tap"; "long_press" -> "Long press"
            "set_text" -> "Enter text"; "ime_action" -> "Press enter"; "scroll" -> "Scroll"; "swipe" -> "Swipe"
            "navigate" -> "Navigate ${call.args.optString("action")}"; "list_apps" -> "Find apps"
            "open_app" -> "Open app"; "wait" -> "Wait"; else -> call.name.replace('_', ' ')
        }
        val index = events.size
        events.add(RunEvent("tool", title, details = call.args.toString(2).take(3000), state = "running"))
        AppState.changed(); return index
    }
    fun finishInteraction(index: Int, state: String, outcome: String) {
        if (index !in events.indices) return
        val old = events[index]
        events[index] = old.copy(state = state, details = "${old.details}\n\n$outcome")
        AppState.changed()
    }
    fun interruptInteractions() {
        events.indices.filter { events[it].state == "running" }.forEach {
            finishInteraction(it, "unknown", "Interrupted. Check the current screen before repeating this action.")
        }
    }
    fun json() = obj("id" to id, "task" to task, "started" to started, "status" to status, "summary" to summary,
        "step" to step, "input" to input, "cached" to cached, "output" to output, "cacheWrite" to cacheWrite,
        "provider" to provider, "model" to model, "endpoint" to endpoint, "reasoning" to reasoning, "pending" to pending, "messages" to conversation.messages,
        "events" to JSONArray().also { a -> events.forEach { a.put(obj("kind" to it.kind, "text" to it.text, "time" to it.time, "details" to it.details, "state" to it.state, "attachments" to JSONArray(it.attachments.map { a -> a.json() }))) } })
    companion object {
        fun from(j: JSONObject, transcript: Boolean = false): Session = Session(j.getString("task"), j.getString("id"), j.getLong("started")).apply {
            status = j.getString("status"); summary = j.optString("summary"); step = j.optInt("step")
            input = j.optInt("input"); cached = j.optInt("cached"); output = j.optInt("output"); cacheWrite = j.optInt("cacheWrite")
            reasoning = j.optString("reasoning", "default")
            provider = j.optString("provider"); model = j.optString("model"); endpoint = j.optString("endpoint"); pending = j.optJSONObject("pending")
            // Loading history does not resume inference or retain all image payloads
            // from up to 40 old transcripts in the UI heap. The encrypted journal stays on disk.
            conversation = Conversation(provider, if (transcript) j.optJSONArray("messages") ?: JSONArray() else JSONArray())
            j.optJSONArray("events")?.objects()?.forEach { events.add(RunEvent(it.getString("kind"), it.getString("text"), it.getLong("time"), it.optString("details"), it.optString("state"), it.optJSONArray("attachments")?.objects()?.map(Attachment::from) ?: emptyList())) }
        }
    }
}

object AppState {
    @Volatile var session: Session? = null
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun changed() { main.post { listeners.forEach { it() } } }
}

object Stores {
    private lateinit var context: Context
    fun init(ctx: Context) {
        context = ctx.applicationContext
        Attachments.init(context)
        migrateTokenDefault()
    }
    fun migrateTokenDefault() {
        if (!prefs.getBoolean("token_default_v3", false)) {
            val edit = prefs.edit().putBoolean("token_default_v3", true)
            if (prefs.getInt("tokens", 100000) == 100000) edit.putInt("tokens", ProviderConfig.DEFAULT_INPUT_TOKENS)
            check(edit.commit()) { "Could not update token settings." }
        }
    }
    private val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun config(): ProviderConfig = ProviderConfig(
        prefs.getString("provider", "openai")!!, prefs.getString("endpoint", "https://api.openai.com/v1")!!,
        prefs.getString("model", "gpt-4.1-mini")!!,
        prefs.getString("credential", null)?.let { try { Vault.decrypt(it) } catch (_: Exception) { "" } } ?: "",
        prefs.getInt("steps", 24), prefs.getInt("tokens", ProviderConfig.DEFAULT_INPUT_TOKENS), prefs.getBoolean("screenshots", true), prefs.getString("reasoning", "default")!!)
    fun saveConfig(c: ProviderConfig) {
        c.validate()
        check(prefs.edit().putString("provider", c.provider).putString("endpoint", c.endpoint.trimEnd('/')).putString("model", c.model)
            .putString("credential", Vault.encrypt(c.apiKey)).putInt("steps", c.maxSteps).putInt("tokens", c.maxInputTokens)
            .putBoolean("screenshots", c.allowScreenshots).putString("reasoning", c.reasoning).commit()) { "Could not save settings." }
    }
    fun autoDisablePhoneControl() = prefs.getBoolean("auto_disable_phone_control", false)
    fun setAutoDisablePhoneControl(enabled: Boolean) { prefs.edit().putBoolean("auto_disable_phone_control",enabled).apply() }
    fun consented() = prefs.getBoolean("disclosure", false)
    fun consent() { prefs.edit().putBoolean("disclosure", true).apply() }
    fun removeKey() { prefs.edit().remove("credential").commit() }
    private fun folder() = File(context.filesDir, "sessions").apply { mkdirs() }
    @Synchronized fun saveSession(s: Session) {
        val file = AtomicFile(File(folder(), "${s.id}.json.enc"))
        val bytes = Vault.encrypt(s.json().toString()).toByteArray()
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
        folder().listFiles()?.filter { it.name.endsWith(".enc") }?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { expired ->
            runCatching { Session.from(JSONObject(Vault.decrypt(AtomicFile(expired).readFully().toString(Charsets.UTF_8)))) }
                .getOrNull()?.events?.flatMap { it.attachments }?.forEach { Attachments.delete(it.id) }
            expired.delete()
        }
    }
    @Synchronized fun sessions(): List<Session> = folder().listFiles()?.filter { it.name.endsWith(".enc") }?.sortedByDescending { it.lastModified() }?.mapNotNull {
        try { Session.from(JSONObject(Vault.decrypt(AtomicFile(it).readFully().toString(Charsets.UTF_8)))) } catch (_: Exception) { null }
    } ?: emptyList()
    @Synchronized fun loadSession(id: String): Session? {
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "Invalid conversation ID." }
        return try {
            val bytes = AtomicFile(File(folder(), "$id.json.enc")).readFully()
            Session.from(JSONObject(Vault.decrypt(bytes.toString(Charsets.UTF_8))), transcript = true)
        } catch (_: Exception) { null }
    }
    fun clearHistory() { check(AgentService.current == null) { "Stop the active task first." }; Attachments.clear(); folder().listFiles()?.forEach { it.delete() }; AppState.session = null; AppState.changed() }
}
