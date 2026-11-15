import Foundation

/// Port of `clients/desktop/src/errorMapper.ts`: decides whether a piece of
/// text may be shown, and turns every failure into a descriptive sentence.
public enum ErrorText {
    private static func matches(_ pattern: String, _ text: String, caseInsensitive: Bool = true) -> Bool {
        let options: NSRegularExpression.Options = caseInsensitive ? [.caseInsensitive] : []
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else { return false }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.firstMatch(in: text, options: [], range: range) != nil
    }

    /// True for bodies that come from Cloudflare, a proxy or a captive portal
    /// rather than the VeritasVPN API: "error code: 1033", HTML pages, Ray IDs,
    /// gateway texts. None of that may ever be shown.
    public static func looksLikeEdgeResponse(_ body: String) -> Bool {
        let text = body.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if text.isEmpty { return false }
        return matches(#"error\s*code\s*:?\s*\d{3,4}"#, text)
            || matches(#"<\s*(!doctype|html|head|body|title|div|h1|center)\b"#, text)
            || text.contains("cloudflare")
            || text.contains("cf-ray")
            || matches(#"\bray id\b"#, text)
            || matches(#"\b(bad gateway|gateway time-?out|service unavailable|origin (is )?unreachable|web server is down|tunnel error|argo tunnel)\b"#, text)
            || matches(#"^5\d\d\b"#, text)
    }

    /// Statuses that mean "the service is down or unreachable".
    public static func isServerUnavailableStatus(_ status: Int) -> Bool {
        status == 0 || status == 408 || status >= 500
    }

    /// Whether a server-provided message is safe to show as-is: short, plain
    /// words, and free of status codes, markup, JSON or parser noise.
    public static func isDisplaySafeMessage(_ message: String) -> Bool {
        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty || text.count > 200 { return false }
        if looksLikeEdgeResponse(text) { return false }
        let lower = text.lowercased()
        if matches(#"[<>{}\[\]`]"#, text) { return false }
        if matches(#"\b(error|status|http|code)\s*[:#(]?\s*\d{3,}\b"#, lower) { return false }
        if matches(#"\(\s*\d{3,}\s*\)"#, text) { return false }
        if matches(#"\b[1-5]\d\d\s+(error|bad request|unauthorized|forbidden|not found|internal)"#, lower) { return false }
        let noise = ["request failed", "unexpected token", "not valid json", "syntaxerror", "json.parse",
                     "os error", "exception", "traceback", "nsurlerrordomain", "nserror", "error domain="]
        if noise.contains(where: { lower.contains($0) }) { return false }
        if matches(#"\b(undefined|null|nan|nil)\b"#, lower) { return false }
        if matches(#"\bat [\w$.]+ \("#, text, caseInsensitive: false) { return false }
        // Machine codes such as "peer_limit" or "connect.invalid_config".
        if matches(#"^[a-z0-9_.-]+$"#, lower) { return false }
        return true
    }

    /// A server sentence written for people ("Device limit reached."), as
    /// opposed to log-style text ("create peer failed", "invalid json").
    public static func isWrittenSentence(_ message: String?) -> Bool {
        let text = (message ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = text.first, first.isUppercase else { return false }
        return isDisplaySafeMessage(text)
    }

    /// Last-resort guard for any text about to be rendered as an error.
    public static func safe(_ message: String?, fallback: String = Messages.generic) -> String {
        let text = (message ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if looksLikeEdgeResponse(text) { return Messages.serverUnreachable }
        // Stricter than the desktop: lower-case log text never reaches the UI.
        return isWrittenSentence(text) ? text : fallback
    }

    /// The sentence to show for any error thrown by the core or the system.
    public static func message(for error: Error) -> String {
        if let visible = error as? UserVisibleError { return visible.userMessage }
        if let api = error as? APIError { return api.userMessage }
        if let url = error as? URLError { return message(for: url) }
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain { return message(for: URLError(URLError.Code(rawValue: ns.code))) }
        if let failure = TunnelFailure(error: error) { return failure.userMessage }
        return Messages.generic
    }

    public static func message(for error: URLError) -> String {
        switch error.code {
        case .notConnectedToInternet, .dataNotAllowed, .internationalRoamingOff:
            return Messages.offline
        case .timedOut:
            return Messages.serverTimeout
        case .secureConnectionFailed, .serverCertificateHasBadDate, .serverCertificateUntrusted,
             .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid, .clientCertificateRejected,
             .clientCertificateRequired:
            return Messages.secureConnectionFailed
        case .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed, .networkConnectionLost,
             .badServerResponse, .cannotParseResponse, .zeroByteResource, .resourceUnavailable,
             .callIsActive, .httpTooManyRedirects, .redirectToNonExistentLocation:
            return Messages.serverUnreachable
        default:
            return Messages.generic
        }
    }

    /// Port of `formatConnectError` for the Apple clients. `wantedStealth`
    /// is true when the user picked Stealth always.
    public static func connectMessage(for error: Error, wantedStealth: Bool) -> String {
        if let failure = TunnelFailure(error: error) {
            if wantedStealth, failure == .invalidConfig { return Messages.stealthUnavailable }
            return failure.userMessage
        }
        if let api = error as? APIError { return api.userMessage }
        if error is ProfileError { return wantedStealth ? Messages.stealthUnavailable : Messages.invalidConfig }
        if let visible = error as? UserVisibleError { return visible.userMessage }
        let text = message(for: error)
        return text == Messages.generic ? Messages.connectFailed : text
    }
}

/// Errors whose message is already written for people.
public protocol UserVisibleError: Error {
    var userMessage: String { get }
}
