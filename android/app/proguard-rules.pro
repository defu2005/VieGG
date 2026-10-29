-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.rentgg.app.data.** { kotlinx.serialization.KSerializer serializer(...); }

# Tink (security-crypto) tham chiếu annotation chỉ có lúc biên dịch.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.crypto.tink.**
