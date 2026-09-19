package io.github.gopher64.gopher64

import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

internal object FrontendInput {
    fun source(data: String?, extras: List<String?>): String? {
        val value = data?.takeIf { it.isNotBlank() }
            ?: extras.firstOrNull { !it.isNullOrBlank() } ?: return null
        return value.takeIf { it.startsWith('/') || it.startsWith("content://", true) || it.startsWith("file://", true) }
    }

    fun extension(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension in setOf("z64", "n64", "v64", "bin", "zip", "7z"))
        return extension
    }

    fun copy(source: InputStream, destination: OutputStream, limit: Long = 512L * 1024 * 1024) {
        val buffer = ByteArray(65536)
        var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val count = source.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "ROM/archive exceeds 512 MiB" }
            destination.write(buffer, 0, count)
        }
        require(total > 0) { "Empty ROM" }
    }
}
