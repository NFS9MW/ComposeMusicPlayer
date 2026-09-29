import java.io.File

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// Windows packaging needs WiX, and the Compose Desktop plugin supplies it the
// same way it supplies the runtime image: by fetching it. `downloadWix` pulls
// wix311-binaries.zip from a GitHub release and `unzipWix` expands it into
// build/wix311, which is then put on jpackage's PATH.
//
// That download is the only step in the packaging pipeline that reaches outside
// a Maven repository, and the two halves of it are cached separately: the zip in
// the Gradle user home, the expansion in the build directory. Clean either on
// its own and the plugin still fetches the zip again, so on a machine that
// cannot reach github.com the build stops there with an HttpClient stack trace
// and no way forward -- while a perfectly good toolset sits in build/wix311.
//
// Skipping the download when the toolset is already expanded costs nothing on a
// machine that can download it, and makes packaging work on one that cannot, as
// long as it has been expanded there once. candle.exe compiles the WiX source,
// light.exe links the MSI and darice.cub is the cabinet builder light.exe loads;
// a partial expansion is missing at least one of them, so it still gets the
// download it is owed.
//
// A machine that would rather keep WiX somewhere durable can point the plugin at
// it with the WIX_PATH environment variable instead. That is read before any of
// this, the plugin then creates neither task, and the matching below finds
// nothing to configure.
val wixToolsetHome = layout.buildDirectory.dir("wix311").get().asFile
val wixToolsetIsExpanded = listOf("candle.exe", "light.exe", "darice.cub")
    .all { File(wixToolsetHome, it).isFile }

listOf("downloadWix", "unzipWix").forEach { taskName ->
    // Read into a local and handed to the spec by value. A spec that reads the
    // property above would carry a reference to the whole script object, and the
    // configuration cache refuses to serialize one -- failing the build at the
    // very end, after the MSI has already been written.
    val skip = wixToolsetIsExpanded
    tasks.matching { it.name == taskName }.configureEach {
        onlyIf { !skip }
    }
}
