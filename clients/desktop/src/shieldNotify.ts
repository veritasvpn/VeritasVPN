/** Desktop notification via the Tauri plugin. Failure stays in the window. No pkexec. */

/** A click opens Shield when the desktop reports it. Otherwise the in-app banner does. */
export async function listenForShieldNotification(onOpen: () => void): Promise<() => void> {
  try {
    const { onAction } = await import("@tauri-apps/plugin-notification");
    const listener = await onAction(() => onOpen());
    return () => {
      void listener.unregister();
    };
  } catch {
    return () => {};
  }
}

export async function sendShieldNotification(title: string, body: string): Promise<boolean> {
  try {
    const { isPermissionGranted, requestPermission, sendNotification } = await import("@tauri-apps/plugin-notification");
    let granted = await isPermissionGranted();
    if (!granted) {
      const permission = await requestPermission();
      granted = permission === "granted";
    }
    if (!granted) return false;
    sendNotification({ title, body });
    return true;
  } catch {
    return false;
  }
}
