plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fabiosilva.ecosdeeldra"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.fabiosilva.ecosdeeldra"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "0.5.1"

        // AdMob (IDs reais do app). O aparelho de desenvolvimento recebe anúncios de teste
        // via ADMOB_TEST_DEVICES para não gerar tráfego inválido (políticas do AdMob).
        manifestPlaceholders["admobAppId"] = "ca-app-pub-6598765502914364~3458194932"
        buildConfigField("String", "ADMOB_REWARDED_ID", "\"ca-app-pub-6598765502914364/5788844807\"")
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"ca-app-pub-6598765502914364/6741641853\"")
        buildConfigField("String", "ADMOB_TEST_DEVICES", "\"E812F36934272DE8BFD5D96B7E64BE83\"")

        ndk {
            // Celulares atuais (arm64) e antigos (armv7). Ao rodar pelo Android Studio,
            // só a ABI do aparelho conectado é compilada.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fno-rtti")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("glb")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("com.google.android.gms:play-services-ads:24.7.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")
    testImplementation("junit:junit:4.13.2")
}
