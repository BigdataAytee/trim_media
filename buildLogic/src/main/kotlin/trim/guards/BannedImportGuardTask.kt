package trim.guards

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * A guard that bans a set of imports and symbols from every source set.
 *
 * Guards #1 and #2 of app-architecture §8 both have this shape: a list of things that must
 * not be referenced anywhere, plus an allow-list of the one place that legitimately may.
 * Like every guard in §8 it **fails when it finds nothing to scan**, so a renamed module
 * cannot turn it into a no-op that reports success.
 */
abstract class BannedImportGuardTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scannedSources: ConfigurableFileCollection

    /** Import prefixes that may not appear. A prefix bans the whole subtree beneath it. */
    @get:Input
    abstract val bannedImportPrefixes: ListProperty<String>

    /** Bare symbols that may not appear anywhere in a line, import or not. */
    @get:Input
    abstract val bannedSymbols: ListProperty<String>

    @get:Input
    abstract val allowedPathFragments: ListProperty<String>

    /** What the user is told when the guard fires. Names the invariant, not the rule. */
    @get:Input
    abstract val rationale: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val prefixes = bannedImportPrefixes.get()
        val symbols = bannedSymbols.get()
        if (prefixes.isEmpty() && symbols.isEmpty()) {
            throw GradleException(
                "${name} has nothing on its ban list, so it can never fail. A guard that " +
                    "cannot fail is not a guard (app-architecture §8)."
            )
        }

        val files = scannedSources.files.filter { it.isFile && it.extension == "kt" }
        if (files.isEmpty()) {
            throw GradleException(
                "$name found no Kotlin sources to scan. A guard that silently passes is a " +
                    "guard that silently died (app-architecture §8)."
            )
        }

        val allowed = allowedPathFragments.get().map { it.replace('/', java.io.File.separatorChar) }
        val violations = mutableListOf<String>()
        var policed = 0

        for (file in files) {
            if (allowed.any { file.absolutePath.contains(it) }) continue
            policed++
            file.readLines().forEachIndexed { index, rawLine ->
                val line = rawLine.substringBefore("//").trim()
                if (line.isEmpty()) return@forEachIndexed
                if (line.startsWith("import ")) {
                    val imported = line.removePrefix("import ").substringBefore(" as ").trim()
                    prefixes.firstOrNull { imported == it || imported.startsWith("$it.") }
                        ?.let { banned ->
                            violations += "${file.path}:${index + 1}: imports $imported " +
                                "(banned: $banned)"
                        }
                }
                symbols.firstOrNull { line.contains(it) }?.let { banned ->
                    violations += "${file.path}:${index + 1}: references $banned"
                }
            }
        }

        if (policed == 0) {
            throw GradleException(
                "$name scanned ${files.size} files and every one was on the allow-list, so " +
                    "nothing was policed. Check allowedPathFragments."
            )
        }

        report.get().asFile.apply { parentFile.mkdirs() }.writeText(
            buildString {
                appendLine(name)
                appendLine("  banned import prefixes: ${prefixes.joinToString(", ")}")
                appendLine("  banned symbols: ${symbols.joinToString(", ").ifEmpty { "(none)" }}")
                appendLine("  kotlin sources considered: ${files.size}")
                appendLine("  sources policed: $policed")
                appendLine("  violations: ${violations.size}")
                violations.forEach { appendLine("    $it") }
            }
        )

        if (violations.isNotEmpty()) {
            throw GradleException(
                "${rationale.get()}\n\n${violations.size} violation(s):\n" +
                    violations.joinToString("\n") { "  $it" }
            )
        }
        logger.lifecycle(
            "$name: ${prefixes.size + symbols.size} banned reference(s), $policed source " +
                "file(s) policed, no violations."
        )
    }
}
