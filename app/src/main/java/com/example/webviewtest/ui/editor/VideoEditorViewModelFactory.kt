package com.example.webviewtest.ui.editor

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi

@UnstableApi
class VideoEditorViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VideoEditorViewModel::class.java)) {
            return VideoEditorViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
