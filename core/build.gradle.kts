import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

// Prekladame akymkolvek nainstalovanym JDK, ale vystup je bytecode 17 - to je
// to, co bude vediet spracovat Android modul. Ziadny toolchain sa nestahuje.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Iba runtime kniznica serializacie - JSON sa cita cez JsonElement, takze
    // compiler plugin netreba. Menej pohyblivych casti pri buildovani.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
