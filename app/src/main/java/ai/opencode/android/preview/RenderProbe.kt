package ai.opencode.android.preview

/**
 * v9.36 (owner): "medium-level web design took 20-30 screenshots for the
 * first page and burned huge tokens". The loop was starved: the agent's only
 * feedback after an edit was PIXELS, and every image it reads rides on every
 * later step (upstream re-sends them in full). This probe gives the agent a
 * TEXT render digest instead: one evaluateJavascript at the settled frame,
 * one compact `REPORT` line in `.preview/console.log`, plus the raw numbers
 * in `.preview/report.json`. Overflow, broken images and an empty render -
 * the things a "did my CSS work?" screenshot actually answers - are all
 * visible here for ~40 tokens.
 *
 * Finite by construction: a single evaluation per load, no polling, and the
 * caller decides what to do with the result. Kept free of WebView types so
 * the digest stays unit-testable on the JVM.
 */
object RenderProbe {

    /** Where the raw numbers land next to latest.png/latest.jpg. */
    const val REPORT_FILE = "report.json"

    /**
     * Evaluated in the page after it settles. Returns a plain object
     * (evaluateJavascript serializes it as JSON). Every accessor is guarded:
     * a broken page must yield a broken report, never a second exception.
     */
    const val JS =
        "(function(){try{var d=document,v=d.documentElement,b=d.body;" +
            "var imgs=[].slice.call(d.images||[]),ok=0,bad=0;" +
            "for(var i=0;i<imgs.length;i++){var im=imgs[i];if(im.complete){" +
            "if(im.naturalWidth>0){ok++}else{bad++}}}" +
            "var vw=(window.innerWidth||v.clientWidth||0),vh=(window.innerHeight||v.clientHeight||0);" +
            "var sw=Math.max(v.scrollWidth||0,(b&&b.scrollWidth)||0);" +
            "var sh=Math.max(v.scrollHeight||0,(b&&b.scrollHeight)||0);" +
            "return {viewport:[vw,vh],doc:[sw,sh],overflowX:Math.max(0,sw-vw)," +
            "imgs:ok,totalImgs:imgs.length,broken:bad,text:((b&&b.innerText)||'').length};" +
            "}catch(e){return {error:String(e&&e.message||e)}}})()"

    /**
     * Compact one-line digest for console.log, e.g.
     * `REPORT viewport=412x915 doc=412x2350 overflowX=0px imgs=4/5 broken=1
     * text=1810ch frame=changed`. `raw` is evaluateJavascript's serialization
     * (or null when the probe did not answer); any unparseable input yields
     * `REPORT unavailable` rather than a crash or a silent miss.
     */
    fun digest(raw: String?, frame: String? = null): String {
        val o = raw?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        if (o == null || o.has("error")) return "REPORT unavailable"
        val vp = o.optJSONArray("viewport")
        val doc = o.optJSONArray("doc")
        val vw = vp?.optInt(0, 0) ?: 0
        val vh = vp?.optInt(1, 0) ?: 0
        val dw = doc?.optInt(0, 0) ?: 0
        val dh = doc?.optInt(1, 0) ?: 0
        val sb = StringBuilder("REPORT")
            .append(" viewport=").append(vw).append('x').append(vh)
            .append(" doc=").append(dw).append('x').append(dh)
            .append(" overflowX=").append(o.optInt("overflowX", 0)).append("px")
            .append(" imgs=").append(o.optInt("imgs", 0))
            .append('/').append(o.optInt("totalImgs", 0))
            .append(" broken=").append(o.optInt("broken", 0))
            .append(" text=").append(o.optInt("text", 0)).append("ch")
        if (frame != null) sb.append(" frame=").append(frame)
        return sb.toString()
    }
}
