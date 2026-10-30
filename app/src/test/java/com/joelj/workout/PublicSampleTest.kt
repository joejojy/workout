package com.joelj.workout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublicSampleTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun sample()=context.assets.open("sample-plan.pdf").use{PlanStore(context).importPdf(it,"Sample workout plan.pdf")}
    @Test fun genericPdfIncludesRunningStrengthAndRest(){
        val p=sample();assertEquals(7,p.sessions.size)
        assertEquals(3,p.sessions.count{it.kind==Kind.RUN});assertEquals(3,p.sessions.count{it.kind==Kind.STRENGTH});assertEquals(1,p.sessions.count{it.kind==Kind.REST})
        assertEquals(3,p.sessions.single{it.day==1}.exercises.size)
        assertTrue(p.sessions.single{it.day==4}.details.any{it.contains("Warm up")})
    }
    @Test fun datedTablesKeepCrossMonthBounds(){
        val p=PlanParser.parse("Sample August 2026\nWeek 1 (Aug 31 – Sep 6)\nMon Push\nTue Easy Run: 3 km\nWed Legs\nThu Rest\nFri Pull\nSat Long Run: 5 km\nSun Rest\nPush / Pull / Legs — Exercise Breakdown\nPush\nExercise Sets x Reps\nPush-ups 3 x 10\nLegs\nSquat 3 x 8\nPull\nRow 3 x 10","sample.pdf")
        assertEquals(LocalDate.of(2026,8,31),p.firstDate);assertEquals(LocalDate.of(2026,9,6),p.lastDate)
        assertTrue(p.on(LocalDate.of(2026,8,31)).single().exercises.any{it.contains("3 x 10")})
    }
    @Test fun failedImportPreservesDraftAndCompletionIsPerSession(){
        val store=PlanStore(context);val p=sample();val before=store.draft()!!.toJson().toString()
        try{store.importPdf("bad pdf".byteInputStream(),"bad.pdf");fail("Expected rejection")}catch(_:java.io.IOException){}
        assertEquals(before,store.draft()!!.toJson().toString())
        val s=p.sessions.first();store.toggle(p.anchor!!,s);assertTrue(store.isComplete(p.anchor!!,s));store.toggle(p.anchor!!,s);assertFalse(store.isComplete(p.anchor!!,s))
    }
    @Test fun allSampleDaysFitEverySizeAndModeAndProduceDocumentationImages(){
        val p=sample();val images=File("../docs/images").apply{mkdirs()}
        for(s in p.sessions)for((w,h) in listOf(160 to 190,340 to 190,300 to 320,180 to 520,340 to 600))for(mode in WidgetDensity.entries){
            val date=p.anchor!!.plusDays((s.day-1).toLong());val c=WidgetLayout.compose(context,p,date,w.toFloat(),h.toFloat(),density=mode,bindCollection=false)
            val root=c.remote.apply(context,FrameLayout(context));val wp=WidgetLayout.px(context,w.toFloat());val hp=WidgetLayout.px(context,h.toFloat())
            root.measure(View.MeasureSpec.makeMeasureSpec(wp,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(hp,View.MeasureSpec.EXACTLY));root.layout(0,0,wp,hp)
            val card=root.findViewById<View>(R.id.widget_root);val footer=root.findViewById<View>(R.id.widget_footer)
            assertTrue("Overflow day ${s.day} ${w}x$h $mode",card.height<=hp);assertEquals(footer.bottom+card.paddingBottom,card.height);assertNull(root.background)
            assertEquals(WidgetContent.forDate(context,p,date).rows,c.rows)
            if(s.day==1&&mode==WidgetDensity.AUTO&&(w==160||w==340&&h==600)){
                val bitmap=Bitmap.createBitmap(wp,card.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
                File(images,if(w==160)"widget-compact.png" else "widget-large.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
        }
    }
    @Test fun unknownActivityIsPreservedAndRestDuplicatesAreRemoved(){
        val p=PlanParser.parse("Sample routine\nMon: Swimming\n20 minutes easy\nTue: Rest\nOptional gentle walk","sample.pdf")
        assertEquals(Kind.OTHER,p.sessions.first().kind)
        val rest=Session(day=7,kind=Kind.REST,title="Rest",details=listOf("Rest","Optional walk"))
        assertEquals(listOf("Optional walk"),WidgetContent.rows(rest).map{it.text})
    }
}
