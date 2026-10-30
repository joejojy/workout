package com.joelj.workout

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.time.LocalDate

class PlanStore(private val context: Context) {
    private val dir = context.filesDir
    private val prefs = context.getSharedPreferences("workout", Context.MODE_PRIVATE)
    val draftPdf get() = File(dir, "pdf/draft.pdf")
    val activePdf get() = File(dir, "pdf/active.pdf")
    fun active(): Plan? = read("active.json")
    fun draft(): Plan? = read("draft.json")
    private fun read(name: String): Plan? = synchronized(calendarLock) { try {
        val file = AtomicFile(File(dir, name))
        val text=file.openRead().bufferedReader().use{it.readText()}
        val json=JSONObject(text)
        var plan=Plan.fromJson(json)
        if(json.optInt("schema",1)<2) {
            val pdf=if(name=="active.json")activePdf else draftPdf
            val raw=runCatching{PDFBoxResourceLoader.init(context);PDDocument.load(pdf).use{PDFTextStripper().apply{sortByPosition=true}.getText(it)}}.getOrDefault("")
            val detected=CalendarMapper.detect(raw,plan.title,plan.weeks)
            val previousStart=plan.start
            val mapping=if(detected.basis==DateBasis.UNDATED_INFERRED&&previousStart!=null)CalendarMapper.relative(previousStart,plan.weeks,DateBasis.LEGACY) else detected
            val backup=File(dir,"calendar-backups/$name");backup.parentFile!!.mkdirs();if(!backup.exists())backup.writeText(text)
            plan=plan.copy(start=mapping.anchor,calendar=mapping)
            write(name,plan)
        }
        plan
    } catch (_: java.io.FileNotFoundException) { null } }
    companion object {private val calendarLock=Any()}
    fun saveDraft(plan: Plan) = write("draft.json", plan)
    private fun write(name: String, plan: Plan) {
        val atomic = AtomicFile(File(dir, name))
        val out = atomic.startWrite()
        try { out.write(plan.toJson().toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    fun discardDraft() { File(dir, "draft.json").delete(); draftPdf.delete() }
    fun importPdf(uri: Uri, name: String,progress:(String)->Unit={}): Plan = context.contentResolver.openInputStream(uri)?.use { importPdf(it, name,progress) }
        ?: throw IllegalArgumentException("The selected file could not be opened.")
    fun importPdf(input: InputStream, name: String,progress:(String)->Unit={}): Plan {
        draftPdf.parentFile!!.mkdirs()
        val incoming = File(dir, "pdf/incoming.pdf")
        try {
            input.use { source -> incoming.outputStream().use { target ->
                val buffer = ByteArray(8192); var total = 0
                while (true) { val n = source.read(buffer); if (n < 0) break; total += n
                    if(Thread.currentThread().isInterrupted)throw InterruptedException("Import cancelled")
                    require(total <= 20 * 1024 * 1024) { "Choose a PDF smaller than 20 MB." }; target.write(buffer, 0, n) }
            } }
            var extracted=PdfImportReader.read(context,incoming,progress=progress)
            var parsed=runCatching{PlanParser.parse(extracted.text,name)}
            if(parsed.isFailure&&extracted.scannedPages.size<extracted.pages.size) {
                progress("Trying page images for this layout…")
                extracted=PdfImportReader.read(context,incoming,forceOcr=true,progress=progress)
                parsed=runCatching{PlanParser.parse(extracted.text,name)}
            }
            val raw=extracted.text
            val ocrWarnings=if(extracted.scannedPages.isEmpty())emptyList() else listOf("Scanned pages read on your phone: ${extracted.scannedPages.joinToString()}. Check OCR numbers, units, and dates against the original. English/Latin-script printed text is supported; handwriting is not guaranteed.")
            val unread=extracted.pages.mapIndexedNotNull{i,p->if(p.count(Char::isLetterOrDigit)<15)i+1 else null}
            val plan=parsed.getOrThrow().let{p->p.copy(warnings=p.warnings+ocrWarnings+if(unread.isEmpty())emptyList() else listOf("Pages ${unread.joinToString()} had little readable text; verify nothing was missed."))}
            if(Thread.currentThread().isInterrupted)throw InterruptedException("Import cancelled")
            incoming.copyTo(draftPdf, overwrite = true)
            File(dir, "draft-source.txt").writeText(raw)
            saveDraft(plan)
            return plan
        } finally { incoming.delete() }
    }
    fun activate(plan: Plan) {
        require(plan.anchor != null && plan.anchor!!.dayOfWeek.value == 1)
        require(plan.weeks in 1..52)
        require(plan.sessions.all { it.day in 1..7 && it.week in 0..plan.weeks && it.title.isNotBlank() })
        val calendar=plan.calendar
        val active=if(calendar!=null&&!calendar.monthly&&calendar.basis in setOf(DateBasis.UNDATED_INFERRED,DateBasis.MANUAL,DateBasis.LEGACY))plan.copy(calendar=CalendarMapper.relative(calendar.from,plan.weeks,calendar.basis)) else plan
        draftPdf.copyTo(activePdf, overwrite = true)
        File(dir,"draft-source.txt").takeIf{it.exists()}?.copyTo(File(dir,"active-source.txt"),overwrite=true)
        write("active.json", active)
        discardDraft()
        WorkoutWidget.refresh(context)
    }
    fun isComplete(date: LocalDate, s: Session) = prefs.getStringSet("completed", emptySet())!!.contains("$date:${s.id}")
    fun toggle(date: LocalDate, s: Session) {
        val key = "$date:${s.id}"
        val complete = prefs.getStringSet("completed", emptySet())!!.toMutableSet()
        if (!complete.add(key)) complete.remove(key)
        prefs.edit().putStringSet("completed", complete).commit()
        WorkoutWidget.refresh(context)
    }
}

