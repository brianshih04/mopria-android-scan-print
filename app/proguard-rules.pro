# R8 / ProGuard — release minify + resource shrinking are enabled in build.gradle.kts.
# No project -keep rules needed: AGP defaults + AndroidX/Compose consumer rules cover
# this app (manifest-kept components; no reflection-based serialization such as
# Gson/Retrofit/Room). Add a targeted -keep here if a release build crashes on a device.

# PDFBox Android references its optional JPEG2000 codec when the JPX filter is linked. The app
# only embeds JPEG pages and does not ship that optional codec; suppress the optional references
# so R8 can remove the unreachable backend without masking a required app class.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
