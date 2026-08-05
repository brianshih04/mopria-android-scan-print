# R8 / ProGuard — release minify + resource shrinking are enabled in build.gradle.kts.
# No project -keep rules needed: AGP defaults + AndroidX/Compose consumer rules cover
# this app (manifest-kept components; no reflection-based serialization such as
# Gson/Retrofit/Room). Add a targeted -keep here if a release build crashes on a device.
