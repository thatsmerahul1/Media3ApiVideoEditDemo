package com.example.webviewtest

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.webviewtest.ui.editor.VideoEditorViewModel
import com.example.webviewtest.ui.editor.VideoEditorViewModelFactory
import com.example.webviewtest.videoeditor.*
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
class MainActivity2 : AppCompatActivity() {
    private val viewModel: VideoEditorViewModel by viewModels {
        VideoEditorViewModelFactory(application)
    }

    // UI references
    private lateinit var mergeButton: Button
    private lateinit var trimButton: Button
    private lateinit var rotateButton: Button
    private lateinit var trimAndRotateButton: Button
    private lateinit var videoRecyclerView: RecyclerView
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var operationsContainer: LinearLayout

    private lateinit var trimStartEditText: EditText
    private lateinit var trimEndEditText: EditText
    private lateinit var rotationEditText: EditText

    // Operation views management
    private val operationViews = ConcurrentHashMap<String, OperationView>()

    // For picking videos
    private lateinit var pickMultipleMediaLauncher: ActivityResultLauncher<Intent>
    private lateinit var pickSingleMediaLauncher: ActivityResultLauncher<Intent>

    private val selectedVideoUris = mutableListOf<Uri>()

    private lateinit var rotationPreviewHandler: RotationPreviewHandler
    private lateinit var rotationOverlay: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupVideoRecyclerView()
        setupMediaPickers()
        setupClickListeners()
        observeOperations()
        checkStoragePermission()
    }

    private fun initViews() {
        mergeButton = findViewById(R.id.mergeButton)
        trimButton = findViewById(R.id.trimButton)
        rotateButton = findViewById(R.id.rotateButton)
        trimAndRotateButton = findViewById(R.id.trimAndRotateButton)
        videoRecyclerView = findViewById(R.id.videoRecyclerView)
        operationsContainer = findViewById(R.id.operationsContainer)

        trimStartEditText = findViewById(R.id.trimStartEditText)
        trimEndEditText = findViewById(R.id.trimEndEditText)
        rotationEditText = findViewById(R.id.rotationEditText)

        rotationOverlay = findViewById(R.id.rotationOverlay)
        rotationPreviewHandler = RotationPreviewHandler(this, rotationOverlay)
    }

    private fun setupVideoRecyclerView() {
        videoRecyclerView.layoutManager = LinearLayoutManager(this)
        videoAdapter = VideoAdapter(selectedVideoUris) { uri ->
            selectedVideoUris.remove(uri)
            videoAdapter.notifyDataSetChanged()
        }
        videoRecyclerView.adapter = videoAdapter
    }

    private fun setupMediaPickers() {
        pickMultipleMediaLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.clipData?.let { clipData ->
                        for (i in 0 until clipData.itemCount) {
                            selectedVideoUris.add(clipData.getItemAt(i).uri)
                        }
                    } ?: result.data?.data?.let { uri ->
                        selectedVideoUris.add(uri)
                    }
                    videoAdapter.notifyDataSetChanged()
                }
            }

        pickSingleMediaLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
                    selectedVideoUris.clear()
                    selectedVideoUris.add(result.data!!.data!!)
                    videoAdapter.notifyDataSetChanged()
                }
            }
    }

    private fun setupClickListeners() {
        findViewById<Button>(R.id.selectVideoButton).setOnClickListener {
            openMediaPicker()
        }

        findViewById<Button>(R.id.selectSingleVideoButton).setOnClickListener {
            openSingleMediaPicker()
        }

        mergeButton.setOnClickListener {
            handleMergeOperation()
        }

        trimButton.setOnClickListener {
            handleTrimOperation()
        }

        rotateButton.setOnClickListener {
            handleRotateOperation()
        }

        trimAndRotateButton.setOnClickListener {
            handleTrimAndRotateOperation()
        }
    }

//    private fun observeOperations() {
//        lifecycleScope.launch {
//            repeatOnLifecycle(Lifecycle.State.RESUMED) {
//                viewModel.operations.forEach { (operationId, stateFlow) ->
//                    launch {
//                        stateFlow.collect { state ->
//                            updateOperationUI(operationId, state)
//                        }
//                    }
//                }
//            }
//        }
//    }

    private fun observeOperations() {
        lifecycleScope.launch {
            while (isActive) {
                viewModel.operations.forEach { (operationId, stateFlow) ->
                    launch {
                        stateFlow.collectLatest { state ->
                            runOnUiThread {
                                updateOperationUI(operationId, state)
                            }
                        }
                    }
                }
                delay(1000) // Refresh UI every second
            }
        }
    }



    private fun updateOperationUI(
        operationId: String,
        state: VideoEditorViewModel.OperationState
    ) {
        val operationView = getOrCreateOperationView(operationId, state.operation)

        with(operationView) {
            progressBar.progress = (state.progress * 100).toInt()
            statusText.text = buildStatusText(state)

            // Update memory usage
            memoryText.text = formatMemoryUsage(state.memoryUsage)

            // Update time remaining if available
            timeRemainingText.text = viewModel.getFormattedTimeRemaining(operationId)
                ?.let { "Time remaining: $it" } ?: ""

            when (state.status) {
                VideoEditState.COMPLETED -> {
                    container.animate()
                        .alpha(0f)
                        .withEndAction {
                            container.visibility = View.VISIBLE
//                            operationViews.remove(operationId)
                            state.outputUri?.let { uri -> openVideo(uri) }
                        }
                }
                VideoEditState.ERROR -> {
                    container.animate()
                        .alpha(0f)
                        .withEndAction {
                            container.visibility = View.VISIBLE
//                            operationViews.remove(operationId)
                            showError(state.error ?: "Unknown error")
                        }
                }
                else -> {
                    container.visibility = View.VISIBLE
                    container.alpha = 1f

                    // Show/hide pause button based on state
                    pauseResumeButton.isEnabled = state.status == VideoEditState.PROCESSING
                    pauseResumeButton.setImageResource(
                        if (state.isPaused) R.drawable.ic_play
                        else R.drawable.ic_pause
                    )
                }
            }
        }
    }

    private fun buildStatusText(state: VideoEditorViewModel.OperationState): String {
        return buildString {
            append("Status: ${state.status.name}")
            if (state.isPaused) append(" (PAUSED)")
            append("\nProgress: ${(state.progress * 100).toInt()}%")
        }
    }

    private fun formatMemoryUsage(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        return "Memory: $mb MB"
    }

    private fun getOrCreateOperationView(
        operationId: String,
        operation: VideoOperation?
    ): OperationView {
        return operationViews.getOrPut(operationId) {
            val view = layoutInflater.inflate(
                R.layout.operation_progress_item,
                operationsContainer,
                false
            )

            val operationView = OperationView(
                container = view,
                progressBar = view.findViewById(R.id.progressBar),
                statusText = view.findViewById(R.id.statusText),
                titleText = view.findViewById(R.id.titleText),
                cancelButton = view.findViewById(R.id.cancelButton),
                pauseResumeButton = view.findViewById(R.id.pauseResumeButton),
                memoryText = view.findViewById(R.id.memoryText),
                timeRemainingText = view.findViewById(R.id.timeRemainingText)
            )

            // Set operation title
            operationView.titleText.text = getOperationTitle(operation)

            // Setup cancel button
            operationView.cancelButton.setOnClickListener {
                viewModel.cancelOperation(operationId)
            }

            // Setup pause/resume button
            operationView.pauseResumeButton.setOnClickListener {
                val currentState = viewModel.operations[operationId]?.value
                if (currentState?.isPaused == true) {
                    viewModel.resumeOperation(operationId)
                } else {
                    viewModel.pauseOperation(operationId)
                }
            }

            operationsContainer.addView(view)
            operationView
        }
    }

    private fun getOperationTitle(operation: VideoOperation?): String {
        return when (operation) {
            is VideoOperation.Trim -> "Trimming Video"
            is VideoOperation.Rotate -> "Rotating Video"
            is VideoOperation.Merge -> "Merging Videos"
            is VideoOperation.TrimAndRotate -> "Trimming & Rotating"
            null -> "Processing Video"
        }
    }

    // Operation handling methods
    private fun handleMergeOperation() {
        if (selectedVideoUris.size < 2) {
            showError("Select at least two videos to merge")
            return
        }

        val outputUri = generateOutputUri(this@MainActivity2, "merged_${System.currentTimeMillis()}.mp4")
        outputUri?.let { uri ->
            var operationId = viewModel.mergeVideos(
                videoUris = selectedVideoUris,
                outputUri = uri,
                priority = VideoOperationManager.OperationPriority.NORMAL
            )
        } ?: showError("Failed to create output file")
    }

    private fun handleTrimOperation() {
        if (selectedVideoUris.isEmpty()) {
            showError("Select a video to trim")
            return
        }

        val startMs = trimStartEditText.text.toString().toLongOrNull() ?: 0
        val endMs = trimEndEditText.text.toString().toLongOrNull() ?: 0

        if (startMs >= endMs) {
            showError("End time must be greater than start time")
            return
        }

        val outputUri = generateOutputUri(this@MainActivity2,"trimmed_${System.currentTimeMillis()}.mp4")
        outputUri?.let { uri ->
            viewModel.trimVideo(
                inputUri = selectedVideoUris[0],
                startMs = startMs,
                endMs = endMs,
                outputUri = uri
            )
        } ?: showError("Failed to create output file")
    }

    private fun handleRotateOperation() {
        if (selectedVideoUris.isEmpty()) {
            showError("Select a video to rotate")
            return
        }

        rotationOverlay.visibility = View.VISIBLE  // Make sure overlay is visible
        rotationOverlay.bringToFront()  // Bring to front of view hierarchy
        rotationPreviewHandler.showRotationPreview(selectedVideoUris[0]) { angle ->
            val outputUri = generateOutputUri(this@MainActivity2,"rotated_${System.currentTimeMillis()}.mp4")
            outputUri?.let { uri ->
                viewModel.rotateVideo(
                    inputUri = selectedVideoUris[0],
                    rotationDegrees = angle,
                    outputUri = uri
                )
            } ?: showError("Failed to create output file")
        }
    }

    private fun handleTrimAndRotateOperation() {
        if (selectedVideoUris.isEmpty()) {
            showError("Select a video")
            return
        }

        val startMs = trimStartEditText.text.toString().toLongOrNull() ?: 0
        val endMs = trimEndEditText.text.toString().toLongOrNull() ?: 0
        val rotationDegrees = rotationEditText.text.toString().toFloatOrNull() ?: 90f

        if (startMs >= endMs) {
            showError("End time must be greater than start time")
            return
        }

        val outputUri = generateOutputUri(this@MainActivity2,"trimRotate_${System.currentTimeMillis()}.mp4")
        outputUri?.let { uri ->
            viewModel.trimAndRotateVideo(
                inputUri = selectedVideoUris[0],
                startMs = startMs,
                endMs = endMs,
                rotationDegrees = rotationDegrees,
                outputUri = uri
            )
        } ?: showError("Failed to create output file")
    }

    // Helper methods
    private fun openMediaPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        pickMultipleMediaLauncher.launch(intent)
    }

    private fun openSingleMediaPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "video/*"
        }
        pickSingleMediaLauncher.launch(intent)
    }

    private fun showError(message: String) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG)
            .setAction("Dismiss") { }
            .show()
    }

    private fun openVideo(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")  // Explicitly set MIME type
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)  // Add this flag
            }

            // Check if there's an app that can handle this intent
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                showError("No video player app found")
            }
        } catch (e: Exception) {
            showError("Error opening video: ${e.message}")
            Log.e("MainActivity2", "Error opening video", e)
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

    private fun checkStoragePermission() {
        val STORAGE_PERMISSION_REQUEST_CODE = 100
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissions = arrayOf(
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_AUDIO
            )
            val notGranted = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (notGranted.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), STORAGE_PERMISSION_REQUEST_CODE)
            }
        } else {
            val permissions = arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val notGranted = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (notGranted.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), STORAGE_PERMISSION_REQUEST_CODE)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        operationViews.clear()
    }

    // Data class for holding operation progress views
    private data class OperationView(
        val container: View,
        val progressBar: ProgressBar,
        val statusText: TextView,
        val titleText: TextView,
        val cancelButton: ImageButton,
        val pauseResumeButton: ImageButton,
        val memoryText: TextView,
        val timeRemainingText: TextView
    )
}