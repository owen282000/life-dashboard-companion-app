package com.owen282000.lifedashboard.ui.ld

import com.owen282000.lifedashboard.HealthDataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LDBackStackTest {

    /** Every route, with its parameters, once per shape: a new one has to be added here to be held. */
    private val everyRoute: List<LDRoute> = listOf(
        LDRoute.Home, LDRoute.ScreenTimeHome, LDRoute.LogsList,
        LDRoute.AddDestination(), LDRoute.AddDestination(LDSection.ScreenTime),
        LDRoute.WebhookDestination(), LDRoute.WebhookDestination(LDSection.ScreenTime), LDRoute.WebhookDestination(LDSection.ScreenTime, "id-1"),
        LDRoute.WebhookDestination(LDSection.Health, null, "https://example.org/hook"),
        LDRoute.MqttDestination(), LDRoute.MqttDestination(LDSection.ScreenTime), LDRoute.PairedResult,
        LDRoute.WhatToSend, LDRoute.DataResolution, LDRoute.Backfill(), LDRoute.Backfill(LDBackfillStage.Running),
        LDRoute.PayloadPreview(), LDRoute.PayloadPreview(LDSection.ScreenTime), LDRoute.WhenToSend, LDRoute.Receive,
        LDRoute.More, LDRoute.Advanced(), LDRoute.Advanced(LDSection.ScreenTime), LDRoute.ServerCertificates,
        LDRoute.NotificationSettings, LDRoute.ExportData(), LDRoute.ExportData(LDSection.ScreenTime), LDRoute.BackupRestore,
        LDRoute.LogDetail("log-1"), LDRoute.ScreenTimeApps, LDRoute.ScreenTimeDay, LDRoute.ScreenTimeWhen, LDRoute.ScreenTimeMore,
        LDRoute.AboutHome, LDRoute.AboutHelp, LDRoute.AboutSupport, LDRoute.AboutPrivacy, LDRoute.AboutOpenSource, LDRoute.AboutNerd
    ) + LDTypeCategory.entries.map { LDRoute.TypeCategory(it) }

    @Test
    fun `the whole state survives being saved, every route and parameter included`() {
        var stack = LDBackStack()
        everyRoute.forEach { stack = stack.push(it) }
        stack = stack.selectTab(LDTab.Logs).push(LDRoute.LogDetail("x")).selectTab(LDTab.ScreenTime).push(LDRoute.ScreenTimeApps)

        val restored = LDBackStack.decode(stack.encode())

        assertEquals(stack, restored)
        assertEquals(LDTab.ScreenTime, restored!!.tab)
    }

    @Test
    fun `what cannot be read gives no state, so the app starts fresh`() {
        assertNull(LDBackStack.decode("not json"))
        assertNull(LDBackStack.decode("""{"tab":"Nowhere"}"""))
    }

    @Test
    fun `a tab switch keeps every stack`() {
        var stack = LDBackStack().push(LDRoute.More).push(LDRoute.Advanced())
        stack = stack.selectTab(LDTab.Logs).push(LDRoute.LogDetail("a"))
        assertEquals(listOf(LDRoute.LogDetail("a")), stack.current.map { it.route })

        stack = stack.selectTab(LDTab.Health)
        assertEquals(listOf(LDRoute.More, LDRoute.Advanced()), stack.current.map { it.route })
        assertEquals(LDTab.Health, stack.current.first().tab)
    }

    @Test
    fun `back pops the front tab only, and an empty stack stays empty`() {
        var stack = LDBackStack().push(LDRoute.More).selectTab(LDTab.Logs).push(LDRoute.LogDetail("a")).pop()
        assertTrue(stack.current.isEmpty())
        stack = stack.pop()
        assertTrue(stack.current.isEmpty())
        assertEquals(1, stack.selectTab(LDTab.Health).current.size)
    }

    @Test
    fun `a double tap does not open the same page twice, but the same page further down is fine`() {
        val twice = LDBackStack().push(LDRoute.More).push(LDRoute.More)
        assertEquals(1, twice.current.size)
        val again = twice.push(LDRoute.Advanced()).push(LDRoute.More)
        assertEquals(3, again.current.size)
        assertEquals("each its own state", 3, again.current.map { it.id }.toSet().size)
    }

    @Test
    fun `ids only count up, also after a pop, so a page never finds the state of one that was closed`() {
        val first = LDBackStack().push(LDRoute.More)
        val id = first.current.single().id
        val second = first.pop().push(LDRoute.More)
        assertFalse(id == second.current.single().id)
        assertTrue(second.current.single().order > first.current.single().order)
    }

    @Test
    fun `replaceTop puts a page in the place of the one in front, as a newer page`() {
        val before = LDBackStack().push(LDRoute.WhatToSend).push(LDRoute.Backfill())
        val after = before.replaceTop(LDRoute.Backfill(LDBackfillStage.Running))
        assertEquals(listOf(LDRoute.WhatToSend, LDRoute.Backfill(LDBackfillStage.Running)), after.current.map { it.route })
        assertTrue(after.current.last().order > before.current.last().order)
    }

    @Test
    fun `the navigator says which pages went for good`() {
        val navigator = LDNavigator()
        val gone = mutableListOf<LDRoute>()
        navigator.onRemoved = { gone += it.route }

        navigator.push(LDRoute.More)
        navigator.push(LDRoute.Advanced())
        assertTrue(navigator.pop())
        assertEquals(listOf<LDRoute>(LDRoute.Advanced()), gone)

        navigator.pop("not the one in front")
        assertEquals(1, navigator.stack.size)

        navigator.open(LDRoute.PairedResult)
        assertEquals(listOf(LDRoute.Advanced(), LDRoute.More), gone)
        assertEquals(listOf<LDRoute>(LDRoute.PairedResult), navigator.stack.map { it.route })

        navigator.pop(navigator.stack.single().id)
        assertFalse(navigator.pop())
    }

    @Test
    fun `a route opened from outside lands on its own tab`() {
        assertEquals(LDTab.Logs, LDRoute.LogDetail("a").homeTab)
        assertEquals(LDTab.ScreenTime, LDRoute.ScreenTimeApps.homeTab)
        assertEquals(LDTab.ScreenTime, LDRoute.WebhookDestination(LDSection.ScreenTime).homeTab)
        assertEquals(LDTab.Health, LDRoute.WebhookDestination().homeTab)
        assertEquals(LDTab.Health, LDRoute.AboutHome.homeTab)
        LDTab.entries.forEach { assertEquals(it, it.root.homeTab) }
    }

    @Test
    fun `the categories cover the 34 types once each`() {
        val listed = LDTypeCategory.entries.flatMap { it.types }
        assertEquals(HealthDataType.entries.size, listed.size)
        assertEquals(HealthDataType.entries.toSet(), listed.toSet())
    }

    @Test
    fun `every route has a debug name, except the ones that need a row`() {
        val named = LDDebugRoute.plain.values.filterIsInstance<LDDebugDestination.Page>().map { it.route }.toSet()
        val needRow = setOf<LDRoute>(LDRoute.WebhookDestination(LDSection.ScreenTime, "id-1"), LDRoute.LogDetail("log-1"), LDRoute.WebhookDestination(LDSection.Health, null, "https://example.org/hook"))
        assertEquals(everyRoute.toSet() - needRow, named)
    }

    @Test
    fun `the debug names that need a row find the newest one, or nothing`() {
        assertEquals(LDDebugDestination.Page(LDRoute.LogDetail("n")), LDDebugRoute.named("logDetail", newestLog = "n"))
        assertEquals(LDDebugDestination.Page(LDRoute.LogDetail("f")), LDDebugRoute.named("logDetail.failed", newestFailedLog = "f"))
        assertEquals(LDDebugDestination.Page(LDRoute.LogDetail("o")), LDDebugRoute.named("logDetail.ok", newestOkLog = "o"))
        assertNull(LDDebugRoute.named("logDetail"))
        assertEquals(LDDebugDestination.Page(LDRoute.WebhookDestination(LDSection.Health, "d")), LDDebugRoute.named("webhookDestination", firstHealthDestination = "d"))
        assertNull(LDDebugRoute.named("no such page"))
        assertNotNull(LDDebugRoute.named("scanner"))
    }
}
