package com.tak.parallaxwallpaper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.abs

/**
 * 回転ベクトルセンサー（TYPE_ROTATION_VECTOR）から、基準姿勢に対する傾きを求める。
 *
 * 絶対的な姿勢ではなく「登録した時点の姿勢からの変化量」を使うため、
 * 端末を立てて持っても寝かせて持っても、そのときの姿勢が中立になる。
 * 平滑化はここでは行わず、描画側の [LowPassFilter] に任せる。
 */
class TiltTracker(context: Context) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val currentMatrix = FloatArray(9)
    private val baselineMatrix = FloatArray(9)
    private val angleChange = FloatArray(3)
    private var hasBaseline = false

    /** 画面座標系での傾き（ラジアン）。x は左右、y は上下。 */
    var tiltXRad: Float = 0f
        private set
    var tiltYRad: Float = 0f
        private set

    /** 画面の向き（Surface.ROTATION_*）。横画面のときに軸を入れ替えるために使う。 */
    var displayRotation: Int = Surface.ROTATION_0

    val isAvailable: Boolean get() = sensor != null

    /** センサーの登録。成功した場合は true を返す。 */
    fun start(): Boolean {
        val manager = sensorManager ?: return false
        val target = sensor ?: return false
        hasBaseline = false
        tiltXRad = 0f
        tiltYRad = 0f
        return manager.registerListener(this, target, SensorManager.SENSOR_DELAY_GAME)
    }

    /** センサーの登録解除。 */
    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    /** 現在の姿勢を新たな中立とする。 */
    fun recenter() {
        hasBaseline = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(currentMatrix, event.values)

        if (!hasBaseline) {
            currentMatrix.copyInto(baselineMatrix)
            hasBaseline = true
        }

        // 基準姿勢からの変化量。[0] 方位角、[1] ピッチ（上下）、[2] ロール（左右）
        SensorManager.getAngleChange(angleChange, currentMatrix, baselineMatrix)
        var pitch = angleChange[1]
        var roll = angleChange[2]

        // 持ち方が大きく変わった場合は、その姿勢を新しい基準にする
        if (abs(pitch) > RESET_ANGLE_RAD || abs(roll) > RESET_ANGLE_RAD) {
            currentMatrix.copyInto(baselineMatrix)
            pitch = 0f
            roll = 0f
        }

        // 端末座標系から画面座標系へ変換する
        when (displayRotation) {
            Surface.ROTATION_90 -> {
                tiltXRad = pitch
                tiltYRad = -roll
            }
            Surface.ROTATION_180 -> {
                tiltXRad = -roll
                tiltYRad = -pitch
            }
            Surface.ROTATION_270 -> {
                tiltXRad = -pitch
                tiltYRad = roll
            }
            else -> {
                tiltXRad = roll
                tiltYRad = pitch
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** これを超えて傾いた場合は基準姿勢を取り直す（約 60 度）。 */
        private const val RESET_ANGLE_RAD = 1.05f
    }
}
