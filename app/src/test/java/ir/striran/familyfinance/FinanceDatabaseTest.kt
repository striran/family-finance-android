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

}
