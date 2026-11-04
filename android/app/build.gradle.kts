plugins {
    id("com.android.application")
}

android {
    namespace = "com.umb.mirs"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.umb.mirs"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.3.0"
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
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
