package aman.playbackengine.enginecore

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Custom Audio Focus Manager for the Playback Library.
 * Handles focus loss bypass and smooth volume transitions.
 */
class LibraryAudioFocusManager(
    context: Context,
    private val callback: Callback
) {
    private val TAG = "PB_FOCUS"
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    private var focusRequest: AudioFocusRequest? = null
    private var currentFocusState = AudioManager.AUDIOFOCUS_NONE
    private var isTransientLoss = false
    
    /**
     * If true, focus loss events from the system will be ignored.
     */
    var ignoreAudioFocusLoss: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            Log.d(TAG, "Focus Interruption Policy: Ignore=$value")
            
            if (value) {
                // When ignoring focus, stop participating in the OS focus stack 
                // to prevent automatic system ducking.
                abandonFocus()
            }
        }

    interface Callback {
        fun onFocusRequestPause()
        fun onFocusRequestResume()
        fun onFocusRequestDucking(duck: Boolean)
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (ignoreAudioFocusLoss) {
            Log.d(TAG, "Focus change ignored (Policy: Ignore ON): $focusChange")
            return@OnAudioFocusChangeListener
        }

        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Focus Gained")
                currentFocusState = AudioManager.AUDIOFOCUS_GAIN
                isTransientLoss = false
                callback.onFocusRequestDucking(false)
                callback.onFocusRequestResume()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Focus Loss (Permanent)")
                currentFocusState = AudioManager.AUDIOFOCUS_LOSS
                isTransientLoss = false
                callback.onFocusRequestPause()
                abandonFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Focus Loss (Transient)")
                currentFocusState = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                isTransientLoss = true
                callback.onFocusRequestPause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Focus Loss (Can Duck)")
                currentFocusState = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                callback.onFocusRequestDucking(true)
            }
        }
    }

    /**
     * Requests audio focus from the system.
     * Returns true if granted.
     */
    fun requestFocus(): Boolean {
        if (ignoreAudioFocusLoss) return true
        
        // Prevent redundant requests if we already own the focus
        if (currentFocusState == AudioManager.AUDIOFOCUS_GAIN) {
            return true
        }

        isTransientLoss = false
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            
            focusRequest?.let { audioManager.requestAudioFocus(it) } ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            currentFocusState = AudioManager.AUDIOFOCUS_GAIN
        }
        return granted
    }

    /**
     * Abandons audio focus.
     */
    fun abandonFocus() {
        isTransientLoss = false
        currentFocusState = AudioManager.AUDIOFOCUS_NONE
        val request = focusRequest
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
            audioManager.abandonAudioFocusRequest(request)
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    /**
     * Called when the engine's playback state changes.
     * Automatically handles focus requests and abandonment.
     */
    fun onPlayerStateChanged(isPlaying: Boolean) {
        if (isPlaying) {
            // SYMMETRIC REQUEST: If player started (e.g. from Notification) without focus
            if (currentFocusState != AudioManager.AUDIOFOCUS_GAIN) {
                Log.d(TAG, "External play detected. Requesting focus.")
                val granted = requestFocus()
                if (!granted) {
                    Log.d(TAG, "Focus denied for external play. Forcing pause.")
                    callback.onFocusRequestPause()
                }
            }
        } else {
            // EXISTING ABANDON: If paused manually (not by a phone call)
            if (!isTransientLoss) {
                Log.d(TAG, "Manual pause detected. Abandoning focus.")
                abandonFocus()
            }
        }
    }
}
