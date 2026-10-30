package com.joelj.workout

import java.text.Normalizer
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.time.temporal.ChronoUnit

/** Layout-independent schedule rows, dated weeks and reusable exercise libraries. */
object FlexiblePlanParser {
    private const val DAY="Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|Mon|Tue(?:s)?|Wed|Thu(?:rs)?|Fri|Sat|Sun"
    private val dayRow=Regex("^($DAY)\\b[.,]?\\s*(.*)$",RegexOption.IGNORE_CASE)
    private val weekHeading=Regex("^(?:This\\s+)?Week\\s*(\\d+)?\\s*\\(([^)]+)\\)",RegexOption.IGNORE_CASE)
    private val libraryHeading=Regex("^(Push|Pull|Legs|Core)(?:\\s*\\([^)]*\\))?\\s*$",RegexOption.IGNORE_CASE)
    private val prescription=Regex("^(.+?)\\s+(\\d+(?:[–-]\\d+)?(?:/side)?(?:\\s*[x×]\\s*.+|\\s*\\([^)]*\\))?)$")
    private val names=listOf("mon","tue","wed","thu","fri","sat","sun")
    fun normalize(raw:String)=Normalizer.normalize(raw,Normalizer.Form.NFKC).replace('\u00a0',' ').replace(Regex("(?m)^\\s*[•●▪]\\s*"),"")
        .replace(Regex("\\bW ed\\b"),"Wed").replace(Regex("\\bW arm"),"Warm").replace(Regex("\\bO verhead"),"Overhead")
    private fun day(value:String)=names.indexOf(value.lowercase().take(3))+1
    data class Entry(val day:Int,val week:Int,val date:LocalDate?,val page:Int,val parts:MutableList<String>)
    fun parseOrNull(raw:String,name:String):Plan? {
        val normalized=normalize(raw)
        val numbered=weekHeading.containsMatchIn(normalized.lineSequence().firstOrNull{weekHeading.containsMatchIn(it.trim())}.orEmpty())
        // Preserve the tested detailed October interpreter. Tables with dated week
        // headings and more general weekday headings use the flexible interpreter.
        if(!numbered&&Regex("(?im)^MONDAY\\s*[—–]\\s*Push / Upper-body").containsMatchIn(normalized))return null
        val mapping=CalendarMapper.detect(normalized,name,4)
        val year=mapping.from.year
        val entries=mutableListOf<Entry>()
        val libraries=mutableMapOf<String,MutableList<String>>()
        val libraryPages=mutableMapOf<String,Int>()
        var current:Entry?=null;var week=0;var monday:LocalDate?=null;var library:String?=null
        var inLibraries=false;var stopped=false;var page=1
        val warnings=mutableListOf<String>()
        val writtenRanges=mutableListOf<Pair<LocalDate,LocalDate>>()
        val notes=mutableListOf<String>()
        for(rawLine in normalized.lines()) {
            if(rawLine.contains('\u000c')){page+=rawLine.count{it=='\u000c'};continue}
            val line=rawLine.trim().trimStart('|').trim().replace(Regex("[ \\t]+")," ").replace(Regex("\\s+\\|(?=\\d)")," ")
            if(line.isBlank()||Regex("(?i)^(Day Session|Exercise Sets.*|Page \\d+|Day Training)$").matches(line))continue
            if(Regex("(?i)^Push\\s*/\\s*Pull\\s*/\\s*Legs.*Exercise Breakdown").containsMatchIn(line)) {inLibraries=true;current=null;library=null;continue}
            if(inLibraries) {
                val h=libraryHeading.matchEntire(line)
                if(h!=null){library=h.groupValues[1].lowercase();libraries.getOrPut(library!!){mutableListOf()};libraryPages[library!!]=page;continue}
                if(line.startsWith("Equipment",true)||line.startsWith("Notes on",true)||line.startsWith("How to",true)||line.startsWith("Interval Execution",true)||line=="Notes"||line.startsWith("Suggested placement",true)||line.startsWith("Placement:",true)){library=null}
                if(library!=null&&!line.startsWith("Exercise",true))libraries.getValue(library!!)+=line else notes+=line
                continue
            }
            val wh=weekHeading.find(line)
            if(wh!=null) {
                week=wh.groupValues[1].toIntOrNull()?:1
                val first=wh.groupValues[2].split(Regex("[–—]|(?<=\\d)\\s*-\\s*(?=[A-Za-z])|\\s+-\\s+")).first().replace(Regex("(?i)^($DAY)\\s+"),"")
                val start=CalendarMapper.parseDate(first,year)
                val endText=wh.groupValues[2].split(Regex("[–—]|(?<=\\d)\\s*-\\s*(?=[A-Za-z])|\\s+-\\s+")).getOrNull(1)?.replace(Regex("(?i)^($DAY)\\s+"),"")
                val end=endText?.let{CalendarMapper.parseDate(it,year)}
                if(start!=null&&end!=null&&end>=start)writtenRanges+=start to end
                monday=start?.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                current=null;stopped=false;continue
            }
            if(Regex("(?i)^(Notes|How to |Paces|Updated paces|Calisthenics Progression|End-of-)").containsMatchIn(line)){current=null;stopped=true}
            val h=if(!stopped)dayRow.matchEntire(line) else null
            if(h!=null) {
                val d=day(h.groupValues[1]);var rest=h.groupValues[2].trim().trimStart('—','–','-',':','|').trim()
                val explicit=CalendarMapper.parseDate(rest,year)
                if(explicit!=null){
                    rest=rest.replace(Regex("^(?:\\d{4}-\\d{2}-\\d{2}|[A-Za-z]+\\s+\\d{1,2}(?:,?\\s+20\\d{2})?|\\d{1,2}\\s+[A-Za-z]+(?:\\s+20\\d{2})?)(?:\\s*\\((?:today|tomorrow)\\))?\\s*"),"").trimStart('—','–','-',':','|').trim()
                }
                if(rest.isBlank()) {
                    // Vertically centred weekday cells can be extracted after the
                    // first line of the adjacent, wrapped workout cell.
                    val previous=current
                    val tailIndex=previous?.parts?.drop(1)?.indexOfFirst{Regex("(?i)^(Hard Run|Easy Run|Long Run|Push|Pull|Legs|Rest)\\b").containsMatchIn(it)}?:-1
                    val split=if(tailIndex>=0)tailIndex+1 else -1
                    val carry=if(previous!=null&&split>0)previous.parts.subList(split,previous.parts.size).toList() else emptyList()
                    if(previous!=null&&split>0)previous.parts.subList(split,previous.parts.size).clear()
                    current=Entry(d,week,explicit?:monday?.plusDays((d-1).toLong()),page,carry.toMutableList());entries+=current!!;continue
                }
                current=Entry(d,week,explicit?:monday?.plusDays((d-1).toLong()),page,mutableListOf(rest));entries+=current!!
            } else if(current!=null)current!!.parts+=line else notes+=line
        }
        if(entries.isEmpty())return null
        fun exercises(key:String):List<String> {
            val output=mutableListOf<String>();var pending=""
            for(line in libraries[key].orEmpty()) {
                if(line.startsWith("Exercise",true)||line.startsWith("Same ",true))continue
                val match=prescription.matchEntire(line)
                if(match!=null){
                    val label=(pending+" "+match.groupValues[1]).trim();pending=""
                    val value=match.groupValues[2]
                    output+="$label: $value${if(!Regex("[x×]|sec|hold",RegexOption.IGNORE_CASE).containsMatchIn(value))" sets" else ""}"
                } else if(output.isNotEmpty()&&(line=="side"||line=="hold")) {
                    output[output.lastIndex]=output.last()+if(line=="side")"side" else " $line"
                } else if(output.isNotEmpty()&&line.contains(')')&&!output.last().substringBefore(':').contains(')')) {
                    val previous=output.last();val label=previous.substringBefore(':')+" "+line.substringBefore(')')+")"
                    output[output.lastIndex]=label+": "+previous.substringAfter(':').trim()+if(line.endsWith("side"))"side" else ""
                } else {
                    output+="$line: Prescription unclear — check original"
                    warnings+="Check the exercise prescription for ‘$line’ on page ${libraryPages[key]}; the text was kept without guessing sets or reps."
                }
            }
            if(pending.isNotBlank())output+="Library note: $pending"
            return output
        }
        val libraryRows=libraries.keys.associateWith(::exercises)
        val timerHeader=normalized.lineSequence().firstOrNull{it.trim().startsWith("Interval timer app settings",true)}
        val timerDate=timerHeader?.let{CalendarMapper.parseDate(it,year)}
        val timerText=timerHeader?.let{normalized.substringAfter(it).substringBefore("\nNotes")}.orEmpty()
        val datedTimerFacts=listOf("Prepare" to "Preparation time","Timer round" to "Round time","Recovery" to "Rest time","Rounds" to "Rounds").mapNotNull{(label,field)->
            Regex("(?i)$field\\s*\\|?\\s*(\\d{1,2}:\\d{2}|\\d+)").find(timerText)?.let{Metric(label,it.groupValues[1])}
        }
        val sessions=entries.mapNotNull{e->
            if(e.parts.isEmpty()){warnings+="Page ${e.page}: empty weekday row needs checking.";return@mapNotNull null}
            val text=e.parts.joinToString(" ").replace(Regex("([–-])\\s+(?=\\d)"),"$1")
            val unclear=Regex("\\b\\d+[OoIl][A-Za-z0-9]*(?:m|km)\\b").findAll(text).map{it.value}.toList()
            if(unclear.isNotEmpty())warnings+="Page ${e.page}: check unclear number/unit ${unclear.joinToString()} in this session. The recognized text was preserved without guessing."
            val title=text.substringBefore(" — ").substringBefore(" – ").substringBefore(":").trim().ifBlank{"Workout"}
            val kind=when {
                Regex("(?i)^(?:complete\\s+)?rest\\b|^recovery day\\b").containsMatchIn(title)->Kind.REST
                Regex("(?i)run|interval|tempo|time trial|hills").containsMatchIn(title)->Kind.RUN
                Regex("(?i)push|pull|legs|strength|upper|lower|core|calisthenics").containsMatchIn(title)->Kind.STRENGTH
                else->Kind.OTHER
            }
            val libraryKey=listOf("push","pull","legs","core").firstOrNull{title.startsWith(it,true)}
            val exerciseRows=libraryRows[libraryKey].orEmpty()
            val details=if(exerciseRows.isNotEmpty())exerciseRows+listOf("Session notes: $text") else if(kind==Kind.STRENGTH)e.parts.drop(1).map{part->prescription.matchEntire(part)?.let{"${it.groupValues[1]}: ${it.groupValues[2]}"}?:part} else listOf(text)
            if(kind==Kind.OTHER)warnings+="Page ${e.page}: session type for ‘$title’ needs checking; its text was preserved."
            if(kind==Kind.STRENGTH&&exerciseRows.isEmpty()&&details.isEmpty())warnings+="Page ${e.page}: no exercise library found for ‘$title’."
            val prescriptionText=text.substringAfter(":",text.substringAfter(" — ",text)).trim()
            val continuous=Regex("(?i)\\d+\\s*min\\s+continuous(?:\\s*@\\s*[\\d:–.-]+/km)?").find(prescriptionText)?.value
            val target=when(kind){Kind.REST->"No training scheduled";Kind.STRENGTH->if(exerciseRows.isNotEmpty())"${exerciseRows.size} movements" else "See session details";else->continuous?:prescriptionText.substringBefore(',')}
            val timing=Regex("(?im)^\\s*${e.week}\\s+\\d+\\s*[x×]\\s*\\d+\\s*m\\s+(\\d{2}:\\d{2})\\s+(\\d{2}:\\d{2})\\s+(\\d{2}:\\d{2})\\s+(\\d+)").find(normalized)
            val metrics=if(kind==Kind.RUN&&title.contains("Hard",true)&&timing!=null)listOf(Metric("Prepare",timing.groupValues[1]),Metric("Timer round",timing.groupValues[2]),Metric("Recovery",timing.groupValues[3]),Metric("Rounds",timing.groupValues[4])) else if(kind==Kind.RUN&&e.date!=null&&e.date==timerDate)datedTimerFacts else emptyList()
            Session(day=e.day,week=e.week,kind=kind,title=title,target=target,details=details,metrics=metrics,date=e.date,sourcePages=(listOf(e.page)+listOfNotNull(libraryPages[libraryKey])).distinct())
        }
        if(sessions.isEmpty())return null
        require(sessions.size<=400){"This plan has more than 400 sessions. Split it into smaller plans."}
        val dated=sessions.mapNotNull{it.date}
        val first=(dated+writtenRanges.map{it.first}).minOrNull();val last=(dated+writtenRanges.map{it.second}).maxOrNull()
        val calendar=if(first!=null&&last!=null)CalendarMapping(first,last,first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),DateBasis.EXPLICIT_DATES,"Written schedule dates detected. Partial weeks and sessions extending into the next month are preserved.") else mapping
        val weeks=maxOf(1,sessions.maxOf{it.week},ChronoUnit.WEEKS.between(calendar.anchor,calendar.through).toInt()+1)
        require(weeks<=52){"Choose a plan with at most 52 weeks."}
        if(libraryRows["core"].orEmpty().isNotEmpty())warnings+="Optional core library is in the extracted text. Its 2–3-times-per-week recommendation was not turned into extra dated sessions."
        for((from,to) in writtenRanges) {
            val missing=generateSequence(from){it.plusDays(1)}.takeWhile{it<=to}.filter{d->sessions.none{it.date==d}}.toList()
            if(missing.isNotEmpty())warnings+="No session extracted for ${missing.joinToString()} inside a written schedule range. Check these dates in the original; they are not assumed to be rest days."
        }
        warnings.add(0,"Check extracted sessions against the original pages, especially wrapped table rows, units, and inferred dates. General notes remain available in ‘Read extracted text’.")
        if(!numbered&&dated.isEmpty())warnings+="No dated weekly table found. Weekday sessions use the automatically detected calendar; check the schedule."
        return Plan(name.removeSuffix(".pdf").replace('_',' '),weeks,sessions,warnings.distinct(),calendar.anchor,calendar)
    }
}
