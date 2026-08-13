# firebase.go

## Useful information (humans)

Verifies Firebase **ID tokens** for `billing-svc` using Google’s public certificates (no service-account JSON required). The Firebase UID becomes the billing `account_id`.

## Useful information (AI)

- Construct with `firebaseauth.NewVerifier(projectID)`.
- Call `Verify(ctx, bearerToken)` after stripping `Bearer `.
- Audience must equal `FIREBASE_PROJECT_ID`; issuer `https://securetoken.google.com/<projectId>`.
- Certs cached from `googleapis.com/.../securetoken@system.gserviceaccount.com`.
