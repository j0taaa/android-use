package dev.androiduse.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentCoreTest {
    private fun reply(provider: String): ModelReply = if(provider == "anthropic")
        ProviderClient.parse(provider, obj("content" to arr(obj("type" to "tool_use", "id" to "call-1", "name" to "observe", "input" to obj())), "usage" to obj("input_tokens" to 20, "cache_read_input_tokens" to 100, "cache_creation_input_tokens" to 30, "output_tokens" to 10)))
    else ProviderClient.parse(provider, obj("choices" to arr(obj("message" to obj("role" to "assistant", "content" to null, "tool_calls" to arr(obj("id" to "call-1", "type" to "function", "function" to obj("name" to "observe", "arguments" to "{}")))), "finish_reason" to "tool_calls")), "usage" to obj("prompt_tokens" to 150, "prompt_tokens_details" to obj("cached_tokens" to 100), "completion_tokens" to 10)))

    @Test fun `attachments use native provider blocks and survive followups unchanged`() {
        val attachments=listOf(
            AttachmentInput(Attachment("image","photo.jpg","image/jpeg",3),"YWJj"),
            AttachmentInput(Attachment("pdf","guide.pdf","application/pdf",3),"ZGVm"),
            AttachmentInput(Attachment("text","notes.txt","text/plain",5),"hello")
        )
        for(provider in listOf("openai","anthropic")) {
            val c=Conversation(provider); c.addUser("",attachments)
            val parts=c.messages.getJSONObject(0).getJSONArray("content")
            val image=parts.getJSONObject(1)
            val pdf=parts.getJSONObject(2)
            if(provider=="openai") {
                assertEquals("data:image/jpeg;base64,YWJj",image.getJSONObject("image_url").getString("url"))
                assertEquals("data:application/pdf;base64,ZGVm",pdf.getJSONObject("file").getString("file_data"))
                assertEquals("guide.pdf",pdf.getJSONObject("file").getString("filename"))
            } else {
                assertEquals("image",image.getString("type")); assertEquals("YWJj",image.getJSONObject("source").getString("data"))
                assertEquals("document",pdf.getString("type")); assertEquals("application/pdf",pdf.getJSONObject("source").getString("media_type"))
            }
            assertTrue(parts.getJSONObject(3).getString("text").endsWith("hello"))
            val prefix=c.messages.toString()
            c.addUser("A follow-up")
            assertEquals(JSONArray(prefix).get(0).toString(),c.messages.get(0).toString())
            assertEquals(prefix,JSONArray().put(c.messages.get(0)).toString())
        }
    }

    @Test fun `previous messages tools and instructions remain identical as a task grows`() {
        for (provider in listOf("openai", "anthropic")) {
            val config = ProviderConfig(provider = provider)
            val history = Conversation(provider)
            history.addUser("Save a note")
            history.addUser("Initial screen: snapshot=s1")
            val before = history.request(config)
            val oldMessages = before.getJSONArray("messages")
            val response = reply(provider)
            history.addAssistant(response)
            history.addResult(response.calls.single(), ToolOutput(obj("snapshot_id" to "s2", "text" to "new screen"), "AAAA"))
            val after = history.request(config)
            assertEquals(before.getJSONArray("tools").toString(), after.getJSONArray("tools").toString())
            if(provider == "anthropic") { assertEquals(before.getString("system"), after.getString("system")); assertEquals("ephemeral", after.getJSONObject("cache_control").getString("type")) }
            for(i in 0 until oldMessages.length()) assertEquals(oldMessages.get(i).toString(), after.getJSONArray("messages").get(i).toString())
            assertEquals(oldMessages.toString(), before.getJSONArray("messages").toString())
            assertTrue(after.getJSONArray("messages").length() > oldMessages.length())
            assertEquals(after.toString(), history.request(config).toString())
        }
    }
    @Test fun `tool result images follow their call without rewriting earlier messages`() {
        val c = Conversation("openai"); c.addUser("look")
        val r = reply("openai"); c.addAssistant(r); c.addResult(r.calls.single(), ToolOutput(obj("width" to 100), "image-data"))
        assertEquals("tool", c.messages.getJSONObject(2).getString("role"))
        assertEquals("call-1", c.messages.getJSONObject(2).getString("tool_call_id"))
        assertEquals("user", c.messages.getJSONObject(3).getString("role"))
        assertTrue(c.messages.getJSONObject(3).toString().contains("data:image/jpeg;base64,image-data"))
    }
    @Test fun `cache usage is provider reported and anthropic input includes cache tokens`() {
        for(p in listOf("openai", "anthropic")) { val r = reply(p); assertEquals(150,r.input); assertEquals(100,r.cached); assertEquals(10,r.output) }
        assertEquals(30, reply("anthropic").cacheWrite)
    }
    @Test fun `malformed and unknown tool calls fail validation`() {
        listOf(ToolCall("1","shell",obj("command" to "ls")), ToolCall("2","set_text",obj("text" to "hi")), ToolCall("3","navigate",obj("action" to "unlock")), ToolCall("4","wait",obj("milliseconds" to "100"))).forEach {
            try { PhoneTools.validate(it); fail("Should reject ${it.name}") } catch(_: IllegalArgumentException) {}
        }
    }
    @Test fun `endpoint credentials and insecure remote URLs are rejected`() {
        listOf("http://example.com/v1", "https://user:pass@example.com/v1", "https://example.com/v1?key=secret").forEach {
            try { ProviderConfig(endpoint=it,apiKey="key").validate(); fail("Accepted unsafe endpoint") } catch(_: IllegalArgumentException) {}
        }
        ProviderConfig(endpoint="http://127.0.0.1:8080/v1",apiKey="key").validate()
    }
    @Test fun `provider request uses authentication header and keeps key out of body`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(obj("choices" to arr(obj("message" to obj("role" to "assistant", "content" to "Ready")))).toString()))
            val config = ProviderConfig(endpoint=server.url("/v1").toString().trimEnd('/'),apiKey="private-test-key")
            ProviderClient(config).infer(Conversation("openai").apply { addUser("hello") })
            val request=server.takeRequest()
            assertEquals("Bearer private-test-key",request.getHeader("Authorization"))
            assertFalse(request.body.readUtf8().contains("private-test-key"))
        }
    }
    @Test fun `provider error never echoes potentially private response body`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("secret echoed credential"))
            try { ProviderClient(ProviderConfig(endpoint=server.url("/v1").toString(),apiKey="key")).infer(Conversation("openai")); fail() }
            catch(e: java.io.IOException) { assertTrue(e.message!!.contains("401")); assertFalse(e.message!!.contains("secret")) }
        }
    }
    @Test fun `cancellation before inference never dispatches a request`() {
        val client = ProviderClient(ProviderConfig())
        client.cancel()
        try { client.infer(Conversation("openai")); fail() } catch(_: IllegalStateException) {}
    }
    @Test fun `truncated model calls are not executable`() {
        try { ProviderClient.parse("openai", obj("choices" to arr(obj("finish_reason" to "length", "message" to obj("content" to "partial"))))); fail() }
        catch(_: IllegalArgumentException) {}
    }
    @Test fun `interrupted calls are resolved once without changing the cached prefix`() {
        for (provider in listOf("openai", "anthropic")) {
            val c = Conversation(provider); c.addUser("Save a note")
            val response = reply(provider); c.addAssistant(response)
            val before = JSONArray(c.messages.toString())
            c.closeInterruptedCalls()
            assertEquals(before.length()+1,c.messages.length())
            for (i in 0 until before.length()) assertEquals(before.get(i).toString(),c.messages.get(i).toString())
            assertTrue(c.messages.getJSONObject(c.messages.length()-1).toString().contains("not replayed"))
            val resolved=c.messages.toString()
            c.closeInterruptedCalls()
            assertEquals(resolved,c.messages.toString())
            c.addUser("Continue after checking the screen")
            assertEquals(before.length()+2,c.messages.length())
        }
    }

    @Test fun `default token budget is one hundred times the original and is valid`() {
        val config=ProviderConfig(apiKey="test")
        assertEquals(100000*100,config.maxInputTokens)
        config.validate()
        config.copy(maxInputTokens=100000).validate()
        try { config.copy(maxInputTokens=100000001).validate(); fail("Unbounded budget accepted") }
        catch(_:IllegalArgumentException) {}
    }

}
