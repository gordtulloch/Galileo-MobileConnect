import Foundation
import Testing

@testable import GalileoCore

@Suite("Address entry (MAPP-CON-020)")
struct MasterAddressTests {
    @Test(arguments: [
        ("master.local", "master.local", 8765), ("192.168.1.20", "192.168.1.20", 8765),
        ("192.168.1.20:9000", "192.168.1.20", 9000), ("https://obs.example.org:8765/", "obs.example.org", 8765),
        ("  observatory  ", "observatory", 8765), ("[fe80::1]:9000", "fe80::1", 9000), ("fe80::1", "fe80::1", 8765),
    ])
    func accepted(text: String, host: String, port: Int) throws {
        let address = try MasterAddress.parse(text).get()
        #expect(address.host == host)
        #expect(address.port == port)
        #expect(address.baseURL.scheme == "https")
    }

    @Test func refusedInPlainWords() {
        #expect(MasterAddress.parse("") == .failure(.empty))
        #expect(MasterAddress.parse("http://master.local") == .failure(.notEncrypted))
        #expect(MasterAddress.parse("ftp://master") == .failure(.unsupportedScheme("ftp")))
        #expect(MasterAddress.parse("master.local:0") == .failure(.badPort))
        #expect(MasterAddress.parse("master.local:70000") == .failure(.badPort))
        #expect(MasterAddress.parse("master.local:abc") == .failure(.badPort))
        #expect(MasterAddress.parse("bad host") == .failure(.badHost))
        #expect(MasterAddress.parse("https://master/path") == .failure(.badHost))
        #expect(MasterAddress.parse("-bad.local") == .failure(.badHost))
    }

    @Test func ipv6BaseURLIsBracketed() {
        #expect(MasterAddress(host: "fe80::1", port: 9000).baseURL.absoluteString.contains("[fe80::1]:9000"))
    }
}

@Suite("Certificate pinning (MAPP-CON-050)")
struct PinningTests {
    @Test func aPinAcceptsOnlyItsOwnCertificate() {
        let policy = PinPolicy.pinned("aabb")
        #expect(policy.accepts(presented: "aabb"))
        #expect(policy.accepts(presented: "AABB"))
        #expect(!policy.accepts(presented: "ccdd"))
    }

    @Test func pairingAcceptsWhatIsShown() { #expect(PinPolicy.pairing.accepts(presented: "anything")) }

    /// Same value as `cert.fingerprint(hashes.SHA256()).hex()` on the same bytes.
    @Test func fingerprintIsLowerCaseHexSHA256() {
        #expect(
            PinPolicy.fingerprint(ofDER: Data("abc".utf8))
                == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test func aDifferentCertificateAtPairingIsRefusedAndNothingStored() async throws {
        let master = try MockMaster(scenarioNamed: "normal-night")
        let store = InMemorySecureStore()
        let flow = PairingFlow(
            api: MasterConnection.demo(master, identity: testIdentity).api, store: store,
            address: MasterAddress(host: "m"), masterName: "M", presentedFingerprint: { "not-the-master" })
        guard case .failed = await flow.start() else {
            Issue.record("expected the mismatch to end pairing")
            return
        }
        #expect(store.all().isEmpty)
    }
}

@Suite("Secure store (MAPP-CON-040)")
struct SecureStoreTests {
    @Test func inMemoryStoreRoundTrips() {
        let store = InMemorySecureStore()
        let item = MasterCredentials(name: "M", address: MasterAddress(host: "m"), token: "t", fingerprint: "f")
        store.save(item)
        #expect(store.all() == [item])
        store.delete(id: item.id)
        #expect(store.all().isEmpty)
    }

    /// The Keychain needs an application identifier, which a bare test process may lack: it then reports
    /// errSecMissingEntitlement and this proves nothing. The UI test `testRealPairingKeychainAndUnpair` is what
    /// exercises the real Keychain, in the real app.
    @Test func keychainRoundTripsAndKeepsTheItemOffOtherDevices() throws {
        let store = KeychainSecureStore(service: "com.gordtulloch.galileo.tests.\(UUID().uuidString)")
        let item = MasterCredentials(name: "M", address: MasterAddress(host: "m"), token: "t", fingerprint: "f")
        do {
            try store.save(item)
        } catch let failure as KeychainSecureStore.Failure where failure.status == errSecMissingEntitlement {
            return
        }
        defer { try? store.delete(id: item.id) }
        #expect(try store.all() == [item])
        var updated = item
        updated.token = "t2"
        try store.save(updated)
        #expect(try store.all() == [updated])
        try store.delete(id: item.id)
        #expect(try store.all().isEmpty)
    }
}

@Suite("Stream link (MAPP-CON-070)")
struct WebSocketURLTests {
    @Test func resumeUsesTheCoreParameters() throws {
        let base = try #require(URL(string: "https://m.local:8765"))
        #expect(
            WebSocketStreamTransport.url(baseURL: base, epoch: "e", last: 4)?.absoluteString
                == "wss://m.local:8765/connect/v1/stream?epoch=e&last=4")
        #expect(WebSocketStreamTransport.url(baseURL: base, epoch: nil, last: nil)?.absoluteString == "wss://m.local:8765/connect/v1/stream")
    }

    @Test func backoffGrowsAndIsCapped() {
        var backoff = StreamLink.Backoff()
        backoff.jitter = { 1 }
        #expect(backoff.delay(attempt: 0) == 1)
        #expect(backoff.delay(attempt: 3) == 8)
        #expect(backoff.delay(attempt: 20) == 30)
    }
}
