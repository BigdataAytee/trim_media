package trim.guards

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.file.RegularFileProperty

/**
 * Build guard #3 (app-architecture §8): user storage is written ONLY by the Replacer.
 *
 * The authoritative list of write-capable members is not maintained here — it is
 * derived by reading the `@StorageWrite` annotations off the `Storage` port. Adding a
 * new write method to the port therefore extends the guard automatically; there is no
 * second list to forget to update.
 *
 * Per §8 the task **fails when it finds nothing to scan**: a guard that silently passes
 * is a guard that silently died.
 */
abstract class StorageWriteGuardTask : DefaultTask() {

    /** Sources of the `core/ports` module, read to discover `@StorageWrite` members. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val portSources: ConfigurableFileCollection

    /** Every production Kotlin source in the build, the population being policed. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scannedSources: ConfigurableFileCollection

    /**
     * Path fragments permitted to call write-capable storage members: the Replacer's own
     * package, and the port declaration itself.
     */
    @get:Input
    abstract val allowedPathFragments: ListProperty<String>

    @get:Input
    abstract val annotationName: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val annotation = annotationName.get()
        val writeMembers = discoverWriteMembers(annotation)
        if (writeMembers.isEmpty()) {
            throw GradleException(
                "guardStorageWrites found no @$annotation members in the ports sources. " +
                    "Either the Storage port lost its annotations or this guard is pointed at " +
                    "the wrong directory. A guard with nothing to enforce is a dead guard."
            )
        }

        val files = scannedSources.files.filter { it.isFile && it.extension == "kt" }
        if (files.isEmpty()) {
            throw GradleException(
                "guardStorageWrites found no Kotlin sources to scan. A guard that silently " +
                    "passes is a guard that silently died (app-architecture §8)."
            )
        }

        val allowed = allowedPathFragments.get().map { it.replace('/', java.io.File.separatorChar) }
        val violations = mutableListOf<String>()
        var policedFiles = 0

        for (file in files) {
            val path = file.absolutePath
            if (allowed.any { path.contains(it) }) continue
            policedFiles++
            file.readLines().forEachIndexed { index, rawLine ->
                val line = rawLine.substringBefore("//")
                for (member in writeMembers) {
                    if (CALL_REGEX(member).containsMatchIn(line)) {
                        violations += "${file.path}:${index + 1}: calls Storage.$member — " +
                            "only the Replacer may write to user storage"
                    }
                }
            }
        }

        if (policedFiles == 0) {
            throw GradleException(
                "guardStorageWrites scanned ${files.size} files but every one of them was on the " +
                    "allow-list, so nothing was actually policed. Check allowedPathFragments."
            )
        }

        val summary = buildString {
            appendLine("guardStorageWrites")
            appendLine("  write-capable Storage members (@$annotation): ${writeMembers.sorted().joinToString(", ")}")
            appendLine("  kotlin sources considered: ${files.size}")
            appendLine("  sources policed (allow-list removed): $policedFiles")
            appendLine("  violations: ${violations.size}")
            violations.forEach { appendLine("    $it") }
        }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(summary)

        if (violations.isNotEmpty()) {
            throw GradleException(
                "User storage may be written only by the Replacer (CLAUDE.md invariant, " +
                    "app-architecture §6/§8). ${violations.size} violation(s):\n" +
                    violations.joinToString("\n") { "  $it" }
            )
        }
        logger.lifecycle(
            "guardStorageWrites: ${writeMembers.size} write-capable member(s), " +
                "$policedFiles source file(s) policed, no violations."
        )
    }

    private fun discoverWriteMembers(annotation: String): Set<String> {
        val members = mutableSetOf<String>()
        for (file in portSources.files.filter { it.isFile && it.extension == "kt" }) {
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (!line.trim().startsWith("@$annotation")) return@forEachIndexed
                // The annotated declaration is the next line that declares a function.
                for (next in (index + 1)..minOf(index + 4, lines.lastIndex)) {
                    val match = DECLARATION_REGEX.find(lines[next])
                    if (match != null) {
                        members += match.groupValues[1]
                        break
                    }
                }
            }
        }
        return members
    }

    private companion object {
        val DECLARATION_REGEX = Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        fun CALL_REGEX(member: String) = Regex("""\.\s*$member\s*\(""")
    }
}
