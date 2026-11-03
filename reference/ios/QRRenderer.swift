import Foundation
import CoreImage
import CoreImage.CIFilterBuiltins
import CoreGraphics

enum QRRenderError: Error { case badImage, badSize }

enum QRRenderer {
    static func image(payload: String, pixelsPerModule: Int = 8) throws -> CGImage {
        guard (1...32).contains(pixelsPerModule) else { throw QRRenderError.badSize }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"
        let context = CIContext(options: [.useSoftwareRenderer: true])
        guard let output = filter.outputImage,
              let original = context.createCGImage(output, from: output.extent) else {
            throw QRRenderError.badImage
        }
        let scale = pixelsPerModule
        let border = 4 * scale // at least four white modules on every side
        let width = original.width * scale + 2 * border
        let height = original.height * scale + 2 * border
        guard let canvas = CGContext(data: nil, width: width, height: height,
            bitsPerComponent: 8, bytesPerRow: width,
            space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else {
            throw QRRenderError.badImage
        }
        canvas.setFillColor(gray: 1, alpha: 1)
        canvas.fill(CGRect(x: 0, y: 0, width: width, height: height))
        canvas.interpolationQuality = .none
        canvas.draw(original, in: CGRect(x: border, y: border,
            width: original.width * scale, height: original.height * scale))
        guard let image = canvas.makeImage() else { throw QRRenderError.badImage }
        return image
    }
}
