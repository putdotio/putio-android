import java.io.File
import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NodeToolsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun resolvesValidPosixToolExecutable() {
        val root = createFixture()
        val executable = NodeTools.requireToolExecutable(
            nodeModulesDir = root.resolve("node_modules"),
            packageJson = root.resolve("package.json"),
            packageLock = root.resolve("package-lock.json"),
            packageName = "@google/design.md",
            binName = "designmd",
            isWindows = false,
        )
        assertEquals(root.resolve("node_modules/.bin/designmd"), executable)
    }

    @Test
    fun resolvesWindowsCmdToolExecutable() {
        val root = createFixture(windows = true)
        val executable = NodeTools.requireToolExecutable(
            nodeModulesDir = root.resolve("node_modules"),
            packageJson = root.resolve("package.json"),
            packageLock = root.resolve("package-lock.json"),
            packageName = "oxfmt",
            binName = "oxfmt",
            isWindows = true,
        )
        assertEquals(root.resolve("node_modules/.bin/oxfmt.cmd"), executable)
    }

    @Test
    fun rejectsMissingNodeModulesDirectory() {
        val root = createFixture(createNodeModules = false)
        val error = assertThrows(GradleException::class.java) {
            NodeTools.requireToolExecutable(
                nodeModulesDir = root.resolve("node_modules"),
                packageJson = root.resolve("package.json"),
                packageLock = root.resolve("package-lock.json"),
                packageName = "oxfmt",
                binName = "oxfmt",
                isWindows = false,
            )
        }
        assertTrue(error.message.orEmpty().contains("not installed in node_modules (found none)"))
        assertTrue(error.message.orEmpty().contains("run npm ci"))
    }

    @Test
    fun rejectsMissingNodeModulesLockStamp() {
        val root = createFixture(createModulesLockStamp = false)
        val error = assertThrows(GradleException::class.java) {
            NodeTools.requireToolExecutable(
                nodeModulesDir = root.resolve("node_modules"),
                packageJson = root.resolve("package.json"),
                packageLock = root.resolve("package-lock.json"),
                packageName = "oxfmt",
                binName = "oxfmt",
                isWindows = false,
            )
        }
        assertTrue(error.message.orEmpty().contains("not installed in node_modules (found none)"))
    }

    @Test
    fun rejectsStaleInstalledPackageVersion() {
        val root = createFixture(installedOxfmtVersion = "0.78.0")
        val error = assertThrows(GradleException::class.java) {
            NodeTools.requireToolExecutable(
                nodeModulesDir = root.resolve("node_modules"),
                packageJson = root.resolve("package.json"),
                packageLock = root.resolve("package-lock.json"),
                packageName = "oxfmt",
                binName = "oxfmt",
                isWindows = false,
            )
        }
        assertTrue(error.message.orEmpty().contains("oxfmt 0.79.0 is not installed in node_modules (found 0.78.0)"))
    }

    @Test
    fun rejectsLockfileMismatchAgainstPackageJson() {
        val root = createFixture(lockedOxfmtVersion = "0.78.0")
        val error = assertThrows(GradleException::class.java) {
            NodeTools.requireToolExecutable(
                nodeModulesDir = root.resolve("node_modules"),
                packageJson = root.resolve("package.json"),
                packageLock = root.resolve("package-lock.json"),
                packageName = "oxfmt",
                binName = "oxfmt",
                isWindows = false,
            )
        }
        assertTrue(error.message.orEmpty().contains("package-lock.json has oxfmt 0.78.0, expected 0.79.0"))
    }

    @Test
    fun rejectsMissingDevDependency() {
        val root = createFixture(includeOxfmtInPackageJson = false)
        val error = assertThrows(GradleException::class.java) {
            NodeTools.requireToolExecutable(
                nodeModulesDir = root.resolve("node_modules"),
                packageJson = root.resolve("package.json"),
                packageLock = root.resolve("package-lock.json"),
                packageName = "oxfmt",
                binName = "oxfmt",
                isWindows = false,
            )
        }
        assertTrue(error.message.orEmpty().contains("package.json must pin oxfmt in devDependencies"))
    }

    @Test
    fun rejectsMissingBinExecutable() {
        val root = createFixture(createBinFiles = false)
        val error = assertThrows(GradleException::class.java) {
            NodeTools.requireToolExecutable(
                nodeModulesDir = root.resolve("node_modules"),
                packageJson = root.resolve("package.json"),
                packageLock = root.resolve("package-lock.json"),
                packageName = "@google/design.md",
                binName = "designmd",
                isWindows = false,
            )
        }
        assertTrue(error.message.orEmpty().contains("@google/design.md 0.4.0 is not installed in node_modules"))
    }

    private fun createFixture(
        windows: Boolean = false,
        createNodeModules: Boolean = true,
        createModulesLockStamp: Boolean = true,
        createBinFiles: Boolean = true,
        includeOxfmtInPackageJson: Boolean = true,
        lockedOxfmtVersion: String = "0.79.0",
        installedOxfmtVersion: String = "0.79.0",
    ): File {
        val root = temp.newFolder()
        val devDeps = buildString {
            append("""    "@google/design.md": "0.4.0"""")
            if (includeOxfmtInPackageJson) {
                append(",\n    \"oxfmt\": \"0.79.0\"")
            }
        }
        root.resolve("package.json").writeText(
            """
            {
              "private": true,
              "devDependencies": {
            $devDeps
              }
            }
            """.trimIndent(),
        )
        root.resolve("package-lock.json").writeText(
            """
            {
              "lockfileVersion": 3,
              "packages": {
                "node_modules/@google/design.md": {
                  "version": "0.4.0"
                },
                "node_modules/oxfmt": {
                  "version": "$lockedOxfmtVersion"
                }
              }
            }
            """.trimIndent(),
        )
        if (createNodeModules) {
            val nodeModules = root.resolve("node_modules").apply { mkdirs() }
            if (createModulesLockStamp) {
                nodeModules.resolve(".package-lock.json").writeText("{}")
            }
            nodeModules.resolve("@google/design.md").apply { mkdirs() }
                .resolve("package.json").writeText("""{"version":"0.4.0"}""")
            nodeModules.resolve("oxfmt").apply { mkdirs() }
                .resolve("package.json").writeText("""{"version":"$installedOxfmtVersion"}""")
            if (createBinFiles) {
                val bin = nodeModules.resolve(".bin").apply { mkdirs() }
                val suffix = if (windows) ".cmd" else ""
                bin.resolve("designmd$suffix").writeText("bin")
                bin.resolve("oxfmt$suffix").writeText("bin")
            }
        }
        return root
    }
}
