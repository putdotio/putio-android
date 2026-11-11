import java.io.File

/** The bash the scripts expect: on Windows a bare `bash` is the WSL launcher, so use Git for Windows' own. */
object HostBash {
    val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

    val path: String by lazy {
        if (!isWindows) {
            "bash"
        } else {
            System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .map { File(it, "git.exe") }
                .firstOrNull { it.isFile }
                ?.parentFile?.parentFile?.resolve("bin/bash.exe")
                ?.takeIf { it.isFile }
                ?.path
                ?: error("Git for Windows must be on PATH: its bash runs the scripts")
        }
    }
}
