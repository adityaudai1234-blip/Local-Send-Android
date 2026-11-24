package com.example.data.transfer

enum class ConflictAction(val label: String) {
    REPLACE("Replace"),
    SKIP("Skip"),
    RENAME("Rename"),
    KEEP_BOTH("Keep Both")
}

data class ConflictPrompt(
    val fileId: String,
    val fileName: String,
    val incomingSize: Long,
    val existingSize: Long,
    val existingLastModified: Long,
    val existingPath: String,
    val remainingConflictCount: Int = 0
)

data class ConflictDecision(
    val action: ConflictAction,
    val applyToAll: Boolean = false
)
