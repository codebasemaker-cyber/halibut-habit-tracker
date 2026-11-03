package com.codebasemaker.habittracker.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.codebasemaker.habittracker.BuildConfig
import com.codebasemaker.habittracker.data.AppDatabase
import com.codebasemaker.habittracker.data.SettingKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class TableData(
    val name: String,
    val columns: List<String>,
    val types: List<String>,
    val rows: List<List<String?>>,
)

data class BackupContent(
    val schemaVersion: Int,
    val appVersion: String,
    val createdAt: String,
    val tables: Map<String, TableData>,
)

enum class RestoreMode { REPLACE, MERGE }

data class TablePreview(val table: String, val inBackup: Int, val adds: Int, val updates: Int, val deletes: Int)

data class RestorePreview(val mode: RestoreMode, val content: BackupContent, val tables: List<TablePreview>)

class BackupException(message: String) : Exception(message)

/**
 * Lossless backup of every table as CSV inside one zip (DESIGN.md §8).
 * Generic over the schema: columns and types are read from SQLite itself, so new columns are
 * picked up automatically.
 */
class BackupManager(private val context: Context, private val db: AppDatabase) {

    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    // ---- export --------------------------------------------------------------------------

    suspend fun writeZip(out: OutputStream) = withContext(Dispatchers.IO) {
        val tables = AppDatabase.BACKUP_TABLES.map { dumpTable(it) }
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.csv"))
            val manifest = StringBuilder()
            manifest.append(Csv.writeRow(listOf("key", "value")))
            manifest.append(Csv.writeRow(listOf("format", "halibut-habit-tracker-backup")))
            manifest.append(Csv.writeRow(listOf("schemaVersion", AppDatabase.SCHEMA_VERSION.toString())))
            manifest.append(Csv.writeRow(listOf("appVersion", BuildConfig.VERSION_NAME)))
            manifest.append(Csv.writeRow(listOf("createdAt", LocalDateTime.now().toString())))
            tables.forEach { manifest.append(Csv.writeRow(listOf("rows.${it.name}", it.rows.size.toString()))) }
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            tables.forEach { t ->
                zip.putNextEntry(ZipEntry("${t.name}.csv"))
                val sb = StringBuilder()
                sb.append(Csv.writeRow(t.columns))
                sb.append(Csv.writeRow(t.types))
                t.rows.forEach { sb.append(Csv.writeRow(it)) }
                zip.write(sb.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    /** Writes a timestamped backup into the chosen folder and prunes old ones. Returns the file name. */
    suspend fun backupToFolder(prefix: String = PREFIX): String = withContext(Dispatchers.IO) {
        val folder = folder() ?: throw BackupException("No backup folder chosen")
        val name = prefix + LocalDateTime.now().format(STAMP) + ".zip"
        val file = folder.createFile("application/zip", name) ?: throw BackupException("Could not create $name")
        context.contentResolver.openOutputStream(file.uri)?.use { writeZip(it) }
            ?: throw BackupException("Could not open $name")
        if (prefix == PREFIX) prune(folder)
        file.name ?: name
    }

    private suspend fun folder(): DocumentFile? {
        val uri = db.dao().setting(SettingKeys.BACKUP_TREE_URI)?.value?.ifEmpty { null } ?: return null
        val doc = DocumentFile.fromTreeUri(context, Uri.parse(uri)) ?: return null
        if (!doc.exists() || !doc.canWrite()) throw BackupException("Backup folder is missing or no longer accessible")
        return doc
    }

    private suspend fun prune(folder: DocumentFile) {
        val keep = db.dao().setting(SettingKeys.BACKUP_KEEP)?.value?.toIntOrNull()?.coerceAtLeast(1) ?: 10
        folder.listFiles()
            .filter { it.name?.startsWith(PREFIX) == true && it.name?.endsWith(".zip") == true }
            .sortedByDescending { it.name }
            .drop(keep)
            .forEach { it.delete() }
    }

    /** Safety copy before a replace-restore: the backup folder if set, otherwise app storage. */
    suspend fun safetyBackup(): String = withContext(Dispatchers.IO) {
        runCatching { folder() }.getOrNull()?.let { return@withContext backupToFolder(PRE_RESTORE_PREFIX) }
        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        val f = File(dir, PRE_RESTORE_PREFIX + LocalDateTime.now().format(STAMP) + ".zip")
        f.outputStream().use { writeZip(it) }
        dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
        f.absolutePath
    }

    private fun dumpTable(table: String): TableData {
        val info = tableInfo(table)
        val rows = mutableListOf<List<String?>>()
        sql.query("SELECT * FROM `$table`").use { c ->
            val idx = info.columns.map { c.getColumnIndexOrThrow(it) }
            while (c.moveToNext()) {
                rows += idx.map { i ->
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i).toString()
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toString()
                        else -> c.getString(i)
                    }
                }
            }
        }
        return TableData(table, info.columns, info.types, rows)
    }

    private data class Info(val columns: List<String>, val types: List<String>, val pk: List<String>)

    private fun tableInfo(table: String): Info {
        val cols = mutableListOf<String>(); val types = mutableListOf<String>(); val pk = mutableListOf<Pair<Int, String>>()
        sql.query("PRAGMA table_info(`$table`)").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                cols += name
                types += c.getString(c.getColumnIndexOrThrow("type")).uppercase()
                val p = c.getInt(c.getColumnIndexOrThrow("pk"))
                if (p > 0) pk += p to name
            }
        }
        return Info(cols, types, pk.sortedBy { it.first }.map { it.second })
    }

    // ---- import --------------------------------------------------------------------------

    suspend fun read(input: InputStream): BackupContent = withContext(Dispatchers.IO) {
        val files = mutableMapOf<String, String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) {
                    val buf = ByteArrayOutputStream(); zip.copyTo(buf)
                    files[e.name.substringAfterLast('/')] = buf.toString(Charsets.UTF_8.name())
                }
            }
        }
        val manifest = files["manifest.csv"]?.let { Csv.parse(it).drop(1).associate { r -> (r[0] ?: "") to (r.getOrNull(1) ?: "") } }
            ?: throw BackupException("Not a backup file (manifest.csv missing)")
        val schema = manifest["schemaVersion"]?.toIntOrNull() ?: throw BackupException("Backup has no schema version")
        if (schema > AppDatabase.SCHEMA_VERSION) {
            throw BackupException("This backup was made by a newer app version (schema $schema). Update the app first.")
        }
        val tables = AppDatabase.BACKUP_TABLES.mapNotNull { name ->
            val text = files["$name.csv"] ?: return@mapNotNull null
            val rows = Csv.parse(text)
            if (rows.size < 2) return@mapNotNull null
            val cols = rows[0].map { it ?: "" }
            val types = rows[1].map { it ?: "TEXT" }
            val expected = manifest["rows.$name"]?.toIntOrNull()
            val data = rows.drop(2).filter { it.size == cols.size }
            if (expected != null && expected != data.size) {
                throw BackupException("Backup is damaged: $name has ${data.size} rows, expected $expected")
            }
            name to TableData(name, cols, types, data)
        }.toMap()
        BackupContent(schema, manifest["appVersion"] ?: "?", manifest["createdAt"] ?: "?", tables)
    }

    suspend fun preview(content: BackupContent, mode: RestoreMode): RestorePreview = withContext(Dispatchers.IO) {
        val plan = plan(content, mode)
        RestorePreview(mode, content, plan.map { p ->
            TablePreview(p.table, content.tables[p.table]?.rows?.size ?: 0, p.inserts.count { !p.existing.contains(it.key) },
                p.inserts.count { p.existing.contains(it.key) }, p.deletes.size)
        })
    }

    suspend fun restore(content: BackupContent, mode: RestoreMode) = withContext(Dispatchers.IO) {
        val plan = plan(content, mode)
        db.withTransaction {
            val deviceSettings = if (mode == RestoreMode.REPLACE) {
                SettingKeys.DEVICE_KEYS.mapNotNull { k -> db.dao().setting(k) }
            } else emptyList()
            for (p in plan) {
                if (mode == RestoreMode.REPLACE) sql.execSQL("DELETE FROM `${p.table}`")
                for (key in p.deletes) {
                    val where = p.pk.joinToString(" AND ") { "`$it` = ?" }
                    sql.delete(p.table, where, key.split('|').toTypedArray())
                }
                for (row in p.inserts) sql.insert(p.table, SQLiteDatabase.CONFLICT_REPLACE, row.values)
            }
            deviceSettings.forEach { db.dao().upsertSetting(it) }
        }
    }

    private data class Row(val key: String, val values: ContentValues)
    private data class TablePlan(
        val table: String, val pk: List<String>, val existing: Set<String>, val inserts: List<Row>, val deletes: List<String>,
    )

    private fun plan(content: BackupContent, mode: RestoreMode): List<TablePlan> {
        val localTomb = mutableMapOf<String, Long>() // "table|rowId" -> deletedAt
        sql.query("SELECT tableName, rowId, deletedAt FROM tombstone").use { c ->
            while (c.moveToNext()) localTomb["${c.getString(0)}|${c.getString(1)}"] = c.getLong(2)
        }
        val incomingTomb = content.tables["tombstone"]?.let { t ->
            val ti = t.columns.indexOf("tableName"); val ri = t.columns.indexOf("rowId"); val di = t.columns.indexOf("deletedAt")
            t.rows.mapNotNull { r -> if (ti < 0 || ri < 0 || di < 0) null else Triple(r[ti]!!, r[ri]!!, r[di]!!.toLong()) }
        }.orEmpty()

        return AppDatabase.BACKUP_TABLES.map { table ->
            val info = tableInfo(table)
            val incoming = content.tables[table]
            val localUpdated = mutableMapOf<String, Long>()
            val hasUpdated = "updatedAt" in info.columns
            sql.query("SELECT * FROM `$table`").use { c ->
                val pkIdx = info.pk.map { c.getColumnIndexOrThrow(it) }
                val uIdx = if (hasUpdated) c.getColumnIndexOrThrow("updatedAt") else if (table == "tombstone") c.getColumnIndexOrThrow("deletedAt") else -1
                while (c.moveToNext()) {
                    localUpdated[pkIdx.joinToString("|") { c.getString(it) }] = if (uIdx >= 0) c.getLong(uIdx) else 0L
                }
            }
            val rows = incoming?.rows.orEmpty().mapNotNull { r -> toRow(table, info, incoming!!, r) }
                .filterNot { table == "setting" && it.second.key in SettingKeys.DEVICE_KEYS }

            if (mode == RestoreMode.REPLACE) {
                TablePlan(table, info.pk, localUpdated.keys, rows.map { it.second }, emptyList())
            } else {
                val inserts = rows.filter { (updated, row) ->
                    val tomb = localTomb["$table|${row.key}"]
                    when {
                        tomb != null && tomb >= updated -> false
                        table == "setting" && row.key in SettingKeys.DEVICE_KEYS -> false
                        else -> localUpdated[row.key]?.let { updated > it } ?: true
                    }
                }.map { it.second }
                val deletes = if (table == "tombstone") emptyList() else incomingTomb
                    .filter { (t, rowId, deletedAt) -> t == table && localUpdated[rowId]?.let { it <= deletedAt } == true }
                    .map { it.second }
                TablePlan(table, info.pk, localUpdated.keys, inserts, deletes)
            }
        }
    }

    /** Converts a CSV row to ContentValues for the current schema. Returns (updatedAt, row). */
    private fun toRow(table: String, info: Info, data: TableData, r: List<String?>): Pair<Long, Row>? {
        val cv = ContentValues()
        for ((i, col) in info.columns.withIndex()) {
            val src = data.columns.indexOf(col)
            if (src < 0) continue
            val v = r[src]
            val type = info.types[i]
            when {
                v == null -> cv.putNull(col)
                type.contains("INT") -> cv.put(col, v.toLongOrNull() ?: return null)
                type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB") -> cv.put(col, v.toDoubleOrNull() ?: return null)
                else -> cv.put(col, v)
            }
        }
        if (info.pk.any { cv.getAsString(it) == null }) return null
        val key = info.pk.joinToString("|") { cv.getAsString(it) }
        val updated = when (table) {
            "tombstone" -> cv.getAsLong("deletedAt") ?: 0L
            else -> cv.getAsLong("updatedAt") ?: 0L
        }
        return updated to Row(key, cv)
    }

    companion object {
        const val PREFIX = "habit-backup-"
        const val PRE_RESTORE_PREFIX = "habit-prerestore-"
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
    }
}
