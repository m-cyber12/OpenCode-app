package ai.opencode.android.preview

import org.json.JSONArray
import org.json.JSONObject

/**
 * Watch and tap for the live preview (agent.md "Visual QA"; owner defaults on
 * 2026-10-31: the visible pane, selector or text first, ask once per preview
 * session, text read-back).
 *
 * The agent writes `.preview/act.json`; the app runs the batch on the open
 * pane and writes `.preview/act-result.txt`. This file is the pure part: the
 * batch format, validation, the in-page scripts and the text report. It has no
 * Android types, so it is unit-tested on the JVM. The WebView side lives in
 * [PreviewDriver].
 */

/** One step the agent asked the live preview to run. */
sealed class PreviewStep {
    abstract val label: String

    /** Tap by CSS [selector] or visible [text]; [x]/[y] (CSS px) only when neither names the target. */
    data class Tap(val selector: String?, val text: String?, val x: Int?, val y: Int?) : PreviewStep() {
        override val label: String
            get() = when {
                selector != null -> "tap selector=$selector"
                text != null -> "tap text=\"$text\""
                else -> "tap at x=$x y=$y"
            }
    }

    data class Type(val selector: String, val value: String) : PreviewStep() {
        override val label: String get() = "type selector=$selector"
    }

    data class Scroll(val y: Int) : PreviewStep() {
        override val label: String get() = "scroll y=$y"
    }

    data class Wait(val ms: Long) : PreviewStep() {
        override val label: String get() = "wait ms=$ms"
    }

    object Snapshot : PreviewStep() {
        override val label: String = "snapshot"
    }
}

/** A validated batch: one id, one to [PreviewActions.MAX_STEPS] steps. */
data class PreviewBatch(val id: String, val steps: List<PreviewStep>)

sealed class ParseResult {
    data class Ok(val batch: PreviewBatch) : ParseResult()
    data class Invalid(val reason: String) : ParseResult()
}

/** The outcome of one step, as written into the result file. */
data class StepResult(val label: String, val ok: Boolean, val detail: String)

object PreviewActions {
    const val ACTION_FILE = "act.json"
    const val RESULT_FILE = "act-result.txt"
    const val MAX_STEPS = 20
    const val MAX_WAIT_MS = 5000L
    const val MAX_VALUE = 200
    private const val MAX_SCROLL = 5000
    private const val MAX_COORD = 10000
    private const val MAX_ELEMENTS = 40
    private val ID = Regex("[A-Za-z0-9_-]{1,40}")

    /** Parse and validate the agent's batch. Anything unclear is rejected with a reason. */
    fun parse(text: String): ParseResult {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return ParseResult.Invalid("act.json is not valid JSON")
        }
        val id = root.optString("id", "")
        if (!ID.matches(id)) return ParseResult.Invalid("id must be 1-40 letters, digits, '-' or '_'")
        val array = root.optJSONArray("steps") ?: return ParseResult.Invalid("steps must be an array")
        if (array.length() == 0) return ParseResult.Invalid("steps is empty")
        if (array.length() > MAX_STEPS) return ParseResult.Invalid("at most $MAX_STEPS steps per batch")
        val steps = ArrayList<PreviewStep>(array.length())
        for (i in 0 until array.length()) {
            val n = i + 1
            val obj = array.optJSONObject(i) ?: return ParseResult.Invalid("step $n is not an object")
            if (obj.length() != 1) return ParseResult.Invalid("step $n must have exactly one action")
            val key = obj.keys().next()
            val body = obj.opt(key)
            val step: PreviewStep = when (key) {
                "tap" -> parseTap(body) ?: return ParseResult.Invalid("step $n: tap needs selector, text, or x and y")
                "type" -> parseType(body) ?: return ParseResult.Invalid("step $n: type needs selector and value (max $MAX_VALUE chars)")
                "scroll" -> parseScroll(body) ?: return ParseResult.Invalid("step $n: scroll needs y")
                "wait" -> parseWait(body) ?: return ParseResult.Invalid("step $n: wait needs ms")
                "snapshot" -> if (body == true) PreviewStep.Snapshot else return ParseResult.Invalid("step $n: snapshot must be true")
                else -> return ParseResult.Invalid("step $n: unknown action \"$key\"")
            }
            steps += step
        }
        return ParseResult.Ok(PreviewBatch(id, steps))
    }

    private fun parseTap(body: Any?): PreviewStep.Tap? {
        val o = body as? JSONObject ?: return null
        val selector = o.optString("selector", "").takeIf { it.isNotEmpty() }?.take(MAX_VALUE)
        val text = o.optString("text", "").takeIf { it.isNotEmpty() }?.take(MAX_VALUE)
        val hasXY = o.has("x") && o.has("y")
        val x = if (hasXY) o.optInt("x") else null
        val y = if (hasXY) o.optInt("y") else null
        if (x != null && (x < -MAX_COORD || x > MAX_COORD)) return null
        if (y != null && (y < -MAX_COORD || y > MAX_COORD)) return null
        if (selector == null && text == null && !hasXY) return null
        return PreviewStep.Tap(selector, text, x, y)
    }

    private fun parseType(body: Any?): PreviewStep.Type? {
        val o = body as? JSONObject ?: return null
        val selector = o.optString("selector", "").takeIf { it.isNotEmpty() } ?: return null
        if (!o.has("value")) return null
        val value = o.optString("value", "")
        if (value.length > MAX_VALUE) return null
        return PreviewStep.Type(selector.take(MAX_VALUE), value)
    }

    private fun parseScroll(body: Any?): PreviewStep.Scroll? {
        val o = body as? JSONObject ?: return null
        if (!o.has("y")) return null
        return PreviewStep.Scroll(o.optInt("y").coerceIn(-MAX_SCROLL, MAX_SCROLL))
    }

    private fun parseWait(body: Any?): PreviewStep.Wait? {
        val o = body as? JSONObject ?: return null
        if (!o.has("ms")) return null
        return PreviewStep.Wait(o.optLong("ms").coerceIn(0L, MAX_WAIT_MS))
    }

    /** One line for the permission prompt, e.g. `tap text="Sign in"; type selector=#email`. */
    fun describe(batch: PreviewBatch): String =
        batch.steps.joinToString("; ") { it.label }.take(300)

    /**
     * Read the in-page script's answer. [raw] is what `evaluateJavascript`
     * gives back: a JSON-encoded string, or null. Anything unreadable is a
     * failed step, never a silent pass.
     */
    fun outcome(step: PreviewStep, raw: String?): StepResult {
        val json = decodeJs(raw) ?: return StepResult(step.label, false, "no answer from the page")
        val o = try {
            JSONObject(json)
        } catch (e: Exception) {
            return StepResult(step.label, false, "unreadable answer from the page")
        }
        if (!o.optBoolean("ok", false)) {
            return StepResult(step.label, false, o.optString("reason", "failed"))
        }
        val detail = when (step) {
            is PreviewStep.Tap -> "-> hit=${o.optString("hit")} at ${o.optInt("x")},${o.optInt("y")}"
            is PreviewStep.Type -> "-> typed ${o.optInt("length")} chars"
            is PreviewStep.Scroll -> "-> scrollY=${o.optInt("scrollY")}"
            is PreviewStep.Wait -> ""
            is PreviewStep.Snapshot -> snapshotText(o)
        }
        return StepResult(step.label, true, detail)
    }

    private fun snapshotText(o: JSONObject): String {
        val total = o.optInt("total")
        val list = o.optJSONArray("elements") ?: JSONArray()
        val sb = StringBuilder("-> title=\"${o.optString("title").take(80)}\" tappable=$total shown=${list.length()}")
        for (i in 0 until minOf(list.length(), MAX_ELEMENTS)) {
            val e = list.optJSONObject(i) ?: continue
            val tag = e.optString("tag")
            val type = e.optString("type").takeIf { it.isNotEmpty() }?.let { "[$it]" } ?: ""
            val id = e.optString("id").takeIf { it.isNotEmpty() }?.let { "#$it" } ?: ""
            val text = e.optString("text").take(60)
            sb.append("\n    - $tag$type$id \"$text\"")
        }
        return sb.toString()
    }

    /**
     * The text report the agent reads first. [results] may be shorter than the
     * batch: the rest were skipped after the first failure. [consoleErrors] are
     * the ERROR and LOAD_ERROR lines since this batch started.
     */
    fun digest(batch: PreviewBatch, results: List<StepResult>, ms: Long, consoleErrors: List<String>): String {
        val total = batch.steps.size
        val done = results.count { it.ok }
        val allOk = results.size == total && results.all { it.ok }
        val sb = StringBuilder()
        sb.append("ACT ${batch.id} ${if (allOk) "ok" else "failed"} steps=$done/$total ms=$ms\n")
        results.forEachIndexed { i, r ->
            sb.append(" ${i + 1} ${r.label} ${if (r.ok) "ok" else "FAILED: ${r.detail}"}")
            if (r.ok && r.detail.isNotEmpty()) sb.append(" ${r.detail}")
            sb.append('\n')
        }
        if (results.size < total) {
            sb.append(" ${total - results.size} step(s) skipped after the first failure\n")
        }
        sb.append("CONSOLE errors=${consoleErrors.size}")
        consoleErrors.take(3).forEach { sb.append("\n  ").append(it.take(160)) }
        return sb.toString()
    }

    /** Decode a `evaluateJavascript` answer (a JSON-encoded string, or "null"). */
    internal fun decodeJs(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return try {
            JSONArray("[$raw]").getString(0)
        } catch (e: Exception) {
            null
        }
    }

    // ---- in-page scripts ---------------------------------------------------
    // Every value from the agent goes through JSONObject.quote, so a selector
    // or text cannot break out of its string literal.

    private fun lit(s: String?): String = if (s == null) "null" else JSONObject.quote(s)
    private fun num(n: Int?): String = n?.toString() ?: "null"

    /** Helpers shared by the scripts. Plain ES5 plus the events every WebView supports. */
    private const val HELPERS = """
var norm=function(s){return (s||'').replace(/\s+/g,' ').trim().toLowerCase();};
var vis=function(e){var r=e.getBoundingClientRect();var cs=getComputedStyle(e);return r.width>0&&r.height>0&&cs.visibility!=='hidden'&&cs.display!=='none';};
var label=function(e){return e.innerText||e.value||e.getAttribute('aria-label')||e.placeholder||'';};
var desc=function(e){if(!e)return 'none';var s=e.tagName.toLowerCase();if(e.id)s+='#'+e.id;var c=typeof e.className==='string'?e.className.trim().split(/\s+/).slice(0,2).join('.'):'';if(c)s+='.'+c;return s;};
var INTER='a,button,input,select,textarea,summary,label,[role=button],[role=link],[role=tab],[role=menuitem],[onclick]';
var findText=function(t){var want=norm(t);var inter=[].slice.call(document.querySelectorAll(INTER)).filter(vis);var hit=inter.filter(function(e){return norm(label(e))===want;});if(hit.length)return hit[0];hit=inter.filter(function(e){return norm(label(e)).indexOf(want)>=0;});if(hit.length)return hit[hit.length-1];var all=[].slice.call(document.querySelectorAll('*')).filter(vis).filter(function(e){return norm(label(e))===want;});return all.length?all[all.length-1]:null;};
"""

    fun tapScript(selector: String?, text: String?, x: Int?, y: Int?): String = wrap(
        """
var sel=${lit(selector)},txt=${lit(text)},px=${num(x)},py=${num(y)};
var el=null,cx=px,cy=py;
try{if(sel!==null){el=document.querySelector(sel);}else if(txt!==null){el=findText(txt);}}catch(err){return JSON.stringify({ok:false,reason:'bad selector'});}
if(sel!==null||txt!==null){
  if(!el)return JSON.stringify({ok:false,reason:'not found'});
  if(!vis(el))return JSON.stringify({ok:false,reason:'not visible'});
  el.scrollIntoView({block:'center',inline:'center'});
  var r=el.getBoundingClientRect();cx=r.left+r.width/2;cy=r.top+r.height/2;
}
if(cx===null||cy===null)return JSON.stringify({ok:false,reason:'no target'});
var hit=document.elementFromPoint(cx,cy);
if(!hit)return JSON.stringify({ok:false,reason:'outside the page'});
if(el&&!(hit===el||el.contains(hit)))return JSON.stringify({ok:false,reason:'obstructed by '+desc(hit)});
var target=el||hit;
var o={bubbles:true,cancelable:true,composed:true,view:window,clientX:cx,clientY:cy,button:0,buttons:1};
try{target.dispatchEvent(new PointerEvent('pointerdown',o));}catch(err){}
target.dispatchEvent(new MouseEvent('mousedown',o));
try{target.dispatchEvent(new PointerEvent('pointerup',o));}catch(err){}
target.dispatchEvent(new MouseEvent('mouseup',o));
target.dispatchEvent(new MouseEvent('click',o));
return JSON.stringify({ok:true,hit:desc(target),x:Math.round(cx),y:Math.round(cy)});
"""
    )

    fun typeScript(selector: String, value: String): String = wrap(
        """
var el=null;
try{el=document.querySelector(${lit(selector)});}catch(err){return JSON.stringify({ok:false,reason:'bad selector'});}
if(!el)return JSON.stringify({ok:false,reason:'not found'});
var tag=el.tagName.toLowerCase();var type=(el.type||'').toLowerCase();
var notText=['checkbox','radio','file','button','submit','image','range','color','hidden'];
var isText=el.isContentEditable||tag==='textarea'||(tag==='input'&&notText.indexOf(type)<0);
if(!isText)return JSON.stringify({ok:false,reason:'not a text field: '+desc(el)});
el.scrollIntoView({block:'center'});el.focus();
var val=${lit(value)};
if(el.isContentEditable){el.textContent=val;}else{var proto=tag==='textarea'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(proto,'value').set.call(el,val);}
el.dispatchEvent(new Event('input',{bubbles:true}));
el.dispatchEvent(new Event('change',{bubbles:true}));
return JSON.stringify({ok:true,length:val.length});
"""
    )

    fun scrollScript(y: Int): String = wrap(
        """
window.scrollBy(0,${num(y)});
return JSON.stringify({ok:true,scrollY:Math.round(window.scrollY)});
"""
    )

    val SNAPSHOT_SCRIPT: String = wrap(
        """
var all=[].slice.call(document.querySelectorAll(INTER)).filter(vis);
var out=all.slice(0,$MAX_ELEMENTS).map(function(e){return {tag:e.tagName.toLowerCase(),text:norm(label(e)).slice(0,60),id:e.id||'',type:e.type||''};});
return JSON.stringify({ok:true,total:all.length,elements:out,title:document.title});
"""
    )

    private fun wrap(body: String): String = "(function(){$HELPERS$body})()"
}
