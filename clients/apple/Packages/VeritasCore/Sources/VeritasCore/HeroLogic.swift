import Foundation

/// Connect circle states. Same names and rules as Android's
/// HeroConnectControl / HeroPhaseMapper / HeroMainAction.
public enum HeroPhase: String, Sendable {
    case ready, upsell, checking, connecting, protected, reconnecting
}

public enum HeroAction: Equatable, Sendable {
    case none, signIn, disconnect, connect, plans
}

public enum HeroLogic {
    public static func phase(
        connected: Bool,
        connecting: Bool,
        reconnecting: Bool,
        handshakeOK: Bool,
        billingReady: Bool,
        isPremium: Bool
    ) -> HeroPhase {
        if connected || reconnecting {
            if connecting && !connected { return .connecting }
            if connected && reconnecting { return .reconnecting }
            if connected && !handshakeOK { return .reconnecting }
            if connected && handshakeOK { return .protected }
            return .ready
        }
        if connecting { return .connecting }
        if !billingReady { return .checking }
        return isPremium ? .ready : .upsell
    }

    /// One tap does the obvious thing. Connecting and reconnecting stay
    /// tappable so a stuck attempt can be cancelled.
    public static func mainAction(
        connecting: Bool,
        billingReady: Bool,
        connected: Bool,
        signInAgain: Bool,
        isPremium: Bool,
        reconnecting: Bool
    ) -> HeroAction {
        if signInAgain { return .signIn }
        if connecting || reconnecting || connected { return .disconnect }
        if !billingReady { return .none }
        return isPremium ? .connect : .plans
    }

    /// Spoken name (English key; the app localizes it).
    public static func actionName(_ phase: HeroPhase, signInAgain: Bool) -> String? {
        if signInAgain { return "Sign in" }
        switch phase {
        case .ready: return "Connect"
        case .upsell: return "Get Premium"
        case .connecting, .reconnecting, .protected: return "Disconnect"
        case .checking: return nil
        }
    }

    public static func isLive(_ phase: HeroPhase) -> Bool {
        phase == .checking || phase == .connecting || phase == .reconnecting
    }

    // Desktop MARK_DIM / MARK_FULL.
    public static let dim = MarkLook(brightness: 0.58, saturation: 0.48, opacity: 0.66)
    public static let full = MarkLook(brightness: 1.08, saturation: 1.12, opacity: 1)
    public static let glowDuration: TimeInterval = 1.4

    public struct MarkLook: Equatable, Sendable {
        public var brightness: Double
        public var saturation: Double
        public var opacity: Double
    }

    /// Keeps the ramp clock across checking → connecting → protected so a
    /// status update cannot restart the glow. nil means "no ramp running".
    public static func nextLiveStart(previous: TimeInterval?, phase: HeroPhase, now: TimeInterval) -> TimeInterval? {
        switch phase {
        case .checking, .connecting, .reconnecting: return previous ?? now
        case .protected: return previous
        default: return nil
        }
    }

    /// Fast-out-slow-in, like Compose's FastOutSlowInEasing (cubic 0.4, 0, 0.2, 1).
    public static func easeOutSlowIn(_ t: Double) -> Double {
        let x = min(max(t, 0), 1)
        // Solve the cubic Bézier for x, then evaluate y.
        let p1x = 0.4, p1y = 0.0, p2x = 0.2, p2y = 1.0
        func bezier(_ s: Double, _ a: Double, _ b: Double) -> Double {
            let u = 1 - s
            return 3 * u * u * s * a + 3 * u * s * s * b + s * s * s
        }
        var lo = 0.0, hi = 1.0, s = x
        for _ in 0..<24 {
            let value = bezier(s, p1x, p2x)
            if abs(value - x) < 1e-5 { break }
            if value < x { lo = s } else { hi = s }
            s = (lo + hi) / 2
        }
        return bezier(s, p1y, p2y)
    }

    public static func glow(elapsed: TimeInterval) -> Double {
        if elapsed <= 0 { return 0 }
        if elapsed >= glowDuration { return 1 }
        return easeOutSlowIn(elapsed / glowDuration)
    }

    /// Reduced motion shows the end state: full while live or protected.
    public static func markLook(phase: HeroPhase, motion: Bool, elapsedLive: TimeInterval?) -> MarkLook {
        let live = isLive(phase)
        let g: Double
        if phase == .protected {
            if !motion || elapsedLive == nil { g = 1 } else { g = glow(elapsed: elapsedLive ?? 0) }
        } else if live {
            g = motion ? glow(elapsed: max(0, elapsedLive ?? 0)) : 1
        } else {
            g = 0
        }
        func lerp(_ a: Double, _ b: Double) -> Double { g >= 1 ? b : (g <= 0 ? a : a + (b - a) * g) }
        return MarkLook(
            brightness: lerp(dim.brightness, full.brightness),
            saturation: lerp(dim.saturation, full.saturation),
            opacity: lerp(dim.opacity, full.opacity)
        )
    }

    /// 0...1...0 over 2.56 s from wall-clock time, so a stalled backend can
    /// never freeze the pulse.
    public static func pulseUnit(now: TimeInterval) -> Double {
        let half = 1.28
        let t = now.truncatingRemainder(dividingBy: half * 2)
        return t < half ? easeOutSlowIn(t / half) : easeOutSlowIn(1 - (t - half) / half)
    }

    /// Stronger while disconnected or connecting, softer once protected.
    public static func pulseAmplitude(_ phase: HeroPhase, motion: Bool) -> Double {
        guard motion else { return 0 }
        return phase == .protected ? 0.06 : 0.16
    }
}
