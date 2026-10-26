package io.github.audiosplit.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * All non-SDK dependencies are confined here. Any mismatch is a compatibility failure.
 * RENDER registration may reuse an existing HAL output for both devices. A successful
 * registration therefore does not establish independent per-app routing; the service
 * separately monitors the actual playback devices and restores a persistent mismatch.
 */
internal class Android16AudioPolicy(private val context: Context, private val manager: AudioManager) {
    private val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
    private val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
    private val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
    private val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
    private val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
    private val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
    private val registerMethod = AudioManager::class.java.getMethod("registerAudioPolicy", policyClass)
    private val unregisterMethod = AudioManager::class.java.getMethod("unregisterAudioPolicy", policyClass)
    private val attachMethod = policyClass.getMethod("attachMixes", List::class.java)
    private val successCode = AudioManager::class.java.getField("SUCCESS").getInt(null)

    init {
        check(Build.VERSION.SDK_INT == 36) { "Only Android API 36 is supported by this experiment." }
        // Resolve every signature before anything can alter routing.
        ruleBuilderClass.getConstructor()
        ruleBuilderClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
        ruleBuilderClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
        ruleBuilderClass.getMethod("build")
        mixBuilderClass.getConstructor(ruleClass)
        mixBuilderClass.getMethod("setDevice", AudioDeviceInfo::class.java)
        mixBuilderClass.getMethod("setFormat", AudioFormat::class.java)
        mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
        mixBuilderClass.getMethod("build")
        policyBuilderClass.getConstructor(Context::class.java)
        policyBuilderClass.getMethod("addMix", mixClass)
        policyBuilderClass.getMethod("build")
    }

    fun prepare(firstUid: Int, bluetooth: AudioDeviceInfo, secondUid: Int, speaker: AudioDeviceInfo): Handle {
        val first = createMix(firstUid, bluetooth)
        val second = createMix(secondUid, speaker)
        val builder = policyBuilderClass.getConstructor(Context::class.java).newInstance(context)
        call(policyBuilderClass.getMethod("addMix", mixClass), builder, first)
        val policy = call(policyBuilderClass.getMethod("build"), builder)
            ?: error("AudioPolicy.Builder returned null")
        return Handle(policy, second)
    }

    private fun createMix(uid: Int, device: AudioDeviceInfo): Any {
        val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val matchUsage = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
        val matchUid = ruleClass.getField("RULE_MATCH_UID").getInt(null)
        call(ruleBuilderClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType),
            ruleBuilder, attributes, matchUsage)
        call(ruleBuilderClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java),
            ruleBuilder, matchUid, uid)
        val rule = call(ruleBuilderClass.getMethod("build"), ruleBuilder)
            ?: error("AudioMixingRule.Builder returned null")
        val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule)
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        call(mixBuilderClass.getMethod("setFormat", AudioFormat::class.java), mixBuilder, format)
        call(mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType), mixBuilder,
            mixClass.getField("ROUTE_FLAG_RENDER").getInt(null))
        call(mixBuilderClass.getMethod("setDevice", AudioDeviceInfo::class.java), mixBuilder, device)
        return call(mixBuilderClass.getMethod("build"), mixBuilder)
            ?: error("AudioMix.Builder returned null")
    }

    inner class Handle(private val policy: Any, private val secondMix: Any) {
        private var registrationAttempted = false

        fun registerFirst() {
            registrationAttempted = true
            val result = call(registerMethod, manager, policy) as Int
            check(result == successCode) { "registerAudioPolicy returned $result" }
        }

        fun attachSecond() {
            val result = call(attachMethod, policy, listOf(secondMix)) as Int
            check(result == successCode) { "attachMixes returned $result for the phone speaker" }
        }

        /** Synchronous removal of the complete owned policy, including partially attached mixes. */
        fun unregister() {
            if (!registrationAttempted) return
            call(unregisterMethod, manager, policy)
            registrationAttempted = false
        }
    }

    private fun call(method: Method, receiver: Any, vararg arguments: Any?): Any? = try {
        method.invoke(receiver, *arguments)
    } catch (error: InvocationTargetException) {
        val cause = error.targetException
        if (cause is Exception || cause is LinkageError) throw cause
        throw error
    }
}
