pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rjseat"

// `core` je zamerne cisty Kotlin/JVM modul bez Android zavislosti - vdaka tomu
// sa cela logika (parsovanie, topologia miest, odporucanie, sledovanie zmien)
// da testovat lokalne bez Android SDK. `app` je nad nim len tenka vrstva.
include(":core")
include(":app")
