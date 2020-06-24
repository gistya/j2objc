/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package libcore.io;

import android.system.ErrnoException;
import android.system.Int32Ref;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import libcore.util.ArrayUtils;

import static libcore.io.OsConstants.*;

/**
 * Implements java.io/java.net/java.nio semantics in terms of the underlying POSIX system calls.
 *
 * @hide
 */
@libcore.api.CorePlatformApi
public final class IoBridge {

    private IoBridge() {
    }

    public static int available(FileDescriptor fd) throws IOException {
        try {
            Int32Ref available = new Int32Ref(0);
            Libcore.os.ioctlInt(fd, FIONREAD, available);
            if (available.value < 0) {
                // If the fd refers to a regular file, the result is the difference between
                // the file size and the file position. This may be negative if the position
                // is past the end of the file. If the fd refers to a special file masquerading
                // as a regular file, the result may be negative because the special file
                // may appear to have zero size and yet a previous read call may have
                // read some amount of data and caused the file position to be advanced.
                available.value = 0;
            }
            return available.value;
        } catch (ErrnoException errnoException) {
            if (errnoException.errno == ENOTTY) {
                // The fd is unwilling to opine about its read buffer.
                return 0;
            }
            throw errnoException.rethrowAsIOException();
        }
    }

    /**
     * Closes the Unix file descriptor associated with the supplied file descriptor, resets the
     * internal int to -1, and sends a signal to any threads are currently blocking. In order for
     * the signal to be sent the blocked threads must have registered with the
     * AsynchronousCloseMonitor before they entered the blocking operation. {@code fd} will be
     * invalid after this call.
     *
     * <p>This method is a no-op if passed a {@code null} or already-closed file descriptor.
     */
    @libcore.api.CorePlatformApi
    public static void closeAndSignalBlockedThreads(FileDescriptor fd) throws IOException {
        if (fd == null || !fd.valid()) {
            return;
        }
        // fd is invalid after we call release.
        FileDescriptor oldFd = fd.release$();
        AsynchronousCloseMonitor.signalBlockedThreads(oldFd);
        try {
            Libcore.os.close(oldFd);
        } catch (ErrnoException errnoException) {
            throw errnoException.rethrowAsIOException();
        }
    }


    /**
     * java.io only throws FileNotFoundException when opening files, regardless of what actually
     * went wrong. Additionally, java.io is more restrictive than POSIX when it comes to opening
     * directories: POSIX says read-only is okay, but java.io doesn't even allow that.
     */
    @libcore.api.CorePlatformApi
    public static FileDescriptor open(String path, int flags) throws FileNotFoundException {
        FileDescriptor fd = null;
        try {
            fd = Libcore.os.open(path, flags, 0666);
            // Posix open(2) fails with EISDIR only if you ask for write permission.
            // Java disallows reading directories too.
            if (isDirectory(path)) {
                throw new ErrnoException("open", EISDIR);
            }
            return fd;
        } catch (ErrnoException errnoException) {
            try {
                if (fd != null) {
                    closeAndSignalBlockedThreads(fd);
                }
            } catch (IOException ignored) {
            }
            FileNotFoundException ex = new FileNotFoundException(path + ": " + errnoException.getMessage());
            ex.initCause(errnoException);
            throw ex;
        }
    }

    private static native boolean isDirectory(String path) /*-[
      NSDictionary *attrs = [[NSFileManager defaultManager] attributesOfItemAtPath:path error:nil];
      return [[attrs fileType] isEqualToString:NSFileTypeDirectory];
    ]-*/;

    /**
     * java.io thinks that a read at EOF is an error and should return -1, contrary to traditional
     * Unix practice where you'd read until you got 0 bytes (and any future read would return -1).
     */
    @libcore.api.CorePlatformApi
    public static int read(FileDescriptor fd, byte[] bytes, int byteOffset, int byteCount) throws IOException {
        ArrayUtils.throwsIfOutOfBounds(bytes.length, byteOffset, byteCount);
        if (byteCount == 0) {
            return 0;
        }
        try {
            int readCount = Libcore.os.read(fd, bytes, byteOffset, byteCount);
            if (readCount == 0) {
                return -1;
            }
            return readCount;
        } catch (ErrnoException errnoException) {
            if (errnoException.errno == EAGAIN) {
                // We return 0 rather than throw if we try to read from an empty non-blocking pipe.
                return 0;
            }
            throw errnoException.rethrowAsIOException();
        }
    }

    /**
     * java.io always writes every byte it's asked to, or fails with an error. (That is, unlike
     * Unix it never just writes as many bytes as happens to be convenient.)
     */
    @libcore.api.CorePlatformApi
    public static void write(FileDescriptor fd, byte[] bytes, int byteOffset, int byteCount) throws IOException {
        ArrayUtils.throwsIfOutOfBounds(bytes.length, byteOffset, byteCount);
        if (byteCount == 0) {
            return;
        }
        try {
            while (byteCount > 0) {
                int bytesWritten = Libcore.os.write(fd, bytes, byteOffset, byteCount);
                byteCount -= bytesWritten;
                byteOffset += bytesWritten;
            }
        } catch (ErrnoException errnoException) {
            throw errnoException.rethrowAsIOException();
        }
    }
}
