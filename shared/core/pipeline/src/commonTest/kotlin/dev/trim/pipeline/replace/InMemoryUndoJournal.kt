package dev.trim.pipeline.replace

import dev.trim.model.UndoEntry
import dev.trim.model.VideoId

internal class InMemoryUndoJournal : UndoJournal {
    val entries: LinkedHashMap<String, UndoEntry> = LinkedHashMap()
    var failNextWrite: String? = null
    var failForget: Boolean = false

    override suspend fun record(entry: UndoEntry): UndoWriteResult {
        failNextWrite?.let {
            failNextWrite = null
            return UndoWriteResult.Failed(it)
        }
        entries[entry.videoId.value] = entry
        return UndoWriteResult.Written
    }

    override suspend fun forget(videoId: VideoId): Boolean {
        if (failForget) return false
        return entries.remove(videoId.value) != null
    }

    fun snapshot(): Map<String, UndoEntry> = LinkedHashMap(entries)
}
