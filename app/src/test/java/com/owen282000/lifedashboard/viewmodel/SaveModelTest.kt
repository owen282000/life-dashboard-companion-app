package com.owen282000.lifedashboard.viewmodel

import com.owen282000.lifedashboard.Destination
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.MqttBroker
import com.owen282000.lifedashboard.MqttSection
import com.owen282000.lifedashboard.MqttSectionSettings
import com.owen282000.lifedashboard.ReceiveSettings
import com.owen282000.lifedashboard.SyncMode
import com.owen282000.lifedashboard.TodayDay
import com.owen282000.lifedashboard.TodayKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * No Save bar: switches, chips and times apply at once, and the screens where text is typed hold
 * their own draft until an explicit Save. Each test here is a way that goes wrong, from reading the
 * old code: the list in the redesign's brief.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val readSteps = "android.permission.health.READ_STEPS"
    private val readWeight = "android.permission.health.READ_WEIGHT"
    private val stack = Destination(id = "stack", url = "https://stack.example/health", secret = "stack-secret", headers = mapOf("X-Api-Key" to "k"))
    private val ha = Destination(id = "ha", url = "https://ha.example/api/webhook/abc", secret = "ha-secret")

    private fun twoReceivers() = FakeAppSettings(
        health = HealthDraft(WebhookDraft(destinations = listOf(stack, ha)), emptySet(), emptyMqtt())
    )

    private fun enabledMqtt(host: String = "broker.local") = MqttDraft.from(
        MqttSectionSettings(enabled = true, useSharedBroker = true, ownBroker = MqttBroker("", 1883, false, null, null), baseTopic = "lifedashboard"),
        MqttBroker(host, 1883, false, "user", "pass")
    )

    // ==================== The half typed address ====================

    @Test
    fun `typing in the draft stores nothing, and typing back to the host brings headers and secret back`() {
        val settings = twoReceivers()
        val vm = HealthConnectViewModel(settings, FakeHealthOps())

        var draft = vm.destinationDraft("stack")
        draft = draft.withUrl("https://st")
        assertTrue("another host starts without them", draft.headers.isEmpty())
        assertEquals("the secret a new one would get", ha.secret, draft.secret)
        assertEquals("still on disk", 0, settings.destinationWrites)

        draft = draft.withUrl(stack.url)
        assertEquals(stack, draft.destination)
    }

    @Test
    fun `a save that moves to another host is the one write, and it can no longer be typed back`() {
        val settings = twoReceivers()
        val vm = HealthConnectViewModel(settings, FakeHealthOps())

        assertNull(vm.saveDestination(vm.destinationDraft("stack").withUrl("https://elsewhere.example/health")))
        assertEquals(1, settings.destinationWrites)

        val moved = settings.health.webhook.destinations.first()
        assertEquals("stack", moved.id)
        assertEquals("https://elsewhere.example/health", moved.url)
        assertTrue(moved.headers.isEmpty())
        assertEquals(moved, vm.state.value.saved.webhook.destinations.first())
    }

    @Test
    fun `a problem on the address stores nothing and comes back for under the field`() {
        val settings = twoReceivers()
        val vm = HealthConnectViewModel(settings, FakeHealthOps())

        val invalid = vm.destinationDraft("stack").withUrl("not a url")
        assertEquals(UiMessage.InvalidUrl, invalid.urlProblem)
        assertEquals(UiMessage.InvalidUrl, vm.saveDestination(invalid))

        val duplicate = vm.destinationDraft("stack").withUrl(ha.url)
        assertEquals(UiMessage.DuplicateUrl, duplicate.urlProblem)
        assertEquals(UiMessage.DuplicateUrl, vm.saveDestination(duplicate))

        val credentials = vm.destinationDraft(null, "https://user:pass@example.org/hook")
        assertEquals(UiMessage.UrlHasCredentials(null), vm.saveDestination(credentials))

        assertEquals(0, settings.destinationWrites)
    }

    @Test
    fun `a new destination starts with the secret the others share and stays out of the list until Save`() {
        val settings = FakeAppSettings(health = HealthDraft(WebhookDraft(destinations = listOf(stack)), emptySet(), emptyMqtt()))
        val vm = HealthConnectViewModel(settings, FakeHealthOps())

        val draft = vm.destinationDraft(null, " https://third.example/hook ")
        assertTrue(draft.isNew)
        assertEquals(stack.secret, draft.secret)
        assertEquals(listOf(stack), settings.health.webhook.destinations)

        assertNull(vm.saveDestination(draft))
        assertEquals("trimmed", "https://third.example/hook", settings.health.webhook.destinations.last().url)
    }

    @Test
    fun `a saved address from before a check came in can still be saved unchanged`() {
        val legacy = Destination(id = "old", url = "https://old.example/hook?token=abc")
        val settings = FakeAppSettings(health = HealthDraft(WebhookDraft(destinations = listOf(legacy)), emptySet(), emptyMqtt()))
        val vm = HealthConnectViewModel(settings, FakeHealthOps())

        val draft = vm.destinationDraft("old").withSecret("new")
        assertNull(draft.urlProblem)
        assertNull(vm.saveDestination(draft))
        assertEquals("new", settings.health.webhook.destinations.single().secret)
    }

    @Test
    fun `headers and a secret are edited in the draft and copied from another destination`() {
        val settings = twoReceivers()
        val vm = HealthConnectViewModel(settings, FakeHealthOps())

        var draft = vm.destinationDraft("ha").withHeader(" Authorization ", " Bearer t ")
        assertEquals(mapOf("Authorization" to "Bearer t"), draft.headers)
        draft = draft.withoutHeader("Authorization").withHeadersOf("stack").withSecretOf("stack")
        assertEquals(stack.headers, draft.headers)
        assertEquals(stack.secret, draft.secret)
        assertEquals(0, settings.destinationWrites)
    }

    // ==================== A new secret on the Receive source ====================

    @Test
    fun `a secret being typed on the Receive source reaches the store once, at Save`() {
        // PreferencesManager.setDestinations runs clearWriteBackState on the secret that changed,
        // so a write per keystroke would throw the ledger away for a half typed secret.
        val settings = twoReceivers().apply { receive = ReceiveSettings(enabled = true, sourceUrl = ha.url) }
        val vm = HealthConnectViewModel(settings, FakeHealthOps())

        var draft = vm.destinationDraft("ha")
        "ha-secret-2".forEach { draft = draft.withSecret(draft.secret + it) }
        assertEquals(0, settings.destinationWrites)

        assertNull(vm.saveDestination(draft))
        assertEquals(1, settings.destinationWrites)
    }

    // ==================== Removing ====================

    @Test
    fun `removing the last destination with MQTT off is allowed`() {
        val settings = FakeAppSettings(health = HealthDraft(WebhookDraft(destinations = listOf(stack)), setOf(HealthDataType.STEPS), emptyMqtt()))
        val vm = HealthConnectViewModel(settings, FakeHealthOps())

        vm.removeDestination("stack")

        assertTrue(settings.health.webhook.destinations.isEmpty())
        assertFalse(vm.state.value.saved.hasDestination)
        assertFalse("nothing to send to", vm.state.value.canSync)
        assertEquals("what was picked stays", setOf(HealthDataType.STEPS), vm.state.value.saved.enabledTypes)
    }

    @Test
    fun `removing the Receive source forgets it on screen too`() {
        val settings = twoReceivers().apply { receive = ReceiveSettings(enabled = true, sourceUrl = ha.url) }
        val vm = HealthConnectViewModel(settings, FakeHealthOps())
        assertEquals(ha.url, vm.state.value.receive.sourceUrl)

        vm.removeDestination("ha")

        assertNull(settings.receive.sourceUrl)
        assertNull(vm.state.value.receive.sourceUrl)
    }

    @Test
    fun `Screen Time can remove its last destination too`() {
        val settings = FakeAppSettings(screenTime = ScreenTimeDraft(WebhookDraft(destinations = listOf(stack)), "4", true, emptyMqtt()))
        val vm = ScreenTimeViewModel(settings, FakeScreenTimeOps())
        vm.removeDestination("stack")
        assertTrue(settings.screenTime.webhook.destinations.isEmpty())
        assertFalse(vm.state.value.canSync)
    }

    // ==================== A schedule that never runs ====================

    @Test
    fun `no times left and no days left are stored, and the state says they never run`() {
        val settings = FakeAppSettings()
        val vm = HealthConnectViewModel(settings, FakeHealthOps())

        vm.setSchedule(ScheduleDraft(mode = SyncMode.TIMES, times = listOf(LocalTime.of(8, 0))))
        assertFalse(vm.state.value.saved.schedule.wouldNeverRun())

        vm.setSchedule(vm.state.value.saved.schedule.copy(times = emptyList()))
        assertEquals(SyncMode.TIMES, settings.health.schedule.mode)
        assertTrue(settings.health.schedule.times.isEmpty())
        assertTrue(vm.state.value.saved.schedule.wouldNeverRun())

        vm.setSchedule(ScheduleDraft(mode = SyncMode.INTERVAL, days = emptySet()))
        assertTrue(settings.health.schedule.days.isEmpty())
        assertTrue(vm.state.value.saved.schedule.wouldNeverRun())
    }

    // ==================== None that sticks ====================

    @Test
    fun `None stays None when the permissions are checked again`() = runTest {
        val settings = FakeAppSettings()
        val ops = FakeHealthOps(granted = setOf(readSteps, readWeight))
        val vm = HealthConnectViewModel(settings, ops)
        vm.refreshPermissions()
        assertEquals("a fresh install takes what it may read", setOf(HealthDataType.STEPS, HealthDataType.WEIGHT), settings.health.enabledTypes)

        vm.applyTypes(emptySet())
        vm.refreshPermissions()
        vm.refreshPermissions()

        assertTrue(settings.health.enabledTypes.isEmpty())
        assertTrue(vm.state.value.saved.enabledTypes.isEmpty())
    }

    @Test
    fun `switching the last type off sticks like None`() = runTest {
        val settings = FakeAppSettings()
        val vm = HealthConnectViewModel(settings, FakeHealthOps(granted = setOf(readSteps)))
        vm.refreshPermissions()
        vm.toggleType(HealthDataType.STEPS, false)
        vm.refreshPermissions()
        assertTrue(settings.health.enabledTypes.isEmpty())
    }

    @Test
    fun `a chosen None survives a new view model`() = runTest {
        val settings = FakeAppSettings()
        val ops = FakeHealthOps(granted = setOf(readSteps))
        HealthConnectViewModel(settings, ops).apply {
            refreshPermissions()
            applyTypes(emptySet())
        }
        HealthConnectViewModel(settings, ops).refreshPermissions()
        assertTrue(settings.health.enabledTypes.isEmpty())
    }

    @Test
    fun `Grant with nothing selected makes what is granted the selection, as the request says`() = runTest {
        val settings = FakeAppSettings()
        val ops = FakeHealthOps(granted = setOf(readSteps))
        val vm = HealthConnectViewModel(settings, ops)
        vm.refreshPermissions()
        vm.applyTypes(emptySet())

        val request = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.permissionRequests.first() }
        vm.requestAccess()
        assertTrue(readWeight in request.await())
        ops.granted = setOf(readSteps, readWeight)
        vm.refreshPermissions()

        assertEquals(setOf(HealthDataType.STEPS, HealthDataType.WEIGHT), settings.health.enabledTypes)
    }

    // ==================== Shortcuts ====================

    @Test
    fun `a shortcut sets what may go on at once, asks for the rest and adds what is granted`() = runTest {
        val settings = FakeAppSettings().apply { typesChosen = true }
        val ops = FakeHealthOps(granted = setOf(readSteps))
        val vm = HealthConnectViewModel(settings, ops)
        vm.refreshPermissions()

        val request = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { vm.permissionRequests.first() }
        vm.applyTypes(setOf(HealthDataType.STEPS, HealthDataType.WEIGHT))
        assertEquals(setOf(HealthDataType.STEPS), settings.health.enabledTypes)
        assertTrue(readWeight in request.await())
        assertFalse("only what is missing", readSteps in request.await())

        ops.granted = setOf(readSteps, readWeight)
        vm.refreshPermissions()
        assertEquals(setOf(HealthDataType.STEPS, HealthDataType.WEIGHT), settings.health.enabledTypes)
    }

    @Test
    fun `All types turns on every type once everything is granted, cycle data included`() = runTest {
        val settings = FakeAppSettings().apply { typesChosen = true }
        val ops = FakeHealthOps(granted = emptySet())
        val vm = HealthConnectViewModel(settings, ops)
        vm.refreshPermissions()

        vm.applyTypes(HealthDataType.entries.toSet())
        assertTrue(settings.health.enabledTypes.isEmpty())

        ops.granted = com.owen282000.lifedashboard.HealthPermissionRequests.readPermissions(HealthDataType.entries)
        vm.refreshPermissions()
        assertEquals(HealthDataType.entries.toSet(), settings.health.enabledTypes)
        assertTrue(HealthDataType.MENSTRUATION_FLOW in settings.health.enabledTypes)
    }

    @Test
    fun `what was refused stays off, and a later check does not bring it on`() = runTest {
        val settings = FakeAppSettings().apply { typesChosen = true }
        val ops = FakeHealthOps(granted = setOf(readSteps))
        val vm = HealthConnectViewModel(settings, ops)
        vm.refreshPermissions()
        vm.applyTypes(setOf(HealthDataType.STEPS, HealthDataType.WEIGHT))

        vm.refreshPermissions()
        assertEquals(setOf(HealthDataType.STEPS), settings.health.enabledTypes)

        ops.granted = setOf(readSteps, readWeight)
        vm.refreshPermissions()
        assertEquals(setOf(HealthDataType.STEPS), settings.health.enabledTypes)
    }

    // ==================== Only reschedule on a change ====================

    @Test
    fun `WorkManager only hears of a schedule that changed`() {
        val settings = FakeAppSettings()
        val vm = HealthConnectViewModel(settings, FakeHealthOps())
        val first = vm.state.value.saved.schedule

        vm.setSchedule(first)
        assertEquals(emptyList<LogType>(), settings.reschedules)
        assertEquals("nothing written either", emptyList<LogType>(), settings.scheduleWrites)

        vm.setSchedule(first.copy(days = setOf(DayOfWeek.MONDAY)))
        assertEquals(listOf(LogType.HEALTH_CONNECT), settings.reschedules)

        val eight = LocalTime.of(8, 0)
        val nine = LocalTime.of(9, 0)
        vm.setSchedule(vm.state.value.saved.schedule.copy(mode = SyncMode.TIMES, times = listOf(eight, nine)))
        assertEquals(2, settings.reschedules.size)
        vm.setSchedule(vm.state.value.saved.schedule.copy(times = listOf(nine, eight, eight)))
        assertEquals("order and doubles are no change", 2, settings.reschedules.size)
    }

    @Test
    fun `settings that are no schedule do not reschedule`() {
        val settings = FakeAppSettings(health = HealthDraft(WebhookDraft(destinations = listOf(stack)), setOf(HealthDataType.STEPS), emptyMqtt()))
        val vm = HealthConnectViewModel(settings, FakeHealthOps(granted = setOf(readSteps, readWeight)))

        vm.setIncludeDailyTotals(false)
        vm.toggleType(HealthDataType.STEPS, false)
        assertNull(vm.saveDestination(vm.destinationDraft("stack").withSecret("new")))
        assertNull(vm.saveDestination(vm.destinationDraft("stack").withHeader("X-Other", "v")))
        assertEquals(2, settings.destinationWrites)
        assertEquals(emptyList<LogType>(), settings.reschedules)

        assertNull(vm.saveInterval("120"))
        assertEquals(listOf(LogType.HEALTH_CONNECT), settings.reschedules)
    }

    @Test
    fun `the interval waits for its Save, refuses under fifteen and keeps the rest of the schedule`() {
        val settings = FakeAppSettings()
        val vm = ScreenTimeViewModel(settings, FakeScreenTimeOps())
        vm.setSchedule(vm.state.value.saved.schedule.copy(days = setOf(DayOfWeek.MONDAY)))
        settings.reschedules.clear()

        assertEquals(UiMessage.IntervalTooShort, vm.saveInterval("5"))
        assertEquals(UiMessage.IntervalTooShort, vm.saveInterval(""))
        assertEquals(emptyList<LogType>(), settings.reschedules)

        assertNull(vm.saveInterval("90"))
        assertEquals("90", settings.screenTime.schedule.intervalText)
        assertEquals(setOf(DayOfWeek.MONDAY), settings.screenTime.schedule.days)
        assertEquals(listOf(LogType.SCREEN_TIME), settings.reschedules)

        assertNull(vm.saveInterval("90"))
        assertEquals(1, settings.reschedules.size)
    }

    @Test
    fun `a half typed interval in the draft does not change the stored one when a day changes`() {
        val settings = FakeAppSettings()
        val vm = HealthConnectViewModel(settings, FakeHealthOps())
        assertNull(vm.saveInterval("45"))

        vm.setSchedule(vm.state.value.saved.schedule.copy(intervalText = "4", days = setOf(DayOfWeek.FRIDAY)))

        assertEquals("45", settings.health.schedule.intervalText)
        assertEquals(setOf(DayOfWeek.FRIDAY), settings.health.schedule.days)
    }

    // ==================== The shared broker ====================

    @Test
    fun `a stale tab does not put back the shared broker when it saves its own MQTT settings`() {
        val settings = FakeAppSettings(
            health = HealthDraft(WebhookDraft(), emptySet(), enabledMqtt("old.local")),
            screenTime = ScreenTimeDraft(WebhookDraft(), "4", true, enabledMqtt("old.local"))
        )
        val health = HealthConnectViewModel(settings, FakeHealthOps())
        val screenTime = ScreenTimeViewModel(settings, FakeScreenTimeOps())

        // Health has not looked since. Screen Time moves the broker.
        val moved = screenTime.mqttDraft().let { it.withActiveBroker(it.activeBroker.copy(host = "new.local")) }
        assertNull(screenTime.saveMqtt(moved))
        assertEquals("new.local", settings.broker.host)

        // Health's screen opened before that, and only changes its topic.
        val stale = health.state.value.saved.mqtt
        assertEquals("old.local", stale.sharedBroker.host)
        assertNull(health.saveMqtt(stale.copy(section = stale.section.copy(baseTopic = "health"))))

        assertEquals("new.local", settings.broker.host)
        assertEquals("health", settings.health.mqtt.section.baseTopic)
        assertEquals("the screen now shows what is stored", "new.local", health.state.value.saved.mqtt.sharedBroker.host)
    }

    @Test
    fun `saving another setting does not put back a shared broker the other tab changed`() {
        val settings = FakeAppSettings(health = HealthDraft(WebhookDraft(destinations = listOf(stack)), emptySet(), enabledMqtt("old.local")))
        val health = HealthConnectViewModel(settings, FakeHealthOps())
        settings.broker = settings.broker.copy(host = "new.local")

        assertNull(health.saveInterval("120"))
        assertNull(health.saveDestination(health.destinationDraft("stack").withSecret("new")))

        assertEquals("new.local", settings.broker.host)
        assertEquals(0, settings.brokerWrites)
    }

    @Test
    fun `a look at the other tab picks up the shared broker, and a draft on the MQTT page stays`() {
        val settings = FakeAppSettings(health = HealthDraft(WebhookDraft(), emptySet(), enabledMqtt("old.local")))
        val health = HealthConnectViewModel(settings, FakeHealthOps())

        settings.broker = settings.broker.copy(host = "new.local", port = 8883)
        health.refreshSharedSettings()
        assertEquals("new.local", health.state.value.saved.mqtt.sharedBroker.host)
        assertEquals("8883", health.state.value.saved.mqtt.portText)

        // The page's draft is its own: a reload has nothing to overwrite.
        val page = health.mqttDraft()
        val typed = page.withActiveBroker(page.activeBroker.copy(host = "typed.local"))
        settings.broker = settings.broker.copy(host = "newer.local")
        health.refreshSharedSettings()
        assertEquals("newer.local", health.state.value.saved.mqtt.sharedBroker.host)
        assertEquals("typed.local", typed.sharedBroker.host)
    }

    @Test
    fun `saving MQTT writes the shared broker when this screen changed it, and only then`() {
        val settings = FakeAppSettings(health = HealthDraft(WebhookDraft(), emptySet(), enabledMqtt()))
        val vm = HealthConnectViewModel(settings, FakeHealthOps())

        assertNull(vm.saveMqtt(vm.mqttDraft().let { it.copy(section = it.section.copy(baseTopic = "t")) }))
        assertEquals(0, settings.brokerWrites)

        assertNull(vm.saveMqtt(vm.mqttDraft().copy(portText = "8883")))
        assertEquals(1, settings.brokerWrites)
        assertEquals(8883, settings.broker.port)
    }

    @Test
    fun `MQTT on its own broker leaves the shared one alone`() {
        val settings = FakeAppSettings(health = HealthDraft(WebhookDraft(), emptySet(), enabledMqtt("shared.local")))
        val vm = HealthConnectViewModel(settings, FakeHealthOps())

        val own = vm.mqttDraft().withShared(false).let { it.withActiveBroker(it.activeBroker.copy(host = "own.local")) }
        assertNull(vm.saveMqtt(own))

        assertEquals("shared.local", settings.broker.host)
        assertEquals("own.local", settings.health.mqtt.section.ownBroker.host)
        assertEquals(0, settings.brokerWrites)
    }

    @Test
    fun `MQTT problems come back for under the field, with publishing off too`() {
        val settings = FakeAppSettings()
        val vm = HealthConnectViewModel(settings, FakeHealthOps())
        val off = vm.mqttDraft()

        assertEquals(UiMessage.MqttHostInvalid, vm.saveMqtt(off.withActiveBroker(off.activeBroker.copy(host = "bad host"))))
        assertEquals(UiMessage.MqttPortInvalid, vm.saveMqtt(off.withActiveBroker(off.activeBroker.copy(host = "ok.local")).copy(portText = "0")))
        assertEquals(UiMessage.MqttHostMissing, vm.saveMqtt(off.copy(section = off.section.copy(enabled = true))))
        assertNull("an empty host is no broker while it is off", vm.saveMqtt(off))
    }

    @Test
    fun `removing MQTT keeps the shared broker while the other section publishes through it`() {
        val settings = FakeAppSettings(
            health = HealthDraft(WebhookDraft(), emptySet(), enabledMqtt()),
            screenTime = ScreenTimeDraft(WebhookDraft(), "4", true, enabledMqtt())
        )
        val health = HealthConnectViewModel(settings, FakeHealthOps())
        val screenTime = ScreenTimeViewModel(settings, FakeScreenTimeOps())

        health.removeMqtt()
        assertFalse(settings.health.mqtt.section.enabled)
        assertEquals("broker.local", settings.broker.host)
        assertEquals(listOf(MqttSection.HEALTH), settings.clearedStatus)

        screenTime.removeMqtt()
        assertFalse(settings.screenTime.mqtt.section.enabled)
        assertEquals("", settings.broker.host)
        assertNull(settings.broker.username)
        assertNull(settings.broker.password)
        assertEquals("the port stays for one added later", 1883, settings.broker.port)
    }

    // ==================== SecretsNotReady ====================

    @Test
    fun `a refused Save comes back as its result, for a retry, and keeps the draft`() {
        val settings = twoReceivers().apply { refuseSaves = true }
        val vm = HealthConnectViewModel(settings, FakeHealthOps())
        val draft = vm.destinationDraft("ha").withSecret("rotated")

        assertEquals(UiMessage.SecretsNotReady, vm.saveDestination(draft))
        assertNull("going back discards it, so nothing waits behind the screen", vm.state.value.refused)
        assertEquals(ha, settings.health.webhook.destinations.last())

        settings.refuseSaves = false
        assertNull(vm.saveDestination(draft))
        assertEquals("rotated", settings.health.webhook.destinations.last().secret)
    }

    @Test
    fun `a refused removal is a visible state with Try again`() {
        val settings = twoReceivers().apply { refuseSaves = true }
        val vm = HealthConnectViewModel(settings, FakeHealthOps())

        vm.removeDestination("ha")
        assertEquals(UiMessage.SecretsNotReady, vm.state.value.refused)
        assertEquals("nothing changed", 2, vm.state.value.saved.webhook.destinations.size)

        vm.retryRefused()
        assertEquals("still refused", UiMessage.SecretsNotReady, vm.state.value.refused)

        settings.refuseSaves = false
        vm.retryRefused()
        assertNull(vm.state.value.refused)
        assertEquals(listOf(stack), settings.health.webhook.destinations)
    }

    @Test
    fun `a refused removal can be dismissed, and then Try again does nothing`() {
        val settings = twoReceivers().apply { refuseSaves = true }
        val vm = ScreenTimeViewModel(settings, FakeScreenTimeOps())
        settings.screenTime = settings.screenTime.copy(webhook = WebhookDraft(destinations = listOf(stack)))
        vm.reloadFromSettings()

        vm.removeDestination("stack")
        assertEquals(UiMessage.SecretsNotReady, vm.state.value.refused)
        vm.dismissRefused()
        assertNull(vm.state.value.refused)

        settings.refuseSaves = false
        vm.retryRefused()
        assertEquals(listOf(stack), settings.screenTime.webhook.destinations)
    }

    @Test
    fun `a refused MQTT removal can be tried again`() {
        val settings = FakeAppSettings(health = HealthDraft(WebhookDraft(), emptySet(), enabledMqtt())).apply { refuseSaves = true }
        val vm = HealthConnectViewModel(settings, FakeHealthOps())

        vm.removeMqtt()
        assertEquals(UiMessage.SecretsNotReady, vm.state.value.refused)
        assertTrue(settings.health.mqtt.section.enabled)

        settings.refuseSaves = false
        vm.retryRefused()
        assertFalse(settings.health.mqtt.section.enabled)
        assertNull(vm.state.value.refused)
    }

    @Test
    fun `a switch is no secret, so a refusal of the secure storage does not stop it`() {
        val settings = FakeAppSettings().apply { refuseSaves = true }
        val vm = HealthConnectViewModel(settings, FakeHealthOps(granted = setOf(readSteps)))
        vm.applyTypes(emptySet())
        vm.setIncludeDailyTotals(false)
        assertNull(vm.state.value.refused)
    }

    // ==================== What the actions read ====================

    @Test
    fun `sync, preview, export and test ping use what is stored, not a draft`() = runTest {
        val settings = FakeAppSettings(health = HealthDraft(WebhookDraft(destinations = listOf(stack)), setOf(HealthDataType.STEPS), emptyMqtt()))
        val ops = FakeHealthOps(granted = setOf(readSteps))
        val vm = HealthConnectViewModel(settings, ops)
        vm.refreshPermissions()
        // Typed on the page, not saved.
        vm.destinationDraft(null, "https://draft.example/hook")

        vm.testPing()
        assertEquals(listOf(stack), ops.pinged.single().destinations)
        vm.preview()
        vm.export()
        assertEquals(setOf(HealthDataType.STEPS), ops.previewTypes[0])
        assertEquals(setOf(HealthDataType.STEPS), ops.previewTypes[1])
        vm.syncNow()
        assertEquals(1, ops.syncs)
        assertEquals(0, settings.destinationWrites)
    }

    @Test
    fun `a test of one destination pings that one as it is stored`() = runTest {
        val settings = twoReceivers()
        val ops = FakeHealthOps()
        val vm = HealthConnectViewModel(settings, ops)
        vm.testDestination("ha")
        assertEquals(listOf(ha), ops.pinged.single().destinations)

        val screenOps = FakeScreenTimeOps()
        val screen = ScreenTimeViewModel(FakeAppSettings(screenTime = ScreenTimeDraft(WebhookDraft(destinations = listOf(stack)), "4", true, emptyMqtt())), screenOps)
        screen.testPing()
        assertEquals(listOf(stack), screenOps.pinged.single().destinations)
    }

    @Test
    fun `canSync goes by what is stored`() = runTest {
        val settings = FakeAppSettings(health = HealthDraft(WebhookDraft(), setOf(HealthDataType.STEPS), emptyMqtt()))
        val vm = HealthConnectViewModel(settings, FakeHealthOps(granted = setOf(readSteps)))
        vm.destinationDraft(null, "https://draft.example/hook")
        assertFalse(vm.state.value.canSync)
        assertNull(vm.saveDestination(vm.destinationDraft(null, "https://stored.example/hook")))
        assertTrue(vm.state.value.canSync)
    }

    @Test
    fun `keeping full payloads is applied at once`() {
        val settings = FakeAppSettings()
        val vm = HealthConnectViewModel(settings, FakeHealthOps())
        vm.setKeepFullPayloads(true)
        assertTrue(settings.fullPayloads)
        assertTrue(vm.state.value.keepFullPayloads)
    }

    // ==================== Today ====================

    private val today = LocalDate.of(2026, 11, 4)
    private fun days(vararg values: Double) = values.mapIndexed { index, value -> TodayDay(today.minusDays((values.size - 1 - index).toLong()), value) }

    @Test
    fun `Today reads only the enabled types and has their figures in a fixed order`() = runTest {
        val settings = FakeAppSettings(
            health = HealthDraft(WebhookDraft(), setOf(HealthDataType.WEIGHT, HealthDataType.STEPS, HealthDataType.MENSTRUATION_FLOW), emptyMqtt())
        ).apply { recordsToday = 12 }
        val ops = FakeHealthOps().apply {
            today = mapOf(TodayKind.STEPS to days(100.0, 200.0), TodayKind.WEIGHT to days(80.5), TodayKind.SLEEP to days(400.0))
        }
        val vm = HealthConnectViewModel(settings, ops)
        assertFalse(vm.state.value.today.hasLoaded)

        vm.refreshToday()

        val state = vm.state.value.today
        assertEquals(setOf(TodayKind.STEPS, TodayKind.WEIGHT), ops.todayAsked.single())
        assertEquals(listOf(TodayKind.STEPS, TodayKind.WEIGHT), state.stats.map { it.kind })
        assertEquals(200.0, state.stats.first().latest.value, 0.0)
        assertEquals(today, state.stats.first().latest.date)
        assertEquals(listOf(100.0, 200.0), state.stats.first().values)
        assertEquals(200.0, state.stats.first().valueOn(today)!!, 0.0)
        assertNull(state.stats.first().valueOn(today.plusDays(1)))
        assertEquals(12, state.recordsToday)
        assertTrue(state.hasLoaded)
    }

    @Test
    fun `Today with nothing selected asks Health Connect nothing and has loaded`() = runTest {
        val ops = FakeHealthOps()
        val vm = HealthConnectViewModel(FakeAppSettings(), ops)
        vm.refreshToday()
        assertTrue(ops.todayAsked.isEmpty())
        assertTrue(vm.state.value.today.hasLoaded)
        assertTrue(vm.state.value.today.stats.isEmpty())
    }

    @Test
    fun `a figure that cannot be read now keeps its last value, and one without data drops off`() = runTest {
        val settings = FakeAppSettings(health = HealthDraft(WebhookDraft(), setOf(HealthDataType.STEPS, HealthDataType.SLEEP), emptyMqtt()))
        val ops = FakeHealthOps().apply { today = mapOf(TodayKind.STEPS to days(100.0), TodayKind.SLEEP to days(420.0)) }
        val vm = HealthConnectViewModel(settings, ops)
        vm.refreshToday()
        assertEquals(2, vm.state.value.today.stats.size)

        ops.today = mapOf(TodayKind.STEPS to null, TodayKind.SLEEP to emptyList())
        vm.refreshToday()

        assertEquals(listOf(TodayKind.STEPS), vm.state.value.today.stats.map { it.kind })
        assertEquals(100.0, vm.state.value.today.stats.single().latest.value, 0.0)
    }
}
