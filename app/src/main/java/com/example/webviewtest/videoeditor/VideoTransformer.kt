package com.example.webviewtest.videoeditor

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Handles direct Media3 Transformer creation and lifecycle.
 * You can keep your `createTransformerWithProgress(...)` method here.
 */

@UnstableApi
object VideoTransformer {

    private const val TAG = "VideoTransformer"

    /**
     * Create a Transformer with appropriate listeners, hooking your
     * own onCompleted or onError logic.
     */
    fun createTransformerWithProgress(
        context: Context,
        outputFile: File,
        operation: VideoOperation,
        listener: VideoEditListener,
        onCompleteCallback: () -> Unit,
        onErrorCallback: () -> Unit,
        coroutineScope: CoroutineScope,
        progressHolder: ProgressHolder
    ): Transformer {
        return Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    Log.d(TAG, "Operation completed: $operation")
                    listener.onVideoEditSuccess(android.net.Uri.fromFile(outputFile))
                    onCompleteCallback()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exception: ExportException
                ) {
                    Log.e(TAG, "Operation failed: $operation", exception)
                    listener.onVideoEditFailure(
                        VideoEditResult.Error(
                            throwable = exception,
                            errorCode = mapExceptionToErrorCode(exception),
                            isRecoverable = isRecoverableError(exception),
                            operation = operation
                        )
                    )
                    onErrorCallback()
                }
            })
            .build()
            .also { transformer ->
                // Potentially start a coroutine to poll for progress
                coroutineScope.launch {
                    while (true) {
                        val progress = transformer.getProgress(progressHolder)
                        if (progress != Transformer.PROGRESS_STATE_NOT_STARTED &&
                            progress != Transformer.PROGRESS_STATE_UNAVAILABLE
                        ) {
                            val normalizedProgress = progressHolder.progress / 100f
                            listener.onVideoEditProgress(
                                VideoEditResult.Progress(
                                    progress = normalizedProgress,
                                    stage = VideoEditState.PROCESSING,
                                    memoryUsage = getCurrentMemoryUsage(),
                                    operation = operation
                                )
                            )
                        }
                        // add delay etc.
                    }
                }
            }
    }

    // Keep memory usage, mapExceptionToErrorCode, isRecoverableError, etc. here if you like
    private fun getCurrentMemoryUsage(): Long {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    }

    private fun mapExceptionToErrorCode(exception: Throwable): ErrorCode {
        // same logic as before
        return when {
            exception.message?.contains("storage") == true -> ErrorCode.INSUFFICIENT_STORAGE
                exception.message?.contains("memory") == true -> ErrorCode.INSUFFICIENT_MEMORY
                    exception is IllegalArgumentException -> ErrorCode.INVALID_INPUT
            exception is ExportException &&
                    exception.errorCode == ExportException.ERROR_CODE_ENCODING_FAILED -> ErrorCode.CODEC_ERROR
            exception is CancellationException -> ErrorCode.OPERATION_CANCELLED
            else -> ErrorCode.UNKNOWN
        }
    }

    private fun isRecoverableError(error: Throwable): Boolean {
        // same logic as before
        return when (mapExceptionToErrorCode(error)) {
            ErrorCode.INSUFFICIENT_STORAGE,
            ErrorCode.INSUFFICIENT_MEMORY,
            ErrorCode.OPERATION_CANCELLED -> true
            else -> false
        }
    }
}
