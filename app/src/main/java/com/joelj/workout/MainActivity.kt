package com.joelj.workout

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.time.YearMonth
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var store: PlanStore
    private lateinit var body: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val green = Color.rgb(19,124,55)
    private var selectedDate = LocalDate.now()
    private var screen = "today"
    private var planWeek = 1
    private var busy = false
    private var importMessage="Reading your PDF…"
    private var importGeneration=0
    private var importJob:java.util.concurrent.Future<*>?=null
    private var acknowledged = false
    private val dayNames = listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday")

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = PlanStore(this)
        selectedDate = runCatching { LocalDate.parse(state?.getString("date") ?: intent.getStringExtra("date")) }.getOrDefault(LocalDate.now())
        screen = state?.getString("screen") ?: if (store.draft() != null) "review" else "today"
        acknowledged = state?.getBoolean("ack") ?: false
        planWeek = state?.getInt("week") ?: 1
        render()
        WorkoutWidget.refresh(this)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        selectedDate=runCatching{LocalDate.parse(intent.getStringExtra("date"))}.getOrDefault(LocalDate.now())
        screen = "today"; render()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("date", selectedDate.toString()); out.putString("screen", screen)
        out.putBoolean("ack", acknowledged); out.putInt("week", planWeek)
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(value: String, size: Int = 14, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(Color.WHITE)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(5), 0, dp(5))
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 13f; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(green); cornerRadius = dp(16).toFloat() }
        minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9); bottomMargin = dp(5) }
        setOnClickListener { action() }
    }
    private fun card(): LinearLayout = column().apply {
        background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.rgb(42,48,44)) }
        setPadding(dp(18), dp(15), dp(18), dp(15))
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12); bottomMargin=dp(4) }
    }
    private fun render() {
        val root = column().apply { setBackgroundColor(Color.BLACK) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            } else { @Suppress("DEPRECATION") v.setPadding(0,insets.systemWindowInsetTop,0,insets.systemWindowInsetBottom) }
            insets
        }
        body = column().apply { setPadding(dp(22),dp(16),dp(22),dp(24)) }
        val scroll = ScrollView(this).apply { isFillViewport=true; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        val nav = row().apply { setPadding(dp(14),dp(7),dp(14),dp(8)) }
        listOf("today" to "Today", "plan" to "Plan", "import" to "Import").forEach { (tab, title) ->
            nav.addView(button(title) { if (!busy) { if(tab=="plan"&&screen!="plan")planWeek=(store.active()?.weekAt(LocalDate.now())?:1).coerceAtLeast(1);screen=tab; render() } }, LinearLayout.LayoutParams(0,dp(48),1f).apply { marginStart=dp(4); marginEnd=dp(4) })
        }
        root.addView(nav)
        setContentView(root); root.requestApplyInsets()
        body.addView(label("WORKOUT",12,true).apply { letterSpacing=.15f })
        when(screen) { "review" -> review(); "import" -> imports(); "plan" -> weekly(); else -> today() }
    }
    private fun today() {
        body.addView(label(if (selectedDate == LocalDate.now()) "Today’s training" else selectedDate.dayOfWeek.toString().lowercase().replaceFirstChar(Char::titlecase),28,true))
        body.addView(label(selectedDate.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")),13))
        val dates = row()
        dates.addView(button("← Previous") { selectedDate=selectedDate.minusDays(1); render() }, LinearLayout.LayoutParams(0,dp(48),1f))
        dates.addView(button("Today") { selectedDate=LocalDate.now(); render() }, LinearLayout.LayoutParams(0,dp(48),1f).apply { marginStart=dp(6); marginEnd=dp(6) })
        dates.addView(button("Next →") { selectedDate=selectedDate.plusDays(1); render() }, LinearLayout.LayoutParams(0,dp(48),1f))
        body.addView(dates)
        val plan = store.active()
        if (plan == null) { body.addView(label("Your plan. Your home screen.",23,true)); body.addView(label("Import your workout PDF, review the schedule, then add your widget. Running and strength stay together.")); body.addView(button("Import a PDF") { screen="import"; render() }); return }
        val week = plan.weekAt(selectedDate)
        val state=plan.stateAt(selectedDate)
        plan.calendar?.let{body.addView(label(it.label,14,true))}
        body.addView(label(when(state){PlanDayState.UPCOMING->"PLAN HAS NOT STARTED";PlanDayState.FINISHED->"PLAN FINISHED";PlanDayState.UNSPECIFIED->"NO NUMBERED WEEK PROVIDED";PlanDayState.ACTIVE->if(week in 1..plan.weeks)"WEEK $week OF ${plan.weeks}" else "MONTHLY SCHEDULE"},12,true))
        val sessions=plan.on(selectedDate)
        if (sessions.isEmpty()) body.addView(label(when(state){PlanDayState.UPCOMING->"This plan starts ${plan.firstDate}.";PlanDayState.FINISHED->"You’ve reached the end of this plan. Import your next one when ready.";PlanDayState.UNSPECIFIED->"The PDF does not specify a numbered training week for this date.";else->"No session scheduled for this day."},18))
        sessions.forEach { s ->
            val c=card(); c.addView(label(when(s.kind){Kind.RUN->"RUNNING";Kind.STRENGTH->"STRENGTH";Kind.REST->"RECOVERY";Kind.OTHER->"WORKOUT"},11,true))
            c.addView(label(s.title,26,true)); if(s.target.isNotBlank()) c.addView(label(s.target,16))
            s.metrics.forEach { c.addView(label("${it.label}  ·  ${it.value}",13)) }
            s.details.forEach { c.addView(label(it,14).apply { setTextIsSelectable(true) }) }
            if(s.kind != Kind.REST) c.addView(button(if(store.isComplete(selectedDate,s)) "✓ Complete · Undo" else "Mark session complete") { store.toggle(selectedDate,s); render() })
            body.addView(c)
        }
        plan.nextAfter(selectedDate)?.let { (date,s) -> body.addView(label("Next: ${date.format(DateTimeFormatter.ofPattern("EEE, d MMM"))} · ${s.title}",13)) }
        body.addView(button("Add home-screen widget") { pinWidget() })
        body.addView(button("Test widget sizes") { startActivity(Intent(this,WidgetPreviewActivity::class.java).putExtra("date",selectedDate.toString())) })
        body.addView(button("Widget display options") { startActivity(Intent(this,WidgetSettingsActivity::class.java)) })
    }
    private fun weekly() {
        body.addView(label("Your plan",28,true))
        val plan=store.active()
        if(plan==null){body.addView(label("Import a plan to see your weekly schedule."));body.addView(button("Import PDF"){screen="import";render()});return}
        body.addView(label(plan.title,14)); body.addView(label(plan.calendar?.let{"${it.label} · ${it.from} – ${it.through}"}?:"${plan.start} · ${plan.weeks} weeks",12))
        val anchor=plan.anchor?:return
        val count=maxOf(plan.weeks,((java.time.temporal.ChronoUnit.DAYS.between(anchor,plan.lastDate?:anchor))/7).toInt()+1)
        planWeek=planWeek.coerceIn(1,count)
        val weeks=Spinner(this); weeks.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,(1..count).map { w->val from=anchor.plusWeeks((w-1).toLong());val to=minOf(from.plusDays(6),plan.lastDate?:from.plusDays(6));"${if(w<=plan.weeks)"Week $w" else "Final partial week"} · ${from.format(DateTimeFormatter.ofPattern("d MMM"))}–${to.format(DateTimeFormatter.ofPattern("d MMM"))}" });weeks.setSelection(planWeek-1)
        weeks.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:AdapterView<*>?){}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){if(planWeek!=position+1){planWeek=position+1;render()}}
        };body.addView(weeks)
        val monday=anchor.plusWeeks((planWeek-1).toLong())
        if(plan.firstDate!=null&&plan.firstDate!!<anchor)body.addView(button("View opening partial days"){selectedDate=plan.firstDate!!;screen="today";render()})
        for(i in 0..6){val date=monday.plusDays(i.toLong());if((plan.firstDate!=null&&date<plan.firstDate)||(plan.lastDate!=null&&date>plan.lastDate))continue;val sessions=plan.on(date);val c=card()
            c.addView(label("${dayNames[i]} · ${date.format(DateTimeFormatter.ofPattern("d MMM"))}",13,true))
            if(sessions.isEmpty()) c.addView(label("No session scheduled",14))
            sessions.forEach { s -> c.addView(label((if(store.isComplete(date,s)) "✓ " else "")+s.title,20,true)); if(s.target.isNotBlank())c.addView(label(s.target,13)) }
            c.addView(button("View day") { selectedDate=date;screen="today";render() });body.addView(c)
        }
        body.addView(button("View original PDF") { viewPdf(false) })
        body.addView(button("Edit this plan") { store.activePdf.copyTo(store.draftPdf,overwrite=true);java.io.File(filesDir,"active-source.txt").takeIf{it.exists()}?.copyTo(java.io.File(filesDir,"draft-source.txt"),overwrite=true);store.saveDraft(plan);acknowledged=false;screen="review";render() })
    }
    private fun imports() {
        body.addView(label("Bring your plan",28,true))
        body.addView(label("Import your workout PDF, including scanned pages. Review the detected schedule, running targets, and strength prescriptions before making it active."))
        body.addView(label("Processed on your phone · No account needed",12,true))
        if(busy){body.addView(ProgressBar(this));body.addView(label(importMessage));body.addView(button("Cancel import"){importGeneration++;importJob?.cancel(true);busy=false;render()});return}
        body.addView(button("Choose PDF") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="application/pdf";addCategory(Intent.CATEGORY_OPENABLE);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },100) })
        body.addView(button("Try sample plan") { importInBackground { progress -> assets.open("sample-plan.pdf").use { store.importPdf(it,"Sample workout plan.pdf",progress) } } })
        if(store.draft()!=null)body.addView(button("Continue import review"){screen="review";render()})
        body.addView(label("Supported PDF layouts",19,true))
        body.addView(label("• Weekday sections and weekly schedule tables\n• Wrapped running instructions and exercise libraries\n• Scanned and mixed PDFs: automatic on-device text recognition\n• PDFs up to 20 MB and 50 pages"))
        body.addView(label("Check the import review against your original. Blurry scans, handwriting, unsupported scripts, and ambiguous layouts may need correction. No PDF is sent to a server.",13))
        body.addView(button("Add home-screen widget"){pinWidget()})
        body.addView(button("Test widget sizes"){startActivity(Intent(this,WidgetPreviewActivity::class.java))})
        body.addView(button("Widget display options"){startActivity(Intent(this,WidgetSettingsActivity::class.java))})
    }
    @Deprecated("Platform result API is sufficient for this single document picker")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==100&&resultCode==RESULT_OK){val uri=data?.data?:return
            var name="Workout plan.pdf"
            contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst())name=it.getString(0) }
            importInBackground { progress -> store.importPdf(uri,name,progress) }
        }
    }
    private fun importInBackground(task:((String)->Unit)->Plan){
        if(busy)return
        val generation=++importGeneration
        busy=true;importMessage="Reading your PDF…";screen="import";render()
        importJob=io.submit{
            val result=runCatching{task{message->runOnUiThread{if(generation==importGeneration&&!isDestroyed){importMessage=message;if(screen=="import")render()}}}}
            runOnUiThread{
                if(generation!=importGeneration||isFinishing||isDestroyed)return@runOnUiThread
                busy=false
                result.onSuccess{acknowledged=false;screen="review";render()}.onFailure{screen="import";render();error(it.message?:"This PDF could not be read. Try a clearer or unencrypted PDF.")}
            }
        }
    }
    private fun review(){
        val plan=store.draft()?:run{screen="import";imports();return}
        body.addView(label("Review your import",28,true));body.addView(label(plan.title,14))
        body.addView(label("${plan.sessions.size} session templates · ${plan.weeks} weeks. Tap a session to correct any extracted details.",13))
        val calendar=plan.calendar?:CalendarMapper.relative(plan.start?:LocalDate.now(),plan.weeks)
        val dates=card();dates.addView(label("Dates set automatically",18,true));dates.addView(label(calendar.label,22,true));dates.addView(label("${calendar.from} – ${calendar.through}",13));dates.addView(label(calendar.explanation,12))
        val today=LocalDate.now();val sessionsToday=plan.on(today);val currentWeek=plan.weekAt(today)
        dates.addView(label("Today · ${today.format(DateTimeFormatter.ofPattern("EEE, d MMM"))}",12,true))
        dates.addView(label(if(sessionsToday.isNotEmpty())"${if(currentWeek in 1..plan.weeks)"Week $currentWeek · " else ""}${sessionsToday.joinToString(" + "){it.title}}" else when(plan.stateAt(today)){PlanDayState.UPCOMING->"This plan starts ${plan.firstDate}";PlanDayState.FINISHED->"This plan has ended";else->"No prescription provided for this date"},16,true));body.addView(dates)
        body.addView(button("Compare with original PDF"){viewPdf(true)})
        body.addView(button("Read extracted text"){
            val text=java.io.File(filesDir,"draft-source.txt").takeIf{it.exists()}?.readText().orEmpty()
            AlertDialog.Builder(this).setTitle("Extracted pages & notes").setView(ScrollView(this).apply{addView(label(text.replace("\u000c","\n—— Next page ——\n"),13))}).setPositiveButton("Close",null).show()
        })
        val issues=card();issues.addView(label("Check before activating",18,true));plan.warnings.forEach{issues.addView(label("• $it",13))};body.addView(issues)
        body.addView(button("Correct detected dates (optional)"){
            val proposed=calendar.from
            DatePickerDialog(this,{_,y,m,d->val chosen=LocalDate.of(y,m+1,d)
                val corrected=if(calendar.monthly)CalendarMapper.monthly(YearMonth.from(chosen),DateBasis.MANUAL) else CalendarMapper.relative(chosen,plan.weeks,DateBasis.MANUAL)
                store.saveDraft(plan.copy(start=corrected.anchor,calendar=corrected));render()
            },proposed.year,proposed.monthValue-1,proposed.dayOfMonth).show()
        })
        body.addView(label("Plan length (weeks)",12,true))
        val weekCount=EditText(this).apply{inputType=InputType.TYPE_CLASS_NUMBER;setText(plan.weeks.toString());setTextColor(Color.WHITE);setSingleLine()};body.addView(weekCount)
        val check=CheckBox(this).apply{text="I checked the sessions and import notes";setTextColor(Color.WHITE);isChecked=acknowledged;setOnCheckedChangeListener{_,value->acknowledged=value}};body.addView(check)
        body.addView(button("Activate plan"){
            val current=store.draft()?:return@button
            val count=weekCount.text.toString().toIntOrNull()
            when{
                current.anchor==null->error("The plan dates could not be resolved. Import the PDF again.")
                count==null||count !in 1..52||count<(current.sessions.maxOfOrNull{it.week}?:0)->error("Choose 1–52 weeks, including every numbered session week.")
                current.sessions.isEmpty()->error("Add at least one session before activating.")
                !acknowledged->error("Check the sessions and import notes, then tick the review box.")
                else->{store.activate(current.copy(weeks=count));selectedDate=LocalDate.now();planWeek=current.weekAt(selectedDate).coerceIn(1,count);screen="today";render();Toast.makeText(this,"Plan activated. Showing today’s workout.",Toast.LENGTH_LONG).show()}
            }
        })
        body.addView(button("Add session") { editSession(Session(day=1,kind=Kind.STRENGTH,title="New session")) })
        plan.sessions.sortedWith(compareBy<Session>{it.day}.thenBy{it.week}).forEach { s ->
            val c=card();c.addView(label(s.date?.toString()?:"${dayNames[s.day-1]} · ${if(s.week==0)"Every week" else "Week ${s.week}"}",12,true));c.addView(label(s.title,21,true));c.addView(label(s.target,14))
            s.details.take(2).forEach{c.addView(label(it,12))};c.addView(button("Edit session"){editSession(s)});body.addView(c)
        }
        body.addView(button("Discard this import") { store.discardDraft();acknowledged=false;screen="import";render() })
    }
    private fun editSession(s:Session){
        val form=column().apply{setPadding(dp(20),dp(8),dp(20),dp(16))}
        fun field(name:String,value:String,multi:Boolean=false):EditText{form.addView(label(name,12,true));return EditText(this).apply{setText(value);setTextColor(Color.WHITE);textSize=14f;inputType=InputType.TYPE_CLASS_TEXT or if(multi)InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;minLines=if(multi)3 else 1;maxLines=if(multi)14 else 2;form.addView(this)}}
        s.sourcePages.forEach{page->form.addView(button("Original page $page"){
            startActivity(Intent(this,PdfReviewActivity::class.java).putExtra("draft",true).putExtra("page",page-1))
        })}
        val title=field("Session title",s.title)
        val target=field("Main target",s.target)
        form.addView(label("Day",12,true));val day=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,dayNames);setSelection(s.day-1)};form.addView(day)
        form.addView(label("Session type",12,true));val kind=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,Kind.entries.map{it.name.lowercase().replaceFirstChar(Char::titlecase)});setSelection(s.kind.ordinal)};form.addView(kind)
        val week=field("Week number (0 = every week)",s.week.toString()).apply{inputType=InputType.TYPE_CLASS_NUMBER}
        val details=field("Details — one item per line",s.details.joinToString("\n"),true)
        val metrics=field("Quick facts — Label: value, one per line",s.metrics.joinToString("\n"){"${it.label}: ${it.value}"},true)
        val dialog=AlertDialog.Builder(this).setTitle("Edit session").setView(ScrollView(this).apply{addView(form)}).setPositiveButton("Save",null).setNegativeButton("Cancel",null).setNeutralButton("Remove"){_,_->
            val p=store.draft()?:return@setNeutralButton;store.saveDraft(p.copy(sessions=p.sessions.filterNot{it.id==s.id}));acknowledged=false;render()
        }.create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val p=store.draft()?:return@setOnClickListener
            val w=week.text.toString().toIntOrNull()
            val metricLines=metrics.text.toString().lines().filter{it.isNotBlank()}
            if(title.text.isBlank()||w==null||w !in 0..52||metricLines.any{!it.contains(':')}){error("Enter a title, a week from 0–52, and quick facts in Label: value format.");return@setOnClickListener}
            val edited=s.copy(title=title.text.toString().trim(),target=target.text.toString().trim(),day=day.selectedItemPosition+1,week=w,kind=Kind.entries[kind.selectedItemPosition],details=details.text.toString().lines().filter{it.isNotBlank()},metrics=metricLines.map{Metric(it.substringBefore(':').trim(),it.substringAfter(':').trim())})
            val sessions=if(p.sessions.any{it.id==s.id})p.sessions.map{if(it.id==s.id)edited else it} else p.sessions+edited
            store.saveDraft(p.copy(sessions=sessions,weeks=maxOf(p.weeks,w)));acknowledged=false;dialog.dismiss();render()
        }};dialog.show()
    }
    private fun viewPdf(draft:Boolean){
        val file=if(draft)store.draftPdf else store.activePdf
        if(!file.exists()){error("The original PDF is unavailable. Import the file again.");return}
        startActivity(Intent(this,PdfReviewActivity::class.java).putExtra("draft",draft))
    }
    private fun pinWidget(){
        val manager=AppWidgetManager.getInstance(this)
        if(manager.isRequestPinAppWidgetSupported)manager.requestPinAppWidget(ComponentName(this,WorkoutWidget::class.java),null,null)
        else AlertDialog.Builder(this).setTitle("Add Workout widget").setMessage("Long-press your home screen, choose Widgets, then Workout. Resize it to reveal more detail.").setPositiveButton("Got it",null).show()
    }
    private fun error(message:String){AlertDialog.Builder(this).setTitle("Workout").setMessage(message).setPositiveButton("OK",null).show()}
}

