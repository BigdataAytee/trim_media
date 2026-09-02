package trim.guards

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * A guard from app-architecture §8 that is not implementable yet because the code it
 * polices does not exist in this milestone. It is registered — with a name, a milestone,
 * and a loud failure — so that it cannot be quietly forgotten. It is deliberately NOT
 * wired into `check`; running it is how you find out it is still a stub.
 */
abstract class PendingGuardTask : DefaultTask() {

    @get:Input
    abstract val guardName: Property<String>

    @get:Input
    abstract val milestone: Property<String>

    @get:Input
    abstract val rationale: Property<String>

    @TaskAction
    fun fail() {
        throw GradleException(
            """
            TODO(${milestone.get()}): guard "${guardName.get()}" is not implemented.

            ${rationale.get()}

            This task exists so the guard cannot be forgotten. It fails on purpose.
            Implement it in ${milestone.get()}, then wire it into `check` alongside
            guardStorageWrites and delete this registration.
            """.trimIndent()
        )
    }
}
