package com.owen282000.lifedashboard.viewmodel

import com.owen282000.lifedashboard.BackfillFailure
import com.owen282000.lifedashboard.BackfillJob
import com.owen282000.lifedashboard.BackfillStatus
import com.owen282000.lifedashboard.Destination
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.ReceiveStatus
import com.owen282000.lifedashboard.SeriesResolution
import com.owen282000.lifedashboard.ServerTrust
import com.owen282000.lifedashboard.WriteBackType
import com.owen282000.lifedashboard.destinationsOf
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
        vm.removeDestination(vm.state.value.draft.webhook.destinations.single().id)
        vm.save()
        assertEquals(UiMessage.NoDestination, toasts.last())
        assertEquals(0, settings.savedHealth)
        job.cancel()
    }

    @Test
    fun `MQTT alone is a valid destination`() = runTest {
        val settings = FakeAppSettings()
        val vm = vm(settings)
        vm.setMqtt(vm.state.value.draft.mqtt.let { it.withActiveBroker(it.activeBroker.copy(host = "192.168.1.10")).copy(section = it.section.copy(enabled = true)) })
        vm.save()
        assertEquals(1, settings.savedHealth)
        assertFalse(vm.state.value.hasChanges)
    }

    @Test
    fun `a broker host with a space or port 0 is not saved, and the message says which field`() = runTest {
        val settings = FakeAppSettings()
        val vm = vm(settings)
        val toasts = mutableListOf<UiMessage>()
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.toasts.collect { toasts += it } }
        val enabled = vm.state.value.draft.mqtt.let { it.copy(section = it.section.copy(enabled = true)) }

        vm.setMqtt(enabled.withActiveBroker(enabled.activeBroker.copy(host = "bad host")))
        vm.save()
        assertEquals(UiMessage.MqttHostInvalid, toasts.last())

        vm.setMqtt(enabled.withActiveBroker(enabled.activeBroker.copy(host = "broker.local")).copy(portText = "0"))
        vm.save()
        assertEquals(UiMessage.MqttPortInvalid, toasts.last())

        vm.setMqtt(enabled)
        vm.save()
        assertEquals(UiMessage.MqttHostMissing, toasts.last())
        assertEquals(0, settings.savedHealth)
        assertTrue(vm.state.value.hasChanges)
        job.cancel()
    }

    @Test
    fun `a header name with a space is refused when it is added`() = runTest {
        val vm = vm()
        val toasts = mutableListOf<UiMessage>()
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.toasts.collect { toasts += it } }
        vm.addUrl("https://example.org/hook")
        val id = vm.state.value.draft.webhook.destinations.single().id
        vm.addHeader(id, "Bad Header", "value")
        assertEquals(UiMessage.InvalidHeaderName, toasts.last())
        vm.addHeader(id, "X-Api-Key", "line\nbreak")
        assertEquals(UiMessage.InvalidHeaderValue, toasts.last())
        assertTrue(vm.state.value.draft.webhook.destinations.single().headers.isEmpty())

        vm.addHeader(id, " X-Api-Key ", " k ")
        assertEquals(mapOf("X-Api-Key" to "k"), vm.state.value.draft.webhook.destinations.single().headers)
        job.cancel()
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

    private val stack = Destination(id = "stack", url = "https://stack.example/health", secret = "stack-secret", headers = mapOf("X-Api-Key" to "k"))
    private val ha = Destination(id = "ha", url = "https://ha.example/api/webhook/abc", secret = "ha-secret")

    private fun twoReceivers() = FakeAppSettings(
        health = HealthDraft(WebhookDraft(destinations = listOf(stack, ha), sectionSecret = "stack-secret"), emptySet(), emptyMqtt())
    )

    @Test
    fun `each destination keeps its own secret and headers when another one is edited`() = runTest {
        val settings = twoReceivers()
        val vm = vm(settings)

        vm.setSecret(ha.id, "rotated")
        vm.addHeader(ha.id, "Authorization", "Bearer t")
        vm.removeHeader(stack.id, "X-Api-Key")
        vm.save()

        assertEquals(listOf(stack.copy(headers = emptyMap()), ha.copy(secret = "rotated", headers = mapOf("Authorization" to "Bearer t"))), settings.health.webhook.destinations)
    }

    @Test
    fun `editedUrlKeepsItsSecretAndHeaders`() = runTest {
        val settings = twoReceivers()
        val vm = vm(settings)

        vm.setDestinationUrl(stack.id, " https://stack.example:8443/health ")
        vm.save()

        // Trimmed on save, and still the same destination: its id, secret and headers stay.
        assertEquals(listOf(stack.copy(url = "https://stack.example:8443/health"), ha), settings.health.webhook.destinations)
    }

    @Test
    fun `a URL added by hand starts with the secret the others share and no headers`() = runTest {
        val shared = FakeAppSettings(
            health = HealthDraft(WebhookDraft(destinations = listOf(stack)), emptySet(), emptyMqtt())
        )
        val vm = vm(shared)
        vm.addUrl("https://third.example/hook")
        val added = vm.state.value.draft.webhook.destinations.last()
        assertEquals("stack-secret", added.secret)
        assertTrue(added.headers.isEmpty())

        // Two different secrets: the section's own one is the default.
        val vm2 = vm(twoReceivers())
        vm2.addUrl("https://third.example/hook")
        assertEquals("stack-secret", vm2.state.value.draft.webhook.destinations.last().secret)

        // The same address twice is one destination.
        vm2.addUrl(ha.url)
        assertEquals(3, vm2.state.value.draft.webhook.destinations.size)
    }

    @Test
    fun `an address moved to another host leaves its headers and secret behind, and gets them back on its own host`() = runTest {
        val settings = twoReceivers()
        val vm = vm(settings)

        // A new port or scheme is the same receiver.
        vm.setDestinationUrl(stack.id, "http://stack.example:8080/health")
        assertEquals(stack.copy(url = "http://stack.example:8080/health"), vm.state.value.draft.webhook.destinations.first())

        // Another host starts like a new address: no headers, and the secret a new one would get.
        vm.setDestinationUrl(stack.id, "https://elsewhere.example/health")
        val moved = vm.state.value.draft.webhook.destinations.first()
        assertTrue(moved.headers.isEmpty())
        assertEquals("the one the other destinations share", ha.secret, moved.secret)

        // Typed back to its own host, as happens mid-edit, it has its own again.
        vm.setDestinationUrl(stack.id, stack.url)
        assertEquals(stack, vm.state.value.draft.webhook.destinations.first())
    }

    @Test
    fun `an address listed twice is refused, added or edited`() = runTest {
        val settings = twoReceivers()
        val vm = vm(settings)
        val toasts = mutableListOf<UiMessage>()
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.toasts.collect { toasts += it } }

        vm.addUrl(" ${ha.url} ")
        assertEquals(UiMessage.DuplicateUrl, toasts.last())

        vm.setDestinationUrl(stack.id, ha.url)
        vm.save()
        assertEquals(UiMessage.DuplicateUrl, toasts.last())
        assertEquals(0, settings.savedHealth)
        job.cancel()
    }

    @Test
    fun `headers and a secret can be copied from another destination`() = runTest {
        val vm = vm(twoReceivers())

        vm.copyHeaders(ha.id, stack.id)
        vm.copySecret(ha.id, stack.id)

        assertEquals(ha.copy(headers = stack.headers, secret = stack.secret), vm.state.value.draft.webhook.destinations.last())
        assertEquals("the source keeps its own", stack, vm.state.value.draft.webhook.destinations.first())
    }

    @Test
    fun `a save the secure storage cannot keep is refused and stays unsaved`() = runTest {
        val settings = FakeAppSettings().apply { refuseSaves = true }
        val vm = vm(settings)
        val toasts = mutableListOf<UiMessage>()
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.toasts.collect { toasts += it } }

        vm.addUrl("https://example.org/hook")
        vm.save()

        assertEquals(UiMessage.SecretsNotReady, toasts.last())
        assertTrue(toasts.last().isFailure)
        assertTrue("the user can try again", vm.state.value.hasChanges)
        job.cancel()
    }

    @Test
    fun `urlWithCredentialsIsRejected when added or edited, and one saved before keeps working`() = runTest {
        val legacy = Destination(id = "old", url = "https://old.example/hook?token=abc")
        val settings = FakeAppSettings(health = HealthDraft(WebhookDraft(destinations = listOf(legacy)), emptySet(), emptyMqtt()))
        val vm = vm(settings)
        val toasts = mutableListOf<UiMessage>()
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.toasts.collect { toasts += it } }

        vm.addUrl("https://user:pass@example.org/hook")
        assertEquals(UiMessage.UrlHasCredentials(null), toasts.last())
        vm.addUrl("https://example.org/hook?apiKey=k")
        assertEquals(UiMessage.UrlHasCredentials("apiKey"), toasts.last())
        assertEquals(listOf(legacy), vm.state.value.draft.webhook.destinations)

        // Saved before the check existed: saving the tab keeps it.
        vm.addUrl("https://example.org/hook")
        vm.save()
        assertEquals(UiMessage.Saved, toasts.last())

        // Edited, it is checked like a new one.
        vm.setDestinationUrl(legacy.id, "https://old.example/hook?password=x")
        vm.save()
        assertEquals(UiMessage.UrlHasCredentials("password"), toasts.last())
        assertEquals(1, settings.savedHealth)

        // An Apps Script web app can only check a token in its URL.
        vm.setDestinationUrl(legacy.id, "https://script.google.com/macros/s/abc/exec?token=t")
        vm.save()
        assertEquals(UiMessage.Saved, toasts.last())
        job.cancel()
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
    fun `sync now without changes writes nothing, so it cannot put back older settings`() = runTest {
        val settings = FakeAppSettings()
        val ops = FakeHealthOps()
        val vm = vm(settings, ops)
        vm.refreshPermissions()
        vm.addUrl("https://example.org/hook")
        vm.save()
        assertEquals(1, settings.savedHealth)
        vm.syncNow()
        assertEquals(1, settings.savedHealth)
        assertEquals(1, ops.syncs)
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
    fun `backfill starts the job, follows its progress and then says the total`() = runTest {
        val ops = FakeHealthOps()
        val vm = vm(ops = ops)
        vm.backfill(90)
        assertEquals(listOf(90), ops.backfillStarts)
        assertEquals(0 to 30, vm.state.value.backfillProgress)

        ops.backfill = BackfillStatus.Running(12, 30)
        assertEquals(12 to 30, vm.state.value.backfillProgress)
        ops.backfill = BackfillStatus.Finished(records = 4_000, failure = null)
        assertNull(vm.state.value.backfillProgress)
        assertEquals(UiMessage.BackfillComplete(4_000), vm.state.value.syncMessage)
    }

    @Test
    fun `a backfill that runs when the screen opens is shown, and a start then says it runs already`() = runTest {
        val ops = FakeHealthOps().apply { backfill = BackfillStatus.Running(7, 30) }
        val vm = vm(ops = ops)
        val toasts = mutableListOf<UiMessage>()
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.toasts.collect { toasts += it } }
        assertEquals(7 to 30, vm.state.value.backfillProgress)

        vm.backfill(90)
        assertEquals(emptyList<Int>(), ops.backfillStarts)
        assertEquals(listOf<UiMessage>(UiMessage.BackfillAlreadyRunning), toasts)
        assertEquals(7 to 30, vm.state.value.backfillProgress)
        job.cancel()
    }

    @Test
    fun `a backfill that finished before the screen looked says nothing`() = runTest {
        val ops = FakeHealthOps().apply { backfill = BackfillStatus.Finished(records = 10, failure = null) }
        val vm = vm(ops = ops)
        assertNull(vm.state.value.backfillProgress)
        assertNull(vm.state.value.syncMessage)
    }

    @Test
    fun `a failed backfill says why, and a paused one says it continues and lets a sync run`() = runTest {
        val ops = FakeHealthOps()
        val vm = vm(ops = ops)
        vm.refreshPermissions()
        vm.addUrl("https://example.org/hook")
        vm.backfill(30)
        assertFalse(vm.state.value.canSync)
        ops.backfill = BackfillStatus.Running(3, 10, paused = true)
        assertTrue(vm.state.value.backfillPaused)
        assertTrue("a paused backfill holds no lock", vm.state.value.canSync)
        val failure = BackfillFailure.Delivery(done = 3, total = 10)
        ops.backfill = BackfillStatus.Finished(records = null, failure = failure)
        assertFalse(vm.state.value.backfillPaused)
        assertEquals(UiMessage.BackfillFailed(failure), vm.state.value.syncMessage)
        assertTrue(vm.state.value.syncMessage!!.isFailure)
    }

    @Test
    fun `stop clears the line at once, also while the stopped work still reports itself running`() = runTest {
        val ops = FakeHealthOps()
        val vm = vm(ops = ops)
        vm.backfill(90)
        ops.backfill = BackfillStatus.Running(5, 30)
        vm.cancelBackfill()
        assertEquals(1, ops.backfillCancels)
        assertNull(vm.state.value.backfillProgress)

        // The worker has not unwound yet: WorkManager still says running.
        ops.backfill = BackfillStatus.Running(5, 30)
        assertNull(vm.state.value.backfillProgress)
        ops.backfill = BackfillStatus.Idle
        assertNull(vm.state.value.backfillProgress)
        assertNull(vm.state.value.syncMessage)
    }

    @Test
    fun `a start right after stop starts a new backfill instead of doing nothing`() = runTest {
        val ops = FakeHealthOps()
        val vm = vm(ops = ops)
        vm.backfill(90)
        vm.cancelBackfill()
        assertNull(vm.state.value.backfillProgress)

        vm.backfill(90)

        assertEquals(listOf(90, 90), ops.backfillStarts)
        assertEquals(0 to 30, vm.state.value.backfillProgress)
    }

    @Test
    fun `a backfill that started over for other data types says so`() = runTest {
        val ops = FakeHealthOps()
        val vm = vm(ops = ops)
        vm.backfill(90)
        ops.backfill = BackfillStatus.Running(0, 30, restarted = true)
        assertTrue(vm.state.value.backfillRestarted)
        ops.backfill = BackfillStatus.Finished(records = 5, failure = null)
        assertFalse(vm.state.value.backfillRestarted)
    }

    @Test
    fun `the dialog looks up the oldest record only with history access, and again once it is granted`() = runTest {
        val ops = FakeHealthOps().apply { earliest = 1_600_000_000_000L }
        val vm = vm(ops = ops)
        vm.refreshPermissions()
        vm.addUrl("https://example.org/hook")
        vm.openBackfillDialog()
        assertEquals("without history access the answer would be cut off", 0, ops.earliestLookups)
        assertNull(vm.state.value.backfillEarliest)

        ops.granted = ops.granted + HealthConnectManager.HISTORY_PERMISSION
        vm.refreshPermissions()
        assertEquals(1, ops.earliestLookups)
        assertEquals(1_600_000_000_000L, vm.state.value.backfillEarliest)

        vm.dismissBackfillDialog()
        vm.openBackfillDialog()
        assertEquals("looked up again each time the dialog opens", 2, ops.earliestLookups)
    }

    @Test
    fun `a backfill of all history with nothing to send says so`() = runTest {
        val ops = FakeHealthOps()
        val vm = vm(ops = ops)
        val toasts = mutableListOf<UiMessage>()
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.toasts.collect { toasts += it } }
        vm.backfill(BackfillJob.ALL_HISTORY)
        assertEquals(listOf<UiMessage>(UiMessage.BackfillNoRecords), toasts)
        assertEquals(emptyList<Int>(), ops.backfillStarts)
        job.cancel()
    }

    @Test
    fun `the dialog names the stopped backfill that its length continues`() = runTest {
        val stopped = BackfillJob.start(days = 90, types = listOf("STEPS"), now = 1_000_000L).copy(nextWindow = 12)
        val vm = vm(ops = FakeHealthOps().apply { this.stopped = stopped })
        vm.addUrl("https://example.org/hook")
        vm.openBackfillDialog()
        assertEquals(stopped, vm.state.value.stoppedBackfill)
    }

    @Test
    fun `a backfill that waits for a sync says so, and sync now stays off while it runs`() = runTest {
        val ops = FakeHealthOps()
        val vm = vm(ops = ops)
        vm.refreshPermissions()
        vm.addUrl("https://example.org/hook")
        assertTrue(vm.state.value.canSync)

        vm.backfill(90)
        ops.backfill = BackfillStatus.Running(0, 30, waiting = true)
        assertTrue(vm.state.value.backfillWaiting)
        assertFalse(vm.state.value.canSync)

        ops.backfill = BackfillStatus.Running(1, 30)
        assertFalse(vm.state.value.backfillWaiting)
        ops.backfill = BackfillStatus.Finished(records = 90, failure = null)
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
    fun `view and export use the data types and resolution on screen without saving them`() = runTest {
        val savedResolutions = mapOf(HealthDataType.STEPS to SeriesResolution.HOURLY)
        val settings = FakeAppSettings(
            health = HealthDraft(WebhookDraft(), setOf(HealthDataType.STEPS), emptyMqtt(), resolutions = savedResolutions)
        )
        val ops = FakeHealthOps(granted = setOf("android.permission.health.READ_STEPS", "android.permission.health.READ_WEIGHT"))
        val vm = vm(settings, ops)
        vm.refreshPermissions()
        vm.toggleType(HealthDataType.WEIGHT, true)
        vm.setResolution(HealthDataType.STEPS, SeriesResolution.FIFTEEN_MINUTES)
        val onScreen = setOf(HealthDataType.STEPS, HealthDataType.WEIGHT)
        val resolutionsOnScreen = mapOf(HealthDataType.STEPS to SeriesResolution.FIFTEEN_MINUTES)
        assertEquals(onScreen, vm.state.value.draft.enabledTypes)
        assertEquals(resolutionsOnScreen, vm.state.value.draft.resolutions)

        vm.preview()
        vm.export()

        assertEquals(listOf(onScreen, onScreen), ops.previewTypes)
        assertEquals(listOf(resolutionsOnScreen, resolutionsOnScreen), ops.previewResolutions)
        assertEquals(setOf(HealthDataType.STEPS), settings.health.enabledTypes)
        assertEquals(savedResolutions, settings.health.resolutions)
        assertEquals(0, settings.savedHealth)
        assertTrue(vm.state.value.hasChanges)
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
        assertEquals(0 to 10, vm.state.value.backfillProgress)
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
        health = HealthDraft(WebhookDraft(destinations = destinationsOf(urls.toList(), secret = secret.ifEmpty { null })), emptySet(), emptyMqtt())
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

    @Test
    fun `server trust is applied at once and a host back at the defaults is dropped`() {
        val settings = FakeAppSettings()
        val vm = vm(settings)

        vm.setServerTrust("nas.lan:443", ServerTrust(userCas = true))
        vm.setServerTrust("ha.lan:8123", ServerTrust(pinSha256 = "AB:CD"))
        assertEquals(mapOf("nas.lan:443" to ServerTrust(userCas = true), "ha.lan:8123" to ServerTrust(pinSha256 = "AB:CD")), settings.trust)
        assertEquals(settings.trust, vm.state.value.serverTrust)
        // Applied like the other Advanced settings: nothing waits for Save.
        assertFalse(vm.state.value.hasChanges)

        vm.setServerTrust("nas.lan:443", ServerTrust())
        assertEquals(mapOf("ha.lan:8123" to ServerTrust(pinSha256 = "AB:CD")), settings.trust)
    }
}
