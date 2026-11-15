import SwiftUI
import VeritasCore
import WebKit

/// Cloudflare Turnstile check, hosted at veritasvpn.cloud/turnstile-mobile-v2.
/// The page talks to `window.VeritasTurnstile.postMessage(json)` (the Android
/// bridge name); a user script forwards that to WKScriptMessageHandler.
@MainActor
struct TurnstileSheet: View {
    let onToken: (String) -> Void
    let onCancel: () -> Void

    @State private var interactive = false
    @State private var failure: String?
    @State private var attempt = 0

    var body: some View {
        PremiumBackdrop {
            VStack(alignment: .leading, spacing: 14) {
                Eyebrow(text: "SECURITY CHECK")
                Text(L("Securing your request…"))
                    .font(VFont.titleLarge)
                    .foregroundStyle(Palette.paper)
                Text(L(interactive ? "Complete the check below to continue." : "This takes a moment and runs only for this request."))
                    .font(VFont.bodyMedium)
                    .foregroundStyle(Palette.paperMuted)
                if let failure {
                    StatusBanner(text: failure, tint: Palette.warning, actionTitle: "Try again") {
                        self.failure = nil
                        interactive = false
                        attempt += 1
                    }
                } else {
                    TurnstileWebView(
                        onInteractive: { interactive = true },
                        onToken: { token in
                            if !token.isEmpty { onToken(token) }
                        },
                        onFailure: { failure = $0 }
                    )
                    .id(attempt)
                    .frame(height: interactive ? 96 : 1)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    if !interactive {
                        ProgressView().tint(Palette.cyan).frame(maxWidth: .infinity)
                    }
                }
                Button(L("Cancel"), action: onCancel)
                    .buttonStyle(SecondaryButtonStyle())
            }
            .padding(24)
            .frame(maxWidth: 480)
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 300)
        #endif
    }
}

@MainActor
struct TurnstileWebView {
    let onInteractive: () -> Void
    let onToken: (String) -> Void
    /// Receives a descriptive sentence, never the page's own error text.
    let onFailure: (String) -> Void

    static let bridgeScript = """
    window.VeritasTurnstile = {
      postMessage: function (raw) {
        try { window.webkit.messageHandlers.VeritasTurnstile.postMessage(String(raw)); } catch (e) {}
      }
    };
    """

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    fileprivate func makeWebView(coordinator: Coordinator) -> WKWebView {
        let controller = WKUserContentController()
        controller.addUserScript(WKUserScript(source: Self.bridgeScript, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        controller.add(coordinator, name: "VeritasTurnstile")
        let configuration = WKWebViewConfiguration()
        configuration.userContentController = controller
        configuration.websiteDataStore = .nonPersistent()
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = coordinator
        #if os(iOS)
        webView.isOpaque = false
        webView.backgroundColor = UIColor(red: 0x06 / 255, green: 0x10 / 255, blue: 0x1C / 255, alpha: 1)
        webView.scrollView.isScrollEnabled = false
        #endif
        coordinator.webView = webView
        webView.load(URLRequest(url: SupportLinks.turnstilePage))
        return webView
    }

    @MainActor
    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        var parent: TurnstileWebView
        weak var webView: WKWebView?
        private var loadFailures = 0
        private var finished = false

        init(parent: TurnstileWebView) { self.parent = parent }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let raw = message.body as? String,
                  let data = raw.data(using: .utf8),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let type = json["type"] as? String else { return }
            switch type {
            case "ready":
                webView?.evaluateJavaScript("window.veritasTurnstileExecute && window.veritasTurnstileExecute()")
            case "interactive-required":
                parent.onInteractive()
            case "token":
                if let token = json["token"] as? String, !token.isEmpty, !finished {
                    finished = true
                    parent.onToken(token)
                }
            case "expired":
                parent.onToken("")
            case "error":
                parent.onFailure(Messages.securityCheckFailed)
            default:
                break
            }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            mainFrameFailed(webView)
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            mainFrameFailed(webView)
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
            // Only the check page and Cloudflare's challenge frames load here.
            let url = navigationAction.request.url
            let host = url?.host ?? ""
            let allowed = host == "veritasvpn.cloud" || host.hasSuffix(".veritasvpn.cloud")
                || host == "challenges.cloudflare.com" || url?.scheme == "about"
            return allowed ? .allow : .cancel
        }

        private func mainFrameFailed(_ webView: WKWebView) {
            loadFailures += 1
            if loadFailures <= 1 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak webView] in
                    webView?.load(URLRequest(url: SupportLinks.turnstilePage))
                }
            } else {
                parent.onFailure(Messages.securityCheckUnavailable)
            }
        }
    }
}

#if os(iOS)
extension TurnstileWebView: UIViewRepresentable {
    func makeUIView(context: Context) -> WKWebView { makeWebView(coordinator: context.coordinator) }
    func updateUIView(_ uiView: WKWebView, context: Context) { context.coordinator.parent = self }
    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.stopLoading()
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "VeritasTurnstile")
    }
}
#else
extension TurnstileWebView: NSViewRepresentable {
    func makeNSView(context: Context) -> WKWebView { makeWebView(coordinator: context.coordinator) }
    func updateNSView(_ nsView: WKWebView, context: Context) { context.coordinator.parent = self }
    static func dismantleNSView(_ nsView: WKWebView, coordinator: Coordinator) {
        nsView.stopLoading()
        nsView.configuration.userContentController.removeScriptMessageHandler(forName: "VeritasTurnstile")
    }
}
#endif
