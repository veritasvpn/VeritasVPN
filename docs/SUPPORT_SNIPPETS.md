# Support snippets

Paste-ready replies for the questions we get most often. The same wording is public at `website/support.html` (`/support.html`). When product behavior changes, update both copies together.

Cases these do not cover: email contact@veritasvpn.cloud.

Do not promise Account ID recovery, an in-app Android kill-switch off switch, Premium at zero Bitcoin confirmations, or system-wide protection from the Chrome extension.

## Account ID backup

```
Anonymous Account ID: when you create one, VeritasVPN shows the Account ID once and downloads veritasvpn-account.txt. Save that .txt file and copy the Account ID before you leave the page. If both are lost, the account cannot be recovered.

Email accounts do not use that file. Use Forgot password on the sign-in form. The reset link goes to the email address on the account.
```

## Android Always-on

Matches the Connect gate from the Always-on lockdown change: both system switches are required, the app only deep-links to VPN settings, and there is no in-app off switch.

```
Tap Connect. If Android asks, allow the VPN connection so VeritasVPN appears under Settings → VPN. That step is required when another VPN app, such as Tailscale, is already listed. On some phones the list is under Settings → Network & internet.

Select VeritasVPN and turn on both Always-on VPN and Block connections without VPN. The tunnel stays blocked until both are on for VeritasVPN. The app cannot turn those system switches on for you. It explains them and opens Android VPN settings. There is no in-app kill-switch off. After both are on, return to the app and Connect continues. Cancel leaves you disconnected.
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

A full tunnel is the Android or Linux WireGuard client. Android asks for VPN permission first so VeritasVPN is listed, then requires Always-on VPN and Block connections without VPN before the tunnel starts. Linux keeps a firewall and routing kill switch on while connected, with no in-app off. Current downloads on the site are those two clients.
```
