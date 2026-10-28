package com.oxygenupdater.internal.settings

/**
 * Added in v5.4.0
 *
 * @see [com.oxygenupdater.BuildConfig.VERSION_CODE]
 */
const val KeyVersionCode = "version_code"

// Settings properties
const val KeyDevice = "device"
const val KeyDeviceId = "device_id"
const val KeyUpdateMethod = "update_method"
const val KeyUpdateMethodId = "update_method_id"

const val KeyThemeId = "theme_id"

const val KeyAdvancedMode = "advanced_mode"
const val KeySetupDone = "setup_done"
const val KeySqlToRoomMigrationDone = "sql_to_room_migration_done"
const val KeyIgnoreUnsupportedDeviceWarnings = "ignore_unsupported_device_warnings"
const val KeyIgnoreIncorrectDeviceWarnings = "ignore_incorrect_device_warnings"
const val KeyIgnoreNotificationPermissionSheet = "ignore_notification_permission_sheet"
const val KeyDownloadBytesDone = "download_bytes_done"
const val KeyDownloadChunks = "download_chunks"

/** Forced number of parallel download connections; `0` (default) picks automatically. Shown only in advanced mode. */
const val KeyDownloadConnections = "download_connections"

/**
 * Value cannot be changed - is from older version where it was called 'upload_logs'
 */
const val KeyShareAnalyticsAndLogs = "upload_logs"

/**
 * Note: contribution was a feature in app v2.7.0 - v5.8.3. v5.9.0 removed it, as it wasn't useful anymore.
 * Post Oppo-merger, OnePlus phones used either Google OTA (doesn't save files anywhere), or Component OTA
 * (saves in a directory accessible only via root). URLs in the latter case couldn't be constructed via names alone.
 *
 * It was brought back in app v5.11.0, because we failed to realize that URLs were saved in a SQLite database.
 */
const val KeyContribute = "contribute"
const val KeyContributionCount = "contribution_count"

const val KeyFlexibleAppUpdateIgnoreCount = "flexibleAppUpdateIgnoreCount"

// Notifications properties
const val KeyFirebaseToken = "firebase_token"
const val KeyFirebaseInstallationId = "firebase_installation_id"

/**
 * Value must not be changed for legacy reasons
 *
 * @see [com.oxygenupdater.utils.FcmUtils.currentFullTopic]
 */
const val KeyNotificationFullTopic = "notification_topic"

/**
 * Since v6.2.0
 *
 * @see [com.oxygenupdater.utils.FcmUtils.currentDeviceTopic]
 */
const val KeyNotificationDeviceTopic = "notification_topic_device"

const val KeyNotificationDelayInSeconds = "notification_delay_in_seconds"

// IAB properties
const val KeyAdFree = "34ejrtgalsJKDf;awljker;2k3jrpwosKjdfpio24uj3tp3oiwfjdscPOKj"
