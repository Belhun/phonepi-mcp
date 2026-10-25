package dev.phonepi.companion.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object HealthPermissions {
    val READ: Set<String> = buildSet {
        add(HealthPermission.getReadPermission(SleepSessionRecord::class))
        add(HealthPermission.getReadPermission(StepsRecord::class))
        add(HealthPermission.getReadPermission(HeartRateRecord::class))
        add(HealthPermission.getReadPermission(RestingHeartRateRecord::class))
        add(HealthPermission.getReadPermission(ExerciseSessionRecord::class))
        add(HealthPermission.getReadPermission(OxygenSaturationRecord::class))
        add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
    }

    fun sdkAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)

    suspend fun grantedFlags(context: Context): HealthPermissionFlags {
        if (!sdkAvailable(context)) {
            return HealthPermissionFlags(
                sleep = false,
                steps = false,
                heartRate = false,
                restingHeartRate = false,
                exercise = false,
                oxygenSaturation = false,
                background = false,
                history = false,
            )
        }
        val granted = withContext(Dispatchers.IO) {
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        }
        return HealthPermissionFlags(
            sleep = HealthPermission.getReadPermission(SleepSessionRecord::class) in granted,
            steps = HealthPermission.getReadPermission(StepsRecord::class) in granted,
            heartRate = HealthPermission.getReadPermission(HeartRateRecord::class) in granted,
            restingHeartRate = HealthPermission.getReadPermission(RestingHeartRateRecord::class) in granted,
            exercise = HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted,
            oxygenSaturation = HealthPermission.getReadPermission(OxygenSaturationRecord::class) in granted,
            background = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted,
            history = false,
        )
    }

    fun coreReadsGranted(flags: HealthPermissionFlags): Boolean =
        flags.sleep || flags.steps || flags.heartRate || flags.restingHeartRate ||
            flags.exercise || flags.oxygenSaturation
}
