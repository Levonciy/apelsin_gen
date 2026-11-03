package qr.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QrProtocolTest {
    private val codec = QrCodec()
    private val profile = WireProfile("7789000000000000", "123456", "9000000000", "35fd4d616e5df80d41b8f5720ca68e2b", "14", "TCX", "4.1.1", "2Wl0P", "Xiaomi 22101320G")

    @Test fun canonicalMessages() {
        assertEquals("7789000000000000;20261027220000;123456;9000000000;35fd4d616e5df80d41b8f5720ca68e2b;14;X;411;2Wl0P", codec.paymentMessage(profile, "20261027220000"))
        assertEquals("20261027220000;9000000000;35fd4d616e5df80d41b8f5720ca68e2b;14;X;411;2Wl0P;Xiaomi 22101320G", codec.bankMessage(profile, "20261027220000"))
    }

    @Test fun strictDerAndRaw() {
        assertEquals("00000000000000000000000000000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000001", DerCodec.derToRaw("3006020101020101".hexBytes()).hex())
        assertEquals("3006020101020101", DerCodec.rawToDer("00000000000000000000000000000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000001".hexBytes()).hex())
        assertEquals("80000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001", DerCodec.derToRaw("30260221008000000000000000000000000000000000000000000000000000000000000000020101".hexBytes()).hex())
        assertEquals("30260221008000000000000000000000000000000000000000000000000000000000000000020101", DerCodec.rawToDer("80000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001".hexBytes()).hex())
        assertFailsWith<QrProtocolException> { DerCodec.derToRaw("3006020180020101".hexBytes()) }
        assertFailsWith<QrProtocolException> { DerCodec.derToRaw("300702020001020101".hexBytes()) }
        assertFailsWith<QrProtocolException> { DerCodec.derToRaw("300602010102010100".hexBytes()) }
    }

    @Test fun badTimestampAndSignatureAreRejected() {
        assertFailsWith<QrProtocolException> { codec.paymentMessage(profile, "20260230010101") }
        assertFailsWith<QrProtocolException> { codec.paymentMessage(profile, "20261027240000") }
        assertFailsWith<QrProtocolException> { codec.assemble("message", "00".repeat(64)) }
    }

    // Requires KotlinCryptoBlake2b and its commonMain dependency in the host project.
    @Test fun identityAndModelVectors() {
        val identity = IdentityDerivation(KotlinCryptoBlake2b())
        assertEquals("1PPnA", identity.modelId("iPhone16,1"))
        assertEquals("2Wl0P", identity.modelId("Xiaomi 22101320G"))
        assertEquals("EpD7o", identity.modelId("SyntheticModel-51"))
        assertEquals("35fd4d616e5df80d41b8f5720ca68e2b", identity.appUserId("000102030405060708090A0B0C0D0E0F", "12345678", "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"))
    }
}
