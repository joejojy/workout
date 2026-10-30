package com.joelj.workout

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.util.TypedValue
import android.text.TextPaint
import android.graphics.Typeface
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

enum class WidgetShape { COMPACT, WIDE_SHORT, NARROW_TALL, WIDE_TALL }
enum class WidgetDensity { AUTO, GLANCE, DETAILED }
enum class RowKind { FACT, EXERCISE, NOTE }
data class WorkoutRow(val label:String,val value:String="",val kind:RowKind=RowKind.NOTE) {
    val text get()=if(value.isBlank())label else "$label: $value"
}
object WidgetSizing {
    fun shape(width:Float,height:Float,fontScale:Float=1f):WidgetShape {
        val scale=fontScale.coerceAtLeast(1f)
        return when {
            width/scale>=260 && height/scale<245 -> WidgetShape.WIDE_SHORT
            width/scale<240 && height/scale>=260 -> WidgetShape.NARROW_TALL
            width/scale>=240 && height/scale>=245 -> WidgetShape.WIDE_TALL
            else -> WidgetShape.COMPACT
        }
    }
}
object WidgetPreferences {
    fun get(context:Context,id:Int)=runCatching { WidgetDensity.valueOf(context.getSharedPreferences("widgets",0).getString("density_$id","AUTO")!!) }.getOrDefault(WidgetDensity.AUTO)
    fun set(context:Context,id:Int,density:WidgetDensity) { context.getSharedPreferences("widgets",0).edit().putString("density_$id",density.name).apply() }
    fun remove(context:Context,id:Int) { context.getSharedPreferences("widgets",0).edit().remove("density_$id").apply() }
}
data class WidgetContent(val title:String,val target:String,val category:String,val badge:String,val next:String,val rows:List<WorkoutRow>,val session:Session?) {
    companion object {
        fun forDate(context:Context,plan:Plan?,date:LocalDate):WidgetContent {
            val day=date.dayOfWeek.getDisplayName(TextStyle.SHORT,Locale.getDefault())
            if(plan==null)return WidgetContent("Import a plan","Running + strength","Workout",day,"Choose a PDF in Workout",emptyList(),null)
            val week=plan.weekAt(date)
            val sessions=plan.on(date)
            val store=PlanStore(context)
            val session=sessions.firstOrNull { it.kind!=Kind.REST&&!store.isComplete(date,it) }?:sessions.firstOrNull{it.kind!=Kind.REST}?:sessions.firstOrNull()
            val next=plan.nextAfter(date)?.let{(d,s)->"Next: ${d.dayOfWeek.getDisplayName(TextStyle.SHORT,Locale.getDefault())} · ${s.title.substringBefore('/').trim()}"}?:"End of plan"
            val badge=if(week in 1..plan.weeks)"W$week · ${day.uppercase()}" else day.uppercase()
            if(plan.stateAt(date)==PlanDayState.UPCOMING)return WidgetContent("Plan starts soon",plan.firstDate?.format(DateTimeFormatter.ofPattern("EEE, d MMM")).orEmpty(),"Upcoming",badge,next,emptyList(),null)
            if(plan.stateAt(date)==PlanDayState.FINISHED)return WidgetContent("Plan complete","Ready for your next plan","Workout",badge,"Import your next plan",emptyList(),null)
            if(plan.stateAt(date)==PlanDayState.UNSPECIFIED)return WidgetContent("No prescription for this date",plan.calendar?.label?:"Check your plan","Workout",badge,next,emptyList(),null)
            if(session==null)return WidgetContent("No session scheduled","Check your weekly plan","Workout",badge,next,emptyList(),null)
            val category=when(session.kind){Kind.RUN->"Running";Kind.STRENGTH->"Strength";Kind.REST->"Recovery";Kind.OTHER->"Workout"} + if(sessions.size>1)" · ${sessions.size} sessions today" else ""
            val otherSessions=sessions.filter{it.id!=session.id}.map{other->
                WorkoutRow("Also today · ${when(other.kind){Kind.RUN->"Running";Kind.STRENGTH->"Strength";Kind.REST->"Recovery";Kind.OTHER->"Workout"}}", listOf(other.title,other.target).filter{it.isNotBlank()}.joinToString(" · "))
            }
            return WidgetContent((if(store.isComplete(date,session))"✓ " else "")+session.title,session.target,category,badge,next,rows(session)+otherSessions,session)
        }
        fun rows(s:Session):List<WorkoutRow> {
            if(s.kind==Kind.STRENGTH) {
                val exercises=s.exercises
                return exercises.map{WorkoutRow(it.substringBefore(':').trim(),it.substringAfter(':').trim(),RowKind.EXERCISE)}+
                    s.details.filterNot{it in exercises||it.startsWith("Session notes:")&&it.substringAfter(':').trim().equals(s.title.trim(),true)}.map{WorkoutRow(it)}
            }
            if(s.kind==Kind.REST)return s.details.filterNot{it.trim().equals(s.title.trim(),true)||it.trim().equals(s.target.trim(),true)}.map{WorkoutRow(it)}
            val facts=s.metrics.toMutableList()
            // Older saved imports have timer settings in the source text, not quick facts.
            val timer=s.details.firstOrNull{it.startsWith("Interval timer:")}
            if(timer!=null) {
                listOf("Prepare" to "preparation", "Timer round" to "round", "Recovery" to "recovery", "Rounds" to "rounds").forEach{(label,key)->
                    val value=if(key=="rounds")Regex("(\\d+) rounds").find(timer)?.groupValues?.get(1) else Regex("$key (\\d{1,2}:\\d{2})").find(timer)?.groupValues?.get(1)
                    if(value!=null&&facts.none{it.label.equals(label,true)})facts+=Metric(label,value)
                }
            }
            val estimate=s.details.firstOrNull{it.startsWith("Estimated total:")}
            if(estimate!=null&&facts.none{it.label=="Est. total"})facts+=Metric("Est. total",estimate.substringAfter(':').trim())
            val distinctFacts=facts.distinctBy{it.label.lowercase()}.filterNot{(it.label.equals("Distance",true)||it.label.equals("Target",true))&&s.target.contains(it.value)}
            val notes=s.details.filterNot { text ->
                text.startsWith("Workout:") || (text.startsWith("Interval timer:")&&facts.any{it.label=="Timer round"}) ||
                    (text.startsWith("Estimated total:")&&facts.any{it.label=="Est. total"}) ||
                    facts.any{text=="${it.label}: ${it.value}"} ||
                    ((text.startsWith("Distance:")||text.startsWith("Target:"))&&s.target.contains(text.substringAfter(':').trim()))
            }
            return distinctFacts.map{WorkoutRow(it.label,it.value,RowKind.FACT)}+notes.map{WorkoutRow(it)}
        }
    }
}

data class WidgetComposition(
    val remote:RemoteViews,val shape:WidgetShape,val rows:List<WorkoutRow>,val visibleRows:List<WorkoutRow>,
    val scrolling:Boolean,val omitted:Int,val baseHeight:Int,val bodyRoom:Int,val layoutId:Int,
    val agendaDates:List<LocalDate> = emptyList()
)

object WidgetLayout {
    private val days=intArrayOf(R.id.day_1,R.id.day_2,R.id.day_3,R.id.day_4,R.id.day_5,R.id.day_6,R.id.day_7)
    fun px(context:Context,dp:Float)=(dp*context.resources.displayMetrics.density).toInt()
    fun row(context:Context,data:WorkoutRow):RemoteViews {
        val r=RemoteViews(context.packageName,if(data.kind==RowKind.FACT)R.layout.widget_fact else R.layout.widget_row)
        r.setTextViewText(R.id.row_title,if(data.kind==RowKind.FACT)data.text else data.label)
        if(data.kind!=RowKind.FACT) {
            r.setTextViewText(R.id.row_value,data.value)
            r.setViewVisibility(R.id.row_value,if(data.value.isBlank())View.GONE else View.VISIBLE)
            r.setContentDescription(R.id.row_root,data.text)
        }
        return r
    }
    fun measureHeight(context:Context,remote:RemoteViews,width:Int):Int {
        val view=remote.apply(context,FrameLayout(context))
        view.measure(View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        return view.measuredHeight
    }
    fun compose(context:Context,plan:Plan?,date:LocalDate,width:Float,height:Float,widgetId:Int=-1,density:WidgetDensity=WidgetDensity.AUTO,bindCollection:Boolean=true):WidgetComposition {
        val content=WidgetContent.forDate(context,plan,date)
        val font=context.resources.configuration.fontScale.coerceAtLeast(1f)
        var shape=WidgetSizing.shape(width,height,font)
        val w=px(context,width); val h=px(context,height)
        var pad=if(width<220)10 else 12
        var showHeader=true
        var showCategory=height/font>=400
        var showNext=height/font>=350
        var title=content.title
        var titleSize=if(shape==WidgetShape.COMPACT||shape==WidgetShape.NARROW_TALL)19f else 22f
        var showWeek=shape==WidgetShape.WIDE_TALL&&width>=365&&height/font>=550
        fun layoutId()=when(shape){WidgetShape.COMPACT->R.layout.widget_workout;WidgetShape.WIDE_SHORT->R.layout.widget_wide;WidgetShape.NARROW_TALL->R.layout.widget_tall;WidgetShape.WIDE_TALL->R.layout.widget_detailed}
        fun base():RemoteViews {
            val r=RemoteViews(context.packageName,layoutId())
            // Launchers reapply actions to existing views. Reset every dynamic container explicitly.
            r.setViewVisibility(R.id.inline_rows,View.GONE)
            r.removeAllViews(R.id.inline_rows)
            r.setViewVisibility(R.id.list_container,View.GONE)
            r.setInt(R.id.widget_root,"setMinimumHeight",0)
            r.setViewVisibility(R.id.agenda_rows,View.GONE)
            r.removeAllViews(R.id.agenda_rows)
            if(shape==WidgetShape.WIDE_SHORT)r.removeAllViews(R.id.wide_facts)
            val padding=px(context,pad.toFloat());r.setViewPadding(R.id.widget_root,padding,padding,padding,padding)
            r.setViewVisibility(R.id.widget_header,if(showHeader)View.VISIBLE else View.GONE)
            r.setTextViewText(R.id.week_day,content.badge)
            r.setTextViewText(R.id.category,content.category)
            r.setViewVisibility(R.id.category,if(showCategory)View.VISIBLE else View.GONE)
            r.setTextViewText(R.id.session_title,title)
            r.setTextViewTextSize(R.id.session_title,TypedValue.COMPLEX_UNIT_SP,titleSize)
            r.setContentDescription(R.id.session_title,content.title)
            r.setTextViewText(R.id.target,content.target)
            r.setViewVisibility(R.id.target,if(content.target.isBlank())View.GONE else View.VISIBLE)
            r.setTextViewText(R.id.open_session,if(content.session==null)"Open Workout ↗" else "View session ↗")
            r.setViewVisibility(R.id.next_session,if(showNext||shape==WidgetShape.WIDE_SHORT)View.VISIBLE else View.GONE)
            r.setTextViewText(R.id.next_session,if(shape==WidgetShape.WIDE_SHORT)content.badge else content.next)
            if(shape==WidgetShape.WIDE_TALL) {
                r.setViewVisibility(R.id.week_strip,if(showWeek)View.VISIBLE else View.GONE)
                if(showWeek)days.forEachIndexed{i,id->
                    r.setInt(id,"setBackgroundResource",if(date.dayOfWeek.value==i+1)R.drawable.button_green else R.drawable.pill_dark)
                    val day=date.minusDays((date.dayOfWeek.value-1).toLong()).plusDays(i.toLong())
                    r.setContentDescription(id,day.dayOfWeek.getDisplayName(TextStyle.FULL,Locale.getDefault()))
                    r.setOnClickPendingIntent(id,WorkoutWidget.open(context,day))
                }
            }
            val click=WorkoutWidget.open(context,date)
            r.setOnClickPendingIntent(R.id.open_session,click)
            r.setOnClickPendingIntent(R.id.widget_root,click)
            if(widgetId>=0)r.setOnClickPendingIntent(R.id.widget_brand,PendingIntent.getActivity(context,50000+widgetId,
                Intent(context,WidgetSettingsActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return r
        }
        var r=base()
        var baseHeight=measureHeight(context,r,w)
        // Reserve the complete prescription and a real 48dp action before adding optional content.
        if(baseHeight>h){showNext=false;showCategory=false;showWeek=false;r=base();baseHeight=measureHeight(context,r,w)}
        if(baseHeight>h){showHeader=false;pad=8;r=base();baseHeight=measureHeight(context,r,w)}
        if(baseHeight>h&&shape==WidgetShape.WIDE_SHORT){shape=WidgetShape.COMPACT;r=base();baseHeight=measureHeight(context,r,w)}
        if(baseHeight>h){title=content.title.substringBefore('/').trim();titleSize=17f;r=base();baseHeight=measureHeight(context,r,w)}
        if(baseHeight>h) {
            val paint=TextPaint().apply{textSize=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,titleSize,context.resources.displayMetrics);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)}
            val count=paint.breakText(content.title,true,(w-px(context,2f*pad)).toFloat(),null)
            val prefix=content.title.take((count-2).coerceAtLeast(1))
            title=prefix.substringBeforeLast(' ',prefix)+"…"
            r=base();baseHeight=measureHeight(context,r,w)
        }
        val allRows=content.rows
        val candidates=if(density==WidgetDensity.GLANCE)allRows.take(if(content.session?.kind==Kind.STRENGTH)2 else 3) else allRows
        val shown=mutableListOf<WorkoutRow>()
        if(shape==WidgetShape.WIDE_SHORT) {
            for(item in candidates) {
                if(item.kind==RowKind.NOTE)break
                r.addView(R.id.wide_facts,row(context,item))
                if(measureHeight(context,r,w)>h-px(context,2f)) {
                    r.removeAllViews(R.id.wide_facts);shown.forEach{r.addView(R.id.wide_facts,row(context,it))};break
                }
                shown+=item
            }
            baseHeight=measureHeight(context,r,w)
        }
        val remaining=candidates.drop(shown.size)
        val room=(h-baseHeight-px(context,4f)).coerceAtLeast(0)
        val innerWidth=w-px(context,2f*pad)
        val heights=remaining.map{measureHeight(context,row(context,it),innerWidth)}
        val detailed=density!=WidgetDensity.GLANCE&&(shape==WidgetShape.NARROW_TALL||shape==WidgetShape.WIDE_TALL||density==WidgetDensity.DETAILED)
        val scrolling=detailed&&remaining.isNotEmpty()&&heights.sum()>room&&room>=px(context,75f*font)
        if(scrolling) {
            r.setViewVisibility(R.id.list_container,View.VISIBLE)
            r.setInt(R.id.widget_root,"setMinimumHeight",h)
            if(bindCollection)bindRows(context,r,remaining,widgetId,date,content.session?.id.orEmpty(),width)
        } else {
            var used=0
            for((i,item) in remaining.withIndex()) {
                if(used+heights[i]>room)break
                if(shape==WidgetShape.WIDE_SHORT)break
                shown+=item;used+=heights[i]
                r.addView(R.id.inline_rows,row(context,item))
            }
            if(shown.isNotEmpty()&&shape!=WidgetShape.WIDE_SHORT)r.setViewVisibility(R.id.inline_rows,View.VISIBLE)
        }
        val omitted=if(scrolling)0 else allRows.size-shown.size
        if(omitted>0)r.setTextViewText(R.id.open_session,if(width<220||shape==WidgetShape.WIDE_SHORT)"Session · +$omitted ↗" else "View session · +$omitted details ↗")
        if(scrolling)r.setTextViewText(R.id.target_label,"Scroll for workout details ↓")
        r.setViewVisibility(R.id.target_label,if(scrolling)View.VISIBLE else View.GONE)
        // Only add schedule context after today's complete prescription fits. Never pad
        // sparse sessions with invented workout instructions or an empty scroll viewport.
        val agendaDates=mutableListOf<LocalDate>()
        if(!scrolling&&omitted==0&&density!=WidgetDensity.GLANCE&&width/font>=240&&height/font>=320&&plan!=null) {
            var next=maxOf(date.plusDays(1),plan.firstDate?:date.plusDays(1))
            val end=minOf(next.plusDays(6),plan.lastDate?:next.minusDays(1))
            val heading=row(context,WorkoutRow("COMING UP"))
            val added=mutableListOf<RemoteViews>()
            r.setViewVisibility(R.id.agenda_rows,View.VISIBLE)
            r.addView(R.id.agenda_rows,heading)
            while(next<=end) {
                val sessions=plan.on(next)
                val description=if(sessions.isEmpty())"No session scheduled" else sessions.joinToString(" · "){it.title}
                val item=row(context,WorkoutRow(next.format(DateTimeFormatter.ofPattern("EEE, d MMM")),description))
                item.setOnClickPendingIntent(R.id.row_root,WorkoutWidget.open(context,next))
                r.addView(R.id.agenda_rows,item)
                if(measureHeight(context,r,w)>h-px(context,2f)) {
                    r.removeAllViews(R.id.agenda_rows)
                    if(added.isNotEmpty()){r.addView(R.id.agenda_rows,heading);added.forEach{r.addView(R.id.agenda_rows,it)}}
                    break
                }
                added+=item;agendaDates+=next;next=next.plusDays(1)
            }
            if(agendaDates.isEmpty()){r.removeAllViews(R.id.agenda_rows);r.setViewVisibility(R.id.agenda_rows,View.GONE)}
        }
        // The scroll hint itself consumes space; its measured height is included by the weighted list viewport.
        return WidgetComposition(r,shape,allRows,shown,scrolling,omitted,baseHeight,room,layoutId(),agendaDates)
    }
    @Suppress("DEPRECATION")
    private fun bindRows(context:Context,r:RemoteViews,rows:List<WorkoutRow>,widgetId:Int,date:LocalDate,sessionId:String,width:Float) {
        val template=WorkoutWidget.open(context,date,mutable=true)
        r.setPendingIntentTemplate(R.id.workout_list,template)
        if(Build.VERSION.SDK_INT>=31) {
            val collection=RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(2)
            rows.forEachIndexed{i,data->
                val view=row(context,data)
                view.setOnClickFillInIntent(if(data.kind==RowKind.FACT)R.id.row_title else R.id.row_root,Intent().putExtra("date",date.toString()))
                collection.addItem(i.toLong(),view)
            }
            r.setRemoteAdapter(R.id.workout_list,collection.build())
        } else {
            val intent=Intent(context,WorkoutRowsService::class.java).putExtra("date",date.toString()).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId)
                .putExtra("width",width).setData(Uri.parse("workout://rows/$widgetId/$date/$sessionId"))
            r.setRemoteAdapter(R.id.workout_list,intent)
        }
    }
}

