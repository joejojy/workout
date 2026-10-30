package com.joelj.workout

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class DateBasis { PDF_MONTH, FILENAME_MONTH, MONTH_YEAR_INFERRED, EXPLICIT_DATES, UNDATED_INFERRED, MANUAL, LEGACY }
enum class PlanDayState { UPCOMING, ACTIVE, UNSPECIFIED, FINISHED }

data class CalendarMapping(val from:LocalDate,val through:LocalDate,val anchor:LocalDate,val basis:DateBasis,val explanation:String) {
    val label:String get()=if(from.dayOfMonth==1&&through==YearMonth.from(from).atEndOfMonth())from.format(DateTimeFormatter.ofPattern("MMMM yyyy",Locale.ENGLISH)) else "$from – $through"
    val monthly:Boolean get()=from.dayOfMonth==1&&through==YearMonth.from(from).atEndOfMonth()
    fun toJson()=JSONObject().put("from",from.toString()).put("through",through.toString()).put("anchor",anchor.toString()).put("basis",basis.name).put("explanation",explanation)
    companion object {
        fun fromJson(j:JSONObject)=CalendarMapping(LocalDate.parse(j.getString("from")),LocalDate.parse(j.getString("through")),LocalDate.parse(j.getString("anchor")),DateBasis.valueOf(j.getString("basis")),j.getString("explanation"))
    }
}

object CalendarMapper {
    private val months=listOf("january","february","march","april","may","june","july","august","september","october","november","december")
    private const val MONTH="(?:January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)"
    private val monthYear=Regex("\\b($MONTH)[\\s,./_-]*(20\\d{2})\\b",RegexOption.IGNORE_CASE)
    private val yearMonth=Regex("\\b(20\\d{2})[\\s,./_-]+($MONTH)\\b",RegexOption.IGNORE_CASE)
    private val monthOnly=Regex("\\b($MONTH)\\b",RegexOption.IGNORE_CASE)
    private val iso=Regex("\\b(20\\d{2}-\\d{2}-\\d{2})\\b")
    private val dayMonth=Regex("\\b(\\d{1,2})\\s+($MONTH)(?:\\s*,?\\s*(20\\d{2}))?\\b",RegexOption.IGNORE_CASE)
    private val monthDay=Regex("\\b($MONTH)\\s+(\\d{1,2})(?:,?\\s+(20\\d{2}))?\\b",RegexOption.IGNORE_CASE)
    private fun month(name:String)=months.indexOfFirst{it.startsWith(name.lowercase().take(3))}+1
    private fun declaredMonth(text:String):YearMonth? {
        monthYear.find(text)?.let{return YearMonth.of(it.groupValues[2].toInt(),month(it.groupValues[1]))}
        yearMonth.find(text)?.let{return YearMonth.of(it.groupValues[1].toInt(),month(it.groupValues[2]))}
        return null
    }
    fun parseDate(value:String,year:Int):LocalDate? {
        iso.find(value)?.let{return runCatching{LocalDate.parse(it.value)}.getOrNull()}
        dayMonth.find(value)?.let{m->return runCatching{LocalDate.of(m.groupValues[3].toIntOrNull()?:year,month(m.groupValues[2]),m.groupValues[1].toInt())}.getOrNull()}
        monthDay.find(value)?.let{m->return runCatching{LocalDate.of(m.groupValues[3].toIntOrNull()?:year,month(m.groupValues[1]),m.groupValues[2].toInt())}.getOrNull()}
        return null
    }
    fun monthly(period:YearMonth,basis:DateBasis=DateBasis.PDF_MONTH):CalendarMapping {
        val anchor=period.atDay(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        val source=when(basis){DateBasis.FILENAME_MONTH->"Month detected in the filename";DateBasis.MONTH_YEAR_INFERRED->"Month detected; year inferred from the phone date";DateBasis.MANUAL->"Dates corrected by you";else->"Month detected in the PDF"}
        return CalendarMapping(period.atDay(1),period.atEndOfMonth(),anchor,basis,"$source. Numbered training weeks begin on the first Monday ($anchor). Opening partial days without a dated prescription stay unspecified.")
    }
    fun relative(first:LocalDate,weeks:Int,basis:DateBasis=DateBasis.UNDATED_INFERRED):CalendarMapping {
        val anchor=first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return CalendarMapping(first,anchor.plusWeeks(weeks.toLong()).minusDays(1),anchor,basis,
            if(basis==DateBasis.LEGACY)"Existing start date retained for this undated plan." else if(basis==DateBasis.MANUAL)"Dates corrected by you." else "No plan dates found. Automatically aligned to the current training week; weekday sessions retain their weekday.")
    }
    fun detect(raw:String,name:String,weeks:Int,today:LocalDate=LocalDate.now()):CalendarMapping {
        val normalized=raw.replace('_',' ')
        val heading=normalized.lineSequence().take(30).joinToString("\n")
        val knownMonth=declaredMonth(heading)
        val year=knownMonth?.year?:today.year
        // Only workout/date-range lines count as explicit dates; benchmarks and incidental notes do not.
        val dated=normalized.lines().filter{Regex("(?i)^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\b").containsMatchIn(it.trim())}.mapNotNull{parseDate(it,year)}
        if(dated.isNotEmpty()) {
            if(knownMonth!=null) {
                val period=knownMonth
                if(dated.all{YearMonth.from(it)==period})return monthly(period).copy(basis=DateBasis.EXPLICIT_DATES,explanation="Workout dates detected in the PDF. Written dates select sessions; week numbering follows the detected month’s first Monday.")
            }
            val from=dated.min();val through=dated.max()
            return CalendarMapping(from,through,from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),DateBasis.EXPLICIT_DATES,"Workout dates detected in the PDF. Written session dates take priority over inferred weeks.")
        }
        val rangeLine=normalized.lines().firstOrNull{Regex("(?i)^(plan dates|date range|period|valid|from|schedule dates)\\s*:").containsMatchIn(it.trim())}
        if(rangeLine!=null) {
            val candidates=iso.findAll(rangeLine).mapNotNull{runCatching{LocalDate.parse(it.value)}.getOrNull()}.toList()
            if(candidates.size>=2&&candidates[0]<=candidates[1]) {
                val from=candidates[0];val to=candidates[1]
                val anchor=if(from.dayOfMonth==1&&to==YearMonth.from(from).atEndOfMonth())from.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)) else from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                return CalendarMapping(from,to,anchor,DateBasis.EXPLICIT_DATES,"Plan date range detected in the PDF; training-week numbering is inferred from its Monday anchor.")
            }
        }
        if(knownMonth!=null)return monthly(knownMonth)
        declaredMonth(name.replace('_',' '))?.let{return monthly(it,DateBasis.FILENAME_MONTH)}
        monthOnly.find(heading)?.let{return monthly(YearMonth.of(today.year,month(it.groupValues[1])),DateBasis.MONTH_YEAR_INFERRED)}
        monthOnly.find(name.replace('_',' '))?.let{return monthly(YearMonth.of(today.year,month(it.groupValues[1])),DateBasis.MONTH_YEAR_INFERRED)}
        return relative(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),weeks)
    }
}
