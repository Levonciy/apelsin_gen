package qr.protocol

/** outputBytes is the BLAKE2 parameter, not a truncation after hashing. */
interface Blake2bPort { fun digest(input: ByteArray, outputBytes: Int): ByteArray }

/** Android-SDK-compatible identity derivation; not a claim about native iOS SDK internals. */
class IdentityDerivation(private val blake2b: Blake2bPort) {
    @Throws(QrProtocolException::class)
    fun modelId(modelName: String): String {
        demand(modelName.isNotEmpty(), "Missing model")
        for (size in listOf(4, 5)) {
            val digest = blake2b.digest(modelName.encodeToByteArray(), size)
            demand(digest.size == size, "Wrong BLAKE2b output length")
            var n = digest.fold(0L) { a, b -> (a shl 8) or (b.toLong() and 255) }
            var result = ""
            do { result = BASE62[(n % 62).toInt()] + result; n /= 62 } while (n > 0)
            if (result.length >= 5) return result.take(5)
        }
        throw QrProtocolException("Model hash is too short")
    }

    @Throws(QrProtocolException::class)
    fun appUserId(seedUpperHex: String, x5id: String, sec1PublicKeyHex: String): String {
        demand(seedUpperHex.length == 32 && seedUpperHex.all { it in "0123456789ABCDEF" }, "Seed must be uppercase hex16")
        demand(x5id.isNotEmpty() && x5id.all(::asciiDigit), "Bad X5ID")
        val input = seedUpperHex + x5id.encodeToByteArray().hex() + sdkPublicString(sec1PublicKeyHex)
        val digest = blake2b.digest(input.encodeToByteArray(), 16)
        demand(digest.size == 16, "Wrong BLAKE2b output length")
        return digest.hex()
    }

    @Throws(QrProtocolException::class)
    fun sdkPublicString(sec1PublicKeyHex: String): String {
        val point = sec1PublicKeyHex.hexBytes()
        demand(point.size == 65 && point[0] == 4.toByte(), "Expected uncompressed SEC1 P-256 key")
        // Reproduce Java positive BigInteger.toByteArray(), then the SDK's copy offsets.
        fun signedPositiveBytes(fixed: ByteArray): ByteArray {
            var first = 0
            while (first < fixed.lastIndex && fixed[first] == 0.toByte()) first++
            val minimal = fixed.copyOfRange(first, fixed.size)
            return if ((minimal[0].toInt() and 128) != 0) byteArrayOf(0) + minimal else minimal
        }
        val x = signedPositiveBytes(point.copyOfRange(1, 33))
        val y = signedPositiveBytes(point.copyOfRange(33, 65))
        demand(x.size >= 32 && y.size >= 32, "Key hits the Android SDK short-coordinate bug")
        val sdk = ByteArray(65)
        sdk[0] = 4
        x.copyInto(sdk, destinationOffset = 0, startIndex = 0, endIndex = 32)
        y.copyInto(sdk, destinationOffset = 33, startIndex = 0, endIndex = 32)
        return sdk.hex()
    }
}
