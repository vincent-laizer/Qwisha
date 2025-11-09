package com.tanzaniaprogrammers.qwisha

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

object AudioUtils {
    private const val TAG = "AudioUtils"
    private const val MAX_RECORDING_DURATION_MS = 20000 // 20 seconds
    private const val AUDIO_SAMPLE_RATE = 8000 // 8kHz for compression
    private const val AUDIO_BIT_RATE = 16000 // 16 kbps for high compression
    private const val AUDIO_CHANNELS = 1 // Mono for compression

    // SMS character limit (accounting for header overhead)
    // Standard SMS limit is 160 characters for 7-bit encoding
    // But we need to account for our header which can vary in length
    // Header format: "@i=<msgId>;c=<cmd>;t=voice;p=<part>/<total>"
    // msgId is typically 8 chars, cmd is 1 char, part/total can be up to 10 chars (e.g., "1/99")
    // So header can be: 3 + 8 + 3 + 1 + 3 + 6 + 3 + 10 + 1 = 38 chars minimum
    // With reply/edit refId: +3 + 8 = 11 more chars = 49 chars
    // Adding some buffer for safety: use 60 chars for header overhead
    private const val MAX_SMS_CHARS = 100 * 4 // Standard SMS limit
    private const val HEADER_OVERHEAD = 60 // Header size with buffer (accounts for msgId, cmd, type, part info, etc.)
    private const val MAX_CONTENT_CHARS = MAX_SMS_CHARS - HEADER_OVERHEAD // ~100 chars per part

    // Global recorder instance for cancellation
    @Volatile
    private var currentRecorder: MediaRecorder? = null
    @Volatile
    private var shouldStopRecording = false
    @Volatile
    private var currentRecordingFilePath: String? = null

    /**
     * Stop the current recording
     */
    fun stopCurrentRecording() {
        shouldStopRecording = true
        Log.d(TAG, "Stop recording requested")
    }

    /**
     * Get the current recording file path (for cleanup on cancel)
     */
    fun getCurrentRecordingFilePath(): String? {
        return currentRecordingFilePath
    }

    /**
     * Record audio for up to 20 seconds (or until stopped)
     * Returns the file path of the recorded audio
     * Note: This function blocks until recording is complete or stopped
     */
    suspend fun recordAudio(context: Context, maxDurationMs: Int = MAX_RECORDING_DURATION_MS): String? = withContext(Dispatchers.IO) {
        var recorder: MediaRecorder? = null
        val audioFile = File(context.filesDir, "audio/voice_${System.currentTimeMillis()}.3gp")

        // Reset stop flag and track file path
        shouldStopRecording = false
        currentRecordingFilePath = audioFile.absolutePath

        try {
            // Create audio directory if it doesn't exist
            val audioDir = audioFile.parentFile
            if (audioDir != null && !audioDir.exists()) {
                audioDir.mkdirs()
            }

            Log.d(TAG, "Initializing MediaRecorder for file: ${audioFile.absolutePath}")

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            // Configure MediaRecorder
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                Log.d(TAG, "Audio source set to MIC")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting audio source: ${e.message}", e)
                throw e
            }

            try {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                Log.d(TAG, "Output format set to THREE_GPP")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting output format: ${e.message}", e)
                throw e
            }

            try {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                Log.d(TAG, "Audio encoder set to AMR_NB")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting audio encoder: ${e.message}", e)
                throw e
            }

            try {
                recorder.setOutputFile(audioFile.absolutePath)
                Log.d(TAG, "Output file set to: ${audioFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting output file: ${e.message}", e)
                throw e
            }

            try {
                recorder.setMaxDuration(maxDurationMs)
                Log.d(TAG, "Max duration set to: $maxDurationMs ms")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting max duration: ${e.message}", e)
                // Continue even if this fails
            }

            // Prepare the recorder
            try {
                recorder.prepare()
                Log.d(TAG, "MediaRecorder prepared successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing MediaRecorder: ${e.message}", e)
                throw e
            }

            // Start recording
            try {
                recorder.start()
                Log.d(TAG, "Recording started successfully")
                currentRecorder = recorder // Store for cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Error starting recorder: ${e.message}", e)
                throw e
            }

            // Wait for the recording to complete, max duration, or stop request
            // Poll the recorder state periodically instead of just delaying
            var elapsedTime = 0L
            val pollInterval = 100L // Check every 100ms
            val startTime = System.currentTimeMillis()
            val minimumRecordingDuration = 500L // Minimum 500ms to ensure file is valid

            while (elapsedTime < maxDurationMs && !shouldStopRecording) {
                kotlinx.coroutines.delay(pollInterval)
                elapsedTime = System.currentTimeMillis() - startTime
            }

            // Note: We don't enforce a minimum duration anymore
            // MediaRecorder should handle very short recordings fine

            val wasStoppedEarly = shouldStopRecording

            if (wasStoppedEarly) {
                Log.d(TAG, "Recording stopped early by user. Elapsed: $elapsedTime ms")
                // Give MediaRecorder a brief moment to finish writing any buffered data
                // This is especially important for very short recordings
                if (elapsedTime < 1000) {
                    Log.d(TAG, "Very short recording (${elapsedTime}ms), adding small delay for MediaRecorder to stabilize")
                    kotlinx.coroutines.delay(200) // 200ms delay for very short recordings
                }
            } else {
                Log.d(TAG, "Recording duration completed. Elapsed: $elapsedTime ms")
            }

            // Clear the stop flag before stopping
            shouldStopRecording = false
            val savedFilePath = currentRecordingFilePath
            currentRecorder = null
            currentRecordingFilePath = null

            // Stop the recorder
            try {
                recorder.stop()
                Log.d(TAG, "Recording stopped successfully after ${elapsedTime}ms. File should be saved now.")
            } catch (e: IllegalStateException) {
                // Recorder might have already stopped due to max duration or error
                Log.w(TAG, "Recorder already stopped or in wrong state: ${e.message}")
                // Check if file exists anyway - if it does and has content, continue
                if (!audioFile.exists() || audioFile.length() == 0L) {
                    Log.e(TAG, "Recording stopped but no file was created or file is empty")
                    // For early stops, this might be okay if the recording was too short
                    // Let's check the file one more time after a brief delay
                    kotlinx.coroutines.delay(100)
                    if (audioFile.exists() && audioFile.length() > 0) {
                        Log.d(TAG, "File appeared after delay, continuing...")
                    } else {
                        Log.e(TAG, "File still doesn't exist or is empty after delay")
                        throw e
                    }
                } else {
                    Log.d(TAG, "Recorder was in wrong state but file exists with content, continuing...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recorder: ${e.message}", e)
                // Try to check if file exists before throwing
                if (audioFile.exists() && audioFile.length() > 0) {
                    Log.w(TAG, "Error stopping recorder but file exists, continuing with file")
                } else {
                    // Give it one more chance - wait a bit and check again
                    kotlinx.coroutines.delay(100)
                    if (audioFile.exists() && audioFile.length() > 0) {
                        Log.d(TAG, "File appeared after error delay, continuing...")
                    } else {
                        Log.e(TAG, "File still doesn't exist after error, throwing exception")
                        throw e
                    }
                }
            }

            // Small delay after stopping to ensure file is fully written
            kotlinx.coroutines.delay(50)

            // Release the recorder
            try {
                recorder.release()
                recorder = null
                Log.d(TAG, "MediaRecorder released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing recorder: ${e.message}", e)
            }

            // Verify file was created and has content
            if (audioFile.exists() && audioFile.length() > 0) {
                Log.d(TAG, "Audio recorded successfully: ${audioFile.absolutePath}, size: ${audioFile.length()} bytes")
                audioFile.absolutePath
            } else {
                Log.e(TAG, "Audio file was not created or is empty. Exists: ${audioFile.exists()}, Size: ${audioFile.length()}")
                null
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException - RECORD_AUDIO permission not granted: ${e.message}", e)
            try {
                recorder?.stop()
            } catch (_: Exception) {}
            try {
                recorder?.release()
            } catch (_: Exception) {}
            currentRecorder = null
            currentRecordingFilePath = null
            shouldStopRecording = false
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error recording audio: ${e.message}", e)
            e.printStackTrace()
            try {
                recorder?.stop()
            } catch (_: Exception) {}
            try {
                recorder?.release()
            } catch (_: Exception) {}
            // Clean up empty file if it exists
            try {
                if (audioFile.exists() && audioFile.length() == 0L) {
                    audioFile.delete()
                }
            } catch (_: Exception) {}
            currentRecorder = null
            currentRecordingFilePath = null
            shouldStopRecording = false
            null
        }
    }

    /**
     * Stop recording
     */
    fun stopRecording(recorder: MediaRecorder?) {
        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder: ${e.message}", e)
        }
    }

    /**
     * Compress audio file and convert to base64
     * Returns base64 encoded string
     */
    suspend fun compressAndEncodeAudio(audioFilePath: String): String? = withContext(Dispatchers.IO) {
        try {
            val audioFile = File(audioFilePath)
            if (!audioFile.exists()) {
                Log.e(TAG, "Audio file not found: $audioFilePath")
                return@withContext null
            }

            // Read audio file
            val audioBytes = audioFile.readBytes()
            Log.d(TAG, "Original audio size: ${audioBytes.size} bytes")

            // Encode to base64
            val base64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
            Log.d(TAG, "Base64 encoded size: ${base64.length} characters")

            return@withContext base64
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing audio: ${e.message}", e)
            null
        }
    }

    /**
     * Decode base64 and save as audio file
     * Returns the file path of the decoded audio
     */
    suspend fun decodeAndSaveAudio(context: Context, base64Audio: String, messageId: String): String? = withContext(Dispatchers.IO) {
        try {
            val audioBytes = Base64.decode(base64Audio, Base64.NO_WRAP)

            val audioDir = File(context.filesDir, "audio")
            if (!audioDir.exists()) {
                audioDir.mkdirs()
            }

            val audioFile = File(audioDir, "voice_${messageId}.3gp")
            FileOutputStream(audioFile).use { fos ->
                fos.write(audioBytes)
            }

            Log.d(TAG, "Audio decoded and saved: ${audioFile.absolutePath}, size: ${audioFile.length()} bytes")
            audioFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio: ${e.message}", e)
            null
        }
    }

    /**
     * Split base64 string into chunks that fit in SMS
     * Returns list of chunks
     */
    fun splitBase64ForSms(base64: String): List<String> {
        val chunks = mutableListOf<String>()
        var index = 0

        while (index < base64.length) {
            val chunk = base64.substring(index, minOf(index + MAX_CONTENT_CHARS, base64.length))
            chunks.add(chunk)
            index += MAX_CONTENT_CHARS
        }

        return chunks
    }

    /**
     * Play audio file
     */
    suspend fun playAudio(audioFilePath: String, onCompletion: () -> Unit = {}): MediaPlayer? = withContext(Dispatchers.IO) {
        try {
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(audioFilePath)
            mediaPlayer.prepare()
            mediaPlayer.setOnCompletionListener {
                it.release()
                onCompletion()
            }
            mediaPlayer.start()
            mediaPlayer
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio: ${e.message}", e)
            null
        }
    }

    /**
     * Get audio duration in milliseconds
     */
    suspend fun getAudioDuration(audioFilePath: String): Int = withContext(Dispatchers.IO) {
        try {
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(audioFilePath)
            mediaPlayer.prepare()
            val duration = mediaPlayer.duration
            mediaPlayer.release()
            duration
        } catch (e: Exception) {
            Log.e(TAG, "Error getting audio duration: ${e.message}", e)
            0
        }
    }

    /**
     * Format duration in seconds to MM:SS format
     */
    fun formatDuration(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%d:%02d", mins, secs)
    }
}

