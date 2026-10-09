package com.example.soul_knight_save_editor.unlock

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Bounded binary transport; framing, byte count and SHA are checked independently. */
object StreamProtocol {
    private const val MAGIC = 0x534B5031
    const val MAX_METADATA = 4096
    data class Packet(val metadata: String, val bytes: ByteArray)

    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun write(output: OutputStream, metadata: String, bytes: ByteArray, maximum: Int) {
        val header = metadata.toByteArray(Charsets.UTF_8)
        require(header.size in 1..MAX_METADATA && bytes.size in 1..maximum) { "传输大小超出限制" }
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeInt(header.size)
        data.write(header)
        data.writeLong(bytes.size.toLong())
        data.writeUTF(sha(bytes))
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(32768, bytes.size - offset)
            data.write(bytes, offset, count)
            offset += count
        }
        data.flush()
    }

    fun read(input: InputStream, maximum: Int): Packet {
        val data = DataInputStream(input)
        require(data.readInt() == MAGIC) { "传输协议不匹配" }
        val metadataLength = data.readInt()
        require(metadataLength in 1..MAX_METADATA) { "传输元数据超出限制" }
        val header = ByteArray(metadataLength)
        data.readFully(header)
        val length = data.readLong()
        require(length in 1..maximum.toLong()) { "传输大小超出限制" }
        val hash = data.readUTF()
        require(Regex("[a-f0-9]{64}").matches(hash)) { "传输校验值无效" }
        val bytes = ByteArray(length.toInt())
        data.readFully(bytes)
        require(data.read() == -1) { "传输长度不一致" }
        require(sha(bytes) == hash) { "传输 SHA-256 校验失败" }
        return Packet(header.toString(Charsets.UTF_8), bytes)
    }

    /** Replacement input is raw bytes; only complete, bounded and verified payloads reach a writer. */
    fun readRaw(input: InputStream, length: Long, hash: String, maximum: Int): ByteArray {
        require(length in 1..maximum.toLong() && Regex("[a-f0-9]{64}").matches(hash)) { "写入传输参数无效" }
        val bytes = ByteArray(length.toInt())
        DataInputStream(input).readFully(bytes)
        require(input.read() == -1) { "写入传输长度不一致" }
        require(sha(bytes) == hash) { "写入传输 SHA-256 校验失败" }
        return bytes
    }
}
