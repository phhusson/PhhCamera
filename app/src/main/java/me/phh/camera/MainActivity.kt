package me.phh.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.service.autofill.TextValueSanitizer
import android.util.Log
import android.widget.TextView
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    val handler = Handler(HandlerThread("Camera").also { it.start()}.looper)
    val executor = Executor { p0 -> handler.post(p0) }
    val firstPicture = AtomicLong()
    val lastPicture = AtomicLong()
    var startTs = ""
    fun doStuff(cam: CameraDevice, mode: Int, w: Int, h: Int) {
        /*
        val mClassLoader = PathClassLoader("/system/framework/scamera_sdk_util.jar", classLoader)
        val c = Class.forName("com.samsung.android.sdk.camera.impl.internal.CustomInterfaceHelper", true, mClassLoader)
        c.getMethod("setSamsungParameter", CameraDevice::class.java, String::class.java).invoke(null, cam, "samsungcamera=true")
         */

        startTs = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(LocalDateTime.now())
        Log.d("PHH", "startTs = $startTs")

        //val imageReader = ImageReader.newInstance(w, h, ImageFormat.RAW_SENSOR, 32)
        val imageReader = ImageReader.newInstance(w, h, ImageFormat.RAW_SENSOR, 32)
        val imReaderSurface = imageReader.surface
        val i = AtomicInteger()
        val sessionConfiguration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(imReaderSurface)),
            executor,
            object: CameraCaptureSession.StateCallback() {
                override fun onConfigured(p0: CameraCaptureSession) {
                    Log.d("PHH", "Configured camera capture session")
                    val captureRequest = cam.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG)
                    captureRequest.addTarget(imReaderSurface)

                    val captureCallback =  object:
                        CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureStarted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            timestamp: Long,
                            frameNumber: Long
                        ) {
                            Log.d("PHH", "onCaptureStarted")
                        }

                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            Log.d("PHH", "onCaptureCompleted ${result.frameNumber} ${result.sequenceId}")

                            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                            if(i.get() == 0 && timestamp != null) {
                                firstPicture.set(timestamp)
                            }

                            val img = imageReader.acquireNextImage() ?: return
                            Log.d("PHH", "Got image ${img.width} ${img.height}")
                            Log.d("PHH", "Got nPlanes ${img.planes.size}")

                            val b = img.planes[0].buffer
                            val bb = ByteArray(b.capacity())
                            b.get(bb)
                            val base = "hello-${startTs}-${i.getAndAdd(1)}"
                            FileOutputStream(File(externalCacheDirs[0], "${base}.raw")).use {
                                it.write(bb)
                            }
                            FileOutputStream(File(externalCacheDirs[0], "${base}.keys")).bufferedWriter().use {
                                for(key in result.keys) {
                                    // very big all-0 array
                                    if(key.name == "samsung.android.jpeg.imageDebugInfoApp4") continue
                                    val v = result.get(key)
                                    val v2 = when (v) {
                                        is ByteArray -> v.toList()
                                        is IntArray -> v.toList()
                                        is LongArray -> v.toList()
                                        is FloatArray -> v.toList()
                                        is DoubleArray -> v.toList()
                                        is BooleanArray -> v.toList()
                                        is Array<*> -> v.toList()
                                        else -> v
                                    }
                                    it.write("- ${key.name} = $v2\n")
                                }
                            }
                            if(result.frameNumber == 27L && timestamp != null) {
                                lastPicture.set(timestamp)
                            }
                            runOnUiThread {
                                val t = findViewById<TextView>(R.id.hello)
                                t.text = "${result.frameNumber} / 27"
                            }
                        }
                    }

                    p0.captureBurst((0 until 28).map { i ->
                        // On S22 that's -3EV, (-2, -1, 0, 1, 2, 3) x 4
                        // TODO: Read CONTROL_AE_EXPOSURE_COMPENSATION
                        captureRequest.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ((i%7) - 3) * 10)
                        captureRequest.build()
                    }.toList(), captureCallback, handler)
                }

                override fun onConfigureFailed(p0: CameraCaptureSession) {
                    Log.d("PHH", "Configure failed $p0")
                }
            }
        )
        cam.createCaptureSession(sessionConfiguration)
    }

    fun cameraStuff() {
        val camMgr = getSystemService(CameraManager::class.java)

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("PHH", "Camera permission not granted")
            return
        }

        var maxW = 0
        var maxH = 0
        var maxMode = 0
        var maxCamera = ""
        for(camera in camMgr.cameraIdList) {
            val chars = camMgr.getCameraCharacteristics(camera)

            Log.d("PHH", "For camera $camera, keys")
            //val key = CameraCharacteristics.Key("samsung.android.scaler.availablePictureStreamConfigurations", Array<Int>::class.java)
            val key = CameraCharacteristics.Key("android.scaler.availableStreamConfigurations", Array<Int>::class.java)
            val streams = chars[key] ?: continue

            Log.d("PHH", " - ${streams.joinToString(", ")}")
            val configs = (0 until streams.size/4).map {
                listOf(streams[it*4], streams[it*4+1], streams[it*4+2], streams[it*4+3])
            }.toList()
            for(conf in configs) {
                val mode = conf[0]
                val w = conf[1]
                val h = conf[2]
                val out = conf[3]
                if(out != 0) continue
                if(w > maxW) {
                    maxW = w
                    maxMode = mode
                    maxCamera = camera
                }
                if(h > maxH) {
                    maxH = h
                    maxMode = mode
                    maxCamera = camera
                }
            }
        }
        maxCamera = "0"

        Log.d("PHH", "Will take mode ${maxW}x${maxH} $maxMode at camera $maxCamera")

        val camera = camMgr.openCamera(maxCamera, object: CameraDevice.StateCallback() {
            override fun onOpened(p0: CameraDevice) {
                Log.d("PHH", "Camera $p0 opened")
                doStuff(p0, maxMode, maxW, maxH)
            }

            override fun onDisconnected(p0: CameraDevice) {
                Log.d("PHH", "Camera $p0 closed")
            }

            override fun onError(p0: CameraDevice, p1: Int) {
                Log.d("PHH", "Camera $p0 errored $p1")
            }
        }, handler)
    }

    data class SensorValues(val x: Float, val y: Float, val z: Float)
    data class Event(val timestamp: Long, val accel: SensorValues, val gyro: SensorValues)
    val myQueue = ConcurrentLinkedQueue<Event>()
    fun sensorStuff() {
        val sensorHandler = Handler(HandlerThread("Sensors").also { it.start()}.looper)
        val sm = getSystemService(SensorManager::class.java)
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val lastGyro = AtomicReference<SensorValues>()
        val lastAccel = AtomicReference<SensorValues>()

        val gyroListener = object: SensorEventListener {
            override fun onSensorChanged(p0: SensorEvent) {
                val v = SensorValues(p0.values[0], p0.values[1], p0.values[2])
                lastGyro.set(v)
                val acc = lastAccel.get()
                if(acc != null) {
                    myQueue.add(Event(p0.timestamp, acc, v))
                }
            }

            override fun onAccuracyChanged(p0: Sensor, p1: Int) {
                Log.d("PHH", "Got gyro accuracy $p0 $p1")
            }
        }
        sm.registerListener(gyroListener, gyro, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)

        val accelListener = object: SensorEventListener {
            override fun onSensorChanged(p0: SensorEvent) {
                val v = SensorValues(p0.values[0], p0.values[1], p0.values[2])
                lastAccel.set(v)
                val gyro = lastGyro.get()
                if(gyro != null) {
                    myQueue.add(Event(p0.timestamp, v, gyro))
                }
            }

            override fun onAccuracyChanged(p0: Sensor, p1: Int) {
                Log.d("PHH", "Got accel accuracy $p0 $p1")
            }
        }
        sm.registerListener(accelListener, accel, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)

        while(true) {
            Log.d("PHH", "Waiting for first frame timestamp")
            Thread.sleep(10)
            if(firstPicture.get() > 0) break
        }
        Log.d("PHH", "Got first frame timestamp")

        FileOutputStream(File(externalCacheDirs[0], "hello-${startTs}.sensors")).bufferedWriter().use {
            val firstTs = firstPicture.get()
            val l = Locale.US

            while(true) {
                val lastTs = lastPicture.get()
                val head = myQueue.poll()
                if(head == null) {
                    Thread.sleep(10)
                    continue
                }
                Log.d("PHH", "Got event $head")
                // Ignore all events that date from before first picture minus 16ms
                if(head.timestamp < firstTs - 16L*1000L*1000L) continue
                // Stop writing sensor logs 16ms after last picture
                if(lastTs != 0L && head.timestamp > lastTs + 16L*1000L*1000L) break
                val s = String.format(l, "%d,%f,%f,%f,%f,%f,%f\n",
                    head.timestamp - firstTs,
                    head.accel.x, head.accel.y, head.accel.z,
                    head.gyro.x, head.gyro.y, head.gyro.z
                )
                it.write(s)
            }
            sm.unregisterListener(accelListener)
            sm.unregisterListener(gyroListener)
        }
        runOnUiThread {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        thread {
            cameraStuff()
        }

        thread {
            sensorStuff()
        }
    }
}