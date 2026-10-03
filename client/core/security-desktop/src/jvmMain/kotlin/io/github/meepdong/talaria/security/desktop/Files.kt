package io.github.meepdong.talaria.security.desktop

import io.github.meepdong.talaria.security.KeyProtection
import io.github.meepdong.talaria.security.KeyStoreException
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

/**
 * Write [bytes] to [file] so that it is never half-written and, on Linux, only readable
 * by this user (0600). On Windows, files under %APPDATA% are private to the user already.
 */
internal fun writePrivateFile(file: File, bytes: ByteArray) {
    try {
        val dir = file.absoluteFile.parentFile.toPath()
        Files.createDirectories(dir)
        val tmp = if (posix) {
            Files.createTempFile(dir, ".${file.name}", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createTempFile(dir, ".${file.name}", ".tmp")
        }
        try {
            Files.write(tmp, bytes)
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    } catch (e: IOException) {
        throw KeyStoreException("Couldn't save the device key in ${file.parent}: ${e.message}", e)
    }
}

internal fun readFileOrNull(file: File): ByteArray? = try {
    if (file.exists()) file.readBytes() else null
} catch (e: IOException) {
    throw KeyStoreException("Couldn't read the device key from $file: ${e.message}", e)
}

/** Fallback when there is no keyring: the key in a file only this user can read. */
class PlainFileStore(private val file: File) : KeyBlobStore {
    override val protection get() = KeyProtection.FILE_ONLY
    fun exists(): Boolean = file.exists()
    override fun read(): ByteArray? = readFileOrNull(file)
    override fun write(bytes: ByteArray) = writePrivateFile(file, bytes)
    override fun delete() {
        file.delete()
    }
}
