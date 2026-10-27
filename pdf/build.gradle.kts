plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "hu.reelee81.reepdf.pdf"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation(project(":core"))
    api(libs.itextpdf.kernel)
    api(libs.itextpdf.layout)
    api(libs.itextpdf.forms)
    api(libs.itextpdf.bc.adapter)
    api(libs.itextpdf.bc.connector)
    api(libs.mupdf.fitz)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    testImplementation(libs.junit)
}
