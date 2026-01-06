package io.github.manum45.openaa

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers


/// https://stackoverflow.com/a/59511458
class LogcatViewModel : ViewModel() {
    fun logCatOutput() = liveData(viewModelScope.coroutineContext + Dispatchers.IO) {
        Runtime.getRuntime().exec("logcat -c")
        Runtime.getRuntime().exec("logcat")
            .inputStream
            .bufferedReader()
            .useLines { lines -> lines.forEach { line -> emit(line) }
            }
    }
}