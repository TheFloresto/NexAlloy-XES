package io.github.nexalloy.revanced.facebook.download

import android.content.Context
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.scopedHook

val DownloadStory = patch(
    name = "Download any story",
    description = "Adds Save to the menu of any story, including stories with music, at the best quality.",
) {
    val builder = ::storyMenuBuilderFingerprint.method
    val capability = ::storySaveCapabilityFingerprint.method
    val tap = ::storySaveTapFingerprint.method
    val contextField = tap.declaringClass.declaredFields.single { it.type == Context::class.java }.accessible()
    val cardField = tap.declaringClass.declaredFields.single { it.type.name == STORY_CARD }.accessible()
    recordPlayerSources()
    useCdnResize()

    deoptimize(builder)
    builder.hookMethod(scopedHook(capability) {
        after { param -> param.result = true }
    })

    tap.hookMethod {
        before { param ->
            val context = contextField.get(param.thisObject) as? Context ?: return@before
            val card = cardField.get(param.thisObject) ?: return@before
            if (runCatching { saveStory(context, card) }.getOrDefault(false)) param.result = null
        }
    }
}

private fun saveStory(context: Context, card: Any): Boolean {
    val media = GraphModel.callGetter(card, "getMedia")
    val from = media ?: card
    val depth = if (media != null) 1 else 2
    val urls = Reflect.walk(from, depth) { Urls.isHttp(it) && Urls.isFacebookCdn(it) }
    val source = PlayerSources.find(card)
    val manifest = source?.manifest ?: Reflect.walk(from, depth) { it.contains("<MPD") && !Urls.isHttp(it) }.firstOrNull()
    val videos = listOfNotNull(source?.hd) + urls.filter { Urls.isVideo(it) }
    if (manifest != null || videos.isNotEmpty()) return MediaSaver.saveVideo(context, source?.videoId, manifest, videos)

    val photo = GraphModel.tree(media, "image")?.let { GraphModel.string(it, "uri") } ?: urls.firstOrNull { Urls.isImage(it) }
    return MediaSaver.savePhoto(context, GraphModel.string(media, "id"), photo)
}
