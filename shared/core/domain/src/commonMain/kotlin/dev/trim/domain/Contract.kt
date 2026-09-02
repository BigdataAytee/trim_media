package dev.trim.domain

import dev.trim.model.CompressPhase
import dev.trim.model.EstimateRange
import dev.trim.model.FolderId
import dev.trim.model.HistoryEntry
import dev.trim.model.OriginalFate
import dev.trim.model.RestoreResult
import dev.trim.model.SkippedEntry
import dev.trim.model.TrimSettings
import dev.trim.model.VideoId
import dev.trim.data.CandidateRow
import dev.trim.data.SkippedRow
import kotlinx.coroutines.flow.Flow

/**
 * The entire interface the frontend consumes (frontend-architecture §8). Screens talk to
 * these and to nothing else — no pipeline, no database, no platform API.
 *
 * They are declared as interfaces rather than classes so that Milestone 5's ViewModels can
 * be unit-tested against fakes without an emulator, which is the whole reason ~95% of
 * frontend tests will run on the JVM.
 */
public interface ObserveCandidates {
    public operator fun invoke(): Flow<CandidateSnapshot>
}

public interface CompressNow {
    public operator fun invoke(ids: List<VideoId>): Flow<CompressPhase>
}

public interface QueueForNight {
    public suspend operator fun invoke(ids: List<VideoId>)
}

public interface SetFolderMode {
    public suspend operator fun invoke(folder: FolderId, mode: OriginalFate)
}

public interface RestoreOriginal {
    public suspend operator fun invoke(id: VideoId): RestoreResult
}

public interface ObserveHistory {
    public operator fun invoke(): Flow<HistorySnapshot>
}

public interface ObserveSettings {
    public operator fun invoke(): Flow<TrimSettings>
}

public interface UpdateSettings {
    public suspend operator fun invoke(settings: TrimSettings)
}

/**
 * What the Hub renders. The freeable total is an [EstimateRange] and not a number, so the
 * headline can only ever say "about" — the honesty is in the type, not in the copy
 * (frontend-architecture §1, §4.2).
 */
public data class CandidateSnapshot(
    val shrinkable: List<CandidateRow>,
    val notShrinkable: List<SkippedRow>,
    val totalFreeable: EstimateRange,
)

public data class HistorySnapshot(
    val completed: List<HistoryEntry>,
    val skipped: List<SkippedEntry>,
    val lifetimeSavedBytes: Long,
)
