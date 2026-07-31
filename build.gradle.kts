plugins {
    id("com.android.application") version "8.7.3" apply false
    kotlin("android") version "2.1.0" apply false
    kotlin("jvm") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

// Build adresar sa da presunut mimo projektu cez RJSEAT_BUILD_DIR. Potrebne to
// bolo, kym projekt lezal v OneDrive - jeho synchronizacia drzala subory
// v build/ otvorene a Gradle ich nedokazal zmazat. Teraz je to len volitelne.
System.getenv("RJSEAT_BUILD_DIR")?.let { buildRoot ->
    allprojects {
        layout.buildDirectory.set(file("$buildRoot/${project.path.replace(':', '_').trim('_')}"))
    }
}
