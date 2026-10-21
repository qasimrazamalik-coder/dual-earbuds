# Release builds currently run with minifyEnabled false, so these rules are only used if you turn R8 on later.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
