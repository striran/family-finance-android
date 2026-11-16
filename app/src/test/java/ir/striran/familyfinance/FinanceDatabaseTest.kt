package ir.striran.familyfinance

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FinanceDatabaseTest {
    private lateinit var context: Context
    private lateinit var db: FinanceDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("family_finance.db")
        db = FinanceDatabase(context)
        db.writableDatabase
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase("family_finance.db")
    }

    @Test fun incomeAndExpenseUpdateAccountBalance() {
        val account = db.accounts().first()
        assertTrue(db.addTransaction(account.id, "درآمد", "حقوق", 100_000, ""))
        assertEquals(100_000L, db.accounts().first { it.id == account.id }.balance)
        assertTrue(db.addTransaction(account.id, "هزینه", "خرید", 25_000, ""))
        assertEquals(75_000L, db.accounts().first { it.id == account.id }.balance)
    }

    @Test fun expenseCannotExceedAvailableBalance() {
        val account = db.accounts().first()
        assertFalse(db.addTransaction(account.id, "هزینه", "خرید", 1, ""))
        assertEquals(0L, db.accounts().first { it.id == account.id }.balance)
        assertTrue(db.addTransaction(account.id, "درآمد", "حقوق", 500, ""))
        assertFalse(db.addTransaction(account.id, "هزینه", "خرید", 501, ""))
        assertEquals(500L, db.accounts().first { it.id == account.id }.balance)
    }

    @Test fun transferIsAtomicAndExcludedFromIncomeExpenseTotals() {
        val accounts = db.accounts()
        assertTrue(db.addTransaction(accounts[0].id, "درآمد", "حقوق", 1_000, ""))
        assertTrue(db.transfer(accounts[0].id, accounts[1].id, 400, "انتقال"))
        assertEquals(600L, db.accounts().first { it.id == accounts[0].id }.balance)
        assertEquals(400L, db.accounts().first { it.id == accounts[1].id }.balance)
        assertEquals(1_000L, db.totalBalance())
        assertEquals(1_000L, db.monthTotal("درآمد"))
        assertEquals(0L, db.monthTotal("هزینه"))
        assertFalse(db.transfer(accounts[0].id, accounts[1].id, 601, "بیش از موجودی"))
    }

    @Test fun editingTransactionReversesOldEffectBeforeApplyingNewEffect() {
        val account = db.accounts().first()
        assertTrue(db.addTransaction(account.id, "درآمد", "حقوق", 1_000, ""))
        assertTrue(db.addTransaction(account.id, "هزینه", "خرید", 200, ""))
        val tx = db.allTransactions().first { it.type == "هزینه" }
        assertTrue(db.updateTransaction(tx.id, account.id, "هزینه", "خرید", 300, "اصلاح"))
        assertEquals(700L, db.accounts().first { it.id == account.id }.balance)
        assertFalse(db.updateTransaction(tx.id, account.id, "هزینه", "خرید", 2_000, "نامعتبر"))
        assertEquals(700L, db.accounts().first { it.id == account.id }.balance)
    }

    @Test fun savingGoalCannotReceiveMoreThanItsTarget() {
        assertTrue(db.addGoal("سفر", 1_000))
        val goal = db.goals().first()
        assertTrue(db.contributeGoal(goal.id, 700))
        assertFalse(db.contributeGoal(goal.id, 301))
        assertEquals(700L, db.goals().first().saved)
    }

    @Test fun memberCreationAlsoCreatesItsDefaultAccount() {
        assertTrue(db.addMember("فرزند"))
        assertTrue(db.accounts().any { it.memberName == "فرزند" && it.name == "کیف پول فرزند" })
    }
    @Test fun deletingTransactionRestoresAccountBalance() {
        val account = db.accounts().first()
        assertTrue(db.addTransaction(account.id, "درآمد", "حقوق", 1_000, ""))
        assertTrue(db.addTransaction(account.id, "هزینه", "خرید", 250, ""))
        val expense = db.allTransactions().first { it.category == "خرید" }
        assertTrue(db.deleteTransaction(expense.id))
        assertEquals(1_000L, db.accounts().first { it.id == account.id }.balance)
        assertFalse(db.allTransactions().any { it.id == expense.id })
    }

    @Test fun deletingSingleTransferEntryIsRejected() {
        val accounts = db.accounts()
        assertTrue(db.addTransaction(accounts[0].id, "درآمد", "حقوق", 1_000, ""))
        assertTrue(db.transfer(accounts[0].id, accounts[1].id, 400, "انتقال"))
        val transferEntry = db.allTransactions().first { it.category == "انتقال وجه" }
        assertFalse(db.deleteTransaction(transferEntry.id))
        assertEquals(600L, db.accounts().first { it.id == accounts[0].id }.balance)
        assertEquals(400L, db.accounts().first { it.id == accounts[1].id }.balance)
        assertEquals(2, db.allTransactions().count { it.category == "انتقال وجه" || it.category == "دریافت انتقال" })
    }

    @Test fun deletingTransactionRejectsBalanceOverflowWithoutChangingData() {
        val account = db.accounts().first()
        assertTrue(db.addTransaction(account.id, "درآمد", "حقوق", 200, ""))
        assertTrue(db.addTransaction(account.id, "هزینه", "خرید", 100, ""))
        val expense = db.allTransactions().first { it.category == "خرید" }
        db.writableDatabase.execSQL(
            "UPDATE accounts SET balance=? WHERE id=?",
            arrayOf(Long.MAX_VALUE, account.id)
        )
        assertFalse(db.deleteTransaction(expense.id))
        assertEquals(Long.MAX_VALUE, db.accounts().first { it.id == account.id }.balance)
        assertTrue(db.allTransactions().any { it.id == expense.id })
    }

    @Test fun debtCreationSplitsTotalAcrossInstallmentsAndPreservesRemainder() {
        val firstDue = java.util.Calendar.getInstance().apply {
            set(2027, java.util.Calendar.JANUARY, 31, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertTrue(db.addDebt("وام خانه", 1_000L, 3, firstDue, "بانک"))
        val debt = db.debts().single()
        val installments = db.debtInstallments(debt.id)
        assertEquals(3, installments.size)
        assertEquals(1_000L, installments.sumOf { it.amount })
        assertEquals(listOf(333L,333L,334L), installments.map { it.amount })
        assertEquals(31, java.util.Calendar.getInstance().apply { timeInMillis=installments[0].dueAt }.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(28, java.util.Calendar.getInstance().apply { timeInMillis=installments[1].dueAt }.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(1_000L, debt.remainingAmount)
        assertEquals(0, debt.paidInstallments)
    }

    @Test fun payingDebtInstallmentDeductsAccountAndCreatesExpenseAtomically() {
        val account=db.accounts().first()
        assertTrue(db.addTransaction(account.id,"درآمد","حقوق",2_000L,""))
        assertTrue(db.addDebt("قسط خودرو",1_000L,2,System.currentTimeMillis(),""))
        val debt=db.debts().single()
        val first=db.debtInstallments(debt.id).first()
        assertTrue(db.payDebtInstallment(first.id,account.id))
        assertEquals(1_500L,db.accounts().first{it.id==account.id}.balance)
        assertEquals(500L,db.debts().single().paidAmount)
        assertEquals(500L,db.debts().single().remainingAmount)
        assertEquals(1,db.debts().single().paidInstallments)
        assertEquals("قسط بدهی",db.allTransactions().first().category)
        assertFalse(db.payDebtInstallment(first.id,account.id))
        assertFalse(db.deleteDebt(debt.id))
    }

    @Test fun debtPaymentFailsWithoutEnoughBalanceAndLeavesInstallmentUnpaid() {
        val account=db.accounts().first()
        assertTrue(db.addDebt("قرض",500L,1,System.currentTimeMillis(),""))
        val installment=db.debtInstallments(db.debts().single().id).single()
        assertFalse(db.payDebtInstallment(installment.id,account.id))
        assertNull(db.debtInstallments(db.debts().single().id).single().paidAt)
        assertEquals(0L,db.accounts().first{it.id==account.id}.balance)
        assertTrue(db.deleteDebt(db.debts().single().id))
    }

    @Test fun debtValidationRejectsInvalidInstallmentCountAndAmount() {
        val due=System.currentTimeMillis()
        assertFalse(db.addDebt("",1_000,1,due,""))
        assertFalse(db.addDebt("وام",0,1,due,""))
        assertFalse(db.addDebt("وام",2,3,due,""))
        assertFalse(db.addDebt("وام",1_000,121,due,""))
        assertTrue(db.debts().isEmpty())
    }

    @Test fun receivablesCanBeCreatedReceivedAndDeletedWithoutChangingCashBalance() {
        val due = System.currentTimeMillis() + 86_400_000L
        assertTrue(db.addReceivable("علی", 250_000L, due, "قرض کوتاه‌مدت"))
        val receivable = db.receivables().single()
        assertEquals(250_000L, db.totalPendingReceivables())
        assertTrue(db.markReceivableReceived(receivable.id))
        assertFalse(db.markReceivableReceived(receivable.id))
        assertEquals(0L, db.totalPendingReceivables())
        assertNotNull(db.receivables().single().receivedAt)
        assertEquals(0L, db.totalBalance())
        assertTrue(db.deleteReceivable(receivable.id))
    }

    @Test fun receivableValidationRejectsBlankPersonAndInvalidAmount() {
        val due = System.currentTimeMillis()
        assertFalse(db.addReceivable("", 100L, due, ""))
        assertFalse(db.addReceivable("علی", 0L, due, ""))
        assertFalse(db.addReceivable("علی", 100L, 0L, ""))
        assertTrue(db.receivables().isEmpty())
    }

    @Test fun overdueAndUpcomingInstallmentsAreClassifiedByDueDate() {
        val now = System.currentTimeMillis()
        val yesterday = now - 86_400_000L
        val tomorrow = now + 86_400_000L
        assertTrue(db.addDebt("وام عقب‌افتاده", 100L, 1, yesterday, ""))
        assertTrue(db.addDebt("وام نزدیک", 200L, 1, tomorrow, ""))
        assertEquals(1, db.overdueInstallments(now).size)
        assertEquals(1, db.upcomingInstallments(3, now).size)
        assertEquals(300L, db.totalRemainingDebt())
    }

    @Test fun recurringBillPaymentUpdatesBalanceTransactionAndNextDueDate() {
        val account=db.accounts().first()
        assertTrue(db.addTransaction(account.id,"درآمد","حقوق",2_000_000L,""))
        val now=System.currentTimeMillis()
        val due=java.util.Calendar.getInstance().apply{timeInMillis=now;add(java.util.Calendar.DAY_OF_YEAR,-1)}.timeInMillis
        assertTrue(db.addRecurringBill("قبض اینترنت","اینترنت",300_000L,due,"ماهانه",""))
        val bill=db.dueRecurringBills(now).single()
        assertTrue(db.payRecurringBill(bill.id,account.id,now))
        assertEquals(1_700_000L,db.accounts().first{it.id==account.id}.balance)
        assertEquals("اینترنت",db.allTransactions().first().category)
        assertTrue(db.dueRecurringBills(now).isEmpty())
        val next=db.recurringBills().single()
        val expected=java.util.Calendar.getInstance().apply{timeInMillis=due;add(java.util.Calendar.MONTH,1)}.timeInMillis
        assertEquals(expected,next.dueAt)
        assertFalse(db.payRecurringBill(bill.id,account.id,now))
    }

    @Test fun recurringBillPaymentFailsSafelyWhenBalanceIsInsufficient() {
        val due=System.currentTimeMillis()-1_000L
        assertTrue(db.addRecurringBill("قبض برق","برق",500_000L,due,"ماهانه",""))
        val bill=db.recurringBills().single()
        assertFalse(db.payRecurringBill(bill.id,db.accounts().first().id))
        assertEquals(due,db.recurringBills().single().dueAt)
        assertTrue(db.allTransactions().isEmpty())
    }

    @Test fun recurringBillRejectsInvalidInputsAndSupportsDeletion() {
        val due=System.currentTimeMillis()+86_400_000L
        assertFalse(db.addRecurringBill("","برق",100L,due,"ماهانه",""))
        assertFalse(db.addRecurringBill("قبض","",100L,due,"ماهانه",""))
        assertFalse(db.addRecurringBill("قبض","برق",0L,due,"ماهانه",""))
        assertFalse(db.addRecurringBill("قبض","برق",100L,due,"روزانه",""))
        assertTrue(db.addRecurringBill("قبض","برق",100L,due,"هفتگی",""))
        assertTrue(db.deleteRecurringBill(db.recurringBills().single().id))
        assertTrue(db.recurringBills().isEmpty())
    }

    @Test fun upgradingVersionThreeDatabasePreservesExistingDataAndAddsDebtTables() {
        db.close()
        context.deleteDatabase("family_finance.db")
        val file=context.getDatabasePath("family_finance.db")
        file.parentFile?.mkdirs()
        val old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null)
        old.execSQL("CREATE TABLE members(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL)")
        old.execSQL("CREATE TABLE accounts(id INTEGER PRIMARY KEY AUTOINCREMENT,member_id INTEGER NOT NULL,name TEXT NOT NULL,balance INTEGER NOT NULL DEFAULT 0)")
        old.execSQL("CREATE TABLE transactions(id INTEGER PRIMARY KEY AUTOINCREMENT,account_id INTEGER NOT NULL,type TEXT NOT NULL,category TEXT NOT NULL,amount INTEGER NOT NULL,note TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL)")
        old.execSQL("CREATE TABLE budgets(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT NOT NULL UNIQUE,limit_amount INTEGER NOT NULL)")
        old.execSQL("CREATE TABLE goals(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,target INTEGER NOT NULL,saved INTEGER NOT NULL DEFAULT 0)")
        old.execSQL("INSERT INTO members(id,name) VALUES(1,'من')")
        old.execSQL("INSERT INTO accounts(id,member_id,name,balance) VALUES(1,1,'کیف پول من',7500)")
        old.version=3
        old.close()
        db=FinanceDatabase(context)
        assertEquals(7_500L,db.accounts().single().balance)
        assertTrue(db.addDebt("بدهی قدیمی",1_000L,2,System.currentTimeMillis(),""))
        assertEquals(2,db.debtInstallments(db.debts().single().id).size)
    }

}
