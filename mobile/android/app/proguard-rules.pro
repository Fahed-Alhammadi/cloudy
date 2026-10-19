# Keep serializable API models intact.
-keep class io.filecloud.app.data.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontwarn org.slf4j.**
