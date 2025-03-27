package com.example.webviewtest.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.util.UnstableApi
import com.example.webviewtest.videoeditor.VideoEditListener
import com.example.webviewtest.videoeditor.VideoEditResult
import com.example.webviewtest.videoeditor.VideoEditState
import com.example.webviewtest.videoeditor.VideoEditorUtils
import com.example.webviewtest.videoeditor.VideoOperation
import com.example.webviewtest.videoeditor.VideoOperationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.example.webviewtest.videoeditor.*
import androidx.lifecycle.*

/**
 * A ViewModel that wraps VideoEditorUtils so that all editing operations
 * are triggered here, not directly in the Activity.
 */

@UnstableApi
class VideoEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val videoEditor = VideoEditorUtils(context = getApplication())
    private val operationManager = VideoOperationManager(getApplication())

    // State management
    private val _operations = ConcurrentHashMap<String, MutableStateFlow<OperationState>>()
    val operations: Map<String, StateFlow<OperationState>> = _operations

    data class OperationState(
        val progress: Float = 0f,
        val status: VideoEditState = VideoEditState.IDLE,
        val error: String? = null,
        val outputUri: Uri? = null,
        val operation: VideoOperation? = null,
        val priority: VideoOperationManager.OperationPriority = VideoOperationManager.OperationPriority.NORMAL,
        val memoryUsage: Long = 0,
        val isPaused: Boolean = false,
        val estimatedTimeRemaining: Long? = null
    )

    fun mergeVideos(
        videoUris: List<Uri>,
        outputUri: Uri,
        priority: VideoOperationManager.OperationPriority = VideoOperationManager.OperationPriority.NORMAL
    ): String {
        val operationId = UUID.randomUUID().toString()

        val request = VideoOperationManager.OperationRequest(
            operationId = operationId,
            operation = {
                videoEditor.mergeVideos(
                    videoUriList = videoUris,
                    outputUri = outputUri,
                    listener = createListener(operationId)
                )
            },
            priority = priority
        )

        operationManager.enqueueOperation(request)
        initializeOperation(operationId, priority)
        return operationId
    }

    fun trimVideo(
        inputUri: Uri,
        startMs: Long,
        endMs: Long,
        outputUri: Uri,
        priority: VideoOperationManager.OperationPriority = VideoOperationManager.OperationPriority.NORMAL
    ): String {
        val operationId = java.util.UUID.randomUUID().toString()

        val request = VideoOperationManager.OperationRequest(
            operationId = operationId,
            operation = {
                videoEditor.trimVideo(
                    inputUri = inputUri,
                    startMs = startMs,
                    endMs = endMs,
                    outputUri = outputUri,
                    listener = createListener(operationId)
                )
            },
            priority = priority
        )

        operationManager.enqueueOperation(request)
        initializeOperation(operationId, priority)
        return operationId
    }

    fun rotateVideo(
        inputUri: Uri,
        rotationDegrees: Float,
        outputUri: Uri,
        priority: VideoOperationManager.OperationPriority = VideoOperationManager.OperationPriority.NORMAL
    ): String {
        val operationId = java.util.UUID.randomUUID().toString()

        val request = VideoOperationManager.OperationRequest(
            operationId = operationId,
            operation = {
                videoEditor.rotateVideo(
                    inputUri = inputUri,
                    rotationDegrees = rotationDegrees,
                    outputUri = outputUri,
                    listener = createListener(operationId)
                )
            },
            priority = priority
        )

        operationManager.enqueueOperation(request)
        initializeOperation(operationId, priority)
        return operationId
    }

    fun trimAndRotateVideo(
        inputUri: Uri,
        startMs: Long,
        endMs: Long,
        rotationDegrees: Float,
        outputUri: Uri,
        priority: VideoOperationManager.OperationPriority = VideoOperationManager.OperationPriority.NORMAL
    ): String {
        val operationId = java.util.UUID.randomUUID().toString()

        val request = VideoOperationManager.OperationRequest(
            operationId = operationId,
            operation = {
                videoEditor.trimAndRotateVideo(
                    inputUri = inputUri,
                    startMs = startMs,
                    endMs = endMs,
                    rotationDegrees = rotationDegrees,
                    outputUri = outputUri,
                    listener = createListener(operationId)
                )
            },
            priority = priority
        )

        operationManager.enqueueOperation(request)
        initializeOperation(operationId, priority)
        return operationId
    }

    private fun initializeOperation(
        operationId: String,
        priority: VideoOperationManager.OperationPriority
    ) {
        _operations[operationId] = MutableStateFlow(
            OperationState(priority = priority)
        )
    }

    private fun createListener(operationId: String) = object : VideoEditListener {
        private val startTime = System.currentTimeMillis()

        override fun onVideoEditSuccess(outputUri: Uri) {
            updateOperationState(operationId) { currentState ->
                currentState.copy(
                    status = VideoEditState.COMPLETED,
                    outputUri = outputUri,
                    progress = 1f
                )
            }
        }

        override fun onVideoEditFailure(error: VideoEditResult.Error) {
            updateOperationState(operationId) { currentState ->
                currentState.copy(
                    status = VideoEditState.ERROR,
                    error = error.throwable.message ?: "Unknown error"
                )
            }
        }

        override fun onVideoEditProgress(progress: VideoEditResult.Progress) {
            val elapsedTime = System.currentTimeMillis() - startTime
            val estimatedTotal = if (progress.progress > 0) {
                (elapsedTime / progress.progress).toLong()
            } else null
            val remaining = estimatedTotal?.let { it - elapsedTime }

            updateOperationState(operationId) { currentState ->
                currentState.copy(
                    progress = progress.progress,
                    status = progress.stage,
                    operation = progress.operation,
                    memoryUsage = progress.memoryUsage,
                    estimatedTimeRemaining = remaining
                )
            }
        }
    }

    fun cancelOperation(operationId: String) {
        operationManager.cancelOperation(operationId)
        _operations.remove(operationId)
    }

    fun pauseOperation(operationId: String) {
        operationManager.pauseOperation(operationId)
        updateOperationState(operationId) { it.copy(isPaused = true) }
    }

    fun resumeOperation(operationId: String) {
        operationManager.resumeOperation(operationId)
        updateOperationState(operationId) { it.copy(isPaused = false) }
    }

    private fun updateOperationState(
        operationId: String,
        update: (OperationState) -> OperationState
    ) {
        _operations[operationId]?.value = update(_operations[operationId]?.value
            ?: OperationState())
    }

    override fun onCleared() {
        super.onCleared()
        videoEditor.release()
        operationManager.release()
        _operations.clear()
    }

    // Helper method to get remaining time as formatted string
    fun getFormattedTimeRemaining(operationId: String): String? {
        val remaining = _operations[operationId]?.value?.estimatedTimeRemaining ?: return null
        val minutes = remaining / 60000
        val seconds = (remaining % 60000) / 1000
        return String.format("%02d:%02d", minutes, seconds)
    }
}