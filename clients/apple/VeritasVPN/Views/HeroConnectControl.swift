import SwiftUI
import VeritasCore

/// The logo hero connect circle, a port of Android's HeroConnectControl:
/// 118 pt circle, 68 pt Veritas mark, wall-clock pulse (1.28 s half period),
/// dim → full mark ramp over 1.4 s, edge colour per phase. Reduce Motion
/// shows the end states with no pulse.
@MainActor
struct HeroConnectControl: View {
    let phase: HeroPhase
    let signInAgain: Bool
    let onTap: (() -> Void)?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var liveStart: TimeInterval?
    @State private var pulseFrom: Double = 0.16
    @State private var pulseTo: Double = 0.16
    @State private var pulseChangedAt: TimeInterval = 0
    @State private var pressed = false

    private var motion: Bool { !reduceMotion }

    private var edgeColor: Color {
        switch phase {
        case .protected: return Palette.cyan
        case .reconnecting: return Palette.warning
        case .upsell: return Palette.royalHover
        default: return Palette.cyanHover
        }
    }

    private var showNotConnected: Bool {
        phase == .ready || phase == .upsell || phase == .checking
    }

    var body: some View {
        VStack(spacing: 0) {
            if showNotConnected {
                Text(L("Not connected"))
                    .font(VFont.headlineMedium)
                    .foregroundStyle(Palette.cyan)
                    .multilineTextAlignment(.center)
                    .padding(.bottom, 36)
                    .transition(motion ? .opacity.combined(with: .move(edge: .top)) : .identity)
            }
            circle
            caption
                .animation(motion ? .easeOut(duration: 0.34) : nil, value: phase)
        }
        .animation(motion ? .easeOut(duration: 0.36) : nil, value: showNotConnected)
        .onAppear {
            let now = Date().timeIntervalSinceReferenceDate
            liveStart = HeroLogic.nextLiveStart(previous: nil, phase: phase, now: now)
            let amplitude = HeroLogic.pulseAmplitude(phase, motion: motion)
            pulseFrom = amplitude
            pulseTo = amplitude
            pulseChangedAt = now
        }
        .onChange(of: phase) { newPhase in
            let now = Date().timeIntervalSinceReferenceDate
            // The ramp clock carries over checking → connecting → protected.
            liveStart = HeroLogic.nextLiveStart(previous: liveStart, phase: newPhase, now: now)
            let target = HeroLogic.pulseAmplitude(newPhase, motion: motion)
            if target != pulseTo {
                pulseFrom = currentAmplitude(now: now)
                pulseTo = target
                pulseChangedAt = now
            }
        }
    }

    /// Amplitude eases to the new strength so connecting → protected never snaps.
    private func currentAmplitude(now: TimeInterval) -> Double {
        guard motion else { return 0 }
        let t = min(max((now - pulseChangedAt) / 0.6, 0), 1)
        return pulseFrom + (pulseTo - pulseFrom) * HeroLogic.easeOutSlowIn(t)
    }

    private var circle: some View {
        TimelineView(.animation(minimumInterval: nil, paused: !motion)) { context in
            let now = context.date.timeIntervalSinceReferenceDate
            let pulse = motion ? 1 + HeroLogic.pulseUnit(now: now) * currentAmplitude(now: now) : 1
            let elapsed = liveStart.map { now - $0 }
            let look = HeroLogic.markLook(phase: phase, motion: motion, elapsedLive: elapsed)
            ZStack {
                Circle()
                    .fill(RadialGradient(colors: [Palette.cardElevated, Palette.ink2, Palette.ink], center: .center, startRadius: 0, endRadius: 59))
                Circle()
                    .stroke(edgeColor.opacity(0.85), lineWidth: 1.5)
                Image("VeritasMark")
                    .resizable()
                    .interpolation(.high)
                    .scaledToFit()
                    .frame(width: 68, height: 68)
                    // Approximates Android's colour matrix: saturation, then a
                    // brightness shift, then alpha.
                    .saturation(look.saturation)
                    .brightness(look.brightness - 1)
                    .opacity(look.opacity)
            }
            .frame(width: 118, height: 118)
            .shadow(color: edgeColor.opacity(0.28), radius: phase == .protected ? 18 : 12)
            .scaleEffect((pressed && onTap != nil ? 0.94 : 1) * pulse)
        }
        .animation(motion ? .easeInOut(duration: 0.42) : nil, value: edgeColor)
        .contentShape(Circle())
        .onTapGesture { onTap?() }
        .simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in if !pressed { withAnimation(motion ? .spring(response: 0.26, dampingFraction: 0.68) : nil) { pressed = true } } }
                .onEnded { _ in withAnimation(motion ? .spring(response: 0.26, dampingFraction: 0.68) : nil) { pressed = false } }
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(HeroLogic.actionName(phase, signInAgain: signInAgain).map { L($0) } ?? L("Checking plan…")))
        .accessibilityValue(Text(phase == .protected ? L("VPN connected") : (phase == .ready ? L("Not connected") : "")))
        .accessibilityAddTraits(onTap != nil ? .isButton : [])
        .accessibilityAction { onTap?() }
    }

    private var captionText: String? {
        if signInAgain { return Messages.signInAgain }
        switch phase {
        case .checking: return "Checking plan…"
        case .connecting: return "Connecting…"
        case .protected: return "Protected"
        case .reconnecting: return "Reconnecting…"
        default: return nil
        }
    }

    private var captionColor: Color {
        if signInAgain { return Palette.warning }
        switch phase {
        case .protected: return Palette.cyan
        case .reconnecting: return Palette.warning
        case .connecting, .checking: return Palette.cyanHover
        default: return Palette.paperDim
        }
    }

    @ViewBuilder
    private var caption: some View {
        let text = captionText
        let color = captionColor
        if text != nil || phase == .upsell {
            VStack(spacing: 2) {
                if let text {
                    Text(L(text)).font(VFont.titleLarge).foregroundStyle(color)
                }
                if phase == .protected {
                    Text(L("Your connection is encrypted")).font(VFont.bodySmall).foregroundStyle(Palette.paperDim)
                }
                if signInAgain {
                    Text(L("Your session needs to be confirmed. Sign in to keep using VeritasVPN."))
                        .font(VFont.bodySmall).foregroundStyle(Palette.warning)
                } else if phase == .reconnecting {
                    Text(L("Not protected")).font(VFont.bodySmall).foregroundStyle(Palette.warning)
                }
                if phase == .upsell {
                    Text(L("Premium required")).font(VFont.bodySmall).foregroundStyle(Palette.paperDim)
                }
            }
            .multilineTextAlignment(.center)
            .padding(.top, phase == .upsell ? 20 : 8)
            .id(phase)
            .transition(motion ? .opacity.combined(with: .move(edge: .bottom)) : .identity)
        }
    }
}
