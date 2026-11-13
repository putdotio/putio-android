import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import org.gradle.api.file.ArchiveOperations

internal object NpmPackage {
    private const val TIMEOUT_MILLIS = 30_000

    fun download(url: String): ByteArray {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    fun unpack(archives: ArchiveOperations, tarball: File, wanted: Set<String>): Map<String, ByteArray> {
        val members = wanted.map { "package/$it" }.toSet()
        val files = mutableMapOf<String, ByteArray>()
        archives.tarTree(archives.gzip(tarball)).visit {
            if (!isDirectory && relativePath.pathString in members) {
                files[relativePath.pathString.removePrefix("package/")] = open().use { it.readBytes() }
            }
        }
        return files
    }
}
