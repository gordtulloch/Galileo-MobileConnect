import Foundation

/// Any JSON value. The Master's state, operation arguments and results are open-ended, so they travel as this.
public enum JSONValue: Sendable, Equatable, Hashable, Codable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else {
            self = .object(try container.decode([String: JSONValue].self))
        }
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let value): try container.encode(value)
        case .number(let value):
            // A whole number stays whole on the wire: the Master's `int` arguments are strict about it.
            if value.rounded() == value, abs(value) < 9e15 {
                try container.encode(Int64(value))
            } else {
                try container.encode(value)
            }
        case .string(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        }
    }

    public var stringValue: String? { if case .string(let v) = self { v } else { nil } }
    public var boolValue: Bool? { if case .bool(let v) = self { v } else { nil } }
    public var doubleValue: Double? { if case .number(let v) = self { v } else { nil } }
    public var intValue: Int? { doubleValue.map { Int($0) } }
    public var arrayValue: [JSONValue]? { if case .array(let v) = self { v } else { nil } }
    public var objectValue: [String: JSONValue]? { if case .object(let v) = self { v } else { nil } }

    public subscript(key: String) -> JSONValue? { objectValue?[key] }

    /// The value as a person reads it.
    public var displayText: String {
        switch self {
        case .null: return "—"
        case .bool(let v): return v ? "Yes" : "No"
        case .number(let v):
            return v.rounded() == v && abs(v) < 1e15 ? String(Int64(v)) : String(format: "%.3g", v)
        case .string(let v): return v
        case .array(let v): return v.map(\.displayText).joined(separator: ", ")
        case .object(let v):
            return v.keys.sorted().map { "\($0): \(v[$0]!.displayText)" }.joined(separator: ", ")
        }
    }

    /// Re-types this value through JSON, to reach the generated client's containers.
    func converted<T: Decodable>(to type: T.Type) throws -> T {
        try JSONDecoder().decode(T.self, from: JSONEncoder().encode(self))
    }

    /// Re-types any encodable generated value into a `JSONValue`.
    init(encoding value: some Encodable) throws {
        self = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(value))
    }
}

extension JSONValue: ExpressibleByNilLiteral, ExpressibleByBooleanLiteral, ExpressibleByIntegerLiteral,
    ExpressibleByFloatLiteral, ExpressibleByStringLiteral, ExpressibleByArrayLiteral,
    ExpressibleByDictionaryLiteral
{
    public init(nilLiteral: ()) { self = .null }
    public init(booleanLiteral value: Bool) { self = .bool(value) }
    public init(integerLiteral value: Int) { self = .number(Double(value)) }
    public init(floatLiteral value: Double) { self = .number(value) }
    public init(stringLiteral value: String) { self = .string(value) }
    public init(arrayLiteral elements: JSONValue...) { self = .array(elements) }
    public init(dictionaryLiteral elements: (String, JSONValue)...) {
        self = .object(Dictionary(elements, uniquingKeysWith: { _, last in last }))
    }
}
