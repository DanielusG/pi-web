import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.pimobile"
    // 36: required by the LaTeX renderer's AAR metadata (targetSdk stays 35).
    compileSdk = 36

    defaultConfig {
        applicationId = "app.pimobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideload build: signed with the local debug key until a real keystore exists.
            signingConfig = signingConfigs.getByName("debug")
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
}

dependencies {
    // Compose 1.9: what the Markdown and LaTeX renderers are built against.
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Media3: TTS playback — ExoPlayer + MediaSession in a foreground service,
    // so audio survives background and screen-off (see service/TtsPlaybackService).
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Markdown + LaTeX. Newest releases compatible with Kotlin 2.1: the renderer needs Kotlin 2.3
    // from 0.39.0, the LaTeX library publishes dedicated -kt2.1.0 builds.
    implementation("com.mikepenz:multiplatform-markdown-renderer:0.38.1")
    // Patched at build time by LatexRendererPatches below: check them on every update.
    implementation("io.github.huarangmeng:latex-renderer:1.5.4-kt2.1.0")

    testImplementation("junit:junit:4.13.2")
    // Same version as okhttp; in-process mock server for PiApiTest (JVM, no Robolectric).
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

/**
 * Two fixes to the LaTeX renderer's bytecode, needed for its precise glyph bounds (see
 * LatexGlyphBounds.kt). The build fails if the code they patch is no longer what it was in 1.5.4,
 * rather than silently bringing back the slow paths.
 * - InkBoundsEstimator: its glyph measurement goes to app.pimobile.ui.markdown.cachedGlyphBounds,
 *   which loads each font once instead of on every call.
 * - LatexFontFamilies.hashCode: identity hashes for the font byte arrays instead of hashing their
 *   whole content (hundreds of KB) on every layout-cache lookup. There is one array per font.
 */
abstract class LatexRendererPatches : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData) = classData.className in PATCHED

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            val className = classContext.currentClassData.className
            var patched = 0

            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val method = name
                return object : MethodVisitor(api, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        when {
                            owner == "com/hrm/latex/renderer/utils/GlyphBoundsProvider_androidKt" && name == "measureGlyphBounds" -> {
                                patched++
                                super.visitMethodInsn(opcode, "app/pimobile/ui/markdown/LatexGlyphBoundsKt", "cachedGlyphBounds", descriptor, false)
                            }
                            method == "hashCode" && owner == "java/util/Arrays" && name == "hashCode" && descriptor == "([B)I" -> {
                                patched++
                                super.visitMethodInsn(opcode, "java/lang/System", "identityHashCode", "(Ljava/lang/Object;)I", false)
                            }
                            else -> super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                        }
                    }
                }
            }

            override fun visitEnd() {
                check(patched > 0) { "LaTeX renderer changed: nothing to patch in $className" }
                super.visitEnd()
            }
        }

    private companion object {
        val PATCHED = setOf("com.hrm.latex.renderer.utils.InkBoundsEstimator", "com.hrm.latex.renderer.model.LatexFontFamilies")
    }
}

androidComponents {
    onVariants { variant ->
        variant.instrumentation.transformClassesWith(LatexRendererPatches::class.java, InstrumentationScope.ALL) {}
    }
}
