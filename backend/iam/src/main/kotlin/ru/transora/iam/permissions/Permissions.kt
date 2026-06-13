package ru.transora.iam.permissions

object Permissions {
    const val TICKETS_SELL = "tickets:sell"
    const val TICKETS_REFUND = "tickets:refund"
    const val TICKETS_VIEW = "tickets:view"
    const val SHIFTS_MANAGE = "shifts:manage"
    const val SCHEDULE_VIEW = "schedule:view"
    const val SCHEDULE_CREATE = "schedule:create"
    const val SCHEDULE_EDIT = "schedule:edit"
    const val SCHEDULE_CANCEL_TRIP = "schedule:cancel_trip"
    const val INVENTORY_VIEW = "inventory:view"
    const val INVENTORY_TOGGLE = "inventory:toggle_restriction"
    const val INVENTORY_MANUAL_BLOCK = "inventory:manual_block"
    const val INVENTORY_TRANSIT_GATE = "inventory:open_transit_gate"
    const val INVENTORY_CLOSE_TRANSIT_GATE = "inventory:close_transit_gate"
    const val DOCUMENTS_PRINT = "documents:print"
    const val DOCUMENTS_VIEW_MANIFEST = "documents:view_manifest"
    const val BOARDING_SCAN = "boarding:scan"
    const val BOARDING_VIEW_STATS = "boarding:view_stats"
    const val ANNOUNCEMENTS_MANAGE = "announcements:manage_queue"
    const val USERS_VIEW = "users:view"
    const val USERS_CREATE = "users:create"
    const val USERS_EDIT = "users:edit"
    const val USERS_DEACTIVATE = "users:deactivate"
    const val REPORTS_VIEW_STATION = "reports:view_station"
    const val REPORTS_VIEW_NETWORK = "reports:view_network"
    const val SETTINGS_MANAGE_TARIFFS = "settings:manage_tariffs"
}

object RoleCodes {
    const val SYSTEM_ADMIN = "SYSTEM_ADMIN"
    const val STATION_ADMIN = "STATION_ADMIN"
    const val DISPATCHER = "DISPATCHER"
    const val CASHIER = "CASHIER"
    const val INSPECTOR = "INSPECTOR"
    const val STATION_AGENT = "STATION_AGENT"
}

object RolePermissionMatrix {
    val allPermissions: Set<String> = setOf(
        Permissions.TICKETS_SELL, Permissions.TICKETS_REFUND, Permissions.TICKETS_VIEW,
        Permissions.SHIFTS_MANAGE, Permissions.SCHEDULE_VIEW, Permissions.SCHEDULE_CREATE,
        Permissions.SCHEDULE_EDIT, Permissions.SCHEDULE_CANCEL_TRIP,
        Permissions.INVENTORY_VIEW, Permissions.INVENTORY_TOGGLE, Permissions.INVENTORY_MANUAL_BLOCK,
        Permissions.INVENTORY_TRANSIT_GATE, Permissions.INVENTORY_CLOSE_TRANSIT_GATE, Permissions.DOCUMENTS_PRINT, Permissions.DOCUMENTS_VIEW_MANIFEST,
        Permissions.BOARDING_SCAN, Permissions.BOARDING_VIEW_STATS, Permissions.ANNOUNCEMENTS_MANAGE,
        Permissions.USERS_VIEW, Permissions.USERS_CREATE, Permissions.USERS_EDIT,
        Permissions.USERS_DEACTIVATE, Permissions.REPORTS_VIEW_STATION,
        Permissions.REPORTS_VIEW_NETWORK, Permissions.SETTINGS_MANAGE_TARIFFS,
    )

    fun permissionsFor(roleCode: String): Set<String> = when (roleCode) {
        RoleCodes.SYSTEM_ADMIN -> allPermissions
        RoleCodes.STATION_ADMIN -> setOf(
            Permissions.USERS_VIEW, Permissions.USERS_CREATE, Permissions.USERS_EDIT,
            Permissions.USERS_DEACTIVATE, Permissions.SCHEDULE_VIEW,
            Permissions.TICKETS_VIEW, Permissions.SHIFTS_MANAGE, Permissions.INVENTORY_VIEW,
            Permissions.DOCUMENTS_VIEW_MANIFEST, Permissions.BOARDING_VIEW_STATS,
            Permissions.REPORTS_VIEW_STATION,
        )
        RoleCodes.DISPATCHER -> setOf(
            Permissions.SCHEDULE_VIEW, Permissions.SCHEDULE_CREATE, Permissions.SCHEDULE_EDIT,
            Permissions.SCHEDULE_CANCEL_TRIP, Permissions.INVENTORY_VIEW, Permissions.INVENTORY_TOGGLE,
            Permissions.INVENTORY_MANUAL_BLOCK, Permissions.INVENTORY_TRANSIT_GATE,
            Permissions.INVENTORY_CLOSE_TRANSIT_GATE,
            Permissions.TICKETS_VIEW, Permissions.DOCUMENTS_VIEW_MANIFEST, Permissions.DOCUMENTS_PRINT,
            Permissions.BOARDING_VIEW_STATS, Permissions.ANNOUNCEMENTS_MANAGE, Permissions.REPORTS_VIEW_STATION,
        )
        RoleCodes.CASHIER -> setOf(
            Permissions.TICKETS_SELL, Permissions.TICKETS_REFUND, Permissions.TICKETS_VIEW,
            Permissions.SHIFTS_MANAGE, Permissions.SCHEDULE_VIEW, Permissions.INVENTORY_VIEW,
            Permissions.DOCUMENTS_PRINT, Permissions.BOARDING_VIEW_STATS,
        )
        RoleCodes.INSPECTOR -> setOf(
            Permissions.SCHEDULE_VIEW, Permissions.BOARDING_SCAN, Permissions.BOARDING_VIEW_STATS,
        )
        RoleCodes.STATION_AGENT -> setOf(Permissions.BOARDING_SCAN)
        else -> emptySet()
    }
}
