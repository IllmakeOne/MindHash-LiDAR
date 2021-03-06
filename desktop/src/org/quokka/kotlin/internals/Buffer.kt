package org.quokka.kotlin.internals

import com.badlogic.gdx.Gdx
import java.lang.Exception
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

interface Buffer {
    val lastFrameIndex: Int
    val pastBufferSize: Int
    val futureBufferSize: Int
    val progress: Float
    fun nextFrame(): LidarFrame?
    fun prevFrame(): LidarFrame?
    fun skipForward(seconds: Float)
    fun skipBackward(seconds: Float)
    fun skipTo(percentage: Float)
    fun clear()
}

/**
 * Implementation of the Buffer interface.
 * It is backed by a database which contains information about the point clouds at each individual frame.
 *
 * @param recordingId The recordingId associated with the desired recording in the database.
 */
class PrerecordedBuffer(private val recordingId: Int) : Buffer {
    /**
     * Use this object to get access to all the meta data of the current buffer.
     */
    private val recordingMetaData: RecordingMeta

    /**
     * These variables are for meta data on the current buffer and are updated every time a frame is retrieved.
     */
    override var lastFrameIndex: Int = 0
    override var futureBufferSize: Int = 0
    override var pastBufferSize: Int = 0
    override val progress: Float
        get() {
            var p = (lastFrameIndex - recordingMetaData.minFrame) / (recordingMetaData.maxFrame - recordingMetaData.minFrame).toFloat()
            if (p < 0f)
                p = 0f
            if (p > 1f)
                p = 1f
            return p
        }


    // How many frames to fetch with the fetch function
    @Volatile
    private var skipToFrameIndex: Int?
    private val framesPerBuffer: Int
        get() = BUFFER_SIZE_S * LIDAR_FPS
    private val playQueue = ConcurrentLinkedDeque<LidarFrame>()
    private val delQueue = ConcurrentLinkedDeque<LidarFrame>()

    /*
     * This lock prevents the updateBuffers() function from running simultaneously
     */
    private val queryLock = ReentrantLock()

    /*
     * This lock prevents the queues from being modified at the same time. For example when a user spams the skip
     * buttons.
     */
    private val skipLock = ReentrantLock()


    init {
        println("Initializing buffer with $framesPerBuffer max frames")
        val rec = Database.getRecording(recordingId)
        if (rec != null) {
            recordingMetaData = rec
        } else {
            throw NoRecordingException("No recording found with id '$recordingId'")
        }

        skipToFrameIndex = null
        lastFrameIndex = recordingMetaData.minFrame

        // Update buffers to fill them up initially
        thread {
            updateBuffers()
            futureBufferSize = playQueue.size
            pastBufferSize = playQueue.size
        }
    }

    companion object {
        private val prefs = Gdx.app.getPreferences("My Preferences")

        // Number of frames to be queried per update. Defaults to 2 seconds of footage
        val FRAMES_PER_QUERY
            get() = LIDAR_FPS * 2

        val LIDAR_FPS
            get() = prefs.getInteger("LIDAR FPS")

        // The maximum size of the buffer in seconds
        val BUFFER_SIZE_S
            get() = prefs.getInteger("MEMORY")
    }

    /**
     * Fetch a single frame from the buffer and forwards it by one frame.
     *
     * @return The next frame in the buffer if it exists.
     */
    override fun nextFrame(): LidarFrame? {
        try {
            skipLock.lock()

            val frame = playQueue.poll()
            frame?.let {
                updateMeta(frame)
                delQueue.offer(it)
            }

            thread {
                updateBuffers()
            }

            return frame
        } finally {
            skipLock.unlock()
        }
    }

    /**
     * Fetch a single frame from the buffer and reverts it by one frame.
     *
     * @return The previous frame in the buffer if it exists.
     */
    override fun prevFrame(): LidarFrame? {
        try {
            skipLock.lock()
            val frame = delQueue.pollFirst()
            frame?.let {
                updateMeta(frame)
                playQueue.offer(it)
            }
            return frame

        } finally {
            skipLock.unlock()
        }
    }

    /**
     * Skip forward a certain amount.
     *
     * @param seconds The number of seconds to skip.
     */
    override fun skipForward(seconds: Float) {
        try {
            skipLock.lock()

            val framesToSkip = (seconds * LIDAR_FPS).toInt()
            val targetFrame = lastFrameIndex + framesToSkip
            val lastFrameAvailable = playQueue.peekLast()?.frameId

            if (lastFrameAvailable == null || targetFrame > lastFrameAvailable) {
                // Target frame is not in buffer
                skipTo(targetFrame)
                return
            }

            // Number of extra frames that have to be fetched
            for (i in 0 until framesToSkip) {
                val frame = playQueue.poll()
                if (frame != null) {
                    delQueue.offer(frame)
                } else {
                    break
                }
            }
        } finally {
            skipLock.unlock()
        }
    }

    /**
     * Skip backward a certain amount.
     *
     * @param seconds The number of seconds to skip.
     */
    override fun skipBackward(seconds: Float) {
        try {
            skipLock.lock()
            val framesToSkip = (seconds * LIDAR_FPS).toInt()
            val targetFrame = lastFrameIndex - framesToSkip
            val firstFrameAvailable = delQueue.peekFirst()?.frameId

            if (firstFrameAvailable == null || firstFrameAvailable > targetFrame) {
                // Target frame is not in buffer
                skipTo(targetFrame)
                return
            }

            for (i in 0 until framesToSkip) {
                // Get the latest frame
                val frame = delQueue.pollLast()
                if (frame != null) {
                    playQueue.offerFirst(frame)
                } else {
                    break
                }
            }
        } finally {
            skipLock.unlock()
        }
    }

    override fun clear() {
        skipTo(lastFrameIndex)
    }

    /**
     * Skip to a specific frame index and reload the buffer. This may take a while and blocks the thread.
     * If the frameIndex value is not in the bounds, stuff may break.
     *
     * @param frameIndex Which frame to skip to.
     */
    private fun skipTo(frameIndex: Int) {
        try {
            skipLock.lock()
            skipToFrameIndex = frameIndex
            updateBuffers()
            updateMeta()
        } finally {
            skipLock.unlock()
        }
    }

    /**
     * Skip to a certain percentage indicated by a float between 0 and 1. If the value is below 0 or above 1 it will be
     * capped.
     *
     * @param percentage The percentage of the recording to which the progress should be set.
     */
    override fun skipTo(percentage: Float) {
        var p = percentage
        if (p < 0f)
            p = 0f
        if (p > 1f)
            p = 1f

        val totalFrames = recordingMetaData.maxFrame - recordingMetaData.minFrame
        skipTo((recordingMetaData.minFrame + totalFrames * p).toInt())
    }

    override fun toString(): String {
        return "Buffer { recordingId=$recordingId, playQueueSize=${playQueue.size}, delQueueSize=${delQueue.size}" +
                ", framesPerBuffer=${LIDAR_FPS * BUFFER_SIZE_S}" +
                ", lastFrameId=${playQueue.peekFirst()?.frameId}}"
    }

    /*
     * Private functions
     */
    private fun updateMeta(frame: LidarFrame? = null) {
        var fr = frame
        if (fr == null) {
            fr = playQueue.peekLast() ?: delQueue.peekFirst()
        }

        fr?.let {
            lastFrameIndex = it.frameId
        }
        futureBufferSize = playQueue.size
        pastBufferSize = delQueue.size
    }

    private fun fullCleanBuffers() {
        delQueue.clear()
        playQueue.clear()
    }

    /**
     * Clean up the buffers and fit them to size with a query if needed.
     */
    private fun updateBuffers() {
        if (!queryLock.tryLock()) {
            return
        }

        clearBuffers()

        queryLock.unlock()
    }

    private fun clearBuffers() {
        // Calculate number of frames per buffer


        // Remove or add frames to the queue
        if (playQueue.size >= framesPerBuffer) {
            for (i in 0 until playQueue.size - framesPerBuffer) {
                playQueue.pollLast()
            }
        } else if (playQueue.size <= framesPerBuffer * 0.8) {
            var lastId = playQueue.peekLast()?.frameId ?: lastFrameIndex
            // If the skipToFrameIndex is not null, clean everything and go there
            skipToFrameIndex?.let {
                lastId = it
                fullCleanBuffers()
                // Reset it back to null after skipping is done
                skipToFrameIndex = null
            }

            if (lastId < recordingMetaData.maxFrame) {
                var frames = emptyList<LidarFrame>()

                while (frames.isEmpty() && lastId < recordingMetaData.maxFrame) {
                    frames = Database.getFrames(
                            recordingId = recordingId,
                            startFrame = lastId + 1,
                            numberOfFrames = FRAMES_PER_QUERY,
                            framerate = LIDAR_FPS)
                    lastId += FRAMES_PER_QUERY
                }

                try {
                    skipLock.lock()
                    playQueue.addAll(frames)
                } finally {
                    skipLock.unlock()
                }
            }
        }

        // Clean up history buffer
        for (i in 0 until delQueue.size - framesPerBuffer) {
            delQueue.pollFirst()
        }
    }
}

/**
 * Exception to be thrown when there is no recording present with a given ID.
 */
class NoRecordingException(msg: String) : Exception(msg)
