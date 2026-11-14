package ir.striran.familyfinance

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class FinanceTransaction(
    val id: Long,
    val type: String,
    val category: String,
    val amount: Long,
    val note: String,
    val createdAt: Long,
    val accountName: String
)

class FinanceDatabase(context: Context) :
    SQLiteOpenHelper(context, "family_finance.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE members(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE accounts(id INTEGER PRIMARY KEY AUTOINCREMENT, member_id INTEGER NOT NULL, name TEXT NOT NULL, balance INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL, type TEXT NOT NULL, category TEXT NOT NULL, amount INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_transactions_date ON transactions(created_at)")
        db.execSQL("INSERT INTO members(name) VALUES('من'),('همسر')")
        db.execSQL("INSERT INTO accounts(member_id,name,balance) VALUES(1,'کیف پول من',0),(2,'کیف پول همسر',0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun addTransaction(type: String, category: String, amount: Long, note: String): Boolean {
        if (amount <= 0) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("account_id", 1)
                put("type", type)
                put("category", category)
                put("amount", amount)
                put("note", note.trim())
                put("created_at", System.currentTimeMillis())
            }
            db.insertOrThrow("transactions", null, values)
            val sign = if (type == "درآمد") 1 else -1
            db.execSQL("UPDATE accounts SET balance = balance + ? WHERE id = 1", arrayOf(sign * amount))
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun totalBalance(): Long = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(balance),0) FROM accounts", null
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun monthTotal(type: String): Long = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type=? AND created_at >= ?",
        arrayOf(type, monthStart().toString())
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun recentTransactions(): List<FinanceTransaction> {
        val result = mutableListOf<FinanceTransaction>()
        readableDatabase.rawQuery(
            "SELECT t.id,t.type,t.category,t.amount,t.note,t.created_at,a.name FROM transactions t JOIN accounts a ON a.id=t.account_id ORDER BY t.created_at DESC LIMIT 30",
            null
        ).use { c ->
            while (c.moveToNext()) result += FinanceTransaction(
                c.getLong(0), c.getString(1), c.getString(2), c.getLong(3),
                c.getString(4), c.getLong(5), c.getString(6)
            )
        }
        return result
    }

    fun accounts(): List<Pair<String, Long>> {
        val result = mutableListOf<Pair<String, Long>>()
        readableDatabase.rawQuery("SELECT name,balance FROM accounts ORDER BY id", null).use { c ->
            while (c.moveToNext()) result += c.getString(0) to c.getLong(1)
        }
        return result
    }

    private fun monthStart(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
