import SwiftUI

/// Android's VeritasTypography: Space Grotesk for display and titles,
/// Inter for body and labels. Sizes scale with Dynamic Type.
enum VFont {
    private static func inter(_ weight: Font.Weight) -> String {
        switch weight {
        case .medium: return "Inter-Medium"
        case .semibold: return "Inter-SemiBold"
        case .bold: return "Inter-Bold"
        case .heavy, .black: return "Inter-ExtraBold"
        default: return "Inter-Regular"
        }
    }

    private static func grotesk(_ weight: Font.Weight) -> String {
        switch weight {
        case .bold, .heavy, .black: return "SpaceGrotesk-Bold"
        case .semibold: return "SpaceGrotesk-SemiBold"
        default: return "SpaceGrotesk-Medium"
        }
    }

    static func body(_ size: CGFloat, _ weight: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body) -> Font {
        .custom(inter(weight), size: size, relativeTo: style)
    }

    static func display(_ size: CGFloat, _ weight: Font.Weight = .semibold, relativeTo style: Font.TextStyle = .title) -> Font {
        .custom(grotesk(weight), size: size, relativeTo: style)
    }

    static let displayLarge = display(36, .bold, relativeTo: .largeTitle)
    static let displayMedium = display(30, .bold, relativeTo: .largeTitle)
    static let headlineLarge = display(26, .semibold, relativeTo: .title)
    static let headlineMedium = display(22, .semibold, relativeTo: .title2)
    static let titleLarge = display(18, .semibold, relativeTo: .title3)
    static let titleMedium = display(16, .medium, relativeTo: .headline)
    static let bodyLarge = body(16)
    static let bodyMedium = body(14, relativeTo: .subheadline)
    static let bodySmall = body(12, relativeTo: .footnote)
    static let labelLarge = body(14, .medium, relativeTo: .subheadline)
    static let labelMedium = body(12, .medium, relativeTo: .caption)
    static let labelSmall = body(11, .semibold, relativeTo: .caption2)
}

/// Uppercase eyebrow labels ("NETWORK MAP") with Android's 1.35 sp tracking.
struct Eyebrow: View {
    let text: String
    var color: Color = Palette.cyanHover

    var body: some View {
        Text(L(text))
            .font(VFont.labelSmall)
            .tracking(1.35)
            .foregroundStyle(color)
    }
}
