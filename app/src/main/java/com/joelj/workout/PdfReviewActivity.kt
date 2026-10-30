package com.joelj.workout

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.os.Build
import android.widget.*

/** Original-page review, independent of third-party PDF viewer availability. */
class PdfReviewActivity:Activity(){
    private var renderer:PdfRenderer?=null
    private var descriptor:ParcelFileDescriptor?=null
    private var pageIndex=0
    private var bitmap:Bitmap?=null
    private lateinit var image:ImageView
    private lateinit var title:TextView
    private lateinit var previous:Button
    private lateinit var next:Button
    override fun onCreate(saved:Bundle?){
        super.onCreate(saved)
        pageIndex=saved?.getInt("page")?:intent.getIntExtra("page",0)
        val store=PlanStore(this)
        val file=if(intent.getBooleanExtra("draft",false))store.draftPdf else store.activePdf
        try{descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);renderer=PdfRenderer(descriptor!!)}
        catch(_:Exception){Toast.makeText(this,"The PDF could not be rendered. Try importing an unencrypted PDF.",Toast.LENGTH_LONG).show();finish();return}
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK)}
        root.setOnApplyWindowInsetsListener{v,i->if(Build.VERSION.SDK_INT>=30){val bars=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom)}else{@Suppress("DEPRECATION") v.setPadding(0,i.systemWindowInsetTop,0,i.systemWindowInsetBottom)};i}
        title=TextView(this).apply{setTextColor(Color.WHITE);textSize=15f;gravity=Gravity.CENTER;setPadding(16,18,16,18)};root.addView(title)
        val scroll=ScrollView(this);image=ImageView(this).apply{adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER};scroll.addView(image);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val nav=LinearLayout(this)
        fun action(text:String,clicked:()->Unit)=Button(this).apply{this.text=text;isAllCaps=false;setTextColor(Color.WHITE);backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(19,124,55));setOnClickListener{clicked()}}
        previous=action("← Page"){pageIndex--;showPage()};next=action("Page →"){pageIndex++;showPage()}
        nav.addView(previous,LinearLayout.LayoutParams(0,-2,1f));nav.addView(action("Back to review"){finish()},LinearLayout.LayoutParams(0,-2,1.3f));nav.addView(next,LinearLayout.LayoutParams(0,-2,1f));root.addView(nav)
        setContentView(root);root.requestApplyInsets();showPage()
    }
    private fun showPage(){
        val r=renderer?:return
        pageIndex=pageIndex.coerceIn(0,r.pageCount-1)
        r.openPage(pageIndex).use{page->
            val width=(resources.displayMetrics.widthPixels*1.5f).toInt().coerceIn(600,1400)
            val ratio=minOf(width.toFloat()/page.width,2400f/page.height)
            val fresh=Bitmap.createBitmap((page.width*ratio).toInt().coerceAtLeast(1),(page.height*ratio).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}
            page.render(fresh,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val old=bitmap;bitmap=fresh;image.setImageBitmap(fresh);old?.recycle()
        }
        title.text="Original PDF · ${pageIndex+1} / ${r.pageCount}"
        image.contentDescription="Original workout PDF, page ${pageIndex+1} of ${r.pageCount}"
        previous.isEnabled=pageIndex>0;next.isEnabled=pageIndex<r.pageCount-1
    }
    override fun onSaveInstanceState(out:Bundle){out.putInt("page",pageIndex);super.onSaveInstanceState(out)}
    override fun onDestroy(){imageOrNull()?.setImageDrawable(null);bitmap?.recycle();renderer?.close();descriptor?.close();super.onDestroy()}
    private fun imageOrNull():ImageView?=if(::image.isInitialized)image else null
}
