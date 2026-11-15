import Foundation

/// A small, Sendable JSON value used for request bodies. It keeps request
/// construction type-safe without `[String: Any]`.
public enum JSONValue: Sendable, Equatable {
    case string(String)
    case bool(Bool)
    case int(Int)
    case array([JSONValue])
    case object([String: JSONValue])
    case null
}

extension JSONValue: Encodable {
    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .int(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }
}

extension JSONValue: ExpressibleByStringLiteral {
    public init(stringLiteral value: String) { self = .string(value) }
}

extension JSONValue: ExpressibleByBooleanLiteral {
    public init(booleanLiteral value: Bool) { self = .bool(value) }
}

extension JSONValue: ExpressibleByIntegerLiteral {
    public init(integerLiteral value: Int) { self = .int(value) }
}

extension JSONValue: ExpressibleByDictionaryLiteral {
    public init(dictionaryLiteral elements: (String, JSONValue)...) {
        var object: [String: JSONValue] = [:]
        for (key, value) in elements { object[key] = value }
        self = .object(object)
    }
}

extension JSONValue: ExpressibleByArrayLiteral {
    public init(arrayLiteral elements: JSONValue...) { self = .array(elements) }
}

public extension JSONValue {
    /// Merges two objects. Keys in `other` win. Non-objects return `other`.
    func merging(_ other: JSONValue) -> JSONValue {
        guard case .object(var mine) = self, case .object(let theirs) = other else { return other }
        for (key, value) in theirs { mine[key] = value }
        return .object(mine)
    }

    /// Stable, sorted JSON bytes (sorted keys keep tests deterministic).
    func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return try encoder.encode(self)
    }
}
