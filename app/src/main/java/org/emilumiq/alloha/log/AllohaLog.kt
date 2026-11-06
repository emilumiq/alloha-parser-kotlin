package org.emilumiq.alloha.log

import android.util.Log
import org.emilumiq.alloha.BuildConfig

object AllohaLog {
    fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG || Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "[$tag] ${message()}")
        }
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(TAG, "[$tag] $message", error)
    }

    private const val TAG = "Alloha"
}
