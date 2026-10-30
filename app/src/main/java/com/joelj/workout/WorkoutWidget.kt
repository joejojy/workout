package com.joelj.workout

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class WorkoutWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { update(context, manager, it) }; scheduleMidnight(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = update(context, manager, id)
    override fun onEnabled(context: Context) = scheduleMidnight(context)
    override fun onDeleted(context:Context,ids:IntArray){ids.forEach{WidgetPreferences.remove(context,it)}}
    override fun onDisabled(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(midnightIntent(context))
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in setOf(MIDNIGHT, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) refresh(context)
    }
    companion object {
        private const val MIDNIGHT = "com.joelj.workout.MIDNIGHT"
        private val weekDays = intArrayOf(R.id.day_1,R.id.day_2,R.id.day_3,R.id.day_4,R.id.day_5,R.id.day_6,R.id.day_7)
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context)
            m.getAppWidgetIds(ComponentName(context, WorkoutWidget::class.java)).forEach { update(context, m, it) }
            scheduleMidnight(context)
        }
        private fun midnightIntent(context: Context) = PendingIntent.getBroadcast(context, 44,
            Intent(context, WorkoutWidget::class.java).setAction(MIDNIGHT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        private fun scheduleMidnight(context: Context) {
            val m = AppWidgetManager.getInstance(context)
            if (m.getAppWidgetIds(ComponentName(context, WorkoutWidget::class.java)).isEmpty()) return
            val next = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, next, midnightIntent(context))
        }
        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val options = manager.getAppWidgetOptions(id)
            val plan = runCatching { PlanStore(context).active() }.getOrNull()
            val date = LocalDate.now()
            if (Build.VERSION.SDK_INT >= 31) {
                @Suppress("DEPRECATION")
                val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                if (!sizes.isNullOrEmpty()) {
                    val map = sizes.distinct().take(16).associateWith { WidgetLayout.compose(context, plan, date, it.width, it.height,id,WidgetPreferences.get(context,id)).remote }
                    manager.updateAppWidget(id, RemoteViews(map)); return
                }
            }
            val minWidth=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,280).toFloat()
            val minHeight=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,280).toFloat()
            val maxWidth=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,minWidth.toInt()).toFloat()
            val maxHeight=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,minHeight.toInt()).toFloat()
            val density=WidgetPreferences.get(context,id)
            val portrait=WidgetLayout.compose(context,plan,date,minWidth,maxHeight,id,density).remote
            val landscape=WidgetLayout.compose(context,plan,date,maxWidth,minHeight,id,density).remote
            manager.updateAppWidget(id,RemoteViews(landscape,portrait))
            if(Build.VERSION.SDK_INT<31){@Suppress("DEPRECATION") manager.notifyAppWidgetViewDataChanged(id,R.id.workout_list)}
        }
        fun render(context: Context, plan: Plan?, date: LocalDate, width: Float, height: Float): RemoteViews = WidgetLayout.compose(context,plan,date,width,height).remote
        fun open(context: Context, date: LocalDate, mutable:Boolean=false) = PendingIntent.getActivity(context, date.toEpochDay().toInt()*2+(if(mutable)1 else 0),
            Intent(context, MainActivity::class.java).putExtra("date", date.toString()).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or (if(mutable) { if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0 } else PendingIntent.FLAG_IMMUTABLE))
    }
}


