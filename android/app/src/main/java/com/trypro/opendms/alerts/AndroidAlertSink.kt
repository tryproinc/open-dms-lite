package com.trypro.opendms.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 音・振動・画面での警告発報を実装する。11_DAY_SMARTPHONE_MVP_PLAN.md 3.6/ALT-002/ALT-005〜008に対応。
 *
 * - 画面表示は[activeAlert]をComposeで観測し、[DISPLAY_DURATION_MS]後に自動で消える(操作不要、ALT-005)。
 * - 音はAudioTrackで正弦波を実行時合成する([ToneSynthesizer]。3.6の「同梱音源」の暫定代替、D0-008_notes.md参照)。
 * - 振動は種別ごとにパターンを変え、音と合わせて4種を区別できるようにする(ALT-002/ALT-006)。
 * - 再生前にtransient Audio Focusを要求し、再生後に解放する(ALT-008)。
 */
class AndroidAlertSink(context: Context) : AlertSink {

    private val appContext = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService()
    private val vibratorManager: VibratorManager? = appContext.getSystemService()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeAlert = MutableStateFlow<AlertKind?>(null)
    val activeAlert: StateFlow<AlertKind?> = _activeAlert.asStateFlow()

    /**
     * メディア音量が最大に対して占める割合(0.0〜1.0)。取得できない場合は1.0を返す
     * (取得不能を理由に運行開始を妨げない=安全側)。
     *
     * 11_DAY_SMARTPHONE_MVP_PLAN.md 3.6:「起動前にメディア音量が小さい場合は開始を止め、
     * 試聴ボタンを表示する」。音が鳴らなければ警告は運転者に届かないため、
     * PREFLIGHTで確認する必要がある。
     */
    fun mediaVolumeRatio(): Float {
        val am = audioManager ?: return 1f
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 1f
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    override fun trigger(kind: AlertKind) {
        val triggeredAtMs = System.currentTimeMillis()
        _activeAlert.value = kind
        vibrate(kind)
        scope.launch { playTone(kind, triggeredAtMs) }
        scope.launch {
            delay(DISPLAY_DURATION_MS)
            // 別の警告が既に上書きしている場合は消さない(最新の表示を優先する)。
            if (_activeAlert.value == kind) _activeAlert.value = null
        }
    }

    private fun vibrate(kind: AlertKind) {
        val manager = vibratorManager ?: return
        val pattern = when (kind) {
            AlertKind.EYE_CLOSURE_CAUTION -> longArrayOf(0, 200)
            AlertKind.EYE_CLOSURE_WARNING -> longArrayOf(0, 250, 150, 250)
            AlertKind.DISTRACTION_WARNING -> longArrayOf(0, 150, 100, 150)
            AlertKind.DROWSY_NODDING -> longArrayOf(0, 120, 80, 120, 80, 120)
            // あくびは最も弱い通知(ASV眠気2.4.2)。他のどれよりも短い単発にする。
            AlertKind.DROWSY_YAWNING -> longArrayOf(0, 90)
            AlertKind.UNAVAILABLE -> longArrayOf(0, 400)
            AlertKind.THERMAL -> longArrayOf(0, 250)
            // 故障は監視不能と同じ振動・音を使い、画面表示で区別する(AlertKind.SYSTEM_FAULT参照)。
            AlertKind.SYSTEM_FAULT -> longArrayOf(0, 400)
            AlertKind.CALIBRATION_CUE -> longArrayOf(0, 60)
        }
        val effect = VibrationEffect.createWaveform(pattern, -1)
        manager.vibrate(CombinedVibration.createParallel(effect))
    }

    private suspend fun playTone(kind: AlertKind, triggeredAtMs: Long) {
        val pattern = when (kind) {
            AlertKind.EYE_CLOSURE_CAUTION -> AlertTonePatterns.EYE_CLOSURE_CAUTION
            AlertKind.EYE_CLOSURE_WARNING -> AlertTonePatterns.EYE_CLOSURE_WARNING
            AlertKind.DISTRACTION_WARNING -> AlertTonePatterns.DISTRACTION_WARNING
            AlertKind.DROWSY_NODDING -> AlertTonePatterns.DROWSY_NODDING
            AlertKind.DROWSY_YAWNING -> AlertTonePatterns.DROWSY_YAWNING
            AlertKind.UNAVAILABLE -> AlertTonePatterns.UNAVAILABLE
            AlertKind.THERMAL -> AlertTonePatterns.THERMAL
            AlertKind.SYSTEM_FAULT -> AlertTonePatterns.UNAVAILABLE
            AlertKind.CALIBRATION_CUE -> AlertTonePatterns.CALIBRATION_CUE
        }
        val pcm = ToneSynthesizer.synthesize(pattern)

        val focusRequest = requestAudioFocus()
        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(ToneSynthesizer.SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Log.d(TAG, "alert=$kind latencyMs=${System.currentTimeMillis() - triggeredAtMs}")
            delay((pattern.totalDurationMs + PLAYBACK_TAIL_MS).toLong())
        } catch (e: Exception) {
            Log.e(TAG, "tone playback failed for $kind", e)
        } finally {
            track?.stop()
            track?.release()
            abandonAudioFocus(focusRequest)
        }
    }

    private fun requestAudioFocus(): AudioFocusRequest? {
        val manager = audioManager ?: return null
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttributes)
            .build()
        manager.requestAudioFocus(request)
        return request
    }

    private fun abandonAudioFocus(request: AudioFocusRequest?) {
        if (request != null) audioManager?.abandonAudioFocusRequest(request)
    }

    /** Composeの破棄時等に呼び、進行中の再生用コルーチンを止める。 */
    fun release() {
        scope.cancel()
    }

    private companion object {
        const val TAG = "AndroidAlertSink"
        const val DISPLAY_DURATION_MS = 3_500L
        const val PLAYBACK_TAIL_MS = 80
    }
}
