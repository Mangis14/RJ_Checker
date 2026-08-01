plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Stabilny podpisovaci kluc.
//
// Bez neho si Gradle generuje debug keystore do domovskeho adresara, ktory
// v Docker kontejneri (--rm, /root nie je mountovany) po kazdom builde zanikne.
// Kazde APK by potom bolo podpisane inym klucom a Android by odmietol update -
// aplikaciu by bolo treba pred kazdou novou verziou odinstalovat.
//
// Subor je zamerne mimo gitu (viz .gitignore): podpisuje appku nainstalovanu na
// telefone, takze do verejneho repozitara nepatri.
val signingKeystore = rootProject.file("keystore/rjseat.jks")

android {
    namespace = "io.github.mangis14.rjchecker"
    compileSdk = 35

    signingConfigs {
        if (signingKeystore.exists()) {
            create("stable") {
                storeFile = signingKeystore
                storePassword = System.getenv("RJSEAT_KEYSTORE_PASSWORD") ?: "rjseat"
                keyAlias = "rjseat"
                keyPassword = System.getenv("RJSEAT_KEY_PASSWORD") ?: "rjseat"
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.mangis14.rjchecker"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        debug {
            // stabilny kluc znamena, ze sa nova verzia da nainstalovat cez staru
            if (signingKeystore.exists()) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            if (signingKeystore.exists()) signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // zdroje su v src/main/kotlin, AGP sam predpoklada src/main/java
    sourceSets["main"].java.srcDir("src/main/kotlin")

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
}
