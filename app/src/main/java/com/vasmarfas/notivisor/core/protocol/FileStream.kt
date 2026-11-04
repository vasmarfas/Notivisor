package com.vasmarfas.notivisor.core.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class FileStream(private val key: SecretKey, transferId: String) {

    private val random = SecureRandom()
    private val context = "Notivisor/file/$transferId".toByteArray(Charsets.UTF_8)

    fun send(source: InputStream, target: OutputStream, onProgress: (Long) -> Unit = {}) {
        val output = DataOutputStream(BufferedOutputStream(target, MAX_FRAME_BYTES + HEADER_BYTES))
        val buffer = ByteArray(CHUNK_BYTES)
        var index = 0L
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) throw IOException("file is over the limit")
            writeFrame(output, buffer, read, index++, last = false)
            onProgress(total)
        }
        writeFrame(output, buffer, 0, index, last = true)
    }

    fun receive(source: InputStream, target: OutputStream, onProgress: (Long) -> Unit = {}): Long {
        val input = DataInputStream(BufferedInputStream(source))
        var index = 0L
        var total = 0L
        while (true) {
            val last = input.readBoolean()
            val length = input.readInt()
            if (length !in MIN_FRAME_BYTES..MAX_FRAME_BYTES) {
                throw IOException("bad frame length $length")
            }
            val frame = ByteArray(length).also { input.readFully(it) }
            val plain = open(frame, index++, last)
            if (last) return total
            total += plain.size
            if (total > MAX_BYTES) throw IOException("file is over the limit")
            target.write(plain)
            onProgress(total)
        }
    }

    private fun writeFrame(
        output: DataOutputStream,
        chunk: ByteArray,
        length: Int,
        index: Long,
        last: Boolean,
    ) {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(frameContext(index, last))
        val sealed = cipher.doFinal(chunk, 0, length)
        output.writeBoolean(last)
        output.writeInt(iv.size + sealed.size)
        output.write(iv)
        output.write(sealed)
        output.flush()
    }

    private fun open(frame: ByteArray, index: Long, last: Boolean): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, frame, 0, IV_BYTES))
        cipher.updateAAD(frameContext(index, last))
        return cipher.doFinal(frame, IV_BYTES, frame.size - IV_BYTES)
    }

    private fun frameContext(index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(context.size + Long.SIZE_BYTES + 1)
            .put(context)
            .putLong(index)
            .put(if (last) 1 else 0)
            .array()

    companion object {
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024

        const val READY = 1
        const val SAVED = 2
        const val NO_SPACE = 3
        const val UNSUPPORTED = 4

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val CHUNK_BYTES = 64 * 1024
        private const val HEADER_BYTES = 5
        private const val MIN_FRAME_BYTES = IV_BYTES + TAG_BYTES
        private const val MAX_FRAME_BYTES = IV_BYTES + CHUNK_BYTES + TAG_BYTES

        private const val MAX_NAME_CHARS = 120
        private const val DEFAULT_NAME = "file"
        private const val DEFAULT_MIME = "application/octet-stream"
        private val MIME = Regex("""[\w.+-]+/[\w.+-]+""")

        fun safeName(raw: String?): String =
            raw.orEmpty()
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .filterNot { it.isISOControl() }
                .trim()
                .trimStart('.')
                .takeLast(MAX_NAME_CHARS)
                .ifEmpty { DEFAULT_NAME }

        fun safeMime(raw: String?): String = raw?.takeIf(MIME::matches) ?: DEFAULT_MIME
    }
}
