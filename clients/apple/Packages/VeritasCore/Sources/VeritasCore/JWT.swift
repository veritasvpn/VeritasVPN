import Foundation

/// Reads only the `exp` claim of an access token. The signature is the
/// server's business; the client uses `exp` to refresh before a call.
public enum JWT {
    public static func expiry(of token: String) -> Date? {
        let parts = token.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count >= 2 else { return nil }
        guard let payload = base64URLDecode(String(parts[1])),
              let object = try? JSONSerialization.jsonObject(with: payload) as? [String: Any] else { return nil }
        if let exp = object["exp"] as? NSNumber, exp.doubleValue > 0 {
            return Date(timeIntervalSince1970: exp.doubleValue)
        }
        return nil
    }

    /// True when the token is missing, unreadable, or expires within `skew`.
    public static func isExpired(_ token: String?, now: Date = Date(), skew: TimeInterval = 30) -> Bool {
        guard let token, !token.isEmpty, let exp = expiry(of: token) else { return true }
        return exp <= now.addingTimeInterval(skew)
    }

    public static func base64URLDecode(_ value: String) -> Data? {
        var base64 = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder > 0 { base64 += String(repeating: "=", count: 4 - remainder) }
        return Data(base64Encoded: base64)
    }
}
