package com.example.roleplay.ai.gguf

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 与 PC 端 `model_switcher.py`（默认 8090 端口）通信。
 *
 * 架构回顾：8080 上永远只有一个 llama-server，切换模型 = 停旧的、起新的。
 * 所以"切换"是一次**秒级到十几秒的阻塞操作**（要重新加载 gguf），
 * 返回时新模型已经就绪。
 *
 * host/port 用 lambda 传入而不是构造时定值：设置页里随时可能改地址，
 * 每次都读最新值才能立刻生效。
 */
class ModelSwitcher(
    private val hostProvider: () -> String,
    private val portProvider: () -> Int
) {

    companion object {
        private const val TAG = "ModelSwitcher"

        /** 切换要重新加载模型，3GB 的 gguf 实测 5~7 秒，给足余量 */
        private const val SWITCH_TIMEOUT_MS = 180_000
        private const val QUERY_TIMEOUT_MS = 6_000
    }

    /** 切换器自报的当前状态 */
    data class ActiveModel(
        val id: String,
        val name: String,
        val inferPort: Int
    )

    /** 建议 #6：PC 端模型文件与磁盘信息（只读） */
    data class ModelFile(
        val id: String,
        val name: String,
        val exists: Boolean,
        val size: Long
    )

    /**
     * 反馈 #11：PC 端模型清单里的一项（配置项 + models/gguf 里自动扫到的）。
     * 有它之后"往文件夹丢个 gguf"就能直接在手机上看到并切换，
     * 不再要求用户去改 ModelRegistry.kt。
     */
    data class RemoteModel(
        val id: String,
        val name: String,
        val file: String,
        val size: Long,
        val auto: Boolean,
        val exists: Boolean
    )

    data class DiskInfo(
        val files: List<ModelFile>,
        val diskTotal: Long,
        val diskFree: Long
    )

    private fun baseUrl(): String = "http://${hostProvider()}:${portProvider()}"

    /** 查询当前实际加载的模型 */
    suspend fun status(): Result<ActiveModel> = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("${baseUrl()}/status").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = QUERY_TIMEOUT_MS
                readTimeout = QUERY_TIMEOUT_MS
            }
            val code = conn.responseCode
            val text = (if (code == 200) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()

            if (code != 200) {
                return@withContext Result.failure(Exception("切换器返回 $code：$text"))
            }
            val json = JSONObject(text)
            Result.success(
                ActiveModel(
                    id = json.optString("active_id", ""),
                    name = json.optString("active_name", ""),
                    inferPort = json.optInt("infer_port", 8080)
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "查询切换器失败: ${e.message}")
            Result.failure(Exception("无法连接模型切换器（${baseUrl()}）：${e.message}"))
        }
    }

    /**
     * 切换到指定模型 id（对应 servers.config.json 里的 id）。
     * 阻塞直到新模型加载完成。
     */
    suspend fun switch(modelId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "切换模型 -> $modelId")
            val body = JSONObject().put("id", modelId).toString()
            val conn = (URL("${baseUrl()}/switch").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = SWITCH_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code == 200) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()

            if (code != 200) {
                return@withContext Result.failure(Exception("切换失败 $code：$text"))
            }
            val json = JSONObject(text)
            if (!json.optBoolean("ok", false)) {
                return@withContext Result.failure(Exception("切换器拒绝：${json.optString("error", text)}"))
            }
            Result.success(json.optString("active", modelId))
        } catch (e: Exception) {
            Log.e(TAG, "切换模型失败: ${e.message}", e)
            Result.failure(Exception("切换模型失败：${e.message}"))
        }
    }

    /** 切换器是否在线（用来决定设置页里模型列表能不能点） */
    suspend fun isAvailable(): Boolean = status().isSuccess

    /**
     * 反馈 #11：拉取 PC 端的**完整**模型清单（配置项 + models/gguf 自动扫到的）。
     * 端点由 model_switcher.py 的 GET /models 提供。
     */
    suspend fun listModels(): Result<List<RemoteModel>> = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("${baseUrl()}/models").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = QUERY_TIMEOUT_MS
                readTimeout = QUERY_TIMEOUT_MS
            }
            val code = conn.responseCode
            val text = (if (code == 200) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()
            if (code != 200) {
                return@withContext Result.failure(Exception("切换器返回 $code：$text"))
            }
            val arr = JSONObject(text).optJSONArray("models") ?: JSONArray()
            val models = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RemoteModel(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    file = o.optString("file"),
                    size = o.optLong("size"),
                    auto = o.optBoolean("auto"),
                    exists = o.optBoolean("exists", true)
                )
            }.filter { it.id.isNotBlank() && it.exists }
            Result.success(models)
        } catch (e: Exception) {
            Log.w(TAG, "拉取模型清单失败: ${e.message}")
            Result.failure(Exception("无法获取 PC 模型清单：${e.message}"))
        }
    }

    /**
     * 建议 #6：查询 PC 端 gguf 文件列表 + 磁盘空间（只读，不触发切换）。
     * 端点由 model_switcher.py 的 GET /disk 提供。
     */
    suspend fun diskInfo(): Result<DiskInfo> = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("${baseUrl()}/disk").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = QUERY_TIMEOUT_MS
                readTimeout = QUERY_TIMEOUT_MS
            }
            val code = conn.responseCode
            val text = (if (code == 200) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()
            if (code != 200) {
                return@withContext Result.failure(Exception("切换器返回 $code：$text"))
            }
            val json = JSONObject(text)
            val filesArr = json.optJSONArray("files") ?: JSONArray()
            val files = (0 until filesArr.length()).map { i ->
                val o = filesArr.getJSONObject(i)
                ModelFile(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    exists = o.optBoolean("exists"),
                    size = o.optLong("size")
                )
            }
            Result.success(
                DiskInfo(
                    files = files,
                    diskTotal = json.optLong("disk_total"),
                    diskFree = json.optLong("disk_free")
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "查询磁盘信息失败: ${e.message}")
            Result.failure(Exception("无法获取 PC 磁盘信息：${e.message}"))
        }
    }
}
