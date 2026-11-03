package qr.protocol

import org.kotlincrypto.hash.blake2.BLAKE2b

/** commonMain dependency: org.kotlincrypto.hash:blake2:0.8.0. */
class KotlinCryptoBlake2b : Blake2bPort {
    override fun digest(input: ByteArray, outputBytes: Int): ByteArray =
        BLAKE2b(outputBytes * 8).digest(input)
}
