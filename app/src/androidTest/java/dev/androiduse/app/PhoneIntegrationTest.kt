package dev.androiduse.app

import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PhoneIntegrationTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val phone get() = PhoneAccessibilityService.instance!!
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun waitUntil(timeout: Long = 15000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while(System.currentTimeMillis() < until) { if(condition()) return; Thread.sleep(150) }
        assertTrue("Condition timed out", condition())
    }
    @Before fun setup() {
        shell("settings put secure enabled_accessibility_services ${context.packageName}/dev.androiduse.app.PhoneAccessibilityService")
        shell("settings put secure accessibility_enabled 1")
        shell("settings put system screen_off_timeout 1800000")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        waitUntil { PhoneAccessibilityService.instance != null }
        Stores.consent()
        launchPractice()
    }
    @After fun cleanup() {
        AgentService.current?.stopRun()
        waitUntil { AgentService.current == null }
    }
    private fun launchPractice() {
        inst.runOnMainSync { context.startActivity(Intent(context, PracticeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        waitUntil { try { phone.observe().getJSONArray("nodes").toString().contains("Your practice note") } catch(_: Exception) { false } }
        phone.settle()
    }
    private fun node(screen: JSONObject, text: String) = screen.getJSONArray("nodes").objects().first { it.optString("text") == text || it.optString("description") == text }
    private fun call(name: String, args: JSONObject) = phone.execute(name,args) { false }
    @Test fun actualTextTapScreenshotScrollAndStaleReferences() {
        var screen = phone.observe()
        val edit = screen.getJSONArray("nodes").objects().first { it.optBoolean("editable") }
        val stale = obj("snapshot_id" to screen.getString("snapshot_id"), "node_id" to edit.getString("id"), "text" to "stale")
        screen = call("set_text", obj("snapshot_id" to screen.getString("snapshot_id"), "node_id" to edit.getString("id"), "text" to "Emulator control verified")).json.getJSONObject("observation")
        assertTrue(screen.toString().contains("Emulator control verified"))
        try { call("set_text",stale); fail("Old snapshot accepted") } catch(_: Exception) {}
        val save = node(screen,"Save note")
        screen = call("tap",obj("snapshot_id" to screen.getString("snapshot_id"),"node_id" to save.getString("id"))).json.getJSONObject("observation")
        assertTrue(screen.toString().contains("Saved: Emulator control verified"))
        val shot = phone.screenshot()
        assertTrue(shot.image!!.length > 1000)
        assertTrue(shot.json.getInt("image_width") > 0)
        screen = phone.observe()
        val scroll = screen.getJSONArray("nodes").objects().first { it.optBoolean("scrollable") }
        val next = call("scroll",obj("snapshot_id" to screen.getString("snapshot_id"),"node_id" to scroll.getString("id"),"direction" to "down")).json
        assertTrue(next.getBoolean("action_completed"))
        assertFalse(next.getJSONObject("observation").getJSONArray("nodes").toString().contains("Your practice note"))
    }
    @Test fun nativeGesturesLongPressAndAppNavigation() {
        var screen=phone.observe()
        val target=node(screen,"Long press me")
        screen=call("long_press",obj("snapshot_id" to screen.getString("snapshot_id"),"node_id" to target.getString("id"))).json.getJSONObject("observation")
        assertTrue(screen.toString().contains("Long press worked"))
        screen=call("swipe",obj("snapshot_id" to screen.getString("snapshot_id"),"x1" to 500,"y1" to 1800,"x2" to 500,"y2" to 800)).json.getJSONObject("observation")
        assertFalse(screen.toString().contains("A safe place to try."))
        val apps=call("list_apps",obj()).json.getJSONArray("apps")
        assertTrue(apps.toString().contains("com.android.settings"))
        screen=call("open_app",obj("package_name" to "com.android.settings")).json.getJSONObject("observation")
        assertEquals("com.android.settings",screen.getString("package"))
        call("navigate",obj("action" to "home"))
        call("open_app",obj("package_name" to "android-use:practice"))
        assertEquals(context.packageName,phone.observe().getString("package"))
    }
    private fun response(id: Int, name: String, args: JSONObject) = MockResponse().setHeader("Content-Type","application/json").setBody(obj("choices" to arr(obj("finish_reason" to "tool_calls", "message" to obj("role" to "assistant", "content" to null, "tool_calls" to arr(obj("id" to "call$id", "type" to "function", "function" to obj("name" to name, "arguments" to args.toString())))))), "usage" to obj("prompt_tokens" to 1500,"prompt_tokens_details" to obj("cached_tokens" to if(id > 0) 1024 else 0),"completion_tokens" to 30)).toString())
    private fun lastObservation(body: JSONObject): JSONObject {
        val messages=body.getJSONArray("messages")
        for(i in messages.length()-1 downTo 0) {
            val m=messages.getJSONObject(i)
            if(m.optString("role")=="tool") {
                val result=JSONObject(m.getString("content"))
                if(result.has("observation")) return result.getJSONObject("observation")
                if(result.has("snapshot_id")) return result
            }
        }
        error("No observation")
    }
    @Test fun realForegroundAgentLoopAgainstScriptedHttpProviderPreservesPrefix() {
        val requests=CopyOnWriteArrayList<JSONObject>()
        val index=AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val body=JSONObject(request.body.readUtf8()); requests.add(body)
                    val n=index.getAndIncrement()
                    return try {
                        when(n) {
                            0 -> response(n,"open_app",obj("package_name" to "android-use:practice"))
                            1 -> { val s=lastObservation(body); val e=s.getJSONArray("nodes").objects().first { it.optBoolean("editable") }; response(n,"set_text",obj("snapshot_id" to s.getString("snapshot_id"),"node_id" to e.getString("id"),"text" to "Agent HTTP loop verified")) }
                            2 -> { val s=lastObservation(body); response(n,"tap",obj("snapshot_id" to s.getString("snapshot_id"),"node_id" to node(s,"Save note").getString("id"))) }
                            3 -> response(n,"screenshot",obj())
                            4 -> response(n,"finish",obj("summary" to "Verified saved note on the practice screen.","success" to true))
                            else -> MockResponse().setResponseCode(500)
                        }
                    } catch(e:Exception) { MockResponse().setResponseCode(500).setBody(e.toString()) }
                }
            }
            server.start()
            Stores.saveConfig(ProviderConfig(endpoint=server.url("/v1").toString().trimEnd('/'),apiKey="instrumentation-only",model="scripted-test"))
            inst.runOnMainSync { context.startForegroundService(Intent(context,AgentService::class.java).setAction(AgentService.START).putExtra("task","Save a practice note")) }
            waitUntil(60000) { AppState.session?.status in setOf("COMPLETE","ERROR","INCOMPLETE","LIMIT") }
            val s=AppState.session!!
            assertEquals(s.summary,"COMPLETE",s.status)
            assertTrue(phone.observe().toString().contains("Saved: Agent HTTP loop verified"))
            assertEquals(5,requests.size)
            for(i in 1 until requests.size) {
                val prev=requests[i-1].getJSONArray("messages"); val next=requests[i].getJSONArray("messages")
                for(j in 0 until prev.length()) assertEquals("Prefix changed at $i/$j",prev.get(j).toString(),next.get(j).toString())
                assertEquals(requests[0].getJSONArray("tools").toString(),requests[i].getJSONArray("tools").toString())
            }
            assertEquals(4096,s.cached)
            assertNull(s.pending)
            assertEquals("COMPLETE",Stores.sessions().first().status)
            assertFalse(context.getSharedPreferences("settings",0).getString("credential","")!!.contains("instrumentation-only"))
        }
    }
    @Test fun stopCancelsPendingInferenceWithoutExecutingItsLateAction() {
        MockWebServer().use { server ->
            server.enqueue(response(0,"open_app",obj("package_name" to "com.android.settings")).setBodyDelay(2,TimeUnit.SECONDS))
            Stores.saveConfig(ProviderConfig(endpoint=server.url("/v1").toString().trimEnd('/'),apiKey="test",model="delayed-test"))
            inst.runOnMainSync { context.startForegroundService(Intent(context,AgentService::class.java).setAction(AgentService.START).putExtra("task","Cancellation test")) }
            assertNotNull(server.takeRequest(10,TimeUnit.SECONDS))
            AgentService.current!!.stopRun()
            waitUntil(6000) { AppState.session?.status == "STOPPED" && AgentService.current == null }
            assertEquals(context.packageName,phone.observe().getString("package"))
            Thread.sleep(2400) // Let the deliberately late response finish; it must not dispatch a tool.
            assertEquals(1,server.requestCount)
            assertEquals(context.packageName,phone.observe().getString("package"))
        }
    }
    @Test fun anthropicConversationWaitsForUserAndUsesAutomaticCaching() {
        val requests=CopyOnWriteArrayList<JSONObject>()
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val body=JSONObject(request.body.readUtf8()); requests.add(body)
                    val call=if(requests.size==1) obj("type" to "tool_use", "id" to "ask1", "name" to "ask_user", "input" to obj("question" to "Which note should I save?"))
                        else obj("type" to "tool_use", "id" to "done2", "name" to "finish", "input" to obj("success" to true,"summary" to "Received the user's instruction."))
                    return MockResponse().setBody(obj("content" to arr(call), "stop_reason" to "tool_use", "usage" to obj("input_tokens" to 40,"cache_read_input_tokens" to 2048,"output_tokens" to 20)).toString())
                }
            }
            server.start()
            Stores.saveConfig(ProviderConfig(provider="anthropic",endpoint=server.url("/v1").toString().trimEnd('/'),apiKey="test-anthropic",model="scripted-anthropic"))
            inst.runOnMainSync { context.startForegroundService(Intent(context,AgentService::class.java).setAction(AgentService.START).putExtra("task","Ask me which note to save")) }
            waitUntil { AppState.session?.status=="WAITING" }
            assertEquals(1,server.requestCount)
            Thread.sleep(350)
            assertEquals(1,server.requestCount)
            AgentService.current!!.reply("Use the title Meeting notes")
            waitUntil { AppState.session?.status=="COMPLETE" }
            assertEquals(2,requests.size)
            assertEquals("ephemeral",requests[1].getJSONObject("cache_control").getString("type"))
            val first=requests[0].getJSONArray("messages"); val second=requests[1].getJSONArray("messages")
            for(i in 0 until first.length()) assertEquals(first.get(i).toString(),second.get(i).toString())
            assertTrue(second.toString().contains("Meeting notes"))
            assertEquals(4096,AppState.session!!.cached)
        }
    }
    @Test fun pauseHoldsAnInferenceResultUntilResume() {
        MockWebServer().use { server ->
            server.enqueue(response(0,"open_app",obj("package_name" to "com.android.settings")).setBodyDelay(1,TimeUnit.SECONDS))
            server.enqueue(response(1,"finish",obj("summary" to "Settings opened", "success" to true)))
            Stores.saveConfig(ProviderConfig(endpoint=server.url("/v1").toString().trimEnd('/'),apiKey="test",model="pause-test"))
            inst.runOnMainSync { context.startForegroundService(Intent(context,AgentService::class.java).setAction(AgentService.START).putExtra("task","Pause test")) }
            assertNotNull(server.takeRequest(10,TimeUnit.SECONDS))
            AgentService.current!!.pauseFor("Test pause")
            Thread.sleep(1400)
            assertEquals("PAUSED",AppState.session!!.status)
            assertEquals(context.packageName,phone.observe().getString("package"))
            assertEquals(1,server.requestCount)
            AgentService.current!!.resumeRun()
            waitUntil { AppState.session?.status=="COMPLETE" }
            assertEquals("com.android.settings",phone.observe().getString("package"))
        }
    }
    @Test fun agentCannotReadOrEditItsOwnCredentialScreen() {
        inst.runOnMainSync { context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        waitUntil { MainActivity.visible }
        val screen=phone.observe()
        assertTrue(screen.getBoolean("agent_controls_hidden"))
        assertEquals(0,screen.getJSONArray("nodes").length())
        try { phone.screenshot(); fail("Captured the agent controls") } catch(_:IllegalArgumentException) {}
        try { call("tap",obj("snapshot_id" to screen.getString("snapshot_id"),"x" to 100,"y" to 300)); fail("Tapped agent controls") } catch(_:Exception) {}
    }

}
