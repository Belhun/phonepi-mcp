package dev.phonepi.companion.telephony

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

data class SmsSubscriptionInfo(
    val subscriptionId: Int,
    val simSlot: Int,
    val displayName: String,
    val carrierName: String?,
    val isDefaultSms: Boolean,
)

object SmsSubscriptionHelper {
    fun list(context: Context): List<SmsSubscriptionInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return emptyList()
        val manager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val defaultId = SubscriptionManager.getDefaultSmsSubscriptionId()
        return manager.activeSubscriptionInfoList?.map { info ->
            SmsSubscriptionInfo(
                subscriptionId = info.subscriptionId,
                simSlot = info.simSlotIndex,
                displayName = info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}",
                carrierName = info.carrierName?.toString(),
                isDefaultSms = info.subscriptionId == defaultId,
            )
        } ?: emptyList()
    }

    fun resolveSubscriptionId(
        context: Context,
        subscriptionId: Int?,
        simSlot: Int?,
    ): Int? {
        subscriptionId?.let { return it }
        simSlot?.let { slot ->
            return list(context).firstOrNull { it.simSlot == slot }?.subscriptionId
        }
        return null
    }

    fun smsManagerForSubscription(subscriptionId: Int): SmsManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
        @Suppress("DEPRECATION")
        return SmsManager.getDefault()
    }

    fun subscriptionInfo(context: Context, subscriptionId: Int): SmsSubscriptionInfo? =
        list(context).firstOrNull { it.subscriptionId == subscriptionId }
}
