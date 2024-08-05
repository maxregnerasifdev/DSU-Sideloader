package vegabobo.dsusideloader.installer.root

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemProperties
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import vegabobo.dsusideloader.model.DSUInstallationSource
import vegabobo.dsusideloader.model.ImagePartition
import vegabobo.dsusideloader.model.Type
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.service.PrivilegedProvider

class DSUInstaller(
    private val application: Application,
    private val userdataSize: Long,
    private val dsuInstallation: DSUInstallationSource,
    private var installationJob: Job = Job(),
    private val onInstallationError: (error: InstallationStep, errorInfo: String) -> Unit,
    private val onInstallationProgressUpdate: (progress: Float, partition: String) -> Unit,
    private val onCreatePartition: (partition: String) -> Unit,
    private val onInstallationStepUpdate: (step: InstallationStep) -> Unit,
    private val onInstallationSuccess: () -> Unit,
) : () -> Unit {

    private val tag = this.javaClass.simpleName

    object Constants {
        const val DEFAULT_SLOT = "dsu"
        const val BUFFER_SIZE = 8192
    }

    private val UNSUPPORTED_PARTITIONS: List<String> = listOf(
        "vbmeta",
        "boot",
        "userdata",
        "dtbo",
        "super_empty",
        "system_other",
        "scratch",
    )

    private fun isPartitionSupported(partitionName: String): Boolean =
        !UNSUPPORTED_PARTITIONS.contains(partitionName)

    private fun publishProgress(bytesRead: Long, totalBytes: Long, partition: String) {
        var progress = 0F
        if (totalBytes != 0L && bytesRead != 0L) {
            progress = (bytesRead.toFloat() / totalBytes.toFloat())
        }
        onInstallationProgressUpdate(progress, partition)
    }

    private fun installImage(partition: String, inputStream: InputStream, size: Long) {
        onCreatePartition(partition)
        onInstallationStepUpdate(InstallationStep.INSTALLING_ROOTED)

        val file = File(application.filesDir, "${partition}.img")
        FileOutputStream(file).use { output ->
            var bytesRead: Long = 0
            val buffer = ByteArray(Constants.BUFFER_SIZE)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                bytesRead += read
                publishProgress(bytesRead, size, partition)
                if (installationJob.isCancelled) return
            }
        }

        // Use system installed GSI tool to install the image
        val result = Runtime.getRuntime().exec("su -c gsi_tool install ${file.absolutePath} $partition").waitFor()
        if (result != 0) {
            Log.e(tag, "Failed to install $partition partition")
            onInstallationError(InstallationStep.ERROR_CREATE_PARTITION, partition)
            return
        }

        file.delete()
        Log.d(tag, "Partition $partition installed, size: $size")
    }

    private fun installStreamingZipUpdate(inputStream: InputStream): Boolean {
        val zis = ZipInputStream(inputStream)
        var entry: ZipEntry?
        while (zis.nextEntry.also { entry = it } != null) {
            val fileName = entry!!.name
            if (fileName.endsWith(".img") && isPartitionSupported(fileName.substringBeforeLast("."))) {
                installImage(fileName.substringBeforeLast("."), zis, entry!!.size)
            } else {
                Log.d(tag, "$fileName installation is not supported, skip it.")
            }
            if (installationJob.isCancelled) {
                break
            }
        }
        return true
    }

    private fun startInstallation() {
        PrivilegedProvider.getService().setDynProp()
        if (SystemProperties.getBoolean("gsid.image_running", false)) {
            onInstallationError(InstallationStep.ERROR_ALREADY_RUNNING_DYN_OS, "")
            return
        }
        if (File("/data/gsi").exists()) {
            onInstallationError(InstallationStep.ERROR_REQUIRES_DISCARD_DSU, "")
            return
        }
        Runtime.getRuntime().exec("su -c am force-stop com.android.dynsystem").waitFor()

        // Create userdata partition
        val userdataResult = Runtime.getRuntime().exec("su -c gsi_tool install -s $userdataSize userdata").waitFor()
        if (userdataResult != 0) {
            onInstallationError(InstallationStep.ERROR_CREATE_PARTITION, "userdata")
            return
        }

        when (dsuInstallation.type) {
            Type.SINGLE_SYSTEM_IMAGE -> {
                installImage("system", openInputStream(dsuInstallation.uri), dsuInstallation.fileSize)
            }
            Type.MULTIPLE_IMAGES -> {
                installImages(dsuInstallation.images)
            }
            Type.DSU_PACKAGE -> {
                installStreamingZipUpdate(openInputStream(dsuInstallation.uri))
            }
            Type.URL -> {
                val url = URL(dsuInstallation.uri.toString())
                installStreamingZipUpdate(url.openStream())
            }
            else -> {}
        }
        if (!installationJob.isCancelled) {
            Runtime.getRuntime().exec("su -c gsi_tool enable").waitFor()
            Log.d(tag, "Installation finished successfully.")
            onInstallationSuccess()
        }
    }

    private fun installImages(images: List<ImagePartition>) {
        for (image in images) {
            if (isPartitionSupported(image.partitionName)) {
                installImage(image.partitionName, openInputStream(image.uri), image.fileSize)
            }
            if (installationJob.isCancelled) {
                Runtime.getRuntime().exec("su -c gsi_tool disable").waitFor()
                return
            }
        }
    }

    private fun openInputStream(uri: Uri): InputStream {
        return application.contentResolver.openInputStream(uri)!!
    }

    override fun invoke() {
        startInstallation()
    }
}package vegabobo.dsusideloader.installer.root

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemProperties
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import vegabobo.dsusideloader.model.DSUInstallationSource
import vegabobo.dsusideloader.model.ImagePartition
import vegabobo.dsusideloader.model.Type
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.service.PrivilegedProvider

class DSUInstaller(
    private val application: Application,
    private val userdataSize: Long,
    private val dsuInstallation: DSUInstallationSource,
    private var installationJob: Job = Job(),
    private val onInstallationError: (error: InstallationStep, errorInfo: String) -> Unit,
    private val onInstallationProgressUpdate: (progress: Float, partition: String) -> Unit,
    private val onCreatePartition: (partition: String) -> Unit,
    private val onInstallationStepUpdate: (step: InstallationStep) -> Unit,
    private val onInstallationSuccess: () -> Unit,
) : () -> Unit {

    private val tag = this.javaClass.simpleName

    object Constants {
        const val DEFAULT_SLOT = "dsu"
        const val BUFFER_SIZE = 8192
    }

    private val UNSUPPORTED_PARTITIONS: List<String> = listOf(
        "vbmeta",
        "boot",
        "userdata",
        "dtbo",
        "super_empty",
        "system_other",
        "scratch",
    )

    private fun isPartitionSupported(partitionName: String): Boolean =
        !UNSUPPORTED_PARTITIONS.contains(partitionName)

    private fun publishProgress(bytesRead: Long, totalBytes: Long, partition: String) {
        var progress = 0F
        if (totalBytes != 0L && bytesRead != 0L) {
            progress = (bytesRead.toFloat() / totalBytes.toFloat())
        }
        onInstallationProgressUpdate(progress, partition)
    }

    private fun installImage(partition: String, inputStream: InputStream, size: Long) {
        onCreatePartition(partition)
        onInstallationStepUpdate(InstallationStep.INSTALLING_ROOTED)

        val file = File(application.filesDir, "${partition}.img")
        FileOutputStream(file).use { output ->
            var bytesRead: Long = 0
            val buffer = ByteArray(Constants.BUFFER_SIZE)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                bytesRead += read
                publishProgress(bytesRead, size, partition)
                if (installationJob.isCancelled) return
            }
        }

        // Use system installed GSI tool to install the image
        val result = Runtime.getRuntime().exec("su -c gsi_tool install ${file.absolutePath} $partition").waitFor()
        if (result != 0) {
            Log.e(tag, "Failed to install $partition partition")
            onInstallationError(InstallationStep.ERROR_CREATE_PARTITION, partition)
            return
        }

        file.delete()
        Log.d(tag, "Partition $partition installed, size: $size")
    }

    private fun installStreamingZipUpdate(inputStream: InputStream): Boolean {
        val zis = ZipInputStream(inputStream)
        var entry: ZipEntry?
        while (zis.nextEntry.also { entry = it } != null) {
            val fileName = entry!!.name
            if (fileName.endsWith(".img") && isPartitionSupported(fileName.substringBeforeLast("."))) {
                installImage(fileName.substringBeforeLast("."), zis, entry!!.size)
            } else {
                Log.d(tag, "$fileName installation is not supported, skip it.")
            }
            if (installationJob.isCancelled) {
                break
            }
        }
        return true
    }

    private fun startInstallation() {
        PrivilegedProvider.getService().setDynProp()
        if (SystemProperties.getBoolean("gsid.image_running", false)) {
            onInstallationError(InstallationStep.ERROR_ALREADY_RUNNING_DYN_OS, "")
            return
        }
        if (File("/data/gsi").exists()) {
            onInstallationError(InstallationStep.ERROR_REQUIRES_DISCARD_DSU, "")
            return
        }
        Runtime.getRuntime().exec("su -c am force-stop com.android.dynsystem").waitFor()

        // Create userdata partition
        val userdataResult = Runtime.getRuntime().exec("su -c gsi_tool install -s $userdataSize userdata").waitFor()
        if (userdataResult != 0) {
            onInstallationError(InstallationStep.ERROR_CREATE_PARTITION, "userdata")
            return
        }

        when (dsuInstallation.type) {
            Type.SINGLE_SYSTEM_IMAGE -> {
                installImage("system", openInputStream(dsuInstallation.uri), dsuInstallation.fileSize)
            }
            Type.MULTIPLE_IMAGES -> {
                installImages(dsuInstallation.images)
            }
            Type.DSU_PACKAGE -> {
                installStreamingZipUpdate(openInputStream(dsuInstallation.uri))
            }
            Type.URL -> {
                val url = URL(dsuInstallation.uri.toString())
                installStreamingZipUpdate(url.openStream())
            }
            else -> {}
        }
        if (!installationJob.isCancelled) {
            Runtime.getRuntime().exec("su -c gsi_tool enable").waitFor()
            Log.d(tag, "Installation finished successfully.")
            onInstallationSuccess()
        }
    }

    private fun installImages(images: List<ImagePartition>) {
        for (image in images) {
            if (isPartitionSupported(image.partitionName)) {
                installImage(image.partitionName, openInputStream(image.uri), image.fileSize)
            }
            if (installationJob.isCancelled) {
                Runtime.getRuntime().exec("su -c gsi_tool disable").waitFor()
                return
            }
        }
    }

    private fun openInputStream(uri: Uri): InputStream {
        return application.contentResolver.openInputStream(uri)!!
    }

    override fun invoke() {
        startInstallation()
    }
}
