import SwiftUI

/// Server map, a port of Android's ConnectionMap. Shows only our server in
/// Asunción, Paraguay. The user's own position is never requested or drawn.
@MainActor
struct ConnectionMap: View {
    let connected: Bool
    let connecting: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private static let serverLatitude = -25.2867
    private static let serverLongitude = -57.3333

    var body: some View {
        GeometryReader { geo in
            let zoom = 1.10
            let mapWidth = geo.size.width * zoom
            let mapHeight = geo.size.height * zoom
            let padX = (geo.size.width - mapWidth) / 2
            let padY = (geo.size.height - mapHeight) / 2
            let project: (Double, Double) -> CGPoint = { lat, lon in
                CGPoint(x: padX + (lon + 180) / 360 * mapWidth, y: padY + (90 - lat) / 180 * mapHeight)
            }
            ZStack(alignment: .topLeading) {
                LinearGradient(colors: [Palette.ink3, Palette.ink2], startPoint: .top, endPoint: .bottom)
                Canvas { context, _ in
                    var grid = Path()
                    for lon in [-120.0, -60, 0, 60, 120] {
                        grid.move(to: project(75, lon))
                        grid.addLine(to: project(-60, lon))
                    }
                    for lat in [-45.0, 0, 45] {
                        grid.move(to: project(lat, -175))
                        grid.addLine(to: project(lat, 175))
                    }
                    context.stroke(grid, with: .color(Palette.line.opacity(0.25)), lineWidth: 1)
                }
                Image("WorldMap")
                    .resizable()
                    .frame(width: mapWidth, height: mapHeight)
                    .offset(x: padX, y: padY)
                    .accessibilityHidden(true)
                TimelineView(.animation(minimumInterval: nil, paused: reduceMotion || !(connected || connecting))) { context in
                    let pulse = pinPulse(context.date)
                    let server = project(Self.serverLatitude, Self.serverLongitude)
                    ZStack {
                        Circle().fill(Palette.royalHover.opacity(0.22)).frame(width: 24 * pulse, height: 24 * pulse)
                        Circle().fill(Palette.royalHover).frame(width: 12, height: 12)
                        Circle().fill(Palette.paper).frame(width: 4, height: 4)
                    }
                    .position(server)
                }
            }
        }
        .frame(height: 210)
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L("Map showing the VeritasVPN server in Asunción, Paraguay"))
    }

    /// 0.92 ↔ 1.18 over 1.4 s, like Android, while connecting or connected.
    private func pinPulse(_ date: Date) -> CGFloat {
        guard !reduceMotion, connected || connecting else { return 1 }
        let period = 1.4
        let t = date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: period * 2)
        let unit = t < period ? t / period : 1 - (t - period) / period
        return 0.92 + (1.18 - 0.92) * unit
    }
}

@MainActor
struct NetworkMapView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        SettingsScreen(eyebrow: "NETWORK MAP", title: "Server location") {
            ConnectionMap(connected: model.connected, connecting: model.connecting)
            Text("Asunción, Paraguay")
                .font(VFont.body(13))
                .foregroundStyle(Palette.paperMuted)
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("CONNECTION"))
                        .font(VFont.body(10)).tracking(1.2)
                        .foregroundStyle(Palette.paperDim)
                    Text(L(model.connected ? "Encrypted route active" : (model.connecting ? "Establishing route…" : "No secure route")))
                        .font(VFont.body(15, .semibold))
                        .foregroundStyle(Palette.paper)
                }
                Spacer()
                Text(L(model.connected ? "SECURED" : (model.connecting ? "CONNECTING" : "OFFLINE")))
                    .font(VFont.body(11, .heavy))
                    .foregroundStyle(model.connected ? Palette.success : (model.connecting ? Palette.cyanHover : Palette.warning))
            }
            .padding(16)
            .glassSurface()
        }
    }
}
