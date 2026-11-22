package com.kids.monitor.data

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** التقاط صورة من الكاميرا الخلفية  via Camera2. */
object CameraCapture {

    fun capture(
        context: Context,
        facing: Int = CameraCharacteristics.LENS_FACING_BACK,
        timeoutMs: Long = 10000,
    ): File? {
        if (!Permissions.has(context, android.Manifest.permission.CAMERA)) return null

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: return null

        val handler = Handler(Looper.getMainLooper())
        val reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)
        val latch = CountDownLatch(1)
        var failure: Exception? = null
        var device: CameraDevice? = null

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    try {
                        camera.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        val request = session.device
                                            .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                            .apply { addTarget(reader.surface) }
                                            .build()
                                        session.capture(
                                            request,
                                            object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(
                                                    s: CameraCaptureSession,
                                                    r: CaptureRequest,
                                                    result: android.hardware.camera2.TotalCaptureResult,
                                                ) {
                                                    latch.countDown()
                                                }
                                            },
                                            handler,
                                        )
                                    } catch (e: Exception) {
                                        failure = e
                                        latch.countDown()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    failure = IllegalStateException("فشل تجهيز الكاميرا")
                                    latch.countDown()
                                }
                            },
                            handler,
                        )
                    } catch (e: Exception) {
                        failure = e
                        latch.countDown()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    failure = IllegalStateException("انقطع اتصال الكاميرا")
                    latch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    failure = IllegalStateException("خطأ الكاميرا $error")
                    latch.countDown()
                }
            }, handler)
        } catch (e: Exception) {
            reader.close()
            return null
        }

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)

        var file: File? = null
        try {
            val image = reader.acquireLatestImage()
            if (image != null) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                file.writeBytes(bytes)
            }
        } catch (e: Exception) {
            failure = e
        } finally {
            try { reader.close() } catch (e: Exception) { /* تجاهل */ }
            try { device?.close() } catch (e: Exception) { /* تجاهل */ }
        }

        return if (failure == null) file else null
    }
}
