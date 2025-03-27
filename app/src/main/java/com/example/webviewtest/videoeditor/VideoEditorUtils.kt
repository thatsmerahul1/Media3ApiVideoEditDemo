package com.example.webviewtest.videoeditor

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Utility class for video editing operations using Media3 Transformer:
 * - Trim
 * - Rotate
 * - Merge
 * - Trim + Rotate
 */

@UnstableApi
class VideoEditorUtils(private val context: Context) {
    private val tag = "VideoEditorUtils"
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val maxMemoryUsage = Runtime.getRuntime().maxMemory() * 0.75 // 75% of max memory

    // Track multiple operations
    private val activeOperations = ConcurrentHashMap<String, OperationState>()

    data class OperationState(
        val state: AtomicReference<VideoEditState>,
        val operation: VideoOperation,
        val progressHolder: ProgressHolder,
        var memoryWatcher: Job? = null,
        var transformer: Transformer? = null,
        val tempFile: File
    )

    // ------------------------------------------------------------------------
    // PUBLIC API METHODS
    // ------------------------------------------------------------------------

    fun trimVideo(
        inputUri: Uri,
        startMs: Long,
        endMs: Long,
        outputUri: Uri,
        listener: VideoEditListener
    ): String {
        if (startMs >= endMs) {
            listener.onVideoEditFailure(
                VideoEditResult.Error(
                    throwable = IllegalArgumentException("End time must be greater than start time"),
                    errorCode = ErrorCode.INVALID_INPUT,
                    isRecoverable = false,
                    operation = null
                )
            )
            return ""
        }

        val operationId = UUID.randomUUID().toString()
        val operation = VideoOperation.Trim(startMs, endMs)

        initializeOperation(operationId, operation) { state ->
            coroutineScope.launch {
                try {
                    updateState(operationId, VideoEditState.PROCESSING)

                    val inputMediaItem = MediaItem.Builder()
                        .setUri(inputUri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(startMs)
                                .setEndPositionMs(endMs)
                                .build()
                        )
                        .build()

                    val editedMediaItem = EditedMediaItem.Builder(inputMediaItem).build()
                    val composition = Composition.Builder(EditedMediaItemSequence(editedMediaItem)).build()

                    val transformer = createTransformerWithProgress(
                        operationId = operationId,
                        outputFile = state.tempFile,
                        listener = wrapListener(operationId, listener, outputUri)
                    )

                    // Store transformer in state
                    activeOperations[operationId]?.transformer = transformer
                    transformer.start(composition, state.tempFile.absolutePath)

                    startMemoryWatcher(operationId)
                } catch (e: Exception) {
                    handleError(operationId, e, listener)
                }
            }
        }

        return operationId
    }

    fun rotateVideo(
        inputUri: Uri,
        rotationDegrees: Float,
        outputUri: Uri,
        listener: VideoEditListener
    ): String {
        val operationId = UUID.randomUUID().toString()
        val operation = VideoOperation.Rotate(rotationDegrees)

        initializeOperation(operationId, operation) { state ->
            coroutineScope.launch {
                try {
                    updateState(operationId, VideoEditState.PROCESSING)

                    val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
                        .setEffects(
                            Effects(
                                listOf(),
                                listOf(
                                    ScaleAndRotateTransformation.Builder()
                                        .setRotationDegrees(rotationDegrees)
                                        .build()
                                )
                            )
                        )
                        .build()

                    val composition = Composition.Builder(EditedMediaItemSequence(editedMediaItem)).build()

                    val transformer = createTransformerWithProgress(
                        operationId = operationId,
                        outputFile = state.tempFile,
                        listener = wrapListener(operationId, listener, outputUri)
                    )

                    activeOperations[operationId]?.transformer = transformer
                    transformer.start(composition, state.tempFile.absolutePath)

                    startMemoryWatcher(operationId)
                } catch (e: Exception) {
                    handleError(operationId, e, listener)
                }
            }
        }

        return operationId
    }

    fun mergeVideos(
        videoUriList: List<Uri>,
        outputUri: Uri,
        audioMute: Boolean = false,
        listener: VideoEditListener
    ): String {
        if (videoUriList.size < 2) {
            listener.onVideoEditFailure(
                VideoEditResult.Error(
                    throwable = IllegalArgumentException("At least two videos required for merging"),
                    errorCode = ErrorCode.INVALID_INPUT,
                    isRecoverable = false,
                    operation = null
                )
            )
            return ""
        }

        val operationId = UUID.randomUUID().toString()
        val operation = VideoOperation.Merge(videoUriList.size)

        initializeOperation(operationId, operation) { state ->
            coroutineScope.launch {
                try {
                    updateState(operationId, VideoEditState.PROCESSING)

                    val videoList = videoUriList.map { uri ->
                        EditedMediaItem.Builder(MediaItem.fromUri(uri))
                            .setRemoveAudio(audioMute)
                            .build()
                    }
                    val videoSequence = EditedMediaItemSequence(videoList)
                    val composition = Composition.Builder(videoSequence).build()

                    val transformer = createTransformerWithProgress(
                        operationId = operationId,
                        outputFile = state.tempFile,
                        listener = wrapListener(operationId, listener, outputUri)
                    )

                    activeOperations[operationId]?.transformer = transformer
                    transformer.start(composition, state.tempFile.absolutePath)

                    startMemoryWatcher(operationId)
                } catch (e: Exception) {
                    handleError(operationId, e, listener)
                }
            }
        }

        return operationId
    }

    fun trimAndRotateVideo(
        inputUri: Uri,
        startMs: Long,
        endMs: Long,
        rotationDegrees: Float,
        outputUri: Uri,
        listener: VideoEditListener
    ): String {
        if (startMs >= endMs) {
            listener.onVideoEditFailure(
                VideoEditResult.Error(
                    throwable = IllegalArgumentException("End time must be greater than start time"),
                    errorCode = ErrorCode.INVALID_INPUT,
                    isRecoverable = false,
                    operation = null
                )
            )
            return ""
        }

        val operationId = UUID.randomUUID().toString()
        val operation = VideoOperation.TrimAndRotate(startMs, endMs, rotationDegrees)

        initializeOperation(operationId, operation) { state ->
            coroutineScope.launch {
                try {
                    updateState(operationId, VideoEditState.PROCESSING)

                    // Build trimmed + rotated MediaItem
                    val inputMediaItem = MediaItem.Builder()
                        .setUri(inputUri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(startMs)
                                .setEndPositionMs(endMs)
                                .build()
                        )
                        .build()

                    val editedMediaItem = EditedMediaItem.Builder(inputMediaItem)
                        .setEffects(
                            Effects(
                                listOf(),
                                listOf(
                                    ScaleAndRotateTransformation.Builder()
                                        .setRotationDegrees(rotationDegrees)
                                        .build()
                                )
                            )
                        )
                        .build()

                    val composition = Composition.Builder(EditedMediaItemSequence(editedMediaItem)).build()

                    val transformer = createTransformerWithProgress(
                        operationId = operationId,
                        outputFile = state.tempFile,
                        listener = wrapListener(operationId, listener, outputUri)
                    )

                    activeOperations[operationId]?.transformer = transformer
                    transformer.start(composition, state.tempFile.absolutePath)

                    startMemoryWatcher(operationId)
                } catch (e: Exception) {
                    handleError(operationId, e, listener)
                }
            }
        }

        return operationId
    }

    // ------------------------------------------------------------------------
    // HELPER METHODS
    // ------------------------------------------------------------------------

    private fun initializeOperation(
        operationId: String,
        operation: VideoOperation,
        block: (OperationState) -> Unit
    ) {
        val tempFile = createTempFile(getOperationPrefix(operation))
        val state = OperationState(
            state = AtomicReference(VideoEditState.PREPARING),
            operation = operation,
            progressHolder = ProgressHolder(),
            tempFile = tempFile
        )

        activeOperations[operationId] = state
        block(state)
    }

    private fun createTransformerWithProgress(
        operationId: String,
        outputFile: File,
        listener: VideoEditListener
    ): Transformer {
        return Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        Log.d(tag, "Operation completed: $operationId")
                        handleCompletion(operationId, outputFile, listener)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exception: ExportException
                    ) {
                        Log.e(tag, "Operation failed: $operationId", exception)
                        handleError(operationId, exception, listener)
                    }
                }
            )
            .build()
            .also { transformer ->
                startProgressTracking(operationId, transformer, listener)
            }
    }

    private fun handleCompletion(
        operationId: String,
        tempFile: File,
        listener: VideoEditListener
    ) {
        updateState(operationId, VideoEditState.COMPLETED)
        stopProgressTracking(operationId)

        coroutineScope.launch {
            try {
                updateState(operationId, VideoEditState.SAVING)

                // Generate output URI
                val outputUri = generateOutputUri(context, "output_${System.currentTimeMillis()}.mp4")
                    ?: throw IOException("Failed to create output URI")

                // Pass both tempFile and outputUri to handleFileCleanup
                val finalUri = handleFileCleanup(tempFile, outputUri)
                listener.onVideoEditSuccess(finalUri)
            } catch (e: Exception) {
                handleError(operationId, e, listener)
            } finally {
                cleanupOperation(operationId)
            }
        }
    }

    private fun generateOutputUri(context: Context, fileName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            try {
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            } catch (e: Exception) {
                Log.e("MainActivity2", "Error creating media store entry", e)
                null
            }
        } else {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val outputFile = File(downloadsDir, fileName)
                Uri.fromFile(outputFile)
            } catch (e: Exception) {
                Log.e("MainActivity2", "Error creating file", e)
                null
            }
        }
    }

    private fun wrapListener(
        operationId: String,
        originalListener: VideoEditListener,
        outputUri: Uri
    ): VideoEditListener {
        return object : VideoEditListener {
            override fun onVideoEditSuccess(uri: Uri) {
                cleanupOperation(operationId)
                originalListener.onVideoEditSuccess(outputUri)
            }

            override fun onVideoEditFailure(error: VideoEditResult.Error) {
                cleanupOperation(operationId)
                originalListener.onVideoEditFailure(error)
            }

            override fun onVideoEditProgress(progress: VideoEditResult.Progress) {
                originalListener.onVideoEditProgress(progress)
            }
        }
    }

    private fun startProgressTracking(
        operationId: String,
        transformer: Transformer,
        listener: VideoEditListener
    ) {
        coroutineScope.launch {
            while (isActive && activeOperations.containsKey(operationId)) {
                val state = activeOperations[operationId] ?: break
                val progress = transformer.getProgress(state.progressHolder)

                if (progress != Transformer.PROGRESS_STATE_NOT_STARTED &&
                    progress != Transformer.PROGRESS_STATE_UNAVAILABLE
                ) {
                    val normalized = state.progressHolder.progress / 100f
                    listener.onVideoEditProgress(
                        VideoEditResult.Progress(
                            progress = normalized,
                            stage = state.state.get(),
                            memoryUsage = getUsedMemory(),
                            operation = state.operation
                        )
                    )
                }
                delay(300)
            }
        }
    }

    private fun startMemoryWatcher(operationId: String) {
        val job = coroutineScope.launch {
            while (isActive && activeOperations.containsKey(operationId)) {
                val usedMemory = getUsedMemory()
                if (usedMemory > maxMemoryUsage) {
                    Log.w(tag, "High memory usage detected: $usedMemory")
                    System.gc()
                    delay(1000)
                    if (getUsedMemory() > maxMemoryUsage) {
                        throw OutOfMemoryError("Memory usage exceeded safe threshold")
                    }
                }
                delay(1000)
            }
        }
        activeOperations[operationId]?.memoryWatcher = job
    }

    private fun stopProgressTracking(operationId: String) {
        activeOperations[operationId]?.apply {
            memoryWatcher?.cancel()
            transformer?.cancel()
        }
    }

    private fun cleanupOperation(operationId: String) {
        activeOperations[operationId]?.let { state ->
            state.memoryWatcher?.cancel()
            state.transformer?.cancel()
            state.tempFile.delete()
        }
        activeOperations.remove(operationId)
    }

    private fun updateState(operationId: String, newState: VideoEditState) {
        activeOperations[operationId]?.state?.set(newState)
        Log.d(tag, "Operation $operationId: State -> $newState")
    }

    private fun handleError(
        operationId: String,
        error: Throwable,
        listener: VideoEditListener
    ) {
        Log.e(tag, "Error in operation $operationId", error)
        updateState(operationId, VideoEditState.ERROR)
        stopProgressTracking(operationId)

        val operation = activeOperations[operationId]?.operation
        val errorResult = VideoEditResult.Error(
            throwable = error,
            errorCode = mapExceptionToErrorCode(error),
            isRecoverable = isRecoverableError(error),
            operation = operation
        )

        cleanupOperation(operationId)
        listener.onVideoEditFailure(errorResult)
    }

    private fun mapExceptionToErrorCode(throwable: Throwable): ErrorCode {
        return when {
            throwable.message?.contains("storage", ignoreCase = true) == true ->
                ErrorCode.INSUFFICIENT_STORAGE
            throwable.message?.contains("memory", ignoreCase = true) == true ->
                ErrorCode.INSUFFICIENT_MEMORY
            throwable is IllegalArgumentException ->
                ErrorCode.INVALID_INPUT
            throwable is ExportException &&
                    throwable.errorCode == ExportException.ERROR_CODE_ENCODING_FAILED ->
                ErrorCode.CODEC_ERROR
            throwable is CancellationException ->
                ErrorCode.OPERATION_CANCELLED
            else ->
                ErrorCode.UNKNOWN
        }
    }

    private fun isRecoverableError(error: Throwable): Boolean {
        return when (mapExceptionToErrorCode(error)) {
            ErrorCode.INSUFFICIENT_STORAGE,
            ErrorCode.INSUFFICIENT_MEMORY,
            ErrorCode.OPERATION_CANCELLED -> true
            else -> false
        }
    }

    private fun getUsedMemory(): Long {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    }

    private fun createTempFile(prefix: String): File {
        val file = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}.mp4")
        if (!file.exists()) file.createNewFile()
        return file
    }

    private fun getOperationPrefix(operation: VideoOperation): String {
        return when (operation) {
            is VideoOperation.Trim -> "trim"
            is VideoOperation.Rotate -> "rotate"
            is VideoOperation.Merge -> "merge"
            is VideoOperation.TrimAndRotate -> "trim_rotate"
        }
    }

    private suspend fun handleFileCleanup(tempFile: File, finalOutputUri: Uri): Uri {
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(finalOutputUri)?.use { outStream ->
                    tempFile.inputStream().use { inStream ->
                        inStream.copyTo(outStream)
                    }
                }

                // Grant permissions
                context.grantUriPermission(
                    context.packageName,
                    finalOutputUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                tempFile.delete()
                finalOutputUri
            } catch (e: Exception) {
                Log.e(tag, "Error in handleFileCleanup", e)
                throw e
            }
        }
    }

    fun cancelOperation(operationId: String) {
        activeOperations[operationId]?.let { state ->
            state.transformer?.cancel()
            cleanupOperation(operationId)
        }
    }

    fun release() {
        coroutineScope.cancel()
        activeOperations.forEach { (operationId, _) ->
            cancelOperation(operationId)
        }
        activeOperations.clear()
    }

}