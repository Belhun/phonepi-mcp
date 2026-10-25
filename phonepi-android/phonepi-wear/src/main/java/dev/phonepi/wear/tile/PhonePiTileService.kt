package dev.phonepi.wear.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.phonepi.wear.AskAssistantActivity
import dev.phonepi.wear.ConfirmActionActivity
import dev.phonepi.wear.NextAlarmActivity

class PhonePiTileService : TileService() {
    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val layout = tileLayout(packageName)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(layout),
            )
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()
        return Futures.immediateFuture(resources)
    }

    companion object {
        private const val RESOURCES_VERSION = "1"

        fun requestUpdate(context: Context) {
            TileService.getUpdater(context).requestUpdate(PhonePiTileService::class.java)
        }

        internal fun tileLayout(packageName: String): LayoutElementBuilders.LayoutElement {
            val ask = cell("Ask assistant", launch(packageName, AskAssistantActivity::class.java.name))
            val alarm = cell("Next alarm", launch(packageName, NextAlarmActivity::class.java.name))
            val quiet = cell(
                "Quiet 1h",
                launch(
                    packageName,
                    ConfirmActionActivity::class.java.name,
                    ConfirmActionActivity.EXTRA_KIND,
                    ConfirmActionActivity.KIND_QUIET,
                ),
            )
            val imOk = cell(
                "I'm OK",
                launch(
                    packageName,
                    ConfirmActionActivity::class.java.name,
                    ConfirmActionActivity.EXTRA_KIND,
                    ConfirmActionActivity.KIND_IM_OK,
                ),
            )
            return LayoutElementBuilders.Column.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(row(ask, alarm))
                .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
                .addContent(row(quiet, imOk))
                .build()
        }

        private fun row(
            left: LayoutElementBuilders.LayoutElement,
            right: LayoutElementBuilders.LayoutElement,
        ): LayoutElementBuilders.LayoutElement =
            LayoutElementBuilders.Row.Builder()
                .setWidth(expand())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(left)
                .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
                .addContent(right)
                .build()

        private fun cell(
            label: String,
            clickable: ModifiersBuilders.Clickable,
        ): LayoutElementBuilders.LayoutElement =
            LayoutElementBuilders.Box.Builder()
                .setWidth(dp(76f))
                .setHeight(dp(52f))
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setClickable(clickable)
                        .setBackground(
                            ModifiersBuilders.Background.Builder()
                                .setColor(argb(0xFF1B3A4B.toInt()))
                                .setCorner(
                                    ModifiersBuilders.Corner.Builder().setRadius(dp(12f)).build(),
                                )
                                .build(),
                        )
                        .setPadding(
                            ModifiersBuilders.Padding.Builder().setAll(dp(6f)).build(),
                        )
                        .build(),
                )
                .addContent(
                    LayoutElementBuilders.Text.Builder()
                        .setText(label)
                        .setMaxLines(2)
                        .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
                        .build(),
                )
                .build()

        private fun launch(
            packageName: String,
            className: String,
            extraKey: String? = null,
            extraVal: String? = null,
        ): ModifiersBuilders.Clickable {
            val activity = ActionBuilders.AndroidActivity.Builder()
                .setPackageName(packageName)
                .setClassName(className)
            if (extraKey != null && extraVal != null) {
                activity.addKeyToExtraMapping(
                    extraKey,
                    ActionBuilders.AndroidStringExtra.Builder().setValue(extraVal).build(),
                )
            }
            return ModifiersBuilders.Clickable.Builder()
                .setId(className.substringAfterLast('.'))
                .setOnClick(
                    ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(activity.build())
                        .build(),
                )
                .build()
        }
    }
}
