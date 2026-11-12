package com.jarvis.assistant.speech

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** Downloads + caches Vosk models (speech model and speaker x-vector model). */
object VoskModels {
    const val MAIN_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"
    const val SPEAKER_URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"

    private val lock = Any()

    fun mainDir(context: Context) = File(context.filesDir, "vosk/model")
    fun speakerDir(context: Context) = File(context.filesDir, "vosk/spk-model")

    /** Returns [target] after making sure it is installed. Safe to call from any worker thread. */
    fun ensure(context: Context, url: String, target: File, onStatus: (String) -> Unit, label: String): File =
        synchronized(lock) {
            if (!isInstalled(target)) {
                onStatus("Downloading $label (one time)…")
                download(context, url, target)
                onStatus("")
            }
            target
        }

    private fun isInstalled(dir: File) = dir.isDirectory && (dir.list()?.isNotEmpty() == true)

    private fun download(context: Context, url: String, target: File) {
        val zip = File(context.cacheDir, "vosk-download.zip")
        OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Model download failed (HTTP ${resp.code}).")
            val body = resp.body ?: throw IOException("Model download was empty.")
            body.byteStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        }

        val tmp = File(context.filesDir, "vosk/tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = File(tmp, entry.name)
                if (!out.canonicalPath.startsWith(tmp.canonicalPath)) throw SecurityException("Bad zip entry")
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        val inner = tmp.listFiles()?.singleOrNull { it.isDirectory } ?: tmp
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        if (!inner.renameTo(target)) throw IOException("Could not install the model.")
        tmp.deleteRecursively()
        zip.delete()
    }
}
