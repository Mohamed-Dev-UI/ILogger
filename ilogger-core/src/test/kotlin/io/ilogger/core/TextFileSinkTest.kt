package io.ilogger.core

import io.ilogger.annotations.LogLevel
import io.ilogger.core.crypto.AesGcmCipher
import io.ilogger.core.crypto.EncryptionKey
import io.ilogger.core.crypto.EncryptionKeyProvider
import io.ilogger.core.sink.EventCodec
import io.ilogger.core.sink.TextFileSink
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextFileSinkTest {

    @get:Rule
    public val temporaryFolder: TemporaryFolder = TemporaryFolder()

    /** Encodes only the event id: line length is exactly `id.length + 1` bytes. */
    private val idCodec = EventCodec { event -> event.id }

    private fun event(id: String): LogEvent =
        LogEvent(id = id, timestampMillis = 0, level = LogLevel.INFO, action = "test.event")

    private fun activeFile(name: String = "audit.log"): Path = temporaryFolder.root.toPath().resolve(name)

    private fun archives(name: String = "audit.log"): List<Path> = Files.list(temporaryFolder.root.toPath()).use { stream ->
        stream.filter { path -> path.fileName.toString().startsWith("$name.") }
            .sorted()
            .toList()
    }

    private fun lines(path: Path): List<String> = Files.readAllLines(path)

    private fun writeIds(sink: TextFileSink, ids: Iterable<String>) {
        for (id in ids) sink.writeDirect(event(id))
    }

    /** Fixed-width ids keep the encoded line length (and therefore rotation) deterministic. */
    private fun ids(range: IntRange): List<String> = range.map { it.toString().padStart(2, '0') }

    @Test
    fun `maxFiles one keeps exactly one archive and never throws across rolls`() {
        val file = activeFile()
        val sink = TextFileSink(
            file,
            codec = idCodec,
            rotation = TextFileSink.Rotation(maxBytesPerFile = 6, maxFiles = 1),
        )
        sink.use { writeIds(it, ids(1..8)) }

        assertEquals(listOf(file.resolveSibling("audit.log.1")), archives())
        assertEquals(listOf("05", "06"), lines(file.resolveSibling("audit.log.1")))
        assertEquals(listOf("07", "08"), lines(file))
    }

    @Test
    fun `maxFiles two and five retain newest archives in order`() {
        val file = activeFile()
        TextFileSink(
            file,
            codec = idCodec,
            rotation = TextFileSink.Rotation(maxBytesPerFile = 6, maxFiles = 2),
        ).use { writeIds(it, ids(1..6)) }

        assertEquals(listOf("03", "04"), lines(file.resolveSibling("audit.log.1")))
        assertEquals(listOf("01", "02"), lines(file.resolveSibling("audit.log.2")))
        assertEquals(listOf("05", "06"), lines(file))

        val second = activeFile("five.log")
        TextFileSink(
            second,
            codec = idCodec,
            rotation = TextFileSink.Rotation(maxBytesPerFile = 6, maxFiles = 5),
        ).use { writeIds(it, ids(1..20)) }

        val fiveArchives = archives("five.log")
        assertEquals(5, fiveArchives.size)
        assertEquals(listOf("17", "18"), lines(second.resolveSibling("five.log.1")))
        assertFalse(Files.exists(second.resolveSibling("five.log.6")))
    }

    @Test
    fun `rotation succeeds when target archive already exists from a previous process`() {
        val file = activeFile()
        Files.createFile(file.resolveSibling("audit.log.1"))
        Files.createFile(file.resolveSibling("audit.log.2"))
        Files.writeString(file.resolveSibling("audit.log.1"), "stale-1\n")
        Files.writeString(file.resolveSibling("audit.log.2"), "stale-2\n")

        TextFileSink(
            file,
            codec = idCodec,
            rotation = TextFileSink.Rotation(maxBytesPerFile = 6, maxFiles = 2),
        ).use { writeIds(it, ids(1..6)) }

        assertEquals(listOf("03", "04"), lines(file.resolveSibling("audit.log.1")))
        assertEquals(listOf("01", "02"), lines(file.resolveSibling("audit.log.2")))
        assertEquals(listOf("05", "06"), lines(file))
    }

    @Test
    fun `restart with existing archives keeps retention bounded`() {
        val file = activeFile()
        val rotation = TextFileSink.Rotation(maxBytesPerFile = 6, maxFiles = 2)
        TextFileSink(file, codec = idCodec, rotation = rotation).use { writeIds(it, ids(1..6)) }
        TextFileSink(file, codec = idCodec, rotation = rotation).use { writeIds(it, ids(7..12)) }

        assertTrue(archives().size <= 2)
        assertTrue(Files.exists(file))
        assertEquals(listOf("09", "10"), lines(file.resolveSibling("audit.log.1")))
        assertEquals(listOf("11", "12"), lines(file))
    }

    @Test
    fun `maxFiles zero keeps only the active file`() {
        val file = activeFile()
        TextFileSink(
            file,
            codec = idCodec,
            rotation = TextFileSink.Rotation(maxBytesPerFile = 6, maxFiles = 0),
        ).use { writeIds(it, ids(1..6)) }

        assertTrue(archives().isEmpty())
        assertEquals(listOf("05", "06"), lines(file))
    }

    @Test
    fun `many rolls never throw and active file is always writable`() {
        val file = activeFile()
        TextFileSink(
            file,
            codec = idCodec,
            rotation = TextFileSink.Rotation(maxBytesPerFile = 6, maxFiles = 3),
        ).use { sink ->
            writeIds(sink, ids(1..60))
            assertEquals(3, archives().size)
            assertEquals(listOf("59", "60"), lines(file))
            sink.writeDirect(event("61"))
            assertTrue(lines(file).contains("61"))
        }
    }

    @Test
    fun `buffered sink flushes every accepted event on close`() {
        val file = activeFile()
        TextFileSink(file, codec = idCodec, flushEveryWrite = false).use { sink ->
            writeIds(sink, listOf("a", "b", "c"))
        }
        assertEquals(listOf("a", "b", "c"), lines(file))
    }

    @Test
    fun `fsync sink writes are visible before close`() {
        val file = activeFile()
        TextFileSink(file, codec = idCodec, fsyncEveryWrite = true).use { sink ->
            writeIds(sink, listOf("durable"))
            assertEquals(listOf("durable"), lines(file))
        }
    }

    @Test
    fun `io dispatcher runs writes off the calling thread`() {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ilogger-io") }
        val dispatcher = executor.asCoroutineDispatcher()
        val file = activeFile()
        val sink = TextFileSink(
            file,
            codec = EventCodec { Thread.currentThread().name },
            ioDispatcher = dispatcher,
        )
        try {
            runBlocking { sink.write(event("thread-check")) }
            assertEquals("ilogger-io", lines(file).single().substringBefore('@').trim())
        } finally {
            sink.close()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `flush after close is a no-op and further writes fail clearly`() {
        val sink = TextFileSink(activeFile())
        sink.close()
        runBlocking { sink.flush() }
        val failure = assertThrows(IllegalStateException::class.java) { sink.writeDirect(event("late")) }
        assertEquals("TextFileSink is closed", failure.message)
    }

    @Test
    fun `time rotation rolls lazily on the next write`() {
        val file = activeFile()
        val clock = FixedClock(0)
        TextFileSink(
            file,
            codec = idCodec,
            rotation = TextFileSink.Rotation(
                maxBytesPerFile = 1_000_000,
                maxFiles = 2,
                maxAgeMillis = 1_000,
            ),
            clock = clock,
        ).use { sink ->
            sink.writeDirect(event("a"))
            clock.set(999)
            sink.writeDirect(event("b"))
            assertEquals(listOf("a", "b"), lines(file))

            clock.set(1_000)
            sink.writeDirect(event("c"))
            assertEquals(listOf("a", "b"), lines(file.resolveSibling("audit.log.1")))
            assertEquals(listOf("c"), lines(file))
        }
    }

    @Test
    fun `gzip archives are readable and shift with retention`() {
        val file = activeFile()
        TextFileSink(
            file,
            codec = idCodec,
            rotation = TextFileSink.Rotation(maxBytesPerFile = 6, maxFiles = 2, gzip = true),
        ).use { writeIds(it, ids(1..6)) }

        assertFalse(Files.exists(file.resolveSibling("audit.log.1")))
        assertEquals(listOf("03", "04"), gzipLines(file.resolveSibling("audit.log.1.gz")))
        assertEquals(listOf("01", "02"), gzipLines(file.resolveSibling("audit.log.2.gz")))
        assertEquals(listOf("05", "06"), lines(file))
    }

    @Test
    fun `maxTotalBytes deletes oldest archives but keeps the active file`() {
        val file = activeFile()
        TextFileSink(
            file,
            codec = idCodec,
            rotation = TextFileSink.Rotation(maxBytesPerFile = 6, maxFiles = 5, maxTotalBytes = 12),
        ).use { writeIds(it, ids(1..8)) }

        assertTrue(Files.exists(file))
        assertTrue(Files.exists(file.resolveSibling("audit.log.1")))
        assertFalse(archives().toString(), Files.exists(file.resolveSibling("audit.log.2")))
        assertEquals(listOf("07", "08"), lines(file))
    }

    @Test
    fun `suspend writes leave the caller thread and writeDirect stays synchronous`() = runBlocking {
        val file = activeFile("threads.log")
        val threads = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        val sink = TextFileSink(
            file,
            codec = EventCodec { event ->
                threads += Thread.currentThread()
                event.id
            },
        )
        val caller = Thread.currentThread()
        sink.write(event("a"))
        assertEquals(1, threads.size)
        assertFalse("write() ran on the caller thread", caller === threads[0])

        sink.writeDirect(event("b"))
        assertTrue("writeDirect() must run on the caller thread", Thread.currentThread() === threads[1])
        sink.close()
    }

    @Test
    fun `concurrent suspend writes are serialized on the sink IO dispatcher`() = runBlocking {
        val file = activeFile("concurrent.log")
        val active = java.util.concurrent.atomic.AtomicInteger()
        val maxActive = java.util.concurrent.atomic.AtomicInteger()
        val sink = TextFileSink(
            file,
            codec = EventCodec { event ->
                val inFlight = active.incrementAndGet()
                maxActive.accumulateAndGet(inFlight) { current, candidate -> maxOf(current, candidate) }
                Thread.sleep(1)
                active.decrementAndGet()
                event.id
            },
        )
        kotlinx.coroutines.coroutineScope {
            repeat(64) { index ->
                launch(Dispatchers.Default) {
                    sink.write(event(index.toString()))
                }
            }
        }
        assertEquals("writes overlapped on the sink IO dispatcher", 1, maxActive.get())
        assertEquals(0, active.get())
        assertEquals(64, lines(file).size)
        sink.close()
    }

    private val secretKey = EncryptionKey("k1", ByteArray(32) { 3 })

    @Test
    fun `encrypted sink stores no plaintext and decrypts back`() {
        val file = activeFile("enc.log")
        val cipher = AesGcmCipher(secretKey)
        TextFileSink(file, codec = idCodec, cipher = cipher).use { writeIds(it, ids(1..4)) }

        val raw = lines(file)
        assertEquals(4, raw.size)
        raw.forEach { line -> assertTrue(line, line.startsWith("ilogenc1:k1:")) }
        raw.forEachIndexed { index, line ->
            assertEquals(ids(1..4)[index], cipher.decrypt(line))
        }
    }

    @Test
    fun `encrypted sink rotates keys mid-stream and a keyring reads both`() {
        val file = activeFile("rot.log")
        val key1 = EncryptionKey("k1", ByteArray(32) { 1 })
        val key2 = EncryptionKey("k2", ByteArray(32) { 2 })
        val current = AtomicReference(key1)
        val cipher = AesGcmCipher(EncryptionKeyProvider { current.get() })
        TextFileSink(file, codec = idCodec, cipher = cipher).use { sink ->
            writeIds(sink, listOf("01", "02"))
            current.set(key2)
            writeIds(sink, listOf("03", "04"))
        }

        val raw = lines(file)
        assertEquals(
            listOf("ilogenc1:k1", "ilogenc1:k1", "ilogenc1:k2", "ilogenc1:k2"),
            raw.map { it.substringBeforeLast(':') },
        )

        val keyring = AesGcmCipher({ key1 }, mapOf("k1" to key1.bytes, "k2" to key2.bytes))
        assertEquals(listOf("01", "02", "03", "04"), raw.map { keyring.decrypt(it) })

        val oldOnly = AesGcmCipher(key1)
        assertEquals(listOf("01", "02"), raw.take(2).map { oldOnly.decrypt(it) })
        assertThrows(IllegalStateException::class.java) { oldOnly.decrypt(raw[2]) }
    }

    @Test
    fun `tampering with an encrypted file breaks decryption`() {
        val file = activeFile("tamper.log")
        val cipher = AesGcmCipher(secretKey)
        TextFileSink(file, codec = idCodec, cipher = cipher).use { writeIds(it, ids(1..2)) }

        val line = lines(file)[0]
        val tampered = StringBuilder(line).apply {
            val index = length - 3
            setCharAt(index, if (get(index) == 'A') 'B' else 'A')
        }.toString()

        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(tampered) }
    }

    private fun gzipLines(path: Path): List<String> =
        GZIPInputStream(Files.newInputStream(path)).bufferedReader().use { reader -> reader.readLines() }
}
