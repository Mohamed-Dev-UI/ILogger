package com.mohamed.dev.ui.ilogger.logging

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class AuditFileInfo(val name: String, val bytes: Long)

data class AuditFileSnapshot(
    val directory: String,
    val path: String,
    val files: List<AuditFileInfo> = emptyList(),
    val tail: List<String> = emptyList(),
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
}

/**
 * Application-owned directory for the category-routed JSONL sink. TextFileSink
 * creates parents and rotates `<name>.1`, `<name>.2`, ... itself; this class is
 * only the UI's read side.
 */
@Singleton
class AuditFileStore @Inject constructor(@ApplicationContext context: Context) {

    val directory: File = File(context.filesDir, "audit")
    val file: File = File(directory, "audit.jsonl")

    fun snapshot(tailLines: Int = 60): AuditFileSnapshot {
        val files = directory.listFiles()
            .orEmpty()
            .filter { it.isFile }
            .sortedByDescending { it.name == file.name }
            .sortedBy { rotationIndex(it) }
            .map { AuditFileInfo(it.name, it.length()) }
        val tail = runCatching {
            if (file.exists()) file.readLines().takeLast(tailLines) else emptyList()
        }.getOrElse { emptyList() }
        return AuditFileSnapshot(
            directory = directory.absolutePath,
            path = file.absolutePath,
            files = files,
            tail = tail,
        )
    }

    private fun rotationIndex(candidate: File): Int {
        if (candidate.name == file.name) return 0
        return candidate.name.removePrefix("${file.name}.").toIntOrNull() ?: Int.MAX_VALUE
    }
}
