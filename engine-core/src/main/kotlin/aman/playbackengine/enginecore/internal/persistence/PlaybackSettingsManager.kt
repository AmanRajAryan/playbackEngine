package aman.playbackengine.enginecore.internal.persistence

import android.content.Context
import android.content.SharedPreferences
import aman.playbackengine.enginecore.RepeatMode

internal object PlaybackSettingsManager {
    private const val PREFS_NAME = "playback_engine_settings"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_SHUFFLE_MODE = "shuffle_mode"
    private const val KEY_PLAYBACK_SPEED = "playback_speed"
    private const val KEY_PLAYBACK_PITCH = "playback_pitch"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getRepeatMode(): RepeatMode {
        val name = prefs?.getString(KEY_REPEAT_MODE, RepeatMode.OFF.name) ?: RepeatMode.OFF.name
        return try {
            RepeatMode.valueOf(name)
        } catch (e: Exception) {
            RepeatMode.OFF
        }
    }

    fun saveRepeatMode(mode: RepeatMode) {
        prefs?.edit()?.putString(KEY_REPEAT_MODE, mode.name)?.apply()
    }

    fun getShuffleMode(): Boolean {
        return prefs?.getBoolean(KEY_SHUFFLE_MODE, false) ?: false
    }

    fun saveShuffleMode(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SHUFFLE_MODE, enabled)?.apply()
    }

    fun getSpeed(): Float {
        return prefs?.getFloat(KEY_PLAYBACK_SPEED, 1.0f) ?: 1.0f
    }

    fun saveSpeed(speed: Float) {
        prefs?.edit()?.putFloat(KEY_PLAYBACK_SPEED, speed)?.apply()
    }

    fun getPitch(): Float {
        return prefs?.getFloat(KEY_PLAYBACK_PITCH, 1.0f) ?: 1.0f
    }

    fun savePitch(pitch: Float) {
        prefs?.edit()?.putFloat(KEY_PLAYBACK_PITCH, pitch)?.apply()
    }
}
