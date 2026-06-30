package com.pedometer.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.pedometer.health.StepProviderReader
import com.pedometer.health.UserProfile

class StepWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = StepProviderReader.readToday(context)
        val profile = UserProfile.load(context)
        val steps = today?.totalSteps ?: 0
        val goal = profile.stepGoal

        provideContent {
            StepWidgetContent(steps = steps, goal = goal)
        }
    }
}

@Composable
private fun StepWidgetContent(steps: Int, goal: Int) {
    val green = ColorProvider(android.graphics.Color.parseColor("#FF4CAF50"))
    val white = ColorProvider(android.graphics.Color.WHITE)
    val gray = ColorProvider(android.graphics.Color.parseColor("#FF888888"))

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(android.graphics.Color.parseColor("#FF1A1A1A")))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "$steps",
                style = TextStyle(
                    color = green,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = "/ $goal шагов",
                style = TextStyle(color = gray, fontSize = 12.sp),
            )
        }
    }
}

class StepWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepWidget()
}
