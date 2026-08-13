# firebase-config.js

## Useful information (humans)

Holds the Firebase **web** project configuration for the VeritasVPN marketing site (`veritasvpn-37cf6`).

This is the public client config from the Firebase console (API key, auth domain, app ID, etc.). It is safe to ship in the browser; access control comes from Firebase Authentication settings and authorized domains, not from hiding these values.

Analytics (`measurementId`) is included in the config object but is **not** initialized by the site (privacy-first).

## Useful information (AI)

- Import `firebaseConfig` from this module; do not duplicate the config elsewhere.
- Pin SDK version via `FIREBASE_SDK_VERSION` / matching CDN URLs in `auth.js`.
- Do **not** put service-account JSON or Admin SDK credentials here.
- If credentials rotate, update this file and keep authorized domains (`localhost`, production host) configured in Firebase Console → Authentication → Settings.
