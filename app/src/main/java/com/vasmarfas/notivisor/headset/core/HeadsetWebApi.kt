package com.vasmarfas.notivisor.headset.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.vasmarfas.notivisor.core.adb.AdbAccess
import com.vasmarfas.notivisor.core.adb.AdbConnection
import com.vasmarfas.notivisor.core.control.CastQuality
import com.vasmarfas.notivisor.core.control.CastSource
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.util.AppVersion
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.core.util.Clipboard
import com.vasmarfas.notivisor.core.util.localAddress
import com.vasmarfas.notivisor.headset.service.HeadsetCastService
import dadb.Dadb
import dadb.InstallResult
import dadb.SyncResult
import okio.sink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class HeadsetWebApi(private val context: Context, private val hub: CastHub) : WebApi {

    private val settings = BridgeSettings.get(context)
    private val icons = ConcurrentHashMap<String, ByteArray>()
    private val adb: AdbAccess? get() = HeadsetAdb.access.value
    private val controlled: Boolean get() = settings.webControl && settings.webPassword != null

    @Volatile
    private var checkedAt = 0L

    init {
        checkAdb()
    }

    override fun handle(request: WebRequest): WebResponse? {
        val route = "${request.method} ${request.path.removePrefix("/api/")}"
        when (route) {
            "GET state" -> return WebResponse.json(state().toString())
            "POST keyframe" -> {
                hub.refresh()
                return WebResponse.EMPTY
            }
        }
        if (!controlled) return WebResponse.error("403 Forbidden", "control_off")
        return when (route) {
            "POST cast" -> configure(JSONObject(request.text()))
            "POST adb/check" -> {
                checkAdb()
                WebResponse.json("{\"adb\":\"CHECKING\"}")
            }

            "GET apps" -> WebResponse.json(JSONObject().put("apps", apps()).toString())
            "GET icon" -> icon(request.query["package"].orEmpty())
            "GET apps/apk" -> apk(request.query["package"].orEmpty())
            "POST apps/launch" -> launch(packageOf(request) ?: return BAD_PACKAGE)
            "POST apps/stop" -> {
                val name = packageOf(request) ?: return BAD_PACKAGE
                shell { it.report("am force-stop $name") }
            }

            "POST apps/uninstall" -> {
                val name = packageOf(request) ?: return BAD_PACKAGE
                if (name == context.packageName) return WebResponse.error("400 Bad Request", "self")
                shell { it.report("pm uninstall $name", expect = "Success") }
            }

            "POST install" -> install(request)
            "GET files" -> list(request.query["path"] ?: Environment.getExternalStorageDirectory().path)
            "GET file" -> download(request.query["path"].orEmpty())
            "PUT file" -> upload(request)
            "POST files/delete" -> {
                val path = pathOf(JSONObject(request.text()).optString("path")) ?: return BAD_PATH
                if (path.trim('/').count { it == '/' } < 1) return BAD_PATH
                shell { it.report("rm -rf -- ${quote(path)}") }
            }

            "POST files/mkdir" -> {
                val path = pathOf(JSONObject(request.text()).optString("path")) ?: return BAD_PATH
                shell { it.report("mkdir -p -- ${quote(path)}") }
            }

            "POST files/rename" -> {
                val body = JSONObject(request.text())
                val from = pathOf(body.optString("from")) ?: return BAD_PATH
                val to = pathOf(body.optString("to")) ?: return BAD_PATH
                shell { it.report("mv -- ${quote(from)} ${quote(to)}") }
            }

            "POST shell" -> {
                val command = JSONObject(request.text()).optString("command")
                shell(COMMAND_TIMEOUT_MS) { connection ->
                    val response = connection.shell(command)
                    val output = response.allOutput.take(MAX_OUTPUT_CHARS)
                    WebResponse.json(JSONObject().put("output", output).put("exit", response.exitCode).toString())
                }
            }

            "GET screenshot" -> shell { connection ->
                val stream = connection.open("exec:screencap -p")
                WebResponse(type = "image/png", resource = connection) { out ->
                    stream.source.inputStream().copyTo(out)
                }
            }

            "POST input" -> input(JSONObject(request.text()))
            "POST open" -> open(JSONObject(request.text()).optString("url"))
            "POST clipboard" -> {
                Clipboard.write(context, JSONObject(request.text()).optString("text"))
                WebResponse.EMPTY
            }

            "POST proximity" -> {
                val off = JSONObject(request.text()).optBoolean("off")
                if (adb != AdbAccess.READY) return adbMissing()
                val now = HeadsetProximity.set(context, off)
                WebResponse.json(JSONObject().put("proximityOff", now ?: JSONObject.NULL).toString())
            }

            "POST power" -> {
                if (JSONObject(request.text()).optString("action") != "reboot") return BAD_REQUEST
                shell { connection ->
                    runCatching { connection.shell("reboot") }
                    WebResponse.EMPTY
                }
            }

            "GET log" -> WebResponse.json(JSONObject().put("lines", JSONArray(BridgeLog.snapshot())).toString())
            else -> null
        }
    }

    private fun state(): JSONObject {
        if (adb != AdbAccess.READY && adb != AdbAccess.UNAUTHORIZED &&
            System.nanoTime() - checkedAt > RECHECK_NS
        ) {
            checkAdb()
        }
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        }
        val storage = StatFs(Environment.getDataDirectory().path)
        return JSONObject()
            .put(
                "device",
                JSONObject()
                    .put("name", Build.MODEL)
                    .put("android", Build.VERSION.RELEASE)
                    .put("app", AppVersion.name(context) ?: "")
                    .put("address", localAddress(context) ?: ""),
            )
            .put(
                "battery",
                JSONObject()
                    .put("level", level ?: JSONObject.NULL)
                    .put("charging", (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0),
            )
            .put("storage", JSONObject().put("free", storage.availableBytes).put("total", storage.totalBytes))
            .put("cast", cast())
            .put("control", controlled)
            .put(
                "adb",
                when {
                    adb == AdbAccess.READY -> AdbAccess.READY.name
                    HeadsetAdb.checking.value -> "CHECKING"
                    else -> (adb ?: AdbAccess.OFFLINE).name
                },
            )
            .put(
                "proximityOff",
                (if (adb == AdbAccess.READY) HeadsetProximity.state(context) else null) ?: JSONObject.NULL,
            )
    }

    private fun cast(chosen: CastSource? = null): JSONObject {
        val status = HeadsetCastService.status.value
        val config = settings.castConfig
        val source = chosen ?: status.source ?: settings.castSource
        return JSONObject()
            .put("source", source.name)
            .put("sources", JSONArray((WEB_SOURCES + source).distinct().map { it.name }))
            .put("quality", config.quality.name)
            .put(
                "qualities",
                JSONArray(CastQuality.entries.map {
                    JSONObject().put("id", it.name).put("width", it.width).put("height", it.height)
                }),
            )
            .put("fps", config.fps)
            .put("bitRate", config.bitRate)
            .put("rightEye", config.rightEye)
            .put("sound", config.sound)
            .put("stabilized", config.stabilized)
            .put("viewers", status.browsers)
            .put("capturing", status.capturing)
            .put("error", status.error ?: JSONObject.NULL)
    }

    private fun configure(body: JSONObject): WebResponse {
        var config = settings.castConfig
        CastQuality.entries.firstOrNull { it.name == body.optString("quality") }
            ?.let { config = config.copy(quality = it) }
        if (body.has("fps")) config = config.copy(fps = if (body.optInt("fps") >= 60) 60 else 30)
        if (body.has("bitRate")) config = config.copy(bitRate = body.optInt("bitRate").coerceIn(0, MAX_BIT_RATE))
        if (body.has("rightEye")) config = config.copy(rightEye = body.optBoolean("rightEye"))
        if (body.has("sound")) config = config.copy(sound = body.optBoolean("sound"))
        if (body.has("stabilized")) config = config.copy(stabilized = body.optBoolean("stabilized"))
        if (config != settings.castConfig) settings.castConfig = config

        val source = WEB_SOURCES.firstOrNull { it.name == body.optString("source") }
        if (source != null) settings.castSource = source
        HeadsetCastService.refresh(context)
        return WebResponse.json(cast(source).toString())
    }

    private fun apps(): JSONArray {
        val manager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = manager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .map { info ->
                JSONObject()
                    .put("package", info.packageName)
                    .put("label", manager.getApplicationLabel(info).toString())
                    .put("version", runCatching { manager.getPackageInfo(info.packageName, 0).versionName }.getOrNull() ?: "")
                    .put("system", info.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            }
            .sortedBy { it.getString("label").lowercase() }
        return JSONArray(found)
    }

    private fun icon(name: String): WebResponse {
        val png = icons[name] ?: runCatching {
            val drawable = context.packageManager.getApplicationIcon(name)
            val bitmap = createBitmap(ICON_PX, ICON_PX)
            drawable.setBounds(0, 0, ICON_PX, ICON_PX)
            drawable.draw(Canvas(bitmap))
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            stream.toByteArray()
        }.getOrNull()?.also { icons[name] = it } ?: return WebResponse.error("404 Not Found", "not_found")
        return WebResponse.bytes(png, "image/png", headers = "Cache-Control: max-age=86400\r\n")
    }

    private fun apk(name: String): WebResponse {
        val file = runCatching { File(context.packageManager.getApplicationInfo(name, 0).sourceDir) }.getOrNull()
            ?.takeIf { it.canRead() }
            ?: return WebResponse.error("404 Not Found", "not_found")
        return WebResponse(type = APK, length = file.length(), headers = attachment("$name.apk")) { out ->
            file.inputStream().use { it.copyTo(out) }
        }
    }

    private fun launch(name: String): WebResponse {
        if (adb == AdbAccess.READY) return shell { it.report("monkey -p $name -c android.intent.category.LAUNCHER 1") }
        val intent = context.packageManager.getLaunchIntentForPackage(name)
            ?: return WebResponse.error("404 Not Found", "not_found")
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .fold({ WebResponse.EMPTY }, { WebResponse.error("500 Internal Server Error", it.message.orEmpty()) })
    }

    private fun install(request: WebRequest): WebResponse {
        if (request.length <= 0) return BAD_REQUEST
        return shell(INSTALL_TIMEOUT_MS) { connection ->
            when (val result = connection.install(request.body.source(), request.length, "-r")) {
                is InstallResult.Failure -> WebResponse.error("422 Unprocessable Entity", result.reason.trim())
                else -> {
                    BridgeLog.i(SCOPE, "installed a package of ${request.length} bytes")
                    WebResponse.json("{\"ok\":true}")
                }
            }
        }
    }

    private fun list(raw: String): WebResponse {
        val path = pathOf(raw) ?: return BAD_PATH
        return shell { connection ->
            val response = connection.shell(
                "cd -- ${quote(path)} || exit $NO_FOLDER; stat -L -c '%F|%s|%Y|%n' -- * .[!.]* 2>/dev/null; true"
            )
            if (response.exitCode == NO_FOLDER) return@shell WebResponse.error("404 Not Found", response.errorOutput.trim())
            val entries = response.output.lineSequence().mapNotNull { line ->
                val parts = line.split('|', limit = 4)
                if (parts.size < 4) return@mapNotNull null
                val folder = parts[0] == "directory"
                JSONObject()
                    .put("name", parts[3])
                    .put("dir", folder)
                    .put("size", if (folder) 0 else parts[1].toLongOrNull() ?: 0)
                    .put("modified", parts[2].toLongOrNull() ?: 0)
            }.toList()
            WebResponse.json(JSONObject().put("path", path).put("entries", JSONArray(entries)).toString())
        }
    }

    private fun download(raw: String): WebResponse {
        val path = pathOf(raw) ?: return BAD_PATH
        return shell(TRANSFER_TIMEOUT_MS) { connection ->
            val size = connection.shell("stat -L -c %s -- ${quote(path)}").output.trim().toLongOrNull()
                ?: return@shell WebResponse.error("404 Not Found", "not_found")
            WebResponse(
                type = BINARY,
                length = size,
                headers = attachment(path.substringAfterLast('/')),
                resource = connection,
            ) { out -> connection.pull(out.sink(), path) }
        }
    }

    private fun upload(request: WebRequest): WebResponse {
        val path = pathOf(request.query["path"].orEmpty()) ?: return BAD_PATH
        return shell(TRANSFER_TIMEOUT_MS) { connection ->
            when (val result = connection.push(request.body.source(), path, FILE_MODE, System.currentTimeMillis())) {
                is SyncResult.Failure -> WebResponse.error("500 Internal Server Error", result.reason)
                else -> WebResponse.EMPTY
            }
        }
    }

    private fun input(body: JSONObject): WebResponse {
        val text = body.optString("text")
        if (text.isNotEmpty()) {
            HeadsetInput.deliver(context, text)
            return WebResponse.EMPTY
        }
        val code = KEYS[body.optString("key")] ?: return BAD_REQUEST
        return shell { it.report("input keyevent $code") }
    }

    private fun open(url: String): WebResponse {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return BAD_REQUEST
        if (adb == AdbAccess.READY) return shell { it.report("am start -a android.intent.action.VIEW -d ${quote(url)}") }
        val view = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(view) }
            .fold({ WebResponse.EMPTY }, { WebResponse.error("500 Internal Server Error", it.message.orEmpty()) })
    }

    private fun shell(timeoutMs: Int = SHELL_TIMEOUT_MS, block: (Dadb) -> WebResponse): WebResponse {
        if (adb != AdbAccess.READY) return adbMissing()
        val connection = AdbConnection.open(context, timeoutMs)
        return try {
            block(connection).also { if (it.resource !== connection) connection.close() }
        } catch (e: IOException) {
            runCatching { connection.close() }
            BridgeLog.w(SCOPE, "adb request failed: ${e.message}")
            checkAdb()
            WebResponse.error("500 Internal Server Error", e.message ?: "adb")
        }
    }

    private fun Dadb.report(command: String, expect: String? = null): WebResponse {
        val response = shell(command)
        val failed = response.exitCode != 0 || (expect != null && expect !in response.allOutput)
        return if (failed) WebResponse.error("500 Internal Server Error", response.allOutput.trim()) else WebResponse.EMPTY
    }

    private fun adbMissing(): WebResponse {
        val unauthorized = adb == AdbAccess.UNAUTHORIZED
        if (!unauthorized) checkAdb()
        return WebResponse.error("409 Conflict", if (unauthorized) "adb_unauthorized" else "adb_offline")
    }

    private fun checkAdb() {
        checkedAt = System.nanoTime()
        HeadsetAdb.check(context, wake = controlled)
    }

    private fun packageOf(request: WebRequest): String? =
        JSONObject(request.text()).optString("package").takeIf(PACKAGE::matches)

    private fun pathOf(raw: String): String? =
        raw.takeIf { it.startsWith("/") && it.none { char -> char == '\u0000' || char == '\n' || char == '\r' } }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun attachment(name: String): String {
        val plain = name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
        val encoded = URLEncoder.encode(name, Charsets.UTF_8.name()).replace("+", "%20")
        return "Content-Disposition: attachment; filename=\"$plain\"; filename*=UTF-8''$encoded\r\n"
    }

    private companion object {
        const val SCOPE = "castweb"
        const val ICON_PX = 96
        const val NO_FOLDER = 9
        const val FILE_MODE = 436
        const val MAX_BIT_RATE = 50_000_000
        const val MAX_OUTPUT_CHARS = 512 * 1024
        const val SHELL_TIMEOUT_MS = 20_000
        const val COMMAND_TIMEOUT_MS = 60_000
        const val TRANSFER_TIMEOUT_MS = 120_000
        const val INSTALL_TIMEOUT_MS = 600_000
        const val RECHECK_NS = 20_000_000_000L
        const val BINARY = "application/octet-stream"
        const val APK = "application/vnd.android.package-archive"

        val WEB_SOURCES = listOf(CastSource.MAGIC, CastSource.MAGIC_ADB, CastSource.SCRCPY)
        val PACKAGE = Regex("""[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+""")
        val KEYS = mapOf(
            "HOME" to 3,
            "BACK" to 4,
            "VOLUME_UP" to 24,
            "VOLUME_DOWN" to 25,
            "POWER" to 26,
            "ENTER" to 66,
            "MENU" to 82,
            "MEDIA_PLAY_PAUSE" to 85,
        )

        val BAD_REQUEST = WebResponse.error("400 Bad Request", "bad_request")
        val BAD_PACKAGE = WebResponse.error("400 Bad Request", "bad_package")
        val BAD_PATH = WebResponse.error("400 Bad Request", "bad_path")
    }
}
