package com.example.webviewtest

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import java.io.File

@UnstableApi
class VideoEditorUtils(private val context: Context) {
    private val transformer: Transformer = Transformer.Builder(context).build()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val progressHolder = ProgressHolder()
    private var progressJob: Job? = null

    interface VideoEditListener {
        fun onVideoEditSuccess(outputUri: Uri)
        fun onVideoEditFailure(error: Throwable)
        fun onVideoEditProgress(progress: Float)
    }

    private fun createTransformerWithProgress(outputFile: File, listener: VideoEditListener): Transformer {
        return Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        stopProgressTracking()
                        listener.onVideoEditSuccess(Uri.fromFile(outputFile))
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException
                    ) {
                        stopProgressTracking()
                        listener.onVideoEditFailure(exception)
                    }
                }
            )
            .build()
            .also { transformer ->
                startProgressTracking(transformer, listener)
            }
    }

    private fun startProgressTracking(transformer: Transformer, listener: VideoEditListener) {
        progressJob = coroutineScope.launch {
            while (isActive) {
                val progress = transformer.getProgress(progressHolder)
                if (progress != Transformer.PROGRESS_STATE_NOT_STARTED &&
                    progress != Transformer.PROGRESS_STATE_UNAVAILABLE) {
                    // Convert progress from 0-100000 to 0-1 range
                    val normalizedProgress = progressHolder.progress / 100f
                    listener.onVideoEditProgress(normalizedProgress)
                }
                delay(250) // Update every 250ms
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    fun mergeVideos(
        videoUriList: List<Uri>,
        outputUri: Uri,
        context: Context,
        audioMute: Boolean = false,
        audioUri: Uri? = null,
        listener: VideoEditListener
    ) {
        try {
            val outputFile = File(context.cacheDir, "temp_merged_video.mp4")
            if (!outputFile.exists()) {
                outputFile.createNewFile()
            }

            val transformer = createTransformerWithProgress(outputFile, object : VideoEditListener {
                override fun onVideoEditSuccess(resultUri: Uri) {
                    try {
                        context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                            outputFile.inputStream().use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        outputFile.delete()
                        listener.onVideoEditSuccess(outputUri)
                    } catch (e: Exception) {
                        listener.onVideoEditFailure(e)
                    }
                }

                override fun onVideoEditFailure(error: Throwable) {
                    outputFile.delete()
                    listener.onVideoEditFailure(error)
                }

                override fun onVideoEditProgress(progress: Float) {
                    listener.onVideoEditProgress(progress)
                }
            })

            val videoList = mutableListOf<EditedMediaItem>()
            videoUriList.map { uri ->
                videoList.add(
                    EditedMediaItem.Builder(MediaItem.fromUri(uri))
                        .setRemoveAudio(audioMute)
                        .build()
                )
            }

            val videoSequence = EditedMediaItemSequence(videoList)
            val composition = Composition.Builder(videoSequence).build()

            transformer.start(composition, outputFile.absolutePath)
        } catch (e: Exception) {
            stopProgressTracking()
            listener.onVideoEditFailure(e)
        }
    }

    fun trimVideo(
        inputUri: Uri,
        startMs: Long? = 0L,
        endMs: Long? = 0L,
        outputUri: Uri,
        listener: VideoEditListener
    ) {
        try {
            val outputFile = File(context.cacheDir, "temp_trimmed_video.mp4")
            if (!outputFile.exists()) {
                outputFile.createNewFile()
            }

            val transformer = createTransformerWithProgress(outputFile, object : VideoEditListener {
                override fun onVideoEditSuccess(resultUri: Uri) {
                    try {
                        context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                            outputFile.inputStream().use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        outputFile.delete()
                        listener.onVideoEditSuccess(outputUri)
                    } catch (e: Exception) {
                        listener.onVideoEditFailure(e)
                    }
                }

                override fun onVideoEditFailure(error: Throwable) {
                    outputFile.delete()
                    listener.onVideoEditFailure(error)
                }

                override fun onVideoEditProgress(progress: Float) {
                    listener.onVideoEditProgress(progress)
                }
            })

            val inputMediaItem = MediaItem.Builder()
                .setUri(inputUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs ?: 0L)
                        .setEndPositionMs(endMs ?: 0L)
                        .build()
                )
                .build()

            val editedMediaItem = EditedMediaItem.Builder(inputMediaItem).build()
            val composition = Composition.Builder(EditedMediaItemSequence(editedMediaItem)).build()

            transformer.start(composition, outputFile.absolutePath)
        } catch (e: Exception) {
            stopProgressTracking()
            listener.onVideoEditFailure(e)
        }
    }

    fun rotateVideo(inputUri: Uri, rotationDegrees: Float, outputUri: Uri, listener: VideoEditListener) {
        try {
            val outputFile = File(context.cacheDir, "temp_rotated_video.mp4")
            if (!outputFile.exists()) {
                outputFile.createNewFile()
            }

            val transformer = createTransformerWithProgress(outputFile, object : VideoEditListener {
                override fun onVideoEditSuccess(resultUri: Uri) {
                    try {
                        context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                            outputFile.inputStream().use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        outputFile.delete()
                        listener.onVideoEditSuccess(outputUri)
                    } catch (e: Exception) {
                        listener.onVideoEditFailure(e)
                    }
                }

                override fun onVideoEditFailure(error: Throwable) {
                    outputFile.delete()
                    listener.onVideoEditFailure(error)
                }

                override fun onVideoEditProgress(progress: Float) {
                    listener.onVideoEditProgress(progress)
                }
            })

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
            transformer.start(composition, outputFile.absolutePath)
        } catch (e: Exception) {
            stopProgressTracking()
            listener.onVideoEditFailure(e)
        }
    }

    fun trimAndRotateVideo(
        inputUri: Uri,
        startMs: Long? = 0L,
        endMs: Long? = 0L,
        rotationDegrees: Float,
        outputUri: Uri,
        listener: VideoEditListener
    ) {
        try {
            val outputFile = File(context.cacheDir, "temp_trimmed_rotated_video.mp4")
            if (!outputFile.exists()) {
                outputFile.createNewFile()
            }

            val transformer = createTransformerWithProgress(outputFile, object : VideoEditListener {
                override fun onVideoEditSuccess(resultUri: Uri) {
                    try {
                        context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                            outputFile.inputStream().use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        outputFile.delete()
                        listener.onVideoEditSuccess(outputUri)
                    } catch (e: Exception) {
                        listener.onVideoEditFailure(e)
                    }
                }

                override fun onVideoEditFailure(error: Throwable) {
                    outputFile.delete()
                    listener.onVideoEditFailure(error)
                }

                override fun onVideoEditProgress(progress: Float) {
                    listener.onVideoEditProgress(progress)
                }
            })

            // Create MediaItem with trimming configuration
            val inputMediaItem = MediaItem.Builder()
                .setUri(inputUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs ?: 0L)
                        .setEndPositionMs(endMs ?: 0L)
                        .build()
                )
                .build()

            // Create EditedMediaItem with both trimming and rotation effects
            val editedMediaItem = EditedMediaItem.Builder(inputMediaItem)
                .setEffects(
                    Effects(
                        listOf(), // No color effects
                        listOf(
                            ScaleAndRotateTransformation.Builder()
                                .setRotationDegrees(rotationDegrees)
                                .build()
                        )
                    )
                )
                .build()

            val composition = Composition.Builder(EditedMediaItemSequence(editedMediaItem)).build()
            transformer.start(composition, outputFile.absolutePath)
        } catch (e: Exception) {
            stopProgressTracking()
            listener.onVideoEditFailure(e)
        }
    }

    fun release() {
        stopProgressTracking()
        transformer.removeAllListeners()
        coroutineScope.cancel()
    }
}