# 28. Android module (`ilogger-android`)

`ilogger-android` packages the Android-specific behavior every app repeats:
Logcat output, process-background flushes, a StrictMode-safe file sink, a
crash-handoff buffer, a session-aware actor provider and optional WorkManager
shipping. The reference app uses this module instead of app-local bridges.

```kotlin
dependencies {
    implementation(platform("io.github.mohamed-dev-ui:ilogger-bom:2.0.0"))
    implementation("io.github.mohamed-dev-ui:ilogger-android")
    // Optional: only for LogShippingWorker
    implementation("androidx.work:work-runtime-ktx:2.11.2")
}
```

The module requires `minSdk 28` and exposes `ilogger-core` plus
`androidx.lifecycle:lifecycle-process` as API dependencies. WorkManager is
**compile-only**: apps that never ship logs remotely do not depend on it.

## One-line setup

```kotlin
class App : Application() {
    lateinit var logging: AndroidLogger

    override fun onCreate() {
        super.onCreate()
        logging = ILoggerAndroid.install(this) {
            logcat(category = "diagnostics")
            file(name = "audit.jsonl", category = "audit")
            logger {
                strictMode = StrictMode.WARN
                dispatch = DispatchMode.BUFFERED
            }
        }
    }
}
```

`install` builds the logger, makes it `ILogger.default`, binds the
`CrashBuffer`, installs the crash handler, and attaches the lifecycle
observer. It must run on the main thread (`ProcessLifecycleOwner`).

Defaults you can change in the options block:

| Option | Default | Effect |
|---|---|---|
| `logcatSink` / `logcat(tag, category)` | `LogcatSink("ILogger")`, catch-all | Logcat delivery |
| `disableLogcat()` | — | Removes the Logcat sink |
| `file(...)` | none | Adds a JSONL file sink under `filesDir/ilogger` |
| `crashHandoff` | `true` | Installs `CrashHandoff` and binds `crashBuffer` |
| `crashBuffer` | 64 events | Recent-event ring written on a crash |
| `crashDirectory` | `filesDir/ilogger/crash` | Crash report destination |
| `flushOnStop` | `true` | Flushes on process `ON_STOP` |
| `setAsDefault` | `true` | Publishes `ILogger.default` |
| `actors` | `AndroidActorProvider` | Session/user actor source |

Close `AndroidLogger` to flush, remove the lifecycle observer and restore the
previous uncaught-exception handler.

## Components

### LogcatSink

- `Log.println` — never `println`, which on Android goes nowhere.
- Level mapping: TRACE→VERBOSE, DEBUG→DEBUG, INFO→INFO, WARN→WARN,
  ERROR→ERROR, AUDIT→INFO (`LogcatSink.priorityOf`).
- Default tag `ILogger`; `tagFor = { event -> event.category ?: "app" }` maps
  tags per event.
- Lines above 3,800 characters are split into multiple entries instead of
  being truncated by Logcat.
- A `DirectLogSink`: safe with `DispatchMode.INLINE`, no coroutine machinery.
- `writeLine` is injectable, which is how unit tests assert priority, tag,
  formatting and chunking without a device.

### AndroidFileSink

A rotation-capable JSONL sink that deliberately implements only `LogSink`,
not `DirectLogSink`. Every write and batch hops to a dedicated
single-threaded IO dispatcher (`ilogger-android-file`), so:

- no disk IO on the main thread, even when a caller requests `INLINE`;
- StrictMode disk-read/write violations are avoided by construction;
- batch writes share one dispatcher hop.

It delegates to `TextFileSink`, so size/time rotation, gzip archives and
`maxTotalBytes` retention behave exactly as documented in
[14-sinks.md](14-sinks.md). Use `fsyncEveryWrite = true` only for audit data
that must survive power loss.

### AndroidActorProvider

Resolves the actor when generated/manual calls do not pass one explicitly:

- the **session id** is generated once and persisted in SharedPreferences, so
  events from one install share a stable automation actor;
- `setUser(id, name, attributes)` marks a signed-in user and adds the session
  id as the `session` attribute;
- `clearUser()` returns to the session automation actor.

The user is intentionally **not persisted**: process death ends the user
session, preventing misattribution after a crash. `currentActor()` is a
volatile read, safe on the audit hot path.

### AndroidLifecycleHooks

Observes `ProcessLifecycleOwner` and calls `logger.flush()` on `ON_STOP` on an
IO dispatcher. Buffered events at the moment the user backgrounds the app are
persisted instead of dying with the process. Flush failures are contained;
`close()` detaches and cancels pending flushes.

The reference app also keeps its `onTrimMemory` flush as a second line of
defense.

### CrashBuffer and CrashHandoff

The engine is asynchronous, so a process killed by an uncaught exception
loses whatever is still queued. `CrashBuffer` is a bounded ring sink (default
64 events). `CrashHandoff` is an `UncaughtExceptionHandler` that:

1. snapshots the ring on the crashing thread;
2. appends a synthetic `android.crash` event with `LogFailure.from(error)`;
3. writes JSONL with `fd.sync()` to `crash-<timestamp>.jsonl`;
4. delegates to the previous handler so the platform crash reporter still runs.

On the next launch, read and delete pending reports:

```kotlin
val reports = crashHandoff.drain()   // List<CrashReport>; files deleted
reports.forEach { report -> ship(report.lines) }
```

`pending()` reads without deleting. Crash files are JSONL, one
`JsonEventCodec` object per line, so they can be forwarded to any durable sink.
The handoff can only persist events that already reached the buffer — bind it
catch-all, and use `INLINE` dispatch for the few events that must never be
lost.

### Optional WorkManager shipping

```kotlin
val spool = LogShippingWorker.createSpool(context)
LogShippingWorker.configure(transport = ShippingTransport { lines -> myHttp.send(lines) })

val logger = ILogger.create {
    sink(ShippingSpoolSink(spool), category = "audit")
}

LogShippingWorker.enqueue(context)   // network-constrained, unique work
```

- `ShippingSpool` is a durable JSONL queue with suspend append/peek/ack
  operations on its own IO dispatcher.
- `ShippingSpoolSink` implements only `LogSink`, so producers never touch
  disk on the main thread.
- The worker drains up to ten bounded batches per run (500 lines / 256 KB each).
  `ShippingTransport.send` returns `true` only when the receiver durably
  accepted the batch; `false` or an exception leaves the spool intact and
  WorkManager retries with exponential backoff.
- Duplicate delivery is possible after a crash between send and acknowledge;
  receivers must deduplicate by event id, which is always unique.

Add `work-runtime-ktx` yourself. Without it, the rest of the module still
works; only `LogShippingWorker` is unusable.

## Manual wiring (reference app)

The `:app` module wires the pieces through Hilt instead of `install`, because
it also needs Room and an in-app live feed:

```kotlin
sink(feed.sink)                                  // catch-all live view
sink(target)                                     // Room trail
sink(crashBuffer)                                // crash handoff source
sink(LogcatSink(tag = "ILogger"), "diagnostics")
sink(AndroidFileSink.create(auditFile, JsonEventCodec, rotation), "audit")

@Provides @Singleton fun crashHandoff(context, buffer) =
    CrashHandoff(buffer, File(context.filesDir, "ilogger/crash")).also { it.install() }

@Provides @Singleton fun lifecycleHooks(logger: ILogger) =
    AndroidLifecycleHooks(logger).attach()
```

## Testing

JVM unit tests cover `LogcatSink` mapping/chunking, the actor provider with an
in-memory `SessionStore`, the crash ring and report writer (temporary
directories), file-sink JSONL output, and spool/worker batch logic with a fake
transport. `src/androidTest` covers what needs a device: process `ON_STOP`
flush through `ProcessLifecycleOwner`, SharedPreferences session persistence,
file writes under `filesDir`, and the default Logcat writer.

Run them with:

```bash
./gradlew :ilogger-android:testDebugUnitTest
./gradlew :ilogger-android:connectedDebugAndroidTest   # device/emulator
```

## Constraints and limits

- `install` attaches `ProcessLifecycleOwner`; call it from the main thread.
- Crash handoff captures the buffer, not undispatched queue contents. Keep
  critical events inline or flush frequently.
- A crash report is written even when the crash handler chain is replaced
  later; `uninstall()` restores only the handler captured at `install()`.
- WorkManager is compile-only; the worker fails permanently if
  `LogShippingWorker.configure` was never called.
- Logcat is a diagnostic destination. Audit delivery belongs in a file, Room
  or a remote sink.
