package dev.androiduse.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    companion object { @Volatile var visible = false; private set }
    private var page = "Agent"
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var composer: EditText? = null
    private var draft = ""
    private val listener: () -> Unit = { if (page == "Agent") render() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        render()
    }
    override fun onResume() { super.onResume(); visible = true; AppState.listeners.add(listener); if (page != "Settings") render() }
    override fun onPause() { visible = false; AppState.listeners.remove(listener); super.onPause() }
    private fun render() {
        composer?.let { draft = it.text.toString() }; composer = null
        root = column().apply { setBackgroundColor(Palette.bg) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(root)
        val header = row().apply { setPadding(dp(24), dp(18), dp(24), dp(14)) }
        header.addView(label("◈  android use", 22f, Palette.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("EARLY ACCESS", 10f, Palette.accent, true).apply { letterSpacing = .1f })
        root.fill(header)
        content = column().apply { setPadding(dp(24), dp(12), dp(24), dp(24)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content); clipToPadding = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        when (page) { "Settings" -> settings(); "Activity" -> history(); else -> home() }
        val nav = row().apply { setPadding(dp(16), dp(10), dp(16), dp(10)); setBackgroundColor(Palette.bg) }
        listOf("Agent", "Activity", "Settings").forEach { tab ->
            nav.addView(action(tab, page == tab) { page = tab; render() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        root.fill(nav)
    }
    private fun card(): LinearLayout = column().apply {
        setPadding(dp(18), dp(18), dp(18), dp(18)); background = background(Palette.surface, dp(20).toFloat(), Palette.border)
    }
    private fun heading(kicker: String, title: String, description: String) {
        content.fill(label(kicker, 11f, Palette.accent, true).apply { letterSpacing = .16f }); content.gap(12)
        content.fill(label(title, 34f, Palette.ink, true).apply { letterSpacing = -.04f; setLineSpacing(0f, 1.05f) }); content.gap(12)
        content.fill(label(description, 15f, Palette.muted)); content.gap(24)
    }
    private fun home() {
        val s = AppState.session
        val running = AgentService.current != null
        if (!running && s == null) heading("YOUR AGENT. YOUR DEVICE.", "Your phone.\nOn your behalf.", "Give it a task. Watch it work. Take over whenever you want.")
        else heading(if (running) "WORKING ON YOUR PHONE" else "READY WHEN YOU ARE", if (running) "One step at a time." else "What’s next?", "The agent runs here. Your chosen model handles the reasoning.")
        val config = Stores.config()
        val connected = config.apiKey.isNotBlank()
        val enabled = PhoneAccessibilityService.instance != null
        val setup = card()
        setup.fill(label("${if (connected) "●" else "○"}  Model connection", 15f, if (connected) Palette.accent else Palette.ink, true)); setup.gap(5)
        setup.fill(label(if (connected) config.model else "Add a provider and your API key", 13f, Palette.muted)); setup.gap(12)
        setup.fill(label("${if (enabled) "●" else "○"}  Phone control", 15f, if (enabled) Palette.accent else Palette.ink, true)); setup.gap(5)
        setup.fill(label(if (enabled) "Accessibility connected · no computer needed" else "Enable the accessibility service to start", 13f, Palette.muted))
        if (!connected || !enabled) {
            setup.gap(16)
            setup.fill(action(if (!connected) "Set up your model  →" else "Enable phone control  →", true) { if (!connected) { page = "Settings"; render() } else disclosure() })
        }
        content.fill(setup); content.gap(16)
        if (s != null) {
            val run = card()
            val color = when(s.status) { "COMPLETE" -> Palette.accent; "ERROR", "INCOMPLETE" -> Palette.error; else -> Palette.warning }
            run.fill(label("${s.status}  ·  STEP ${s.step}", 11f, color, true)); run.gap(10)
            run.fill(label(s.task, 18f, Palette.ink, true)); run.gap(12)
            if (s.summary.isNotBlank()) { run.fill(label(s.summary, 14f, Palette.muted)); run.gap(10) }
            s.events.takeLast(if (running) 4 else 2).forEach { event ->
                run.fill(label("${when(event.kind) { "tool" -> "↳"; "error" -> "!"; "question" -> "?"; else -> "·" }} ${event.text}", 13f, if (event.kind == "error") Palette.error else Palette.muted)); run.gap(7)
            }
            run.fill(label("${s.input} input  ·  ${s.cached} cached  ·  ${s.output} output", 11f, Palette.accent)); run.gap(10)
            run.fill(action("View task details") { showSession(s) })
            if (running) {
                run.gap(10)
                val controls = row()
                controls.addView(action(if (s.status == "PAUSED") "Resume" else "Pause") { AgentService.current?.togglePause() }, LinearLayout.LayoutParams(0, -2, 1f))
                controls.addView(action("Stop") { AgentService.current?.stopRun() }.apply { setTextColor(Palette.error) }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
                run.fill(controls)
            }
            content.fill(run); content.gap(16)
        }
        if (!running || s?.status == "WAITING") {
            val box = card()
            box.fill(label(if (running) "Your reply" else "What would you like done?", 16f, Palette.ink, true)); box.gap(10)
            val input = field(if (running) "Answer or describe the manual step you completed" else "e.g. Open Settings and find Display", draft, multiline = true)
            composer = input; box.fill(input); box.gap(12)
            box.fill(action(if (running) "Continue task  →" else "Start task  →", true) {
                val task = input.text.toString().trim()
                if (task.isBlank()) { toast("Enter a task first."); return@action }
                if (running) { AgentService.current?.reply(task); draft = ""; input.setText("") }
                else startTask(task)
            })
            content.fill(box); content.gap(18)
            if (!running) {
                content.fill(label("TRY A SMALL TASK", 10f, Palette.muted, true)); content.gap(8)
                listOf("Open Settings and find Display", "Open Android Use practice and save a note saying Hello from my phone").forEach { task ->
                    content.fill(action(task) { input.setText(task); input.requestFocus() }); content.gap(8)
                }
                content.gap(6); content.fill(label("Only screen content needed for your task goes to your selected provider. Screenshots are optional.", 12f, Palette.muted))
            }
        }
    }
    private fun startTask(task: String) {
        try { Stores.config().validate() } catch(e: Exception) { toast(e.message.orEmpty()); page = "Settings"; render(); return }
        if (!Stores.consented()) { disclosure(); return }
        if (PhoneAccessibilityService.instance == null) { disclosure(); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(composer?.windowToken, 0)
        draft = ""; composer?.setText("")
        startForegroundService(Intent(this, AgentService::class.java).setAction(AgentService.START).putExtra("task", task.take(12000)))
    }
    private fun disclosure() {
        AlertDialog.Builder(this).setTitle("Let your agent use this phone")
            .setMessage("During tasks you start, Android Use reads the current screen and can tap, type, scroll, and open apps. Screen text and requested screenshots are sent directly to the model provider you choose.\n\nYour API key and task history are encrypted on this device. There is no Android Use server. Stop at any time using the floating control or notification.\n\nOn some sideloaded installs, first open App info → ⋮ → Allow restricted settings, then enable Android Use in Accessibility.")
            .setNegativeButton("Not now", null).setPositiveButton("Agree & open Settings") { _, _ -> Stores.consent(); startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.show()
    }
    private fun field(hint: String, value: String = "", secret: Boolean = false, multiline: Boolean = false): EditText = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 15f; setTextColor(Palette.ink); setHintTextColor(Palette.muted)
        background = background(Palette.bg, dp(12).toFloat(), Palette.border)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        if (multiline) { minLines = 3; maxLines = 6; gravity = Gravity.TOP } else isSingleLine = true
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    private fun settings() {
        heading("MAKE IT YOURS", "Connection & control", "Bring your own key. Choose your model. Keep the agent on your phone.")
        val c = Stores.config()
        val provider = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("OpenAI-compatible", "Anthropic Claude"))
            setSelection(if (c.provider == "anthropic") 1 else 0)
        }
        content.fill(label("Provider", 13f, Palette.muted)); content.fill(provider); content.gap(12)
        content.fill(label("API base URL", 13f, Palette.muted)); content.gap(6)
        val endpoint = field("https://api.openai.com/v1", c.endpoint); content.fill(endpoint); content.gap(14)
        content.fill(label("Model ID", 13f, Palette.muted)); content.gap(6)
        val model = field("A model that supports tools and images", c.model); content.fill(model); content.gap(14)
        content.fill(label("API key", 13f, Palette.muted)); content.gap(6)
        val key = field(if(c.apiKey.isNotBlank()) "Saved securely · leave blank to keep" else "Paste your API key", secret = true); content.fill(key); content.gap(6)
        content.fill(label("Encrypted with Android Keystore. Never included in prompts or task exports.", 12f, Palette.muted)); content.gap(18)
        var initial = true
        provider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (initial) { initial = false; return }
                endpoint.setText(if(position == 1) "https://api.anthropic.com/v1" else "https://api.openai.com/v1")
                model.setText(if(position == 1) "claude-sonnet-4-6" else "gpt-4.1-mini")
            }
        }
        val screenshots = Switch(this).apply { text = "Allow screenshots for visual reasoning"; setTextColor(Palette.ink); isChecked = c.allowScreenshots; textSize = 14f }
        content.fill(screenshots); content.gap(16)
        content.fill(label("Maximum steps per task", 13f, Palette.muted)); content.gap(6)
        val steps = field("24", c.maxSteps.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }; content.fill(steps); content.gap(14)
        content.fill(label("Input-token budget per task (includes cached tokens)", 13f, Palette.muted)); content.gap(6)
        val tokens = field("100000", c.maxInputTokens.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }; content.fill(tokens); content.gap(16)
        fun save(): ProviderConfig {
            check(AgentService.current == null) { "Stop the active task before changing its connection." }
            val chosen = if(provider.selectedItemPosition == 1) "anthropic" else "openai"
            val existingKey = if(chosen == c.provider && endpoint.text.toString().trim().trimEnd('/') == c.endpoint.trimEnd('/')) c.apiKey else ""
            val next = ProviderConfig(chosen, endpoint.text.toString().trim(), model.text.toString().trim(), key.text.toString().trim().ifBlank { existingKey }, steps.text.toString().toIntOrNull() ?: 24, tokens.text.toString().toIntOrNull() ?: 100000, screenshots.isChecked)
            Stores.saveConfig(next); return next
        }
        content.fill(action("Save connection", true) { try { save(); toast("Connection saved securely."); page = "Agent"; render() } catch (e: Exception) { toast(e.message.orEmpty()) } }); content.gap(10)
        val test = action("Test connection") {}
        test.setOnClickListener {
            try {
                val conf = save(); test.isEnabled = false; test.text = "Testing…"
                Thread {
                    val result = try { ProviderClient(conf).infer(Conversation(conf.provider).apply { addUser("Connection test only. Do not call tools. Reply with Ready.") }); "Connection works. The model responded." } catch(e: Exception) { e.message ?: "Connection failed." }
                    runOnUiThread { if (!isDestroyed) { test.isEnabled = true; test.text = "Test connection"; AlertDialog.Builder(this).setTitle("Connection test").setMessage(result).setPositiveButton("OK", null).show() } }
                }.start()
            } catch (e: Exception) { toast(e.message.orEmpty()) }
        }
        content.fill(test); content.gap(6)
        content.fill(label("Testing sends one short model request and may incur a small API charge.", 12f, Palette.muted)); content.gap(22)
        val caching = card()
        caching.fill(label("Built to reuse context", 17f, Palette.accent, true)); caching.gap(8)
        caching.fill(label("Stable instructions and tools. New observations are appended; previous messages stay unchanged. Anthropic caching is enabled. Other providers apply their own caching rules. Actual cache reads appear in each task’s usage.", 13f, Palette.muted))
        content.fill(caching); content.gap(18)
        content.fill(action("Accessibility setup") { disclosure() }); content.gap(10)
        content.fill(action("Open practice notepad") { startActivity(Intent(this, PracticeActivity::class.java)) }); content.gap(10)
        content.fill(action("Remove saved API key") { if(AgentService.current == null) { Stores.removeKey(); toast("API key removed."); render() } else toast("Stop the task first.") }); content.gap(20)
        content.fill(label("Android Use 0.1.0 · Native Android\nRequires Android 11+. Keep the phone unlocked during tasks. Protected screens and apps with limited accessibility may need manual help.", 12f, Palette.muted))
    }
    private fun history() {
        heading("LOCAL & PRIVATE", "Task activity", "An honest record of what ran, what changed, and where it stopped.")
        val sessions = Stores.sessions()
        if (sessions.isEmpty()) {
            val empty = card(); empty.fill(label("A clean slate.", 21f, Palette.ink, true)); empty.gap(10); empty.fill(label("Your completed and interrupted tasks will appear here. History stays encrypted on this phone.", 14f, Palette.muted)); content.fill(empty)
        }
        sessions.forEach { s ->
            val card = card()
            card.fill(label("${s.status}  ·  ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(s.started))}", 11f, if(s.status == "COMPLETE") Palette.accent else Palette.muted, true)); card.gap(8)
            card.fill(label(s.task, 17f, Palette.ink, true)); card.gap(8)
            card.fill(label("${s.step} steps · ${s.cached} cached tokens", 12f, Palette.muted)); card.gap(12)
            card.fill(action("View details") { showSession(s) }); content.fill(card); content.gap(12)
        }
        if (sessions.isNotEmpty()) { content.gap(8); content.fill(action("Clear task history") { AlertDialog.Builder(this).setTitle("Delete local task history?").setMessage("This permanently removes saved tasks from this phone.").setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> try { Stores.clearHistory(); render() } catch(e: Exception) { toast(e.message.orEmpty()) } }.show() }) }
    }
    private fun showSession(s: Session) {
        val report = buildString {
            append("${s.task}\n\n${s.status} · ${s.model}\n${s.step} steps\nInput: ${s.input} · Cache reads: ${s.cached} · Cache writes: ${s.cacheWrite} · Output: ${s.output}\n\n")
            s.events.forEach { append("${it.kind.uppercase()}\n${it.text}\n\n") }
            if (s.pending != null) append("An action has an uncertain outcome. Check the phone before repeating it.\n")
        }
        val view = label(report, 13f, Palette.ink).apply { setPadding(dp(22), dp(16), dp(22), dp(16)); setTextIsSelectable(true); typeface = Typeface.MONOSPACE }
        AlertDialog.Builder(this).setTitle("Task details").setView(ScrollView(this).apply { addView(view) }).setPositiveButton("Close", null)
            .setNeutralButton("Share summary") { _, _ ->
                val summary = "Android Use task report\nStatus: ${s.status}\nModel: ${s.model}\nSteps: ${s.step}\nInput tokens: ${s.input}\nCached tokens: ${s.cached}\nOutput tokens: ${s.output}\n\nTask text, screen contents, and API keys omitted."
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, summary), "Share redacted summary"))
            }.show()
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
