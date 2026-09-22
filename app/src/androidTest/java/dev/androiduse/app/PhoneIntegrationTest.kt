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
        androidx.test.uiautomator.Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        // A prior instrumentation process can leave an enabled service marked crashed.
        // Toggle its test-only grant so Android actually binds this process again.
        if (PhoneAccessibilityService.instance == null) {
            shell("settings delete secure enabled_accessibility_services")
            Thread.sleep(250)
        }
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
            val previousId=AppState.session?.id
            inst.runOnMainSync { context.startForegroundService(Intent(context,AgentService::class.java).setAction(AgentService.START).putExtra("task","Save a practice note")) }
            waitUntil(60000) { AppState.session?.id!=previousId && AgentService.current==null && AppState.session?.status in setOf("COMPLETE","ERROR","INCOMPLETE","LIMIT") }
            val s=AppState.session!!
            assertEquals(s.summary,"COMPLETE",s.status)
            assertTrue(phone.observe().toString().contains("Saved: Agent HTTP loop verified"))
            assertEquals(5,requests.size)
            assertEquals(listOf("done","done","done","done"),s.events.filter { it.kind=="tool" }.map { it.state })
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

    @Test fun followUpReloadsEncryptedTranscriptAndPreservesRequestPrefix() {
        MockWebServer().use { server ->
            server.enqueue(response(0,"finish",obj("summary" to "First reply", "success" to true)))
            server.enqueue(response(1,"finish",obj("summary" to "Follow-up reply", "success" to true)))
            val config=ProviderConfig(endpoint=server.url("/v1").toString().trimEnd('/'),apiKey="test",model="chat-test")
            Stores.saveConfig(config)
            inst.runOnMainSync { context.startForegroundService(Intent(context,AgentService::class.java).setAction(AgentService.START).putExtra("task","First message")) }
            waitUntil { AppState.session?.task=="First message" && AppState.session?.status=="COMPLETE" && AgentService.current==null }
            val id=AppState.session!!.id
            val saved=Stores.loadSession(id)!!
            val before=saved.conversation.request(config).getJSONArray("messages")
            assertTrue(before.length()>3)
            assertEquals(config.endpoint,saved.endpoint)
            AppState.session=null // Mimic an unloaded transcript; continuation must read disk.
            inst.runOnMainSync { context.startForegroundService(Intent(context,AgentService::class.java).setAction(AgentService.START).putExtra("task","Follow-up message").putExtra("session_id",id)) }
            waitUntil { AppState.session?.id==id && AppState.session?.summary=="Follow-up reply" && AgentService.current==null }
            server.takeRequest(5,TimeUnit.SECONDS)
            val request=JSONObject(server.takeRequest(5,TimeUnit.SECONDS)!!.body.readUtf8())
            val after=request.getJSONArray("messages")
            for(i in 0 until before.length()) assertEquals("Changed history at $i",before.get(i).toString(),after.get(i).toString())
            assertEquals("Follow-up message",after.getJSONObject(before.length()).getString("content"))
            assertEquals(listOf("First message","Follow-up message"),Stores.loadSession(id)!!.events.filter { it.kind=="user" }.map { it.text })
            assertEquals(1,Stores.sessions().count { it.id==id })
        }
    }

    @Test fun chatStartsBlankSwipeOpensHistoryAndSettingsAndDraftSurvivesRotation() {
        val device=androidx.test.uiautomator.UiDevice.getInstance(inst)
        fun find(selector: androidx.test.uiautomator.BySelector): androidx.test.uiautomator.UiObject2 {
            return device.wait(androidx.test.uiautomator.Until.findObject(selector),7000) ?: error("Missing UI element: $selector")
        }
        Stores.clearHistory()
        val fixture=Session("Save a shopping list").apply {
            status="COMPLETE"; provider="openai"; model="gpt-4.1-mini"; endpoint="https://api.openai.com/v1"
            log("user",task); log("result","Saved your shopping list with milk, coffee, and bread.")
            log("user","Add apples to the list too")
            log("result","Added apples to your shopping list.")
            conversation.addUser(task)
        }
        Stores.saveSession(fixture)
        inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        inst.waitForIdleSync(); device.waitForIdle()
        find(androidx.test.uiautomator.By.text("How can I help?"))
        assertFalse(device.hasObject(androidx.test.uiautomator.By.text(fixture.task)))
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"new-chat.png"))
        device.swipe(device.displayWidth/5,device.displayHeight/2,device.displayWidth*4/5,device.displayHeight/2,25)
        find(androidx.test.uiautomator.By.text("Chats"))
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"chat-drawer.png"))
        find(androidx.test.uiautomator.By.text(fixture.task)).click()
        find(androidx.test.uiautomator.By.text("Added apples to your shopping list."))
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"conversation.png"))
        find(androidx.test.uiautomator.By.desc("New chat")).click()
        find(androidx.test.uiautomator.By.text("How can I help?"))
        find(androidx.test.uiautomator.By.desc("Message")).text="Keep this draft"
        device.setOrientationLeft()
        find(androidx.test.uiautomator.By.text("Keep this draft"))
        device.setOrientationNatural()
        find(androidx.test.uiautomator.By.text("Keep this draft"))
        find(androidx.test.uiautomator.By.desc("Open chat history")).click()
        find(androidx.test.uiautomator.By.desc("Open settings")).click()
        find(androidx.test.uiautomator.By.text("API key"))
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"chat-settings.png"))
        find(androidx.test.uiautomator.By.desc("Back to chat")).click()
        find(androidx.test.uiautomator.By.text("Keep this draft"))
        device.unfreezeRotation()
    }

    @Test fun composerSendsNewAndFollowUpMessagesAndKeepsKeyboardClear() {
        val device=androidx.test.uiautomator.UiDevice.getInstance(inst)
        fun find(selector: androidx.test.uiautomator.BySelector): androidx.test.uiautomator.UiObject2 =
            device.wait(androidx.test.uiautomator.Until.findObject(selector),7000) ?: error("Missing $selector")
        MockWebServer().use { server ->
            server.enqueue(response(0,"finish",obj("summary" to "Ready to help with your phone.","success" to true)))
            server.enqueue(response(1,"finish",obj("summary" to "I still have our conversation.","success" to true)))
            Stores.saveConfig(ProviderConfig(endpoint=server.url("/v1").toString().trimEnd('/'),apiKey="ui-test",model="chat-test"))
            if(android.os.Build.VERSION.SDK_INT>=33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        inst.waitForIdleSync(); device.waitForIdle()
            find(androidx.test.uiautomator.By.desc("Message")).click()
            find(androidx.test.uiautomator.By.desc("Message")).text="Hello from my phone"
            find(androidx.test.uiautomator.By.text("Hello from my phone"))
            device.waitForIdle()
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"chat-keyboard.png"))
            val button=find(androidx.test.uiautomator.By.desc("Send message"))
            assertTrue("Composer should resize above keyboard",button.visibleBounds.bottom<device.displayHeight*3/4)
            button.click()
            try { waitUntil { AgentService.current==null && AppState.session?.summary=="Ready to help with your phone." } }
            catch(e: AssertionError) {
                device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"send-failure.png"))
                throw AssertionError("Send failed: ${AppState.session?.status}: ${AppState.session?.summary}; requests=${server.requestCount}", e)
            }
            find(androidx.test.uiautomator.By.text("Ready to help with your phone."))
            find(androidx.test.uiautomator.By.text("Hello from my phone"))
            val id=AppState.session!!.id
            find(androidx.test.uiautomator.By.desc("Message")).text="Do you remember this chat?"
            find(androidx.test.uiautomator.By.desc("Send message")).click()
            waitUntil { AgentService.current==null && AppState.session?.summary=="I still have our conversation." }
            assertEquals(id,AppState.session!!.id)
            find(androidx.test.uiautomator.By.text("I still have our conversation."))
            assertEquals(2,server.requestCount)
            find(androidx.test.uiautomator.By.desc("Open chat history")).click()
            find(androidx.test.uiautomator.By.text("Chats"))
            device.pressBack()
            find(androidx.test.uiautomator.By.desc("Open chat history"))
            find(androidx.test.uiautomator.By.text("I still have our conversation."))
        }
    }

    @Test fun tokenBudgetMigrationUpdatesOldDefaultOnceAndPreservesCustomValues() {
        val prefs=context.getSharedPreferences("settings",0)
        prefs.edit().remove("token_default_v3").putInt("tokens",100000).commit()
        Stores.migrateTokenDefault()
        assertEquals(10000000,Stores.config().maxInputTokens)
        prefs.edit().putInt("tokens",100000).commit()
        Stores.migrateTokenDefault()
        assertEquals("An intentional setting after upgrade must stay intact",100000,Stores.config().maxInputTokens)
        prefs.edit().remove("token_default_v3").putInt("tokens",250000).commit()
        Stores.migrateTokenDefault()
        assertEquals(250000,Stores.config().maxInputTokens)
        prefs.edit().remove("tokens").commit()
        assertEquals(10000000,Stores.config().maxInputTokens)
    }

    @Test fun chatShowsLiveAgentMessagesToolProgressAndPersistedDetails() {
        val device=androidx.test.uiautomator.UiDevice.getInstance(inst)
        fun find(selector: androidx.test.uiautomator.BySelector): androidx.test.uiautomator.UiObject2 =
            device.wait(androidx.test.uiautomator.Until.findObject(selector),7000) ?: error("Missing $selector")
        MockWebServer().use { server ->
            val tool=obj("id" to "wait1", "type" to "function", "function" to obj("name" to "wait", "arguments" to obj("milliseconds" to 3500).toString()))
            val message=obj("role" to "assistant", "content" to "I am checking the current screen.", "tool_calls" to arr(tool))
            val body=obj("choices" to arr(obj("finish_reason" to "tool_calls", "message" to message)))
            server.enqueue(MockResponse().setBody(body.toString()))
            server.enqueue(response(1,"finish",obj("summary" to "The screen check is complete.","success" to true)))
            Stores.saveConfig(ProviderConfig(endpoint=server.url("/v1").toString().trimEnd('/'),apiKey="ui-test",model="timeline-test"))
            if(android.os.Build.VERSION.SDK_INT>=33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            inst.waitForIdleSync(); device.waitForIdle()
            find(androidx.test.uiautomator.By.desc("Message")).text="Check the current screen"
            find(androidx.test.uiautomator.By.desc("Send message")).click()
            find(androidx.test.uiautomator.By.text("I am checking the current screen."))
            find(androidx.test.uiautomator.By.textContains("Wait · Running"))
            find(androidx.test.uiautomator.By.descContains("Tap for details")).click()
            find(androidx.test.uiautomator.By.textContains("3500"))
            waitUntil { AgentService.current==null && AppState.session?.summary=="The screen check is complete." }
            find(androidx.test.uiautomator.By.text("The screen check is complete."))
            find(androidx.test.uiautomator.By.text("✓  Wait"))
            find(androidx.test.uiautomator.By.textContains("Tool completed."))
            val saved=Stores.loadSession(AppState.session!!.id)!!
            assertEquals("done",saved.events.first { it.kind=="tool" }.state)
            assertTrue(saved.events.first { it.kind=="tool" }.details.contains("3500"))
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"inline-actions.png"))
            find(androidx.test.uiautomator.By.desc("Message")).text="A draft for this chat"
            find(androidx.test.uiautomator.By.desc("New chat")).click()
            find(androidx.test.uiautomator.By.text("How can I help?"))
            find(androidx.test.uiautomator.By.desc("Open chat history")).click()
            find(androidx.test.uiautomator.By.text("Check the current screen")).click()
            find(androidx.test.uiautomator.By.text("A draft for this chat"))
            find(androidx.test.uiautomator.By.text("✓  Wait"))
            // Reverse direction to dismiss the drawer with a short, quick left swipe.
            find(androidx.test.uiautomator.By.desc("Open chat history")).click()
            find(androidx.test.uiautomator.By.text("Chats"))
            device.waitForIdle()
            device.swipe(device.displayWidth*2/3,device.displayHeight/2,device.displayWidth/8,device.displayHeight/2,12)
            find(androidx.test.uiautomator.By.desc("Open chat history"))
            assertFalse(device.hasObject(androidx.test.uiautomator.By.text("Chats")))
        }
    }

}
