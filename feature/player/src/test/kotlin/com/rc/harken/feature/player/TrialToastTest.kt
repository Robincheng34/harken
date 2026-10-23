// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 试听提示判定的 JVM 单测（P1-3）。
//
// 判定逻辑住在 :core:player 的 `shouldShowOneShotToast`——因为订阅它的
// `MusicController` 持有 Context 与 Media3 `MediaController`，JVM 里造不出来；
// 抽成纯函数后这条「一首只弹一次」的规则才能脱离 Android 验证。测试落在本
// 模块（:feature:player），因为提示的消费方是播放 UI：一旦规则回归，第一个
// 出问题的就是这里。
//
// 逐条对齐 iOS `PlayerService.swift:1093-1100`：
//   - 试听且换曲 → 弹；
//   - 同一首保持试听 → 不重复弹；
//   - 切到非试听 → 清空记忆；
//   - 再切回同一首试听 → 重新弹（记忆已被清空）；
//   - 无曲目 → 不弹。
//
// iOS 的 `:1094`（已使用第三方音源）与 `:1097`（试听）是两个独立 `if`、吃同一套
// 状态机，因此谓词是**按条件通用**的，由调用方各持一份记忆。末尾几例覆盖这一点。

package com.rc.harken.feature.player

import com.rc.harken.player.shouldShowOneShotToast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrialToastTest {

    /** 首次遇到试听曲目：应弹，并把记忆切到该曲目。 */
    @Test
    fun `trial track without previous memory shows toast`() {
        val (lastID, shouldShow) = shouldShowOneShotToast(
            lastTrackID = null,
            trackID = 100L,
            isActive = true,
        )

        assertTrue(shouldShow)
        assertEquals(100L, lastID)
    }

    /** 同一首试听曲目重复进入：保持记忆、不再弹（iOS 一首只提示一次）。 */
    @Test
    fun `same trial track does not repeat the toast`() {
        val (lastID, shouldShow) = shouldShowOneShotToast(
            lastTrackID = 100L,
            trackID = 100L,
            isActive = true,
        )

        assertFalse(shouldShow)
        assertEquals(100L, lastID)
    }

    /** 换到另一首试听曲目：应弹，并把记忆迁移到新曲目。 */
    @Test
    fun `switching to another trial track shows toast again`() {
        val (lastID, shouldShow) = shouldShowOneShotToast(
            lastTrackID = 100L,
            trackID = 200L,
            isActive = true,
        )

        assertTrue(shouldShow)
        assertEquals(200L, lastID)
    }

    /** 切到非试听曲目：清空记忆、不弹。 */
    @Test
    fun `non trial track clears the memory and stays silent`() {
        val (lastID, shouldShow) = shouldShowOneShotToast(
            lastTrackID = 100L,
            trackID = 200L,
            isActive = false,
        )

        assertFalse(shouldShow)
        assertNull(lastID)
    }

    /** 非试听 → 试听 → 非试听 → 再回到同一首试听：记忆已清空，应重新弹。 */
    @Test
    fun `memory reset allows the same trial track to toast again later`() {
        var lastID: Long? = 100L

        lastID = shouldShowOneShotToast(lastID, trackID = 200L, isActive = false).first
        assertNull(lastID)

        val (nextID, shouldShow) = shouldShowOneShotToast(lastID, trackID = 100L, isActive = true)
        assertTrue(shouldShow)
        assertEquals(100L, nextID)
    }

    /** 试听标记为真但拿不到曲目 ID（状态尚未发布）：保持记忆、不弹。 */
    @Test
    fun `trial flag without a track id stays silent`() {
        val (lastID, shouldShow) = shouldShowOneShotToast(
            lastTrackID = null,
            trackID = null,
            isActive = true,
        )

        assertFalse(shouldShow)
        assertNull(lastID)
    }

    /** 连播多首试听：每首都弹一次，互不影响。 */
    @Test
    fun `consecutive trial tracks each toast exactly once`() {
        var lastID: Long? = null
        val shown = mutableListOf<Long>()

        for (trackID in listOf(1L, 1L, 2L, 3L, 3L)) {
            val (nextID, shouldShow) = shouldShowOneShotToast(lastID, trackID, isActive = true)
            lastID = nextID
            if (shouldShow) shown += trackID
        }

        assertEquals(listOf(1L, 2L, 3L), shown)
        assertEquals(3L, lastID)
    }

    // MARK: - 解锁提示复用同一套状态机（iOS :1094-1095）

    /** 解锁提示吃同一套规则：命中且换曲 → 弹。 */
    @Test
    fun `unblock flag uses the same one shot rule`() {
        val (lastID, shouldShow) = shouldShowOneShotToast(
            lastTrackID = null,
            trackID = 300L,
            isActive = true, // unblockSource != null
        )

        assertTrue(shouldShow)
        assertEquals(300L, lastID)
    }

    /** 走官方源（unblockSource == null）→ 清空记忆、不弹。 */
    @Test
    fun `official source clears the unblock memory`() {
        val (lastID, shouldShow) = shouldShowOneShotToast(
            lastTrackID = 300L,
            trackID = 301L,
            isActive = false,
        )

        assertFalse(shouldShow)
        assertNull(lastID)
    }

    /**
     * 试听与解锁**各持一份记忆**，互不干扰：同一首上先弹解锁、再弹试听，
     * 两条都要能弹出（iOS `:1094` / `:1097` 是两个独立 `if`，不是 else-if）。
     */
    @Test
    fun `trial and unblock memories are independent so both can fire on one track`() {
        var lastTrialID: Long? = null
        var lastUnblockID: Long? = null

        // 同一首曲目同时命中解锁与试听（预取/预载路径下可能共存）。
        val trackID = 400L
        val (nextUnblockID, showUnblock) =
            shouldShowOneShotToast(lastUnblockID, trackID, isActive = true)
        lastUnblockID = nextUnblockID
        val (nextTrialID, showTrial) =
            shouldShowOneShotToast(lastTrialID, trackID, isActive = true)
        lastTrialID = nextTrialID

        assertTrue("解锁提示应弹出", showUnblock)
        assertTrue("试听提示也应弹出，不能被解锁的 if 挡掉", showTrial)
        assertEquals(400L, lastUnblockID)
        assertEquals(400L, lastTrialID)

        // 同一首再次推送：两条都因记忆而不重复弹。
        assertFalse(shouldShowOneShotToast(lastUnblockID, trackID, isActive = true).second)
        assertFalse(shouldShowOneShotToast(lastTrialID, trackID, isActive = true).second)
    }
}
