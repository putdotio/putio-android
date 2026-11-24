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

    fun lockfileVersion(packageLock: File, packageName: String): String {
        val root = parseObject(packageLock, "package-lock.json")
        val packages = root["packages"] as? JsonObject
            ?: throw GradleException("package-lock.json must declare packages")
        val entry = packages["node_modules/$packageName"] as? JsonObject
            ?: throw GradleException(
                "package-lock.json is missing node_modules/$packageName; regenerate package-lock.json and run npm ci",
            )
        return stringProperty(entry, "version")
            ?: throw GradleException("package-lock.json entry for $packageName has no version")
    }

    fun requireToolExecutable(
        nodeModulesDir: File,
        packageJson: File,
        packageLock: File,
        packageName: String,
        binName: String,
        isWindows: Boolean = HostBash.isWindows,
    ): File {
        val expected = pinnedVersion(packageJson, packageName)
        val locked = lockfileVersion(packageLock, packageName)
        if (locked != expected) {
            throw GradleException(
                "package-lock.json has $packageName $locked, expected $expected from package.json; " +
                    "regenerate package-lock.json and run npm ci",
            )
        }
        val lockStamp = nodeModulesDir.resolve(".package-lock.json")
        val installed = installedVersion(nodeModulesDir.resolve("$packageName/package.json"))
        val executable = nodeModulesDir.resolve(".bin/${if (isWindows) "$binName.cmd" else binName}")
        if (!lockStamp.isFile || installed != expected || !executable.isFile) {
            val found = if (lockStamp.isFile) installed ?: "none" else "none"
            throw GradleException(
                "$packageName $expected is not installed in node_modules (found $found); run npm ci",
            )
        }
        return executable
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
