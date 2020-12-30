package com.tverona.scpanywhere.zipresource

import android.content.res.AssetFileDescriptor
import android.os.ParcelFileDescriptor
import androidx.lifecycle.LifecycleCoroutineScope
import com.tverona.scpanywhere.utils.loge
import com.tverona.scpanywhere.utils.logi
import com.tverona.scpanywhere.utils.logv
import com.tverona.scpanywhere.utils.logw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipFile
import kotlin.concurrent.withLock

/**
 * Wraps zip file(s) and exposes input streams from contained files
 */
class ZipResourceFile(
    private val lifecycleCoroutineScope: LifecycleCoroutineScope,
    zipFileNames: List<String>
) {
    class ZipEntryRO(
        val zipFile: File
    ) {
        var mLocalHdrOffset // offset of local file header
                : Long = 0

        /* useful stuff from the directory entry */
        var mMethod = 0
        var mWhenModified: Long = 0
        var mCRC32: Long = 0
        var mCompressedLength: Long = 0
        var mUncompressedLength: Long = 0

        /**
         * Calculates the offset of the start of the Zip file entry within the
         * Zip file.
         *
         * @return the offset, in bytes from the start of the file of the entry
         */
        private var offset: Long = -1

        @Throws(IOException::class)
        fun setOffsetFromFile(f: RandomAccessFile, buf: ByteBuffer) {
            val localHdrOffset = mLocalHdrOffset
            try {
                f.seek(localHdrOffset)
                f.readFully(buf.array())
                if (buf.getInt(0) != kLFHSignature) {
                    logw(
                        "didn't find signature at start of lfh"
                    )
                    throw IOException()
                }
                val nameLen: Int =
                    buf.getShort(kLFHNameLen).toInt() and 0xffff
                val extraLen: Int =
                    buf.getShort(kLFHExtraLen).toInt() and 0xffff
                offset =
                    localHdrOffset + kLFHLen + nameLen + extraLen
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            } catch (ioe: IOException) {
                ioe.printStackTrace()
            }
        }

        /**
         * isUncompressed
         *
         * @return true if the file is stored in uncompressed form
         */
        val isUncompressed: Boolean
            get() = mMethod == kCompressStored

        val assetFileDescriptor: AssetFileDescriptor?
            get() {
                if (mMethod == kCompressStored) {
                    val pfd: ParcelFileDescriptor
                    try {
                        pfd =
                            ParcelFileDescriptor.open(zipFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        return AssetFileDescriptor(pfd, offset, mUncompressedLength)
                    } catch (e: FileNotFoundException) {
                        loge("Error opening ParcelFileDescriptor", e)
                    }
                }
                return null
            }
    }

    private val mHashMap =
        HashMap<String, ZipEntryRO>()

    private var finishedIndexing = AtomicBoolean(false)
    private val mLock = ReentrantLock()

    /* for reading compressed files */
    private var mZipFiles =
        HashMap<File, ZipFile>()

    /**
     * getInputStream returns an AssetFileDescriptor.AutoCloseInputStream
     * associated with the asset that is contained in the Zip file, or a
     * standard ZipInputStream if necessary to uncompress the file
     *
     * @param assetPath
     * @return an input stream for the named asset path, or null if not found
     * @throws IOException
     */
    @Throws(IOException::class)
    fun getInputStream(assetPath: String): InputStream? {
        try {
            if (!finishedIndexing.get()) {
                // We haven't finished indexing yet, so get it from slower path
                return getInputSteamUnindexed(assetPath)
            } else {
                val entry =
                    mHashMap[assetPath]
                if (null != entry) {
                    if (entry.isUncompressed) {
                        return entry.assetFileDescriptor!!.createInputStream()
                    } else {
                        var zf = mZipFiles[entry.zipFile]
                        val zi = zf?.getEntry(assetPath)
                        if (null != zi) return zf?.getInputStream(zi)
                    }
                }
            }
        } catch (e: Exception) {
            loge("Failed to get input stream: $assetPath", e)
        }
        return null
    }

    fun getNumEntries(): Int {
        return mHashMap.size
    }

    /**
     * Get input stream for asset [assetPath] while we're not yet indexed (by enumerating zip files)
     */
    private fun getInputSteamUnindexed(assetPath: String): InputStream? {
        for (zipFile in mZipFiles) {
            val zi = zipFile.value.getEntry(assetPath)
            if (null != zi) return zipFile.value.getInputStream(zi)
        }
        return null
    }

    /**
     * Index the file entries. Opens the specified file read-only, memory-map the entire thing and
     * close the file before returning.
     */
    @Throws(IOException::class)
    private fun indexFileEntries(file: File) {
        RandomAccessFile(file, "r").use {
            val fileLength = it.length()
            if (fileLength < kEOCDLen) {
                throw IOException()
            }
            var readAmount =
                kMaxEOCDSearch.toLong()
            if (readAmount > fileLength) readAmount = fileLength

            // Make sure this is a Zip archive.
            it.seek(0)
            val header =
                read4LE(it)
            if (header == kEOCDSignature) {
                logw(
                    "Found Zip archive, but it looks empty"
                )
                throw IOException()
            } else if (header != kLFHSignature) {
                logw(
                    "Not a Zip archive"
                )
                throw IOException()
            }

            /*
             * Perform the traditional EOCD snipe hunt. We're searching for the End
             * of Central Directory magic number, which appears at the start of the
             * EOCD block. It's followed by 18 bytes of EOCD stuff and up to 64KB of
             * archive comment. We need to read the last part of the file into a
             * buffer, dig through it to find the magic number, parse some values
             * out, and use those to determine the extent of the CD. We start by
             * pulling in the last part of the file.
             */
            val searchStart = fileLength - readAmount
            it.seek(searchStart)
            val bbuf = ByteBuffer.allocate(readAmount.toInt())
            val buffer = bbuf.array()
            it.readFully(buffer)
            bbuf.order(ByteOrder.LITTLE_ENDIAN)

            /*
             * Scan backward for the EOCD magic. In an archive without a trailing
             * comment, we'll find it on the first try. (We may want to consider
             * doing an initial minimal read; if we don't find it, retry with a
             * second read as above.)
             */

            // EOCD == 0x50, 0x4b, 0x05, 0x06
            var eocdIdx: Int
            eocdIdx =
                buffer.size - kEOCDLen
            while (eocdIdx >= 0) {
                if (buffer[eocdIdx] == 0x50.toByte() && bbuf.getInt(eocdIdx) == kEOCDSignature) {
                    logv(
                        "+++ Found EOCD at index: $eocdIdx for file ${file.name}"
                    )
                    break
                }
                eocdIdx--
            }
            if (eocdIdx < 0) {
                logv(
                    "Zip: EOCD not found, ${file.path} is not zip"
                )
            }

            /*
             * Grab the CD offset and size, and the number of entries in the
             * archive. After that, we can release our EOCD hunt buffer.
             */
            val numEntries =
                bbuf.getShort(eocdIdx + kEOCDNumEntries).toInt() and 0xffff
            val dirSize =
                bbuf.getInt(eocdIdx + kEOCDSize).toLong() and 0xffffffffL
            val dirOffset =
                bbuf.getInt(eocdIdx + kEOCDFileOffset).toLong() and 0xffffffffL

            // Verify that they look reasonable.
            if (dirOffset + dirSize > fileLength) {
                logw(
                    "bad offsets (dir $dirOffset, size $dirSize, eocd $eocdIdx)"
                )
                throw IOException()
            }
            if (numEntries == 0) {
                logw(
                    "empty archive?"
                )
                throw IOException()
            }
            logv(
                "+++ numEntries=$numEntries dirSize=$dirSize dirOffset=$dirOffset for file ${file.name}"
            )
            val directoryMap = it.channel
                .map(FileChannel.MapMode.READ_ONLY, dirOffset, dirSize)
            directoryMap.order(ByteOrder.LITTLE_ENDIAN)
            val tempBuf = ByteArray(0xffff)

            /*
             * Walk through the central directory, adding entries to the hash table.
             */
            var currentOffset = 0

            /*
             * Allocate the local directory information
             */
            val buf =
                ByteBuffer.allocate(kLFHLen)
            buf.order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numEntries) {
                if (directoryMap.getInt(currentOffset) != kCDESignature) {
                    logw(
                        "Missed a central dir sig (at $currentOffset)"
                    )
                    throw IOException()
                }

                /* useful stuff from the directory entry */
                val fileNameLen: Int =
                    directoryMap.getShort(currentOffset + kCDENameLen).toInt() and 0xffff
                val extraLen: Int =
                    directoryMap.getShort(currentOffset + kCDEExtraLen).toInt() and 0xffff
                val commentLen: Int =
                    directoryMap.getShort(currentOffset + kCDECommentLen).toInt() and 0xffff

                /* get the CDE filename */directoryMap.position(currentOffset + kCDELen)
                directoryMap[tempBuf, 0, fileNameLen]
                directoryMap.position(0)

                /* UTF-8 on Android */
                val str = String(tempBuf, 0, fileNameLen)
                if (LOGV) {
                    logv(
                        "Filename: $str"
                    )
                }

                val ze =
                    ZipEntryRO(
                        file
                    )
                ze.mMethod =
                    directoryMap.getShort(currentOffset + kCDEMethod).toInt() and 0xffff
                ze.mWhenModified =
                    directoryMap.getInt(currentOffset + kCDEModWhen).toLong() and 0xffffffffL
                ze.mCRC32 =
                    directoryMap.getLong(currentOffset + kCDECRC) and 0xffffffffL
                ze.mCompressedLength =
                    directoryMap.getLong(currentOffset + kCDECompLen) and 0xffffffffL
                ze.mUncompressedLength =
                    directoryMap.getLong(currentOffset + kCDEUncompLen) and 0xffffffffL
                ze.mLocalHdrOffset =
                    directoryMap.getInt(currentOffset + kCDELocalOffset).toLong() and 0xffffffffL

                // set the offsets
                buf.clear()
                ze.setOffsetFromFile(it, buf)

                // put file into hash
                // Lock to allow for concurrent population
                mLock.withLock {
                    mHashMap[str] = ze
                }

                // go to next directory entry
                currentOffset += kCDELen + fileNameLen + extraLen + commentLen
            }

            logv(
                "+++ zip good scan $numEntries entries for file ${file.name}"
            )
        }
    }

    /**
     * Indexes files async.
     */
    private suspend fun indexFilesAsync(zipFileNames: List<String>) {
        withContext(Dispatchers.IO) {
            for (fileName in zipFileNames) {
                launch {
                    try {
                        indexFileEntries(File(fileName))
                    } catch (e: Exception) {
                        loge("Error indexing file: $fileName", e)
                    }
                }
            }
        }

        logv("Finished processing zip files")
        finishedIndexing.set(true)
    }

    fun close() {
        mZipFiles.forEach {
            it.value.close()
        }
        mZipFiles.clear()
        mHashMap.clear()
        finishedIndexing.set(false)
    }

    init {
        for (fileName in zipFileNames) {
            try {
                logi("Opening zip file $fileName")
                val file = File(fileName)
                val zf = ZipFile(file, ZipFile.OPEN_READ)
                mZipFiles[file] = zf
                logi("Added zip file $fileName")
            } catch (e: Exception) {
                loge("Error processing file: $fileName", e)
            }
        }

        // Asynchronously index files
        lifecycleCoroutineScope.launch {
            indexFilesAsync(zipFileNames)
        }
    }

    companion object {
        // Read-only access to Zip archives, with minimal heap allocation.
        const val LOGV = false

        // 4-byte number
        private fun swapEndian(i: Int): Int {
            return ((i and 0xff shl 24) + (i and 0xff00 shl 8) + (i and 0xff0000 ushr 8)
                    + (i ushr 24 and 0xff))
        }

        // Zip file constants
        const val kEOCDSignature = 0x06054b50
        const val kEOCDLen = 22
        const val kEOCDNumEntries = 8 // offset to #of entries in file
        const val kEOCDSize = 12 // size of the central directory
        const val kEOCDFileOffset = 16 // offset to central directory
        private const val kMaxCommentLen = 65535 // longest possible in ushort
        const val kMaxEOCDSearch =
            kMaxCommentLen + kEOCDLen
        const val kLFHSignature = 0x04034b50
        const val kLFHLen = 30 // excluding variable-len fields
        const val kLFHNameLen = 26 // offset to filename length
        const val kLFHExtraLen = 28 // offset to extra length
        const val kCDESignature = 0x02014b50
        const val kCDELen = 46 // excluding variable-len fields
        const val kCDEMethod = 10 // offset to compression method
        const val kCDEModWhen = 12 // offset to modification timestamp
        const val kCDECRC = 16 // offset to entry CRC
        const val kCDECompLen = 20 // offset to compressed length
        const val kCDEUncompLen = 24 // offset to uncompressed length
        const val kCDENameLen = 28 // offset to filename length
        const val kCDEExtraLen = 30 // offset to extra length
        const val kCDECommentLen = 32 // offset to comment length
        const val kCDELocalOffset = 42 // offset to local hdr
        const val kCompressStored = 0 // no compression

        @Throws(EOFException::class, IOException::class)
        private fun read4LE(f: RandomAccessFile): Int {
            return swapEndian(
                f.readInt()
            )
        }
    }
}