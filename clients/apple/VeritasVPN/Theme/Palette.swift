import SwiftUI

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}

/// Frozen Veritas brand palette, same values as Android's
/// ui/theme/Color.kt and website/css/style.css.
enum Palette {
    static let cyan = Color(hex: 0x09C7F5)
    static let cyanHover = Color(hex: 0x4AD9FA)
    static let cyanSoft = Color(hex: 0x09C7F5, alpha: 0x24 / 255)
    static let royal = Color(hex: 0x0756D9)
    static let royalHover = Color(hex: 0x2877EE)
    static let blueDeep = Color(hex: 0x06265C)

    static let ink = Color(hex: 0x010814)
    static let ink2 = Color(hex: 0x06101F)
    static let ink3 = Color(hex: 0x0A1729)
    static let cardBg = Color(hex: 0x081527)
    static let cardElevated = Color(hex: 0x0B1C32)

    static let paper = Color(hex: 0xFFFFFF)
    static let paperMuted = Color(hex: 0xADC3DB)
    static let paperDim = Color(hex: 0x7189A5)

    static let line = Color(hex: 0x2167A8, alpha: 0x33 / 255)
    static let lineStrong = Color(hex: 0x408FD4, alpha: 0x66 / 255)

    static let success = cyan
    static let error = Color(hex: 0xFF6B7A)
    static let warning = Color(hex: 0xFFB74D)

    static let gradientCyanToRoyal = LinearGradient(colors: [cyan, royal], startPoint: .leading, endPoint: .trailing)
    static let surfaceGradient = LinearGradient(colors: [ink, ink2, blueDeep.opacity(0.62)], startPoint: .top, endPoint: .bottom)
}
