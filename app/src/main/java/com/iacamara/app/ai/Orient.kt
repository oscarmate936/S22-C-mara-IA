package com.iacamara.app.ai

import android.graphics.PointF
import android.graphics.RectF

/**
 * Geometría de orientación. La IA analiza la imagen girada según cómo sostienes el teléfono
 * (así reconoce caras aunque lo tengas en horizontal), y aquí se devuelven los resultados al
 * marco vertical de la pantalla.
 */
object Orient {
    /**
     * Convierte un punto normalizado (0..1) de un marco girado [delta] grados en sentido horario
     * respecto al vertical, al marco vertical.
     */
    fun toPortrait(u: Float, v: Float, delta: Int): Pair<Float, Float> = when (((delta % 360) + 360) % 360) {
        90 -> v to (1f - u)
        180 -> (1f - u) to (1f - v)
        270 -> (1f - v) to u
        else -> u to v
    }

    /** Inversa de [toPortrait]: del marco vertical al marco girado [delta] grados. */
    fun fromPortrait(u: Float, v: Float, delta: Int): Pair<Float, Float> = when (((delta % 360) + 360) % 360) {
        90 -> (1f - v) to u
        180 -> (1f - u) to (1f - v)
        270 -> v to (1f - u)
        else -> u to v
    }

    /** Inversa de [vectorToPortrait] (sin espejo). */
    fun vectorFromPortrait(dx: Float, dy: Float, delta: Int): PointF = when (((delta % 360) + 360) % 360) {
        90 -> PointF(-dy, dx)
        180 -> PointF(-dx, -dy)
        270 -> PointF(dy, -dx)
        else -> PointF(dx, dy)
    }

    fun rectToPortrait(l: Float, t: Float, r: Float, b: Float, delta: Int, mirror: Boolean): RectF {
        val a = toPortrait(l, t, delta)
        val c = toPortrait(r, b, delta)
        var left = minOf(a.first, c.first)
        var right = maxOf(a.first, c.first)
        if (mirror) { val nl = 1f - right; right = 1f - left; left = nl }
        return RectF(
            left.coerceIn(0f, 1f), minOf(a.second, c.second).coerceIn(0f, 1f),
            right.coerceIn(0f, 1f), maxOf(a.second, c.second).coerceIn(0f, 1f),
        )
    }

    /** Convierte una dirección (vector) del marco girado al vertical. */
    fun vectorToPortrait(dx: Float, dy: Float, delta: Int, mirror: Boolean): PointF {
        val (x, y) = when (((delta % 360) + 360) % 360) {
            90 -> dy to -dx
            180 -> -dx to -dy
            270 -> -dy to dx
            else -> dx to dy
        }
        return PointF(if (mirror) -x else x, y)
    }

    fun pointToPortrait(u: Float, v: Float, delta: Int, mirror: Boolean): PointF {
        val (x, y) = toPortrait(u, v, delta)
        return PointF(if (mirror) 1f - x else x, y)
    }
}
