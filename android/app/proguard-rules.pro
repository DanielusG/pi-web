# JSON is handled as kotlinx.serialization JsonElement trees (no reflection),
# so it needs no keep rules.

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
