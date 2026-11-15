import XCTest
@testable import VeritasCore

final class EndpointSelectorTests: XCTestCase {
    let lan = "192.168.77.6:51820"
    let wan = "vpn.example.test:443"

    func testHostParsing() {
        XCTAssertEqual(EndpointSelector.host("192.168.77.6:51820"), "192.168.77.6")
        XCTAssertEqual(EndpointSelector.host("[2001:db8::1]:443"), "2001:db8::1")
        XCTAssertEqual(EndpointSelector.host("vpn.example.test"), "vpn.example.test")
    }

    func testOnNodeLanPrefersLanEndpoint() {
        XCTAssertEqual(EndpointSelector.choose(current: wan, lan: lan, wan: wan, underlayIPv4s: ["192.168.77.5"]), lan)
    }

    func testOffLanUsesWan() {
        XCTAssertEqual(EndpointSelector.choose(current: lan, lan: lan, wan: wan, underlayIPv4s: ["10.20.30.40"]), wan)
    }

    func testNoUnderlayKeepsCurrent() {
        XCTAssertEqual(EndpointSelector.choose(current: lan, lan: lan, wan: wan, underlayIPv4s: []), lan)
    }

    func testProbeOrderIsWanCurrentLan() {
        XCTAssertEqual(EndpointSelector.probeOrder(current: lan, lan: lan, wan: wan), [wan, lan])
        XCTAssertEqual(EndpointSelector.probeOrder(current: "192.168.77.6:51820", lan: nil, wan: wan), [wan, lan])
        XCTAssertEqual(EndpointSelector.probeOrder(current: wan, lan: nil, wan: nil), [wan])
    }

    func testRoamOrderStartsWithPreferred() {
        XCTAssertEqual(EndpointSelector.roamOrder(current: wan, lan: lan, wan: wan, underlayIPv4s: ["192.168.77.9"]), [lan, wan])
        XCTAssertEqual(EndpointSelector.roamOrder(current: lan, lan: lan, wan: wan, underlayIPv4s: ["172.20.10.2"]), [wan, lan])
    }

    func testRFC1918() {
        XCTAssertTrue(EndpointSelector.isRFC1918("10.1.2.3"))
        XCTAssertTrue(EndpointSelector.isRFC1918("172.16.0.1"))
        XCTAssertFalse(EndpointSelector.isRFC1918("172.32.0.1"))
        XCTAssertTrue(EndpointSelector.isRFC1918("192.168.1.1"))
        XCTAssertFalse(EndpointSelector.isRFC1918("8.8.8.8"))
        XCTAssertFalse(EndpointSelector.isRFC1918("vpn.example.test"))
    }
}

final class HeroLogicTests: XCTestCase {
    func testPhases() {
        XCTAssertEqual(HeroLogic.phase(connected: false, connecting: false, reconnecting: false, handshakeOK: false, billingReady: false, isPremium: false), .checking)
        XCTAssertEqual(HeroLogic.phase(connected: false, connecting: false, reconnecting: false, handshakeOK: false, billingReady: true, isPremium: false), .upsell)
        XCTAssertEqual(HeroLogic.phase(connected: false, connecting: false, reconnecting: false, handshakeOK: false, billingReady: true, isPremium: true), .ready)
        XCTAssertEqual(HeroLogic.phase(connected: false, connecting: true, reconnecting: false, handshakeOK: false, billingReady: true, isPremium: true), .connecting)
        XCTAssertEqual(HeroLogic.phase(connected: true, connecting: false, reconnecting: false, handshakeOK: true, billingReady: true, isPremium: true), .protected)
        XCTAssertEqual(HeroLogic.phase(connected: true, connecting: false, reconnecting: true, handshakeOK: true, billingReady: true, isPremium: true), .reconnecting)
        XCTAssertEqual(HeroLogic.phase(connected: true, connecting: false, reconnecting: false, handshakeOK: false, billingReady: true, isPremium: true), .reconnecting)
    }

    func testOneTapActions() {
        XCTAssertEqual(HeroLogic.mainAction(connecting: false, billingReady: true, connected: false, signInAgain: false, isPremium: true, reconnecting: false), .connect)
        XCTAssertEqual(HeroLogic.mainAction(connecting: false, billingReady: true, connected: false, signInAgain: false, isPremium: false, reconnecting: false), .plans)
        XCTAssertEqual(HeroLogic.mainAction(connecting: true, billingReady: true, connected: false, signInAgain: false, isPremium: true, reconnecting: false), .disconnect)
        XCTAssertEqual(HeroLogic.mainAction(connecting: false, billingReady: false, connected: false, signInAgain: false, isPremium: true, reconnecting: false), .none)
        XCTAssertEqual(HeroLogic.mainAction(connecting: false, billingReady: true, connected: true, signInAgain: true, isPremium: true, reconnecting: false), .signIn)
    }

    func testMarkRampHoldsAcrossConnectingToProtected() {
        let start = HeroLogic.nextLiveStart(previous: nil, phase: .connecting, now: 100)
        XCTAssertEqual(start, 100)
        XCTAssertEqual(HeroLogic.nextLiveStart(previous: start, phase: .connecting, now: 101), 100)
        XCTAssertEqual(HeroLogic.nextLiveStart(previous: start, phase: .protected, now: 102), 100)
        XCTAssertNil(HeroLogic.nextLiveStart(previous: start, phase: .ready, now: 103))
    }

    func testMarkLook() {
        XCTAssertEqual(HeroLogic.markLook(phase: .ready, motion: true, elapsedLive: nil), HeroLogic.dim)
        XCTAssertEqual(HeroLogic.markLook(phase: .protected, motion: true, elapsedLive: nil), HeroLogic.full)
        XCTAssertEqual(HeroLogic.markLook(phase: .connecting, motion: false, elapsedLive: 0), HeroLogic.full)
        let mid = HeroLogic.markLook(phase: .connecting, motion: true, elapsedLive: 0.7)
        XCTAssertGreaterThan(mid.opacity, HeroLogic.dim.opacity)
        XCTAssertLessThan(mid.opacity, HeroLogic.full.opacity)
    }

    func testPulseNeverFreezesAndStaysInRange() {
        var values = Set<Int>()
        for step in 0..<50 {
            let value = HeroLogic.pulseUnit(now: Double(step) * 0.1)
            XCTAssertGreaterThanOrEqual(value, 0)
            XCTAssertLessThanOrEqual(value, 1.0001)
            values.insert(Int(value * 100))
        }
        XCTAssertGreaterThan(values.count, 10)
        XCTAssertEqual(HeroLogic.pulseAmplitude(.protected, motion: true), 0.06)
        XCTAssertEqual(HeroLogic.pulseAmplitude(.ready, motion: false), 0)
    }

    func testEasing() {
        XCTAssertEqual(HeroLogic.easeOutSlowIn(0), 0, accuracy: 0.001)
        XCTAssertEqual(HeroLogic.easeOutSlowIn(1), 1, accuracy: 0.001)
        XCTAssertGreaterThan(HeroLogic.easeOutSlowIn(0.5), 0.5)
    }

    func testShieldRowsHideDisabledCounts() {
        var today = ShieldCategoryCounts()
        today.malware = 2; today.phishing = 1; today.ads = 9
        let rows = ShieldRow.rows(policy: ShieldPolicy(), blocks: ShieldBlocks(today: today, week: today))
        XCTAssertEqual(rows.first { $0.group == .malicious }?.today, 3)
        XCTAssertEqual(rows.first { $0.group == .ads }?.today, 0)
        XCTAssertEqual(rows.first { $0.group == .ads }?.enabled, false)
    }

    func testPlanCatalogMatchesPlayProductIds() {
        XCTAssertEqual(PlanCatalog.productIds, ["premium_monthly", "premium_annual"])
    }
}
