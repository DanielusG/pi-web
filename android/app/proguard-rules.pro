# JSON is handled as kotlinx.serialization JsonElement trees (no reflection),
# so no keep rules are needed yet.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
