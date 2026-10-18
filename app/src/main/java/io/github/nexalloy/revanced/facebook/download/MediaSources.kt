package io.github.nexalloy.revanced.facebook.download

import android.os.Build
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.Locale

internal object GraphModel {
    private fun isTree(value: Any?): Boolean {
        var type: Class<*>? = value?.javaClass ?: return false
        while (type != null) {
            if (type.name == TREE_JNI) return true
            type = type.superclass
        }
        return false
    }

    private fun call(model: Any?, name: String, field: Int): Any? = runCatching {
        model!!.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(model, field)
    }.getOrNull()

    fun isLive(model: Any?) = isTree(model) &&
        runCatching { model!!.javaClass.getMethod("isValidGraphServicesJNIModel").invoke(model) == true }.getOrDefault(false)

    fun string(model: Any?, field: String) = call(model, "getString", field.hashCode()) as? String
    fun cachedString(model: Any?, field: String) = call(model, "getCachedString", field.hashCode()) as? String
    fun int(model: Any?, field: String) = call(model, "getIntValue", field.hashCode()) as? Int ?: 0
    fun tree(model: Any?, field: String) = if (isLive(model)) call(model, "getTree", field.hashCode())?.takeIf { isTree(it) } else null

    fun typeName(model: Any?): String? = if (!isLive(model)) null
    else runCatching { model!!.javaClass.getMethod("getTypeName").invoke(model) as? String }.getOrNull()

    fun isA(value: Any?, className: String): Boolean {
        var type: Class<*>? = value?.javaClass ?: return false
        while (type != null) {
            if (type.name == className) return true
            type = type.superclass
        }
        return false
    }

    fun callGetter(host: Any?, name: String): Any? = runCatching { host!!.javaClass.getMethod(name).invoke(host) }.getOrNull()
}

internal class VideoSource(val videoId: String?, val hd: String?, val manifest: String?)

internal object PlayerSources {
    private const val MAX = 48
    private val ID = Regex("\\d{8,20}")
    private val sources = object : LinkedHashMap<String, VideoSource>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoSource>) = size > MAX
    }

    fun remember(params: Any, idField: Field, hdField: Field, manifestField: Field) {
        val id = idField.get(params) as? String ?: return
        val source = Reflect.fieldOfType(params, VIDEO_DATA_SOURCE) ?: return
        val hd = hdField.get(source)?.toString()
        val manifest = (manifestField.get(source) as? String)?.takeIf { Dash.withinLimits(it) }
        if (hd == null && manifest == null) return
        synchronized(sources) { sources[id] = VideoSource(id, hd, manifest) }
    }

    fun byId(id: String?): VideoSource? = id?.let { synchronized(sources) { sources[it] } }

    fun find(card: Any?): VideoSource? {
        val ids = mutableListOf<String>()
        Reflect.walk(card, 3) { text ->
            if (text.length <= 40) ID.findAll(text).forEach { if (it.value !in ids) ids += it.value }
            false
        }
        return synchronized(sources) { ids.firstNotNullOfOrNull { sources[it] } }
    }
}

internal object Reflect {
    private const val MAX_NODES = 512

    fun fieldOfType(host: Any, typeName: String): Any? {
        var type: Class<*>? = host.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.firstOrNull { it.type.name == typeName }?.let { f ->
                return runCatching { f.isAccessible = true; f.get(host) }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    fun walk(host: Any?, maxDepth: Int, keep: (String) -> Boolean): List<String> {
        val found = mutableListOf<String>()
        if (host == null) return found
        val seen = IdentityHashMap<Any, Boolean>().apply { put(host, true) }
        val queue = ArrayDeque<Pair<Any, Int>>().apply { add(host to 0) }
        var nodes = 0
        while (queue.isNotEmpty() && nodes++ < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            var type: Class<*>? = node.javaClass
            while (type != null && type != Any::class.java) {
                for (field in runCatching { type.declaredFields }.getOrDefault(emptyArray())) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    val value = runCatching { field.isAccessible = true; field.get(node) }.getOrNull() ?: continue
                    if (value is CharSequence) {
                        val text = value.toString()
                        if (keep(text)) found += text
                        continue
                    }
                    if (depth >= maxDepth) continue
                    if (value is Iterable<*>) {
                        value.take(16).forEach { item ->
                            if (item is CharSequence) item.toString().let { if (keep(it)) found += it }
                            else if (item != null && isFacebook(item) && seen.put(item, true) == null) queue.add(item to depth + 1)
                        }
                        continue
                    }
                    if (isFacebook(value) && seen.put(value, true) == null) queue.add(value to depth + 1)
                }
                type = type.superclass
            }
        }
        return found
    }

    private fun isFacebook(value: Any) = value.javaClass.name.let { it.startsWith("com.facebook.") || it.startsWith("X.") }
}

internal object Urls {
    fun isHttp(text: String?) = text != null && text.length in 11..8192 &&
        (text.startsWith("https://", true) || text.startsWith("http://", true)) && text.none { it.isWhitespace() }

    private fun path(url: String) = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)

    fun isVideo(url: String): Boolean {
        val p = path(url)
        return p.endsWith(".mp4") || p.endsWith(".m4v") || p.endsWith(".mov") || url.contains("progressive", true)
    }

    fun isImage(url: String): Boolean {
        val p = path(url)
        return p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png") || p.endsWith(".webp") || p.endsWith(".heic")
    }

    fun isFacebookCdn(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase(Locale.ROOT)
        return host.endsWith("fbcdn.net") || host.endsWith("facebook.com") || host.endsWith("cdninstagram.com")
    }
}

internal object Dash {
    class Track(val mime: String, val codecs: String, val width: Int, val height: Int, val bandwidth: Long, val url: String) {
        val isVideo get() = mime.startsWith("video/")
        val isAudio get() = mime.startsWith("audio/")
        val shortSide get() = minOf(width, height)
    }

    private const val MAX_CHARS = 128 * 1024
    private val ADAPTATION_SET = Regex("<AdaptationSet\\b([^>]*)>(.*?)</AdaptationSet>", RegexOption.DOT_MATCHES_ALL)
    private val REPRESENTATION = Regex("<Representation\\b([^>]*?)(?:/>|>(.*?)</Representation>)", RegexOption.DOT_MATCHES_ALL)
    private val ATTRIBUTE = Regex("([\\w:]+)\\s*=\\s*\"([^\"]*)\"")
    private val BASE_URL = Regex("<BaseURL[^>]*>(.*?)</BaseURL>", RegexOption.DOT_MATCHES_ALL)

    fun withinLimits(manifest: String) = manifest.length <= MAX_CHARS && manifest.contains("<MPD")

    fun parse(manifest: String): List<Track> = if (!withinLimits(manifest)) emptyList() else runCatching {
        ADAPTATION_SET.findAll(manifest).flatMap { set ->
            val setAttrs = set.groupValues[1]
            REPRESENTATION.findAll(set.groupValues[2]).mapNotNull { track(setAttrs, it.groupValues[1], it.groupValues[2]) }
        }.toList()
    }.getOrDefault(emptyList())

    private fun track(setAttrs: String, attrs: String, body: String): Track? {
        val url = BASE_URL.find(body)?.groupValues?.get(1)?.trim()?.let(::unescape) ?: return null
        if (!url.startsWith("https://", true)) return null
        val mime = (attr(attrs, "mimeType") ?: attr(setAttrs, "mimeType") ?: attr(setAttrs, "contentType")?.let { "$it/mp4" })
            ?.lowercase(Locale.ROOT) ?: return null
        val codecs = (attr(attrs, "codecs") ?: attr(setAttrs, "codecs") ?: "").lowercase(Locale.ROOT)
        fun num(name: String) = attr(attrs, name)?.trim()?.toLongOrNull()?.coerceIn(0, Int.MAX_VALUE.toLong()) ?: 0
        return Track(mime, codecs, num("width").toInt(), num("height").toInt(), num("bandwidth"), url)
    }

    private fun attr(attrs: String, name: String) =
        ATTRIBUTE.findAll(attrs).firstOrNull { it.groupValues[1] == name }?.groupValues?.get(2)?.let(::unescape)?.takeIf { it.isNotBlank() }

    private fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private fun family(track: Track) = when {
        track.codecs.startsWith("avc1") || track.codecs.startsWith("avc3") -> 3
        track.codecs.startsWith("hvc1") || track.codecs.startsWith("hev1") -> 2
        track.codecs.startsWith("av01") && Build.VERSION.SDK_INT >= 34 -> 1
        else -> 0
    }

    fun bestVideo(tracks: List<Track>) = tracks.filter { it.isVideo && family(it) > 0 }
        .maxWithOrNull(compareBy<Track>({ it.shortSide }, { family(it) }, { it.bandwidth }))

    fun bestAudio(tracks: List<Track>) = tracks.filter { it.isAudio && it.codecs.startsWith("mp4a") }
        .maxWithOrNull(compareBy<Track>({
            when (it.codecs.removePrefix("mp4a.40.").toIntOrNull()) { 2 -> 2; 5, 29 -> 1; else -> 0 }
        }, { it.bandwidth }))

    fun hasSound(tracks: List<Track>) = tracks.any { it.isAudio }
}
