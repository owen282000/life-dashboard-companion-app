package com.owen282000.lifedashboard.viewmodel

import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.ReceiveStatus
import com.owen282000.lifedashboard.WriteBackType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
    fun `a paired URL keeps going without headers until it is typed in by hand`() = runTest {
        val paired = "https://paired.example/hook"
        val settings = FakeAppSettings(
            health = HealthDraft(
                WebhookDraft(urls = listOf("https://mine/hook", paired), headers = mapOf("X-Api-Key" to "k"), urlsWithoutHeaders = setOf(paired)),
                emptySet(),
                emptyMqtt()
            )
        )
        val vm = vm(settings)

        // Adding a header is no reason to send it to the paired URL.
        vm.addHeader("Authorization", "Bearer t")
        assertEquals(setOf(paired), vm.state.value.draft.webhook.urlsWithoutHeaders)

        // Removing it forgets the mark; typing it in again is the user's own choice.
        vm.removeUrl(1)
        assertTrue(vm.state.value.draft.webhook.urlsWithoutHeaders.isEmpty())
        vm.addUrl(paired)
        vm.save()
        assertEquals(listOf("https://mine/hook", paired), settings.health.webhook.urls)
        assertTrue(settings.health.webhook.urlsWithoutHeaders.isEmpty())
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
    fun `a manual sync reloads the dashboard card`() = runTest {
        val vm = vm()
        vm.refreshPermissions()
        vm.addUrl("https://example.org/hook")
        val before = vm.state.value.refreshKey
        vm.syncNow()
        assertEquals(before + 1, vm.state.value.refreshKey)
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
    fun `a backfill started during a sync says it waits, and sync now stays off while it runs`() = runTest {
        val running = CompletableDeferred<Unit>()
        val vm = vm(ops = FakeHealthOps().apply { runningSync = running })
        vm.refreshPermissions()
        vm.addUrl("https://example.org/hook")
        assertTrue(vm.state.value.canSync)

        vm.backfill(90)
        assertTrue(vm.state.value.backfillWaiting)
        assertFalse(vm.state.value.canSync)

        running.complete(Unit)
        assertFalse(vm.state.value.backfillWaiting)
        assertNull(vm.state.value.backfillProgress)
        assertEquals(UiMessage.BackfillComplete(90), vm.state.value.syncMessage)
        assertTrue(vm.state.value.canSync)
    }

    @Test
    fun `grant asks for the enabled types and background reading, not every type`() = runTest {
        val settings = FakeAppSettings(health = HealthDraft(WebhookDraft(), setOf(HealthDataType.STEPS, HealthDataType.WEIGHT), emptyMqtt()))
        val vm = vm(settings, FakeHealthOps(granted = emptySet()))
        val request = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.permissionRequests.first() }
        vm.requestAccess()
        assertEquals(
            setOf("android.permission.health.READ_STEPS", "android.permission.health.READ_WEIGHT", HealthConnectManager.BACKGROUND_PERMISSION),
            request.await()
        )
    }

    @Test
    fun `history access is asked for from the backfill dialog alone`() = runTest {
        val settings = FakeAppSettings(health = HealthDraft(WebhookDraft(), setOf(HealthDataType.STEPS), emptyMqtt()))
        val vm = vm(settings)
        val request = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.permissionRequests.first() }
        vm.requestHistoryPermission()
        assertEquals(setOf("android.permission.health.READ_STEPS", HealthConnectManager.HISTORY_PERMISSION), request.await())
    }

    @Test
    fun `a type without its read permission asks for it and goes on once it is granted`() = runTest {
        val ops = FakeHealthOps(granted = setOf("android.permission.health.READ_STEPS"))
        val vm = vm(ops = ops)
        vm.refreshPermissions()

        vm.toggleType(HealthDataType.WEIGHT, true)
        assertEquals(HealthDataType.WEIGHT, vm.state.value.permissionPrompt)
        assertFalse(HealthDataType.WEIGHT in vm.state.value.draft.enabledTypes)

        val request = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.permissionRequests.first() }
        vm.requestTypePermission()
        assertEquals(setOf("android.permission.health.READ_WEIGHT"), request.await())
        assertNull(vm.state.value.permissionPrompt)

        ops.granted = ops.granted + "android.permission.health.READ_WEIGHT"
        vm.refreshPermissions()
        assertTrue(HealthDataType.WEIGHT in vm.state.value.draft.enabledTypes)

        // A type whose permission is already there goes on without asking.
        vm.toggleType(HealthDataType.STEPS, false)
        assertFalse(HealthDataType.STEPS in vm.state.value.draft.enabledTypes)
        vm.toggleType(HealthDataType.STEPS, true)
        assertNull(vm.state.value.permissionPrompt)
        assertTrue(HealthDataType.STEPS in vm.state.value.draft.enabledTypes)
    }

    @Test
    fun `preview stores the payload and dismiss clears it`() = runTest {
        val vm = vm(ops = FakeHealthOps(previewResult = Result.success("{\"a\":1}")))
        vm.preview()
        assertEquals("{\"a\":1}", vm.state.value.previewData)
        vm.dismissPreview()
        assertNull(vm.state.value.previewData)
    }

    @Test
    fun `backfill without a webhook URL explains why instead of opening the dialog`() = runTest {
        val vm = vm()
        val toasts = mutableListOf<UiMessage>()
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.toasts.collect { toasts += it } }
        vm.openBackfillDialog()
        assertEquals(UiMessage.BackfillNeedsWebhook, toasts.last())
        assertFalse(vm.state.value.backfillDialog)

        vm.addUrl("https://example.org/hook")
        vm.openBackfillDialog()
        assertTrue(vm.state.value.backfillDialog)
        vm.backfill(30)
        assertFalse(vm.state.value.backfillDialog)
        assertEquals(UiMessage.BackfillComplete(30), vm.state.value.syncMessage)
        job.cancel()
    }

    @Test
    fun `the client certificate applies at once and is not an unsaved change`() {
        val settings = FakeAppSettings().apply { certAlias = "home-cert" }
        val vm = vm(settings)
        assertEquals("home-cert", vm.state.value.clientCertAlias)

        vm.setClientCertAlias("other-cert")
        assertEquals("other-cert", settings.certAlias)
        assertEquals("other-cert", vm.state.value.clientCertAlias)
        assertFalse(vm.state.value.hasChanges)

        vm.setClientCertAlias(null)
        assertNull(settings.certAlias)
        assertNull(vm.state.value.clientCertAlias)
    }

    @Test
    fun `shared settings changed on the other tab are picked up without touching the draft`() {
        val settings = FakeAppSettings()
        val vm = vm(settings)
        vm.addUrl("https://example.org/hook")
        assertTrue(vm.state.value.hasChanges)

        settings.certAlias = "home-cert"
        settings.allowHttp = true
        vm.refreshSharedSettings()

        assertEquals("home-cert", vm.state.value.clientCertAlias)
        assertTrue(vm.state.value.allowHttpWebhooks)
        assertTrue(vm.state.value.hasChanges)
        assertEquals(listOf("https://example.org/hook"), vm.state.value.draft.webhook.urls)
    }

    @Test
    fun `the phone name applies at once, is shared with the other tab and is not an unsaved change`() {
        val settings = FakeAppSettings()
        val vm = vm(settings)
        assertNull(vm.state.value.phoneName)

        vm.setPhoneName("Pixel 8")
        assertEquals("Pixel 8", settings.storedPhoneName)
        assertEquals("Pixel 8", vm.state.value.phoneName)
        assertFalse(vm.state.value.hasChanges)

        // Cleared on this tab, then set on the other one: the reload picks it up.
        vm.setPhoneName("")
        assertNull(settings.storedPhoneName)
        settings.storedPhoneName = "Zoë"
        vm.refreshSharedSettings()
        assertEquals("Zoë", vm.state.value.phoneName)
    }

    // Receive (issue #62)

    private val haUrl = "http://homeassistant.local:8123/api/webhook/abc"
    private val writeWeight = WriteBackType.WEIGHT.writePermission

    private fun pairedSettings(vararg urls: String, secret: String = "s3cret") = FakeAppSettings(
        health = HealthDraft(WebhookDraft(urls = urls.toList(), secret = secret), emptySet(), emptyMqtt())
    )

    @Test
    fun `receive cannot go on without a Home Assistant webhook and a secret`() = runTest {
        val noSecret = pairedSettings(haUrl, secret = "")
        val vm = vm(noSecret)
        val toasts = mutableListOf<UiMessage>()
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.toasts.collect { toasts += it } }
        vm.setReceiveEnabled(true)
        assertEquals(UiMessage.ReceiveNeedsIntegration, toasts.last())
        assertFalse(vm.state.value.receive.enabled)

        val noIntegration = pairedSettings("https://grafana.example/hook")
        val vm2 = vm(noIntegration)
        val job2 = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm2.toasts.collect { toasts += it } }
        vm2.setReceiveEnabled(true)
        assertEquals(UiMessage.ReceiveNeedsIntegration, toasts.last())
        assertFalse(noIntegration.receive.enabled)
        job.cancel(); job2.cancel()
    }

    @Test
    fun `receive is available only with a saved secret and an integration url`() {
        assertTrue(vm(pairedSettings(haUrl)).state.value.receiveAvailable)
        assertFalse(vm(pairedSettings(haUrl, secret = "")).state.value.receiveAvailable)
        assertFalse(vm(pairedSettings("https://grafana.example/hook")).state.value.receiveAvailable)
        assertFalse(vm(FakeAppSettings()).state.value.receiveAvailable)
    }

    @Test
    fun `receive goes on with the one Home Assistant webhook as its source`() {
        val settings = pairedSettings("https://grafana.example/hook", haUrl)
        val vm = vm(settings)
        vm.setReceiveEnabled(true)
        assertTrue(settings.receive.enabled)
        assertEquals(haUrl, settings.receive.sourceUrl)
        assertEquals(haUrl, vm.state.value.receive.sourceUrl)
        assertFalse("not an unsaved change", vm.state.value.hasChanges)

        vm.setReceiveEnabled(false)
        assertFalse(settings.receive.enabled)
        assertEquals("the source stays chosen for next time", haUrl, settings.receive.sourceUrl)
    }

    @Test
    fun `with several Home Assistant webhooks the user picks the source`() {
        val other = "https://ha2.example/api/webhook/def"
        val settings = pairedSettings(haUrl, other)
        val vm = vm(settings)
        vm.setReceiveEnabled(true)
        assertEquals(listOf(haUrl, other), vm.state.value.receiveSourceChoice)
        assertFalse(settings.receive.enabled)

        vm.chooseReceiveSource(other)
        assertNull(vm.state.value.receiveSourceChoice)
        assertTrue(settings.receive.enabled)
        assertEquals(other, settings.receive.sourceUrl)
    }

    @Test
    fun `a type without its write permission asks for it and goes on once it is granted`() = runTest {
        val settings = pairedSettings(haUrl)
        val ops = FakeHealthOps(granted = setOf("android.permission.health.READ_WEIGHT"))
        val vm = vm(settings, ops)
        vm.refreshPermissions()
        vm.setReceiveEnabled(true)

        vm.toggleReceiveType(WriteBackType.WEIGHT, true)
        assertEquals(WriteBackType.WEIGHT, vm.state.value.receivePermissionPrompt)
        assertTrue(settings.receive.types.isEmpty())

        val request = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.permissionRequests.first() }
        vm.requestReceivePermission()
        request.join()
        assertNull(vm.state.value.receivePermissionPrompt)

        // Refused in Health Connect's dialog: the switch stays off.
        vm.refreshPermissions()
        assertTrue(settings.receive.types.isEmpty())

        // Asked again and granted this time: the switch goes on by itself on the next check.
        vm.toggleReceiveType(WriteBackType.WEIGHT, true)
        vm.requestReceivePermission()
        ops.granted = ops.granted + writeWeight
        vm.refreshPermissions()
        assertEquals(setOf(WriteBackType.WEIGHT), settings.receive.types)
        assertEquals(setOf(WriteBackType.WEIGHT), vm.state.value.receive.types)

        vm.toggleReceiveType(WriteBackType.WEIGHT, false)
        assertTrue(settings.receive.types.isEmpty())
    }

    @Test
    fun `a type with its permission goes on at once and warns when another app already writes it`() = runTest {
        val settings = pairedSettings(haUrl)
        val ops = FakeHealthOps(granted = setOf(writeWeight), otherSources = listOf("com.xiaomi.hm.health"))
        val vm = vm(settings, ops)
        vm.refreshPermissions()
        val toasts = mutableListOf<UiMessage>()
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.toasts.collect { toasts += it } }

        vm.toggleReceiveType(WriteBackType.WEIGHT, true)
        assertNull(vm.state.value.receivePermissionPrompt)
        assertEquals(setOf(WriteBackType.WEIGHT), settings.receive.types)
        assertEquals("the package is shown by its app name", UiMessage.OtherSourceWrites("Zepp", WriteBackType.WEIGHT), toasts.last())

        vm.setReceiveOlderMeasurements(true)
        assertTrue(settings.receive.olderMeasurements)
        assertTrue(vm.state.value.receive.olderMeasurements)
        job.cancel()
    }

    @Test
    fun `the sync line says what was written to Health Connect`() = runTest {
        val ops = FakeHealthOps(syncResult = Result.success(HealthSyncResult.Success(mapOf(HealthDataType.STEPS to 12), written = 2)))
        val vm = vm(ops = ops)
        vm.addUrl("https://example.org/hook")
        vm.syncNow()
        assertEquals(UiMessage.SyncedRecordsWritten(12, 2), vm.state.value.syncMessage)

        val nothingWritten = vm(ops = FakeHealthOps(syncResult = Result.success(HealthSyncResult.Success(mapOf(HealthDataType.STEPS to 12)))))
        nothingWritten.addUrl("https://example.org/hook")
        nothingWritten.syncNow()
        assertEquals(UiMessage.SyncedRecords(12), nothingWritten.state.value.syncMessage)
    }

    @Test
    fun `what the integration reported is re-read after a sync and a reload`() = runTest {
        val settings = pairedSettings(haUrl)
        val vm = vm(settings)
        vm.addUrl("https://example.org/hook")
        settings.status = ReceiveStatus(configured = listOf("weight", "blood_pressure"), integrationOutdated = false, writtenToday = 3)
        vm.syncNow()
        assertEquals(listOf("weight", "blood_pressure"), vm.state.value.receiveStatus.configured)
        assertEquals(3, vm.state.value.receiveStatus.writtenToday)

        settings.status = ReceiveStatus(integrationOutdated = true)
        vm.reloadFromSettings()
        assertTrue(vm.state.value.receiveStatus.integrationOutdated)
    }

    @Test
    fun `a client certificate picked on the other screen shows up after a reload`() {
        val settings = FakeAppSettings()
        val vm = vm(settings)
        settings.certAlias = "home-cert"
        vm.reloadFromSettings()
        assertEquals("home-cert", vm.state.value.clientCertAlias)
    }
}
