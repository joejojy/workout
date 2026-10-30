package com.joelj.workout

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowInsets
import android.widget.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal object WidgetUi {
    fun dp(a:Activity,n:Int)=(a.resources.displayMetrics.density*n).toInt()
    fun text(a:Activity,value:String,size:Float=14f)=TextView(a).apply{text=value;textSize=size;setTextColor(Color.WHITE);setPadding(0,8,0,8)}
    fun button(a:Activity,value:String,action:()->Unit)=Button(a).apply{
        text=value;isAllCaps=false;textSize=12f;setTextColor(Color.WHITE)
        background=GradientDrawable().apply{setColor(Color.rgb(19,124,55));cornerRadius=dp(a,14).toFloat()}
        minHeight=dp(a,48);setOnClickListener{action()}
    }
    fun root(a:Activity):LinearLayout=LinearLayout(a).apply{
        orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK)
        setOnApplyWindowInsetsListener{v,i->if(Build.VERSION.SDK_INT>=30){val b=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom)}else{@Suppress("DEPRECATION")v.setPadding(0,i.systemWindowInsetTop,0,i.systemWindowInsetBottom)};i}
        a.setContentView(this);requestApplyInsets()
    }
    fun picker(a:Activity,labels:List<String>,selected:Int,changed:(Int)->Unit):Spinner=Spinner(a).apply{
        adapter=ArrayAdapter(a,android.R.layout.simple_spinner_dropdown_item,labels);setSelection(selected)
        onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(p:AdapterView<*>?){}
            override fun onItemSelected(p:AdapterView<*>?,v:View?,position:Int,id:Long){changed(position)}
        }
    }
}

class WidgetPreviewActivity:Activity() {
    private data class Size(val label:String,val width:Float,val height:Float)
    private val sizes=listOf(Size("Compact · 160 × 190",160f,190f),Size("Wide & short · 340 × 190",340f,190f),Size("Medium · 300 × 320",300f,320f),Size("Narrow & tall · 180 × 520",180f,520f),Size("Large · 340 × 600",340f,600f))
    private var sizeIndex=2
    private var density=WidgetDensity.AUTO
    private var date=LocalDate.now()
    private lateinit var host:FrameLayout
    private lateinit var dateLabel:TextView
    private lateinit var sizeLabel:TextView
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        sizeIndex=state?.getInt("size",2)?:2
        density=runCatching{WidgetDensity.valueOf(state?.getString("density")?:"AUTO")}.getOrDefault(WidgetDensity.AUTO)
        date=runCatching{LocalDate.parse(state?.getString("date")?:intent.getStringExtra("date"))}.getOrDefault(LocalDate.now())
        val root=WidgetUi.root(this)
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(WidgetUi.dp(this@WidgetPreviewActivity,16),8,WidgetUi.dp(this@WidgetPreviewActivity,16),8)}
        val scroll=ScrollView(this).apply{addView(content)};root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        content.addView(WidgetUi.text(this,"Test widget sizes",24f))
        content.addView(WidgetUi.picker(this,sizes.map{it.label},sizeIndex){if(sizeIndex!=it){sizeIndex=it;preview()}})
        content.addView(WidgetUi.picker(this,listOf("Auto · adapt to size","Glance · summary","Detailed · more workout rows"),density.ordinal){val next=WidgetDensity.entries[it];if(density!=next){density=next;preview()}})
        val days=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        days.addView(WidgetUi.button(this,"← Day"){date=date.minusDays(1);preview()},LinearLayout.LayoutParams(0,WidgetUi.dp(this,48),1f))
        dateLabel=WidgetUi.text(this,"",12f).apply{gravity=Gravity.CENTER};days.addView(dateLabel,LinearLayout.LayoutParams(0,-2,2f))
        days.addView(WidgetUi.button(this,"Day →"){date=date.plusDays(1);preview()},LinearLayout.LayoutParams(0,WidgetUi.dp(this,48),1f));content.addView(days)
        val samples=LinearLayout(this)
        listOf("Short run" to Kind.RUN,"Strength" to Kind.STRENGTH,"Rest day" to Kind.REST).forEach{(label,kind)->
            samples.addView(WidgetUi.button(this,label){selectSample(kind)},LinearLayout.LayoutParams(0,WidgetUi.dp(this,48),1f))
        }
        content.addView(samples)
        sizeLabel=WidgetUi.text(this,"",11f);content.addView(sizeLabel)
        host=FrameLayout(this).apply{setBackgroundColor(Color.rgb(24,35,28));setPadding(0,0,0,0)}
        content.addView(host,LinearLayout.LayoutParams(-2,-2).apply{gravity=Gravity.CENTER_HORIZONTAL})
        root.addView(WidgetUi.button(this,"Back to Workout"){finish()},LinearLayout.LayoutParams(-1,WidgetUi.dp(this,48)))
        host.post{preview()}
    }
    private fun selectSample(kind:Kind){
        val plan=PlanStore(this).active()?:return
        val first=plan.firstDate?:return
        val last=plan.lastDate?:return
        val dates=generateSequence(first){it.plusDays(1)}.takeWhile{it<=last}.filter{day->plan.on(day).any{it.kind==kind}}.toList()
        val sample=dates.minWithOrNull(compareBy<LocalDate>{day->
            if(kind==Kind.RUN)WidgetContent.forDate(this,plan,day).rows.size else 0
        }.thenBy{ kotlin.math.abs(it.toEpochDay()-LocalDate.now().toEpochDay()) })
        if(sample==null)Toast.makeText(this,"No ${kind.name.lowercase()} sessions found in this plan",Toast.LENGTH_SHORT).show()
        else{date=sample;preview()}
    }
    private fun preview(){
        if(!::host.isInitialized)return
        val s=sizes[sizeIndex]
        val actualWidth=minOf(s.width,resources.displayMetrics.widthPixels/resources.displayMetrics.density-32f)
        val plan=PlanStore(this).active()
        val result=WidgetLayout.compose(this,plan,date,actualWidth,s.height,density=density,bindCollection=false)
        host.removeAllViews()
        host.layoutParams=host.layoutParams.apply{width=WidgetLayout.px(this@WidgetPreviewActivity,actualWidth);height=WidgetLayout.px(this@WidgetPreviewActivity,s.height)}
        val widget=result.remote.apply(this,host);host.addView(widget,FrameLayout.LayoutParams(-1,-1))
        if(result.scrolling){
            val list=widget.findViewById<ListView>(R.id.workout_list)
            list.adapter=object:BaseAdapter(){
                override fun getCount()=result.rows.size
                override fun getItem(position:Int)=result.rows[position]
                override fun getItemId(position:Int)=position.toLong()
                override fun getView(position:Int,convertView:View?,parent:ViewGroup):View=WidgetLayout.row(this@WidgetPreviewActivity,result.rows[position]).apply(this@WidgetPreviewActivity,parent)
            }
            list.setOnTouchListener{v,event->if(event.action==MotionEvent.ACTION_DOWN)v.parent.requestDisallowInterceptTouchEvent(true);if(event.action==MotionEvent.ACTION_UP||event.action==MotionEvent.ACTION_CANCEL)v.parent.requestDisallowInterceptTouchEvent(false);false}
            list.setOnItemClickListener{_,_,_,_->startActivity(Intent(this,MainActivity::class.java).putExtra("date",date.toString()))}
        }
        dateLabel.text=date.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
        sizeLabel.text="${actualWidth.toInt()} × ${s.height.toInt()} dp · ${if(result.scrolling)"Scroll for all details" else "${result.visibleRows.size} complete rows"}"
    }
    override fun onSaveInstanceState(out:Bundle){out.putInt("size",sizeIndex);out.putString("density",density.name);out.putString("date",date.toString());super.onSaveInstanceState(out)}
}

class WidgetSettingsActivity:Activity(){
    override fun onCreate(state:Bundle?){
        super.onCreate(state);setResult(RESULT_CANCELED)
        val manager=AppWidgetManager.getInstance(this)
        val requested=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        val ids=if(requested!=AppWidgetManager.INVALID_APPWIDGET_ID&&manager.getAppWidgetInfo(requested)?.provider==ComponentName(this,WorkoutWidget::class.java))intArrayOf(requested) else manager.getAppWidgetIds(ComponentName(this,WorkoutWidget::class.java))
        val root=WidgetUi.root(this)
        val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,16,32,16)};root.addView(c)
        c.addView(WidgetUi.text(this,"Widget display",26f))
        if(ids.isEmpty()){c.addView(WidgetUi.text(this,"Add a Workout widget first, then choose its display preference here."));c.addView(WidgetUi.button(this,"Back"){finish()});return}
        var selected=0
        val group=RadioGroup(this)
        WidgetDensity.entries.forEachIndexed{i,mode->group.addView(RadioButton(this).apply{id=100+i;text=when(mode){WidgetDensity.AUTO->"Auto — adapt to available space";WidgetDensity.GLANCE->"Glance — a short summary";WidgetDensity.DETAILED->"Detailed — more workout rows"};setTextColor(Color.WHITE);minHeight=WidgetUi.dp(this@WidgetSettingsActivity,48)})}
        c.addView(WidgetUi.picker(this,ids.mapIndexed{i,_->"Workout widget ${i+1}"},0){selected=it;group.check(100+WidgetPreferences.get(this,ids[selected]).ordinal)})
        c.addView(group);group.check(100+WidgetPreferences.get(this,ids[0]).ordinal)
        c.addView(WidgetUi.text(this,"Each widget keeps its own preference. Readability and available space always take priority.",13f))
        c.addView(WidgetUi.button(this,"Save display preference"){
            val id=ids[selected];WidgetPreferences.set(this,id,WidgetDensity.entries[(group.checkedRadioButtonId-100).coerceIn(0,2)])
            WorkoutWidget.update(this,manager,id);setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id));finish()
        })
    }
}
