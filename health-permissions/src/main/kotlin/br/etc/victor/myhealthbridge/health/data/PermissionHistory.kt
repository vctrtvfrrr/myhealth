package br.etc.victor.myhealthbridge.health.data

import android.content.Context
import br.etc.victor.myhealthbridge.health.PermissionHistoryStore

/** Opens the Room history, so that callers depend on the store contract instead of on Room. */
fun permissionHistoryStore(context: Context): PermissionHistoryStore =
    RoomPermissionHistoryStore(HealthPermissionDatabase.open(context).permissionRecords())
