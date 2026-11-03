#if canImport(UIKit)
import SwiftUI
import UIKit

/// Implement with KMP message assembly + the P256Signer actor.
@MainActor
protocol QrPayloadSource: AnyObject {
    func makePayload(at date: Date) async throws -> String
}

@MainActor
final class PaymentQrModel: ObservableObject {
    @Published private(set) var image: CGImage?
    @Published private(set) var generatedAt: Date?
    @Published private(set) var isBusy = false
    @Published private(set) var errorMessage: String?
    private let source: any QrPayloadSource
    private var revision = 0

    init(source: any QrPayloadSource) { self.source = source }

    func refresh() async {
        revision &+= 1
        let request = revision
        isBusy = true
        errorMessage = nil
        image = nil
        generatedAt = nil
        defer { if request == revision { isBusy = false } }
        do {
            let date = Date()
            let payload = try await source.makePayload(at: date)
            try Task.checkCancellation()
            guard request == revision else { return }
            let rendered = try QRRenderer.image(payload: payload)
            image = rendered
            generatedAt = date
        } catch is CancellationError {
            // Lifecycle cancellation must not publish an old code.
        } catch {
            if request == revision {
                errorMessage = "Не удалось сформировать QR. Проверьте доступ к сохранённому ключу."
            }
        }
    }

    func hide() { revision &+= 1; image = nil; generatedAt = nil; isBusy = false }
}

/// iOS 17+ reference component. Uses the supplied source, performs no registration.
struct PaymentQrScreen: View {
    @ObservedObject var model: PaymentQrModel
    let maskedCardNumber: String
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                VStack(spacing: 8) {
                    Text("Карта для оплаты").font(.title2.weight(.semibold))
                    Text(maskedCardNumber).font(.subheadline.monospaced()).foregroundStyle(.secondary)
                }
                Group {
                    if let image = model.image {
                        Image(decorative: image, scale: 1)
                            .interpolation(.none).resizable().scaledToFit()
                            .accessibilityLabel("QR-код карты для предъявления на кассе")
                    } else if model.isBusy {
                        ProgressView("Создаём QR…").frame(minHeight: 280)
                    } else {
                        ContentUnavailableView("QR недоступен", systemImage: "qrcode",
                            description: Text(model.errorMessage ?? "Обновите код"))
                    }
                }
                .padding(20).background(.white)
                .clipShape(RoundedRectangle(cornerRadius: 24))
                .frame(maxWidth: 420)
                if let date = model.generatedAt {
                    Text("Создан \(date, style: .time)")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Text("Генерируется на устройстве без интернета")
                    .font(.footnote).foregroundStyle(.secondary)
                Button { Task { await model.refresh() } } label: {
                    Label("Обновить QR", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).controlSize(.large)
                .disabled(model.isBusy)
            }
            .padding(24).frame(maxWidth: .infinity)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("Моя карта").navigationBarTitleDisplayMode(.inline)
        .task(id: scenePhase) {
            if scenePhase == .active { await model.refresh() }
            else { model.hide() }
        }
        .onDisappear { model.hide() }
        .privacySensitive()
    }
}
#endif
