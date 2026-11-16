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

}
