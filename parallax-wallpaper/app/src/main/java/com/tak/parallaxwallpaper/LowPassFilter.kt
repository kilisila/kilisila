package com.tak.parallaxwallpaper

import kotlin.math.exp

/**
 * 一次のローパスフィルター（指数移動平均）。
 *
 * 平滑化の係数を経過時間から求めるため、描画間隔やセンサーの通知間隔が揺らいでも
 * 追従の速さが変わらない。
 *
 * @param timeConstantSec 時定数（秒）。大きいほど滑らかになり、追従は遅くなる。
 */
class LowPassFilter(private val timeConstantSec: Float) {

    var value: Float = 0f
        private set

    private var initialized = false

    /** 次の入力をそのまま初期値として採用する状態に戻す。 */
    fun reset() {
        initialized = false
        value = 0f
    }

    /** 値を直接設定する。 */
    fun reset(initial: Float) {
        value = initial
        initialized = true
    }

    /**
     * 新しい入力を与えて、平滑化した値を返す。
     *
     * @param target 入力値
     * @param dtSec 前回の更新からの経過時間（秒）
     */
    fun update(target: Float, dtSec: Float): Float {
        if (!initialized) {
            reset(target)
            return value
        }
        if (dtSec <= 0f || timeConstantSec <= 0f) {
            if (timeConstantSec <= 0f) value = target
            return value
        }
        val alpha = 1f - exp(-dtSec / timeConstantSec)
        value += alpha * (target - value)
        return value
    }
}
