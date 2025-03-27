package com.example.webviewtest.videoeditor

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class VideoOperationManager(private val context: Context) {
    private val maxConcurrentOperations = 3
    private val operationQueue = ConcurrentLinkedQueue<QueuedOperation>()
    private val activeOperations = ConcurrentHashMap<String, OperationInfo>()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val maxMemoryUsage = Runtime.getRuntime().maxMemory() * 0.75 // 75% of max memory

    data class OperationRequest(
        val operationId: String,
        val operation: suspend () -> Unit,
        val priority: OperationPriority = OperationPriority.NORMAL
    )

    data class OperationInfo(
        val operation: VideoOperation,
        val memoryUsage: Long = 0L,
        val startTime: Long = System.currentTimeMillis(),
        val priority: OperationPriority,
        var job: Job? = null
    )

    data class QueuedOperation(
        val request: OperationRequest,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class OperationPriority {
        HIGH, NORMAL, LOW
    }

    init {
        startQueueProcessor()
        startMemoryMonitor()
    }

    fun enqueueOperation(request: OperationRequest) {
        val queuedOperation = QueuedOperation(request)
        operationQueue.offer(queuedOperation)
    }

    private fun startQueueProcessor() {
        coroutineScope.launch {
            while (isActive) {
                processQueue()
                delay(100) // Check queue every 100ms
            }
        }
    }

    private fun processQueue() {
        if (activeOperations.size < maxConcurrentOperations && operationQueue.isNotEmpty()) {
            // Sort by priority and timestamp
            val nextOperation = operationQueue
                .sortedWith(compareBy({ it.request.priority }, { it.timestamp }))
                .firstOrNull()

            nextOperation?.let { operation ->
                operationQueue.remove(operation)
                startOperation(operation.request)
            }
        }
    }

    private fun startOperation(request: OperationRequest) {
        val job = coroutineScope.launch {
            try {
                request.operation()
            } catch (e: Exception) {
                // Handle operation failure
                cleanupOperation(request.operationId)
            }
        }

        // Store operation info
        activeOperations[request.operationId] = OperationInfo(
            operation = VideoOperation.Merge(0), // This should be properly set
            priority = request.priority,
            job = job
        )

        // Add job completion listener
        job.invokeOnCompletion { throwable ->
            if (throwable != null) {
                // Handle error
                cleanupOperation(request.operationId)
            }
        }
    }

    private fun startMemoryMonitor() {
        coroutineScope.launch {
            while (isActive) {
                checkMemoryUsage()
                delay(1000) // Check memory every second
            }
        }
    }

    private fun checkMemoryUsage() {
        val usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        if (usedMemory > maxMemoryUsage) {
            // Memory pressure detected, pause low priority operations
            pauseLowPriorityOperations()
        }
    }

    private fun pauseLowPriorityOperations() {
        activeOperations
            .filter { it.value.priority == OperationPriority.LOW }
            .forEach { (operationId, _) ->
                pauseOperation(operationId)
            }
    }

    fun cancelOperation(operationId: String) {
        // Remove from queue if not started
        operationQueue.removeIf { it.request.operationId == operationId }

        // Cancel if active
        activeOperations[operationId]?.let { info ->
            info.job?.cancel()
            cleanupOperation(operationId)
        }
    }

    fun pauseOperation(operationId: String) {
        // Note: Actual pause implementation would depend on your video processing library
        // This is a simplified version
        activeOperations[operationId]?.job?.cancel(CancellationException("Operation paused"))
    }

    fun resumeOperation(operationId: String) {
        // Re-queue the operation if it was paused
        operationQueue.find { it.request.operationId == operationId }?.let { operation ->
            startOperation(operation.request)
        }
    }

    private fun cleanupOperation(operationId: String) {
        activeOperations.remove(operationId)
    }

    fun release() {
        coroutineScope.cancel()
        operationQueue.clear()
        activeOperations.forEach { (operationId, info) ->
            info.job?.cancel()
        }
        activeOperations.clear()
    }
}