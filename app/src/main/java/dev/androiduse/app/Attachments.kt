package dev.androiduse.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Base64
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/** Metadata stays small; full payloads never travel through Activity/Service intents. */
data class Attachment(val id: String, val name: String, val mime: String, val size: Int, val thumbnail: String = "") {
    fun json() = obj("id" to id, "name" to name, "mime" to mime, "size" to size, "thumbnail" to thumbnail)
    companion object {
        fun from(j: JSONObject) = Attachment(j.getString("id"), j.getString("name"), j.getString("mime"), j.getInt("size"), j.optString("thumbnail"))
    }
}
data class AttachmentInput(val attachment: Attachment, val data: String)

object Attachments {
    const val MAX_COUNT = 4
    const val MAX_BYTES = 4 * 1024 * 1024
    private lateinit var context: Context
    val worker = Executors.newSingleThreadExecutor()
    private val importing = mutableSetOf<String>()
    private val errors = mutableMapOf<String, String>()
    private val prefs get() = context.getSharedPreferences("attachment_drafts", Context.MODE_PRIVATE)
    private val folder get() = File(context.filesDir, "attachments").apply { mkdirs() }
    fun init(ctx: Context) { context = ctx.applicationContext }
    private fun file(id: String, suffix: String): File {
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "Invalid attachment ID." }
        return File(folder, "$id.$suffix.enc")
    }
    private fun write(file: File, text: String) {
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(Vault.encrypt(text).toByteArray()); atomic.finishWrite(stream) }
        catch(e: Exception) { atomic.failWrite(stream); throw e }
    }
    fun metadata(id: String) = Attachment.from(JSONObject(Vault.decrypt(AtomicFile(file(id,"meta")).readFully().toString(Charsets.UTF_8))))
    fun load(ids: List<String>): List<AttachmentInput> {
        require(ids.size <= MAX_COUNT) { "Attach up to four files per message." }
        val inputs = ids.map { AttachmentInput(metadata(it), Vault.decrypt(AtomicFile(file(it,"data")).readFully().toString(Charsets.UTF_8))) }
        require(inputs.sumOf { it.attachment.size.toLong() } <= MAX_BYTES) { "Attachments must total 4 MB or less after preparation." }
        return inputs
    }
    @Synchronized fun ids(key: String): List<String> = JSONArray(prefs.getString(key,"[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
    @Synchronized fun draft(key: String) = ids(key).mapNotNull { runCatching { metadata(it) }.getOrNull() }
    @Synchronized fun busy(key: String) = key in importing
    @Synchronized fun takeError(key: String) = errors.remove(key)
    @Synchronized fun forgetDraft(key: String) { prefs.edit().remove(key).commit() }
    @Synchronized fun remove(key: String, id: String) {
        prefs.edit().putString(key, JSONArray(ids(key).filterNot { it==id }).toString()).commit()
        delete(id); AppState.changed()
    }
    fun delete(id: String) { file(id,"meta").delete(); file(id,"data").delete() }
    @Synchronized fun clear() { check(importing.isEmpty()) { "Wait for attachments to finish preparing." }; prefs.edit().clear().commit(); folder.listFiles()?.forEach { it.delete() }; errors.clear() }
    @Synchronized fun importUris(key: String, uris: List<Uri>) {
        if(!importing.add(key)) return
        AppState.changed()
        worker.execute {
            try {
                for(uri in uris) {
                    synchronized(this) { require(ids(key).size < MAX_COUNT) { "Attach up to four files per message." } }
                    val input = prepare(uri)
                    synchronized(this) {
                        require(draft(key).sumOf { it.size.toLong() } + input.attachment.size <= MAX_BYTES) { "Attachments must total 4 MB or less after preparation." }
                        val a = input.attachment
                        try {
                            write(file(a.id,"data"), input.data); write(file(a.id,"meta"), a.json().toString())
                            check(prefs.edit().putString(key, JSONArray(ids(key)+a.id).toString()).commit())
                        } catch(e: Exception) { delete(a.id); throw e }
                    }
                    AppState.changed()
                }
            } catch(e: Exception) { synchronized(this) { errors[key] = e.message ?: "Could not read this attachment." } }
            finally { synchronized(this) { importing.remove(key) }; AppState.changed() }
        }
    }
    private fun readBounded(stream: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while(true) { val n=stream.read(buffer); if(n<0) break; require(out.size()+n<=limit) { "This file is too large." }; out.write(buffer,0,n) }
        return out.toByteArray()
    }
    fun prepare(uri: Uri): AttachmentInput {
        val resolver = context.contentResolver
        var name = "attachment"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if(it.moveToFirst()) name=it.getString(0) ?: name }
        name = name.replace(Regex("[\\p{Cntrl}/\\\\]"), "_").take(160)
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = resolver.getType(uri).orEmpty()
        val image = mime.startsWith("image/") || ext in setOf("jpg","jpeg","png","webp","gif","heic","heif")
        val pdf = mime=="application/pdf" || ext=="pdf"
        val docx = ext=="docx" || mime=="application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val text = mime.startsWith("text/") || ext in setOf("txt","md","csv","tsv","json","xml","html","yaml","yml","log","kt","java","py","js","ts","tsx","jsx","css","sh","sql","toml","ini")
        require(image || pdf || docx || text) { "Choose an image, PDF, DOCX, or text file. This format isn’t supported yet." }
        val raw = resolver.openInputStream(uri)?.use { readBounded(it, if(image) 20*1024*1024 else if(docx) MAX_BYTES else if(pdf) MAX_BYTES else 200_000) } ?: error("Could not open this file.")
        require(raw.isNotEmpty()) { "This file is empty." }
        var thumbnail = ""
        val prepared: ByteArray
        val preparedMime: String
        if(image) {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(raw))) { decoder, info, _ ->
                val factor = minOf(1.0, 1600.0 / maxOf(info.size.width,info.size.height))
                decoder.setTargetSize(maxOf(1,(info.size.width*factor).toInt()),maxOf(1,(info.size.height*factor).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            prepared = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG,85,it) }.toByteArray()
            val scale = minOf(1.0, 480.0/maxOf(bitmap.width,bitmap.height))
            val small = Bitmap.createScaledBitmap(bitmap,maxOf(1,(bitmap.width*scale).toInt()),maxOf(1,(bitmap.height*scale).toInt()),true)
            thumbnail = Base64.encodeToString(ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG,80,it) }.toByteArray(),Base64.NO_WRAP)
            if(small!==bitmap) small.recycle(); bitmap.recycle(); preparedMime="image/jpeg"
        } else if(pdf) {
            require(raw.take(5).toByteArray().toString(Charsets.US_ASCII)=="%PDF-") { "This file is not a valid PDF." }
            prepared=raw; preparedMime="application/pdf"
        } else {
            val extracted = if(docx) extractDocx(raw) else Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString().removePrefix("\uFEFF")
            require(extracted.isNotBlank() && !extracted.contains('\u0000')) { "Choose a non-empty UTF-8 text file." }
            require(extracted.length<=100_000) { "Text attachments can contain up to 100,000 characters." }
            prepared=extracted.toByteArray(Charsets.UTF_8); preparedMime="text/plain"
        }
        require(prepared.size<=MAX_BYTES) { "This attachment exceeds 4 MB after preparation." }
        return AttachmentInput(Attachment(UUID.randomUUID().toString(),name,preparedMime,prepared.size,thumbnail),
            if(preparedMime=="text/plain") prepared.toString(Charsets.UTF_8) else Base64.encodeToString(prepared,Base64.NO_WRAP))
    }
    private fun extractDocx(raw: ByteArray): String {
        ZipInputStream(raw.inputStream()).use { zip ->
            var scanned=0L; var entries=0
            while(true) {
                val entry=zip.nextEntry ?: break
                require(++entries<=2000) { "This Word document is too complex." }
                val bytes=readBounded(zip,2*1024*1024)
                scanned+=bytes.size; require(scanned<=16*1024*1024) { "This Word document is too large when expanded." }
                if(entry.name!="word/document.xml") continue
                val parser=Xml.newPullParser(); parser.setInput(bytes.inputStream(),"UTF-8")
                val out=StringBuilder(); var insideText=false
                while(parser.eventType!=XmlPullParser.END_DOCUMENT) {
                    when(parser.eventType) {
                        XmlPullParser.DOCDECL -> error("Unsupported Word document XML.")
                        XmlPullParser.START_TAG -> { if(parser.name=="t") insideText=true; if(parser.name=="tab") out.append('\t') }
                        XmlPullParser.TEXT -> if(insideText) out.append(parser.text)
                        XmlPullParser.END_TAG -> { if(parser.name=="t") insideText=false; if(parser.name in setOf("p","br","tr")) out.append('\n') }
                    }
                    require(out.length<=100_000) { "Word documents can contain up to 100,000 characters." }; parser.nextToken()
                }
                return out.toString()
            }
        }
        error("Could not read this Word document. Choose a DOCX file.")
    }
}
