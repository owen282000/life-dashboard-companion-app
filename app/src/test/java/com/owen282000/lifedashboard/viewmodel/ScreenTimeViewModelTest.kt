package com.owen282000.lifedashboard.viewmodel

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenTimeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(settings: FakeAppSettings = FakeAppSettings(), ops: FakeScreenTimeOps = FakeScreenTimeOps()) =
        ScreenTimeViewModel(settings, ops)

    @Test
    fun `day boundary hour must be a real hour`() = runTest {
        val settings = FakeAppSettings()
        val vm = vm(settings)
        val toasts = mutableListOf<UiMessage>()
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.toasts.collect { toasts += it } }
        vm.addUrl("https://example.org/hook")
        vm.setDayBoundaryHour("24")
        vm.save()
        assertEquals(UiMessage.InvalidDayBoundaryHour, toasts.last())
        assertEquals(0, settings.savedScreenTime)

        vm.setDayBoundaryHour("5")
        vm.save()
        assertEquals(UiMessage.Saved, toasts.last())
        assertEquals("5", settings.screenTime.dayBoundaryHour)
        assertFalse(vm.state.value.hasChanges)
        job.cancel()
    }

    @Test
    fun `toggling the day boundary is a change`() {
        val vm = vm()
        vm.setUseDayBoundary(false)
        assertTrue(vm.state.value.hasChanges)
    }

    @Test
    fun `sync without usage access opens the settings instead of syncing`() = runTest {
        val ops = FakeScreenTimeOps(usageAccess = false)
        val vm = vm(ops = ops)
        vm.addUrl("https://example.org/hook")
        val opened = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.openUsageAccess.first() }
        vm.syncNow()
        opened.join()
        assertEquals(0, ops.syncs)
        assertEquals(UiMessage.UsageAccessMissing, vm.state.value.syncMessage)
        assertFalse(vm.state.value.canSync)
    }

    @Test
    fun `sync reports the app count and bumps the refresh key`() = runTest {
        val ops = FakeScreenTimeOps()
        val vm = vm(ops = ops)
        vm.addUrl("https://example.org/hook")
        val before = vm.state.value.refreshKey
        vm.syncNow()
        assertEquals(1, ops.syncs)
        assertEquals(UiMessage.SyncedApps(5), vm.state.value.syncMessage)
        assertEquals(before + 1, vm.state.value.refreshKey)
    }

    @Test
    fun `refreshing usage access picks up a grant made in system settings`() {
        val ops = FakeScreenTimeOps(usageAccess = false)
        val vm = vm(ops = ops)
        assertFalse(vm.state.value.hasUsageAccess)
        ops.usageAccess = true
        vm.refreshUsageAccess()
        assertTrue(vm.state.value.hasUsageAccess)
    }

    @Test
    fun `test ping reports delivery`() = runTest {
        val vm = vm()
        val toasts = mutableListOf<UiMessage>()
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.toasts.collect { toasts += it } }
        vm.addUrl("https://example.org/hook")
        vm.testPing()
        assertEquals(UiMessage.PingDelivered, toasts.last())
        assertFalse(vm.state.value.isPinging)
        job.cancel()
    }

    @Test
    fun `the client certificate is shared with the health screen`() {
        val settings = FakeAppSettings().apply { certAlias = "home-cert" }
        val vm = vm(settings)
        assertEquals("home-cert", vm.state.value.clientCertAlias)

        vm.setClientCertAlias(null)
        assertNull(settings.certAlias)
        assertNull(vm.state.value.clientCertAlias)
        assertFalse(vm.state.value.hasChanges)

        settings.certAlias = "other-cert"
        vm.reloadFromSettings()
        assertEquals("other-cert", vm.state.value.clientCertAlias)
    }
}
