package com.joy4fire.workbuddy2api

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.max
import kotlin.random.Random

/** Native, process-local repository. All writes are committed before a method returns. */
class NativeStore private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION
) {
    private val appContext = context.applicationContext
    private val lock = Any()

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) = createSchema(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 5) migrateAccountsToRegions(db)
        // v8：该配置项已并入登录流程，不再由用户配置；老库里可能残留，一并清掉
        if (oldVersion < 8) db.execSQL("DELETE FROM settings WHERE key='invite_code_domestic'")
        createSchema(db)
        ensureColumn(db, "accounts", "last_checkin_date", "TEXT")
        ensureColumn(db, "accounts", "credit_packages_json", "TEXT NOT NULL DEFAULT '[]'")
        ensureColumn(db, "apps", "key_enc", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "apps", "region", "TEXT NOT NULL DEFAULT 'domestic'")
        ensureColumn(db, "apps", "updated_at", "REAL NOT NULL DEFAULT 0")
        db.execSQL("UPDATE apps SET updated_at=created_at WHERE updated_at=0")
        ensureColumn(db, "usage_logs", "input_content", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "usage_logs", "output_content", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "usage_logs", "reasoning_content", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "usage_logs", "credits", "REAL NOT NULL DEFAULT 0")
        ensureColumn(db, "usage_logs", "app_name", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "usage_logs", "account_region", "TEXT NOT NULL DEFAULT 'domestic'")
        // Correct accounts imported by older builds before region-aware migration existed.
        db.execSQL("""UPDATE accounts SET region='international'
            WHERE region='domestic' AND (LOWER(domain) LIKE '%codebuddy.ai%' OR LOWER(domain) LIKE '%workbuddy.ai%'
            OR LOWER(auth_json) LIKE '%codebuddy.ai%' OR LOWER(auth_json) LIKE '%workbuddy.ai%')
            AND NOT EXISTS (SELECT 1 FROM accounts other WHERE other.region='international' AND other.uid=accounts.uid)""")
    }

    private fun migrateAccountsToRegions(db: SQLiteDatabase) {
        val exists = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='accounts'", null).use { it.moveToFirst() }
        if (!exists) return
        db.execSQL("ALTER TABLE accounts RENAME TO accounts_legacy")
        createAccountsTable(db)
        db.execSQL("""INSERT INTO accounts (
            id,uid,region,nickname,enterprise_id,domain,auth_json,enabled,priority,
            credits_remaining,credits_total,credits_expire_at,credit_packages_json,last_used_at,
            last_checkin_date,failure_count,cooldown_until,created_at,updated_at
        ) SELECT id,uid,CASE WHEN LOWER(domain) LIKE '%codebuddy.ai%' OR LOWER(domain) LIKE '%workbuddy.ai%'
            OR LOWER(auth_json) LIKE '%codebuddy.ai%' OR LOWER(auth_json) LIKE '%workbuddy.ai%'
            THEN 'international' ELSE 'domestic' END,nickname,enterprise_id,domain,auth_json,enabled,priority,
            credits_remaining,credits_total,credits_expire_at,credit_packages_json,last_used_at,
            last_checkin_date,failure_count,cooldown_until,created_at,updated_at FROM accounts_legacy""")
        db.execSQL("DROP TABLE accounts_legacy")
    }

    private fun createAccountsTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS accounts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uid TEXT NOT NULL, region TEXT NOT NULL DEFAULT 'domestic',
            nickname TEXT NOT NULL DEFAULT '', enterprise_id TEXT NOT NULL DEFAULT '',
            domain TEXT NOT NULL DEFAULT '', auth_json TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1, priority INTEGER NOT NULL DEFAULT 0,
            credits_remaining REAL, credits_total REAL, credits_expire_at REAL,
            credit_packages_json TEXT NOT NULL DEFAULT '[]', last_used_at REAL NOT NULL DEFAULT 0,
            last_checkin_date TEXT, failure_count INTEGER NOT NULL DEFAULT 0,
            cooldown_until REAL NOT NULL DEFAULT 0, created_at REAL NOT NULL, updated_at REAL NOT NULL,
            UNIQUE(region, uid)
        )""")
    }

    private fun createSchema(db: SQLiteDatabase) {
        createAccountsTable(db)
        db.execSQL("""CREATE TABLE IF NOT EXISTS apps (
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE,
            key_hash TEXT NOT NULL UNIQUE, key_prefix TEXT NOT NULL, key_enc TEXT NOT NULL DEFAULT '',
            region TEXT NOT NULL DEFAULT 'domestic',
            note TEXT NOT NULL DEFAULT '', enabled INTEGER NOT NULL DEFAULT 1,
            created_at REAL NOT NULL, updated_at REAL NOT NULL DEFAULT 0
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS usage_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL NOT NULL, model TEXT NOT NULL DEFAULT '',
            protocol TEXT NOT NULL DEFAULT '', account_uid TEXT NOT NULL DEFAULT '',
            account_region TEXT NOT NULL DEFAULT 'domestic',
            input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0,
            total_tokens INTEGER NOT NULL DEFAULT 0, latency_ms REAL NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'ok', error TEXT NOT NULL DEFAULT '',
            input_content TEXT NOT NULL DEFAULT '', output_content TEXT NOT NULL DEFAULT '',
            reasoning_content TEXT NOT NULL DEFAULT '', credits REAL NOT NULL DEFAULT 0,
            app_name TEXT NOT NULL DEFAULT ''
        )""")
        db.execSQL("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS model_cache (
            cache_key TEXT PRIMARY KEY, payload TEXT NOT NULL, source TEXT NOT NULL DEFAULT 'dynamic',
            fetched_at REAL NOT NULL, expires_at REAL NOT NULL
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_accounts_pick ON accounts(enabled, cooldown_until, priority)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_usage_ts ON usage_logs(ts)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_usage_model ON usage_logs(model)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_usage_account ON usage_logs(account_uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_usage_app ON usage_logs(app_name)")
    }

    private fun ensureColumn(db: SQLiteDatabase, table: String, column: String, ddl: String) {
        val found = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            var exists = false
            while (c.moveToNext()) if (c.getString(1) == column) exists = true
            exists
        }
        if (!found) db.execSQL("ALTER TABLE $table ADD COLUMN $column $ddl")
    }

    // Accounts -----------------------------------------------------------------

    fun upsertAccount(root: JSONObject, requestedRegion: String? = null): String = synchronized(lock) {
        val auth = root.optJSONObject("auth") ?: root
        val profile = root.optJSONObject("account") ?: auth.optJSONObject("account") ?: JSONObject()
        val uid = profile.optString("uid").trim()
        require(uid.isNotEmpty()) { "auth 文件缺少 account.uid" }
        require(auth.optString("accessToken").isNotBlank()) { "auth 文件缺少 accessToken" }
        val region = AccountRegion.infer(requestedRegion ?: root.optString("region"), auth.optString("domain")).id
        root.put("region", region)
        val now = nowSeconds()
        val values = ContentValues().apply {
            put("uid", uid); put("region", region); put("nickname", profile.optString("nickname", uid))
            put("enterprise_id", profile.optString("enterpriseId", auth.optString("enterpriseId")))
            put("domain", auth.optString("domain")); put("auth_json", root.toString())
            put("updated_at", now)
        }
        val db = writableDatabase
        val whereArgs = arrayOf(region, uid)
        val existing = db.rawQuery("SELECT 1 FROM accounts WHERE region=? AND uid=?", whereArgs).use { it.moveToFirst() }
        if (existing) db.update("accounts", values, "region=? AND uid=?", whereArgs) else {
            values.put("created_at", now); db.insertOrThrow("accounts", null, values)
        }
        accountKey(region, uid)
    }

    fun getAccount(accountKey: String): JSONObject? = synchronized(lock) {
        val (region, uid) = splitAccountKey(accountKey)
        readableDatabase.rawQuery("SELECT * FROM accounts WHERE region=? AND uid=?", arrayOf(region, uid)).use { c ->
            if (c.moveToFirst()) accountJson(c) else null
        }
    }

    fun getAccountRoot(accountKey: String): JSONObject? = getAccount(accountKey)?.optString("auth_json")
        ?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun listAccounts(): JSONArray = synchronized(lock) {
        val out = JSONArray()
        readableDatabase.rawQuery("SELECT * FROM accounts ORDER BY priority DESC,id ASC", null).use { c ->
            while (c.moveToNext()) out.put(accountJson(c).apply { remove("auth_json") })
        }
        out
    }

    /** 全部账号的 key（region:uid），顺序与列表一致；导出时按此逐个取原始 auth_json。 */
    fun accountKeys(): List<String> = synchronized(lock) {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT region, uid FROM accounts ORDER BY priority DESC,id ASC", null).use { c ->
            while (c.moveToNext()) out.add(accountKey(c.getString(0), c.getString(1)))
        }
        out
    }

    fun deleteAccount(accountKey: String): Boolean = synchronized(lock) {
        val (region, uid) = splitAccountKey(accountKey)
        writableDatabase.delete("accounts", "region=? AND uid=?", arrayOf(region, uid)) > 0
    }

    fun setAccountEnabled(accountKey: String, enabled: Boolean): Boolean = updateAccount(accountKey, ContentValues().apply {
        put("enabled", if (enabled) 1 else 0); put("updated_at", nowSeconds())
    })

    /** 设置账号优先级：0 = 不额外加权。选号权重用 max(0, priority)，所以负数必须在这里夹紧，避免写进"改小了却没生效"的假值。 */
    fun setAccountPriority(accountKey: String, priority: Int): Boolean = updateAccount(accountKey, ContentValues().apply {
        put("priority", priority.coerceIn(MIN_PRIORITY, MAX_PRIORITY)); put("updated_at", nowSeconds())
    })

    fun clearAccountCooldown(accountKey: String): Boolean = updateAccount(accountKey, ContentValues().apply {
        put("failure_count", 0); put("cooldown_until", 0); put("updated_at", nowSeconds())
    })

    fun markAccountSuccess(accountKey: String): Boolean = updateAccount(accountKey, ContentValues().apply {
        put("failure_count", 0); put("cooldown_until", 0); put("last_used_at", nowSeconds()); put("updated_at", nowSeconds())
    })

    /**
     * 统计「健康」账号数：可用 + 不在冷却 + 仍有额度。
     *
     * 判定口径与 selectAccount 的候选过滤条件完全一致（对齐上游 pool.healthy()）。
     * 换号重试前必须先冷却掉刚失败的账号再调用本方法，否则刚失败的号会被自己算进去，
     * 导致「以为还有健康号 → 换号 → 又选中它」的假重试。
     */
    fun countHealthy(region: String? = null): Int = synchronized(lock) {
        val now = nowSeconds()
        val regionClause = if (region.isNullOrBlank()) "" else " AND region=?"
        val regionArgs = if (region.isNullOrBlank()) null else arrayOf(region)
        var count = 0
        readableDatabase.rawQuery("SELECT * FROM accounts WHERE enabled=1$regionClause", regionArgs).use { c ->
            while (c.moveToNext()) {
                val item = accountJson(c)
                val hasCredits = item.isNull("credits_remaining") || item.optDouble("credits_remaining") > 0
                if (hasCredits && item.optDouble("cooldown_until") <= now) count++
            }
        }
        count
    }

    fun markAccountFailure(accountKey: String, cooldownSeconds: Long): Boolean = synchronized(lock) {
        val current = getAccount(accountKey) ?: return@synchronized false
        updateAccount(accountKey, ContentValues().apply {
            put("failure_count", current.optInt("failure_count") + 1)
            put("cooldown_until", nowSeconds() + max(0L, cooldownSeconds)); put("updated_at", nowSeconds())
        })
    }

    /** token 保活连续失败阈值：对齐上游 scheduler._keepalive_fail_threshold = 3。 */
    private val KEEPALIVE_FAIL_THRESHOLD = 3

    /**
     * 记录一次 token 刷新失败。
     *
     * 对齐上游 scheduler.do_keepalive：**单次失败只计数、不立刻处罚**，连续失败达阈值
     * 才真正冷却并禁用账号。上游原注释说得很清楚——立刻处罚会让一次网络抖动
     * 导致账号「莫名被禁用」。此前 Android 版就是失败即 markAccountFailure(60)。
     *
     * @return true 表示已达阈值并已处罚（冷却 + 禁用）；false 表示仅计数
     */
    fun recordRefreshFailure(accountKey: String): Boolean = synchronized(lock) {
        val current = getAccount(accountKey) ?: return@synchronized false
        val fails = current.optInt("failure_count") + 1
        if (fails >= KEEPALIVE_FAIL_THRESHOLD) {
            // session 很可能已失效：冷却 30 分钟并停用，等用户重新登录。
            // 不自动复活，避免坏 session 持续打上游（对齐上游 set_enabled(false) + 落库）。
            updateAccount(accountKey, ContentValues().apply {
                put("failure_count", fails)
                put("cooldown_until", nowSeconds() + 1800)
                put("enabled", 0)
                put("updated_at", nowSeconds())
            })
            true
        } else {
            updateAccount(accountKey, ContentValues().apply {
                put("failure_count", fails); put("updated_at", nowSeconds())
            })
            false
        }
    }

    fun setAccountCredits(accountKey: String, remaining: Double, total: Double, expireAt: Double? = null, packages: JSONArray = JSONArray()): Boolean =
        updateAccount(accountKey, ContentValues().apply {
            put("credits_remaining", remaining); put("credits_total", total)
            if (expireAt == null) putNull("credits_expire_at") else put("credits_expire_at", expireAt)
            put("credit_packages_json", packages.toString()); put("updated_at", nowSeconds())
        })

    fun setCheckinDate(accountKey: String, date: String): Boolean = updateAccount(accountKey, ContentValues().apply {
        put("last_checkin_date", date); put("updated_at", nowSeconds())
    })

    private fun updateAccount(accountKey: String, values: ContentValues): Boolean = synchronized(lock) {
        val (region, uid) = splitAccountKey(accountKey)
        writableDatabase.update("accounts", values, "region=? AND uid=?", arrayOf(region, uid)) > 0
    }

    /** 无健康账号时的兜底门槛：冷却还需等待超过该秒数则不硬打，由上层转 503。 */
    private val COOLDOWN_FALLBACK_MAX_WAIT = 30.0

    /** 账号级最小间隔（秒）：对齐上游 ratelimit.py 默认 1.5s。 */
    private val RATELIMIT_MIN_INTERVAL = 1.5

    /** 最小间隔的随机抖动幅度（秒）：对齐上游 ratelimit.py 默认 ±0.3s。 */
    private val RATELIMIT_JITTER = 0.3

    /** Weighted selection with hard priority for credits expiring within seven days. */
    fun selectAccount(markUsed: Boolean = true, region: String? = null): JSONObject? = synchronized(lock) {
        val now = nowSeconds()
        val candidates = mutableListOf<JSONObject>()
        val regionClause = if (region.isNullOrBlank()) "" else " AND region=?"
        val regionArgs = if (region.isNullOrBlank()) null else arrayOf(region)
        readableDatabase.rawQuery("SELECT * FROM accounts WHERE enabled=1$regionClause ORDER BY id", regionArgs).use { c ->
            while (c.moveToNext()) {
                val item = accountJson(c)
                val hasCredits = item.isNull("credits_remaining") || item.optDouble("credits_remaining") > 0
                if (hasCredits && item.optDouble("cooldown_until") <= now) candidates += item
            }
        }
        var pool = candidates
        if (pool.isEmpty()) {
            // 冷却兜底：无健康账号时退回「最早冷却到期」的账号顶班，但它若还要冷却
            // 超过 30s 就绝不硬打——打上去只会再吃一个 429，形成限流风暴。
            // 上游实测事故记录见 CODE_REVIEW_TODO.md:195（单账号场景 fallback 反复选中同一账号）。
            readableDatabase.rawQuery("SELECT * FROM accounts WHERE enabled=1$regionClause ORDER BY cooldown_until ASC LIMIT 1", regionArgs).use { c ->
                if (c.moveToFirst()) {
                    val fallback = accountJson(c)
                    if (fallback.optDouble("cooldown_until") - now > COOLDOWN_FALLBACK_MAX_WAIT) {
                        return@synchronized null
                    }
                    pool = mutableListOf(fallback)
                }
            }
        } else {
            // 账号级限速：过滤掉距上次使用不足 (1.5s ± 0.3s) 的账号，避免同一账号被连续高频打。
            // 上游是 await 补齐间隔；Android 侧不能阻塞请求线程（客户端会超时），
            // 故改为「选不带间隔的号」，全部被节流时交回上层 503 明确拒绝。
            val throttled = pool.filter { accountReadyForSend(it, now) }
            if (throttled.isNotEmpty()) pool = throttled.toMutableList()
            val urgent = pool.filter { !it.isNull("credits_expire_at") && it.optDouble("credits_expire_at") - now <= 7 * 86400 }
            if (urgent.isNotEmpty()) pool = urgent.toMutableList()
        }
        if (pool.isEmpty()) return@synchronized null
        val weights = pool.map { accountWeight(it, now) }
        var pick = Random.nextDouble(weights.sum().coerceAtLeast(0.0001))
        var selected = pool.last()
        for (i in pool.indices) { pick -= weights[i]; if (pick <= 0) { selected = pool[i]; break } }
        if (markUsed) updateAccount(selected.getString("account_key"), ContentValues().apply { put("last_used_at", now); put("updated_at", now) })
        selected
    }

    /**
     * 账号是否已过最小发送间隔（账号级限速）。
     *
     * 判定基准是「距上次使用的间隔 ≥ 1.5s 且叠加 ±0.3s 抖动」。抖动值按账号确定性地
     * 生成而不是每次随机——随机抖动会让同一账号的间隔在 1.2s~1.8s 之间跳变，
     * 而确定性抖动既保留了「打散固定节拍」的效果，又让判定可复现、便于排查。
     */
    private fun accountReadyForSend(a: JSONObject, now: Double): Boolean {
        val last = a.optDouble("last_used_at", 0.0)
        if (last <= 0) return true
        val jitter = ((a.optString("account_key").hashCode() % 7) / 10.0 - 0.3) * RATELIMIT_JITTER / 0.3
        val required = RATELIMIT_MIN_INTERVAL + jitter
        return now - last >= required
    }

    private fun accountWeight(a: JSONObject, now: Double): Double {
        val priority = 1.0 + max(0, a.optInt("priority"))
        val total = a.optDouble("credits_total", 0.0)
        val fullness = if (total > 0) 0.5 + 0.5 * (a.optDouble("credits_remaining", 0.0) / total).coerceIn(0.0, 1.0) else 1.0
        val days = if (a.isNull("credits_expire_at")) Double.POSITIVE_INFINITY else (a.optDouble("credits_expire_at") - now) / 86400.0
        val expiry = when { days <= 1 -> 8.0; days <= 3 -> 6.0; days <= 7 -> 4.0; days <= 30 -> 2.0; else -> 1.0 }
        val success = 1.0 / (1.0 + a.optInt("failure_count"))
        val last = a.optDouble("last_used_at", 0.0)
        val idle = if (last <= 0) 1.0 else (1.0 + (now - last).coerceAtLeast(0.0) / 3600.0 * 0.3).coerceAtMost(3.0)
        return priority * fullness * expiry * success * idle
    }

    // Apps/API keys -------------------------------------------------------------

    fun createApp(name: String, note: String = "", region: String = AccountRegion.DOMESTIC.id, customKey: String? = null): JSONObject = synchronized(lock) {
        val normalizedName = validateAppName(name)
        val normalizedNote = validateAppNote(note)
        val normalizedRegion = AccountRegion.fromStrict(region).id
        val key = customKey?.let(::validateAppKey) ?: "sk-" + randomHex(24)
        val now = nowSeconds()
        val values = ContentValues().apply {
            put("name", normalizedName); put("key_hash", sha256(key)); put("key_prefix", keyPrefix(key))
            put("key_enc", encrypt(key)); put("region", normalizedRegion); put("note", normalizedNote); put("enabled", 1)
            put("created_at", now); put("updated_at", now)
        }
        val id = insertApp(values)
        JSONObject().put("id", id).put("name", normalizedName).put("key", key).put("key_prefix", values.getAsString("key_prefix"))
            .put("note", normalizedNote).put("enabled", true).put("region", normalizedRegion).put("region_label", AccountRegion.from(normalizedRegion).label)
    }

    fun updateApp(appId: Long, name: String, note: String, region: String, replacementKey: String? = null): JSONObject = synchronized(lock) {
        val normalizedName = validateAppName(name)
        val normalizedNote = validateAppNote(note)
        val normalizedRegion = AccountRegion.fromStrict(region).id
        require(readableDatabase.rawQuery("SELECT 1 FROM apps WHERE id=?", arrayOf(appId.toString())).use { it.moveToFirst() }) { "API Key 不存在" }
        val values = ContentValues().apply {
            put("name", normalizedName); put("note", normalizedNote); put("region", normalizedRegion); put("updated_at", nowSeconds())
            replacementKey?.let(::validateAppKey)?.let { key ->
                put("key_hash", sha256(key)); put("key_prefix", keyPrefix(key)); put("key_enc", encrypt(key))
            }
        }
        try {
            require(writableDatabase.update("apps", values, "id=?", arrayOf(appId.toString())) > 0) { "API Key 更新失败" }
        } catch (error: android.database.sqlite.SQLiteConstraintException) {
            throw IllegalArgumentException(if (replacementKey != null) "应用名称或 API Key 已存在" else "应用名称已存在", error)
        }
        readableDatabase.rawQuery("SELECT * FROM apps WHERE id=?", arrayOf(appId.toString())).use { cursor ->
            require(cursor.moveToFirst()) { "API Key 不存在" }
            return@synchronized appJson(cursor).apply { replacementKey?.let { put("key", it) } }
        }
    }

    fun ensureDefaultApp(legacyKey: String? = null): JSONObject = synchronized(lock) {
        readableDatabase.rawQuery("SELECT * FROM apps ORDER BY id LIMIT 1", null).use { c ->
            if (c.moveToFirst()) {
                val row = appJson(c)
                var key = decrypt(c.string("key_enc"))
                if (key.isBlank() && !legacyKey.isNullOrBlank() &&
                    MessageDigest.isEqual(sha256(legacyKey).toByteArray(), c.string("key_hash").toByteArray())) {
                    writableDatabase.update("apps", ContentValues().apply { put("key_enc", encrypt(legacyKey)) },
                        "id=?", arrayOf(c.getLong(c.getColumnIndexOrThrow("id")).toString()))
                    key = legacyKey
                }
                row.put("key", key)
                return@synchronized row
            }
        }
        if (legacyKey.isNullOrBlank()) return@synchronized createApp("Default", "系统默认应用")
        val values = ContentValues().apply {
            put("name", "Default"); put("key_hash", sha256(legacyKey)); put("key_prefix", keyPrefix(legacyKey))
            put("key_enc", encrypt(legacyKey)); put("note", "从旧版迁移"); put("enabled", 1); put("created_at", nowSeconds()); put("updated_at", nowSeconds())
        }
        val id = writableDatabase.insertOrThrow("apps", null, values)
        JSONObject().put("id", id).put("name", "Default").put("key", legacyKey)
    }

    fun listApps(): JSONArray = synchronized(lock) {
        val out = JSONArray()
        readableDatabase.rawQuery("""SELECT a.id,a.name,a.key_prefix,a.region,a.note,a.enabled,a.created_at,a.updated_at,
            COUNT(u.id) requests,COALESCE(SUM(u.total_tokens),0) tokens,COALESCE(SUM(u.credits),0) credits
            FROM apps a LEFT JOIN usage_logs u ON u.app_name=a.name GROUP BY a.id ORDER BY a.id""", null).use { c ->
            while (c.moveToNext()) out.put(appJson(c))
        }
        out
    }

    fun getAppKey(appId: Long): String? = synchronized(lock) {
        readableDatabase.rawQuery("SELECT key_enc FROM apps WHERE id=?", arrayOf(appId.toString())).use { c ->
            if (!c.moveToFirst()) null else decrypt(c.getString(0)).ifBlank { null }
        }
    }

    fun authenticateApp(key: String): JSONObject? = synchronized(lock) {
        if (key.isBlank()) return@synchronized null
        val hash = sha256(key)
        readableDatabase.rawQuery("SELECT * FROM apps WHERE key_hash=? AND enabled=1", arrayOf(hash)).use { c ->
            if (c.moveToFirst() && MessageDigest.isEqual(hash.toByteArray(), c.string("key_hash").toByteArray())) appJson(c).apply { remove("key_enc") } else null
        }
    }

    fun setAppEnabled(appId: Long, enabled: Boolean): Boolean = synchronized(lock) {
        writableDatabase.update("apps", ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "id=?", arrayOf(appId.toString())) > 0
    }

    fun toggleApp(appId: Long): Boolean? = synchronized(lock) {
        val enabled = readableDatabase.rawQuery("SELECT enabled FROM apps WHERE id=?", arrayOf(appId.toString())).use { c ->
            if (c.moveToFirst()) c.getInt(0) != 0 else return@synchronized null
        }
        setAppEnabled(appId, !enabled); !enabled
    }

    fun deleteApp(appId: Long): Boolean = synchronized(lock) {
        writableDatabase.delete("apps", "id=?", arrayOf(appId.toString())) > 0
    }

    // Usage --------------------------------------------------------------------

    fun logUsage(record: JSONObject): Long = synchronized(lock) {
        val input = record.optLong("input_tokens", record.optLong("prompt_tokens", 0))
        val output = record.optLong("output_tokens", record.optLong("completion_tokens", 0))
        // JSONObject.optDouble 在字段缺失时返回 NaN，而 SQLite 会把 NaN 存成 NULL，
        // 直接触发 usage_logs 的 NOT NULL 约束（credits 曾因此让所有记录写入失败、记录页永远空白）。
        // 统一兜底：非有限值一律回落到默认值。
        fun real(key: String, fallback: Double): Double = record.optDouble(key, fallback).takeIf { it.isFinite() } ?: fallback
        writableDatabase.insertOrThrow("usage_logs", null, ContentValues().apply {
            put("ts", real("ts", nowSeconds())); put("model", record.optString("model"))
            put("protocol", record.optString("protocol", "chat")); put("account_uid", record.optString("account_uid"))
            put("account_region", record.optString("account_region", "domestic"))
            put("input_tokens", input); put("output_tokens", output); put("total_tokens", record.optLong("total_tokens", input + output))
            put("latency_ms", real("latency_ms", 0.0)); put("status", record.optString("status", "ok")); put("error", record.optString("error"))
            // 写入即截断：这三个字段是数据库体积的唯一主要来源。长对话（多轮历史累积）
            // 与长思考链单条可达数百 KB，若不设限，几条请求就能让库膨胀到几十 MB。
            // 上限内的内容仍完整保留（供排查与统计），超长部分裁掉并标注原文长度。
            put("input_content", clip(record.optString("input_content"), INPUT_LIMIT))
            put("output_content", clip(record.optString("output_content"), OUTPUT_LIMIT))
            put("reasoning_content", clip(record.optString("reasoning_content"), REASONING_LIMIT))
            put("credits", real("credits", 0.0)); put("app_name", record.optString("app_name"))
        })
    }

    fun usageSummary(): JSONObject = synchronized(lock) {
        val total = aggregate("SELECT COUNT(*) c,COALESCE(SUM(total_tokens),0) t FROM usage_logs")
        val today = aggregate("SELECT COUNT(*) c,COALESCE(SUM(total_tokens),0) t FROM usage_logs WHERE ts>=?", arrayOf(localMidnight().toString()))
        JSONObject().put("total_requests", total.first).put("total_tokens", total.second)
            .put("today_requests", today.first).put("today_tokens", today.second)
            .put("by_protocol", grouped("protocol", "protocol")).put("by_model", grouped("model", "model", 20))
            .put("by_app", grouped("COALESCE(NULLIF(app_name,''),'(未命名)')", "app"))
    }

    fun usageRecent(page: Int = 1, pageSize: Int = 20, protocol: String? = null, model: String? = null,
                    appName: String? = null, status: String? = null, light: Boolean = true): JSONObject = synchronized(lock) {
        val filter = usageFilter(protocol, model, appName, status)
        val size = pageSize.coerceIn(1, 500); val safePage = max(1, page)
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM usage_logs ${filter.first}", filter.second.toTypedArray()).use { c -> c.moveToFirst(); c.getInt(0) }
        val columns = if (light) "id,ts,model,protocol,account_uid,input_tokens,output_tokens,total_tokens,latency_ms,status,error,credits,app_name" else "*"
        val args = filter.second + listOf(size.toString(), ((safePage - 1) * size).toString())
        val rows = JSONArray()
        readableDatabase.rawQuery("SELECT $columns FROM usage_logs ${filter.first} ORDER BY id DESC LIMIT ? OFFSET ?", args.toTypedArray()).use { c ->
            while (c.moveToNext()) rows.put(rowJson(c))
        }
        JSONObject().put("records", rows).put("total", count).put("page", safePage).put("page_size", size)
    }

    fun getUsage(id: Long): JSONObject? = synchronized(lock) {
        readableDatabase.rawQuery("SELECT * FROM usage_logs WHERE id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) rowJson(c) else null }
    }

    fun usageFilters(): JSONObject = synchronized(lock) {
        val currentApps = distinct("SELECT name FROM apps ORDER BY id")
        val usedApps = distinct("SELECT DISTINCT app_name FROM usage_logs WHERE app_name!='' ORDER BY app_name")
        val current = (0 until currentApps.length()).map { currentApps.getString(it) }.toSet()
        val history = JSONArray(); for (i in 0 until usedApps.length()) if (usedApps.getString(i) !in current) history.put(usedApps.getString(i))
        val unnamed = readableDatabase.rawQuery("SELECT 1 FROM usage_logs WHERE app_name='' LIMIT 1", null).use { it.moveToFirst() }
        JSONObject().put("protocols", distinct("SELECT DISTINCT protocol FROM usage_logs WHERE protocol!='' ORDER BY protocol"))
            .put("models", distinct("SELECT DISTINCT model FROM usage_logs WHERE model!='' ORDER BY model"))
            .put("apps", currentApps).put("apps_history", history).put("has_unnamed", unnamed)
            .put("statuses", distinct("SELECT DISTINCT status FROM usage_logs WHERE status!='' ORDER BY status"))
    }

    fun usageTimeseries(granularity: String = "hour", points: Int = 24, model: String? = null): JSONArray = synchronized(lock) {
        val day = granularity == "day"; val bucket = if (day) 86400L else 3600L
        val count = points.coerceIn(1, 1000); val now = nowSeconds().toLong()
        val end = if (day) localMidnight().toLong() else now - now % bucket
        val start = end - (count - 1) * bucket
        val sql = if (day) {
            "SELECT strftime('%Y-%m-%d', ts, 'unixepoch', 'localtime') b,COUNT(*) c,COALESCE(SUM(total_tokens),0) t FROM usage_logs WHERE ts>=? AND ts<?"
        } else {
            "SELECT (CAST(ts AS INTEGER)/$bucket)*$bucket b,COUNT(*) c,COALESCE(SUM(total_tokens),0) t FROM usage_logs WHERE ts>=? AND ts<?"
        }
        val query = StringBuilder(sql)
        val args = mutableListOf(start.toString(), (end + bucket).toString())
        if (!model.isNullOrBlank()) { query.append(" AND model=?"); args += model }
        query.append(" GROUP BY b")
        val found = mutableMapOf<String, Pair<Long, Long>>()
        readableDatabase.rawQuery(query.toString(), args.toTypedArray()).use { c -> while (c.moveToNext()) found[c.getString(0)] = c.getLong(1) to c.getLong(2) }
        val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
        val labelFormat = SimpleDateFormat(if (day) "MM-dd" else "MM-dd HH:mm", Locale.getDefault())
        JSONArray().apply { for (i in 0 until count) { val ts = start + i * bucket; val key = if (day) keyFormat.format(Date(ts * 1000)) else ts.toString(); val v = found[key] ?: (0L to 0L); put(JSONObject().put("bucket_ts", ts).put("bucket", labelFormat.format(Date(ts * 1000))).put("count", v.first).put("tokens", v.second)) } }
    }

    fun trimUsageContent(inputLimit: Int = INPUT_LIMIT, outputLimit: Int = OUTPUT_LIMIT, reasoningLimit: Int = REASONING_LIMIT): Int = synchronized(lock) {
        var changed = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT id,input_content,output_content,reasoning_content FROM usage_logs WHERE LENGTH(input_content)>? OR LENGTH(output_content)>? OR LENGTH(reasoning_content)>?", arrayOf(inputLimit.toString(), outputLimit.toString(), reasoningLimit.toString())).use { c ->
                while (c.moveToNext()) {
                    db.update("usage_logs", ContentValues().apply { put("input_content", clip(c.getString(1), inputLimit)); put("output_content", clip(c.getString(2), outputLimit)); put("reasoning_content", clip(c.getString(3), reasoningLimit)) }, "id=?", arrayOf(c.getLong(0).toString())); changed++
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed
    }

    fun cleanupUsage(retentionDays: Int): Int = synchronized(lock) {
        val cutoff = nowSeconds() - max(0, retentionDays) * 86400.0
        writableDatabase.delete("usage_logs", "ts<?", arrayOf(cutoff.toString()))
    }

    /**
     * 存储体积诊断：返回数据库文件大小 + 各内容字段占用估算 + 记录条数。
     *
     * 之前存储占用在界面上完全不可见，用户只能从系统设置里看到"数据 200MB"却不知从何而来，
     * 也没法判断某次清理到底有没有效果。这里把体积拆开，让"谁在占空间"一目了然。
     */
    fun storageInfo(): JSONObject = synchronized(lock) {
        val db = writableDatabase
        val pageCount = db.rawQuery("PRAGMA page_count", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val pageSize = db.rawQuery("PRAGMA page_size", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val fileBytes = pageCount * pageSize
        // 分别统计三个内容字段的总字符数，用于判断体积主要来自输入、输出还是思考链。
        val contentBytes = db.rawQuery(
            "SELECT COALESCE(SUM(LENGTH(input_content)),0),COALESCE(SUM(LENGTH(output_content)),0),COALESCE(SUM(LENGTH(reasoning_content)),0),COUNT(*) FROM usage_logs", null
        ).use { c ->
            if (c.moveToFirst()) longArrayOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3)) else longArrayOf(0, 0, 0, 0)
        }
        // 空闲页 = 曾经写入又被删掉的空间。SQLite 不会自动归还给系统，需要 VACUUM 才能真正缩小文件。
        val freePages = db.rawQuery("PRAGMA freelist_count", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        JSONObject()
            .put("db_bytes", fileBytes)
            .put("free_bytes", freePages * pageSize)
            .put("rows", contentBytes[3])
            .put("input_bytes", contentBytes[0])
            .put("output_bytes", contentBytes[1])
            .put("reasoning_bytes", contentBytes[2])
    }

    /** 回收空闲页，把数据库文件真正缩小（VACUUM 不能在事务中执行）。 */
    fun vacuum(): Unit = synchronized(lock) {
        runCatching { writableDatabase.execSQL("VACUUM") }
    }

    /** 删除单条使用记录；返回是否真的命中了行（未命中说明记录已被清掉，UI 不该提示“删除成功”）。 */
    fun deleteUsage(id: Long): Boolean = synchronized(lock) {
        writableDatabase.delete("usage_logs", "id=?", arrayOf(id.toString())) > 0
    }

    /**
     * 清空全部使用记录，返回删除条数。
     * 记录里存的是完整对话原文，删空后文件不会自动缩小，故默认 VACUUM 回收空间；
     * VACUUM 不允许在事务里执行，这里刻意放在 delete 之后单独调用。
     */
    fun clearUsage(vacuum: Boolean = true): Int = synchronized(lock) {
        val db = writableDatabase
        val removed = db.delete("usage_logs", null, null)
        if (removed > 0 && vacuum) runCatching { db.execSQL("VACUUM") }
        removed
    }

    // Settings and model cache --------------------------------------------------

    fun getSettings(): JSONObject = synchronized(lock) {
        val out = JSONObject(DEFAULT_SETTINGS)
        readableDatabase.rawQuery("SELECT key,value FROM settings", null).use { c -> while (c.moveToNext()) out.put(c.getString(0), c.getString(1)) }
        out
    }

    fun saveSettings(values: JSONObject): JSONObject = synchronized(lock) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            values.keys().forEach { key -> if (key in DEFAULT_SETTINGS) db.insertWithOnConflict("settings", null, ContentValues().apply { put("key", key); put("value", values.optString(key)) }, SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        getSettings()
    }

    fun saveModelCache(models: JSONArray, source: String = "dynamic", ttlSeconds: Long = 3600, region: String = "domestic"): Unit = synchronized(lock) {
        val now = nowSeconds()
        writableDatabase.insertWithOnConflict("model_cache", null, ContentValues().apply {
            put("cache_key", modelCacheKey(region)); put("payload", models.toString()); put("source", source)
            put("fetched_at", now); put("expires_at", now + max(1L, ttlSeconds))
        }, SQLiteDatabase.CONFLICT_REPLACE)
        // 旧版缓存条目里没有 credits（成本倍率）等字段，写完新格式后清掉旧键，避免后续读到残缺目录
        writableDatabase.delete("model_cache", "cache_key=?", arrayOf("models:$region"))
    }

    fun getModelCache(allowExpired: Boolean = true, region: String = "domestic"): JSONObject? = synchronized(lock) {
        readableDatabase.rawQuery("SELECT payload,source,fetched_at,expires_at FROM model_cache WHERE cache_key=?", arrayOf(modelCacheKey(region))).use { c ->
            if (!c.moveToFirst() || (!allowExpired && c.getDouble(3) < nowSeconds())) null else JSONObject()
                .put("models", runCatching { JSONArray(c.getString(0)) }.getOrDefault(JSONArray()))
                .put("source", c.getString(1)).put("fetched_at", c.getDouble(2)).put("expires_at", c.getDouble(3))
        }
    }

    /** 模型缓存键带格式版本：目录结构升级（如新增 credits 成本倍率）后旧缓存自动失效，避免 UI 读到残缺字段。 */
    /** 缓存键带版本号：模型目录结构变化（如全量目录、auto 倍率修复）时递增，确保升级后不会命中旧缓存。 */
    private fun modelCacheKey(region: String): String = "models:v3:$region"

    private fun usageFilter(protocol: String?, model: String?, appName: String?, status: String?): Pair<String, MutableList<String>> {
        val clauses = mutableListOf<String>(); val args = mutableListOf<String>()
        if (!protocol.isNullOrBlank()) { clauses += "protocol=?"; args += protocol }
        if (!model.isNullOrBlank()) { clauses += "model=?"; args += model }
        if (appName != null) { clauses += "COALESCE(app_name,'')=?"; args += appName }
        if (!status.isNullOrBlank()) { clauses += "status=?"; args += status }
        return (if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}") to args
    }

    private fun aggregate(sql: String, args: Array<String>? = null): Pair<Long, Long> = readableDatabase.rawQuery(sql, args).use { c -> c.moveToFirst(); c.getLong(0) to c.getLong(1) }
    private fun grouped(expression: String, alias: String, limit: Int? = null): JSONArray {
        val out = JSONArray(); val suffix = if (limit == null) "" else " LIMIT $limit"
        // 同时汇总 credits：用量页的分布维度要能显示真实积分消耗（历史上恒为 0，见 ApiHostService.upstreamCredits）。
        readableDatabase.rawQuery("SELECT $expression k,COUNT(*) c,COALESCE(SUM(total_tokens),0) t,COALESCE(SUM(credits),0) cr FROM usage_logs GROUP BY k ORDER BY c DESC$suffix", null).use { c ->
            while (c.moveToNext()) out.put(JSONObject().put(alias, c.getString(0)).put("count", c.getLong(1)).put("tokens", c.getLong(2)).put("credits", c.getDouble(3)))
        }; return out
    }
    private fun distinct(sql: String): JSONArray = JSONArray().apply { readableDatabase.rawQuery(sql, null).use { c -> while (c.moveToNext()) put(c.getString(0)) } }
    private fun accountJson(c: Cursor): JSONObject = rowJson(c).apply {
        val region = optString("region", "domestic")
        put("account_key", accountKey(region, optString("uid")))
        put("region_label", AccountRegion.from(region).label)
        put("enabled", optInt("enabled") != 0); put("healthy", optBoolean("enabled") && optDouble("cooldown_until") <= nowSeconds() && (isNull("credits_remaining") || optDouble("credits_remaining") > 0))
        put("credit_packages", runCatching { JSONArray(optString("credit_packages_json")) }.getOrDefault(JSONArray())); remove("credit_packages_json")
    }
    private fun accountKey(region: String, uid: String): String = "$region:$uid"
    private fun splitAccountKey(value: String): Pair<String, String> {
        val split = value.indexOf(':')
        return if (split > 0) value.substring(0, split) to value.substring(split + 1) else "domestic" to value
    }
    private fun appJson(c: Cursor): JSONObject = rowJson(c).apply {
        val region = optString("region", AccountRegion.DOMESTIC.id)
        put("region", region); put("region_label", AccountRegion.from(region).label)
        put("enabled", optInt("enabled") != 0)
    }
    private fun validateAppName(value: String): String = value.trim().also {
        require(it.isNotEmpty()) { "请输入应用名称" }
        require(it.length <= 40) { "应用名称不能超过 40 个字符" }
    }
    private fun validateAppNote(value: String): String = value.trim().also {
        require(it.length <= 200) { "备注不能超过 200 个字符" }
    }
    private fun validateAppKey(value: String): String = value.also {
        require(it.length in 16..256) { "自定义 Key 需要 16–256 位字符" }
        require(it.all { ch -> ch.code in 33..126 }) { "自定义 Key 只能包含无空格的可打印 ASCII 字符" }
    }
    private fun keyPrefix(key: String): String = if (key.length <= 8) "${key.take(3)}•••••" else "${key.take(8)}…"
    private fun insertApp(values: ContentValues): Long = try {
        writableDatabase.insertOrThrow("apps", null, values)
    } catch (error: android.database.sqlite.SQLiteConstraintException) {
        throw IllegalArgumentException("应用名称或 API Key 已存在", error)
    }
    private fun rowJson(c: Cursor): JSONObject = JSONObject().apply {
        for (i in 0 until c.columnCount) put(c.getColumnName(i), when (c.getType(i)) { Cursor.FIELD_TYPE_NULL -> JSONObject.NULL; Cursor.FIELD_TYPE_INTEGER -> c.getLong(i); Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i); Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP); else -> c.getString(i) })
    }
    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column)) ?: ""
    private fun clip(value: String?, limit: Int): String { val v = value.orEmpty(); return if (v.length <= limit) v else v.take(limit) + "\n…（已截断，原文 ${v.length} 字符）" }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, keyStoreKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(encoded: String): String = runCatching {
        if (encoded.isBlank()) return ""
        val raw = Base64.decode(encoded, Base64.NO_WRAP); val iv = raw.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, keyStoreKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
    }.getOrDefault("")
    private fun keyStoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    companion object {
        private const val DATABASE_NAME = "workbuddy-native.db"
        private const val DATABASE_VERSION = 8
        private const val KEY_ALIAS = "workbuddy_native_app_keys"
        /** 优先级下限：0 = 不额外加权（选号权重 = 1.0）。 */
        const val MIN_PRIORITY = 0
        /** 优先级上限：兜底防止误写极端值，让加权随机退化成固定选号。 */
        const val MAX_PRIORITY = 999
        /** saveSettings 只接受本表内的 key，新增配置项必须同时加到 here 与设置页控件。 */
        /**
         * 设置白名单。
         *
         * 为什么必须有这个白名单：saveSettings 只写「键已在此表」的项，
         * 用来防止任意键被写进 settings 表（避免脏数据与注入）。
         *
         * 注意：任何新增配置项都必须同步加到这里，否则会出现
         * 「点保存提示成功、但读回永远是旧值」的静默失败 ——
         * 提示词注入的四项（prompt_mode / prompt_text / sanitize_fingerprints /
         * use_degraded_prompt）就曾因此完全无法保存。
         */
        private val DEFAULT_SETTINGS = mapOf(
            "checkin_hours" to "9,21",
            "credit_refresh_min" to "30",
            "model_refresh_hour" to "6",
            "model_ttl_min" to "60",
            "aa_refresh_hour" to "7",
            "keepalive_hour" to "22",
            "keepalive_enabled" to "1",
            "aa_api_key" to "",
            "usage_retention_days" to "30",
            // ---- 提示词注入管线（移植自 WorkBuddy 1.2.7）----
            // prompt_text 默认留空：实际默认值较长且由 PromptInjection.DEFAULT_PROMPT 提供，
            // 这里留空可以让「用户从未改过」与「用户清空了」两种情况在 UI 层可区分。
            "prompt_mode" to "custom",
            "prompt_text" to "",
            "sanitize_fingerprints" to "1",
            "use_degraded_prompt" to "false"
        )
        /**
         * 记录内容字段的入库上限。取值理由：
         * - 输入 4000 字符 ≈ 一整轮较长对话；截断只影响超长多轮历史，日常排查完全够用。
         * - 输出/思考各 8000 字符：足够覆盖单次完整回复与推理链。
         * 三条合计上限约 20KB/条，即使 1 万条记录也仅约 200MB 的极端上限——
         * 而实际平均远小于此，配合默认 30 天保留期可把库稳定控制在合理范围。
         */
        private const val INPUT_LIMIT = 4000
        private const val OUTPUT_LIMIT = 8000
        private const val REASONING_LIMIT = 8000
        @Volatile private var instance: NativeStore? = null
        fun get(context: Context): NativeStore = instance ?: synchronized(this) { instance ?: NativeStore(context).also { instance = it } }
        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun randomHex(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0
        private fun localMidnight(): Double {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
            return (format.parse(format.format(Date()))?.time ?: System.currentTimeMillis()) / 1000.0
        }
    }
}
