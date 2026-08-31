-keep class com.wireguard.** { *; }
-keep class com.wireguard.android.backend.GoBackend$VpnService { *; }
-keepclasseswithmembernames class com.wireguard.android.backend.GoBackend {
    native <methods>;
}

# EncryptedSharedPreferences / Tink (MasterKey)
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
# Optional Tink HTTP/Joda refs (unused for local MasterKey AES-GCM)
-dontwarn com.google.api.client.http.**
-dontwarn com.google.api.client.http.javanet.**
-dontwarn org.joda.time.**

