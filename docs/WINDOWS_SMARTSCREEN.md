# SmartScreen and antivirus submission

Status: **not submitted**. This note is a stub. There is no signed binary, no publisher certificate, and no vendor account action in this change.

Do not file these submissions until:

1. The stack through the release lane is merged.
2. A real Authenticode certificate exists. The name on that certificate is the publisher name to keep. Do not invent one here.
3. `scripts/sign-windows.ps1` is replaced by a real `bundle.windows.signCommand`. A run must no longer print only `SIGN_SKIPPED`.
4. A `windows-v0.x.y` tag from master has published `veritasvpn-windows-setup.exe` and `SHA256SUMS`, with build provenance.
5. The website pin is still off until you choose to turn `WINDOWS_INSTALLER.published` on. Submitting a file is not the same as offering it for download.

## What a later submission uses

- The signed setup file from that GitHub release, not an unsigned CI artifact.
- The SHA-256 line in that release's `SHA256SUMS`.
- One publisher name, used on the app, the service, the helper, and the installer.
- A short download-page note that SmartScreen can warn on the first releases of a new publisher, and that the file hash is the check.

## Where the forms are, when the binary is signed

- Microsoft SmartScreen / file submission: the Windows security intelligence submission portal, signed in with the account that owns the publisher.
- The main antivirus vendors' false-positive forms, for `veritasvpn-windows-setup.exe` and `wstunnel.exe` if a product flags the helper.

Record the submission date and the vendor reference on this page after a person sends them. Leave the table empty until then.

| Vendor | Submitted | Reference | Result |
| --- | --- | --- | --- |
| Microsoft SmartScreen | No | | |
| Other antivirus vendors | No | | |
