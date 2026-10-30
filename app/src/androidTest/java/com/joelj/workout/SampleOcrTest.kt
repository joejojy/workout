package com.joelj.workout

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SampleOcrTest {
    @Test fun fictionalTextAndScannedSamplesImportOffline(){
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext;val store=PlanStore(context)
        val backup=listOf("draft.json","draft-source.txt","pdf/draft.pdf").associate{path->File(context.filesDir,path).let{it to if(it.exists())it.readBytes() else null}}
        val active=store.active()?.toJson()?.toString()
        try{
            val text=context.assets.open("sample-plan.pdf").use{store.importPdf(it,"Sample workout plan.pdf")};assertEquals(7,text.sessions.size)
            val scan=i.context.assets.open("sample-plan-scanned.pdf").use{store.importPdf(it,"Scanned sample.pdf")}
            assertEquals(7,scan.sessions.size);assertTrue(scan.warnings.any{it.contains("Scanned pages")});assertEquals(active,store.active()?.toJson()?.toString())
        }finally{backup.forEach{(file,bytes)->if(bytes==null)file.delete() else{file.parentFile!!.mkdirs();file.writeBytes(bytes)}}}
    }
}
