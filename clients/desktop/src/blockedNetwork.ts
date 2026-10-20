export const BLOCKED_AFTER_MS = 10_000;
export const RX_HIDE_SAMPLES = 2;

export const BLOCKED_MESSAGE = "This network may be blocking VPN connections.";
export const OTHER_NETWORK = "Try another network.";

export type BlockedClock = {
  stallSinceMs: number;
  rxAtStall: number;
  visible: boolean;
  rxRiseSamples: number;
};

export type BlockedSample = {
  nowMs: number;
  connecting: boolean;
  reconnecting: boolean;
  connected: boolean;
  handshakeOk: boolean;
  handshakeMs: number;
  attemptStartedMs: number;
  rxBytes: number;
  networkValidated: boolean;
  alreadyStealth: boolean;
  dismissed: boolean;
  clock: BlockedClock;
};

export type BlockedStep = {
  visible: boolean;
  offerStealth: boolean;
  suggestOtherNetwork: boolean;
  clock: BlockedClock;
};

export function initialBlockedClock(): BlockedClock {
  return { stallSinceMs: 0, rxAtStall: 0, visible: false, rxRiseSamples: 0 };
}

export function blockedSessionLive(sample: BlockedSample): boolean {
  return sample.connecting || sample.reconnecting || (sample.connected && !sample.handshakeOk);
}

export function handshakeSucceeded(sample: BlockedSample): boolean {
  if (sample.connecting || sample.reconnecting) {
    return sample.handshakeMs > 0 && sample.attemptStartedMs > 0 && sample.handshakeMs >= sample.attemptStartedMs;
  }
  return sample.handshakeOk;
}

export function advanceBlocked(sample: BlockedSample): BlockedStep {
  if (!blockedSessionLive(sample) || handshakeSucceeded(sample) || !sample.networkValidated) {
    return { visible: false, offerStealth: false, suggestOtherNetwork: false, clock: initialBlockedClock() };
  }
  if (sample.dismissed) {
    return {
      visible: false,
      offerStealth: false,
      suggestOtherNetwork: false,
      clock: { ...sample.clock, visible: false },
    };
  }
  const clock = sample.clock;
  const rxIncreased = clock.stallSinceMs !== 0 && sample.rxBytes > clock.rxAtStall;
  if (rxIncreased) {
    const rises = clock.rxRiseSamples + 1;
    if (!clock.visible || rises >= RX_HIDE_SAMPLES) {
      return {
        visible: false,
        offerStealth: false,
        suggestOtherNetwork: false,
        clock: { stallSinceMs: sample.nowMs, rxAtStall: sample.rxBytes, visible: false, rxRiseSamples: 0 },
      };
    }
    return present(sample.alreadyStealth, { ...clock, rxRiseSamples: rises, visible: true });
  }
  const stallSince = clock.stallSinceMs === 0 ? sample.nowMs : clock.stallSinceMs;
  const rxBase = clock.stallSinceMs === 0 ? sample.rxBytes : clock.rxAtStall;
  const visible = sample.nowMs - stallSince >= BLOCKED_AFTER_MS;
  return present(sample.alreadyStealth, {
    stallSinceMs: stallSince,
    rxAtStall: rxBase,
    visible,
    rxRiseSamples: 0,
  });
}

function present(alreadyStealth: boolean, clock: BlockedClock): BlockedStep {
  if (!clock.visible) {
    return { visible: false, offerStealth: false, suggestOtherNetwork: false, clock };
  }
  return {
    visible: true,
    offerStealth: !alreadyStealth,
    suggestOtherNetwork: alreadyStealth,
    clock,
  };
}
