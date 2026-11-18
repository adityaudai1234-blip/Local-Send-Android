package com.example.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

class QuickSendWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetUpdateHelper.computeSnapshot(context)
        provideContent {
            GlanceTheme {
                QuickSendContent(context, snapshot)
            }
        }
    }

    @Composable
    private fun QuickSendContent(context: Context, snapshot: WidgetSnapshot) {
        val openPicker = actionStartActivity(
            WidgetUpdateHelper.createLaunchIntent(context, WidgetUpdateHelper.ACTION_OPEN_PICKER)
        )
        val quickSend = actionStartActivity(
            WidgetUpdateHelper.createLaunchIntent(
                context,
                WidgetUpdateHelper.ACTION_QUICK_SEND,
                snapshot.quickSendTargetId
            )
        )

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .cornerRadius(16.dp)
                .padding(14.dp)
                .clickable(quickSend),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Quick Send",
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = snapshot.quickSendTargetName?.let { "To $it" } ?: "Pick a device & files",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = GlanceModifier.height(10.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    text = if (snapshot.quickSendTargetName != null) "Quick Send" else "Send Files",
                    onClick = quickSend
                )
                Spacer(modifier = GlanceModifier.width(8.dp))
                Button(
                    text = "Pick",
                    onClick = openPicker
                )
            }
        }
    }
}

class QuickSendWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickSendWidget()
}
