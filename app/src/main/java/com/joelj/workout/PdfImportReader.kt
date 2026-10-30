package com.joelj.workout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.concurrent.TimeUnit

data class PdfReadResult(val pages:List<String>,val scannedPages:List<Int>) {
    val text get()=pages.joinToString("\n\u000c\n")
}

/** Runs on the import worker. Uses one bounded bitmap at a time and a bundled offline OCR model. */
object PdfImportReader {
    fun read(context:Context,file:File,forceOcr:Boolean=false,progress:(String)->Unit={}):PdfReadResult {
        PDFBoxResourceLoader.init(context)
        val pages=PDDocument.load(file).use { pdf ->
            require(pdf.numberOfPages in 1..50){"Choose a PDF with 1–50 pages."}
            require(pdf.currentAccessPermission.canExtractContent()){"This PDF does not allow text extraction."}
            (1..pdf.numberOfPages).map { n ->
                if(Thread.currentThread().isInterrupted)throw InterruptedException("Import cancelled")
                progress("Reading page $n of ${pdf.numberOfPages}…")
                PDFTextStripper().apply{sortByPosition=true;startPage=n;endPage=n}.getText(pdf)
            }
        }.toMutableList()
        val scanned=mutableListOf<Int>()
        val needs=pages.indices.filter{forceOcr||pages[it].count(Char::isLetterOrDigit)<45||pages[it].count{c->c=='\ufffd'}>8}
        if(needs.isNotEmpty()) {
            val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{renderer->
                    for(i in needs) {
                        if(Thread.currentThread().isInterrupted)throw InterruptedException("Import cancelled")
                        progress("Reading scanned page ${i+1} of ${pages.size}…")
                        renderer.openPage(i).use{page->
                            val scale=minOf(2.5f,2200f/maxOf(page.width,page.height))
                            val bitmap=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val result=Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),60,TimeUnit.SECONDS)
                                val lines=result.textBlocks.flatMap{it.lines}.filter{it.boundingBox!=null}.sortedBy{it.boundingBox!!.centerY()}
                                val rows=mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Line>>()
                                for(line in lines){
                                    val box=line.boundingBox!!
                                    val row=rows.lastOrNull()
                                    if(row!=null&&kotlin.math.abs(row.first().boundingBox!!.centerY()-box.centerY())<minOf(row.first().boundingBox!!.height(),box.height())*.55f)row+=line
                                    else rows+=mutableListOf(line)
                                }
                                pages[i]=rows.joinToString("\n"){row->row.sortedBy{it.boundingBox!!.left}.joinToString("\t"){it.text}}
                                scanned+=i+1
                            } finally {bitmap.recycle()}
                        }
                    }
                }}
            } finally {recognizer.close()}
        }
        require(pages.any{it.count(Char::isLetterOrDigit)>=15}){"No readable text found. Try a clearer scan; handwriting and unsupported scripts may not be recognized."}
        require(pages.sumOf{it.length}<=500_000){"This PDF contains too much text. Split it into smaller plans."}
        return PdfReadResult(pages,scanned)
    }
}
