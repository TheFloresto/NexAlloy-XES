package io.github.nexalloy.revanced.instagram.story

import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

private const val REEL_ITEM = "com.instagram.model.reels.ReelItem"
private const val MEDIA = "com.instagram.feed.media.Media"
private const val USER = "com.instagram.user.model.User"
private const val SHARE_TYPE = "com.instagram.pendingmedia.model.constants.ShareType"
private const val BIND_MEDIA = "ReelViewerItemBinder.bindMedia"
private const val OP_IPUT_OBJECT = 0x5b

private fun DexKitBridge.pandoGetter(type: String, field: String, returns: String?): MethodData = findMethod {
    matcher {
        declaredClass(type)
        paramCount = 0
        if (returns != null) returnType = returns
        usingNumbers(field.hashCode())
    }
}.single { !Modifier.isStatic(it.modifiers) && '.' in it.returnTypeName }

val storyBindMethodsFingerprint = findMethodListDirect {
    findMethod { matcher { usingEqStrings(BIND_MEDIA) } }
}

val storyPageItemFieldFingerprint = findFieldDirect {
    storyBindMethodsFingerprint().map { m ->
        val code = m.instructions
        val at = code.indexOfFirst { it.string == BIND_MEDIA }
        code.drop(at + 1).first { it.opcode == OP_IPUT_OBJECT && it.fieldRef?.typeName == REEL_ITEM }.fieldRef!!
    }.distinctBy { it.descriptor }.single()
}

val mediaReelMentionsGetterFingerprint = findMethodDirect { pandoGetter(MEDIA, "reel_mentions", "java.util.List") }
val userFullNameGetterFingerprint = findMethodDirect { pandoGetter(USER, "full_name", "java.lang.String") }
val userProfilePicGetterFingerprint = findMethodDirect { pandoGetter(USER, "profile_pic_url", null) }

private fun DexKitBridge.highQualityPref(): MethodData = findMethod {
    matcher {
        returnType = "boolean"
        paramCount = 1
        usingEqStrings("high_quality_media_upload")
        addInvoke { name = "getInt"; paramTypes("java.lang.String", "int") }
    }
}.single { Modifier.isStatic(it.modifiers) }

val uploadHighQualityCheckFingerprint = findMethodDirect {
    val pref = highQualityPref()
    findMethod {
        matcher {
            returnType = "boolean"
            paramCount = 0
            addInvoke(pref.descriptor)
        }
    }.single { m ->
        val owner = m.declaredClass ?: return@single false
        !Modifier.isStatic(m.modifiers) &&
            owner.fields.count { !Modifier.isStatic(it.modifiers) && it.typeName == SHARE_TYPE } == 1 &&
            findMethod { matcher { declaredClass(owner.name); usingNumbers(9_000_000); addInvoke(m.descriptor) } }.isNotEmpty()
    }
}
