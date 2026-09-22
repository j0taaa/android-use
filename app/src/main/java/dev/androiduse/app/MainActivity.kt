package dev.androiduse.app

import android.Manifest
import android.animation.ValueAnimator
import android.view.HapticFeedbackConstants
import android.view.animation.PathInterpolator
import java.util.concurrent.Executors
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
    private lateinit var attach: ChatIcon
    private lateinit var attachmentTray: LinearLayout
    private var attachmentRows = ""
    private var pickerKey: String? = null
    private val draftKey get() = selected?.id ?: "new"
    private lateinit var send: ChatIcon
    private lateinit var status: TextView
    private lateinit var chatBody: FrameLayout
    private var displayedSessionId: String? = null
    private var displayedEntries = emptyList<RunEvent>()
    private val expandedTools = mutableSetOf<Int>()
    private val chatDrafts = mutableMapOf<String, String>()
    private val chatScrolls = mutableMapOf<String, Int>()
    private var switching = false
    private var transitionVersion = 0
    private val historyWorker = Executors.newSingleThreadExecutor()
    private var historyLoading = false
    private var historyGeneration = 0
    private var historySessions = emptyList<Session>()
    private var historyRows = emptyList<String>()
    private val listener: () -> Unit = {
        val active=AppState.session
        if (awaitingStart || (selected != null && active?.id == selected?.id)) { selected=active; awaitingStart=false }
        if(page=="Chat" && !switching) updateChat()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        selected = savedInstanceState?.getString("chat_id")?.let { id ->
            AppState.session?.takeIf { it.id==id } ?: Stores.sessions().firstOrNull { it.id==id }
        } ?: if(savedInstanceState==null && AgentService.current!=null) AppState.session else null
        draft=savedInstanceState?.getString("draft").orEmpty()
        pickerKey=savedInstanceState?.getString("picker_key")
        showChat()
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("picker_key",pickerKey)
        outState.putString("chat_id", selected?.id); outState.putString("draft", if(page=="Chat") composer.text.toString() else draft)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); visible=true; AppState.listeners.add(listener); listener(); refreshHistory() }
    override fun onPause() { visible=false; AppState.listeners.remove(listener); super.onPause() }
    override fun onDestroy() {
        transitionVersion++; historyWorker.shutdownNow()
        if(::chatBody.isInitialized) chatBody.animate().cancel()
        super.onDestroy()
    }
    private fun refreshHistory() {
        if(historyLoading || historyWorker.isShutdown) return
        historyLoading=true
        val generation=historyGeneration
        historyWorker.execute {
            val sessions=Stores.sessions()
            runOnUiThread {
                historyLoading=false
                if(!isDestroyed && generation==historyGeneration) { historySessions=sessions; if(page=="Chat" && drawer.isOpen) populateHistory() }
            }
        }
    }
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
        chatDrafts.remove("new")
        if(selected==null && !switching) {
            if(Attachments.busy("new")) return
            Attachments.ids("new").forEach { Attachments.remove("new",it) }; renderAttachments()
            composer.setText(""); draft=""; drawer.close() }
        else switchConversation(null)
    }
    private fun switchConversation(next: Session?) {
        if(next?.id==selected?.id && !switching) { drawer.close(); return }
        hideKeyboard()
        val oldKey=selected?.id ?: "new"
        chatDrafts[oldKey]=composer.text.toString(); chatScrolls[oldKey]=scroll.scrollY
        drawer.close()
        val version=++transitionVersion
        switching=true
        chatBody.animate().cancel()
        fun replace() {
            if(version!=transitionVersion || page!="Chat" || isDestroyed) return
            selected=next?.let { AppState.session?.takeIf { active -> active.id==it.id } ?: it }
            awaitingStart=false; expandedTools.clear()
            draft=chatDrafts[selected?.id ?: "new"].orEmpty(); composer.setText(draft)
            updateChat(forceBottom=true)
            val position=chatScrolls[selected?.id ?: "new"]
            if(position!=null) scroll.post { if(version==transitionVersion) scroll.scrollTo(0,position) }
            switching=false
            chatBody.translationY=dp(8).toFloat()
            chatBody.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(PathInterpolator(.22f,1f,.36f,1f)).start()
        }
        if(!ValueAnimator.areAnimatorsEnabled()) { replace(); chatBody.animate().cancel(); chatBody.alpha=1f; chatBody.translationY=0f }
        else chatBody.animate().alpha(0f).translationY(-dp(6).toFloat()).setDuration(100).withEndAction { replace() }.start()
    }
    private fun showChat() {
        page="Chat"; displayedEntries=emptyList(); displayedSessionId=null; base()
        drawer=ChatDrawer(this)
        val main=column()
        val header=row().apply { setPadding(dp(10),dp(4),dp(10),dp(4)) }
        header.addView(ChatIcon(this,"menu","Open chat history") { hideKeyboard(); drawer.open() },LinearLayout.LayoutParams(dp(48),dp(48)))
        header.addView(label("Android Use",18f,Palette.ink,true).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(0,-2,1f))
        header.addView(ChatIcon(this,"new","New chat") { newChat() },LinearLayout.LayoutParams(dp(48),dp(48)))
        main.fill(header)
        val center=FrameLayout(this)
        chatBody=center
        messages=column().apply { setPadding(dp(22),dp(20),dp(22),dp(20)) }
        scroll=ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=false; addView(messages) }
        center.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        empty=label("How can I help?",28f,Palette.ink,true).apply { gravity=Gravity.CENTER; letterSpacing=-.025f; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES }
        center.addView(empty,FrameLayout.LayoutParams(-1,-1))
        main.addView(center,LinearLayout.LayoutParams(-1,0,1f))
        status=label("",13f,Palette.muted).apply { gravity=Gravity.CENTER; setPadding(dp(20),dp(8),dp(20),dp(8)); setOnClickListener { haptic(); selected?.let { if(it.status=="PAUSED") AgentService.current?.resumeRun() else showSession(it) } } }
        main.fill(status)
        val footer=column().apply { setPadding(dp(14),dp(4),dp(14),dp(12)) }
        attachmentRows=""
        attachmentTray=column()
        footer.fill(attachmentTray)
        val compose=row().apply { gravity=Gravity.BOTTOM; background=background(Palette.surface,dp(28).toFloat()); setPadding(dp(8),dp(5),dp(5),dp(5)) }
        attach=ChatIcon(this,"attach","Add attachments") { chooseAttachments() }
        compose.addView(attach,LinearLayout.LayoutParams(dp(42),dp(46)))
        composer=field("Message",draft,multiline=true).apply { background=null; minHeight=dp(46); maxLines=5; setPadding(dp(12),dp(12),dp(8),dp(12)); contentDescription="Message" }
        compose.addView(composer,LinearLayout.LayoutParams(0,-2,1f))
        send=ChatIcon(this,"send","Send message",true) { sendMessage() }
        compose.addView(send,LinearLayout.LayoutParams(dp(46),dp(46)))
        footer.fill(compose); main.fill(footer)
        historyRows=emptyList()
        history=column().apply { setBackgroundColor(Palette.bg); setPadding(dp(14),dp(12),dp(14),dp(12)) }
        drawer.attach(main,history); drawer.onOpening={ populateHistory(); refreshHistory() }
        root.addView(drawer,LinearLayout.LayoutParams(-1,0,1f))
        updateChat(forceBottom=true)
    }
    private fun updateChat(forceBottom: Boolean = false) {
        val s=selected
        val active=AgentService.current!=null && AppState.session?.id==s?.id
        val waiting=active && s?.status=="WAITING"
        val entries=mutableListOf<RunEvent>()
        if(s!=null) {
            if(s.events.none { it.kind=="user" }) entries.add(RunEvent("user",s.task,s.started))
            s.events.filter { it.kind!="system" || !it.text.startsWith("Started ·") }.forEach { event ->
                val previous=entries.lastOrNull()
                if(event.kind=="user" || event.kind=="tool" || previous?.text!=event.text) entries.add(event)
            }
            if(s.summary.isNotBlank() && entries.none { it.kind=="result" && it.text==s.summary }) entries.add(RunEvent("result",s.summary,s.started))
        }
        val sameChat=displayedSessionId==s?.id
        val previousCount=if(sameChat) displayedEntries.size else 0
        if(!sameChat || entries!=displayedEntries || forceBottom) {
            val atBottom=messages.height-scroll.height-scroll.scrollY<dp(120)
            val oldScroll=scroll.scrollY
            if(!sameChat) { messages.removeAllViews(); displayedEntries=emptyList(); expandedTools.clear() }
            while(messages.childCount>displayedEntries.size) messages.removeViewAt(messages.childCount-1)
            entries.forEachIndexed { index,event ->
                if(displayedEntries.getOrNull(index)!=event) {
                    val view=eventView(event,index)
                    if(index<messages.childCount) messages.removeViewAt(index)
                    messages.addView(view,index,LinearLayout.LayoutParams(-1,-2))
                    if(sameChat && index>=previousCount && previousCount>0 && !switching && ValueAnimator.areAnimatorsEnabled()) {
                        view.alpha=0f; view.translationY=dp(6).toFloat()
                        view.animate().alpha(1f).translationY(0f).setDuration(180).start()
                    }
                }
            }
            while(messages.childCount>entries.size) messages.removeViewAt(messages.childCount-1)
            if(sameChat && previousCount>0 && !switching && visible && hasWindowFocus() && !drawer.isOpen && entries.drop(previousCount).any { it.kind=="question" || it.kind=="result" }) {
                root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
            displayedEntries=entries.toList(); displayedSessionId=s?.id
            scroll.post {
                if(forceBottom || !sameChat) scroll.fullScroll(View.FOCUS_DOWN)
                else if(atBottom) scroll.smoothScrollTo(0,messages.height)
                else scroll.scrollTo(0,oldScroll)
            }
        }
        empty.visibility=if(s==null) View.VISIBLE else View.GONE
        while(messages.childCount>displayedEntries.size) messages.removeViewAt(messages.childCount-1)
        if(s!=null && !active) messages.fill(label("Usage & details",12f,Palette.muted).apply { setPadding(0,dp(12),0,dp(12)); setOnClickListener { haptic(); showSession(s) } })
        status.text=when { awaitingStart -> "Starting…"; active && s?.status=="PAUSED" -> "Paused · Tap to resume"; waiting -> "Your reply is needed"; active -> "Working on your phone…"; AgentService.current!=null -> "Another chat is running"; else -> "" }
        status.visibility=if(status.text.isEmpty()) View.GONE else View.VISIBLE
        composer.isEnabled=!awaitingStart && (!active || waiting)
        composer.hint=if(waiting) "Reply" else "Message"
        send.show(if(active && !waiting) "stop" else "send",if(active && !waiting) "Stop agent" else "Send message")
        send.isEnabled=!awaitingStart && !Attachments.busy(draftKey)
        attach.isEnabled=composer.isEnabled && !Attachments.busy(draftKey)
        attach.alpha=if(attach.isEnabled) 1f else .35f
        renderAttachments()
    }
    private fun eventView(event: RunEvent, index: Int): View {
        val wrapper=column()
        if(event.kind in setOf("tool","error","system")) {
            val card=column().apply { background=background(Palette.surface,dp(14).toFloat()); setPadding(dp(14),dp(11),dp(14),dp(11)) }
            val state=if(event.state=="running" && selected?.status !in setOf("RUNNING","PAUSED","WAITING")) "unknown" else event.state
            val mark=when { event.kind=="error" || state=="error" -> "!"; state=="running" -> "◌"; state=="done" -> "✓"; state=="unknown" -> "·"; else -> "↳" }
            val suffix=when(state) { "running" -> " · Running"; "error" -> " · Failed"; "unknown" -> " · Check outcome"; else -> "" }
            card.fill(label("$mark  ${event.text}$suffix",13f,if(event.kind=="error" || state=="error") Palette.error else Palette.muted))
            if(event.details.isNotBlank()) {
                val details=label(event.details,12f,Palette.muted).apply {
                    setTextIsSelectable(true); setPadding(0,dp(10),0,0)
                    visibility=if(index in expandedTools) View.VISIBLE else View.GONE
                }
                card.fill(details)
                card.contentDescription="${event.text}$suffix. Tap for details"
                card.isFocusable=true
                card.setOnClickListener {
                    card.haptic()
                    if(!expandedTools.add(index)) expandedTools.remove(index)
                    details.visibility=if(index in expandedTools) View.VISIBLE else View.GONE
                }
            }
            wrapper.fill(card); wrapper.gap(12)
        } else {
            val user=event.kind=="user"
            val container=row().apply { gravity=if(user) Gravity.END else Gravity.START }
            val message=label(event.text,16f).apply {
                setTextIsSelectable(true); setLineSpacing(dp(4).toFloat(),1f)
                if(user) { background=background(Palette.surface,dp(22).toFloat()); setPadding(dp(16),dp(12),dp(16),dp(12)); maxWidth=(resources.displayMetrics.widthPixels*.82f).toInt() }
            }
            val bubble=column().apply { gravity=if(user) Gravity.END else Gravity.START }
            event.attachments.forEach { a ->
                bubble.addView(attachmentView(a),LinearLayout.LayoutParams(dp(250),-2).apply { bottomMargin=dp(6) })
            }
            if(event.text.isNotBlank()) bubble.addView(message,LinearLayout.LayoutParams(if(user) -2 else -1,-2))
            container.addView(bubble,LinearLayout.LayoutParams(if(user) -2 else -1,-2))
            wrapper.fill(container); wrapper.gap(24)
        }
        return wrapper
    }
    private fun populateHistory() {
        val sessions=historySessions.toMutableList()
        AppState.session?.let { active -> sessions.removeAll { it.id==active.id }; sessions.add(0,active) }
        val rows=sessions.map { "${it.id}:${it.task}:${it.started}" } + "selected:${selected?.id}"
        if(rows==historyRows) return
        historyRows=rows
        history.removeAllViews()
        val top=row()
        top.addView(label("Chats",22f,Palette.ink,true),LinearLayout.LayoutParams(0,-2,1f))
        top.addView(ChatIcon(this,"new","Start a new chat") { newChat() },LinearLayout.LayoutParams(dp(48),dp(48)))
        history.fill(top); history.gap(20)
        val list=column()
        var previousDate=""
        sessions.forEach { s ->
            val date=SimpleDateFormat("MMM d",Locale.getDefault()).format(Date(s.started))
            if(date!=previousDate) { list.gap(16); list.fill(label(date,12f,Palette.muted).apply { setPadding(dp(12),0,0,dp(8)) }); previousDate=date }
            list.fill(label(s.task.replace('\n',' '),15f).apply {
                maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END; minHeight=dp(52); gravity=Gravity.CENTER_VERTICAL
                setPadding(dp(12),dp(12),dp(12),dp(12))
                if(s.id==selected?.id) background=background(Palette.surface,dp(12).toFloat())
                isClickable=true; isFocusable=true
                setOnClickListener { haptic(); switchConversation(s) }
            })
        }
        if(sessions.isEmpty()) list.fill(label("No chats yet",14f,Palette.muted).apply { setPadding(dp(12),dp(16),0,0) })
        history.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; addView(list) },LinearLayout.LayoutParams(-1,0,1f))
        val settings=row().apply { setPadding(0,dp(8),0,0); isClickable=true; isFocusable=true; contentDescription="Settings"; setOnClickListener { haptic(); showSettings() } }
        settings.addView(ChatIcon(this,"settings","Open settings") { showSettings() },LinearLayout.LayoutParams(dp(48),dp(48)))
        settings.addView(label("Settings",16f,Palette.ink,true)); history.fill(settings)
    }
    private fun chooseAttachments() {
        if(Attachments.busy(draftKey)) return
        AlertDialog.Builder(this).setTitle("Attach")
            .setItems(arrayOf("Photos", "Files")) { _, which ->
                pickerKey=draftKey
                val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(if(which==0) "image/*" else "*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { startActivityForResult(intent,70) } catch(_: Exception) { toast("No file picker is available.") }
            }.show()
    }
    @Deprecated("Platform document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=70 || resultCode!=RESULT_OK || data==null) return
        val uris=data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        Attachments.importUris(pickerKey ?: draftKey,uris.distinct())
        pickerKey=null
    }
    private fun attachmentView(a: Attachment): View {
        val card=column().apply {
            background=background(Palette.surface,dp(16).toFloat(),Palette.border)
            setPadding(dp(10),dp(10),dp(10),dp(10))
        }
        if(a.thumbnail.isNotBlank()) {
            val bytes=android.util.Base64.decode(a.thumbnail,android.util.Base64.NO_WRAP)
            val picture=ImageView(this).apply {
                setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size))
                scaleType=ImageView.ScaleType.FIT_CENTER
                contentDescription="Image: ${a.name}"
                background=background(Palette.surface,dp(10).toFloat()); clipToOutline=true
            }
            card.addView(picture,LinearLayout.LayoutParams(-1,dp(120))); card.gap(8)
        }
        card.fill(label(a.name,13f,Palette.ink,true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
        val kind=when { a.mime.startsWith("image/") -> "Image"; a.mime=="application/pdf" -> "PDF"; a.name.endsWith(".docx",true) -> "Word · text"; else -> "Text" }
        card.fill(label("$kind · ${android.text.format.Formatter.formatShortFileSize(this,a.size.toLong())}",11f,Palette.muted))
        return card
    }
    private fun renderAttachments() {
        val key=draftKey
        val ids=Attachments.ids(key)
        val busy=Attachments.busy(key)
        Attachments.takeError(key)?.let { toast(it) }
        val rows="$key:$ids:$busy"
        if(rows==attachmentRows) return
        attachmentRows=rows; attachmentTray.removeAllViews()
        attachmentTray.visibility=if(ids.isEmpty() && !busy) View.GONE else View.VISIBLE
        if(ids.isNotEmpty()) {
            val cards=row().apply { gravity=Gravity.TOP }
            Attachments.draft(key).forEach { a ->
                val frame=FrameLayout(this)
                frame.addView(attachmentView(a),FrameLayout.LayoutParams(-1,-2))
                frame.addView(ChatIcon(this,"close","Remove ${a.name}") { Attachments.remove(key,a.id) }.apply {
                    background=background(Palette.bg,dp(20).toFloat())
                },FrameLayout.LayoutParams(dp(36),dp(36),Gravity.TOP or Gravity.END))
                cards.addView(frame,LinearLayout.LayoutParams(dp(180),-2).apply { rightMargin=dp(8) })
            }
            attachmentTray.fill(object : HorizontalScrollView(this) {
                override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                    if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
                    return super.dispatchTouchEvent(event)
                }
            }.apply { isHorizontalScrollBarEnabled=false; addView(cards) })
        }
        if(busy) attachmentTray.fill(label("Preparing attachments…",12f,Palette.muted))
        attachmentTray.gap(8)
    }
    private fun sendMessage() {
        if(Attachments.busy(draftKey)) return
        val attachmentIds=Attachments.ids(draftKey)
        val active=AgentService.current
        if(active!=null) {
            if(AppState.session?.id!=selected?.id) { toast("Stop the running chat before sending another message."); return }
            if(selected?.status!="WAITING") { active.stopRun(); return }
            val reply=composer.text.toString().trim(); if(reply.isBlank() && attachmentIds.isEmpty()) return
            if(!active.reply(reply,attachmentIds)) return
            Attachments.forgetDraft(draftKey); renderAttachments(); composer.setText(""); draft=""; hideKeyboard(); return
        }
        val task=composer.text.toString().trim(); if(task.isBlank() && attachmentIds.isEmpty()) return
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
        Attachments.forgetDraft(draftKey)
        hideKeyboard(); draft=""; composer.setText(""); awaitingStart=true; updateChat()
        startForegroundService(Intent(this,AgentService::class.java).setAction(AgentService.START).putExtra("task",task.take(12000)).putExtra("session_id",selected?.id).putStringArrayListExtra("attachments",ArrayList(attachmentIds)))
    }
    private fun showSettings() {
        if(page=="Chat") { draft=composer.text.toString(); hideKeyboard() }
        transitionVersion++; switching=false; chatBody.animate().cancel()
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
            .setMessage("During tasks you start, Android Use reads the current screen and can tap, type, scroll, and open apps. Screen text, requested screenshots, and chat attachments are sent directly to the model provider you choose.\n\nYour API key and task history are encrypted on this device. There is no Android Use server. Stop at any time using the floating control or notification.\n\nOn some sideloaded installs, first open App info → ⋮ → Allow restricted settings, then enable Android Use in Accessibility.")
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
        screenshots.setOnCheckedChangeListener { button, _ -> button.haptic() }
        content.fill(screenshots); content.gap(16)
        content.fill(label("Maximum steps per message", 13f, Palette.muted)); content.gap(6)
        val steps = field("24", c.maxSteps.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }; content.fill(steps); content.gap(14)
        content.fill(label("Input-token budget per message", 13f, Palette.muted)); content.gap(6)
        val tokens = field(ProviderConfig.DEFAULT_INPUT_TOKENS.toString(), c.maxInputTokens.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }; content.fill(tokens); content.gap(16)
        fun save(): ProviderConfig {
            check(AgentService.current == null) { "Stop the active task before changing its connection." }
            val chosen = if(provider.selectedItemPosition == 1) "anthropic" else "openai"
            val existingKey = if(chosen == c.provider && endpoint.text.toString().trim().trimEnd('/') == c.endpoint.trimEnd('/')) c.apiKey else ""
            val next = ProviderConfig(chosen, endpoint.text.toString().trim(), model.text.toString().trim(), key.text.toString().trim().ifBlank { existingKey }, steps.text.toString().toIntOrNull() ?: 24, tokens.text.toString().toIntOrNull() ?: ProviderConfig.DEFAULT_INPUT_TOKENS, screenshots.isChecked)
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
                    try { Stores.clearHistory(); selected = null; historyGeneration++; historySessions=emptyList(); chatDrafts.clear(); chatScrolls.clear(); toast("Chats deleted") } catch (e: Exception) { toast(e.message.orEmpty()) }
                }.show()
        }); content.gap(20)
        content.fill(label("Android Use ${BuildConfig.VERSION_NAME}", 12f, Palette.muted))
    }
    private fun showSession(s: Session) {
        val report = buildString {
            append("${s.task}\n\n${s.status} · ${s.model}\n${s.step} steps\nInput: ${s.input} · Cache reads: ${s.cached} · Cache writes: ${s.cacheWrite} · Output: ${s.output}\n\n")
            s.events.forEach { append("${it.kind.uppercase()} ${it.state}\n${it.text}\n"); if(it.details.isNotBlank()) append("${it.details}\n"); append("\n") }
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
