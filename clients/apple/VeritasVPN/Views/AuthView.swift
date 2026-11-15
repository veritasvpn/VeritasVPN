import SwiftUI
import VeritasCore

/// Sign in / sign up / Account ID / anonymous, a port of Android's AuthScreen.
@MainActor
struct AuthView: View {
    enum Mode: Equatable { case signIn, signUp, accountId, forgot, verify(email: String) }

    /// What to run after a Turnstile token arrives.
    private enum PendingCheck { case signIn, signUp, accountId, anonymous }

    @EnvironmentObject private var model: AppModel
    @State private var mode: Mode = .signIn
    @State private var email = ""
    @State private var password = ""
    @State private var confirmation = ""
    @State private var accountId = ""
    @State private var showPassword = false
    @State private var busy = false
    @State private var error: String?
    @State private var info: String?
    @State private var pendingCheck: PendingCheck?
    @State private var resetCooldownUntil: Date?

    var body: some View {
        PremiumBackdrop {
            ScrollView {
                VStack(spacing: 18) {
                    Image("VeritasLogo")
                        .resizable().scaledToFit()
                        .frame(width: 88, height: 88)
                        .accessibilityLabel(L("VeritasVPN shield"))
                        .padding(.top, 24)
                    VStack(spacing: 4) {
                        Text("VeritasVPN")
                            .font(VFont.displayMedium)
                            .foregroundStyle(Palette.paper)
                        Text(L("The truth about online privacy"))
                            .font(VFont.bodyMedium)
                            .foregroundStyle(Palette.paperMuted)
                    }
                    if let notice = model.authNotice, error == nil {
                        StatusBanner(text: notice, tint: Palette.warning) { model.authNotice = nil }
                    }
                    form
                        .padding(20)
                        .glassSurface()
                        .disabled(busy)
                }
                .padding(20)
                .frame(maxWidth: 480)
                .frame(maxWidth: .infinity)
            }
        }
        .sheet(isPresented: Binding(get: { pendingCheck != nil }, set: { if !$0 { pendingCheck = nil; busy = false } })) {
            TurnstileSheet { token in
                let check = pendingCheck
                pendingCheck = nil
                Task { await run(check, token: token) }
            } onCancel: {
                pendingCheck = nil
                busy = false
            }
        }
    }

    @ViewBuilder
    private var form: some View {
        VStack(alignment: .leading, spacing: 14) {
            switch mode {
            case .signIn, .signUp:
                emailForm
            case .accountId:
                accountIdForm
            case .forgot:
                forgotForm
            case .verify(let address):
                verifyForm(address)
            }
            if let error {
                Text(L(error))
                    .font(VFont.body(13))
                    .foregroundStyle(Palette.error)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.updatesFrequently)
            }
            if let info {
                Text(L(info))
                    .font(VFont.body(13))
                    .foregroundStyle(Palette.cyanHover)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: Forms

    private var emailForm: some View {
        VStack(alignment: .leading, spacing: 14) {
            Picker("", selection: Binding(get: { mode == .signUp }, set: { switchMode($0 ? .signUp : .signIn) })) {
                Text(L("Sign in")).tag(false)
                Text(L("Sign up")).tag(true)
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            TextField(L("Email"), text: $email)
                .emailEntry()
                .authField()
            passwordField(L("Password"), text: $password, newPassword: mode == .signUp)
            if mode == .signUp {
                passwordField(L("Confirm password"), text: $confirmation, newPassword: true)
                strengthMeter
            }
            Button(L(busy ? "Please wait..." : (mode == .signUp ? "Create account" : "Sign in"))) {
                Task { await submitEmail() }
            }
            .buttonStyle(PrimaryButtonStyle())
            if mode == .signIn {
                Button(L("Forgot password?")) { switchMode(.forgot) }
                    .font(VFont.body(14, .semibold))
                    .foregroundStyle(Palette.cyanHover)
                    .buttonStyle(.plain)
            }
            Divider().overlay(Palette.line)
            Button(L("Sign in with Account ID instead")) { switchMode(.accountId) }
                .buttonStyle(SecondaryButtonStyle())
            Button(L("Skip email — create anonymous account")) {
                error = nil
                busy = true
                pendingCheck = .anonymous
            }
            .buttonStyle(SecondaryButtonStyle(tint: Palette.paperMuted))
            Text(L("Creates an anonymous account. You'll get an Account ID to save — no email required."))
                .font(VFont.bodySmall)
                .foregroundStyle(Palette.paperDim)
        }
    }

    private var accountIdForm: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(L("VeritasVPN Account ID")).font(VFont.titleLarge).foregroundStyle(Palette.paper)
            Text(L("This is the only credential that can restore access to your anonymous account."))
                .font(VFont.bodySmall).foregroundStyle(Palette.paperMuted)
            TextField(L("Account ID"), text: $accountId)
                .plainEntry()
                .font(.system(.body, design: .monospaced))
                .authField()
            Button(L(busy ? "Please wait..." : "Continue")) { Task { await submitAccountId() } }
                .buttonStyle(PrimaryButtonStyle())
            Button(L("Create anonymous account")) {
                error = nil
                busy = true
                pendingCheck = .anonymous
            }
            .buttonStyle(SecondaryButtonStyle())
            Button(L("Use email instead")) { switchMode(.signIn) }
                .font(VFont.body(14, .semibold))
                .foregroundStyle(Palette.cyanHover)
                .buttonStyle(.plain)
        }
    }

    private var forgotForm: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(L("Reset your password")).font(VFont.titleLarge).foregroundStyle(Palette.paper)
            Text(L("Enter your email and we'll send you a secure reset link."))
                .font(VFont.bodySmall).foregroundStyle(Palette.paperMuted)
            TextField(L("Email"), text: $email)
                .emailEntry()
                .authField()
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let remaining = Int(ceil((resetCooldownUntil ?? .distantPast).timeIntervalSince(context.date)))
                Button(remaining > 0 ? L("Try again in %lld s", Int64(remaining)) : L(busy ? "Sending…" : "Send reset link")) {
                    Task { await submitReset() }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(remaining > 0)
            }
            Button(L("Back to sign in")) { switchMode(.signIn) }
                .font(VFont.body(14, .semibold))
                .foregroundStyle(Palette.cyanHover)
                .buttonStyle(.plain)
        }
    }

    private func verifyForm(_ address: String) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Image(systemName: "envelope.badge")
                .font(.system(size: 34))
                .foregroundStyle(Palette.cyan)
                .accessibilityHidden(true)
            Text(L("Verify email")).font(VFont.titleLarge).foregroundStyle(Palette.paper)
            Text(L("We sent a verification link to %@. Open it, then sign in.", address))
                .font(VFont.bodyMedium).foregroundStyle(Palette.paperMuted)
                .fixedSize(horizontal: false, vertical: true)
            Button(L(busy ? "Sending verification email…" : "Resend verification email")) {
                Task { await resend(address) }
            }
            .buttonStyle(PrimaryButtonStyle())
            Button(L("Back to sign in")) { switchMode(.signIn) }
                .buttonStyle(SecondaryButtonStyle())
        }
    }

    private func passwordField(_ title: String, text: Binding<String>, newPassword: Bool) -> some View {
        HStack {
            Group {
                if showPassword {
                    TextField(title, text: text).plainEntry()
                } else {
                    SecureField(title, text: text)
                }
            }
            .passwordContentType(new: newPassword)
            Button {
                showPassword.toggle()
            } label: {
                Image(systemName: showPassword ? "eye.slash" : "eye")
                    .foregroundStyle(Palette.paperDim)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L(showPassword ? "Hide password" : "Show password"))
        }
        .authField()
    }

    private var strengthMeter: some View {
        let score = PasswordRules.strength(password)
        let label = ["Weak", "Weak", "Fair", "Good", "Strong"][min(max(score, 0), 4)]
        let color: Color = score >= 4 ? Palette.success : (score == 3 ? Palette.cyanHover : (score == 2 ? Palette.warning : Palette.error))
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 4) {
                ForEach(0..<4, id: \.self) { index in
                    Capsule().fill(index < score ? color : Palette.line).frame(height: 4)
                }
            }
            Text(L("Password strength") + ": " + L(label))
                .font(VFont.bodySmall)
                .foregroundStyle(Palette.paperDim)
        }
        .opacity(password.isEmpty ? 0 : 1)
        .accessibilityElement(children: .combine)
    }

    // MARK: Actions

    private func switchMode(_ newMode: Mode) {
        mode = newMode
        error = nil
        info = nil
        password = ""
        confirmation = ""
    }

    private func submitEmail() async {
        error = nil
        info = nil
        let trimmed = AuthService.normalizeEmail(email)
        guard !trimmed.isEmpty else { error = Messages.enterEmail; return }
        if mode == .signUp {
            if let problem = PasswordRules.problem(password: password, confirmation: confirmation) {
                error = problem
                return
            }
            // Sign-up always runs the security check first, like Android.
            busy = true
            pendingCheck = .signUp
            return
        }
        guard !password.isEmpty else { error = Messages.enterPassword; return }
        await run(.signIn, token: "")
    }

    private func submitAccountId() async {
        error = nil
        guard !accountId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            error = Messages.enterAccountId
            return
        }
        await run(.accountId, token: "")
    }

    private func submitReset() async {
        error = nil
        info = nil
        guard !AuthService.normalizeEmail(email).isEmpty else { error = Messages.enterEmail; return }
        busy = true
        defer { busy = false }
        do {
            try await model.auth.resetPassword(email: email)
            info = "Check your inbox for a secure reset link."
            resetCooldownUntil = Date().addingTimeInterval(60)
        } catch {
            self.error = ErrorText.message(for: error)
        }
    }

    private func resend(_ address: String) async {
        error = nil
        busy = true
        defer { busy = false }
        do {
            try await model.auth.resendVerification(email: address)
            info = "A new verification link was sent."
        } catch {
            self.error = ErrorText.message(for: error)
        }
    }

    /// Runs one auth call. A "security check required" answer opens
    /// Turnstile and retries with the token.
    private func run(_ check: PendingCheck?, token: String) async {
        guard let check else { busy = false; return }
        busy = true
        defer { busy = false }
        do {
            switch check {
            case .signIn:
                let session = try await model.auth.signIn(email: email, password: password, turnstileToken: token)
                model.signedIn(session)
            case .signUp:
                if let session = try await model.auth.signUp(email: email, password: password, turnstileToken: token) {
                    model.signedIn(session)
                }
            case .accountId:
                let session = try await model.auth.signIn(accountId: accountId, turnstileToken: token)
                model.signedIn(session)
            case .anonymous:
                let session = try await model.auth.registerAnonymous(turnstileToken: token)
                model.signedIn(session, revealAccountId: true)
            }
            password = ""
            confirmation = ""
        } catch AuthFailure.turnstileRequired where token.isEmpty {
            pendingCheck = check
        } catch AuthFailure.verificationRequired(let address) {
            password = ""
            confirmation = ""
            mode = .verify(email: address)
        } catch {
            self.error = ErrorText.message(for: error)
        }
    }
}

extension View {
    @ViewBuilder
    func passwordContentType(new: Bool) -> some View {
        #if os(iOS)
        self.textContentType(new ? .newPassword : .password)
        #else
        self.textContentType(.password)
        #endif
    }

    /// Text field chrome shared by the auth and account forms.
    func authField() -> some View {
        self
            .textFieldStyle(.plain)
            .font(VFont.bodyLarge)
            .foregroundStyle(Palette.paper)
            .padding(.horizontal, 14)
            .frame(minHeight: 50)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Palette.ink2))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Palette.lineStrong, lineWidth: 1))
    }
}
