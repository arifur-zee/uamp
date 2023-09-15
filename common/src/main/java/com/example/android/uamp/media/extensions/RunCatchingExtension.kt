package com.example.android.uamp.media.extensions

import android.util.Log

fun runAndLogFailure(tag: String, run: () -> Unit){
    runCatching { run.invoke() }
        .onFailure {
            Log.e(tag, "error: ${it.stackTrace.contentToString()}")
        }
}