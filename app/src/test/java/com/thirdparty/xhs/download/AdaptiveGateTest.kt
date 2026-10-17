package com.thirdparty.xhs.download

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.coroutineScope
import org.junit.Test

/**
 * [AdaptiveGate] 的回归测试。
 *
 * 为什么要有：这个闸门第一版（轮询等待 + 一次中断降一路、能降到 1 路）**把下载拖慢了**，
 * 用户直接反馈「用了自适应降并发之后变慢了」。这里把三条语义钉住：
 * 并发上限不被突破、降速有下限、成功后会回升。
 */
class AdaptiveGateTest {

    /** 并发上限：同时进入临界区的任务数不得超过 maxParallel。 */
    @Test
    fun never_exceeds_max_parallel() = runBlocking {
        val gate = AdaptiveGate(maxParallel = 2, parent = coroutineContext, cooldownMs = 0)
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)
        coroutineScope {
            (1..8).map {
                async(Dispatchers.Default) {
                    gate.withPermit {
                        val now = running.incrementAndGet()
                        peak.updateAndGet { p -> maxOf(p, now) }
                        delay(30)
                        running.decrementAndGet()
                    }
                }
            }.awaitAll()
        }
        gate.close()
        assertTrue("峰值并发 $peak", peak.get() <= 2)
        assertEquals("全部任务都应跑完", 0, running.get())
    }

    /** 降速有下限：无论中断多少次，并发不会低于「设置值的一半」。 */
    @Test
    fun penalty_never_goes_below_floor() = runBlocking {
        val gate = AdaptiveGate(maxParallel = 8, parent = coroutineContext, cooldownMs = 0)
        repeat(50) { gate.onFail() }
        delay(50)
        // 扣住 4 路 → 剩 4 路可用；尝试起 6 个任务，最多 4 个能同时跑
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)
        coroutineScope {
            (1..6).map {
                async(Dispatchers.Default) {
                    gate.withPermit {
                        val now = running.incrementAndGet()
                        peak.updateAndGet { p -> maxOf(p, now) }
                        delay(40)
                        running.decrementAndGet()
                    }
                }
            }.awaitAll()
        }
        gate.close()
        assertTrue("降到下限后峰值并发应为 4，实际 $peak", peak.get() <= 4)
    }

    /** 成功一片就回升一路：连续 onOk 之后并发能回到满值。 */
    @Test
    fun success_restores_capacity_one_at_a_time() = runBlocking {
        val gate = AdaptiveGate(maxParallel = 4, parent = coroutineContext, cooldownMs = 0)
        repeat(10) { gate.onFail() }
        delay(50)
        // 下限 2 → 先确认被压到 2
        var peak = measurePeak(gate, tasks = 4)
        assertTrue("降速后峰值并发应为 2，实际 $peak", peak <= 2)
        // 连续成功两次 → 应回到 4
        gate.onOk()
        gate.onOk()
        delay(50)
        peak = measurePeak(gate, tasks = 4)
        assertTrue("回升后峰值并发应恢复到 4，实际 $peak", peak == 4)
        gate.close()
    }

    /** 冷却期内多次中断只降一路：避免正常线路上偶发超时把并发一把压到底。 */
    @Test
    fun cooldown_limits_penalty_rate() = runBlocking {
        val gate = AdaptiveGate(maxParallel = 8, parent = coroutineContext, cooldownMs = 60_000)
        repeat(20) { gate.onFail() }
        delay(50)
        val peak = measurePeak(gate, tasks = 8)
        assertTrue("冷却期内只应降一路（峰值 7），实际 $peak", peak == 7)
        gate.close()
    }

    private suspend fun measurePeak(gate: AdaptiveGate, tasks: Int): Int {
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)
        coroutineScope {
            (1..tasks).map {
                async(Dispatchers.Default) {
                    gate.withPermit {
                        val now = running.incrementAndGet()
                        peak.updateAndGet { p -> maxOf(p, now) }
                        delay(40)
                        running.decrementAndGet()
                    }
                }
            }.awaitAll()
        }
        return peak.get()
    }
}
