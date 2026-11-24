import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.gradle.api.GradleException

internal object NodeTools {
    fun pinnedVersion(packageJson: File, packageName: String): String {
        val root = parseObject(packageJson, "package.json")
        val devDependencies = root["devDependencies"] as? JsonObject
            ?: throw GradleException("package.json must declare devDependencies")
        return stringProperty(devDependencies, packageName)
            ?: throw GradleException("package.json must pin $packageName in devDependencies")
    }

    fun lockfileVersion(pnpmLock: File, packageName: String): String {
        if (!pnpmLock.isFile) throw GradleException("pnpm-lock.yaml is missing at ${pnpmLock.path}")
        var inImporters = false
        var inRootImporter = false
        var inDevDependencies = false
        var inTargetPackage = false
        for (rawLine in pnpmLock.readLines()) {
            val trimmed = rawLine.trim()
            if (trimmed == "---" || trimmed == "...") {
                inImporters = false
                inRootImporter = false
                inDevDependencies = false
                inTargetPackage = false
                continue
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = rawLine.indexOfFirst { it != ' ' }.let { if (it < 0) rawLine.length else it }
            when {
                indent == 0 -> {
                    inImporters = trimmed == "importers:"
                    inRootImporter = false
                    inDevDependencies = false
                    inTargetPackage = false
                }
                indent == 2 && inImporters -> {
                    inRootImporter = trimmed == ".:"
                    inDevDependencies = false
                    inTargetPackage = false
                }
                indent == 4 && inRootImporter -> {
                    inDevDependencies = trimmed == "devDependencies:"
                    inTargetPackage = false
                }
                indent == 6 && inDevDependencies -> {
                    val key = trimmed.substringBefore(':').trim().unquoteYaml()
                    inTargetPackage = trimmed.endsWith(":") && key == packageName
                }
                indent > 6 && inTargetPackage && trimmed.startsWith("version:") -> {
                    val version = trimmed.removePrefix("version:")
                        .trim()
                        .unquoteYaml()
                        .substringBefore('(')
                        .trim()
                    if (version.isNotEmpty()) return version
                }
            }
        }
        throw GradleException(
            "pnpm-lock.yaml is missing devDependencies.$packageName; " +
                "update pnpm-lock.yaml and run pnpm install --frozen-lockfile",
        )
    }

    fun requireToolExecutable(
        nodeModulesDir: File,
        packageJson: File,
        pnpmLock: File,
        packageName: String,
        binName: String,
        isWindows: Boolean = HostBash.isWindows,
    ): File {
        val expected = pinnedVersion(packageJson, packageName)
        val locked = lockfileVersion(pnpmLock, packageName)
        if (locked != expected) {
            throw GradleException(
                "pnpm-lock.yaml has $packageName $locked, expected $expected from package.json; " +
                    "update pnpm-lock.yaml and run pnpm install --frozen-lockfile",
            )
        }
        val modulesStamp = nodeModulesDir.resolve(".modules.yaml")
        val installed = installedVersion(nodeModulesDir.resolve("$packageName/package.json"))
        val executable = nodeModulesDir.resolve(".bin/${if (isWindows) "$binName.cmd" else binName}")
        if (!modulesStamp.isFile || installed != expected || !executable.isFile) {
            val found = if (modulesStamp.isFile) installed ?: "none" else "none"
            throw GradleException(
                "$packageName $expected is not installed in node_modules (found $found); " +
                    "run pnpm install --frozen-lockfile",
            )
        }
        return executable
    }

    private fun String.unquoteYaml(): String =
        if (length >= 2 && ((startsWith("'") && endsWith("'")) || (startsWith("\"") && endsWith("\"")))) {
            substring(1, length - 1)
        } else {
            this
        }

    private fun installedVersion(installedPackageJson: File): String? {
        if (!installedPackageJson.isFile) return null
        val root = runCatching { Json.parseToJsonElement(installedPackageJson.readText()) as? JsonObject }.getOrNull()
            ?: return null
        return stringProperty(root, "version")
    }

    private fun stringProperty(jsonObject: JsonObject, key: String): String? =
        (jsonObject[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun parseObject(file: File, label: String): JsonObject {
        if (!file.isFile) throw GradleException("$label is missing at ${file.path}")
        return try {
            Json.parseToJsonElement(file.readText()) as? JsonObject
                ?: throw GradleException("$label must be a JSON object")
        } catch (error: IllegalArgumentException) {
            throw GradleException("Invalid $label JSON", error)
        }
    }
}
