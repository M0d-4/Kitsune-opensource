import com.android.build.gradle.BaseExtension

plugins {
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.zygoteloader) apply false
}

val minSdkVer by extra(29)
val targetSdkVer by extra(37)

// Values consumed by the sub-projects (BuildConfig fields). configVerCode MUST match
// AppListClient.CONFIG_VERSION in the Magisk app.
@Suppress("unused") val configVerCode by extra(93)
@Suppress("unused") val serviceVerCode by extra(105)
@Suppress("unused") val minBackupVerCode by extra(65)
@Suppress("unused") val appPackageName by extra("org.frknkrc44.hma_oss")
@Suppress("unused") val appVerCode by extra(1)
@Suppress("unused") val appVerName by extra("1.0-magisk")

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

fun Project.configureBaseExtension() {
    extensions.findByType<BaseExtension>()?.run {
        compileSdkVersion(targetSdkVer)

        defaultConfig {
            minSdk = minSdkVer
            targetSdk = targetSdkVer
            versionCode = appVerCode
            versionName = appVerName
            consumerProguardFiles("proguard-rules.pro")
        }

        buildTypes {
            all { signingConfig = signingConfigs["debug"] }
            named("release") {
                isMinifyEnabled = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
}

subprojects {
    plugins.withId("com.android.application") { configureBaseExtension() }
    plugins.withId("com.android.library") { configureBaseExtension() }
}
