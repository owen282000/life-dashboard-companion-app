package com.owen282000.lifedashboard.viewmodel

import com.owen282000.lifedashboard.AppChoice
import com.owen282000.lifedashboard.AppFilterMode
import com.owen282000.lifedashboard.InvalidHeaderException
import com.owen282000.lifedashboard.ScreenTimeAppFilter
import com.owen282000.lifedashboard.WebhookSupport
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

    /** A destination as the Webhook screen stores it. */
    private fun ScreenTimeViewModel.stored(url: String) {
        assertNull(saveDestination(destinationDraft(null, url)))
    }

    @Test
    fun `day boundary hour must be a real hour`() = runTest {
        val settings = FakeAppSettings()
        val vm = vm(settings)
        assertEquals(UiMessage.InvalidDayBoundaryHour, vm.saveDayBoundaryHour("24"))
        assertEquals("4", settings.screenTime.dayBoundaryHour)

        assertNull(vm.saveDayBoundaryHour("5"))
        assertEquals("5", settings.screenTime.dayBoundaryHour)
        assertEquals("5", vm.state.value.saved.dayBoundaryHour)
    }

    @Test
    fun `a header with a space in its name is refused, and a broker that cannot work is never saved`() = runTest {
        val settings = FakeAppSettings()
        val vm = vm(settings)
        assertEquals(UiMessage.InvalidHeaderName, SettingsRules.headerProblem("Bad Header", "value"))

        val mqtt = vm.mqttDraft()
        val enabled = mqtt.copy(section = mqtt.section.copy(enabled = true))
        assertEquals(UiMessage.MqttHostInvalid, vm.saveMqtt(enabled.withActiveBroker(enabled.activeBroker.copy(host = "bad host"))))

        // With MQTT off too: the field says what is wrong, and its Save stops on that. A webhook is another page's.
        assertEquals(UiMessage.MqttHostInvalid, vm.saveMqtt(mqtt.withActiveBroker(mqtt.activeBroker.copy(host = "bad host"))))
        assertEquals(0, settings.brokerWrites)
        vm.stored("https://example.org/hook")
        assertEquals(listOf("https://example.org/hook"), settings.screenTime.webhook.urls)
    }

    @Test
    fun `a sync stopped by a header names the header instead of an unreachable server`() = runTest {
        val problem = WebhookSupport.HeaderProblem("Bad Header", inValue = false)
        val vm = vm(ops = FakeScreenTimeOps(syncResult = Result.failure(InvalidHeaderException(problem))))
        vm.stored("https://example.org/hook")
        vm.syncNow()
        assertEquals(UiMessage.HeaderBlocksDelivery("Bad Header", inValue = false), vm.state.value.syncMessage)
        assertTrue(vm.state.value.syncMessage!!.isFailure)
    }

    @Test
    fun `choosing apps to leave out is stored at once`() = runTest {
        val settings = FakeAppSettings()
        val vm = vm(settings)
        vm.setAppFilterMode(AppFilterMode.BLOCKLIST)
        vm.toggleFilteredApp("com.google.android.youtube")
        vm.toggleFilteredApp("com.whatsapp")
        vm.toggleFilteredApp("com.whatsapp")
        assertEquals(ScreenTimeAppFilter(AppFilterMode.BLOCKLIST, setOf("com.google.android.youtube")), settings.screenTime.appFilter)
        assertEquals(settings.screenTime.appFilter, vm.state.value.saved.appFilter)
    }

    @Test
    fun `switching back to all apps keeps the list for next time`() {
        val vm = vm()
        vm.setAppFilterMode(AppFilterMode.ALLOWLIST)
        vm.toggleFilteredApp("com.whatsapp")
        vm.setAppFilterMode(AppFilterMode.ALL)
        assertEquals(setOf("com.whatsapp"), vm.state.value.saved.appFilter.packages)
        assertFalse(vm.state.value.saved.appFilter.active)
    }

    @Test
    fun `the picker's apps are loaded once`() = runTest {
        val ops = FakeScreenTimeOps(choices = listOf(AppChoice("com.whatsapp", "WhatsApp", 120)))
        val vm = vm(ops = ops)
        assertNull(vm.state.value.appChoices)
        vm.loadAppChoices()
        vm.loadAppChoices()
        assertEquals(listOf("WhatsApp"), vm.state.value.appChoices?.map { it.name })
        assertEquals(1, ops.choiceLoads)
    }

    @Test
    fun `toggling the day boundary is stored at once, and the hour waits for its Save`() {
        val settings = FakeAppSettings()
        val vm = vm(settings)
        vm.setUseDayBoundary(false)
        assertFalse(settings.screenTime.useDayBoundary)

        assertEquals(UiMessage.InvalidDayBoundaryHour, vm.saveDayBoundaryHour("24"))
        assertEquals("4", settings.screenTime.dayBoundaryHour)
        assertNull(vm.saveDayBoundaryHour("6"))
        assertEquals("6", settings.screenTime.dayBoundaryHour)
        assertEquals("6", vm.state.value.saved.dayBoundaryHour)
    }

    @Test
    fun `sync without usage access opens the settings instead of syncing`() = runTest {
        val ops = FakeScreenTimeOps(usageAccess = false)
        val vm = vm(ops = ops)
        vm.stored("https://example.org/hook")
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
        vm.stored("https://example.org/hook")
        val before = vm.state.value.refreshKey
        vm.syncNow()
        assertEquals(1, ops.syncs)
        assertEquals(UiMessage.SyncedApps(5), vm.state.value.syncMessage)
        assertEquals(before + 1, vm.state.value.refreshKey)
    }

    @Test
    fun `sync now reads what is stored and writes nothing, so it cannot put back older settings`() = runTest {
        val settings = FakeAppSettings()
        val ops = FakeScreenTimeOps()
        val vm = vm(settings, ops)
        vm.stored("https://example.org/hook")
        val writes = settings.destinationWrites
        vm.syncNow()
        assertEquals(writes, settings.destinationWrites)
        assertEquals(0, settings.brokerWrites)
        assertEquals(emptyList<Any>(), settings.scheduleWrites)
        assertEquals("4", settings.screenTime.dayBoundaryHour)
        assertEquals(1, ops.syncs)
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
        vm.stored("https://example.org/hook")
        vm.testPing()
        assertEquals(UiMessage.PingDelivered, toasts.last())
        assertFalse(vm.state.value.isPinging)
        job.cancel()
    }

    @Test
    fun `shared settings changed on the other tab are picked up`() {
        val settings = FakeAppSettings()
        val vm = vm(settings)

        settings.certAlias = "home-cert"
        settings.allowHttp = true
        vm.refreshSharedSettings()

        assertEquals("home-cert", vm.state.value.clientCertAlias)
        assertTrue(vm.state.value.allowHttpWebhooks)
    }

    @Test
    fun `the client certificate is shared with the health screen`() {
        val settings = FakeAppSettings().apply { certAlias = "home-cert" }
        val vm = vm(settings)
        assertEquals("home-cert", vm.state.value.clientCertAlias)

        vm.setClientCertAlias(null)
        assertNull(settings.certAlias)
        assertNull(vm.state.value.clientCertAlias)

        settings.certAlias = "other-cert"
        vm.reloadFromSettings()
        assertEquals("other-cert", vm.state.value.clientCertAlias)
    }

    @Test
    fun `what a sync in the worker wrote is read at once`() = runTest {
        val settings = FakeAppSettings()
        val ops = FakeScreenTimeOps()
        val vm = vm(settings, ops)
        val key = vm.state.value.refreshKey
        settings.mqttStatus = "Failed: no route to host"
        ops.dataChanged.emit(Unit)
        assertEquals(key + 1, vm.state.value.refreshKey)
        assertEquals("Failed: no route to host", vm.state.value.mqttLastStatus)
    }

    @Test
    fun `Sync Now is busy while the worker syncs`() = runTest {
        val ops = FakeScreenTimeOps()
        val vm = vm(ops = ops)
        vm.stored("https://example.org/hook")
        assertFalse(vm.state.value.isSyncBusy)
        ops.workerState.value = true
        assertTrue(vm.state.value.isSyncBusy)
        assertFalse(vm.state.value.canSync)
        ops.workerState.value = false
        assertFalse(vm.state.value.isSyncBusy)
    }

    @Test
    fun `the line under Sync Now goes when the worker ends a later sync, and not before`() = runTest {
        val ops = FakeScreenTimeOps()
        val vm = vm(ops = ops)
        vm.stored("https://example.org/hook")
        vm.syncNow()
        val line = vm.state.value.syncMessage
        assertTrue(line != null)
        ops.workerState.value = true
        assertEquals(line, vm.state.value.syncMessage)
        ops.workerState.value = false
        assertNull(vm.state.value.syncMessage)
    }
}
