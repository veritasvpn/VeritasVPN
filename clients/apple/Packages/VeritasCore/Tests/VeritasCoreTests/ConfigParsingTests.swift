import XCTest
@testable import VeritasCore

final class ConfigParsingTests: XCTestCase {
    private func peer(
        dns: String? = "10.0.0.1",
        psk: String? = Fixtures.key(3),
        allowed: [String]? = ["0.0.0.0/0", "::/0"],
        assigned: String = "10.0.0.7",
        endpoint: String = "192.0.2.10:51820"
    ) -> PeerResponse {
        PeerResponse(peerId: "p1", serverPublicKey: Fixtures.key(2), serverEndpoint: endpoint,
                     serverEndpointLan: "192.0.2.10:51820", serverEndpointWan: "vpn.example.test:443",
                     assignedIp: assigned, dnsServer: dns, presharedKey: psk, clientAllowedIps: allowed)
    }

    func testProfileCarriesPresharedKeyAndLanWan() throws {
        let profile = try VPNProfile.make(peer: peer(), privateKey: Fixtures.key(1), excludeLAN: false)
        XCTAssertEqual(profile.presharedKey, Fixtures.key(3))
        XCTAssertEqual(profile.endpoint, "192.0.2.10:51820")
        XCTAssertEqual(profile.lanEndpoint, "192.0.2.10:51820")
        XCTAssertEqual(profile.wanEndpoint, "vpn.example.test:443")
        XCTAssertEqual(profile.mtu, 1280)
        XCTAssertEqual(profile.persistentKeepalive, 25)
        XCTAssertEqual(profile.normalizedAddresses, ["10.0.0.7/32"])
        XCTAssertEqual(profile.allowedIPs, ["0.0.0.0/0", "::/0"])
    }

    func testEmptyPresharedKeyIsOmitted() throws {
        let profile = try VPNProfile.make(peer: peer(psk: "  "), privateKey: Fixtures.key(1), excludeLAN: false)
        XCTAssertNil(profile.presharedKey)
        XCTAssertFalse(WgQuickConfig.render(profile).contains("PresharedKey"))
    }

    func testMissingDNSAbortsInsteadOfUsingPublicDNS() {
        XCTAssertThrowsError(try VPNProfile.make(peer: peer(dns: nil), privateKey: Fixtures.key(1), excludeLAN: false)) {
            XCTAssertEqual($0 as? ProfileError, .missingDNS)
        }
        XCTAssertThrowsError(try VPNProfile.make(peer: peer(dns: " "), privateKey: Fixtures.key(1), excludeLAN: false))
    }

    func testInvalidValuesAreRejected() {
        XCTAssertThrowsError(try VPNProfile.make(peer: peer(psk: "short"), privateKey: Fixtures.key(1), excludeLAN: false))
        XCTAssertThrowsError(try VPNProfile.make(peer: peer(), privateKey: "nope", excludeLAN: false))
        XCTAssertThrowsError(try VPNProfile.make(peer: peer(assigned: "10.0.0.300"), privateKey: Fixtures.key(1), excludeLAN: false))
        XCTAssertThrowsError(try VPNProfile.make(peer: peer(endpoint: "vpn.example.test"), privateKey: Fixtures.key(1), excludeLAN: false))
        XCTAssertThrowsError(try VPNProfile.make(peer: peer(allowed: ["0.0.0.0/33"]), privateKey: Fixtures.key(1), excludeLAN: false))
    }

    func testDefaultAllowedIPsWhenServerSendsNone() throws {
        let profile = try VPNProfile.make(peer: peer(allowed: nil), privateKey: Fixtures.key(1), excludeLAN: false)
        XCTAssertEqual(profile.allowedIPs, ["0.0.0.0/0", "::/0"])
    }

    func testExcludeLanReplacesOnlyDefaultIPv4Route() throws {
        let profile = try VPNProfile.make(peer: peer(), privateKey: Fixtures.key(1), excludeLAN: true)
        XCTAssertFalse(profile.allowedIPs.contains("0.0.0.0/0"))
        XCTAssertTrue(profile.allowedIPs.contains("::/0"))
        XCTAssertTrue(profile.allowedIPs.contains("8.0.0.0/7"))
        XCTAssertFalse(profile.allowedIPs.contains { $0.hasPrefix("10.") || $0.hasPrefix("192.168.") })
        XCTAssertEqual(LANExclusion.resolveAllowedIPs(["10.0.0.0/24"], excludeLAN: true), ["10.0.0.0/24"])
    }

    func testWgQuickRoundTrip() throws {
        let profile = try VPNProfile.make(peer: peer(), privateKey: Fixtures.key(1), excludeLAN: false)
        let text = WgQuickConfig.render(profile)
        XCTAssertTrue(text.contains("PresharedKey = \(Fixtures.key(3))"))
        XCTAssertTrue(text.contains("MTU = 1280"))
        let parsed = try WgQuickConfig.parse(text, peerId: "p1")
        XCTAssertEqual(parsed.privateKey, profile.privateKey)
        XCTAssertEqual(parsed.presharedKey, profile.presharedKey)
        XCTAssertEqual(parsed.endpoint, profile.endpoint)
        XCTAssertEqual(parsed.allowedIPs, profile.allowedIPs)
        XCTAssertEqual(parsed.dns, ["10.0.0.1"])
        XCTAssertEqual(parsed.persistentKeepalive, 25)
    }

    func testWgQuickParserIgnoresCommentsAndCase() throws {
        let text = """
        # exported
        [interface]
        privatekey = \(Fixtures.key(1))
        Address = 10.0.0.2/32
        DNS = 10.0.0.1, 10.0.0.2
        [Peer]
        PublicKey = \(Fixtures.key(2)) # server
        Endpoint = [2001:db8::1]:51820
        AllowedIPs = 0.0.0.0/0
        AllowedIPs = ::/0
        """
        let parsed = try WgQuickConfig.parse(text)
        XCTAssertEqual(parsed.dns, ["10.0.0.1", "10.0.0.2"])
        XCTAssertEqual(parsed.allowedIPs, ["0.0.0.0/0", "::/0"])
        XCTAssertEqual(parsed.endpoint, "[2001:db8::1]:51820")
        XCTAssertNil(parsed.presharedKey)
        XCTAssertEqual(parsed.persistentKeepalive, 0)
    }

    func testWgQuickParserRejectsMissingPieces() {
        XCTAssertThrowsError(try WgQuickConfig.parse("[Interface]\nPrivateKey = \(Fixtures.key(1))\n"))
        XCTAssertThrowsError(try WgQuickConfig.parse("[Peer]\nPublicKey = \(Fixtures.key(2))\n"))
    }

    func testProfileJSONRoundTrip() throws {
        let profile = try VPNProfile.make(peer: peer(), privateKey: Fixtures.key(1), excludeLAN: false)
        let data = try JSONEncoder().encode(profile)
        XCTAssertEqual(try JSONDecoder().decode(VPNProfile.self, from: data), profile)
    }

    func testEndpointValidation() {
        XCTAssertTrue(Endpoint.isValid("vpn.example.test:443"))
        XCTAssertTrue(Endpoint.isValid("192.0.2.1:51820"))
        XCTAssertTrue(Endpoint.isValid("[2001:db8::1]:51820"))
        XCTAssertFalse(Endpoint.isValid("192.0.2.1"))
        XCTAssertFalse(Endpoint.isValid("192.0.2.1:0"))
        XCTAssertFalse(Endpoint.isValid("192.0.2.1:70000"))
        XCTAssertFalse(Endpoint.isValid("2001:db8::1:51820"))
        XCTAssertFalse(Endpoint.isValid(":443"))
    }

    func testCIDRParsing() {
        XCTAssertEqual(CIDR.parse("10.0.0.7", defaultHostPrefix: true)?.description, "10.0.0.7/32")
        XCTAssertEqual(CIDR.parse("fd00::7", defaultHostPrefix: true)?.description, "fd00::7/128")
        XCTAssertNil(CIDR.parse("10.0.0.7", defaultHostPrefix: false))
        XCTAssertNil(CIDR.parse("10.0.0.0/33", defaultHostPrefix: false))
        XCTAssertNil(CIDR.parse("::/129", defaultHostPrefix: false))
        XCTAssertNotNil(CIDR.parse("::/0", defaultHostPrefix: false))
    }

    func testStealthPathPrefixNormalization() {
        XCTAssertEqual(StealthPath.normalizePrefix("0123abcdef"), "0123abcdef")
        XCTAssertEqual(StealthPath.normalizePrefix("/0123abcdef"), "0123abcdef")
        XCTAssertEqual(StealthPath.normalizePrefix(" a-b_c.d~e "), "a-b_c.d~e")
        XCTAssertNil(StealthPath.normalizePrefix(""))
        XCTAssertNil(StealthPath.normalizePrefix("/"))
        XCTAssertNil(StealthPath.normalizePrefix("a/b"))
        XCTAssertNil(StealthPath.normalizePrefix("a..b"))
        XCTAssertNil(StealthPath.normalizePrefix("//abc"))
        XCTAssertNil(StealthPath.normalizePrefix(String(repeating: "a", count: 129)))
        XCTAssertNotNil(StealthPath.normalizePrefix(String(repeating: "a", count: 128)))
    }

    func testRuntimeStatsParsing() {
        let uapi = """
        private_key=redacted
        listen_port=51820
        public_key=aa
        endpoint=192.0.2.10:51820
        last_handshake_time_sec=1700000000
        last_handshake_time_nsec=500000000
        rx_bytes=1024
        tx_bytes=2048
        public_key=bb
        last_handshake_time_sec=0
        last_handshake_time_nsec=0
        rx_bytes=1
        tx_bytes=2
        errno=0
        """
        let stats = RuntimeStats.parse(uapi: uapi)
        XCTAssertEqual(stats.rxBytes, 1025)
        XCTAssertEqual(stats.txBytes, 2050)
        XCTAssertEqual(stats.lastHandshakeEpoch, 1_700_000_000.5, accuracy: 0.001)
        XCTAssertTrue(stats.hasHandshake)
        XCTAssertFalse(RuntimeStats.parse(uapi: "").hasHandshake)
    }

    func testSharedConfigurationIgnoresUnexpandedBuildSettings() {
        let config = SharedConfiguration.fromBundle(Bundle(for: ConfigParsingTests.self))
        XCTAssertFalse(config.appGroup.contains("$("))
    }

    #if canImport(CryptoKit)
    func testKeyPairIsWireGuardShaped() throws {
        let pair = WireGuardKeyPair()
        XCTAssertTrue(WireGuardKey.isValid(pair.privateKey))
        XCTAssertTrue(WireGuardKey.isValid(pair.publicKey))
        let derived = try XCTUnwrap(WireGuardKeyPair(privateKey: pair.privateKey))
        XCTAssertEqual(derived.publicKey, pair.publicKey)
        XCTAssertNotEqual(WireGuardKeyPair().privateKey, pair.privateKey)
    }
    #endif
}
