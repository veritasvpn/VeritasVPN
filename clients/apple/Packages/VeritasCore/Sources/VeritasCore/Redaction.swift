import Foundation

/// Scrubs a diagnostic report before it can be copied or emailed: IPv4 and
/// IPv6 addresses, WireGuard keys, JWTs and bearer tokens. The app never
/// records IP addresses in the first place; this is the second fence.
public enum Redaction {
    private static let rules: [(NSRegularExpression, String)] = {
        let patterns: [(String, String)] = [
            (#"(?i)bearer\s+[A-Za-z0-9._~+/=-]+"#, "Bearer [redacted]"),
            (#"eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*"#, "[token]"),
            (#"[A-Za-z0-9+/]{42,43}="#, "[key]"),
            (#"\b(?:\d{1,3}\.){3}\d{1,3}(?:/\d{1,2})?(?::\d{1,5})?\b"#, "[ip]"),
            (#"\[?(?:[0-9A-Fa-f]{1,4})?(?::[0-9A-Fa-f]{0,4}){2,7}(?:%[A-Za-z0-9]+)?\]?(?::\d{1,5})?"#, "[ip]"),
        ]
        return patterns.compactMap { pattern, replacement in
            (try? NSRegularExpression(pattern: pattern)).map { ($0, replacement) }
        }
    }()

    public static func scrub(_ text: String) -> String {
        var output = text
        for (regex, replacement) in rules {
            let range = NSRange(output.startIndex..<output.endIndex, in: output)
            output = regex.stringByReplacingMatches(in: output, options: [], range: range, withTemplate: replacement)
        }
        return output
    }
}
