import SwiftUI
import VeritasCore
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

enum Platform {
    static var isMac: Bool {
        #if os(macOS)
        return true
        #else
        return false
        #endif
    }

    /// `X-Veritas-Client` value.
    static var clientName: String { isMac ? "macos" : "ios" }

    static var appVersion: String {
        let info = Bundle.main.infoDictionary ?? [:]
        let version = info["CFBundleShortVersionString"] as? String ?? "0"
        let build = info["CFBundleVersion"] as? String ?? "0"
        return "\(version) (\(build))"
    }

    static var shortVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
    }

    @MainActor
    static var osVersion: String {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        let name: String
        #if os(macOS)
        name = "macOS"
        #else
        name = UIDevice.current.userInterfaceIdiom == .pad ? "iPadOS" : "iOS"
        #endif
        return "\(name) \(v.majorVersion).\(v.minorVersion).\(v.patchVersion)"
    }

    /// Display-only device details, same keys as Android. No hardware
    /// identifiers: the model is the product family, not a serial or name.
    @MainActor
    static func deviceMetadata() -> DeviceMetadata {
        #if os(macOS)
        let platform = "macOS"
        let model = "Apple Mac"
        #else
        let idiom = UIDevice.current.userInterfaceIdiom
        let platform = idiom == .pad ? "iPadOS" : "iOS"
        let model = idiom == .pad ? "Apple iPad" : "Apple iPhone"
        #endif
        return DeviceMetadata(platform: platform, model: model, osVersion: osVersion, clientVersion: shortVersion)
    }

    @MainActor
    static func copyToClipboard(_ text: String) {
        #if os(iOS)
        UIPasteboard.general.string = text
        #elseif os(macOS)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
    }
}

extension View {
    /// Email-style text entry without auto-capitalization or correction.
    @ViewBuilder
    func emailEntry() -> some View {
        #if os(iOS)
        self.textInputAutocapitalization(.never)
            .autocorrectionDisabled(true)
            .keyboardType(.emailAddress)
            .textContentType(.username)
        #else
        self.autocorrectionDisabled(true)
            .textContentType(.username)
        #endif
    }

    @ViewBuilder
    func plainEntry() -> some View {
        #if os(iOS)
        self.textInputAutocapitalization(.never).autocorrectionDisabled(true)
        #else
        self.autocorrectionDisabled(true)
        #endif
    }

    @ViewBuilder
    func inlineNavigationTitle() -> some View {
        #if os(iOS)
        self.navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }
}

enum SupportLinks {
    static let contactEmail = "contact@veritasvpn.cloud"
    static let contactSubject = "VeritasVPN support"
    static let support = URL(string: "https://veritasvpn.cloud/support")!
    static let supportConnect = URL(string: "https://veritasvpn.cloud/support#stealth")!
    static let privacy = URL(string: "https://veritasvpn.cloud/privacy")!
    static let terms = URL(string: "https://veritasvpn.cloud/terms")!
    static let turnstilePage = URL(string: "https://veritasvpn.cloud/turnstile-mobile-v2")!

    /// mailto: with an optional redacted report in the body.
    static func mailto(report: String?) -> URL? {
        var components = URLComponents()
        components.scheme = "mailto"
        components.path = contactEmail
        var items = [URLQueryItem(name: "subject", value: contactSubject)]
        if let report, !report.isEmpty {
            items.append(URLQueryItem(name: "body", value: "\n\n---\n" + Redaction.scrub(report)))
        }
        components.queryItems = items
        return components.url
    }
}
