import SwiftUI

/// Android's `glassSurface`: elevated card gradient, soft royal shadow and a
/// hairline border.
struct GlassSurface: ViewModifier {
    var cornerRadius: CGFloat = 20
    var border: Color = Palette.lineStrong.opacity(0.72)

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        content
            .background(
                shape.fill(LinearGradient(colors: [Palette.cardElevated, Palette.ink2], startPoint: .top, endPoint: .bottom))
            )
            .overlay(shape.stroke(border, lineWidth: 1))
            .clipShape(shape)
            .shadow(color: Palette.royal.opacity(0.18), radius: 14, x: 0, y: 6)
    }
}

extension View {
    func glassSurface(cornerRadius: CGFloat = 20, border: Color = Palette.lineStrong.opacity(0.72)) -> some View {
        modifier(GlassSurface(cornerRadius: cornerRadius, border: border))
    }
}

/// Android's `PremiumBackdrop`: ink-to-deep-blue gradient with two glows.
struct PremiumBackdrop<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        ZStack {
            Palette.surfaceGradient.ignoresSafeArea()
            GeometryReader { proxy in
                let size = proxy.size
                let minDim = min(size.width, size.height)
                ZStack {
                    RadialGradient(colors: [Palette.royal.opacity(0.22), .clear], center: .center, startRadius: 0, endRadius: minDim * 0.92)
                        .frame(width: minDim * 1.84, height: minDim * 1.84)
                        .position(x: size.width * 0.82, y: size.height * 0.02)
                    RadialGradient(colors: [Palette.cyan.opacity(0.07), .clear], center: .center, startRadius: 0, endRadius: minDim * 0.72)
                        .frame(width: minDim * 1.44, height: minDim * 1.44)
                        .position(x: size.width * 0.08, y: size.height * 0.34)
                }
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
            content
        }
    }
}

/// Round 48 pt icon button used in the header (Help, Settings).
struct GlassIconButton: View {
    let systemImage: String
    let accessibilityLabel: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 19, weight: .semibold))
                .foregroundStyle(Palette.cyanHover)
                .frame(width: 48, height: 48)
                .background(Circle().fill(Palette.cardElevated.opacity(0.92)))
                .overlay(Circle().stroke(Palette.line.opacity(0.9), lineWidth: 1))
        }
        .buttonStyle(PressScaleStyle())
        .accessibilityLabel(L(accessibilityLabel))
    }
}

/// 0.94 press scale with a spring, skipped when Reduce Motion is on.
struct PressScaleStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.94 : 1)
            .animation(reduceMotion ? nil : .spring(response: 0.28, dampingFraction: 0.72), value: configuration.isPressed)
    }
}

/// Full-width primary button (royal fill, 50 pt, 14 pt corners).
struct PrimaryButtonStyle: ButtonStyle {
    var fill: Color = Palette.royal
    var foreground: Color = .white
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(VFont.body(15, .bold))
            .foregroundStyle(foreground)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(fill.opacity(isEnabled ? (configuration.isPressed ? 0.85 : 1) : 0.45))
            )
            .contentShape(Rectangle())
    }
}

/// Outlined secondary button.
struct SecondaryButtonStyle: ButtonStyle {
    var tint: Color = Palette.cyanHover

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(VFont.body(15, .semibold))
            .foregroundStyle(tint)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(tint.opacity(configuration.isPressed ? 0.5 : 0.35), lineWidth: 1)
            )
            .contentShape(Rectangle())
    }
}

/// Section title + rounded glass group, like Android's settings screens.
struct SettingsSection<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Eyebrow(text: title, color: Palette.paperDim)
                .padding(.horizontal, 4)
            VStack(alignment: .leading, spacing: 0) { content }
                .glassSurface(cornerRadius: 18)
        }
    }
}

/// A header with eyebrow + title and an optional Back button.
struct ScreenHeader: View {
    let eyebrow: String
    let title: String
    var onBack: (() -> Void)?

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Eyebrow(text: eyebrow)
                Text(L(title))
                    .font(VFont.headlineMedium)
                    .foregroundStyle(Palette.paper)
            }
            Spacer()
            if let onBack {
                Button(L("Back"), action: onBack)
                    .font(VFont.body(15, .semibold))
                    .foregroundStyle(Palette.cyanHover)
                    .buttonStyle(.plain)
            }
        }
    }
}
