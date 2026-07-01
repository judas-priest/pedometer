# Protobuf lite
-keep class com.pedometer.proto.** { *; }
-keep class com.google.protobuf.** { *; }

# Room
-keep class com.pedometer.data.** { *; }

# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OSMDroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# NanoHTTPD (removed but just in case)
-dontwarn fi.iki.elonen.**

# Compose
-dontwarn androidx.compose.**
