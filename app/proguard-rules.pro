# kotlinx.serialization — keep generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class com.itantra.transport.Payload { *; }
-keep class com.itantra.transport.PayloadType { *; }
# Keep @Serializable data classes (Payload, etc.)
-keep,includedescriptorclasses class com.itantra.**$$serializer { *; }
-keepclassmembers class com.itantra.** {
    *** Companion;
}
-keepclasseswithmembers class com.itantra.** {
    kotlinx.serialization.KSerializer serializer(...);
}
