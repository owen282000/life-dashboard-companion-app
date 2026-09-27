package com.owen282000.lifedashboard

import androidx.health.connect.client.permission.HealthPermission

/**
 * What each Health Connect permission request asks for.
 *
 * A grant asks for what the setup uses and nothing more: the read permission of every enabled
 * type, reading in the background, and history only from the backfill that needs it. Asking for all 35 at once, reproductive data included,
 * scared off a user who had picked 8 types in the wizard.
 *
 * Free of Android types apart from the permission names, so the sets are unit tested on the JVM.
 */
object HealthPermissionRequests {

    fun readPermissions(types: Collection<HealthDataType>): Set<String> =
        types.map { HealthPermission.getReadPermission(it.recordClass) }.toSet()

    /**
     * The Grant button, and a sync that finds nothing granted.
     *
     * No enabled type is the wizard's "choose later": every read permission is offered then,
     * and what the user grants becomes the selection (HealthConnectViewModel.refreshPermissions).
     *
     * Background reading always goes along: every sync but Sync Now runs in a worker, the
     * schedule and also the quick settings tile and the automation broadcast, and nothing
     * would ask again once reads are granted.
     */
    fun forGrant(enabledTypes: Set<HealthDataType>): Set<String> =
        readPermissions(enabledTypes.ifEmpty { HealthDataType.entries.toSet() }) +
            HealthConnectManager.BACKGROUND_PERMISSION

    /**
     * History access, asked for from the backfill dialog: without it Health Connect shows only
     * the 30 days before the first grant, so a 90 or 365 day backfill comes back short (#39).
     * The enabled types' reads go along, so the request is never the extra permission on its
     * own; Health Connect skips the ones already granted.
     */
    fun forHistory(enabledTypes: Set<HealthDataType>): Set<String> =
        readPermissions(enabledTypes) + HealthConnectManager.HISTORY_PERMISSION
}
