/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.view.MotionEvent

class CustomSwipeTracker {
    private val mPastX = FloatArray(NUM_PAST)
    private val mPastY = FloatArray(NUM_PAST)
    private val mPastTime = LongArray(NUM_PAST)
    var yVelocity = 0f
    var xVelocity = 0f

    fun clear() {
        mPastTime[0] = 0
    }

    fun addMovement(ev: MotionEvent) {
        for (i in 0 until ev.historySize) {
            addPoint(ev.getHistoricalX(i), ev.getHistoricalY(i), ev.getHistoricalEventTime(i))
        }
        addPoint(ev.x, ev.y, ev.eventTime)
    }

    private fun addPoint(
        x: Float,
        y: Float,
        time: Long,
    ) {
        var drop = -1
        val pastTime = mPastTime
        var i = 0
        while (i < NUM_PAST) {
            if (pastTime[i] == 0L) {
                break
            } else if (pastTime[i] < time - LONGEST_PAST_TIME) {
                drop = i
            }
            i++
        }
        if (i == NUM_PAST && drop < 0) {
            drop = 0
        }
        if (drop == i) drop--
        val pastX = mPastX
        val pastY = mPastY
        if (drop >= 0) {
            val start = drop + 1
            val count = NUM_PAST - drop - 1
            System.arraycopy(pastX, start, pastX, 0, count)
            System.arraycopy(pastY, start, pastY, 0, count)
            System.arraycopy(pastTime, start, pastTime, 0, count)
            i -= drop + 1
        }
        pastX[i] = x
        pastY[i] = y
        pastTime[i] = time
        i++
        if (i < NUM_PAST) {
            pastTime[i] = 0
        }
    }

    fun computeCurrentVelocity(units: Int) {
        val oldestX = mPastX[0]
        val oldestY = mPastY[0]
        val oldestTime = mPastTime[0]
        var accumX = 0f
        var accumY = 0f
        val n = mPastTime.indexOfFirst { it == 0L }.coerceAtMost(NUM_PAST)
        for (i in 1 until n) {
            val dur = mPastTime[i] - oldestTime
            if (dur == 0L) continue
            val distX = mPastX[i] - oldestX
            val velX = distX / dur * units // pixels/frame.
            accumX += velX
            val distY = mPastY[i] - oldestY
            val velY = distY / dur * units // pixels/frame.
            accumY += velY
        }
        xVelocity = accumX.coerceIn(Float.MIN_VALUE, Float.MAX_VALUE)
        yVelocity = accumY.coerceIn(Float.MIN_VALUE, Float.MAX_VALUE)
    }

    companion object {
        const val NUM_PAST = 4
        const val LONGEST_PAST_TIME = 200
    }
}
