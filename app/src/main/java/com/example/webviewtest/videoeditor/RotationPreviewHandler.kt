package com.example.webviewtest.videoeditor

import android.app.Activity
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.example.webviewtest.R

class RotationPreviewHandler(
    private val activity: Activity,
    private val overlayLayout: View
) {
    private var currentRotationAngle = 0
    private lateinit var videoFramePreview: ImageView
    private lateinit var btnRotateAngle: ImageButton
    private lateinit var btnConfirmRotation: Button
    private lateinit var btnCancelRotation: Button
    private lateinit var txtRotationAngle: TextView
    private var onRotationConfirmed: ((Float) -> Unit)? = null
    private lateinit var previewLayout: View

    init {
        setupPreviewLayout()
        initializeViews()
        setupClickListeners()
    }

    private fun setupPreviewLayout() {
        // Inflate the rotation preview layout
        previewLayout = LayoutInflater.from(activity).inflate(
            R.layout.rotation_preview_layout,
            overlayLayout as android.widget.FrameLayout,
            false
        )
        (overlayLayout as android.widget.FrameLayout).addView(previewLayout)
        hideOverlay() // Initially hide the overlay
    }

    private fun initializeViews() {
        try {
            videoFramePreview = previewLayout.findViewById(R.id.videoFramePreview)
            btnRotateAngle = previewLayout.findViewById(R.id.btnRotateAngle)
            btnConfirmRotation = previewLayout.findViewById(R.id.btnConfirmRotation)
            btnCancelRotation = previewLayout.findViewById(R.id.btnCancelRotation)
            txtRotationAngle = previewLayout.findViewById(R.id.txtRotationAngle)
        } catch (e: Exception) {
            Log.e("RotationPreviewHandler", "Error initializing views", e)
        }
    }

    private fun setupClickListeners() {
        btnRotateAngle.setOnClickListener {
            Log.d("RotationPreviewHandler", "Rotate button clicked")
            currentRotationAngle = (currentRotationAngle + 90) % 360
            updateRotationPreview()
        }

        btnConfirmRotation.setOnClickListener {
            Log.d("RotationPreviewHandler", "Confirm button clicked")
            hideOverlay()
            onRotationConfirmed?.invoke(currentRotationAngle.toFloat())
        }

        btnCancelRotation.setOnClickListener {
            Log.d("RotationPreviewHandler", "Cancel button clicked")
            hideOverlay()
        }
    }

    fun showRotationPreview(videoUri: Uri, onConfirm: (Float) -> Unit) {
        currentRotationAngle = 0 // Reset angle
        onRotationConfirmed = onConfirm // Store callback

        try {
            Log.d("RotationPreviewHandler", "Showing rotation preview")

            val bitmap = getFirstFrameFromVideo(videoUri)
            if (bitmap != null) {
                videoFramePreview.setImageBitmap(bitmap)
                videoFramePreview.visibility = View.VISIBLE
                showOverlay()
                updateRotationPreview()
            } else {
                Log.e("RotationPreviewHandler", "Failed to get video frame")
                showToast("Failed to retrieve video frame.")
            }
        } catch (e: Exception) {
            Log.e("RotationPreviewHandler", "Error showing rotation preview", e)
            showToast("Error showing rotation preview")
        }
    }

    private fun showOverlay() {
        previewLayout.visibility = View.VISIBLE
        overlayLayout.visibility = View.VISIBLE
        setupFullscreen()
    }

    private fun hideOverlay() {
        previewLayout.visibility = View.GONE
        overlayLayout.visibility = View.GONE
    }

    private fun updateRotationPreview() {
        Log.d("RotationPreviewHandler", "Updating rotation to: $currentRotationAngle degrees")
        videoFramePreview.rotation = currentRotationAngle.toFloat()
        txtRotationAngle.text = "${currentRotationAngle}°"
    }

    private fun getFirstFrameFromVideo(videoUri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            Log.d("RotationPreviewHandler", "Getting frame from video: $videoUri")
            retriever.setDataSource(activity, videoUri)
            val frame = retriever.getFrameAtTime(0)
            Log.d("RotationPreviewHandler", "Frame retrieved: ${frame != null}")
            frame
        } catch (e: Exception) {
            Log.e("RotationPreviewHandler", "Error getting video frame", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.e("RotationPreviewHandler", "Error releasing retriever", e)
            }
        }
    }

    private fun showToast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupFullscreen() {
        activity.window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }
}