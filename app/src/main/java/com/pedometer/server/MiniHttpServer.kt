package com.pedometer.server

import android.util.Log
import com.pedometer.notification.WatchNotificationBridge
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

class MiniHttpServer(port: Int = 8765) : NanoHTTPD(port) {

    var onFindWatch: (() -> Unit)? = null
    var onWeatherUpdate: (() -> Unit)? = null
    var onGetStatus: (() -> String)? = null

    override fun start() {
        try {
            super.start()
            Log.i(TAG, "HTTP server started on port $listeningPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        val method = session.method

        Log.d(TAG, "$method $path")

        return try {
            when {
                path == "/notify" && method == Method.POST -> {
                    val body = readBody(session)
                    val json = JSONObject(body)
                    val title = json.optString("title", "")
                    val text = json.optString("body", json.optString("text", ""))
                    val app = json.optString("app", "PC")
                    val pkg = json.optString("package", "com.pedometer")
                    Log.i(TAG, "Notify: [$app] $title: $text")
                    WatchNotificationBridge.sendToWatch(
                        id = (System.currentTimeMillis() % 100000).toInt(),
                        packageName = pkg,
                        appName = app,
                        title = title,
                        body = text,
                    )
                    json("""{"ok":true,"msg":"sent"}""")
                }
                path == "/ask" && method == Method.POST -> {
                    val body = readBody(session)
                    val json = JSONObject(body)
                    val question = json.optString("q", json.optString("question", ""))
                    if (question.isBlank()) {
                        json("""{"error":"empty question"}""")
                    } else {
                        Log.i(TAG, "Ask: $question")
                        val answer = com.pedometer.assistant.LlmClient.ask(question)
                        WatchNotificationBridge.sendToWatch(
                            id = (System.currentTimeMillis() % 100000).toInt(),
                            packageName = "com.pedometer.assistant",
                            appName = "AI",
                            title = question.take(30),
                            body = answer,
                        )
                        json("""{"ok":true,"answer":${JSONObject.quote(answer)}}""")
                    }
                }
                path == "/find" -> {
                    onFindWatch?.invoke()
                    json("""{"ok":true,"msg":"ringing"}""")
                }
                path == "/weather" -> {
                    onWeatherUpdate?.invoke()
                    json("""{"ok":true,"msg":"updating"}""")
                }
                path == "/status" -> {
                    json(onGetStatus?.invoke() ?: """{"error":"not connected"}""")
                }
                path == "/dashboard" -> {
                    val status = onGetStatus?.invoke() ?: "{}"
                    val html = """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Watch</title>
<style>body{font-family:system-ui;max-width:500px;margin:20px auto;padding:0 16px;background:#111;color:#eee}
.card{background:#1e1e1e;border-radius:12px;padding:16px;margin:8px 0}
h1{font-size:20px}h2{font-size:14px;color:#888;margin:4px 0}
.big{font-size:32px;font-weight:bold}.hr{color:#e53935}.spo2{color:#42a5f5}.stress{color:#ff9800}.steps{color:#4caf50}
input,button{width:100%;padding:10px;margin:4px 0;border-radius:8px;border:1px solid #333;background:#222;color:#eee;font-size:14px;box-sizing:border-box}
button{background:#4caf50;border:none;cursor:pointer;font-weight:bold}
button:active{opacity:0.7}</style></head><body>
<h1>Watch Dashboard</h1>
<div class="card" id="stats"></div>
<div class="card"><h2>Уведомление</h2>
<input id="title" placeholder="Заголовок"><input id="body" placeholder="Текст">
<button onclick="send()">Отправить на часы</button></div>
<div class="card"><button onclick="fetch('/find')">Найти часы</button>
<button onclick="fetch('/weather')" style="background:#42a5f5">Обновить погоду</button></div>
<div class="card"><h2>Спросить AI</h2><input id="q" placeholder="Вопрос...">
<button onclick="ask()" style="background:#ff9800">Спросить</button><div id="answer"></div></div>
<script>
function load(){fetch('/status').then(r=>r.json()).then(d=>{
document.getElementById('stats').innerHTML='<div class="big steps">'+d.steps+' шагов</div>'
+'<h2>Пульс: <span class="hr">'+(d.hr||'—')+'</span> | SpO2: <span class="spo2">'+(d.spo2||'—')+'%</span> | Стресс: <span class="stress">'+(d.stress||'—')+'</span></h2>'
+'<h2>Батарея: '+d.battery+'% | '+(d.connected?'Подключено':'Отключено')+'</h2>'
})}
function send(){fetch('/notify',{method:'POST',headers:{'Content-Type':'application/json'},
body:JSON.stringify({title:document.getElementById('title').value,body:document.getElementById('body').value,app:'Web'})})}
function ask(){var q=document.getElementById('q').value;document.getElementById('answer').innerText='Думаю...';
fetch('/ask',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({q:q})})
.then(r=>r.json()).then(d=>{document.getElementById('answer').innerText=d.answer||d.error})}
load();setInterval(load,10000);
</script></body></html>"""
                    val resp = newFixedLengthResponse(Response.Status.OK, "text/html", html)
                    resp.addHeader("Access-Control-Allow-Origin", "*")
                    resp
                }
                path == "/" -> {
                    json("""{"endpoints":["/notify","/find","/weather","/status","/ask","/dashboard"],"usage":"POST /notify {\"title\":\"...\",\"body\":\"...\"}"}""")
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_JSON, """{"error":"not found"}""")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_JSON, """{"error":"${e.message}"}""")
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = session.inputStream.read(buf, read, len - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun json(body: String): Response {
        val resp = newFixedLengthResponse(Response.Status.OK, MIME_JSON, body)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    companion object {
        private const val TAG = "MiniHttpServer"
        private const val MIME_JSON = "application/json"
    }
}
