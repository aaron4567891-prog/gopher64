package io.github.gopher64.gopher64

import java.util.Locale

internal object CoverNames {
    fun isRom(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("n64", "z64", "v64", "bin", "zip", "7z")
    private fun key(name: String, stripTags: Boolean): String {
        var stem = name.substringBeforeLast('.')
        if (stripTags) stem = stem.replace(Regex("\\([^)]*\\)|\\[[^]]*\\]"), "")
        return stem.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    }
    fun match(rom: String, covers: List<String>): String? {
        covers.firstOrNull { key(it, false) == key(rom, false) }?.let { return it }
        val candidates = covers.filter { key(it, true) == key(rom, true) }
        return candidates.sortedWith(compareBy<String> { !it.contains("(USA") }.thenBy { it }).firstOrNull()
    }
}
