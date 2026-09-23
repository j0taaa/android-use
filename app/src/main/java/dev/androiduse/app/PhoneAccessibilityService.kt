package dev.androiduse.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PhoneAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: PhoneAccessibilityService? = null; private set
        fun isEnabled(context: android.content.Context): Boolean = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.let { service -> service.packageName==context.packageName && service.name==PhoneAccessibilityService::class.java.name } }
    }
    @Volatile private var disabled = false
    private val main = Handler(Looper.getMainLooper())
    private val sequence = AtomicInteger()
    @Volatile private var lastChange = 0L
    private var snapshot: Snapshot? = null
    private var overlay: ControlOverlay? = null
    data class NodeRecord(val path: List<Int>, val signature: String)
    data class Capture(val json: JSONObject, val nodes: Map<String, NodeRecord>, val hash: String, val window: Int, val pkg: String)
    data class Snapshot(val id: String, val capture: Capture, val time: Long)

    override fun onServiceConnected() { disabled = false; instance = this; AppState.changed() }
    override fun onInterrupt() { AgentService.current?.pauseFor("Accessibility was interrupted.") }
    override fun onDestroy() {
        val wasDisabled=disabled; disabled=true; hideControls()
        if(instance===this) instance=null
        if(!wasDisabled) AgentService.current?.stopRun("Accessibility disconnected.")
        AppState.changed(); super.onDestroy()
    }
    fun turnOff(cancelAgent: Boolean = true) = onMain {
        disabled=true
        if(cancelAgent) AgentService.current?.stopRun("Phone control turned off.")
        snapshot=null; overlay?.close(); overlay=null
        disableSelf() // Revoke the enabled service in Android; a paused task keeps this permission.
        if(instance===this) instance=null
        AppState.changed()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { if (event != null) lastChange = SystemClock.elapsedRealtime() }
    fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        main.post(task)
        return try { task.get(8, TimeUnit.SECONDS) } catch (e: Exception) { task.cancel(false); throw IllegalStateException(e.cause?.message ?: "Phone control timed out") }
    }
    fun assertAvailable() {
        require(!disabled && instance === this) { "Enable Android Use in Accessibility settings." }
        require(!getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock the phone before continuing." }
        require(getSystemService(PowerManager::class.java).isInteractive) { "Turn on the phone screen before continuing." }
    }
    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }
    private fun signature(node: AccessibilityNodeInfo): String = listOf(node.className, if (node.isPassword) "[password]" else node.text, node.contentDescription, bounds(node), node.isEnabled, node.isChecked, node.isEditable).joinToString("|")
    private fun capture(): Capture {
        assertAvailable()
        val root = rootInActiveWindow ?: throw IllegalStateException("No readable window. Open an app and try again.")
        val wm = getSystemService(WindowManager::class.java)
        val size = wm.maximumWindowMetrics.bounds
        val nodes = JSONArray()
        val records = linkedMapOf<String, NodeRecord>()
        var visited = 0
        var truncated = false
        fun visit(node: AccessibilityNodeInfo, path: List<Int>, parent: String?, depth: Int) {
            if (++visited > 700 || nodes.length() >= 160 || depth > 30) { truncated = true; return }
            val rect = bounds(node)
            var nextParent = parent
            if (node.isVisibleToUser && !rect.isEmpty) {
                val label = if (node.isPassword) "[password hidden]" else node.text?.toString()?.take(300).orEmpty()
                val desc = if (node.isPassword) "" else node.contentDescription?.toString()?.take(300).orEmpty()
                if (label.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable || node.isCheckable) {
                    val id = "n${nodes.length() + 1}"
                    records[id] = NodeRecord(path, signature(node))
                    val actions = JSONArray().also { a -> node.actionList.forEach { a.put(it.id) } }
                    nodes.put(obj("id" to id, "parent" to parent, "class" to node.className?.toString()?.substringAfterLast('.'), "text" to label,
                        "description" to desc, "bounds" to arr(rect.left, rect.top, rect.right, rect.bottom), "clickable" to node.isClickable,
                        "editable" to node.isEditable, "scrollable" to node.isScrollable, "enabled" to node.isEnabled,
                        "checked" to node.isChecked, "focused" to node.isFocused, "password" to node.isPassword, "actions" to actions))
                    nextParent = id
                }
            }
            for (i in 0 until node.childCount) {
                if (nodes.length() >= 160 || visited > 700) { truncated = true; break }
                node.getChild(i)?.let { child -> try { visit(child, path + i, nextParent, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() } }
            }
        }
        try {
            if (!(root.packageName?.toString() == packageName && MainActivity.visible)) visit(root, emptyList(), null, 0)
            val data = obj("package" to root.packageName?.toString(), "window_id" to root.windowId,
                "width" to size.width(), "height" to size.height(), "nodes" to nodes, "truncated" to truncated,
                "agent_controls_hidden" to (root.packageName?.toString() == packageName && MainActivity.visible))
            val hash = MessageDigest.getInstance("SHA-256").digest(data.toString().toByteArray()).joinToString("") { "%02x".format(it) }
            return Capture(data, records, hash, root.windowId, root.packageName.toString())
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }
    fun observe(): JSONObject {
        // App launches can temporarily have no active accessibility root. Retry the
        // read only, never the action that caused the transition, and never sleep on UI.
        for (attempt in 0..8) {
            try {
                return onMain {
                    val capture = capture()
                    val snap = Snapshot("s${sequence.incrementAndGet()}", capture, SystemClock.elapsedRealtime())
                    snapshot = snap
                    JSONObject(capture.json.toString()).put("snapshot_id", snap.id)
                }
            } catch (e: IllegalStateException) {
                if (attempt == 8 || Looper.myLooper() == Looper.getMainLooper() ||
                    e.message?.startsWith("No readable window") != true) throw e
                Thread.sleep(200)
            }
        }
        error("No readable window.")
    }
    private fun validateSnapshot(args: JSONObject): Snapshot {
        val snap = snapshot ?: error("Observe the screen first.")
        require(args.getString("snapshot_id") == snap.id) { "STALE_SNAPSHOT: observe again before acting." }
        require(SystemClock.elapsedRealtime() - snap.time < 180000) { "STALE_SNAPSHOT: observation expired." }
        val fresh = capture()
        require(fresh.hash == snap.capture.hash) { "SCREEN_CHANGED: observe again before acting." }
        return snap
    }
    private fun <T> withNode(args: JSONObject, cancelled: () -> Boolean, action: (AccessibilityNodeInfo) -> T): T = onMain {
        if (cancelled()) throw InterruptedException("Stopped")
        val snap = validateSnapshot(args)
        require(!(snap.capture.pkg == packageName && MainActivity.visible)) { "The agent cannot modify its own controls or credentials." }
        val record = snap.capture.nodes[args.getString("node_id")] ?: error("Unknown node in this snapshot.")
        var node = rootInActiveWindow ?: error("No active window.")
        try {
            for (index in record.path) {
                val child = node.getChild(index) ?: error("STALE_NODE: observe again.")
                @Suppress("DEPRECATION") node.recycle()
                node = child
            }
            require(signature(node) == record.signature && node.isVisibleToUser && node.isEnabled) { "STALE_NODE: target changed or is disabled." }
            require(!node.isPassword) { "Password input requires manual entry." }
            action(node)
        } finally { @Suppress("DEPRECATION") node.recycle() }
    }
    private fun point(x: Float, y: Float) {
        val r = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        require(x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < r.width() && y < r.height()) { "Coordinates are outside the screen." }
    }
    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long, cancelled: () -> Boolean): Boolean {
        point(x1, y1); point(x2, y2)
        val latch = CountDownLatch(1)
        var completed = false
        onMain { overlay?.setHidden(true) }
        try {
            Thread.sleep(60) // Allow the overlay surface to leave the gesture's hit-test region.
            val accepted = onMain {
                assertAvailable()
                if (cancelled()) throw InterruptedException("Stopped")
                require(!MainActivity.visible) { "The agent cannot gesture over its own controls." }
                val path = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
                val desc = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
                dispatchGesture(desc, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { completed = true; latch.countDown() }
                    override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
                }, main)
            }
            return accepted && latch.await(4, TimeUnit.SECONDS) && completed
        } finally { onMain { overlay?.setHidden(false) } }
    }

    fun settle(cancelled: () -> Boolean = { false }) {
        val start = SystemClock.elapsedRealtime()
        do {
            if (cancelled()) throw InterruptedException("Stopped")
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() - start < 450 || (SystemClock.elapsedRealtime() - lastChange < 250 && SystemClock.elapsedRealtime() - start < 1800))
    }
    fun execute(name: String, args: JSONObject, cancelled: () -> Boolean): ToolOutput {
        assertAvailable()
        if (cancelled()) throw InterruptedException("Stopped")
        when (name) {
            "observe" -> return ToolOutput(observe())
            "screenshot" -> return screenshot()
            "list_apps" -> {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps = packageManager.queryIntentActivities(intent, 0).distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString() }
                return ToolOutput(obj("apps" to JSONArray().also { a -> apps.forEach { a.put(obj("label" to it.loadLabel(packageManager).toString(), "package" to it.activityInfo.packageName)) }; a.put(obj("label" to "Android Use practice", "package" to "android-use:practice")) }))
            }
            "wait" -> { Thread.sleep(args.getLong("milliseconds").coerceIn(100, 3000)); return ToolOutput(observe()) }
        }
        var ok = false
        when (name) {
            "tap", "long_press" -> {
                if (args.has("node_id")) {
                    ok = withNode(args, cancelled) { it.performAction(if (name == "tap") AccessibilityNodeInfo.ACTION_CLICK else AccessibilityNodeInfo.ACTION_LONG_CLICK) }
                } else {
                    onMain { validateSnapshot(args) }
                    val x = args.getDouble("x").toFloat(); val y = args.getDouble("y").toFloat()
                    ok = gesture(x, y, x, y, if (name == "tap") 80 else 700, cancelled)
                }
            }
            "set_text" -> ok = withNode(args, cancelled) {
                require(it.isEditable) { "Target is not editable." }
                val text = args.getString("text"); require(text.length <= 12000) { "Text exceeds 12000 characters." }
                it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
            }
            "ime_action" -> ok = withNode(args, cancelled) { it.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) }
            "scroll" -> ok = withNode(args, cancelled) {
                require(it.isScrollable) { "Target is not scrollable." }
                val action = when (args.getString("direction")) {
                    "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                    "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                    "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                    else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                }
                if (it.actionList.any { a -> a.id == action }) it.performAction(action)
                else it.performAction(if (args.getString("direction") in listOf("up", "left")) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            "swipe" -> {
                onMain { validateSnapshot(args) }
                ok = gesture(args.getDouble("x1").toFloat(), args.getDouble("y1").toFloat(), args.getDouble("x2").toFloat(), args.getDouble("y2").toFloat(), args.optLong("duration_ms", 400).coerceIn(100, 1500), cancelled)
            }
            "navigate" -> ok = onMain {
                assertAvailable()
                if (cancelled()) throw InterruptedException("Stopped")
                performGlobalAction(when (args.getString("action")) { "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS; "notifications" -> GLOBAL_ACTION_NOTIFICATIONS; else -> error("Unsupported navigation") })
            }
            "open_app" -> {
                val pkg = args.getString("package_name")
                val intent = if (pkg == "android-use:practice") Intent(this, PracticeActivity::class.java) else packageManager.getLaunchIntentForPackage(pkg) ?: error("App is not launchable or not installed.")
                onMain { assertAvailable(); if (cancelled()) throw InterruptedException("Stopped"); startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                ok = true
            }
            else -> error("Unknown phone tool: $name")
        }
        onMain { snapshot = null }
        settle(cancelled)
        val result = obj("action_completed" to ok, "note" to if (ok) "Check the observation to verify the intended result." else "Action was unsupported or rejected; inspect the screen before retrying.")
        try { result.put("observation", observe()) }
        catch (e: InterruptedException) { throw e }
        catch (e: Exception) { result.put("observation_error", "${e.message}. The action was already dispatched. Observe again; do not repeat it without checking the result.") }
        return ToolOutput(result)
    }
    fun screenshot(): ToolOutput {
        require(!MainActivity.visible) { "Agent controls and credentials are excluded from screenshots. Open the target app first." }
        val observation = observe()
        val before = onMain { snapshot!!.capture.hash }
        onMain { overlay?.setHidden(true) }
        try {
            Thread.sleep(140)
            val latch = CountDownLatch(1)
            var bitmap: Bitmap? = null
            var failure = "Screenshot timed out."
            onMain {
                assertAvailable()
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            if(disabled) { failure="Phone control is off."; return }
                            val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            bitmap = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                        } finally { result.hardwareBuffer.close(); latch.countDown() }
                    }
                    override fun onFailure(errorCode: Int) { failure = "Screenshot unavailable (Android code $errorCode); the screen may be protected or capture was too frequent."; latch.countDown() }
                })
            }
            require(latch.await(5, TimeUnit.SECONDS)) { "Screenshot timed out." }
            val source = bitmap ?: error(failure)
            try {
                require(onMain { capture().hash } == before) { "Screen changed during capture. Observe and capture again." }
                val scale = minOf(1.0, 1280.0 / maxOf(source.width, source.height))
                val scaled = Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
                val bytes = ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 65, out); out.toByteArray() }
                val meta = obj("observation" to observation, "image_width" to scaled.width, "image_height" to scaled.height,
                    "screen_width" to source.width, "screen_height" to source.height, "screen_pixels_per_image_pixel" to 1.0 / scale,
                    "coordinate_rule" to "Multiply image coordinates by screen_pixels_per_image_pixel for tap/swipe.")
                if (scaled !== source) scaled.recycle()
                return ToolOutput(meta, Base64.encodeToString(bytes, Base64.NO_WRAP))
            } finally { source.recycle() }
        } finally { onMain { overlay?.setHidden(false) } }
    }
    fun showControls() { main.post { if(disabled || instance!==this) return@post; if (overlay == null) overlay = ControlOverlay(this); overlay?.show() } }
    fun updateControls(label: String) { main.post { overlay?.update(label) } }
    fun hideControls() { main.post { overlay?.close(); overlay = null } }
}
