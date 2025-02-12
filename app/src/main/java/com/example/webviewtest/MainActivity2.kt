package com.example.webviewtest

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

@UnstableApi
class MainActivity2 : AppCompatActivity() {

    private lateinit var videoEditor: VideoEditorUtils
    private lateinit var mergeButton: Button
    private lateinit var trimButton: Button
    private lateinit var rotateButton: Button
    private lateinit var trimAndRotateButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var videoRecyclerView: RecyclerView
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var progressText: TextView
    private val selectedVideoUris = mutableListOf<Uri>()

    private lateinit var trimStartEditText: TextInputEditText
    private lateinit var trimEndEditText: TextInputEditText
    private lateinit var rotationEditText: TextInputEditText

    private lateinit var pickMultipleMediaLauncher: ActivityResultLauncher<Intent>
    private lateinit var pickSingleMediaLauncher: ActivityResultLauncher<Intent>

    private var outputFileUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkStoragePermission()

        videoEditor = VideoEditorUtils(this)

        initializeViews()
        setupVideoRecyclerView()
        setupMediaPickers()
        setupClickListeners()
    }

    private fun initializeViews() {
        mergeButton = findViewById(R.id.mergeButton)
        trimButton = findViewById(R.id.trimButton)
        rotateButton = findViewById(R.id.rotateButton)
        trimAndRotateButton = findViewById(R.id.trimAndRotateButton)
        progressBar = findViewById(R.id.progressBar)
        videoRecyclerView = findViewById(R.id.videoRecyclerView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        progressText = findViewById(R.id.progressText)

        trimStartEditText = findViewById(R.id.trimStartEditText)
        trimEndEditText = findViewById(R.id.trimEndEditText)
        rotationEditText = findViewById(R.id.rotationEditText)
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
                    val clipData = result.data?.clipData
                    if (clipData != null) {
                        for (i in 0 until clipData.itemCount) {
                            val uri = clipData.getItemAt(i).uri
                            selectedVideoUris.add(uri)
                        }
                    } else if (result.data?.data != null) {
                        selectedVideoUris.add(result.data!!.data!!)
                    }
                    videoAdapter.notifyDataSetChanged()
                }
            }

        pickSingleMediaLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
                    selectedVideoUris.clear() // Clear previous selection for single video operations
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
            performMerge()
        }

        trimButton.setOnClickListener {
            performTrim()
        }

        rotateButton.setOnClickListener {
            performRotation()
        }

        trimAndRotateButton.setOnClickListener {
            performTrimAndRotate()
        }
    }

    private fun performTrimAndRotate() {
        if (selectedVideoUris.isEmpty()) {
            showToast("Please select a video to trim and rotate.")
            return
        }

        val startMs = trimStartEditText.text.toString().toLongOrNull()
        val endMs = trimEndEditText.text.toString().toLongOrNull()
        val rotationDegrees = rotationEditText.text.toString().toFloatOrNull() ?: 90f

        when {
            startMs == null -> {
                showToast("Please enter a valid start time")
                return
            }
            endMs == null -> {
                showToast("Please enter a valid end time")
                return
            }
            startMs >= endMs -> {
                showToast("End time must be greater than start time")
                return
            }
        }

        showLoading(true)
        val outputUri = generateOutputUri("trimmed_rotated_video_${System.currentTimeMillis()}.mp4")

        outputUri?.let { uri ->
            videoEditor.trimAndRotateVideo(
                selectedVideoUris[0],
                startMs,
                endMs,
                rotationDegrees,
                uri,
                videoEditListener
            )
        } ?: run {
            showLoading(false)
            showToast("Failed to create output file")
        }
    }

    private fun showLoading(show: Boolean) {
        runOnUiThread {
            loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
            if (!show) {
                progressBar.progress = 0
                progressText.text = "Processing: 0%"
            }
        }
    }

    private fun updateProgress(progress: Float) {
        runOnUiThread {
            val percentage = (progress * 100).toInt()
            progressBar.progress = percentage
            progressText.text = "Processing: $percentage%"
        }
    }

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

    private fun performMerge() {
        if (selectedVideoUris.size < 2) {
            showToast("Please select at least two videos to merge.")
            return
        }

        showLoading(true)
        outputFileUri = generateOutputUri("merged_video.mp4")

        outputFileUri?.let { uri ->
            videoEditor.mergeVideos(selectedVideoUris, uri, this@MainActivity2, false, null, videoEditListener)
        } ?: run {
            showLoading(false)
            showToast("Failed to create output file")
        }
    }

    private fun performTrim() {
        if (selectedVideoUris.isEmpty()) {
            showToast("Please select a video to trim.")
            return
        }

        val startMs = trimStartEditText.text.toString().toLongOrNull()
        val endMs = trimEndEditText.text.toString().toLongOrNull()

        when {
            startMs == null -> {
                showToast("Please enter a valid start time")
                return
            }
            endMs == null -> {
                showToast("Please enter a valid end time")
                return
            }
            startMs >= endMs -> {
                showToast("End time must be greater than start time")
                return
            }
        }

        showLoading(true)
        val outputUri = generateOutputUri("trimmed_video_${System.currentTimeMillis()}.mp4")

        outputUri?.let { uri ->
            videoEditor.trimVideo(
                selectedVideoUris[0],
                startMs,
                endMs,
                uri,
                videoEditListener
            )
        } ?: run {
            showLoading(false)
            showToast("Failed to create output file")
        }
    }

    private fun performRotation() {
        if (selectedVideoUris.isEmpty()) {
            showToast("Please select a video to rotate.")
            return
        }

        val rotationDegrees = rotationEditText.text.toString().toFloatOrNull() ?: 90f

        showLoading(true)
        val outputUri = generateOutputUri("rotated_video_${System.currentTimeMillis()}.mp4")

        outputUri?.let { uri ->
            videoEditor.rotateVideo(
                selectedVideoUris[0],
                rotationDegrees,
                uri,
                videoEditListener
            )
        } ?: run {
            showLoading(false)
            showToast("Failed to create output file")
        }
    }

    private val videoEditListener = object : VideoEditorUtils.VideoEditListener {
        override fun onVideoEditSuccess(outputUri: Uri) {
            runOnUiThread {
                showLoading(false)
                showToast("Video editing completed successfully!")
                Log.d("VideoEditor", "Video editing completed successfully!")

                // Optionally open the edited video
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(outputUri, "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            }
        }

        override fun onVideoEditFailure(error: Throwable) {
            runOnUiThread {
                showLoading(false)
                Log.e("VideoEditor", "Video editing failed", error)
                showToast("Video editing failed: ${error.message}")
            }
        }

        override fun onVideoEditProgress(progress: Float) {
            updateProgress(progress)
        }
    }

    private fun generateOutputUri(fileName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            try {
                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            } catch (e: Exception) {
                Log.e("FileProvider", "Error creating media store entry", e)
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
                Log.e("FileProvider", "Error creating file", e)
                null
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        videoEditor.release()
    }

    private fun checkStoragePermission() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val permissions = arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_AUDIO
                )

                val permissionsToRequest = permissions.filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }.toTypedArray()

                if (permissionsToRequest.isNotEmpty()) {
                    ActivityCompat.requestPermissions(
                        this,
                        permissionsToRequest,
                        STORAGE_PERMISSION_REQUEST_CODE
                    )
                }
            }
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        STORAGE_PERMISSION_REQUEST_CODE
                    )
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            STORAGE_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    showToast("Storage permissions granted")
                } else {
                    showToast("Storage permissions are required for video operations")
                }
            }
        }
    }

    companion object {
        private const val STORAGE_PERMISSION_REQUEST_CODE = 100
    }
}