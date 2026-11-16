package ir.striran.familyfinance

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

data class FinanceAccount(val id: Long, val memberId: Long, val memberName: String, val name: String, val balance: Long)
data class FinanceMember(val id: Long, val name: String)
data class FinanceTransaction(
    val id: Long, val accountId: Long, val type: String, val category: String,
    val amount: Long, val note: String, val createdAt: Long, val accountName: String,
    val memberName: String
)
data class Budget(val id: Long, val category: String, val limitAmount: Long, val spent: Long)
data class SavingGoal(val id: Long, val title: String, val target: Long, val saved: Long)

class FinanceDatabase(context: Context) : SQLiteOpenHelper(context, "family_finance.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE members(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE accounts(id INTEGER PRIMARY KEY AUTOINCREMENT, member_id INTEGER NOT NULL, name TEXT NOT NULL, balance INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL, type TEXT NOT NULL, category TEXT NOT NULL, amount INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_transactions_date ON transactions(created_at)")
        db.execSQL("CREATE INDEX idx_transactions_category_type_date ON transactions(category,type,created_at)")
        db.execSQL("CREATE TABLE budgets(id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL UNIQUE, limit_amount INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE goals(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, target INTEGER NOT NULL, saved INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("INSERT INTO members(name) VALUES('من'),('همسر')")
        db.execSQL("INSERT INTO accounts(member_id,name,balance) VALUES(1,'کیف پول من',0),(2,'کیف پول همسر',0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS budgets(id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL UNIQUE, limit_amount INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS goals(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, target INTEGER NOT NULL, saved INTEGER NOT NULL DEFAULT 0)")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_transactions_category_type_date ON transactions(category,type,created_at)")
        }
    }

    fun members(): List<FinanceMember> = readableDatabase.rawQuery("SELECT id,name FROM members ORDER BY id", null).use { c ->
        buildList { while (c.moveToNext()) add(FinanceMember(c.getLong(0), c.getString(1))) }
    }
    fun accounts(): List<FinanceAccount> = readableDatabase.rawQuery(
        "SELECT a.id,a.member_id,m.name,a.name,a.balance FROM accounts a JOIN members m ON m.id=a.member_id ORDER BY a.id", null
    ).use { c -> buildList { while(c.moveToNext()) add(FinanceAccount(c.getLong(0),c.getLong(1),c.getString(2),c.getString(3),c.getLong(4))) } }

    fun addMember(name: String): Boolean {
        if (name.isBlank()) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("members", null, ContentValues().apply { put("name", name.trim()) })
            db.insertOrThrow("accounts", null, ContentValues().apply {
                put("member_id", id); put("name", "کیف پول ${name.trim()}"); put("balance", 0)
            })
            db.setTransactionSuccessful()
            return true
        } catch (_: Exception) {
            return false
        } finally { db.endTransaction() }
    }
    fun addAccount(memberId: Long, name: String): Boolean {
        if (name.isBlank() || members().none { it.id == memberId }) return false
        return writableDatabase.insert("accounts", null, ContentValues().apply {
            put("member_id", memberId); put("name", name.trim()); put("balance", 0)
        }) > 0
    }
    fun addTransaction(accountId: Long, type: String, category: String, amount: Long, note: String): Boolean {
        if (amount <= 0 || category.isBlank() || accounts().none { it.id == accountId } || type !in listOf("درآمد","هزینه")) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val currentBalance = db.rawQuery("SELECT balance FROM accounts WHERE id=?", arrayOf(accountId.toString())).use {
                if (it.moveToFirst()) it.getLong(0) else return false
            }
            if (type == "هزینه" && currentBalance < amount) return false
            val delta = if (type == "درآمد") amount else -amount
            val updatedBalance = checkedBalance(currentBalance, delta) ?: return false
            db.insertOrThrow("transactions", null, ContentValues().apply {
                put("account_id", accountId); put("type", type); put("category", category.trim())
                put("amount", amount); put("note", note.trim()); put("created_at", System.currentTimeMillis())
            })
            db.update("accounts", ContentValues().apply { put("balance", updatedBalance) }, "id=?", arrayOf(accountId.toString()))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }
    fun updateTransaction(id: Long, accountId: Long, type: String, category: String, amount: Long, note: String): Boolean {
        if (amount <= 0 || category.isBlank() || type !in listOf("درآمد", "هزینه")) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = db.rawQuery("SELECT account_id,type,amount FROM transactions WHERE id=?", arrayOf(id.toString())).use {
                if (!it.moveToFirst()) return false
                Triple(it.getLong(0), it.getString(1), it.getLong(2))
            }
            val targetExists = db.rawQuery("SELECT 1 FROM accounts WHERE id=?", arrayOf(accountId.toString())).use { it.moveToFirst() }
            if (!targetExists) return false
            val oldBalance = db.rawQuery("SELECT balance FROM accounts WHERE id=?", arrayOf(old.first.toString())).use {
                if (it.moveToFirst()) it.getLong(0) else return false
            }
            val oldDelta = if (old.second == "درآمد") -old.third else old.third
            val restoredOldBalance = checkedBalance(oldBalance, oldDelta) ?: return false
            val targetBalance = if (old.first == accountId) restoredOldBalance else db.rawQuery("SELECT balance FROM accounts WHERE id=?", arrayOf(accountId.toString())).use {
                if (it.moveToFirst()) it.getLong(0) else return false
            }
            if (type == "هزینه" && targetBalance < amount) return false
            val newDelta = if (type == "درآمد") amount else -amount
            val updatedTargetBalance = checkedBalance(targetBalance, newDelta) ?: return false
            if (old.first != accountId) {
                db.update("accounts", ContentValues().apply { put("balance", restoredOldBalance) }, "id=?", arrayOf(old.first.toString()))
            }
            db.update("accounts", ContentValues().apply { put("balance", updatedTargetBalance) }, "id=?", arrayOf(accountId.toString()))
            db.update("transactions", ContentValues().apply {
                put("account_id",accountId); put("type",type); put("category",category.trim())
                put("amount",amount); put("note",note.trim())
            }, "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }
    fun deleteTransaction(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT account_id,type,amount FROM transactions WHERE id=?", arrayOf(id.toString())).use { c ->
                if (c.moveToFirst()) {
                    val delta = if (c.getString(1) == "درآمد") -c.getLong(2) else c.getLong(2)
                    db.execSQL("UPDATE accounts SET balance=balance+? WHERE id=?", arrayOf(delta,c.getLong(0)))
                }
            }
            db.delete("transactions","id=?",arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun transfer(fromId: Long, toId: Long, amount: Long, note: String): Boolean {
        if (fromId == toId || amount <= 0) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val balance = db.rawQuery("SELECT balance FROM accounts WHERE id=?",arrayOf(fromId.toString())).use { if(it.moveToFirst()) it.getLong(0) else return false }
            val destinationExists = db.rawQuery("SELECT 1 FROM accounts WHERE id=?",arrayOf(toId.toString())).use { it.moveToFirst() }
            if (!destinationExists || balance < amount) return false
            val destinationBalance = db.rawQuery("SELECT balance FROM accounts WHERE id=?",arrayOf(toId.toString())).use { if(it.moveToFirst()) it.getLong(0) else return false }
            val newSourceBalance = checkedBalance(balance, -amount) ?: return false
            val newDestinationBalance = checkedBalance(destinationBalance, amount) ?: return false
            db.update("accounts", ContentValues().apply { put("balance",newSourceBalance) }, "id=?", arrayOf(fromId.toString()))
            db.update("accounts", ContentValues().apply { put("balance",newDestinationBalance) }, "id=?", arrayOf(toId.toString()))
            val stamp = System.currentTimeMillis()
            db.insertOrThrow("transactions",null,ContentValues().apply {
                put("account_id",fromId); put("type","هزینه"); put("category","انتقال وجه"); put("amount",amount); put("note","انتقال به حساب مقصد · $note"); put("created_at",stamp)
            })
            db.insertOrThrow("transactions",null,ContentValues().apply {
                put("account_id",toId); put("type","درآمد"); put("category","دریافت انتقال"); put("amount",amount); put("note","دریافت از حساب مبدأ · $note"); put("created_at",stamp)
            })
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }
    fun totalBalance() = scalar("SELECT COALESCE(SUM(balance),0) FROM accounts")
    fun monthTotal(type: String): Long = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type=? AND created_at>=? AND category NOT IN ('انتقال وجه','دریافت انتقال')",
        arrayOf(type, monthStart().toString())
    ).use { if(it.moveToFirst()) it.getLong(0) else 0L }
    fun allTransactions(): List<FinanceTransaction> = readableDatabase.rawQuery(
        "SELECT t.id,t.account_id,t.type,t.category,t.amount,t.note,t.created_at,a.name,m.name FROM transactions t JOIN accounts a ON a.id=t.account_id JOIN members m ON m.id=a.member_id ORDER BY t.created_at DESC",null
    ).use { c -> buildList { while(c.moveToNext()) add(FinanceTransaction(c.getLong(0),c.getLong(1),c.getString(2),c.getString(3),c.getLong(4),c.getString(5),c.getLong(6),c.getString(7),c.getString(8))) } }
    fun recentTransactions() = allTransactions().take(30)
    fun categoryTotals(): List<Pair<String,Long>> = readableDatabase.rawQuery(
        "SELECT category,SUM(amount) FROM transactions WHERE type='هزینه' AND created_at>=? AND category NOT IN ('انتقال وجه','دریافت انتقال') GROUP BY category ORDER BY SUM(amount) DESC",
        arrayOf(monthStart().toString())
    ).use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to c.getLong(1)) } }
    fun budgets(): List<Budget> = readableDatabase.rawQuery(
        "SELECT b.id,b.category,b.limit_amount,COALESCE((SELECT SUM(t.amount) FROM transactions t WHERE t.category=b.category AND t.type='هزینه' AND t.created_at>=?),0) FROM budgets b ORDER BY b.id",
        arrayOf(monthStart().toString())
    ).use { c -> buildList { while(c.moveToNext()) add(Budget(c.getLong(0),c.getString(1),c.getLong(2),c.getLong(3))) } }
    fun saveBudget(category: String, limit: Long): Boolean {
        if(category.isBlank() || limit<=0) return false
        val v=ContentValues().apply { put("category",category.trim()); put("limit_amount",limit) }
        writableDatabase.insertWithOnConflict("budgets",null,v,SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }
    fun deleteBudget(id: Long): Boolean = writableDatabase.delete("budgets", "id=?", arrayOf(id.toString())) > 0
    fun goals(): List<SavingGoal> = readableDatabase.rawQuery("SELECT id,title,target,saved FROM goals ORDER BY id DESC",null).use { c ->
        buildList { while(c.moveToNext()) add(SavingGoal(c.getLong(0),c.getString(1),c.getLong(2),c.getLong(3))) }
    }
    fun addGoal(title:String,target:Long):Boolean {
        if(title.isBlank()||target<=0)return false
        return writableDatabase.insert("goals",null,ContentValues().apply{put("title",title.trim());put("target",target);put("saved",0)})>0
    }
    fun contributeGoal(id:Long,amount:Long):Boolean {
        if(amount<=0)return false
        val db = writableDatabase
        val statement = db.compileStatement("UPDATE goals SET saved=saved+? WHERE id=? AND saved<=target-?")
        statement.bindLong(1, amount); statement.bindLong(2, id); statement.bindLong(3, amount)
        return statement.executeUpdateDelete() > 0
    }
    fun deleteGoal(id:Long){writableDatabase.delete("goals","id=?",arrayOf(id.toString()))}
    private fun scalar(sql:String)=readableDatabase.rawQuery(sql,null).use{if(it.moveToFirst())it.getLong(0)else 0L}
    private fun monthStart():Long { val c=Calendar.getInstance(); c.set(Calendar.DAY_OF_MONTH,1); c.set(Calendar.HOUR_OF_DAY,0); c.set(Calendar.MINUTE,0); c.set(Calendar.SECOND,0); c.set(Calendar.MILLISECOND,0); return c.timeInMillis }
}
