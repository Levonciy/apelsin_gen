package qr.protocol

/** Strict short-form DER <-> IEEE P1363 conversion for P-256 signatures only. */
class DerCodec {
    companion object {
        private val order = "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551".hexBytes()

        private fun validateScalar(v: ByteArray) {
            demand(v.size == 32 && v.any { it != 0.toByte() }, "Bad P-256 scalar")
            var comparison = 0
            for (i in 0..31) {
                val a = v[i].toInt() and 255
                val b = order[i].toInt() and 255
                if (a != b) { comparison = if (a < b) -1 else 1; break }
            }
            demand(comparison < 0, "P-256 scalar out of range")
        }

        @Throws(QrProtocolException::class)
        fun rawToDer(raw: ByteArray): ByteArray {
            demand(raw.size == 64, "P1363 signature must be 64 bytes")
            fun integer(v: ByteArray): ByteArray {
                validateScalar(v)
                var first = 0
                while (first < 31 && v[first] == 0.toByte()) first++
                val value = v.copyOfRange(first, 32)
                val positive = if ((value[0].toInt() and 128) != 0) byteArrayOf(0) + value else value
                return byteArrayOf(2, positive.size.toByte()) + positive
            }
            val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
            return byteArrayOf(0x30, body.size.toByte()) + body
        }

        @Throws(QrProtocolException::class)
        fun derToRaw(der: ByteArray): ByteArray {
            demand(der.size in 8..72 && der[0] == 0x30.toByte() &&
                (der[1].toInt() and 255) == der.size - 2, "Bad DER sequence")
            var pos = 2
            fun integer(): ByteArray {
                demand(pos + 2 <= der.size && der[pos++] == 2.toByte(), "Bad DER integer tag")
                val size = der[pos++].toInt() and 255
                demand(size in 1..33 && pos + size <= der.size, "Bad DER integer length")
                val encoded = der.copyOfRange(pos, pos + size)
                pos += size
                demand((encoded[0].toInt() and 128) == 0, "Negative DER integer")
                if (size > 1 && encoded[0] == 0.toByte())
                    demand((encoded[1].toInt() and 128) != 0, "Non-minimal DER integer")
                val value = if (size > 1 && encoded[0] == 0.toByte()) encoded.copyOfRange(1, size) else encoded
                demand(value.size <= 32, "Oversized P-256 scalar")
                val raw = ByteArray(32)
                value.copyInto(raw, 32 - value.size)
                validateScalar(raw)
                return raw
            }
            val raw = integer() + integer()
            demand(pos == der.size, "Trailing DER bytes")
            return raw
        }
    }
}
