package io.ilogger.annotations

/**
 * Opts a function out of instrumentation: the processor skips it entirely —
 * no wrapper override, no event, no diagnostics. Applied to a function that
 * also carries [Log], `@NoLog` wins (useful to silence inherited annotation
 * combinations without editing the interface).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class NoLog
