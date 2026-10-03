package io.github.meepdong.talaria.desktop

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/** One app per user: start at login and a double-click shouldn't open two sessions. */
class AppLock private constructor(private val channel: FileChannel, private val lock: FileLock) {
    fun release() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        /** The lock, or null when another Talaria already holds it. */
        fun acquire(file: File): AppLock? {
            file.parentFile?.mkdirs()
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            } catch (e: IOException) {
                null
            }
            if (lock == null) {
                channel.close()
                return null
            }
            return AppLock(channel, lock)
        }
    }
}
