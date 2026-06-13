package ru.transora.app.notifications

import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

data class TtsResult(
    val storagePath: String,
    val contentType: String,
)

interface TtsService {
    fun synthesize(text: String, cacheKey: String): TtsResult
}

@Service
class MockTtsService(
    private val properties: NotificationProperties,
) : TtsService {
    override fun synthesize(text: String, cacheKey: String): TtsResult {
        val hash = sha256(cacheKey)
        val directory = Path.of(properties.storagePath)
        Files.createDirectories(directory)
        val filePath = directory.resolve("$hash.wav")
        if (!Files.exists(filePath)) {
            Files.write(filePath, buildMinimalWav(text))
        }
        return TtsResult(
            storagePath = filePath.toAbsolutePath().toString(),
            contentType = "audio/wav",
        )
    }

    private fun buildMinimalWav(text: String): ByteArray {
        val sampleRate = 8000
        val durationSec = (text.length.coerceIn(1, 120) / 10).coerceAtLeast(1)
        val numSamples = sampleRate * durationSec
        val dataSize = numSamples * 2
        val buffer = java.nio.ByteBuffer.allocate(44 + dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        repeat(numSamples) { buffer.putShort(0) }
        return buffer.array()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return HexFormat.of().formatHex(digest)
    }
}
