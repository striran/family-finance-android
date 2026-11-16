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
data class FamilyDebt(
    val id: Long, val title: String, val totalAmount: Long, val installmentCount: Int,
    val firstDueAt: Long, val note: String, val paidAmount: Long,
    val paidInstallments: Int, val nextDueAt: Long?
) {
    val remainingAmount: Long get() = (totalAmount - paidAmount).coerceAtLeast(0L)
}
data class DebtInstallment(
    val id: Long, val debtId: Long, val number: Int, val amount: Long,
    val dueAt: Long, val paidAt: Long?
)
data class FamilyReceivable(
    val id: Long, val person: String, val amount: Long, val dueAt: Long,
    val note: String, val receivedAt: Long?
)
data class RecurringBill(
    val id: Long, val title: String, val category: String, val amount: Long,
    val dueAt: Long, val frequency: String, val note: String
)
private data class TransactionForDeletion(val accountId: Long, val type: String, val amount: Long, val category: String)

class FinanceDatabase(context: Context) : SQLiteOpenHelper(context, "family_finance.db", null, 6) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE members(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE accounts(id INTEGER PRIMARY KEY AUTOINCREMENT, member_id INTEGER NOT NULL, name TEXT NOT NULL, balance INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL, type TEXT NOT NULL, category TEXT NOT NULL, amount INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_transactions_date ON transactions(created_at)")
        db.execSQL("CREATE INDEX idx_transactions_category_type_date ON transactions(category,type,created_at)")
        db.execSQL("CREATE TABLE budgets(id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL UNIQUE, limit_amount INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE goals(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, target INTEGER NOT NULL, saved INTEGER NOT NULL DEFAULT 0)")
        createDebtTables(db)
        createReceivableTables(db)
        createRecurringBillTables(db)
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
        if (oldVersion < 4) createDebtTables(db)
        if (oldVersion < 5) createReceivableTables(db)
        if (oldVersion < 6) createRecurringBillTables(db)
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
    fun deleteTransaction(id: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val transaction = db.rawQuery(
                "SELECT account_id,type,amount,category FROM transactions WHERE id=?",
                arrayOf(id.toString())
            ).use { c ->
                if (!c.moveToFirst()) return false
                TransactionForDeletion(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3))
            }
            // Transfer rows are a linked pair; deleting only one side would corrupt account balances.
            if (transaction.category == "انتقال وجه" || transaction.category == "دریافت انتقال") return false
            val currentBalance = db.rawQuery(
                "SELECT balance FROM accounts WHERE id=?",
                arrayOf(transaction.accountId.toString())
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else return false }
            val delta = if (transaction.type == "درآمد") -transaction.amount else transaction.amount
            val restoredBalance = checkedBalance(currentBalance, delta) ?: return false
            val updated = db.update(
                "accounts",
                ContentValues().apply { put("balance", restoredBalance) },
                "id=?",
                arrayOf(transaction.accountId.toString())
            )
            if (updated != 1) return false
            if (db.delete("transactions", "id=?", arrayOf(id.toString())) != 1) return false
            db.setTransactionSuccessful()
            return true
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

    private fun createDebtTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS debts(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, total_amount INTEGER NOT NULL, installment_count INTEGER NOT NULL, first_due_at INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS debt_installments(id INTEGER PRIMARY KEY AUTOINCREMENT, debt_id INTEGER NOT NULL REFERENCES debts(id) ON DELETE CASCADE, installment_number INTEGER NOT NULL, amount INTEGER NOT NULL, due_at INTEGER NOT NULL, paid_at INTEGER)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_debt_installment_number ON debt_installments(debt_id,installment_number)")
    }

    fun debts(): List<FamilyDebt> = readableDatabase.rawQuery(
        "SELECT d.id,d.title,d.total_amount,d.installment_count,d.first_due_at,d.note,COALESCE(SUM(CASE WHEN i.paid_at IS NOT NULL THEN i.amount ELSE 0 END),0),COALESCE(SUM(CASE WHEN i.paid_at IS NOT NULL THEN 1 ELSE 0 END),0),(SELECT MIN(i2.due_at) FROM debt_installments i2 WHERE i2.debt_id=d.id AND i2.paid_at IS NULL) FROM debts d LEFT JOIN debt_installments i ON i.debt_id=d.id GROUP BY d.id ORDER BY d.first_due_at,d.id",
        null
    ).use { c -> buildList { while(c.moveToNext()) add(FamilyDebt(c.getLong(0),c.getString(1),c.getLong(2),c.getInt(3),c.getLong(4),c.getString(5),c.getLong(6),c.getInt(7),if(c.isNull(8)) null else c.getLong(8))) } }

    fun debtInstallments(debtId: Long): List<DebtInstallment> = readableDatabase.rawQuery(
        "SELECT id,debt_id,installment_number,amount,due_at,paid_at FROM debt_installments WHERE debt_id=? ORDER BY installment_number",
        arrayOf(debtId.toString())
    ).use { c -> buildList { while(c.moveToNext()) add(DebtInstallment(c.getLong(0),c.getLong(1),c.getInt(2),c.getLong(3),c.getLong(4),if(c.isNull(5)) null else c.getLong(5))) } }

    fun addDebt(title: String, totalAmount: Long, installmentCount: Int, firstDueAt: Long, note: String): Boolean {
        if(title.isBlank() || totalAmount<=0 || installmentCount !in 1..120 || installmentCount.toLong()>totalAmount || firstDueAt<=0) return false
        val db=writableDatabase
        db.beginTransaction()
        try {
            val debtId=db.insertOrThrow("debts",null,ContentValues().apply {
                put("title",title.trim()); put("total_amount",totalAmount); put("installment_count",installmentCount)
                put("first_due_at",firstDueAt); put("note",note.trim()); put("created_at",System.currentTimeMillis())
            })
            val base=totalAmount/installmentCount
            val remainder=totalAmount%installmentCount
            for(index in 0 until installmentCount) {
                val due=Calendar.getInstance().apply {
                    timeInMillis=firstDueAt
                    val day=get(Calendar.DAY_OF_MONTH)
                    set(Calendar.DAY_OF_MONTH,1)
                    add(Calendar.MONTH,index)
                    val maxDay=getActualMaximum(Calendar.DAY_OF_MONTH)
                    set(Calendar.DAY_OF_MONTH,day.coerceAtMost(maxDay))
                }.timeInMillis
                db.insertOrThrow("debt_installments",null,ContentValues().apply {
                    put("debt_id",debtId); put("installment_number",index+1)
                    put("amount",base+if(index==installmentCount-1) remainder else 0L); put("due_at",due)
                })
            }
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun payDebtInstallment(installmentId: Long, accountId: Long): Boolean {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val installment=db.rawQuery(
                "SELECT i.debt_id,i.installment_number,i.amount,d.title FROM debt_installments i JOIN debts d ON d.id=i.debt_id WHERE i.id=? AND i.paid_at IS NULL",
                arrayOf(installmentId.toString())
            ).use { c -> if(!c.moveToFirst()) return false else arrayOf(c.getLong(0),c.getLong(1),c.getLong(2),c.getString(3)) }
            val amount=installment[2] as Long
            val balance=db.rawQuery("SELECT balance FROM accounts WHERE id=?",arrayOf(accountId.toString())).use { c -> if(c.moveToFirst()) c.getLong(0) else return false }
            if(balance<amount) return false
            val updatedBalance=checkedBalance(balance,-amount) ?: return false
            if(db.update("accounts",ContentValues().apply{put("balance",updatedBalance)},"id=?",arrayOf(accountId.toString()))!=1) return false
            val title=installment[3] as String
            db.insertOrThrow("transactions",null,ContentValues().apply {
                put("account_id",accountId); put("type","هزینه"); put("category","قسط بدهی")
                put("amount",amount); put("note","قسط ${installment[1]} · $title"); put("created_at",System.currentTimeMillis())
            })
            if(db.update("debt_installments",ContentValues().apply{put("paid_at",System.currentTimeMillis())},"id=? AND paid_at IS NULL",arrayOf(installmentId.toString()))!=1) return false
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun deleteDebt(id: Long): Boolean {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val hasPayments=db.rawQuery("SELECT 1 FROM debt_installments WHERE debt_id=? AND paid_at IS NOT NULL LIMIT 1",arrayOf(id.toString())).use{it.moveToFirst()}
            if(hasPayments) return false
            db.delete("debt_installments","debt_id=?",arrayOf(id.toString()))
            val deleted=db.delete("debts","id=?",arrayOf(id.toString()))==1
            if(deleted) db.setTransactionSuccessful()
            return deleted
        } finally { db.endTransaction() }
    }

    private fun createRecurringBillTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS recurring_bills(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, category TEXT NOT NULL, amount INTEGER NOT NULL, due_at INTEGER NOT NULL, frequency TEXT NOT NULL, note TEXT NOT NULL DEFAULT '', active INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_recurring_bills_due ON recurring_bills(active,due_at)")
    }

    fun recurringBills(): List<RecurringBill> = readableDatabase.rawQuery(
        "SELECT id,title,category,amount,due_at,frequency,note FROM recurring_bills WHERE active=1 ORDER BY due_at,id", null
    ).use { c -> buildList { while(c.moveToNext()) add(RecurringBill(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4),c.getString(5),c.getString(6))) } }

    fun addRecurringBill(title:String,category:String,amount:Long,dueAt:Long,frequency:String,note:String):Boolean {
        if(title.isBlank() || category.isBlank() || amount<=0 || dueAt<=0 || frequency !in listOf("هفتگی","ماهانه","سالانه")) return false
        return writableDatabase.insert("recurring_bills",null,ContentValues().apply {
            put("title",title.trim());put("category",category.trim());put("amount",amount)
            put("due_at",dueAt);put("frequency",frequency);put("note",note.trim())
        })>0
    }

    fun dueRecurringBills(now:Long=System.currentTimeMillis()):List<RecurringBill> =
        readableDatabase.rawQuery("SELECT id,title,category,amount,due_at,frequency,note FROM recurring_bills WHERE active=1 AND due_at<=? ORDER BY due_at,id",arrayOf(now.toString()))
            .use { c -> buildList { while(c.moveToNext()) add(RecurringBill(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4),c.getString(5),c.getString(6))) } }

    fun payRecurringBill(id:Long,accountId:Long,now:Long=System.currentTimeMillis()):Boolean {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val bill=db.rawQuery("SELECT title,category,amount,due_at,frequency,note FROM recurring_bills WHERE id=? AND active=1",arrayOf(id.toString())).use { c ->
                if(!c.moveToFirst()) return false
                RecurringBill(id,c.getString(0),c.getString(1),c.getLong(2),c.getLong(3),c.getString(4),c.getString(5))
            }
            if(bill.dueAt>now) return false
            val balance=db.rawQuery("SELECT balance FROM accounts WHERE id=?",arrayOf(accountId.toString())).use { if(it.moveToFirst())it.getLong(0) else return false }
            if(balance<bill.amount) return false
            val updated=checkedBalance(balance,-bill.amount) ?: return false
            db.insertOrThrow("transactions",null,ContentValues().apply {
                put("account_id",accountId);put("type","هزینه");put("category",bill.category)
                put("amount",bill.amount);put("note",("پرداخت دوره‌ای: "+bill.title+" "+bill.note).trim());put("created_at",now)
            })
            db.update("accounts",ContentValues().apply{put("balance",updated)},"id=?",arrayOf(accountId.toString()))
            val calendar=Calendar.getInstance().apply{timeInMillis=bill.dueAt}
            when(bill.frequency) {
                "هفتگی" -> calendar.add(Calendar.DAY_OF_YEAR,7)
                "ماهانه" -> {
                    val day=calendar.get(Calendar.DAY_OF_MONTH)
                    calendar.set(Calendar.DAY_OF_MONTH,1)
                    calendar.add(Calendar.MONTH,1)
                    calendar.set(Calendar.DAY_OF_MONTH,day.coerceAtMost(calendar.getActualMaximum(Calendar.DAY_OF_MONTH)))
                }
                "سالانه" -> {
                    val day=calendar.get(Calendar.DAY_OF_MONTH)
                    val month=calendar.get(Calendar.MONTH)
                    calendar.set(Calendar.DAY_OF_MONTH,1)
                    calendar.add(Calendar.YEAR,1)
                    calendar.set(Calendar.MONTH,month)
                    calendar.set(Calendar.DAY_OF_MONTH,day.coerceAtMost(calendar.getActualMaximum(Calendar.DAY_OF_MONTH)))
                }
            }
            if(db.update("recurring_bills",ContentValues().apply{put("due_at",calendar.timeInMillis)},"id=? AND due_at=?",arrayOf(id.toString(),bill.dueAt.toString()))!=1) return false
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun deleteRecurringBill(id:Long):Boolean = writableDatabase.delete("recurring_bills","id=?",arrayOf(id.toString()))==1

    private fun createReceivableTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS receivables(id INTEGER PRIMARY KEY AUTOINCREMENT, person TEXT NOT NULL, amount INTEGER NOT NULL, due_at INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, received_at INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_receivables_due ON receivables(due_at,received_at)")
    }

    fun receivables(): List<FamilyReceivable> = readableDatabase.rawQuery(
        "SELECT id,person,amount,due_at,note,received_at FROM receivables ORDER BY received_at IS NOT NULL,due_at,id", null
    ).use { c -> buildList { while(c.moveToNext()) add(FamilyReceivable(c.getLong(0),c.getString(1),c.getLong(2),c.getLong(3),c.getString(4),if(c.isNull(5)) null else c.getLong(5))) } }

    fun addReceivable(person: String, amount: Long, dueAt: Long, note: String): Boolean {
        if(person.isBlank() || amount<=0 || dueAt<=0) return false
        return writableDatabase.insert("receivables",null,ContentValues().apply {
            put("person",person.trim()); put("amount",amount); put("due_at",dueAt)
            put("note",note.trim()); put("created_at",System.currentTimeMillis())
        }) > 0
    }

    fun markReceivableReceived(id: Long): Boolean = writableDatabase.update(
        "receivables",ContentValues().apply { put("received_at",System.currentTimeMillis()) },
        "id=? AND received_at IS NULL",arrayOf(id.toString())
    ) == 1

    fun deleteReceivable(id: Long): Boolean =
        writableDatabase.delete("receivables","id=?",arrayOf(id.toString())) == 1

    fun overdueInstallments(now: Long = System.currentTimeMillis()): List<DebtInstallment> =
        readableDatabase.rawQuery(
            "SELECT id,debt_id,installment_number,amount,due_at,paid_at FROM debt_installments WHERE paid_at IS NULL AND due_at<? ORDER BY due_at",
            arrayOf(now.toString())
        ).use { c -> buildList { while(c.moveToNext()) add(DebtInstallment(c.getLong(0),c.getLong(1),c.getInt(2),c.getLong(3),c.getLong(4),if(c.isNull(5)) null else c.getLong(5))) } }

    fun upcomingInstallments(days: Int = 3, now: Long = System.currentTimeMillis()): List<DebtInstallment> {
        if(days !in 1..30) return emptyList()
        val until = now + days.toLong() * 24L * 60L * 60L * 1000L
        return readableDatabase.rawQuery(
            "SELECT id,debt_id,installment_number,amount,due_at,paid_at FROM debt_installments WHERE paid_at IS NULL AND due_at>=? AND due_at<=? ORDER BY due_at",
            arrayOf(now.toString(),until.toString())
        ).use { c -> buildList { while(c.moveToNext()) add(DebtInstallment(c.getLong(0),c.getLong(1),c.getInt(2),c.getLong(3),c.getLong(4),if(c.isNull(5)) null else c.getLong(5))) } }
    }

    fun totalRemainingDebt(): Long = scalar(
        "SELECT COALESCE(SUM(i.amount),0) FROM debt_installments i WHERE i.paid_at IS NULL"
    )
    fun totalPendingReceivables(): Long = scalar(
        "SELECT COALESCE(SUM(amount),0) FROM receivables WHERE received_at IS NULL"
    )

    private fun scalar(sql:String)=readableDatabase.rawQuery(sql,null).use{if(it.moveToFirst())it.getLong(0)else 0L}
    private fun monthStart():Long { val c=Calendar.getInstance(); c.set(Calendar.DAY_OF_MONTH,1); c.set(Calendar.HOUR_OF_DAY,0); c.set(Calendar.MINUTE,0); c.set(Calendar.SECOND,0); c.set(Calendar.MILLISECOND,0); return c.timeInMillis }
}
