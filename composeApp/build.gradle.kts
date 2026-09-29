import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.resources {
    // Pin the generated accessor package instead of letting it be derived from
    // the project name, so a rename cannot silently break every reference.
    publicResClass = true
    packageOfResClass = "com.rhp.mediaplayer.resources"
    generateResClass = auto
}

compose.desktop {
    application {
        mainClass = "com.rhp.mediaplayer.MainKt"

        // jlink refuses to build a runtime image from a JDK it considers
        // modified, and every OpenJDK Fedora ships looks modified to it: the
        // distribution patches conf/security/java.security for crypto policies
        // without updating the hash recorded inside the image, so the
        // createRuntimeImage step every jpackage task depends on dies with
        // "Error: modified .../conf/security/java.security". Point the
        // image-building tasks at a JDK that is not distribution-patched. Leave
        // the property unset and they use the Gradle JVM, which is what a
        // non-Fedora machine wants.
        //
        // The value names a directory on one machine, and the project is built
        // on more than one, so it is checked before it is used rather than
        // trusted. A machine that does not have that directory is a machine that
        // does not need it, and the only thing an unchecked value achieves there
        // is this failure, reported before the task does any work at all:
        //
        //   property 'jdkHome' specifies directory '<path>' which doesn't exist
        //
        // Windows is where it bites: it resolves the POSIX-looking
        // "/home/<user>/..." against the current drive, so a path copied from a
        // Linux machine becomes an unrelated absolute path instead of an
        // obvious mistake. Falling back to the Gradle JVM keeps the machine
        // that needs the override packaging and lets every other machine
        // package too, so neither has to edit this file to build.
        val packagingJavaHome = providers.gradleProperty("cmp.packaging.javaHome").orNull
        if (!packagingJavaHome.isNullOrBlank()) {
            val packagingJavaHomeDir = File(packagingJavaHome)
            if (packagingJavaHomeDir.isDirectory) {
                javaHome = packagingJavaHomeDir.path
            } else {
                logger.lifecycle(
                    "Ignoring cmp.packaging.javaHome: '${packagingJavaHomeDir.path}' is not a " +
                        "directory on this machine, so the runtime image is built from the " +
                        "Gradle JVM instead.",
                )
            }
        }

        nativeDistributions {
            // Desktop only. Dmg is deliberately dropped: this app targets Windows and Linux.
            // Deb and Rpm both come from jpackage, which shells out to dpkg-deb/rpmbuild.
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "ComposeMusicPlayer"
            // Bumped whenever the launcher changes, and not just for tidiness.
            // Windows Installer keeps the copy it already has when the incoming
            // file carries the same version resource, so rebuilding at an
            // unchanged version and installing over the previous copy can leave
            // the old executable -- and the old icon with it -- sitting in the
            // install directory. Raising the version also turns the install into
            // a major upgrade: the upgrade code is version-independent while the
            // product code is not, and jpackage schedules RemoveExistingProducts
            // before costing, so the previous copy is removed rather than left
            // beside the new one.
            packageVersion = "1.1.1"
            description = "Local music player for Windows and Linux"
            vendor = "ComposeMusicPlayer"

            // jlink trims the runtime to what it can see being used, and locale
            // data is not visible to static analysis. Without jdk.localedata the
            // packaged app has no Chinese collation at all and silently falls
            // back to comparing code points, so 稻香/dào would sort after
            // 晴天/qíng -- correct in a dev run, wrong in the installer.
            // jdk.unsupported is needed by libraries that reach for sun.misc.Unsafe.
            modules("jdk.localedata", "jdk.unsupported")

            // Compose treats every subdirectory of this directory named after an
            // OS (optionally OS-architecture, e.g. "windows-x64") as
            // platform-specific resources and copies the matching one into the
            // packaged app. The staging layout from scripts/fetch_ffmpeg.py
            // already uses exactly that shape, so the binaries are bundled
            // straight from where they were staged -- no second copy in the
            // source tree.
            //
            // At runtime the app finds them under
            // System.getProperty("compose.application.resources.dir").
            appResourcesRootDir.set(rootProject.layout.projectDirectory.dir("third_party/ffmpeg"))

            // One icon file per platform, generated from assets/app-icon.svg by
            // scripts/generate_app_icon.py.
            //
            // Linux deliberately gets a single PNG rather than a directory of
            // sizes: jpackage reads the pixel dimensions out of the image and
            // snaps them to 16/22/32/48/64/128, so a set of files would not be
            // consulted at all. The script writes 128px for the same reason --
            // it is already on that list, so the size jpackage declares and the
            // size the file actually is stay in agreement.
            windows {
                iconFile.set(rootProject.layout.projectDirectory.file("packaging/icons/windows/app.ico"))

                // jpackage writes a Start Menu entry only when it is asked to,
                // and the default is not to ask. An installed app with no Start
                // Menu entry can only be launched by going and finding its
                // folder, which is easy to mistake for the install having gone
                // wrong. Left without a menu group on purpose, so the entry sits
                // at the top level of the Start Menu rather than inside a
                // one-item folder named after itself.
                menu = true

                // A desktop shortcut is a separate setting and is deliberately
                // NOT enabled: an installer that litters the desktop uninvited
                // is worse than one that does not.
            }
            linux {
                iconFile.set(rootProject.layout.projectDirectory.file("packaging/icons/linux/app.png"))

                // The same defect, one platform over: without this jpackage
                // writes no .desktop file at all, and an app with no .desktop
                // file never appears in the application menu.
                shortcut = true

                // Feeds Categories= in that .desktop file. AudioVideo is a
                // registered freedesktop main category; left unset it falls back
                // to jpackage's generic default and lands in the wrong part of
                // the menu.
                menuGroup = "AudioVideo"
            }
        }
    }
}

// Keep jpackage from overwriting the Linux launcher icon with its own.
//
// jpackage takes two different things from --icon, depending on how it is
// invoked. Building an app image, it is the launcher icon, and ours is used --
// the staged image under build/compose/binaries/main/app carries the music note
// as it should. Assembling an *installer* from that image (--app-image, which
// is how Compose always packages), it is not: the package icon is looked up
// separately, as <name>.png in jpackage's resource directory, and --icon is
// ignored. Compose passes no --resource-dir on that path and the DSL exposes no
// way to add one, so the lookup fails and jpackage quietly writes its built-in
// JavaApp.png into the app directory the generated .desktop file points at.
// Installed, the app wears the Java mascot.
//
// Clearing the app image moves the packaging onto Compose's other path, where
// it hands jpackage --input and --main-jar and lets it build the image itself;
// there --icon is honoured. Two inputs have to come along, because Compose only
// wires them into the app-image task and dropping them is silent:
//
//   * appResourcesDir -- the staged decoder. Without it the installers ship an
//     app directory whose resources/ is empty: an app with no ffmpeg, which
//     fails the way verifyBundledDecoder exists to prevent, except that it
//     checks the staging directory rather than the artifact.
//   * runtimeImage -- the jlinked runtime. Without it jpackage bundles the
//     whole JDK it is running under (~180 MB of JBR here, libcef.so included)
//     instead of the trimmed image.
//
// Checked against the package this replaces: same 229 files, byte for byte,
// except the icon, which is now the 128px music note. Windows is left alone --
// the MSI takes the same route, but nothing here can test whether it shares the
// defect, and this is only wanted for the two formats it was diagnosed on.
afterEvaluate {
    val prepareAppResources = tasks.named<Sync>("prepareAppResources")
    val createRuntimeImage = tasks.named<AbstractJLinkTask>("createRuntimeImage")
    tasks.withType<AbstractJPackageTask>().configureEach {
        if (targetFormat == TargetFormat.Deb || targetFormat == TargetFormat.Rpm) {
            dependsOn(prepareAppResources, createRuntimeImage)
            appImage.set(null as Directory?)
            appResourcesDir.set(layout.dir(prepareAppResources.map { it.destinationDir }))
            runtimeImage.set(createRuntimeImage.flatMap { it.destinationDir })
        }
    }
}

// Development convenience: run against a throwaway config directory instead of
// the real user profile.
//   ./gradlew :composeApp:run -Dcmp.config=path/to/config
// The generated demo library from scripts/generate_demo_media.py prints the
// exact command to use.
tasks.withType<JavaExec>().configureEach {
    val configDir = providers.systemProperty("cmp.config").orNull
    if (!configDir.isNullOrBlank()) {
        systemProperty("composemusicplayer.config.dir", configDir)
    }
}

// Fail the packaging step when the decoder for *this* platform was never staged.
//
// Checking merely that some build is present is not enough: staging the Windows
// build and then packaging a .deb on Linux would sail through that check and
// ship an installer whose decoder is missing -- a problem the user only meets
// after installing. The app has a diagnostic screen for a genuinely damaged
// installation, but producing such an installer is the build's mistake to catch.
//
// Deliberately NOT attached to `prepareAppResources`. That task is also in the
// `run` graph and Gradle skips it as NO-SOURCE when there is nothing to copy --
// and a skipped task never runs its actions, so the check would evaporate in
// exactly the case it exists to catch.
val verifyBundledDecoder by tasks.registering {
    group = "compose desktop"
    description = "Verifies that the ffmpeg build for this platform has been staged."

    // Resolved at configuration time; reaching for the Project from a task
    // action would make it unserializable and cost us the configuration cache.
    val decoderRoot = rootProject.layout.projectDirectory.dir("third_party/ffmpeg").asFile
    val hostOs = providers.systemProperty("os.name")
    val hostArch = providers.systemProperty("os.arch")

    // No declared outputs, so this never goes up to date. A validation that can
    // be skipped is worse than no validation.
    doLast {
        val os = hostOs.get().lowercase()
        val osKey = when {
            os.contains("win") -> "windows"
            os.contains("linux") -> "linux"
            else -> null
        }
        if (osKey == null) {
            logger.lifecycle("Skipping bundled decoder check: unsupported host '$os'")
            return@doLast
        }

        // Must match the directory names scripts/fetch_ffmpeg.py stages into,
        // which in turn match what FfmpegBinaries looks for at runtime.
        val archKey = when (val arch = hostArch.get().lowercase()) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> arch
        }
        val platform = "$osKey-$archKey"

        val suffix = if (osKey == "windows") ".exe" else ""
        val required = listOf("ffmpeg$suffix", "ffprobe$suffix")
        val directory = File(decoderRoot, platform)
        val missing = required.filterNot { File(directory, it).isFile }

        if (missing.isNotEmpty()) {
            val staged = decoderRoot.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                ?.sorted()
                .orEmpty()

            throw GradleException(
                buildString {
                    append("No bundled ffmpeg staged for this platform.\n")
                    append("\n  needed: third_party/ffmpeg/$platform/")
                    append(required.joinToString(", "))
                    append("\n  staged: ")
                    append(if (staged.isEmpty()) "(nothing)" else staged.joinToString(", "))
                    append("\n\nFetch it with:\n  python scripts/fetch_ffmpeg.py $platform")
                    append("\n\nOr build a much smaller audio-only one with:\n")
                    append("  python scripts/build_minimal_ffmpeg.py $platform")
                },
            )
        }

        // An executable is only half of a shared build, and staging the
        // launchers without the libraries they load produces an installer whose
        // decoder cannot start. The check above would not notice: the missing
        // file is not one of the files it looked for.
        //
        // The names are read straight out of the executables rather than
        // guessed at, so this holds for both flavours. A self-contained build
        // references nothing and passes without a special case.
        //
        // Only versioned sonames count. A bare `libavcodec.so` can appear in
        // documentation strings inside an otherwise perfectly static binary,
        // and flagging that would break the packaging of a good build.
        val referencePattern = when (osKey) {
            "windows" -> Regex("""(?:av|sw)[a-z]+-\d+\.dll""")
            else -> Regex("""lib(?:av|sw)[a-z]*\.so\.\d+""")
        }
        val referenced = sortedSetOf<String>()
        for (name in required) {
            val file = File(directory, name)
            if (!file.isFile) continue
            // Latin-1 rather than UTF-8: this is a byte-for-byte scan of a
            // binary, and every string it is looking for is ASCII.
            val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
            referencePattern.findAll(bytes).forEach { referenced += it.value }
        }
        val absent = referenced.filterNot { File(directory, it).isFile }
        if (absent.isNotEmpty()) {
            throw GradleException(
                buildString {
                    append("The staged ffmpeg for $platform is missing the libraries it loads.\n")
                    append("\n  present: ")
                    append(required.joinToString(", "))
                    append("\n  missing: ")
                    append(absent.joinToString(", "))
                    append("\n\nAn installer built from this would ship a decoder that cannot start.")
                    append("\nRe-stage it with one of:\n")
                    append("  python scripts/fetch_ffmpeg.py $platform")
                    append("          (general build; on Linux add: patchelf --set-rpath '\$ORIGIN'")
                    append(" third_party/ffmpeg/$platform/ffmpeg third_party/ffmpeg/$platform/ffprobe)")
                    append("\n  python scripts/build_minimal_ffmpeg.py $platform")
                    append("          (audio only, self-contained, far smaller)")
                },
            )
        }

        // The executables are tiny; the shared libraries beside them are the
        // bulk of it, so report the directory rather than the binary.
        val megabytes = directory.listFiles()
            ?.filter { it.isFile }
            ?.sumOf { it.length() }
            ?.div(1024L * 1024L)
            ?: 0L
        logger.lifecycle("Bundled decoder: $platform ($megabytes MB)")
    }
}

// Every task that produces something a user installs. `run` is excluded on
// purpose: a development run is allowed to start without a decoder so the
// in-app diagnostic screen can be exercised.
listOf(
    "packageMsi",
    "packageReleaseMsi",
    "packageDeb",
    "packageReleaseDeb",
    "packageRpm",
    "packageReleaseRpm",
    "packageDistributionForCurrentOS",
    "packageReleaseDistributionForCurrentOS",
    "createDistributable",
    "createReleaseDistributable",
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn(verifyBundledDecoder)
    }
}
