# Windows on-device QA

Status: **pending human sign-off**. Nothing in this list has been run on a Windows 10 or Windows 11 PC. CI on `windows-latest` is not that sign-off.

Run the same list on:

- Windows 11 (primary)
- Windows 10 version 22H2 (best effort)

Use the signed installer once it exists. Until then, an unsigned build can rehearse the steps, and the sign-off stays open.

Mark each row pass or fail, with the date and the Windows version. Do not sign the sheet from a GitHub Actions log.

## Connection, same five steps as Linux

| Step | What to do | Pass looks like | Windows 11 | Windows 10 22H2 |
| --- | --- | --- | --- | --- |
| 1. Connect | Sign in, choose Direct, tap connect | Ready, then Connecting, then Protected. Public address changes. DNS goes through the tunnel | Pending | Pending |
| 2. Switch network | Move between Wi-Fi and Ethernet | Reconnecting, then Protected. No leak in between | Pending | Pending |
| 3. Cable pull | Unplug the network, then plug it back | Reconnecting shows. It recovers without a tap | Pending | Pending |
| 4. Kill switch | While Reconnecting, and after the service process is killed | No traffic leaves the physical adapter. The lock is still there | Pending | Pending |
| 5. Disconnect | Tap disconnect | The lock is gone. Normal internet works. Nothing is left behind | Pending | Pending |

## Windows-only

| Check | What to do | Pass looks like | Windows 11 | Windows 10 22H2 |
| --- | --- | --- | --- | --- |
| Direct | Connect with Direct | WireGuard endpoint is the server, not the local helper | Pending | Pending |
| Stealth | Choose Stealth yourself. Do not let the app switch | Traffic flows. The mode stays Stealth after a network change | Pending | Pending |
| Tray | Close the window while connected. Use Open, Disconnect, and Quit | The tunnel keeps running until Disconnect. Quit does not drop the lock by itself | Pending | Pending |
| Single instance | Launch the app again | The first window comes forward | Pending | Pending |
| Autostart | Leave "Start when I sign in" off, reboot, then turn it on and reboot | The service starts either way. The window starts only after the switch is on | Pending | Pending |
| Sleep and resume | Sleep while connected, then wake | Reconnecting, then Protected. The lock stayed on | Pending | Pending |
| Force-kill | End `veritas-service.exe` in Task Manager while connected | The kill switch stays. Internet does not return until Disconnect, uninstall, or Repair | Pending | Pending |
| Upgrade | Install a newer setup over this one | One admin prompt. The old service is replaced. The tunnel can connect again | Pending | Pending |
| Uninstall leftovers | Uninstall while connected, and say no to removing the saved sign-in | Service, adapter, filters, helper, and Program Files are gone. The account sign-in is still there. A second uninstall that removes app data clears the sign-in | Pending | Pending |

## Sign-off

| | Name | Date | Result |
| --- | --- | --- | --- |
| Windows 11 | | | Pending human sign-off |
| Windows 10 22H2 | | | Pending human sign-off |
