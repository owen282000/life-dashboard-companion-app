package com.owen282000.lifedashboard.viewmodel

import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.HealthSyncResult
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
class HealthConnectViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(settings: FakeAppSettings = FakeAppSettings(), ops: FakeHealthOps = FakeHealthOps()) =
        HealthConnectViewModel(settings, ops)

    @Test
    fun `starts clean and marks changes as the draft diverges`() {
        val vm = vm()
        assertFalse(vm.state.value.hasChanges)
        vm.setSyncInterval("90")
        assertTrue(vm.state.value.hasChanges)
        vm.setSyncInterval("60")
        assertFalse(vm.state.value.hasChanges)
    }

    @Test
    fun `an unparseable interval does not count as a change`() {
        val vm = vm()
        vm.setSyncInterval("")
        assertFalse(vm.state.value.hasChanges)
    }

    @Test
    fun `save refuses intervals under fifteen minutes and destinations that are missing`() = runTest {
        val settings = FakeAppSettings()
        val vm = vm(settings)
        val toasts = mutableListOf<UiMessage>()
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.toasts.collect { toasts += it } }

        vm.setSyncInterval("10")
        vm.addUrl("https://example.org/hook")
        vm.save()
        assertEquals(UiMessage.IntervalTooShort, toasts.last())

        vm.setSyncInterval("30")
        vm.removeUrl(0)
        vm.save()
        assertEquals(UiMessage.NoDestination, toasts.last())
        assertEquals(0, settings.savedHealth)
        job.cancel()
    }

    @Test
    fun `MQTT alone is a valid destination`() = runTest {
        val settings = FakeAppSettings()
        val vm = vm(settings)
        vm.setMqtt(vm.state.value.draft.mqtt.let { it.copy(section = it.section.copy(enabled = true)) })
        vm.save()
        assertEquals(1, settings.savedHealth)
        assertFalse(vm.state.value.hasChanges)
    }

    @Test
    fun `save persists the draft and folds the port text into the broker`() = runTest {
        val settings = FakeAppSettings()
        val vm = vm(settings)
        vm.addUrl("https://example.org/hook")
        vm.setMqtt(vm.state.value.draft.mqtt.copy(portText = "8883"))
        vm.save()
        assertEquals(1, settings.savedHealth)
        assertEquals(listOf("https://example.org/hook"), settings.health.webhook.urls)
        assertEquals(8883, vm.state.value.saved.mqtt.sharedBroker.port)
        assertFalse(vm.state.value.hasChanges)
    }

    @Test
    fun `invalid URLs are rejected with a message instead of being added`() = runTest {
        val vm = vm()
        val toasts = mutableListOf<UiMessage>()
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.toasts.collect { toasts += it } }
        vm.addUrl("example.org")
        assertEquals(UiMessage.InvalidUrl, toasts.last())
        assertTrue(vm.state.value.draft.webhook.urls.isEmpty())
        job.cancel()
    }

    @Test
    fun `toggling a type without any permission asks for the permission first`() = runTest {
        val vm = vm(ops = FakeHealthOps(granted = emptySet()))
        vm.refreshPermissions()
        vm.toggleType(HealthDataType.STEPS, true)
        assertEquals(HealthDataType.STEPS, vm.state.value.permissionPrompt)
        assertTrue(vm.state.value.draft.enabledTypes.isEmpty())
        vm.dismissPermissionPrompt()
        assertNull(vm.state.value.permissionPrompt)
    }

    @Test
    fun `granted permissions pre-select their types on a fresh install`() = runTest {
        val vm = vm(ops = FakeHealthOps(granted = setOf("android.permission.health.READ_STEPS")))
        vm.refreshPermissions()
        assertEquals(true, vm.state.value.hasPermissions)
        assertEquals(setOf(HealthDataType.STEPS), vm.state.value.draft.enabledTypes)
        assertFalse(vm.state.value.hasChanges)
    }

    @Test
    fun `sync now saves first and reports the record count`() = runTest {
        val settings = FakeAppSettings()
        val ops = FakeHealthOps()
        val vm = vm(settings, ops)
        vm.refreshPermissions()
        vm.addUrl("https://example.org/hook")
        vm.syncNow()
        assertEquals(1, settings.savedHealth)
        assertEquals(1, ops.syncs)
        assertEquals(UiMessage.SyncedRecords(12), vm.state.value.syncMessage)
        assertFalse(vm.state.value.isSyncing)
    }

    @Test
    fun `sync now without permissions asks the launcher instead of syncing`() = runTest {
        val ops = FakeHealthOps(granted = emptySet())
        val vm = vm(ops = ops)
        vm.addUrl("https://example.org/hook")
        val request = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.permissionRequests.first() }
        vm.syncNow()
        request.join()
        assertEquals(0, ops.syncs)
    }

    @Test
    fun `sync failure lands in the sync line as a failure`() = runTest {
        val ops = FakeHealthOps(syncResult = Result.failure(IllegalStateException("boom")))
        val vm = vm(ops = ops)
        vm.addUrl("https://example.org/hook")
        vm.syncNow()
        val message = vm.state.value.syncMessage
        assertEquals(UiMessage.SyncFailed("boom"), message)
        assertTrue(message!!.isFailure)
    }

    @Test
    fun `no data and queued results map to their messages`() = runTest {
        val ops = FakeHealthOps(syncResult = Result.success(HealthSyncResult.Queued(7)))
        val vm = vm(ops = ops)
        vm.addUrl("https://example.org/hook")
        vm.syncNow()
        assertEquals(UiMessage.QueuedRecords(7), vm.state.value.syncMessage)
    }

    @Test
    fun `backfill reports progress and then the total`() = runTest {
        val vm = vm()
        vm.backfill(90)
        assertNull(vm.state.value.backfillProgress)
        assertEquals(UiMessage.BackfillComplete(90), vm.state.value.syncMessage)
    }

    @Test
    fun `preview stores the payload and dismiss clears it`() = runTest {
        val vm = vm(ops = FakeHealthOps(previewResult = Result.success("{\"a\":1}")))
        vm.preview()
        assertEquals("{\"a\":1}", vm.state.value.previewData)
        vm.dismissPreview()
        assertNull(vm.state.value.previewData)
    }
}
