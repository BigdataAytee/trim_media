package dev.trim.model

/**
 * Anything that can be shown to a user as "this file was not compressed, and here is why".
 *
 * The two sets underneath it are disjoint and stay disjoint: a [SkipReason] is an expected
 * skip that the pipeline decided on purpose, and a [FailureReason] is something going
 * wrong. app-architecture §10 puts both in History's skipped list, and
 * frontend-architecture §4.2 requires that neither can be constructed without plain-language
 * text — so the common supertype carries exactly that one member and nothing else.
 */
public sealed interface RejectionReason {
    /** One sentence, sentence case, no codes. */
    public val displayText: String
}
