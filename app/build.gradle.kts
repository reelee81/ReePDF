import java.io.File

plugins {
    alias(libs.plugins.android.application)
}

kotlin {
    jvmToolchain(21)
}

fun parseLocalesConfig(file: File): List<String> {
    check(file.exists()) {
        "Missing locales_config.xml at: ${file.path}"
    }
    return Regex("""android:name="([^"]+)"""")
        .findAll(file.readText())
        .map { it.groupValues[1] }
        .toList()
}

android {
    namespace = "hu.reelee81.reepdf"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "hu.reelee81.reepdf"
        minSdk = 24
        targetSdk = 37
        versionCode = 7
        versionName = "0.07"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("r8check") {
            initWith(getByName("release"))
            applicationIdSuffix = ".r8check"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    androidResources {
        val locales = parseLocalesConfig(file("src/main/res/xml/locales_config.xml"))
        @Suppress("UnstableApiUsage")
        localeFilters += locales
    }

    bundle {
        language {
            @Suppress("UnstableApiUsage")
            enableSplit = false
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/services/org.w3c.dom.DOMImplementationSourceList",
                "META-INF/services/org.xml.sax.driver",
                "META-INF/services/sharpen.config.MappingConfiguration"
            )
        }
    }
}

dependencies {

    implementation(project(":core"))
    implementation(project(":pdf"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.pdf.viewer)
    implementation(libs.androidx.pdf.ink)
    implementation(libs.androidx.window)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
}
