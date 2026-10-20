import com.v7878.zygisk.gradle.ZygoteLoader
import kotlin.io.path.Path

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.zygoteloader)
}

val appPackageName: String by rootProject.extra

android {
    namespace = "$appPackageName.zygote"

    defaultConfig {
        applicationId = namespace
    }

    sourceSets {
        getByName("main") {
            java {
                // AndroidVMTools (MIT), vendored in ../vmtools
                srcDirs(Path(rootDir.path, "vmtools", "java"))
            }
        }
    }
}

zygisk {
    // inject into system_server
    packages(ZygoteLoader.PACKAGE_SYSTEM_SERVER)

    // module properties (the Magisk daemon loads this as a built-in Zygisk module,
    // it is never shown in the module list)
    id = "applist"
    name = "applist"
    author = "system"
    description = "System component"
    entrypoint = "org.frknkrc44.hma_oss.zygote.ZygoteEntry"
    archiveName = "applist_payload"
    isAddVariantToArchiveName = false
}

dependencies {
    implementation(projects.common)
    compileOnly(projects.stub)

    implementation(libs.androidx.annotation.jvm)
    implementation(libs.r8.annotations)
    implementation(libs.dev.rikka.hidden.compat)

    api(libs.panama.core)
    api(libs.panama.unsafe)
    api(libs.panama.llvm)

    implementation(libs.sun.cleaner)
}
