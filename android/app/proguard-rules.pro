-keep class com.wireguard.** { *; }
-keep class com.wireguard.android.backend.GoBackend { *; }
-keep class com.wireguard.android.backend.GoBackend$VpnService { *; }
-keepclasseswithmembernames class com.wireguard.android.backend.GoBackend {
    native <methods>;
}
# VeritasVpnService looks these up by name (getDeclaredField / getDeclaredMethod).
-keepclassmembers class com.wireguard.android.backend.GoBackend {
    *** currentTunnelHandle;
    *** currentTunnel;
    *** currentConfig;
    native int wgTurnOn(java.lang.String, int, java.lang.String);
    native void wgTurnOff(int);
    native int wgGetSocketV4(int);
    native int wgGetSocketV6(int);
    native java.lang.String wgGetConfig(int);
}

# Turnstile posts through this annotation. The method is only called from JS.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# EncryptedSharedPreferences / Tink (MasterKey)
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
# Optional Tink HTTP/Joda refs (unused for local MasterKey AES-GCM)
-dontwarn com.google.api.client.http.**
-dontwarn com.google.api.client.http.javanet.**
-dontwarn org.joda.time.**

