# Support snippets

Paste-ready replies for the questions we get most often. The same wording is public at `website/support.html` (`/support.html`). When product behavior changes, update both copies together.

Cases these do not cover: email contact@veritasvpn.cloud.

Do not promise Account ID recovery, an in-app Android kill-switch off switch, Premium at zero Bitcoin confirmations, system-wide protection from the Chrome extension, or that Stealth is undetectable.

## Account ID backup

```
Anonymous Account ID: when you create one, VeritasVPN shows the Account ID once and downloads veritasvpn-account.txt. Save that .txt file and copy the Account ID before you leave the page. If both are lost, the account cannot be recovered.

Email accounts do not use that file. Use Forgot password on the sign-in form. The reset link goes to the email address on the account.
```

## Android Always-on

Matches the Connect gate from the Always-on lockdown change: both system switches are required, the app only deep-links to VPN settings, and there is no in-app off switch.

```
Open Settings → VPN → VeritasVPN and turn on both Always-on VPN and Block connections without VPN. On some phones the VPN list is under Settings → Network & internet.

Connect stays blocked until both are on for VeritasVPN. The app cannot turn those system switches on for you. It explains them and opens Android VPN settings. There is no in-app kill-switch off. After both are on, return to the app and Connect continues. Cancel leaves you disconnected.
```

## Stealth / connection modes

Android Auto tries UDP first, then Stealth on port 443. UDP only and Stealth always live under Settings → Connection → Split tunnel. Linux is Settings → Stealth mode. Reconnect to apply. Not a claim of undetectability.

```
Stealth wraps WireGuard in a TLS WebSocket on port 443 so it looks more like ordinary HTTPS. Use it on networks that block or throttle plain WireGuard UDP. This helps on restrictive networks; it is not a claim of undetectability.

On Android, open Settings → Connection → Split tunnel. Auto (the default) tries UDP WireGuard first and switches to Stealth if that handshake does not complete. The VPN stays on during that switch. UDP only stays on plain WireGuard, with no Stealth fallback. Stealth always starts on Stealth. Reconnect to apply a change.

On Linux, enable Settings → Stealth mode, then reconnect. Direct UDP remains the default when Stealth is off.
```

## BTCPay

On-chain only for this reply. Premium waits for the confirmations BTCPay requires. The status page billing row is checkout health, not a personal invoice.

```
Open the invoice from checkout and pay it on-chain for the amount BTCPay shows. Premium is not granted at zero confirmations. Wait until the payment is confirmed, then reopen the app or refresh the account page so the entitlement updates.

If it stays stuck after you paid, email contact@veritasvpn.cloud with the invoice id and the time you sent the payment. On https://veritasvpn.cloud/status.html, the Bitcoin checkout readiness row is whether checkout itself is up. It is not the state of your invoice.
```

## Chrome versus a full tunnel

```
The Chrome extension is a browser-only HTTP CONNECT proxy. It can cover traffic inside Chrome. Other apps on the device stay unprotected, and it is not a device kill switch.

A full tunnel is the Android or Linux WireGuard client. Android requires Always-on VPN and Block connections without VPN before Connect. Linux keeps a firewall and routing kill switch on while connected, with no in-app off. Current downloads on the site are those two clients.
```
