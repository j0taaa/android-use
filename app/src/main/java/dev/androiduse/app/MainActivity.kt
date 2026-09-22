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
    private var selected: Session? = null
    private var awaitingStart = false
    private var page = "Chat"
    private var draft = ""
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var drawer: ChatDrawer
    private lateinit var history: LinearLayout
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var empty: TextView
    private lateinit var composer: EditText
    private lateinit var send: ChatIcon
    private lateinit var status: TextView
    private var rendered = ""
    private val listener: () -> Unit = {
        val active=AppState.session
        if (awaitingStart || (selected != null && active?.id == selected?.id)) { selected=active; awaitingStart=false }
        if(page=="Chat") updateChat()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        selected = savedInstanceState?.getString("chat_id")?.let { id ->
            AppState.session?.takeIf { it.id==id } ?: Stores.sessions().firstOrNull { it.id==id }
        } ?: if(savedInstanceState==null && AgentService.current!=null) AppState.session else null
        draft=savedInstanceState?.getString("draft").orEmpty()
        showChat()
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("chat_id", selected?.id); outState.putString("draft", if(page=="Chat") composer.text.toString() else draft)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); visible=true; AppState.listeners.add(listener); listener() }
    override fun onPause() { visible=false; AppState.listeners.remove(listener); super.onPause() }
    @Deprecated("Platform back callback for Android 11 compatibility")
    override fun onBackPressed() { handleBack() }
    private fun handleBack() { when { page=="Settings" -> showChat(); drawer.isOpen -> drawer.close(); else -> finish() } }
    private fun base() {
        root=column().apply { setBackgroundColor(Palette.bg) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        setContentView(root)
    }
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(composer.windowToken,0); composer.clearFocus() }
    private fun newChat() {
        hideKeyboard(); draft=""; selected=null; awaitingStart=false
        showChat()
    }
    private fun showChat() {
        page="Chat"; rendered=""; base()
        drawer=ChatDrawer(this)
        val main=column()
        val header=row().apply { setPadding(dp(10),dp(4),dp(10),dp(4)) }
        header.addView(ChatIcon(this,"menu","Open chat history") { hideKeyboard(); drawer.open() },LinearLayout.LayoutParams(dp(48),dp(48)))
        header.addView(label("Android Use",18f,Palette.ink,true).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(0,-2,1f))
        header.addView(ChatIcon(this,"new","New chat") { newChat() },LinearLayout.LayoutParams(dp(48),dp(48)))
        main.fill(header)
        val center=FrameLayout(this)
        messages=column().apply { setPadding(dp(22),dp(20),dp(22),dp(20)) }
        scroll=ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=false; addView(messages) }
        center.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        empty=label("How can I help?",28f,Palette.ink,true).apply { gravity=Gravity.CENTER; letterSpacing=-.025f; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES }
        center.addView(empty,FrameLayout.LayoutParams(-1,-1))
        main.addView(center,LinearLayout.LayoutParams(-1,0,1f))
        status=label("",13f,Palette.muted).apply { gravity=Gravity.CENTER; setPadding(dp(20),dp(8),dp(20),dp(8)); setOnClickListener { selected?.let { if(it.status=="PAUSED") AgentService.current?.resumeRun() else showSession(it) } } }
        main.fill(status)
        val footer=column().apply { setPadding(dp(14),dp(4),dp(14),dp(12)) }
        val compose=row().apply { gravity=Gravity.BOTTOM; background=background(Palette.surface,dp(28).toFloat()); setPadding(dp(8),dp(5),dp(5),dp(5)) }
        composer=field("Message",draft,multiline=true).apply { background=null; minHeight=dp(46); maxLines=5; setPadding(dp(12),dp(12),dp(8),dp(12)); contentDescription="Message" }
        compose.addView(composer,LinearLayout.LayoutParams(0,-2,1f))
        send=ChatIcon(this,"send","Send message",true) { sendMessage() }
        compose.addView(send,LinearLayout.LayoutParams(dp(46),dp(46)))
        footer.fill(compose); main.fill(footer)
        history=column().apply { setBackgroundColor(Palette.bg); setPadding(dp(14),dp(12),dp(14),dp(12)) }
        drawer.attach(main,history); drawer.onOpening={ populateHistory() }
        root.addView(drawer,LinearLayout.LayoutParams(-1,0,1f))
        updateChat(forceBottom=true)
    }
    private fun updateChat(forceBottom: Boolean = false) {
        val s=selected
        val active=AgentService.current!=null && AppState.session?.id==s?.id
        val waiting=active && s?.status=="WAITING"
        val fingerprint="${s?.id}:${s?.events?.size}:${s?.status}:${s?.step}:$active"
        if(fingerprint!=rendered) {
            val atBottom=scroll.getChildAt(0).height-scroll.height-scroll.scrollY<dp(120)
            val oldScroll=scroll.scrollY
            messages.removeAllViews(); rendered=fingerprint
            empty.visibility=if(s==null) View.VISIBLE else View.GONE
            if(s!=null) {
                val entries=mutableListOf<RunEvent>()
                if(s.events.firstOrNull { it.kind=="user" }?.text!=s.task) entries.add(RunEvent("user",s.task))
                entries.addAll(s.events.filter { it.kind in setOf("user","agent","question","result") })
                if(s.summary.isNotBlank() && entries.none { it.kind=="result" && it.text==s.summary }) entries.add(RunEvent("result",s.summary))
                entries.forEachIndexed { index,e ->
                    if(index==0 || entries[index-1].text!=e.text || entries[index-1].kind=="user" || e.kind=="user") addMessage(e.text,e.kind=="user")
                }
                if(!active) {
                    messages.fill(label("View activity",12f,Palette.muted).apply { setPadding(0,dp(12),0,dp(12)); setOnClickListener { showSession(s) } })
                }
            }
            scroll.post { if(forceBottom || atBottom) scroll.fullScroll(View.FOCUS_DOWN) else scroll.scrollTo(0,oldScroll) }
        }
        status.text=when { awaitingStart -> "Starting…"; active && s?.status=="PAUSED" -> "Paused · Tap to resume"; waiting -> "Your reply is needed"; active -> "Working on your phone…"; AgentService.current!=null -> "Another chat is running"; else -> "" }
        status.visibility=if(status.text.isEmpty()) View.GONE else View.VISIBLE
        composer.isEnabled=!awaitingStart && (!active || waiting)
        composer.hint=if(waiting) "Reply" else "Message"
        send.show(if(active && !waiting) "stop" else "send",if(active && !waiting) "Stop agent" else "Send message")
        send.isEnabled=!awaitingStart
    }
    private fun addMessage(text: String,user: Boolean) {
        val container=row().apply { gravity=if(user) Gravity.END else Gravity.START }
        val message=label(text,16f).apply {
            setTextIsSelectable(true); setLineSpacing(dp(4).toFloat(),1f)
            if(user) { background=background(Palette.surface,dp(22).toFloat()); setPadding(dp(16),dp(12),dp(16),dp(12)); maxWidth=(resources.displayMetrics.widthPixels*.82f).toInt() }
        }
        container.addView(message,LinearLayout.LayoutParams(if(user) -2 else -1,-2))
        messages.fill(container); messages.gap(24)
    }
    private fun populateHistory() {
        history.removeAllViews()
        val top=row()
        top.addView(label("Chats",22f,Palette.ink,true),LinearLayout.LayoutParams(0,-2,1f))
        top.addView(ChatIcon(this,"new","Start a new chat") { newChat() },LinearLayout.LayoutParams(dp(48),dp(48)))
        history.fill(top); history.gap(20)
        val list=column()
        val sessions=Stores.sessions().toMutableList()
        AppState.session?.takeIf { AgentService.current!=null }?.let { active -> sessions.removeAll { it.id==active.id }; sessions.add(0,active) }
        var previousDate=""
        sessions.forEach { s ->
            val date=SimpleDateFormat("MMM d",Locale.getDefault()).format(Date(s.started))
            if(date!=previousDate) { list.gap(16); list.fill(label(date,12f,Palette.muted).apply { setPadding(dp(12),0,0,dp(8)) }); previousDate=date }
            list.fill(label(s.task.replace('\n',' '),15f).apply {
                maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END; minHeight=dp(52); gravity=Gravity.CENTER_VERTICAL
                setPadding(dp(12),dp(12),dp(12),dp(12))
                if(s.id==selected?.id) background=background(Palette.surface,dp(12).toFloat())
                isClickable=true; isFocusable=true
                setOnClickListener { draft=""; selected=AppState.session?.takeIf { it.id==s.id } ?: s; showChat() }
            })
        }
        if(sessions.isEmpty()) list.fill(label("No chats yet",14f,Palette.muted).apply { setPadding(dp(12),dp(16),0,0) })
        history.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; addView(list) },LinearLayout.LayoutParams(-1,0,1f))
        val settings=row().apply { setPadding(0,dp(8),0,0); isClickable=true; isFocusable=true; contentDescription="Settings"; setOnClickListener { showSettings() } }
        settings.addView(ChatIcon(this,"settings","Open settings") { showSettings() },LinearLayout.LayoutParams(dp(48),dp(48)))
        settings.addView(label("Settings",16f,Palette.ink,true)); history.fill(settings)
    }
    private fun sendMessage() {
        val active=AgentService.current
        if(active!=null) {
            if(AppState.session?.id!=selected?.id) { toast("Stop the running chat before sending another message."); return }
            if(selected?.status!="WAITING") { active.stopRun(); return }
            val reply=composer.text.toString().trim(); if(reply.isBlank()) return
            active.reply(reply); composer.setText(""); draft=""; hideKeyboard(); return
        }
        val task=composer.text.toString().trim(); if(task.isBlank()) return
        val config=Stores.config()
        try { config.validate() } catch(_:Exception) { draft=task; showSettings(); toast("Add your API connection to start chatting."); return }
        if(!Stores.consented() || PhoneAccessibilityService.instance==null) { disclosure(); return }
        selected?.let { s ->
            if(s.endpoint.isBlank()) {
                AlertDialog.Builder(this).setTitle("Earlier chat").setMessage("This chat was saved before conversation replies were supported. Start a new chat to continue; its history is still available here.").setPositiveButton("OK",null).show(); return
            }
            if(s.provider!=config.provider || s.model!=config.model || s.endpoint!=config.endpoint.trimEnd('/')) {
                AlertDialog.Builder(this).setTitle("Different connection").setMessage("Restore this chat’s provider, endpoint and model in Settings, or start a new chat.").setPositiveButton("OK",null).show(); return
            }
        }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),4)
        hideKeyboard(); draft=""; composer.setText(""); awaitingStart=true; updateChat()
        startForegroundService(Intent(this,AgentService::class.java).setAction(AgentService.START).putExtra("task",task.take(12000)).putExtra("session_id",selected?.id))
    }
    private fun showSettings() {
        if(page=="Chat") { draft=composer.text.toString(); hideKeyboard() }
        page="Settings"; base()
        val header=row().apply { setPadding(dp(10),dp(4),dp(16),dp(4)) }
        header.addView(ChatIcon(this,"back","Back to chat") { showChat() },LinearLayout.LayoutParams(dp(48),dp(48)))
        header.addView(label("Settings",20f,Palette.ink,true)); root.fill(header)
        content=column().apply { setPadding(dp(24),dp(18),dp(24),dp(24)) }
        root.addView(ScrollView(this).apply { addView(content) },LinearLayout.LayoutParams(-1,0,1f))
        settings()
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
        if (multiline) { minLines = 1; maxLines = 6; gravity = Gravity.TOP } else isSingleLine = true
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    private fun settings() {
        val c = Stores.config()
        val provider = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("OpenAI-compatible", "Anthropic Claude"))
            setSelection(if (c.provider == "anthropic") 1 else 0)
        }
        content.fill(label("Provider", 13f, Palette.muted)); content.fill(provider); content.gap(12)
        content.fill(label("API base URL", 13f, Palette.muted)); content.gap(6)
        val endpoint = field("https://api.openai.com/v1", c.endpoint); content.fill(endpoint); content.gap(14)
        content.fill(label("Model ID", 13f, Palette.muted)); content.gap(6)
        val model = field("Model ID", c.model); content.fill(model); content.gap(14)
        content.fill(label("API key", 13f, Palette.muted)); content.gap(6)
        val key = field(if(c.apiKey.isNotBlank()) "Saved securely · leave blank to keep" else "Paste your API key", secret = true); content.fill(key); content.gap(6)
        content.gap(12)
        var initial = true
        provider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (initial) { initial = false; return }
                endpoint.setText(if(position == 1) "https://api.anthropic.com/v1" else "https://api.openai.com/v1")
                model.setText(if(position == 1) "claude-sonnet-4-6" else "gpt-4.1-mini")
            }
        }
        val screenshots = Switch(this).apply { text = "Allow screenshots"; setTextColor(Palette.ink); isChecked = c.allowScreenshots; textSize = 14f }
        content.fill(screenshots); content.gap(16)
        content.fill(label("Maximum steps per message", 13f, Palette.muted)); content.gap(6)
        val steps = field("24", c.maxSteps.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }; content.fill(steps); content.gap(14)
        content.fill(label("Input-token budget per message", 13f, Palette.muted)); content.gap(6)
        val tokens = field("100000", c.maxInputTokens.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }; content.fill(tokens); content.gap(16)
        fun save(): ProviderConfig {
            check(AgentService.current == null) { "Stop the active task before changing its connection." }
            val chosen = if(provider.selectedItemPosition == 1) "anthropic" else "openai"
            val existingKey = if(chosen == c.provider && endpoint.text.toString().trim().trimEnd('/') == c.endpoint.trimEnd('/')) c.apiKey else ""
            val next = ProviderConfig(chosen, endpoint.text.toString().trim(), model.text.toString().trim(), key.text.toString().trim().ifBlank { existingKey }, steps.text.toString().toIntOrNull() ?: 24, tokens.text.toString().toIntOrNull() ?: 100000, screenshots.isChecked)
            Stores.saveConfig(next); return next
        }
        content.fill(action("Save", true) { try { save(); toast("Settings saved"); showChat() } catch (e: Exception) { toast(e.message.orEmpty()) } }); content.gap(10)
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
        content.fill(action("Accessibility setup") { disclosure() }); content.gap(10)
        content.fill(action("Open practice notepad") { startActivity(Intent(this, PracticeActivity::class.java)) }); content.gap(10)
        content.fill(action("Remove saved API key") { if(AgentService.current == null) { Stores.removeKey(); toast("API key removed."); showSettings() } else toast("Stop the task first.") }); content.gap(20)
        content.fill(action("Delete all chats") {
            AlertDialog.Builder(this).setTitle("Delete all chats?").setMessage("This removes saved conversations from this phone.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                    try { Stores.clearHistory(); selected = null; toast("Chats deleted") } catch (e: Exception) { toast(e.message.orEmpty()) }
                }.show()
        }); content.gap(20)
        content.fill(label("Android Use ${BuildConfig.VERSION_NAME}", 12f, Palette.muted))
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
