package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

class ShizukuTransportTest {
    private val limit = 2 * 1024 * 1024
    private val payload = ByteArray(1024 * 1024 + 317) { (it * 31).toByte() }
    private fun encoded(bytes: ByteArray = payload): ByteArray = ByteArrayOutputStream().also {
        StreamProtocol.write(it, "bounded-metadata", bytes, limit)
    }.toByteArray()
    @Test fun payloadLargerThanBinderLimitRoundTripsWithExactCountAndSha() {
        val packet = StreamProtocol.read(ByteArrayInputStream(encoded()), limit)
        assertEquals("bounded-metadata", packet.metadata)
        assertArrayEquals(payload, packet.bytes)
    }
    @Test(expected = EOFException::class) fun truncatedStreamNeverProducesAFile() {
        StreamProtocol.read(ByteArrayInputStream(encoded().dropLast(1).toByteArray()), limit)
    }
    @Test(expected = IllegalArgumentException::class) fun corruptedStreamNeverProducesAFile() {
        val bytes = encoded(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        StreamProtocol.read(ByteArrayInputStream(bytes), limit)
    }
    @Test(expected = IllegalArgumentException::class) fun trailingBytesAreRejected() {
        StreamProtocol.read(ByteArrayInputStream(encoded() + byteArrayOf(1)), limit)
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedAnnouncedPayloadIsRejectedBeforeAllocation() {
        StreamProtocol.read(ByteArrayInputStream(encoded()), 1024)
    }
    @Test(expected = IllegalArgumentException::class) fun metadataIsBounded() {
        StreamProtocol.write(ByteArrayOutputStream(), "x".repeat(StreamProtocol.MAX_METADATA + 1), byteArrayOf(1), limit)
    }
    @Test fun rawWriteRejectsTruncationWrongShaAndOversize() {
        val hash = StreamProtocol.sha(payload)
        assertArrayEquals(payload, StreamProtocol.readRaw(ByteArrayInputStream(payload), payload.size.toLong(), hash, limit))
        assertThrows(EOFException::class.java) { StreamProtocol.readRaw(ByteArrayInputStream(payload.dropLast(1).toByteArray()), payload.size.toLong(), hash, limit) }
        assertThrows(IllegalArgumentException::class.java) { StreamProtocol.readRaw(ByteArrayInputStream(payload), payload.size.toLong(), "0".repeat(64), limit) }
        assertThrows(IllegalArgumentException::class.java) { StreamProtocol.readRaw(ByteArrayInputStream(payload), Long.MAX_VALUE, hash, limit) }
    }
    @Test fun timeoutDoesNotReleaseTheActualOperationGate() {
        val gate = ShizukuOperationGate()
        gate.begin()
        assertThrows(IllegalStateException::class.java) { gate.assertIdle() }
        assertThrows(IllegalStateException::class.java) { gate.begin() }
        gate.end()
        gate.assertIdle()
        gate.begin(); gate.end()
    }
    @Test fun destroyWaitsForTheWorkerAndPreventsNewOperations() {
        val gate = ShizukuOperationGate()
        gate.begin()
        val exited = CountDownLatch(1)
        val thread = Thread { gate.stopAndAwait(); exited.countDown() }
        thread.start()
        assertFalse(exited.await(50, TimeUnit.MILLISECONDS))
        gate.end()
        assertTrue(exited.await(2, TimeUnit.SECONDS))
        assertThrows(IllegalStateException::class.java) { gate.begin() }
        thread.join()
    }
    @Test fun persistedFenceRequiresSameInstanceOrConfirmedDeviceReboot() {
        assertTrue(ShizukuWriterFence.mayCheckIdle("old", "old", 4, 4))
        assertFalse(ShizukuWriterFence.mayCheckIdle("old", "new", 4, 4))
        assertTrue(ShizukuWriterFence.mayCheckIdle("old", "new", 4, 5))
        assertFalse(ShizukuWriterFence.mayCheckIdle("old", "new", -1, 5))
        assertFalse(ShizukuWriterFence.mayCheckIdle("old", "new", 5, -1))
    }
    @Test fun statusDistinguishesAbsenceOldVersionDenialsIdentityAndConnection() {
        fun facts(running: Boolean = true, version: Int = 13, uid: Int? = 0, granted: Boolean = true,
            permanent: Boolean = false, connected: Boolean = false) = ShizukuStatus(running, version, uid, granted, permanent, connected)
        assertTrue(facts(running = false).text.contains("未运行"))
        assertTrue(facts(version = 12).text.contains("13"))
        assertTrue(facts(granted = false).canRequestPermission)
        assertTrue(facts(granted = false, permanent = true).text.contains("永久拒绝"))
        assertFalse(facts(granted = false, permanent = true).canRequestPermission)
        assertEquals(ShizukuStatus.ADB_MESSAGE, facts(uid = 2000, granted = false).text)
        assertFalse(facts(uid = 2000, granted = false).canRequestPermission)
        assertTrue(facts().text.contains("UserService 和游戏目录尚未检查"))
        assertTrue(facts(connected = true).text.contains("实际 UID=0"))
        assertTrue(facts(running = false, connected = true).text.contains("未运行"))
    }
    @Test fun malformedResponseCannotBeMistakenForAConfirmedFailure() {
        val definite = ShizukuWire.failure(AccessFailure(AccessFailureKind.COMMAND_FAILED, "definite"), null)
        val parsed = Json.parseToJsonElement(definite).jsonObject
        val missingUncertainty = JsonObject(parsed.filterKeys { it != "uncertain" }).toString()
        assertThrows(NoSuchElementException::class.java) { ShizukuWire.decode(missingUncertainty) }
        val failed = assertThrows(AccessFailure::class.java) { ShizukuWire.decode(definite) }
        assertFalse(failed.uncertainWrite)
        val unknown = ShizukuWire.failure(AccessFailure(AccessFailureKind.TIMEOUT, "unknown", uncertainWrite = true), null)
        assertTrue(assertThrows(AccessFailure::class.java) { ShizukuWire.decode(unknown) }.uncertainWrite)
        assertTrue(ShizukuWire.decode(ShizukuWire.success(JsonPrimitive(true), null)).value.jsonPrimitive.boolean)
    }
    @Test fun privilegedArgumentsAndDiagnosticBodiesAreBoundedAndSanitized() {
        assertThrows(IllegalArgumentException::class.java) { ShizukuWire.target("com.target;id", 0) }
        assertThrows(IllegalArgumentException::class.java) { ShizukuWire.target("com.target", -1) }
        assertThrows(IllegalArgumentException::class.java) { ShizukuWire.path("/data/user/0/com.target/files/../../secret") }
        assertThrows(IllegalArgumentException::class.java) { ShizukuWire.path("/data/user/0/com.target/files/other.data") }
        ShizukuWire.path("/data/user/0/com.target/files/other.data", true)
        val unsafe = SaveFile("/data/user/0/com.target/files/game.data", byteArrayOf(), "0:0", "777", "label;id")
        assertThrows(IllegalArgumentException::class.java) { ShizukuWire.file(ShizukuWire.metadata(unsafe), byteArrayOf()) }
        val output = ShizukuWire.sanitize("Permission denied /data/user/0/com.target/files/game.data account 12345678901234567890")
        assertTrue(output.contains("Permission denied"))
        assertFalse(output.contains("game.data"))
        assertFalse(output.contains("12345678901234567890"))
    }
}
