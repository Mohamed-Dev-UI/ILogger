# WorkManager is a compile-only dependency: consumers that do not use
# LogShippingWorker do not put androidx.work on the runtime classpath. Its
# absence is expected, not an error. Apps that ship logs include
# work-runtime-ktx, whose own consumer rules keep Worker subclasses.
-dontwarn androidx.work.**
