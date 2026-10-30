package com.joelj.workout

import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.LocalDate

/** Collection compatibility for Android 8–11. Newer Android receives collection items directly. */
class WorkoutRowsService:RemoteViewsService() {
    override fun onGetViewFactory(intent:Intent):RemoteViewsFactory = object:RemoteViewsFactory {
        private var rows=emptyList<WorkoutRow>()
        private val date=runCatching{LocalDate.parse(intent.getStringExtra("date"))}.getOrDefault(LocalDate.now())
        override fun onCreate()=onDataSetChanged()
        override fun onDataSetChanged(){rows=runCatching{WidgetContent.forDate(applicationContext,PlanStore(applicationContext).active(),date).rows}.getOrDefault(emptyList())}
        override fun onDestroy(){rows=emptyList()}
        override fun getCount()=rows.size
        override fun getViewAt(position:Int):RemoteViews?=rows.getOrNull(position)?.let { row ->
            WidgetLayout.row(applicationContext,row).apply{
                setOnClickFillInIntent(if(row.kind==RowKind.FACT)R.id.row_title else R.id.row_root,Intent().putExtra("date",date.toString()))
            }
        }
        override fun getLoadingView():RemoteViews?=null
        override fun getViewTypeCount()=2
        override fun getItemId(position:Int)=position.toLong()
        override fun hasStableIds()=true
    }
}
