package qr.protocol

/** No key material belongs in this model. Keep the registered wire profile stable. */
data class WireProfile(
    val cardNumber: String,
    val keyId: String,
    val phone10: String,
    val userAppId: String,
    val osVersion: String,
    val merchant: String,
    val appVersion: String,
    val modelId: String,
    val modelName: String,
)

class QrProtocolException(message: String) : Exception(message)

/** String-only public boundary is convenient for the Objective-C framework bridge. */
class QrCodec {
    @Throws(QrProtocolException::class)
    fun paymentMessage(profile: WireProfile, utc: String): String {
        validate(profile, utc)
        return listOf(profile.cardNumber, utc, profile.keyId, profile.phone10,
            profile.userAppId, versionDigits(profile.osVersion, 3),
            shortMerchant(profile.merchant), versionDigits(profile.appVersion, 5),
            profile.modelId).joinToString(";")
    }

    @Throws(QrProtocolException::class)
    fun bankMessage(profile: WireProfile, utc: String): String {
        validate(profile, utc)
        return listOf(utc, profile.phone10, profile.userAppId,
            versionDigits(profile.osVersion, 3), shortMerchant(profile.merchant),
            versionDigits(profile.appVersion, 5), profile.modelId,
            profile.modelName).joinToString(";")
    }

    /** Structural validation only: the caller must sign this exact message. */
    @Throws(QrProtocolException::class)
    fun assemble(message: String, signatureHex: String): String {
        demand(message.isNotEmpty() && !message.contains('\n') && !message.contains('\r'), "Bad message")
        demand(signatureHex.length == 128 && signatureHex.all(::isHex), "Expected 64-byte P1363 signature")
        val raw = signatureHex.hexBytes()
        DerCodec.rawToDer(raw) // also validates that r and s are valid P-256 scalars
        return message + "." + signatureHex.lowercase()
    }

    private fun validate(p: WireProfile, utc: String) {
        demand(p.cardNumber.length == 16 && p.cardNumber.all(::asciiDigit), "Expected 16-digit card")
        demand(p.phone10.length == 10 && p.phone10.all(::asciiDigit), "Expected 10-digit phone")
        demand(p.keyId.isNotEmpty() && p.keyId.all(::asciiDigit) && p.keyId.any { it != '0' }, "Bad key_id")
        demand(p.userAppId.length == 32 && p.userAppId.all { it in "0123456789abcdef" }, "Bad user_app_id")
        demand(p.modelId.length == 5 && p.modelId.all { it in BASE62 }, "Bad model_id")
        demand(p.modelName.isNotEmpty(), "Missing model name")
        listOf(p.osVersion, p.appVersion, p.merchant, p.modelName).forEach {
            demand(it.none { c -> c == ';' || c == '\r' || c == '\n' || c == '\u0000' }, "Delimiter in field")
        }
        demand(p.merchant.isNotEmpty(), "Missing merchant")
        versionDigits(p.osVersion, 3)
        versionDigits(p.appVersion, 5)
        validateUtc(utc)
    }
}

internal const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
internal fun asciiDigit(c: Char) = c in '0'..'9'
internal fun isHex(c: Char) = c in "0123456789abcdefABCDEF"
internal fun demand(ok: Boolean, message: String) { if (!ok) throw QrProtocolException(message) }

internal fun versionDigits(value: String, count: Int): String {
    val digits = value.filter(::asciiDigit).take(count)
    demand(digits.isNotEmpty(), "Version has no ASCII digits")
    return digits
}

internal fun shortMerchant(value: String): String =
    if (value.length == 2) value.take(1) else value.takeLast(1)

internal fun ByteArray.hex(): String = joinToString("") { b ->
    val v = b.toInt() and 255
    "0123456789abcdef"[v ushr 4].toString() + "0123456789abcdef"[v and 15]
}

internal fun String.hexBytes(): ByteArray {
    demand(length % 2 == 0 && all(::isHex), "Bad hex")
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private fun validateUtc(s: String) {
    demand(s.length == 14 && s.all(::asciiDigit), "Expected UTC yyyyMMddHHmmss")
    val year = s.substring(0, 4).toInt()
    val month = s.substring(4, 6).toInt()
    val day = s.substring(6, 8).toInt()
    val hour = s.substring(8, 10).toInt()
    val minute = s.substring(10, 12).toInt()
    val second = s.substring(12, 14).toInt()
    demand(year in 1..9999 && month in 1..12, "Bad UTC date")
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    demand(day in 1..days[month - 1] && hour in 0..23 && minute in 0..59 && second in 0..59, "Bad UTC date/time")
}
