import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations

@UntrackedTask(because = "oxfmt scans the repository according to .gitignore")
abstract class CheckMarkdownTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packageJson: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pnpmLock: RegularFileProperty

    @get:Internal
    abstract val nodeModulesDirectory: DirectoryProperty

    @TaskAction
    fun check() {
        val executable = NodeTools.requireToolExecutable(
            nodeModulesDir = nodeModulesDirectory.get().asFile,
            packageJson = packageJson.get().asFile,
            pnpmLock = pnpmLock.get().asFile,
            packageName = "oxfmt",
            binName = "oxfmt",
        )
        exec.exec {
            workingDir = repositoryRoot.get().asFile
            commandLine(executable.absolutePath, "--check", "**/*.md")
        }
    }
}
