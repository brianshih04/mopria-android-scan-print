plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.brianshih.mopria.android.scanprint"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.brianshih.mopria.android.scanprint"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }

    lint {
        // targetSdk is intentionally 36: targetSdk 37 requires the ACCESS_LOCAL_NETWORK
        // permission migration for NSD scanner discovery (tracked in README).
        disable += "OldTargetApi"
        // Pluralizing count-bearing strings across all 10 locales is a tracked i18n
        // follow-up; disabled rather than shipping partial/incorrect plural forms.
        disable += "PluralsCandidate"
        disable += "PropertyEscape"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    implementation("org.opencv:opencv:4.14.0")

    // PDFBox Android adds the opt-in searchable text layer. Ordinary PDF export keeps using
    // PdfPageRenderer when OCR is disabled or no positioned OCR text is available.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // ML Kit Text Recognition v2 unbundled clients. Google Play services owns the script models;
    // only Latin and Chinese are install-time defaults. Japanese and Korean are user-requested.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.1")

    implementation("com.hp.jipp:jipp-core:0.7.18")
    implementation("com.hp.jipp:jipp-pdl:0.7.18")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
