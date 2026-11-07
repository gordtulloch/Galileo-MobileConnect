import Foundation

/// One catalog object as the stream carries it: where, how bright, and its stable catalog index.
public struct SkyObject: Sendable, Equatable {
    public var index: Int32
    public var raDeg: Float
    public var decDeg: Float
    public var magnitude: Float
    /// 0 star, 1 deep-sky.
    public var kind: UInt8

    public init(index: Int32, raDeg: Float, decDeg: Float, magnitude: Float, kind: UInt8) {
        self.index = index
        self.raDeg = raDeg
        self.decDeg = decDeg
        self.magnitude = magnitude
        self.kind = kind
    }

    public var isDeepSky: Bool { kind == 1 }
}

public enum SkyFrameError: Error, Equatable {
    case notASkyFrame, unsupportedEncoding(Int), truncated
}

/// `galileo.skystream`'s binary frame, isolated here so that a format change is one component (`S-SKY`).
public enum SkyFrameDecoder {
    public static let supportedVersions: Set<Int> = [1]
    private static let magic: [UInt8] = [0x47, 0x53, 0x4B]  // "GSK"
    private static let headerSize = 9
    private static let positionQuantum: Double = 1000
    private static let magnitudeQuantum: Double = 100

    /// Header (magic, version, kind, little-endian count), then delta-coded zigzag varints of RA, Dec, magnitude and
    /// index, and one byte of object kind, for each object.
    public static func decode(_ data: Data) throws -> [SkyObject] {
        let bytes = [UInt8](data)
        guard bytes.count >= headerSize, Array(bytes[0..<3]) == magic else { throw SkyFrameError.notASkyFrame }
        guard supportedVersions.contains(Int(bytes[3])), bytes[4] == 1 else {
            throw SkyFrameError.unsupportedEncoding(Int(bytes[3]))
        }
        let count = Int(bytes[5]) | Int(bytes[6]) << 8 | Int(bytes[7]) << 16 | Int(bytes[8]) << 24
        var position = headerSize

        func varint() throws -> Int64 {
            var shift: UInt64 = 0
            var result: UInt64 = 0
            while true {
                guard position < bytes.count, shift < 64 else { throw SkyFrameError.truncated }
                let byte = bytes[position]
                position += 1
                result |= UInt64(byte & 0x7F) << shift
                if byte & 0x80 == 0 { break }
                shift += 7
            }
            return Int64(bitPattern: result >> 1) ^ -Int64(bitPattern: result & 1)
        }

        var objects: [SkyObject] = []
        objects.reserveCapacity(min(count, 100_000))
        var ra: Int64 = 0
        var dec: Int64 = 0
        var mag: Int64 = 0
        var index: Int64 = 0
        for _ in 0..<count {
            ra += try varint()
            dec += try varint()
            mag += try varint()
            index += try varint()
            guard position < bytes.count else { throw SkyFrameError.truncated }
            let kind = bytes[position]
            position += 1
            objects.append(
                SkyObject(
                    index: Int32(truncatingIfNeeded: index), raDeg: Float(Double(ra) / positionQuantum),
                    decDeg: Float(Double(dec) / positionQuantum), magnitude: Float(Double(mag) / magnitudeQuantum), kind: kind))
        }
        return objects
    }

    /// The inverse, for `S-MOCK`: a client never sends one.
    static func encode(_ objects: [SkyObject]) -> Data {
        func put(_ value: Int64, _ out: inout [UInt8]) {
            var v = UInt64(bitPattern: (value << 1) ^ (value >> 63))
            while v > 0x7F {
                out.append(UInt8(v & 0x7F) | 0x80)
                v >>= 7
            }
            out.append(UInt8(v))
        }
        var out: [UInt8] = magic + [1, 1]
        let count = UInt32(objects.count)
        out += [UInt8(count & 0xFF), UInt8(count >> 8 & 0xFF), UInt8(count >> 16 & 0xFF), UInt8(count >> 24 & 0xFF)]
        var previous: [Int64] = [0, 0, 0, 0]
        for object in objects.sorted(by: { $0.raDeg < $1.raDeg }) {
            let current: [Int64] = [
                Int64((Double(object.raDeg) * positionQuantum).rounded()), Int64((Double(object.decDeg) * positionQuantum).rounded()),
                Int64((Double(object.magnitude) * magnitudeQuantum).rounded()), Int64(object.index),
            ]
            for i in 0..<4 { put(current[i] - previous[i], &out) }
            out.append(object.kind)
            previous = current
        }
        return Data(out)
    }
}
