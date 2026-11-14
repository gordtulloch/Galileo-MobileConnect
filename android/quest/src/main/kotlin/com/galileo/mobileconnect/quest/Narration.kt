package com.galileo.mobileconnect.quest

import android.content.Context
import android.media.MediaPlayer

/**
 * Spoken object descriptions. The Quest has no text-to-speech engine, so the clips are baked into assets/speech by
 * tools/make_narration.py (Messier, Caldwell and the brighter NGC/IC objects); index.tsv holds the text each one says,
 * which the object menu shows as well.
 */
object Narration {
    private var context: Context? = null
    private var texts: Map<String, String> = emptyMap()
    private var aliases: Map<String, String> = emptyMap()
    private var player: MediaPlayer? = null

    @Volatile var speaking = false; private set

    private fun key(name: String) = name.replace(Regex("[^A-Za-z0-9]"), "_")
    private fun squash(name: String) = name.replace(Regex("[^A-Za-z0-9]"), "").uppercase()

    fun init(c: Context) {
        context = c.applicationContext
        val rows = try {
            c.assets.open("speech/index.tsv").bufferedReader().readLines()
        } catch (e: Exception) {
            emptyList()
        }
        val t = HashMap<String, String>()
        val a = HashMap<String, String>()
        for (row in rows) {
            val (k, text) = row.split('\t', limit = 2).takeIf { it.size == 2 } ?: continue
            t[k] = text
            a[squash(k)] = k
            Regex("^([^,]+),").find(text)?.let { a[squash(it.groupValues[1])] = k }
            Regex("Also known as ([^.]*)\\.").find(text)?.groupValues?.get(1)?.split(", ")?.forEach { a[squash(it)] = k }
        }
        texts = t
        aliases = a
    }

    private fun find(name: String): String? = key(name).takeIf { it in texts } ?: aliases[squash(name)]

    fun text(name: String): String? = find(name)?.let { texts[it] }

    fun has(name: String) = find(name) != null

    fun toggle(name: String) = if (speaking) stop() else play(name)

    fun play(name: String) {
        val k = find(name) ?: return
        val c = context ?: return
        stop()
        try {
            c.assets.openFd("speech/$k.m4a").use { fd ->
                player = MediaPlayer().apply {
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    setOnCompletionListener { stop() }
                    prepare()
                    start()
                }
            }
            speaking = true
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        speaking = false
    }
}
