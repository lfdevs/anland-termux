# JNI entry points are resolved from the exported Java_com_anland_termux_* names.
# Keep both the declaring classes and native member names stable after R8.
-keep class com.anland.termux.Native { *; }
-keep class com.anland.termux.CameraServices { *; }

# native_consumer.c calls these methods through GetMethodID on Clipboard.
-keep class com.anland.termux.Clipboard { *; }

# Keep native declarations in any future bridge class from being renamed or
# removed when the corresponding C entry point is not visible to R8.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
