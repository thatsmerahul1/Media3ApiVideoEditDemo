package com.example.webviewtest.videoeditor

import android.net.Uri

/** The different states */
enum class VideoEditState {
    IDLE,
    PREPARING,
    PROCESSING,
    SAVING,
    COMPLETED,
    ERROR
}

/** Possible operations */
sealed class VideoOperation {
    data class Trim(val startMs: Long, val endMs: Long): VideoOperation()
    data class Rotate(val degrees: Float): VideoOperation()
    data class Merge(val videoCount: Int): VideoOperation()
    data class TrimAndRotate(val startMs: Long, val endMs: Long, val degrees: Float): VideoOperation()
}

/** Error codes to help interpret failures */
enum class ErrorCode {
    INSUFFICIENT_STORAGE,
    INSUFFICIENT_MEMORY,
    INVALID_INPUT,
    CODEC_ERROR,
    OPERATION_CANCELLED,
    UNKNOWN
}

/** Result model to pass to onError(...) or onProgress(...) */
sealed class VideoEditResult {
    data class Success(val outputUri: Uri) : VideoEditResult()
    data class Progress(
        val progress: Float,
        val stage: VideoEditState,
        val memoryUsage: Long,
        val operation: VideoOperation?
    ) : VideoEditResult()
    data class Error(
        val throwable: Throwable,
        val errorCode: ErrorCode,
        val isRecoverable: Boolean,
        val operation: VideoOperation?
    ) : VideoEditResult()
}

/** The interface for callbacks */
interface VideoEditListener {
    fun onVideoEditSuccess(outputUri: Uri)
    fun onVideoEditFailure(error: VideoEditResult.Error)
    fun onVideoEditProgress(progress: VideoEditResult.Progress)
}
