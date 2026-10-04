import { useEffect, useRef, type Ref } from "react";

export type HeroPhase = "ready" | "upsell" | "checking" | "connecting" | "protected";

type HeroConnectControlProps = {
  phase: HeroPhase;
  onClick: (() => void) | null;
};

/** Compose FastOutSlowInEasing: cubic-bezier(0.4, 0, 0.2, 1). */
function fastOutSlowIn(amount: number): number {
  if (amount <= 0) return 0;
  if (amount >= 1) return 1;
  let lo = 0;
  let hi = 1;
  for (let i = 0; i < 16; i += 1) {
    const mid = (lo + hi) / 2;
    const x = sampleBezier(mid, 0.4, 0.2);
    if (x < amount) lo = mid;
    else hi = mid;
  }
  return sampleBezier((lo + hi) / 2, 0, 1);
}

function sampleBezier(t: number, p1: number, p2: number): number {
  const u = 1 - t;
  return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t;
}

/** Same 1.28s ease-in-out as Android. Absolute time, so a dropped frame catches up. */
export function pulseUnitFromElapsed(nowMs: number): number {
  const half = 1280;
  const t = ((nowMs % (half * 2)) + half * 2) % (half * 2);
  if (t < half) return fastOutSlowIn(t / half);
  return fastOutSlowIn(1 - (t - half) / half);
}

function actionName(phase: HeroPhase): string | null {
  if (phase === "ready") return "Connect";
  if (phase === "upsell") return "Get Premium";
  if (phase === "protected") return "Disconnect";
  return null;
}

function LockGlyph({ open }: { open: boolean }) {
  return (
    <svg className="hero-lock" viewBox="0 0 24 24" aria-hidden="true">
      <rect x="5" y="11" width="14" height="10" rx="2.5" fill="none" stroke="currentColor" strokeWidth="1.8" />
      {open ? (
        <path
          d="M8 11V8.2a4 4 0 0 1 7.7-1.5"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
        />
      ) : (
        <path
          d="M8 11V8a4 4 0 0 1 8 0v3"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
        />
      )}
      <circle cx="12" cy="16" r="1.35" fill="currentColor" />
    </svg>
  );
}

/**
 * Lock circle only. The idle scale and the connecting arc are sampled from
 * elapsed time, not from connection progress, so a stalled backend cannot
 * freeze or restart them.
 */
export function HeroConnectControl({ phase, onClick }: HeroConnectControlProps) {
  const circleRef = useRef<HTMLElement | null>(null);
  const arcRef = useRef<SVGCircleElement | null>(null);
  const phaseRef = useRef(phase);
  const pressRef = useRef(1);
  const clickable = onClick != null && actionName(phase) != null;
  phaseRef.current = phase;

  useEffect(() => {
    const media = window.matchMedia("(prefers-reduced-motion: reduce)");
    let frame = 0;
    let seen = false;
    let wasSecured = false;
    let arrival = 1;
    let arrivalFrom = 1;
    let arrivalStart = 0;
    let arrivalActive = false;

    const tick = (now: number) => {
      const motion = !media.matches;
      const current = phaseRef.current;
      const secured = current === "protected";
      if (!seen) {
        seen = true;
        wasSecured = secured;
      } else if (motion && secured !== wasSecured) {
        wasSecured = secured;
        arrivalFrom = secured ? 0.86 : 1.08;
        arrivalStart = now;
        arrivalActive = true;
      }
      if (arrivalActive) {
        const t = Math.min(1, (now - arrivalStart) / 420);
        const eased = 1 - (1 - t) ** 3;
        arrival = arrivalFrom + (1 - arrivalFrom) * eased;
        if (t >= 1) {
          arrival = 1;
          arrivalActive = false;
        }
      }
      const amplitude = !motion ? 0 : secured ? 0.06 : 0.16;
      const scale = (1 + pulseUnitFromElapsed(now) * amplitude) * pressRef.current * arrival;
      const circle = circleRef.current;
      if (circle) circle.style.transform = `scale(${scale})`;
      const arc = arcRef.current;
      if (arc) {
        const rotation = motion ? ((now % 1100) / 1100) * 360 : 0;
        arc.setAttribute("transform", `rotate(${rotation - 90} 18 18)`);
      }
      frame = window.requestAnimationFrame(tick);
    };

    frame = window.requestAnimationFrame(tick);
    const onMotion = () => undefined;
    media.addEventListener("change", onMotion);
    return () => {
      window.cancelAnimationFrame(frame);
      media.removeEventListener("change", onMotion);
    };
  }, []);

  const showNotConnected = phase === "ready" || phase === "upsell" || phase === "checking";
  const caption =
    phase === "checking" ? "Checking plan…" : phase === "connecting" ? "Connecting…" : phase === "protected" ? "Protected" : null;
  const name = actionName(phase);
  const edgeClass =
    phase === "protected" ? "is-protected" : phase === "upsell" ? "is-upsell" : "is-idle";

  const setPressed = (pressed: boolean) => {
    pressRef.current = pressed && clickable ? 0.94 : 1;
  };

  const circleRefCallback: Ref<HTMLButtonElement & HTMLDivElement> = (node) => {
    circleRef.current = node;
  };
  const circle = (
    <>
      {phase === "checking" || phase === "connecting" ? (
        <svg className="hero-arc" viewBox="0 0 36 36" aria-hidden="true">
          <circle
            ref={arcRef}
            cx="18"
            cy="18"
            r="14"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.5"
            strokeLinecap="round"
            strokeDasharray="24.4 63.6"
          />
        </svg>
      ) : (
        <LockGlyph open={phase === "ready"} />
      )}
    </>
  );

  return (
    <div className={`hero-connect ${edgeClass}`}>
      {showNotConnected && (
        <div className="hero-not-connected">
          <p>Not connected</p>
        </div>
      )}
      {clickable && name ? (
        <button
          ref={circleRefCallback}
          type="button"
          className="hero-circle"
          aria-label={name}
          aria-describedby={phase === "protected" ? "hero-protected-state" : phase === "ready" ? "hero-idle-state" : undefined}
          onClick={onClick}
          onPointerDown={() => setPressed(true)}
          onPointerUp={() => setPressed(false)}
          onPointerLeave={() => setPressed(false)}
          onPointerCancel={() => setPressed(false)}
        >
          {circle}
        </button>
      ) : (
        <div ref={circleRefCallback} className="hero-circle" aria-hidden="true">
          {circle}
        </div>
      )}
      {phase === "ready" && <span id="hero-idle-state" className="sr-only">Not connected</span>}
      {(caption || phase === "upsell") && (
        <div className={`hero-caption${phase === "upsell" ? " is-upsell" : ""}`}>
          {caption && <p className={phase === "protected" ? "is-protected" : ""}>{caption}</p>}
          {phase === "protected" && (
            <>
              <span id="hero-protected-state" className="sr-only">VPN connected</span>
              <small>Your connection is encrypted</small>
            </>
          )}
          {phase === "upsell" && <small className="hero-premium">Premium required</small>}
        </div>
      )}
    </div>
  );
}
