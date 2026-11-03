// The Shared.framework bridge itself must be built and checked in the host project.
#if canImport(Shared) && canImport(UIKit)
import Foundation
import Shared

@MainActor
final class KmpQrSource: QrPayloadSource {
    private let codec: QrCodec
    private let profile: WireProfile
    private let signer: P256Signer
    private let expectedSpkiHex: String

    init(profile: WireProfile, signer: P256Signer, expectedSpkiHex: String) {
        self.codec = QrCodec()
        self.profile = profile
        self.signer = signer
        self.expectedSpkiHex = expectedSpkiHex
    }

    func makePayload(at date: Date) async throws -> String {
        let message = try codec.paymentMessage(profile: profile, utc: utc14(date))
        let signature = try await signer.sign(message: message, expectedSpkiHex: expectedSpkiHex)
        return try codec.assemble(message: message, signatureHex: signature)
    }
}
#endif
