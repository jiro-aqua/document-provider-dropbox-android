package jp.gr.aqua.dropbox.provider

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import com.dropbox.core.DbxException
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.FolderMetadata
import com.dropbox.core.v2.files.Metadata
import com.dropbox.core.v2.files.WriteMode
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.*
import java.util.concurrent.CountDownLatch

/**
 * Manages documents and exposes them to the Android system for sharing.
 */
class DropboxProvider : DocumentsProvider() {

    private val pref by lazy { Preference(context) }

    private val isUserLoggedIn: Boolean
        get() = token.isNotEmpty()

    private val token: String
        get() = pref.getToken()

    private val lastMetadata = HashMap<String,Metadata>()

    override fun onCreate(): Boolean {
        Log.v(TAG, "onCreate")
        context.cacheDir.listFiles().forEach { it.delete() }
        return true
    }

    // BEGIN_INCLUDE(query_roots)
    @Throws(FileNotFoundException::class)
    override fun queryRoots(projection: Array<String>?): Cursor {
        Log.v(TAG, "queryRoots" + projection)

        // Create a cursor with either the requested fields, or the default projection.  This
        // cursor is returned to the Android system picker UI and used to display all roots from
        // this provider.
        val result = MatrixCursor(resolveRootProjection(projection))

        // If user is not logged in, return an empty root cursor.  This removes our provider from
        // the list entirely.
        if (!isUserLoggedIn) {
            return result
        }

        // It's possible to have multiple roots (e.g. for multiple accounts in the same app) -
        // just add multiple cursor rows.
        // Construct one row for a root called "MyCloud".
        val row = result.newRow()

        row.add(Root.COLUMN_ROOT_ID, ROOT)
        row.add(Root.COLUMN_SUMMARY, email())

        // FLAG_SUPPORTS_CREATE means at least one directory under the root supports creating
        // documents.  FLAG_SUPPORTS_RECENTS means your application's most recently used
        // documents will show up in the "Recents" category.  FLAG_SUPPORTS_SEARCH allows users
        // to search all documents the application shares.
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or
                Root.FLAG_SUPPORTS_RECENTS or
                Root.FLAG_SUPPORTS_SEARCH)

        // COLUMN_TITLE is the root title (e.g. what will be displayed to identify your provider).
        row.add(Root.COLUMN_TITLE, context.getString(R.string.provider_name))

        // This document id must be unique within this provider and consistent across time.  The
        // system picker UI may save it and refer to it later.
        row.add(Root.COLUMN_DOCUMENT_ID, ROOT_DIRECTORY)

        // The child MIME types are used to filter the roots and only present to the user roots
        // that contain the desired type somewhere in their file hierarchy.
        row.add(Root.COLUMN_MIME_TYPES, "*/*")
        row.add(Root.COLUMN_AVAILABLE_BYTES, freespace() )
        row.add(Root.COLUMN_ICON, R.drawable.ic_launcher)

        return result
    }
    // END_INCLUDE(query_roots)

    // BEGIN_INCLUDE(query_recent_documents)
    @Throws(FileNotFoundException::class)
    override fun queryRecentDocuments(rootId: String, projection: Array<String>): Cursor {
        Log.v(TAG, "queryRecentDocuments")

        // This example implementation walks a local file structure to find the most recently
        // modified files.  Other implementations might include making a network call to query a
        // server.

        // Create a cursor with the requested projection, or the default projection.
        val result = MatrixCursor(resolveDocumentProjection(projection))

        synchronized(lock){
            val client = DropboxClientFactory.client(token)
            try {
                val parent = client.files().getMetadata(rootId)

                // Create a queue to store the most recent documents, which orders by last modified.
                val lastModifiedFiles = PriorityQueue(5, Comparator<com.dropbox.core.v2.files.FileMetadata> { i, j -> i.serverModified.compareTo(j.serverModified) })

                // Iterate through all files and directories in the file structure under the root.  If
                // the file is more recent than the least recently modified, add it to the queue,
                // limiting the number of results.
                val pending = LinkedList<com.dropbox.core.v2.files.Metadata>()

                // Start by adding the parent to the list of files to be processed
                pending.add(parent)

                // Do while we still have unexamined files
                while (!pending.isEmpty()) {
                    // Take a file from the list of unprocessed files
                    val metadata = pending.removeFirst()
                    if (metadata is FolderMetadata) {
                        // If it's a directory, add all its children to the unprocessed list
                        client.files().listFolder(metadata.pathDisplay).entries.forEach {
                            pending.add(it)
                        }
                    } else if (metadata is FileMetadata) {
                        // If it's a file, add it to the ordered queue.
                        lastModifiedFiles.add(metadata)
                    }
                }

                // Add the most recent files to the cursor, not exceeding the max number of results.
                val size = Math.min(MAX_LAST_MODIFIED + 1, lastModifiedFiles.size)
                for (i in 0 until size) {
                    val file = lastModifiedFiles.remove()
                    includeFile(result, file)
                }

            } catch (e: DbxException) {
                throw e
            }
        }
        return result
    }
    // END_INCLUDE(query_recent_documents)

    // BEGIN_INCLUDE(query_search_documents)
    @Throws(FileNotFoundException::class)
    override fun querySearchDocuments(rootId: String, query: String, projection: Array<String>): Cursor {
        Log.v(TAG, "querySearchDocuments")

        // Create a cursor with the requested projection, or the default projection.
        val result = MatrixCursor(resolveDocumentProjection(projection))

        synchronized(lock){
            val client = DropboxClientFactory.client(token)
            try {
                val parent = client.files().getMetadata(rootId)

                // This example implementation searches file names for the query and doesn't rank search
                // results, so we can stop as soon as we find a sufficient number of matches.  Other
                // implementations might use other data about files, rather than the file name, to
                // produce a match; it might also require a network call to query a remote server.

                // Iterate through all files in the file structure under the root until we reach the
                // desired number of matches.
                val pending = LinkedList<com.dropbox.core.v2.files.Metadata>()

                // Start by adding the parent to the list of files to be processed
                pending.add(parent)

                // Do while we still have unexamined files, and fewer than the max search results
                while (!pending.isEmpty() && result.count < MAX_SEARCH_RESULTS) {
                    // Take a file from the list of unprocessed files
                    val metadata = pending.removeFirst()
                    if (metadata is FolderMetadata) {
                        // If it's a directory, add all its children to the unprocessed list
                        client.files().listFolder(metadata.pathDisplay).entries.forEach {
                            pending.add(it)
                        }
                    } else if ( metadata is FileMetadata){
                        // If it's a file and it matches, add it to the result cursor.
                        if (metadata.name.toLowerCase().contains(query)) {
                            includeFile(result, metadata)
                            lastMetadata[metadata.pathDisplay] = metadata
                        }
                    }
                }
            } catch (e: DbxException) {
                throw e
            }
        }
        return result
    }
    // END_INCLUDE(query_search_documents)

    // BEGIN_INCLUDE(open_document_thumbnail)
    @Throws(FileNotFoundException::class)
    override fun openDocumentThumbnail(documentId: String, sizeHint: Point,
                                       signal: CancellationSignal): AssetFileDescriptor {
        Log.v(TAG, "openDocumentThumbnail")

        synchronized(lock){
            val client = DropboxClientFactory.client(token)
            try {
                val file = File(context.cacheDir, UUID.randomUUID().toString())
                val output = file.outputStream()
                val thumbnail = client.files().getThumbnail(documentId)
                thumbnail.inputStream.copyTo(output)

                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            } catch (e: DbxException) {
                throw e
            }
        }
    }
    // END_INCLUDE(open_document_thumbnail)

    // BEGIN_INCLUDE(query_document)
    @Throws(FileNotFoundException::class)
    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        Log.v(TAG, "queryDocument")

        // Create a cursor with the requested projection, or the default projection.
        val result = MatrixCursor(resolveDocumentProjection(projection))
        lastMetadata[documentId]?.let{
            includeFile(result, it)
        }?:run{
            val latch = CountDownLatch(1)
            Thread {
                includeFile(result, documentId)?.let{
                    lastMetadata[it.pathDisplay] = it
                }
                latch.countDown()
            }.start()
            latch.await()
        }
        return result
    }
    // END_INCLUDE(query_document)

    // BEGIN_INCLUDE(query_child_documents)
    @Throws(FileNotFoundException::class)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?,
                                     sortOrder: String): Cursor {
        Log.v(TAG, "queryChildDocuments, parentDocumentId: " +
                parentDocumentId +
                " sortOrder: " +
                sortOrder)

        val result = MatrixCursor(resolveDocumentProjection(projection))

        synchronized(lock){
            val client = DropboxClientFactory.client(token)
            try {
                val _parentDocumentId = if ( parentDocumentId == ROOT_DIRECTORY ) "" else parentDocumentId
                val children = client.files().listFolder(_parentDocumentId).entries
                children.forEach {
                    includeFile(result, it)
                    lastMetadata[it.pathDisplay] = it
                }
            } catch (e: DbxException) {
                throw e
            }
        }

        return result
    }
    // END_INCLUDE(query_child_documents)


    // BEGIN_INCLUDE(open_document)
    @Throws(FileNotFoundException::class)
    override fun openDocument(documentId: String, mode: String,
                              signal: CancellationSignal?): ParcelFileDescriptor {
        Log.v(TAG, "openDocument, mode: $mode")
        // It's OK to do network operations in this method to download the document, as long as you
        // periodically check the CancellationSignal.  If you have an extremely large file to
        // transfer from the network, a better solution may be pipes or sockets
        // (see ParcelFileDescriptor for helper methods).

        val file = File(context.cacheDir, UUID.randomUUID().toString())

        synchronized(lock){
            val client = DropboxClientFactory.client(token)
            try {
                val output = file.outputStream()
                val download = client.files().download(documentId)
                download.inputStream.copyTo(output)
            } catch (e: DbxException) {
                throw e
            }
        }

        val accessMode = ParcelFileDescriptor.parseMode(mode)

        val isWrite = mode.indexOf('w') != -1
        return if (isWrite) {
            // Attach a close listener if the document is opened in write mode.
            try {
                val handler = Handler(context.mainLooper)
                ParcelFileDescriptor.open(file, accessMode, handler) {
                    // Update the file with the cloud server.  The client is done writing.
//                    Log.i(TAG, "A file with id " + documentId + " has been closed!  Time to " +
//                            "update the server.")
//                    Log.i(TAG, file.name)

                    synchronized(lock){
                        val client = DropboxClientFactory.client(token)
                        try {
                            val localSize = file.length()
                            do{
                                client.files().uploadBuilder(documentId)
                                        .withMode(WriteMode.OVERWRITE)
                                        .uploadAndFinish(file.inputStream())

                                val metadata = client.files().getMetadata(documentId)
                                val serverSize = if ( metadata is FileMetadata ){
                                    metadata.size
                                }else{
                                    throw Exception("Illegal Folder on uploading file")
                                }
                            }while(localSize != serverSize)
                        } catch (e: DbxException) {
                            throw e
                        }
                    }

                }
            } catch (e: IOException) {
                throw FileNotFoundException("Failed to open document with id " + documentId +
                        " and mode " + mode)
            }

        } else {
            ParcelFileDescriptor.open(file, accessMode)
        }
    }
    // END_INCLUDE(open_document)

    // BEGIN_INCLUDE(create_document)
    @Throws(FileNotFoundException::class)
    override fun createDocument(documentId: String, mimeType: String, displayName: String): String {
//        Log.v(TAG, "createDocument")

        synchronized(lock){
            val client = DropboxClientFactory.client(token)
            try {
                val emptyInputStream : InputStream = object : InputStream() {
                    override fun read(): Int {
                        return -1  // end of stream
                    }
                }
                val newPath = if (documentId == "/") {
                    "/$displayName"
                } else {
                    "$documentId/$displayName"
                }
                client.files().uploadBuilder(newPath)
                        .withMode(WriteMode.OVERWRITE)
                        .uploadAndFinish(emptyInputStream)
                return newPath
            } catch (e: DbxException) {
                throw e
            }
        }
    }
    // END_INCLUDE(create_document)

    // BEGIN_INCLUDE(delete_document)
    @Throws(FileNotFoundException::class)
    override fun deleteDocument(documentId: String) {
        Log.v(TAG, "deleteDocument")
        synchronized(lock){
            val client = DropboxClientFactory.client(token)
            try {
                client.files().deleteV2(documentId)
            } catch (e: DbxException) {
                throw e
            }
        }
    }
    // END_INCLUDE(delete_document)


    /**
     * Add a representation of a file to a cursor.
     *
     * @param result the cursor to modify
     * @param path  the document ID representing the desired file (may be null if given file)
     * @throws java.io.FileNotFoundException
     */
    @Throws(FileNotFoundException::class)
    private fun includeFile(result: MatrixCursor, path: String) : Metadata? {
        val client = DropboxClientFactory.client(token)
        try {
            if ( path == ROOT_DIRECTORY ){
                var flags = 0
                flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
                val displayName = "root"

                val row = result.newRow()
                row.add(Document.COLUMN_DOCUMENT_ID, path)
                row.add(Document.COLUMN_DISPLAY_NAME, displayName)
                row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                row.add(Document.COLUMN_FLAGS, flags)

                // Add a custom icon
                row.add(Document.COLUMN_ICON, R.drawable.ic_launcher)

                return null
            }else{
//                Log.d("====>","path=$path")
                val metadata = client.files().getMetadata(path)
                var flags = 0
                if (metadata is FolderMetadata) {
                    // Request the folder to lay out as a grid rather than a list. This also allows a larger
                    // thumbnail to be displayed for each image.
                    //            flags |= Document.FLAG_DIR_PREFERS_GRID;

                    // Add FLAG_DIR_SUPPORTS_CREATE if the file is a writable directory.
                    if (metadata.sharingInfo?.readOnly != true) {
                        flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
                    }
                } else if (metadata is FileMetadata) {
                    if ( metadata.sharingInfo?.readOnly != true ) {
                        // If the file is writable set FLAG_SUPPORTS_WRITE and
                        // FLAG_SUPPORTS_DELETE
                        flags = flags or Document.FLAG_SUPPORTS_WRITE
                        flags = flags or Document.FLAG_SUPPORTS_DELETE
                    }
                }

                val displayName = metadata.name

                val mimeType = if (metadata is FolderMetadata) {
                    Document.MIME_TYPE_DIR
                }else {
                    getTypeForName(displayName)
                }

                if (mimeType.startsWith("image/")) {
                    // Allow the image to be represented by a thumbnail rather than an icon
                    flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
                }

                val row = result.newRow()
                row.add(Document.COLUMN_DOCUMENT_ID, path)
                row.add(Document.COLUMN_DISPLAY_NAME, displayName)
                if (metadata is FileMetadata){
                    row.add(Document.COLUMN_SIZE, metadata.size)
                }
                row.add(Document.COLUMN_MIME_TYPE, mimeType)
                if (metadata is FileMetadata) {
                    row.add(Document.COLUMN_LAST_MODIFIED, metadata.serverModified.time)
                }
                row.add(Document.COLUMN_FLAGS, flags)

                // Add a custom icon
                row.add(Document.COLUMN_ICON, R.drawable.ic_launcher)

                return metadata
            }
        } catch (e: DbxException) {
            //throw e
        }
        return null
    }

    /**
     * Add a representation of a file to a cursor.
     *
     * @param result the cursor to modify
     * @param metadata the document ID representing the desired file (may be null if given file)
     * @throws java.io.FileNotFoundException
     */
    @Throws(FileNotFoundException::class)
    private fun includeFile(result: MatrixCursor, metadata: Metadata) {
        var flags = 0
        if (metadata is FolderMetadata) {
            // Request the folder to lay out as a grid rather than a list. This also allows a larger
            // thumbnail to be displayed for each image.
            //            flags |= Document.FLAG_DIR_PREFERS_GRID;

            // Add FLAG_DIR_SUPPORTS_CREATE if the file is a writable directory.
            if (metadata.sharingInfo?.readOnly != true) {
                flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
            }
        } else if (metadata is FileMetadata) {
            if ( metadata.sharingInfo?.readOnly != true ) {
                // If the file is writable set FLAG_SUPPORTS_WRITE and
                // FLAG_SUPPORTS_DELETE
                flags = flags or Document.FLAG_SUPPORTS_WRITE
                flags = flags or Document.FLAG_SUPPORTS_DELETE
            }
        }

        val displayName = metadata.name

        val mimeType = if (metadata is FolderMetadata) {
            Document.MIME_TYPE_DIR
        }else {
            getTypeForName(displayName)
        }

        if (mimeType.startsWith("image/")) {
            // Allow the image to be represented by a thumbnail rather than an icon
            flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }

        val row = result.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, metadata.pathDisplay)
        row.add(Document.COLUMN_DISPLAY_NAME, displayName)
        if (metadata is FileMetadata){
            row.add(Document.COLUMN_SIZE, metadata.size)
        }
        row.add(Document.COLUMN_MIME_TYPE, mimeType)
        if (metadata is FileMetadata) {
            row.add(Document.COLUMN_LAST_MODIFIED, metadata.serverModified.time)
        }
        row.add(Document.COLUMN_FLAGS, flags)

        // Add a custom icon
        row.add(Document.COLUMN_ICON, R.drawable.ic_launcher)

    }


    private fun freespace(): Long{
        synchronized(lock){
            val client = DropboxClientFactory.client(token)
            try {
                val usage = client.users().spaceUsage
                return usage.allocation.individualValue.allocated - usage.used
            } catch (e: DbxException) {
                throw e
            }
        }
    }

    private fun email(): String{
        synchronized(lock){
            val client = DropboxClientFactory.client(token)
            try {
                return client.users().currentAccount.email
            } catch (e: DbxException) {
                throw e
            }
        }
    }

    companion object {
        private const val TAG = "SAF4Dropbox"
        private const val ROOT_DIRECTORY = "/"

        // Use these as the default columns to return information about a root if no specific
        // columns are requested in a query.
        private val DEFAULT_ROOT_PROJECTION = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_MIME_TYPES, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES)

        // Use these as the default columns to return information about a document if no specific
        // columns are requested in a query.
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE)

        // No official policy on how many to return, but make sure you do limit the number of recent
        // and search results.
        private const val MAX_SEARCH_RESULTS = 20
        private const val MAX_LAST_MODIFIED = 5

        private const val ROOT = "root"

        val lock = Unit

        /**
         * @param projection the requested root column projection
         * @return either the requested root column projection, or the default projection if the
         * requested projection is null.
         */
        private fun resolveRootProjection(projection: Array<String>?): Array<String> {
            return projection ?: DEFAULT_ROOT_PROJECTION
        }

        private fun resolveDocumentProjection(projection: Array<String>?): Array<String> {
            return projection ?: DEFAULT_DOCUMENT_PROJECTION
        }

        /**
         * Get the MIME data type of a document, given its filename.
         *
         * @param name the filename of the document
         * @return the MIME data type of a document
         */
        private fun getTypeForName(name: String): String {
            val lastDot = name.lastIndexOf('.')
            if (lastDot >= 0) {
                val extension = name.substring(lastDot + 1)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                if (mime != null) {
                    return mime
                }
            }
            return "application/octet-stream"
        }
    }

}
