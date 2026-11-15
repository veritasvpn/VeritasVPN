import Foundation

/// Every sentence the core can put in front of the user. Plain words only:
/// no status codes, no server bodies. The apps translate these by key (the
/// English text), so keep them in sync with `Localizable.strings`.
///
/// Wording matches the desktop mapping in `clients/desktop/src/errorMapper.ts`
/// and `connectError.ts`, and the Android strings where they overlap.
public enum Messages {
    // Reachability (errorMapper.ts)
    public static let serverUnreachable = "Can't reach VeritasVPN servers right now. Please check your internet connection or try again in a few minutes."
    public static let serverTimeout = "VeritasVPN's server didn't respond in time. Check your internet connection and try again."
    public static let offline = "You're offline. Check your internet connection and try again."
    public static let secureConnectionFailed = "Secure connection failed. Check your network (public Wi-Fi may block it) and try again."
    public static let generic = "Something went wrong. Please try again."

    // Session
    public static let sessionExpired = "Your session ended. Please sign in again."
    public static let signInAgain = "Sign in again"

    // Connect (connectError.ts)
    public static let connectFailed = "Couldn't connect. Please try again."
    public static let invalidConfig = "We couldn't set up the VPN with this server's settings. Try again in a moment."
    public static let stealthUnavailable = "Stealth isn't available on this server right now. Try again later or choose UDP only."
    public static let stealthComingSoon = "Stealth is coming soon on iPhone, iPad and Mac. Choose Auto or UDP only in Stealth settings to connect with Direct UDP."
    public static let peerSetupFailed = "Couldn't set up a VPN connection on the server. Please try again."
    public static let deviceLimit = "Device limit reached. Disconnect another device or upgrade your plan."
    public static let premiumRequired = "Premium is required to connect. Open Account to see your plan."
    public static let handshakeTimeout = "Couldn't complete a secure connection on this network. Check your connection and try again."
    public static let vpnPermissionDenied = "VeritasVPN needs permission to add a VPN configuration. Tap Connect and choose Allow."
    public static let vpnConfigurationFailed = "Couldn't save the VPN configuration. Please try again."
    public static let tunnelStartFailed = "The VPN couldn't start. Please try again."
    public static let tunnelDnsFailed = "Couldn't find the VeritasVPN server. Check your internet connection and try again."

    // Auth (AuthRepository.kt extractError)
    public static let verifyEmailFirst = "Verify your email before signing in."
    public static let securityCheckRequired = "Security check required. Complete the check and try again."
    public static let securityCheckFailed = "Security check failed. Please try again."
    public static let securityCheckUnavailable = "Security check couldn't be reached. Please try again."
    public static let incorrectCredentials = "Incorrect email or password."
    public static let passwordTooShort = "Password must be at least 10 characters."
    public static let accountExists = "An account with this email already exists."
    public static let tooManyAttempts = "Too many sign-in attempts. Try again later."
    public static let accountIdNotFound = "Account ID not found."
    public static let signInServiceUnreachable = "Could not reach the sign-in service. Check your connection and try again."
    public static let enterEmail = "Enter your email address."
    public static let enterPassword = "Enter your password."
    public static let enterAccountId = "Enter your Account ID."
    public static let passwordNeedsUppercase = "Password must include an uppercase letter."
    public static let passwordNeedsLowercase = "Password must include a lowercase letter."
    public static let passwordNeedsNumber = "Password must include a number."
    public static let confirmPassword = "Confirm your password."
    public static let passwordsDontMatch = "Passwords do not match."
    public static let invalidServerResponse = "The server sent an unexpected response. Please try again."
}
