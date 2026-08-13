-keep class com.wireguard.** { *; }
-keep class com.wireguard.android.backend.GoBackend$VpnService { *; }
-keepclasseswithmembernames class com.wireguard.android.backend.GoBackend {
    native <methods>;
}
