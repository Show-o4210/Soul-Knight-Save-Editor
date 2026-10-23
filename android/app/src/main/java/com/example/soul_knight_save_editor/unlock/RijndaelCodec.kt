package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class ShardEncoding { DES_BASE64, RIJNDAEL_JSON }

/** The legacy envelope is ASCII Base64; malformed legacy data never falls through to AES. */
internal fun hasLegacyBase64Envelope(bytes: ByteArray): Boolean = bytes.all {
    val value = it.toInt() and 0xff
    value in 65..90 || value in 97..122 || value in 48..57 ||
        value == 43 || value == 47 || value == 61 || value == 9 || value == 10 || value == 13 || value == 32
}

/** Native Abo.CryptUtil: AES-256-CBC, zero padding, StreamWriter UTF-8 BOM JSON.
 * Constants were checked against the 8.7 RGGameConst static arrays and their SHA-256 field names.
 * File selection and account binding remain the caller's responsibility. */
object RijndaelCodec {
    private val key = SecretKeySpec(byteArrayOf(79, -11, 100, -75, -17, -91, 126, -89, -45, 32, -89, 20, -74, -70, -83, -118, 48, 122, 66, 108, 21, -81, 14, -102, 64, 112, -98, -80, 80, -82, 60, 64), "AES")
    private val iv = IvParameterSpec(byteArrayOf(25, -118, -98, -73, -89, -8, -25, 102, -77, 127, -47, 118, -3, -118, -86, -87))
    private val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
    private fun crypt(mode: Int, bytes: ByteArray) = Cipher.getInstance("AES/CBC/NoPadding").run {
        init(mode, key, RijndaelCodec.iv)
        doFinal(bytes)
    }

    fun decode(bytes: ByteArray): JsonObject {
        require(bytes.size in 16..UnlockEngine.MAX_BYTES && bytes.size % 16 == 0) { "新格式分片大小或加密块长度无效" }
        return try {
            val plain = crypt(Cipher.DECRYPT_MODE, bytes)
            require(plain[0] == bom[0] && plain[1] == bom[1] && plain[2] == bom[2]) { "新格式分片缺少已知 UTF-8 BOM，未作修改" }
            var end = plain.size
            while (end > 0 && plain[end - 1] == 0.toByte()) end--
            require(plain.size - end < 16 && end > bom.size) { "新格式分片零填充无效" }
            Json.parseToJsonElement(UnlockEngine.utf8(plain.copyOfRange(bom.size, end))).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("新格式分片无法按已知格式解码，未作修改", error)
        }
    }

    fun encode(root: JsonObject): ByteArray {
        val json = root.toString().toByteArray(Charsets.UTF_8)
        require(json.size <= UnlockEngine.MAX_BYTES - bom.size) { "新格式分片数据过大" }
        val size = ((json.size + bom.size + 15) / 16) * 16
        require(size <= UnlockEngine.MAX_BYTES) { "新格式分片文件过大" }
        val plain = ByteArray(size)
        bom.copyInto(plain)
        json.copyInto(plain, bom.size)
        return crypt(Cipher.ENCRYPT_MODE, plain).also {
            require(decode(it) == root) { "新格式分片编码复验失败" }
        }
    }
}
