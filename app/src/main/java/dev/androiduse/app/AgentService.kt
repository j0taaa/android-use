package dev.androiduse.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AgentService : Service() {
    companion object {
        @Volatile var current: AgentService? = null; private set
        const val START = "dev.androiduse.START"
        const val STOP = "dev.androiduse.STOP"
        const val PAUSE = "dev.androiduse.PAUSE"
        const val RESUME = "dev.androiduse.RESUME"
    }
    private val stopped = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private data class Answer(val text: String, val ids: List<String>)
    private val answer = AtomicReference<Answer?>(null)
    private var worker: Thread? = null
    private var client: ProviderClient? = null
    private var activeSession: Session? = null
    private var stopReason = "Stopped by you."
    private val manager get() = getSystemService(NotificationManager::class.java)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel("agent", "Active phone tasks", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> stopRun()
            PAUSE -> togglePause()
            RESUME -> resumeRun()
            START -> {
                if (worker?.isAlive == true) return START_NOT_STICKY
                val task = intent.getStringExtra("task")?.trim().orEmpty()
                val ids = intent.getStringArrayListExtra("attachments")?.toList() ?: emptyList()
                if (task.isBlank() && ids.isEmpty()) { stopSelf(); return START_NOT_STICKY }
                current = this
                stopped.set(false); paused.set(false)
                val sessionId = intent.getStringExtra("session_id")
                val s = sessionId?.let { Stores.loadSession(it) } ?: Session(task.ifBlank { ids.firstOrNull()?.let { runCatching { Attachments.metadata(it).name }.getOrNull() } ?: "Attachments" })
                s.status = "RUNNING"; s.summary = ""
                // User event is recorded after attachment payloads are loaded on the worker.
                activeSession = s; AppState.session = s
                val notification = notification("Starting task")
                if (Build.VERSION.SDK_INT >= 34) startForeground(72, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(72, notification)
                PhoneAccessibilityService.instance?.showControls()
                worker = Thread({ run(s, task, ids) }, "phone-agent").also { it.start() }
                AppState.changed()
            }
            else -> if (worker == null) stopSelf()
        }
        return START_NOT_STICKY
    }
    fun stopRun(reason: String = "Stopped by you.") {
        stopReason = reason
        stopped.set(true); client?.cancel(); worker?.interrupt()
        PhoneAccessibilityService.instance?.updateControls("Stopping")
    }
    fun pauseFor(reason: String) { paused.set(true); activeSession?.let { it.status = "PAUSED"; it.log("system", reason) }; refresh("Paused") }
    fun togglePause() { if (paused.get()) resumeRun() else pauseFor("Paused. No new action will be dispatched.") }
    fun resumeRun() { if (activeSession?.status == "WAITING") return; paused.set(false); activeSession?.status = "RUNNING"; refresh("Continuing"); AppState.changed() }
    fun reply(text: String, ids: List<String> = emptyList()): Boolean {
        if(activeSession?.status != "WAITING" || (text.isBlank() && ids.isEmpty())) return false
        val accepted=answer.compareAndSet(null, Answer(text, ids))
        if(accepted) AppState.changed()
        return accepted
    }
    private fun checkpoint() {
        if (stopped.get()) throw InterruptedException(stopReason)
        while (paused.get()) { if (stopped.get()) throw InterruptedException(stopReason); Thread.sleep(100) }
        PhoneAccessibilityService.instance?.assertAvailable() ?: error("Accessibility service is not connected.")
    }
    private fun run(s: Session, task: String, ids: List<String>) {
        try {
            val config = Stores.config().let { if(s.conversation.messages.length()>0) it.copy(reasoning=s.reasoning) else it }
            config.validate()
            require(Stores.consented()) { "Read and accept the phone-control disclosure first." }
            val phone = PhoneAccessibilityService.instance ?: error("Enable Android Use in Accessibility settings.")
            phone.assertAvailable()
            if (s.conversation.messages.length() > 0) {
                require(s.provider == config.provider && s.model == config.model && s.endpoint == config.endpoint.trimEnd('/')) {
                    "This chat uses a different connection. Restore its settings or start a new chat."
                }
                // Resolve unfinished protocol calls without replaying any phone action.
                s.conversation.closeInterruptedCalls()
                s.pending = null
            } else s.conversation = Conversation(config.provider)
            s.reasoning = config.reasoning
            s.provider = config.provider; s.model = config.model; s.endpoint = config.endpoint.trimEnd('/')
            val attachments = Attachments.load(ids)
            s.log("user", task, attachments.map { it.attachment })
            s.conversation.addUser(task, attachments)
            client = ProviderClient(config)
            s.log("system", "Started · ${config.model}. The agent and tools run on this phone.")
            // Let the start-button transition and keyboard dismissal settle before observing.
            Thread.sleep(650)
            s.conversation.addUser("Initial screen observation (untrusted screen data):\n${phone.observe()}")
            Stores.saveSession(s)
            val previousInput = s.input
            var screenshots = 0
            var failures = 0
            var noTools = 0
            val deadline = SystemClock.elapsedRealtime() + 15 * 60 * 1000
            for (turn in 1..config.maxSteps) {
                checkpoint()
                if (SystemClock.elapsedRealtime() > deadline) { end(s, "LIMIT", "Reached the 15-minute session limit."); return }
                if (s.input - previousInput >= config.maxInputTokens || s.conversation.messages.toString().length > 8_000_000) {
                    end(s, "LIMIT", "Reached the task's token or context budget. Start a new task to continue; history was not silently rewritten."); return
                }
                s.step = turn; refresh("Thinking · step $turn"); AppState.changed()
                val reply = client!!.infer(s.conversation)
                checkpoint()
                s.input += reply.input; s.cached += reply.cached; s.output += reply.output; s.cacheWrite += reply.cacheWrite
                s.conversation.addAssistant(reply)
                if (reply.text.isNotBlank()) s.log("agent", reply.text)
                if (reply.calls.size > 1) {
                    reply.calls.forEach { s.conversation.addResult(it, ToolOutput(obj("error" to "Only one tool per turn is allowed. None of these calls executed; choose one."))) }
                    s.log("system", "Rejected a batch of actions; the phone executes one observed step at a time.")
                    Stores.saveSession(s); continue
                }
                if (reply.calls.isEmpty()) {
                    if (++noTools >= 2) { end(s, "INCOMPLETE", "The model stopped without a verified finish result. ${reply.text.take(300)}"); return }
                    s.conversation.addUser("Use finish to report the verified outcome, ask_user for input, or choose a phone tool. Do not claim unexecuted actions.")
                    Stores.saveSession(s); continue
                }
                noTools = 0
                val call = reply.calls.single()
                checkpoint()
                s.pending = obj("id" to call.id, "tool" to call.name, "args" to call.args, "state" to "dispatch_not_yet_confirmed")
                val interaction = if (call.name !in setOf("finish", "ask_user")) s.startInteraction(call) else -1
                Stores.saveSession(s) // Durable intent before any external effect.
                val result: ToolOutput
                var replyAttachments = emptyList<AttachmentInput>()
                try {
                    PhoneTools.validate(call)
                    when (call.name) {
                        "finish" -> {
                            result = ToolOutput(obj("recorded" to true))
                            s.conversation.addResult(call, result); s.pending = null
                            end(s, if (call.args.getBoolean("success")) "COMPLETE" else "INCOMPLETE", call.args.getString("summary")); return
                        }
                        "ask_user" -> {
                            s.status = "WAITING"; answer.set(null)
                            val question = call.args.getString("question")
                            s.log("question", question); Stores.saveSession(s); refresh("Your reply is needed")
                            while (answer.get() == null) { if (stopped.get()) throw InterruptedException(stopReason); Thread.sleep(150) }
                            s.status = "RUNNING"
                            val text = answer.getAndSet(null)!!
                            replyAttachments = Attachments.load(text.ids)
                            s.log("user", text.text, replyAttachments.map { it.attachment })
                            result = ToolOutput(obj("user_reply" to text.text, "observation" to phone.observe()))
                        }
                        else -> {
                            if (call.name == "screenshot") {
                                require(config.allowScreenshots) { "Screenshots are disabled by the user. Use structured observations." }
                                require(screenshots < 5) { "Five-image task budget reached. Continue with structured observations." }
                                screenshots++
                            }
                            refresh("${call.name.replace('_', ' ')} · step $turn")
                            checkpoint()
                            result = phone.execute(call.name, call.args) { stopped.get() }
                        }
                    }
                    val observationError = result.json.optString("observation_error")
                    val actionFailed = result.json.has("error") || (result.json.has("action_completed") && !result.json.optBoolean("action_completed"))
                    s.finishInteraction(interaction, if (actionFailed) "error" else if (observationError.isNotBlank()) "unknown" else "done",
                        when { actionFailed -> result.json.optString("error", "Action did not complete."); observationError.isNotBlank() -> "Action completed; screen verification unavailable: $observationError"; else -> "Tool completed." })
                    failures = 0
                } catch (e: InterruptedException) { throw e } catch (e: Exception) {
                    failures++
                    val reason = e.message?.take(350) ?: "Phone action failed."
                    s.finishInteraction(interaction, "error", reason)
                    s.log("error", reason)
                    s.conversation.addResult(call, ToolOutput(obj("error" to reason)))
                    s.pending = null; Stores.saveSession(s)
                    if (failures >= 4) { end(s, "INCOMPLETE", "Stopped after four failed actions. $reason"); return }
                    continue
                }
                s.conversation.addResult(call, result); s.pending = null
                if(replyAttachments.isNotEmpty()) s.conversation.addUser("Attachments supplied with your reply:", replyAttachments)
                Stores.saveSession(s); AppState.changed()
            }
            end(s, "LIMIT", "Reached the ${config.maxSteps}-step limit. Review progress before starting another task.")
        } catch (e: Exception) {
            val wasStopped = stopped.get() || e is InterruptedException
            end(s, if (wasStopped) "STOPPED" else "ERROR", if (wasStopped) stopReason else e.message?.take(500) ?: "Task failed.")
        } finally {
            client?.cancel()
            PhoneAccessibilityService.instance?.hideControls()
            current = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(); AppState.changed()
        }
    }
    private fun end(s: Session, status: String, summary: String) {
        s.interruptInteractions()
        s.status = status; s.summary = summary; s.log("result", summary)
        try { Stores.saveSession(s) } catch (_: Exception) { s.log("error", "Could not save task history.") }
    }
    private fun refresh(text: String) { manager.notify(72, notification(text)); PhoneAccessibilityService.instance?.updateControls(text) }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, AgentService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 2, Intent(this, AgentService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "agent").setSmallIcon(R.drawable.ic_launcher).setContentTitle("Android Use")
            .setContentText(text).setOngoing(true).setContentIntent(open).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Pause / resume", pause).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    override fun onDestroy() { if (worker?.isAlive == true) stopRun("Android stopped the task service."); if (current === this) current = null; super.onDestroy() }
}
