package dev.trim.ports.contract

/**
 * # Port contracts
 *
 * app-architecture §11 states the rule that makes 75% code sharing honest:
 *
 * > one shared test suite per port interface, run against both the fake and the real
 * > implementation on-device. **If the fake and the real implementation both pass the same
 * > suite, tests against fakes are evidence, not comfort.**
 *
 * These classes are that suite. They live in `commonMain` rather than a test source set
 * because two different modules must run them — `core/ports-fake`'s JVM tests today, and
 * `androidApp`'s instrumented tests once the real implementations exist. A suite only one
 * module can see is not a contract; it is a test.
 *
 * ## Why cases and not `@Test`
 *
 * `kotlin.test.Test` exists only in test compilations, so a suite that must be visible
 * from `commonMain` cannot carry the annotation. Each contract therefore exposes its
 * clauses as a list of [ContractCase]s. A module runs them with [verifyAll], which reports
 * **every** failing clause rather than stopping at the first — "your Storage fails these
 * three clauses" is a more useful thing to be told than "your Storage fails".
 * A test framework that prefers one test per clause (a parameterised instrumented runner,
 * say) can iterate `cases()` itself.
 *
 * ## What belongs in a contract
 *
 * Only behaviour the **pipeline depends on** — not everything an implementation happens to
 * do. Two clauses show the difference, and both are load-bearing:
 *
 * - [CodecContract] requires that a more aggressive setting never produces a *larger*
 *   encode, and [ScorerContract] that it never produces a *higher* score. Those are the
 *   monotonicity preconditions the binary search rests on (DECISIONS D4.3). Until this
 *   suite existed they were an assumption about hardware; now they are a checked property
 *   of every implementation, fake or real.
 * - [StorageContract] runs the Replacer's six-step sequence against the port itself. The
 *   kill-tests in `core/pipeline` prove the *sequence* is right; this proves the *storage
 *   underneath it* behaves the way the sequence assumes.
 */
/**
 * A world an implementation builds for one clause: whatever it needs to be exercised, plus
 * a way to release it. Fresh per clause, so no clause can leak state into the next.
 */
public interface PortFixture {
    /** Called after each clause. Implementations owning real resources release them here. */
    public suspend fun tearDown() {}
}

public class ContractCase(
    public val name: String,
    public val run: suspend () -> Unit,
)

/** Sugar so a contract body reads as a list of clauses. */
public fun case(name: String, run: suspend () -> Unit): ContractCase = ContractCase(name, run)

/**
 * Runs every clause and fails once, listing all of them. [subject] names the implementation
 * under test so a failure says which one broke the contract.
 */
public suspend fun List<ContractCase>.verifyAll(subject: String) {
    val failures = mutableListOf<Pair<String, Throwable>>()
    for (clause in this) {
        try {
            clause.run()
        } catch (failure: AssertionError) {
            failures += clause.name to failure
        } catch (failure: Exception) {
            failures += clause.name to failure
        }
    }
    if (failures.isEmpty()) return
    throw AssertionError(
        buildString {
            appendLine("$subject fails ${failures.size} of $size contract clause(s):")
            failures.forEach { (name, failure) ->
                appendLine("  • $name")
                appendLine("      ${failure.message?.replace("\n", "\n      ")}")
            }
        },
    )
}
