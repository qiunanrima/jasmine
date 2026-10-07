package eu.kanade.tachiyomi.source.builtin.ehentai

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Downloads and caches the markdown tables published by EhTagTranslation. */
internal class EhTagTranslation(
    context: Context,
    private val client: OkHttpClient,
) {
    private val cacheDir = File(context.filesDir, "eh-tag-translation")
    private val memory = ConcurrentHashMap<String, Map<String, String>>()
    private var versionCheckedAt = 0L
    private var remoteVersion: String? = null

    fun translate(tags: List<String>): List<String> = tags.map { tag ->
        val separator = tag.indexOf(':')
        if (separator <= 0 || separator == tag.lastIndex) return@map tag

        val namespace = tag.substring(0, separator).lowercase()
        val rawName = tag.substring(separator + 1)
        val translatedName = load(namespace)[rawName.lowercase()] ?: return@map tag
        val translatedNamespace = NAMESPACE_NAMES[namespace] ?: namespace
        "$translatedNamespace:$translatedName"
    }

    private fun load(namespace: String): Map<String, String> {
        memory[namespace]?.let { return it }
        val file = File(cacheDir, "$namespace.md")
        val cachedVersion = File(cacheDir, "$namespace.version").takeIf { it.isFile }?.readText().orEmpty()
        val version = getRemoteVersion()
        val map = if (file.isFile && (version == null || version == cachedVersion)) {
            parse(file.readText())
        } else {
            fetch(namespace)?.also { downloaded ->
                cacheDir.mkdirs()
                file.writeText(downloaded)
                if (version != null) File(cacheDir, "$namespace.version").writeText(version)
            }?.let(::parse) ?: if (file.isFile) parse(file.readText()) else emptyMap()
        }
        memory[namespace] = map
        return map
    }

    private fun getRemoteVersion(): String? {
        val now = System.currentTimeMillis()
        if (now - versionCheckedAt < VERSION_CHECK_INTERVAL) return remoteVersion
        versionCheckedAt = now
        remoteVersion = request("version")?.trim()?.takeIf { it.isNotEmpty() }
        return remoteVersion
    }

    private fun fetch(namespace: String): String? = request("database/$namespace.md")

    private fun request(path: String): String? = runCatching {
        val request = Request.Builder()
            .url("$RAW_BASE_URL/$path")
            .header("Accept", "text/plain")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body.string()
        }
    }.getOrNull()

    private fun parse(markdown: String): Map<String, String> = buildMap {
        markdown.lineSequence().forEach { line ->
            if (!line.trimStart().startsWith("|")) return@forEach
            val columns = line.split('|')
            if (columns.size < 3) return@forEach
            val raw = columns[1].trim()
            val translated = columns[2].trim()
            if (raw.isBlank() || translated.isBlank() || raw.startsWith("-") || raw.startsWith("==")) return@forEach
            put(raw.lowercase(), translated)
        }
    }

    companion object {
        private const val RAW_BASE_URL = "https://raw.githubusercontent.com/EhTagTranslation/Database/master"
        private const val VERSION_CHECK_INTERVAL = 24 * 60 * 60 * 1000L
        private val NAMESPACE_NAMES = mapOf(
            "female" to "女性",
            "male" to "男性",
            "mixed" to "混合",
            "location" to "地点",
            "language" to "语言",
            "other" to "其他",
            "group" to "团队",
            "artist" to "艺术家",
            "cosplayer" to "Coser",
            "parody" to "原作",
            "character" to "角色",
            "reclass" to "重新分类",
            "temp" to "临时",
        )
    }
}
