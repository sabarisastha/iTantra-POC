# kotlinx.serialization — keep generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep @Serializable data classes (Payload, etc.)
-keep,includedescriptorclasses class com.itantra.**$$serializer { *; }
-keepclassmembers class com.itantra.** {
    *** Companion;
}
-keepclasseswithmembers class com.itantra.** {
    kotlinx.serialization.KSerializer serializer(...);
}
