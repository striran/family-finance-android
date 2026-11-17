package ir.striran.familyfinance

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.graphics.pdf.PdfDocument
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager

class MainActivity : ComponentActivity() {
    private lateinit var db: FinanceDatabase
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = FinanceDatabase(this)
        val immediateReminder = OneTimeWorkRequestBuilder<InstallmentReminderWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "family-installment-reminder-initial",
            ExistingWorkPolicy.KEEP,
            immediateReminder
        )
        val dailyReminder = PeriodicWorkRequestBuilder<InstallmentReminderWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "family-installment-reminder-daily",
            ExistingPeriodicWorkPolicy.KEEP,
            dailyReminder
        )
        val smsPermissionMissing = checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED
        val notificationPermissionMissing = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (smsPermissionMissing) {
            Toast.makeText(this, "برای شناسایی پیامک‌های بانکی، اجازه دسترسی به پیامک ورودی را تأیید کنید.", Toast.LENGTH_LONG).show()
            val requested = if (notificationPermissionMissing) arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS)
                else arrayOf(Manifest.permission.RECEIVE_SMS)
            requestPermissions(requested, 7002)
        } else if (notificationPermissionMissing) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7001)
        }
        setContent {
            var activeDb by remember { mutableStateOf(db) }
            val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                if (uri != null) {
                    try {
                        activeDb.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { }
                        activeDb.close()
                        val source = getDatabasePath("family_finance.db")
                        contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                            ?: throw IllegalStateException("امکان نوشتن فایل پشتیبان وجود ندارد")
                        activeDb = FinanceDatabase(this)
                        db = activeDb
                        Toast.makeText(this, "پشتیبان با موفقیت ذخیره شد.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        activeDb = FinanceDatabase(this)
                        db = activeDb
                        val errorText = e.localizedMessage ?: "خطای ناشناخته"
                        Toast.makeText(this, "تهیه پشتیبان ناموفق بود: $errorText", Toast.LENGTH_LONG).show()
                    }
                }
            }
            val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                if (uri != null) try {
                    val rows = activeDb.allTransactions()
                    val csv = buildString {
                        append('\uFEFF')
                        appendLine("تاریخ,نوع,دسته‌بندی,مبلغ (تومان),حساب,عضو خانواده,توضیحات")
                        rows.forEach { t ->
                            val cells = listOf(jalali(t.createdAt),t.type,t.category,t.amount.toString(),t.accountName,t.memberName,t.note)
                            appendLine(cells.joinToString(",") { "\"" + it.replace("\"","\"\"") + "\"" })
                        }
                    }
                    contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                        ?: throw IllegalStateException("امکان ذخیره فایل Excel وجود ندارد")
                    Toast.makeText(this, "گزارش سازگار با Excel ذخیره شد.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "خروجی Excel ناموفق بود: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
            val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
                if (uri != null) try {
                    val rows = activeDb.allTransactions()
                    val document = PdfDocument()
                    val pages = rows.chunked(14).ifEmpty { listOf(emptyList()) }
                    pages.forEachIndexed { index, pageRows ->
                        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, index + 1).create()
                        val page = document.startPage(pageInfo)
                        val reportText = buildString {
                            appendLine("گزارش مالی خانواده")
                            appendLine("تاریخ تهیه: ${jalali(System.currentTimeMillis())}")
                            appendLine("صفحه ${index + 1} از ${pages.size}")
                            appendLine("────────────────────────────────────────")
                            pageRows.forEach { t ->
                                appendLine("${jalali(t.createdAt)} | ${t.type} | ${t.category}")
                                appendLine("${money(t.amount)} | ${t.accountName} | ${t.memberName}")
                                if (t.note.isNotBlank()) appendLine("توضیحات: ${t.note}")
                                appendLine("────────────────────────────────────────")
                            }
                        }
                        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.BLACK
                            textSize = 12f
                            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                        }
                        val layout = StaticLayout.Builder.obtain(reportText,0,reportText.length,paint,510)
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setTextDirection(TextDirectionHeuristics.RTL)
                            .setLineSpacing(3f,1f)
                            .build()
                        page.canvas.save()
                        page.canvas.translate(42f,42f)
                        layout.draw(page.canvas)
                        page.canvas.restore()
                        document.finishPage(page)
                    }
                    contentResolver.openOutputStream(uri)?.use { document.writeTo(it) }
                        ?: throw IllegalStateException("امکان ذخیره فایل PDF وجود ندارد")
                    document.close()
                    Toast.makeText(this, "گزارش PDF ذخیره شد.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "خروجی PDF ناموفق بود: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
            val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    val temporary = File(cacheDir, "finance-restore.tmp")
                    val current = getDatabasePath("family_finance.db")
                    val rollback = File(cacheDir, "finance-before-restore.db")
                    var databaseClosed = false
                    try {
                        contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { input.copyTo(it) } }
                            ?: throw IllegalArgumentException("فایل انتخاب‌شده قابل خواندن نیست")
                        val checkDb = android.database.sqlite.SQLiteDatabase.openDatabase(temporary.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
                        val integrity = checkDb.rawQuery("PRAGMA integrity_check", null).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == "ok" }
                        val requiredTables = checkDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('members','accounts','transactions','budgets','goals')", null).use { it.count == 5 }
                        checkDb.close()
                        if (!integrity || !requiredTables) throw IllegalArgumentException("این فایل، پشتیبان معتبر برنامه مدیریت مالی خانواده نیست")
                        activeDb.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { }
                        activeDb.close()
                        databaseClosed = true
                        File(current.path + "-wal").delete()
                        File(current.path + "-shm").delete()
                        if (current.exists()) current.copyTo(rollback, overwrite=true)
                        temporary.copyTo(current, overwrite=true)
                        activeDb = FinanceDatabase(this)
                        db = activeDb
                        rollback.delete()
                        Toast.makeText(this, "بازیابی اطلاعات با موفقیت انجام شد.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        if (!databaseClosed) activeDb.close()
                        if (rollback.exists()) rollback.copyTo(current, overwrite=true)
                        activeDb = FinanceDatabase(this)
                        db = activeDb
                        val errorText = e.localizedMessage ?: "فایل نامعتبر است"
                        Toast.makeText(this, "بازیابی انجام نشد: $errorText", Toast.LENGTH_LONG).show()
                    } finally {
                        temporary.delete()
                        rollback.delete()
                    }
                }
            }
            FinanceApp(activeDb, onExport = { export.launch("پشتیبان-مدیریت-مالی.db") }, onImport = { import.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "*/*")) }, onExportExcel = { exportCsv.launch("گزارش-تراکنش‌های-مالی.csv") }, onExportPdf = { exportPdf.launch("گزارش-مالی-خانواده.pdf") })
        }
    }
}

private val midnight = Color(0xFF090B20)
private val panel = Color(0xD9262445)
private val violet = Color(0xFFB72EFF)
private val aqua = Color(0xFF43E3C2)
private val pink = Color(0xFFFF438F)
private val muted = Color(0xFFAAA9C7)
private fun money(value: Long): String = DecimalFormat("#,###").format(value) + " تومان"
private fun jalali(millis: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    var jy = c.get(java.util.Calendar.YEAR) - 621
    val gy = c.get(java.util.Calendar.YEAR)
    val gm = c.get(java.util.Calendar.MONTH) + 1
    val gd = c.get(java.util.Calendar.DAY_OF_MONTH)
    val gdm = intArrayOf(0,31,if (gy%4==0 && (gy%100!=0 || gy%400==0)) 29 else 28,31,30,31,30,31,31,30,31,30,31)
    var days = 365*(gy-1600)+(gy-1600+3)/4-(gy-1600+99)/100+(gy-1600+399)/400-79
    for(i in 1 until gm) days += gdm[i]
    days += gd-1
    var jdays = days - 79
    val jnp = jdays / 12053
    jdays %= 12053
    jy = 979 + 33*jnp + 4*(jdays/1461)
    jdays %= 1461
    if(jdays>=366){ jy += (jdays-1)/365; jdays=(jdays-1)%365 }
    val jm: Int
    val jd: Int
    if(jdays<186){ jm=1+(jdays/31); jd=1+(jdays%31) } else { jm=7+((jdays-186)/30); jd=1+((jdays-186)%30) }
    return "%04d/%02d/%02d".format(jy,jm,jd)
}

@Composable
fun FinanceApp(db: FinanceDatabase, onExport: () -> Unit, onImport: () -> Unit, onExportExcel: () -> Unit, onExportPdf: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<FinanceTransaction?>(null) }
    var selectedGoal by remember { mutableStateOf<SavingGoal?>(null) }
    var selectedBudget by remember { mutableStateOf<Budget?>(null) }
    var selectedInstallment by remember { mutableStateOf<DebtInstallment?>(null) }
    var selectedDebt by remember { mutableStateOf<FamilyDebt?>(null) }
    var selectedReceivable by remember { mutableStateOf<FamilyReceivable?>(null) }
    var selectedRecurringBill by remember { mutableStateOf<RecurringBill?>(null) }
    var selectedCheque by remember { mutableStateOf<FamilyCheque?>(null) }
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE) }
    var dark by remember { mutableStateOf(preferences.getBoolean("dark_theme", false)) }
    var smsSuggestion by remember { mutableStateOf(readBankSmsSuggestion(context)) }
    val balance = remember(refresh) { db.totalBalance() }
    val income = remember(refresh) { db.monthTotal("درآمد") }
    val expense = remember(refresh) { db.monthTotal("هزینه") }
    val txs = remember(refresh) { db.allTransactions() }
    val accounts = remember(refresh) { db.accounts() }
    val budgets = remember(refresh) { db.budgets() }
    val goals = remember(refresh) { db.goals() }
    val debts = remember(refresh) { db.debts() }
    val receivables = remember(refresh) { db.receivables() }
    val recurringBills = remember(refresh) { db.recurringBills() }
    val cheques = remember(refresh) { db.cheques() }
    val overdueInstallments = remember(refresh) { db.overdueInstallments() }
    val upcomingInstallments = remember(refresh) { db.upcomingInstallments(7) }
    val installmentsByDebt = remember(refresh) { debts.associate { it.id to db.debtInstallments(it.id) } }
    val bg = Color.Transparent
    val fg = if(dark) Color(0xFFF8F6FF) else Color(0xFF132D3A)
    val sub = if(dark) Color(0xFFB9B7D2) else Color(0xFF55717D)
    MaterialTheme(colorScheme = if(dark) darkColorScheme(primary=Color(0xFFE0B7FF),secondary=aqua,background=midnight,surface=panel,onSurface=fg) else lightColorScheme(primary=Color(0xFF087F9B),secondary=Color(0xFFFF8B28),background=Color(0xFFEAFBFF),surface=Color(0xEFFFFFFF),onSurface=fg)) {
        Scaffold(
        containerColor=bg,
        topBar={
            Surface(color=Color.Transparent,shadowElevation=0.dp){
                Row(Modifier.fillMaxWidth().height(66.dp).background(Brush.linearGradient(if(dark) listOf(Color(0xFF11152F),Color(0xFF3C185B),Color(0xFF17152F)) else listOf(Color(0xFF08A9C6),Color(0xFF48C9D7),Color(0xFFFFA24A))).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
                    IconButton(onClick={page=4}){Icon(Icons.Default.Menu,null,tint=Color.White)}
                    Column(Modifier.weight(1f)){
                        Text("مدیریت مالی خانواده",color=Color.White,fontSize=16.sp,fontWeight=FontWeight.Bold)
                        Text("حسابداری شخصی و خانوادگی",color=Color.White.copy(alpha=.78f),fontSize=10.sp)
                    }
                    Icon(Icons.Default.AccountBalanceWallet,null,tint=Color.White.copy(alpha=.92f),modifier=Modifier.padding(end=8.dp).size(22.dp))
                }
            }
        },
        bottomBar={
            Surface(color=if(dark)Color(0xE51B1A35) else Color(0xDDF5FFFF),shadowElevation=14.dp,border=BorderStroke(1.dp,if(dark)Color.White.copy(alpha=.16f) else Color.White.copy(alpha=.92f)),shape=RoundedCornerShape(topStart=22.dp,topEnd=22.dp)){
                NavigationBar(containerColor=Color.Transparent,tonalElevation=0.dp,windowInsets=WindowInsets(0,0,0,0),modifier=Modifier.height(64.dp)){
                    val navPages=listOf(4,3,1,0)
                    val labels=listOf("امکانات","گزارش‌ها","تراکنش‌ها","خانه")
                    val icons=listOf(Icons.Default.GridView,Icons.Default.BarChart,Icons.Default.ReceiptLong,Icons.Default.Home)
                    labels.forEachIndexed { i,label ->
                        val selectedPage=page==navPages[i] || (i==0 && page in listOf(2,5,6))
                        NavigationBarItem(selected=selectedPage,onClick={page=navPages[i]},
                            icon={Icon(icons[i],null,modifier=Modifier.size(if(selectedPage)24.dp else 21.dp))},
                            label={Text(label,fontSize=10.sp,fontWeight=if(selectedPage)FontWeight.Bold else FontWeight.Normal)},
                            colors=NavigationBarItemDefaults.colors(
                                selectedIconColor=if(dark)Color(0xFFE1C7F0) else violet,
                                selectedTextColor=if(dark)Color(0xFFE1C7F0) else violet,
                                unselectedIconColor=if(dark)Color(0xFFB8AABD) else Color(0xFF92909A),
                                unselectedTextColor=if(dark)Color(0xFFB8AABD) else Color(0xFF92909A),
                                indicatorColor=if(dark)Color(0xFF603D72) else Color(0xFFF1EAF5)
                            )
                        )
                    }
                }
            }
        },
        floatingActionButtonPosition=FabPosition.Center,
        floatingActionButton={
            if(page==0||page==1||page==5||page==6) FloatingActionButton(
                onClick={dialog=when(page){5->"debt";6->"cheque";else->"transaction"}},
                containerColor=Color(0xFF36B879),contentColor=Color.White,shape=RoundedCornerShape(50)
            ){Icon(Icons.Default.Add,null)}
        }
    ){ pad ->
        when(page) {
            0 -> Dashboard(pad,balance,income,expense,txs.take(5),db.categoryTotals(),fg,sub,dark,{dialog="transaction"},{dialog="transfer"})
            1 -> TransactionsPage(pad,txs,fg,sub,dark,{selected=it;dialog="edit"},{selected=it;dialog="delete"})
            2 -> AccountsPage(pad,accounts,fg,sub,dark,{dialog="member"},{dialog="account"},{dialog="transfer"})
            3 -> ReportsPage(pad,income,expense,db.categoryTotals(),budgets,fg,sub,dark,{dialog="budget"},{ budget -> selectedBudget=budget; dialog="deleteBudget" })
            4 -> MorePage(pad,goals,fg,sub,dark,{page=2},{page=5},{page=6},{dialog="goal"},{ goal -> selectedGoal=goal; dialog="contribute" },{ goal -> selectedGoal=goal; dialog="deleteGoal" },{dark=!dark;preferences.edit().putBoolean("dark_theme",dark).apply()},onExport,onImport,onExportExcel,onExportPdf)
            5 -> DebtsPage(pad,debts,installmentsByDebt,receivables,recurringBills,overdueInstallments,upcomingInstallments,db.totalRemainingDebt(),db.totalPendingReceivables(),fg,sub,dark,{ installment -> selectedInstallment=installment; dialog="payDebt" },{ debt -> dialog="deleteDebt"; selectedDebt=debt },{ dialog="receivable" },{ receivable -> selectedReceivable=receivable; dialog="markReceived" },{ receivable -> selectedReceivable=receivable; dialog="deleteReceivable" },{dialog="recurringBill"},{bill->selectedRecurringBill=bill;dialog="payRecurringBill"},{bill->selectedRecurringBill=bill;dialog="deleteRecurringBill"})
            else -> ChequesPage(pad,cheques,fg,sub,dark,{dialog="cheque"},{cheque,status->db.updateChequeStatus(cheque.id,status).also{if(it)refresh++}},{cheque->selectedCheque=cheque;dialog="deleteCheque"})
        }
    }
    when(dialog) {
            "transaction" -> TransactionDialog(db,accounts,dark,onDismiss={dialog=""},onSave={a,t,c,v,n->db.addTransaction(a,t,c,v,n).also{if(it)refresh++}})
            "edit" -> selected?.let { EditDialog(db,it,accounts,dark,onDismiss={dialog="";selected=null},onSave={a,t,c,v,n->db.updateTransaction(it.id,a,t,c,v,n).also{ok->if(ok)refresh++}}) }
            "delete" -> selected?.let { AlertDialog(onDismissRequest={dialog="";selected=null},containerColor=if(dark) panel else Color(0xFFF8F9F5),title={Text("حذف تراکنش",color=if(dark)Color.White else Color(0xFF303039))},text={GlassDialogBody(dark=dark) { Text("این تراکنش حذف و موجودی حساب اصلاح شود؟",color=if(dark)Color.White else Color(0xFF303039)) }},confirmButton={TextButton(onClick={if(db.deleteTransaction(it.id)){refresh++;dialog="";selected=null}else{Toast.makeText(context,"حذف انجام نشد؛ موجودی یا نوع تراکنش را بررسی کنید.",Toast.LENGTH_LONG).show()}}){Text("حذف")}},dismissButton={TextButton(onClick={dialog="";selected=null}){Text("انصراف")}}) }
            "transfer" -> TransferDialog(accounts,dark,onDismiss={dialog=""},onSave={a,b,v,n->db.transfer(a,b,v,n).also{if(it)refresh++}})
            "member" -> SimpleNameDialog("افزودن عضو خانواده","نام عضو",dark,onDismiss={dialog=""},onSave={db.addMember(it).also{ok->if(ok)refresh++}})
            "account" -> AccountDialog(db.members(),dark,onDismiss={dialog=""},onSave={a,n->db.addAccount(a,n).also{if(it)refresh++}})
            "budget" -> BudgetDialog(dark,onDismiss={dialog=""},onSave={c,v->db.saveBudget(c,v).also{if(it)refresh++}})
            "debt" -> DebtDialog(dark,onDismiss={dialog=""},onSave={title,total,count,due,note->db.addDebt(title,total,count,due,note).also{if(it)refresh++}})
            "receivable" -> ReceivableDialog(dark,onDismiss={dialog=""},onSave={person,amount,due,note->db.addReceivable(person,amount,due,note).also{if(it)refresh++}})
            "recurringBill" -> RecurringBillDialog(dark,onDismiss={dialog=""},onSave={title,category,amount,due,frequency,note->db.addRecurringBill(title,category,amount,due,frequency,note).also{if(it)refresh++}})
            "payRecurringBill" -> selectedRecurringBill?.let { bill -> PayRecurringBillDialog(bill,accounts,dark,onDismiss={dialog="";selectedRecurringBill=null},onSave={accountId->db.payRecurringBill(bill.id,accountId).also{if(it)refresh++}}) }
            "deleteRecurringBill" -> selectedRecurringBill?.let { bill -> AlertDialog(onDismissRequest={dialog="";selectedRecurringBill=null},title={Text("حذف هزینه دوره‌ای")},text={Text("«${bill.title}» از فهرست هزینه‌های دوره‌ای حذف شود؟")},confirmButton={TextButton(onClick={db.deleteRecurringBill(bill.id);refresh++;dialog="";selectedRecurringBill=null}){Text("حذف",color=pink)}},dismissButton={TextButton(onClick={dialog="";selectedRecurringBill=null}){Text("انصراف")}}) }
            "markReceived" -> selectedReceivable?.let { receivable -> AlertDialog(onDismissRequest={dialog="";selectedReceivable=null},title={Text("ثبت دریافت طلب")},text={Text("مبلغ ${money(receivable.amount)} از ${receivable.person} دریافت شده است؟")},confirmButton={TextButton(onClick={if(db.markReceivableReceived(receivable.id))refresh++;dialog="";selectedReceivable=null}){Text("ثبت دریافت")}},dismissButton={TextButton(onClick={dialog="";selectedReceivable=null}){Text("انصراف")}}) }
            "deleteReceivable" -> selectedReceivable?.let { receivable -> AlertDialog(onDismissRequest={dialog="";selectedReceivable=null},title={Text("حذف طلب")},text={Text("طلب ثبت‌شده از ${receivable.person} حذف شود؟")},confirmButton={TextButton(onClick={db.deleteReceivable(receivable.id);refresh++;dialog="";selectedReceivable=null}){Text("حذف",color=pink)}},dismissButton={TextButton(onClick={dialog="";selectedReceivable=null}){Text("انصراف")}}) }
            "payDebt" -> selectedInstallment?.let { installment -> PayDebtDialog(installment,accounts,dark,onDismiss={dialog="";selectedInstallment=null},onSave={accountId->db.payDebtInstallment(installment.id,accountId).also{if(it)refresh++}}) }
            "deleteDebt" -> selectedDebt?.let { debt -> AlertDialog(onDismissRequest={dialog="";selectedDebt=null},containerColor=if(dark) panel else Color(0xFFF8F9F5),title={Text("حذف بدهی",color=if(dark)Color.White else Color(0xFF303039))},text={GlassDialogBody(dark=dark){Text(if(debt.paidAmount>0)"برای این بدهی پرداخت ثبت شده و حذف آن مجاز نیست." else "بدهی «${debt.title}» و اقساط آن حذف شود؟",color=if(dark)Color.White else Color(0xFF303039))}},confirmButton={TextButton(onClick={if(db.deleteDebt(debt.id)){refresh++;dialog="";selectedDebt=null}else Toast.makeText(context,"بدهی دارای پرداخت قبلی قابل حذف نیست.",Toast.LENGTH_LONG).show()}){Text("حذف",color=pink)}},dismissButton={TextButton(onClick={dialog="";selectedDebt=null}){Text("انصراف")}}) }
            "goal" -> GoalDialog(dark,onDismiss={dialog=""},onSave={t,v->db.addGoal(t,v).also{if(it)refresh++}})
            "contribute" -> selectedGoal?.let { goal -> GoalContributionDialog(goal,dark,onDismiss={dialog="";selectedGoal=null},onSave={amount->db.contributeGoal(goal.id,amount).also{if(it)refresh++}}) }
            "deleteGoal" -> selectedGoal?.let { goal -> AlertDialog(onDismissRequest={dialog="";selectedGoal=null},containerColor=if(dark) panel else Color(0xFFF8F9F5),title={Text("حذف هدف",color=if(dark)Color.White else Color(0xFF303039))},text={GlassDialogBody(dark=dark) { Text("هدف «"+goal.title+"» حذف شود؟",color=if(dark)Color.White else Color(0xFF303039)) }},confirmButton={TextButton(onClick={db.deleteGoal(goal.id);refresh++;dialog="";selectedGoal=null}){Text("حذف",color=pink)}},dismissButton={TextButton(onClick={dialog="";selectedGoal=null}){Text("انصراف")}}) }
            "deleteBudget" -> selectedBudget?.let { budget -> AlertDialog(onDismissRequest={dialog="";selectedBudget=null},containerColor=if(dark) panel else Color(0xFFF8F9F5),title={Text("حذف بودجه",color=if(dark)Color.White else Color(0xFF303039))},text={GlassDialogBody(dark=dark) { Text("بودجه «"+budget.category+"» حذف شود؟",color=if(dark)Color.White else Color(0xFF303039)) }},confirmButton={TextButton(onClick={db.deleteBudget(budget.id);refresh++;dialog="";selectedBudget=null}){Text("حذف",color=pink)}},dismissButton={TextButton(onClick={dialog="";selectedBudget=null}){Text("انصراف")}}) }
            "cheque" -> ChequeDialog(dark,onDismiss={dialog=""},onSave={type,number,bank,person,amount,due,note->db.addCheque(type,number,bank,person,amount,due,note).also{if(it)refresh++}})
            "deleteCheque" -> selectedCheque?.let { cheque -> AlertDialog(onDismissRequest={dialog="";selectedCheque=null},title={Text("حذف چک")},text={Text("چک شماره ${cheque.number} حذف شود؟")},confirmButton={TextButton(onClick={db.deleteCheque(cheque.id);refresh++;dialog="";selectedCheque=null}){Text("حذف",color=pink)}},dismissButton={TextButton(onClick={dialog="";selectedCheque=null}){Text("انصراف")}}) }
        }
        smsSuggestion?.let { suggestion ->
            BankSmsReviewDialog(suggestion,accounts,db.categories(suggestion.type),dark,
                onDismiss={clearBankSmsSuggestion(context);smsSuggestion=null},
                onSave={accountId,category ->
                    val saved=db.addTransaction(accountId,suggestion.type,category,suggestion.amount,"ثبت از پیامک بانکی: ${suggestion.body.take(180)}")
                    if(saved){refresh++;clearBankSmsSuggestion(context);smsSuggestion=null}
                    saved
                })
        }
    }
}

@Composable private fun ChequesPage(p:PaddingValues,cheques:List<FamilyCheque>,fg:Color,sub:Color,dark:Boolean,onAdd:()->Unit,onStatus:(FamilyCheque,String)->Unit,onDelete:(FamilyCheque)->Unit) {
    val pending=cheques.filter{it.status=="در انتظار"}
    val totalIn=pending.filter{it.type=="دریافتی"}.sumOf{it.amount}
    val totalOut=pending.filter{it.type=="پرداختی"}.sumOf{it.amount}
    LazyColumn(Modifier.fillMaxSize().padding(p).background(Brush.linearGradient(if(dark) listOf(Color(0xFF0B0D22),Color(0xFF20153D),Color(0xFF101A35)) else listOf(Color(0xFF13B4CF),Color(0xFFE9FBFF),Color(0xFFFFB45E)))).padding(horizontal=16.dp),contentPadding=PaddingValues(top=18.dp,bottom=30.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { ScreenHeading("مدیریت چک‌ها","چک‌های دریافتی و پرداختی و پیگیری سررسید",fg,sub,dark) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){MetricCard("چک دریافتی",totalIn,aqua,sub,dark,Modifier.weight(1f));MetricCard("چک پرداختی",totalOut,pink,sub,dark,Modifier.weight(1f))} }
        item { Button(onClick=onAdd,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=violet)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(7.dp));Text("ثبت چک جدید")} }
        if(cheques.isEmpty()) item { GlassCard(dark=dark){Text("هنوز چکی ثبت نشده است.",color=fg,fontWeight=FontWeight.SemiBold);Text("چک‌های دریافتی و پرداختی را اینجا ثبت و پیگیری کنید.",color=sub,fontSize=12.sp)} }
        items(cheques,key={"cheque${it.id}"}) { cheque ->
            GlassCard(dark=dark) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)){Text(if(cheque.type=="دریافتی")"چک دریافتی" else "چک پرداختی",color=if(cheque.type=="دریافتی")aqua else pink,fontSize=11.sp,fontWeight=FontWeight.Bold);Text("شماره ${cheque.number}",color=fg,fontSize=16.sp,fontWeight=FontWeight.Bold);Text("${cheque.bank} · ${cheque.person}",color=sub,fontSize=11.sp)}
                    Text(cheque.status,color=if(cheque.status=="برگشت خورد")pink else if(cheque.status=="در انتظار")Color(0xFFFFC66D) else aqua,fontSize=11.sp,fontWeight=FontWeight.Bold)
                }
                Text(money(cheque.amount),color=fg,fontSize=19.sp,fontWeight=FontWeight.Bold)
                Text("سررسید: ${jalali(cheque.dueAt)}",color=if(cheque.dueAt<System.currentTimeMillis()&&cheque.status=="در انتظار")pink else sub,fontSize=11.sp)
                if(cheque.note.isNotBlank())Text(cheque.note,color=sub,fontSize=10.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(cheque.status=="در انتظار") (if(cheque.type=="دریافتی")listOf("وصول شد","برگشت خورد","باطل شد")else listOf("پاس شد","برگشت خورد","باطل شد")).forEach{status->TextButton(onClick={onStatus(cheque,status)}){Text(status,fontSize=10.sp)}}
                    else TextButton(onClick={onStatus(cheque,"در انتظار")}){Text("بازگردانی وضعیت",fontSize=10.sp)}
                    TextButton(onClick={onDelete(cheque)}){Text("حذف",color=pink,fontSize=10.sp)}
                }
            }
        }
    }
}

@Composable private fun ChequeDialog(dark:Boolean,onDismiss:()->Unit,onSave:(String,String,String,String,Long,Long,String)->Boolean) {
    val context=LocalContext.current
    var type by remember{mutableStateOf("دریافتی")}
    var number by remember{mutableStateOf("")};var bank by remember{mutableStateOf("")};var person by remember{mutableStateOf("")}
    var amount by remember{mutableStateOf("")};var note by remember{mutableStateOf("")}
    var due by remember{mutableLongStateOf(System.currentTimeMillis())};var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark)panel else Color(0xFFF8F9F5),title={Text("ثبت چک جدید",color=if(dark)Color.White else Color(0xFF303039))},text={GlassDialogBody(dark=dark){
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("دریافتی","پرداختی").forEach{FilterChip(selected=type==it,onClick={type=it},label={Text("چک $it")})}}
            OutText(number,{number=it;error=false},"شماره چک");OutText(bank,{bank=it;error=false},"نام بانک")
            OutText(person,{person=it;error=false},if(type=="دریافتی")"صادرکننده چک" else "ذی‌نفع چک")
            OutText(amount,{amount=formatAmount(it);error=false},"مبلغ به تومان",numeric=true)
            OutlinedButton(onClick={val c=java.util.Calendar.getInstance().apply{timeInMillis=due};DatePickerDialog(context,{_,y,m,d->due=java.util.Calendar.getInstance().apply{set(y,m,d,12,0,0);set(java.util.Calendar.MILLISECOND,0)}.timeInMillis},c.get(java.util.Calendar.YEAR),c.get(java.util.Calendar.MONTH),c.get(java.util.Calendar.DAY_OF_MONTH)).show()},modifier=Modifier.fillMaxWidth()){Text("تاریخ سررسید: ${jalali(due)}")}
            OutText(note,{note=it},"توضیحات (اختیاری)");if(error)Text("موارد ضروری و مبلغ را بررسی کنید.",color=pink,fontSize=11.sp)
        }
    }},confirmButton={TextButton(onClick={val v=parseAmount(amount);if(v!=null&&onSave(type,number,bank,person,v,due,note))onDismiss()else error=true}){Text("ذخیره چک")}},dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}

@Composable private fun BankSmsReviewDialog(
    suggestion:BankSmsSuggestion, accounts:List<FinanceAccount>, categories:List<String>, dark:Boolean,
    onDismiss:()->Unit, onSave:(Long,String)->Boolean
) {
    var accountId by remember { mutableLongStateOf(accounts.firstOrNull()?.id ?: 0L) }
    var category by remember { mutableStateOf(suggestion.category) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest=onDismiss,
        containerColor=if(dark)panel else Color(0xFFF8F9F5),
        title={Text("بررسی پیامک بانکی",color=if(dark)Color.White else Color(0xFF303039))},
        text={GlassDialogBody(dark=dark) {
            Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Text("نوع تراکنش: ${suggestion.type}",color=if(suggestion.type=="درآمد")aqua else pink,fontWeight=FontWeight.Bold)
                Text("مبلغ شناسایی‌شده: ${money(suggestion.amount)}",color=if(dark)Color.White else Color(0xFF303039),fontWeight=FontWeight.Bold)
                Text("دسته‌بندی پیشنهادی",color=muted)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    categories.forEach { item -> FilterChip(selected=category==item,onClick={category=item},label={Text(item)}) }
                }
                OutText(category,{category=it;error=false},"دسته‌بندی نهایی")
                Text("حساب مقصد/مبدأ:",color=muted)
                accounts.forEach { a -> FilterChip(selected=accountId==a.id,onClick={accountId=a.id},label={Text("${a.memberName} · ${a.name}")}) }
                Text("متن پیامک: ${suggestion.body.take(220)}",color=Color.LightGray,fontSize=11.sp)
                Text("هیچ مبلغی تا زمان تأیید شما در حساب ثبت نمی‌شود.",color=aqua,fontSize=11.sp)
                if(error) Text("حساب یا دسته‌بندی را بررسی کنید. برای هزینه، موجودی حساب باید کافی باشد.",color=pink)
            }
        }},
        confirmButton={TextButton(onClick={if(category.isNotBlank() && accounts.any{it.id==accountId} && onSave(accountId,category)) onDismiss() else error=true}){Text("تأیید و ثبت")}},
        dismissButton={TextButton(onClick=onDismiss){Text("رد کردن")}}
    )
}

@Composable private fun GlassCard(modifier:Modifier=Modifier,dark:Boolean=true,content:@Composable ColumnScope.()->Unit){
    Surface(modifier=modifier.fillMaxWidth(),shape=RoundedCornerShape(23.dp),color=if(dark)Color(0xC9252447) else Color(0xDDF8FFFF),
        border=BorderStroke(1.dp,if(dark)Color.White.copy(alpha=.18f) else Color.White.copy(alpha=.92f)),shadowElevation=if(dark)12.dp else 8.dp) {
        Column(Modifier.fillMaxWidth().padding(17.dp),content=content)
    }
}
@Composable private fun FinanceGauge(title:String,value:Long,maximum:Long,color:Color,fg:Color,sub:Color,dark:Boolean){
    val progress=if(maximum>0L)(value.toFloat()/maximum.toFloat()).coerceIn(0f,1f) else 0f
    Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.width(145.dp)){
        Box(contentAlignment=Alignment.Center,modifier=Modifier.size(112.dp)){
            Canvas(Modifier.fillMaxSize()){
                val stroke=10.dp.toPx()
                drawArc(if(dark)Color.White.copy(alpha=.10f) else Color(0xFFEDEAF0),135f,270f,false,style=Stroke(stroke,cap=StrokeCap.Round))
                drawArc(color,135f,270f*progress,false,style=Stroke(stroke,cap=StrokeCap.Round))
            }
            Column(horizontalAlignment=Alignment.CenterHorizontally){
                Text(if(title=="درآمد")"دریافت" else "پرداخت",color=sub,fontSize=10.sp)
                Text(if(progress==0f)"۰٪" else "${(progress*100).toInt()}٪",color=fg,fontWeight=FontWeight.Bold,fontSize=19.sp)
            }
        }
        Spacer(Modifier.height(5.dp));Text(title,color=fg,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
        Text(money(value),color=color,fontSize=12.sp,fontWeight=FontWeight.Bold)
    }
}
@Composable private fun Dashboard(p:PaddingValues,balance:Long,income:Long,expense:Long,txs:List<FinanceTransaction>,cats:List<Pair<String,Long>>,fg:Color,sub:Color,dark:Boolean,onAdd:()->Unit,onTransfer:()->Unit){
    val maxValue=maxOf(income,expense,1L)
    val categoryColors=listOf(Color(0xFF6C43A0),Color(0xFF2EAD76),Color(0xFFF1A34A),Color(0xFFE35D68),Color(0xFF4C91CE),Color(0xFFB18BCB))
    LazyColumn(Modifier.fillMaxSize().padding(p).background(Brush.linearGradient(if(dark) listOf(Color(0xFF0B0D22),Color(0xFF24153F),Color(0xFF101A35)) else listOf(Color(0xFF09AFCB),Color(0xFFDFFAFF),Color(0xFFFFA34D)))).padding(horizontal=16.dp),
        contentPadding=PaddingValues(top=16.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(15.dp)){
        item {
            Surface(shape=RoundedCornerShape(28.dp),color=Color.Transparent,border=BorderStroke(1.dp,Color.White.copy(alpha=.35f)),shadowElevation=15.dp) {
                Column(Modifier.fillMaxWidth().background(Brush.linearGradient(if(dark) listOf(Color(0xFF43205E),Color(0xFF1D315A),Color(0xFF161B3A)) else listOf(Color(0xFF087E9D),Color(0xFF20B9CF),Color(0xFFFF963D)))).padding(21.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("خوش آمدید",color=Color.White.copy(alpha=.84f),fontSize=12.sp)
                            Text("نمای کلی حساب شما",color=Color.White,fontSize=19.sp,fontWeight=FontWeight.Bold)
                        }
                        Surface(shape=RoundedCornerShape(16.dp),color=Color.White.copy(alpha=.18f),border=BorderStroke(1.dp,Color.White.copy(alpha=.28f))) {
                            Icon(Icons.Default.AccountBalanceWallet,null,tint=Color.White,modifier=Modifier.padding(13.dp).size(27.dp))
                        }
                    }
                    Spacer(Modifier.height(25.dp))
                    Text("موجودی کل حساب‌ها",color=Color.White.copy(alpha=.84f),fontSize=12.sp)
                    Text(money(balance),color=Color.White,fontSize=27.sp,fontWeight=FontWeight.ExtraBold)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                        Surface(Modifier.weight(1f).clickable(onClick=onAdd),shape=RoundedCornerShape(16.dp),color=Color.White.copy(alpha=.20f),border=BorderStroke(1.dp,Color.White.copy(alpha=.30f))) {
                            Row(Modifier.padding(vertical=12.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Add,null,tint=Color.White);Spacer(Modifier.width(5.dp));Text("ثبت تراکنش",color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=12.sp)}
                        }
                        Surface(Modifier.weight(1f).clickable(onClick=onTransfer),shape=RoundedCornerShape(16.dp),color=Color.White.copy(alpha=.20f),border=BorderStroke(1.dp,Color.White.copy(alpha=.30f))) {
                            Row(Modifier.padding(vertical=12.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.SwapHoriz,null,tint=Color.White);Spacer(Modifier.width(5.dp));Text("انتقال وجه",color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=12.sp)}
                        }
                    }
                }
            }
        }
        item { GlassCard(dark=dark){
            Row(verticalAlignment=Alignment.CenterVertically){Text("گزارش هزینه و درآمد",color=fg,fontWeight=FontWeight.Bold,fontSize=15.sp);Spacer(Modifier.weight(1f));Text("ماه جاری",color=sub,fontSize=11.sp)}
            Spacer(Modifier.height(13.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically){
                FinanceGauge("درآمد",income,maxValue,Color(0xFF36D6A0),fg,sub,dark)
                FinanceGauge("هزینه",expense,maxValue,Color(0xFFFF4D9A),fg,sub,dark)
            }
        }}
        item { Row(verticalAlignment=Alignment.CenterVertically){Text("هزینه‌ها بر اساس دسته‌بندی",color=fg,fontSize=15.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));Text("تحلیل ماهانه",color=if(dark)Color(0xFFE0B7FF) else Color(0xFF087F9B),fontSize=11.sp)} }
        if(cats.isEmpty()) item { GlassCard(dark=dark){Text("هنوز هزینه‌ای ثبت نشده است.",color=sub,fontSize=12.sp)} }
        else items(cats.take(6),key={it.first}){(name,value)->
            val index=cats.indexOfFirst{it.first==name}.coerceAtLeast(0);val tint=categoryColors[index%categoryColors.size]
            GlassCard(dark=dark){Row(verticalAlignment=Alignment.CenterVertically){
                Surface(shape=RoundedCornerShape(12.dp),color=tint.copy(alpha=.16f)){Icon(Icons.Default.Category,null,tint=tint,modifier=Modifier.padding(10.dp).size(19.dp))}
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)){Text(name,color=fg,fontWeight=FontWeight.SemiBold,fontSize=12.sp);Spacer(Modifier.height(6.dp));LinearProgressIndicator(progress={if(expense>0)value.toFloat()/expense else 0f},modifier=Modifier.fillMaxWidth().height(6.dp),color=tint,trackColor=if(dark)Color.White.copy(alpha=.12f) else Color(0xFFB9EAF0))}
                Spacer(Modifier.width(10.dp));Text(money(value),color=fg,fontSize=11.sp,fontWeight=FontWeight.Bold)
            }}
        }
        item { Row(verticalAlignment=Alignment.CenterVertically){Text("آخرین تراکنش‌ها",color=fg,fontSize=15.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));Text("تراکنش‌های اخیر",color=if(dark)Color(0xFFE0B7FF) else Color(0xFF087F9B),fontSize=11.sp)} }
        if(txs.isEmpty()) item { GlassCard(dark=dark){Text("تراکنشی برای نمایش وجود ندارد.",color=sub,fontSize=12.sp)} }
        else items(txs.take(5),key={it.id}){TxRow(it,fg,sub,dark)}
    }
}
@Composable private fun MetricCard(title:String,value:Long,tint:Color,sub:Color,dark:Boolean,mod:Modifier){GlassCard(mod,dark){Text(title,color=sub,fontSize=12.sp);Spacer(Modifier.height(8.dp));Text(money(value),color=tint,fontSize=16.sp,fontWeight=FontWeight.Bold)}}
@Composable private fun CategoryDonutChart(cats:List<Pair<String,Long>>,fg:Color,sub:Color,dark:Boolean) {
    val palette = listOf(Color(0xFF8B7CFF),Color(0xFF53D7D0),Color(0xFFFF78B7),Color(0xFFFFC66D),Color(0xFF67A9FF),Color(0xFFB78BFA))
    val positive = cats.filter { it.second > 0L }.take(6)
    val total = positive.sumOf { it.second }
    if (positive.isEmpty() || total <= 0L) return
    GlassCard(dark=dark) {
        Text("ترکیب هزینه‌های خانواده",color=fg,fontSize=16.sp,fontWeight=FontWeight.Bold)
        Text("سهم هر دسته از مجموع هزینه‌های ثبت‌شده",color=sub,fontSize=11.sp)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(150.dp),contentAlignment=Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 22.dp.toPx()
                    var start = -90f
                    positive.forEachIndexed { index, item ->
                        val sweep = item.second.toFloat() / total.toFloat() * 360f
                        drawArc(color=palette[index % palette.size],startAngle=start,sweepAngle=(sweep-2.2f).coerceAtLeast(0f),useCenter=false,style=Stroke(width=stroke,cap=StrokeCap.Round))
                        start += sweep
                    }
                }
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("مجموع هزینه",color=sub,fontSize=10.sp)
                    Text(money(total).replace(" تومان",""),color=fg,fontSize=15.sp,fontWeight=FontWeight.Bold)
                    Text("تومان",color=sub,fontSize=9.sp)
                }
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                positive.forEachIndexed { index,item ->
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(palette[index % palette.size],RoundedCornerShape(50)))
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.first,color=fg,fontSize=10.sp,maxLines=1)
                            Text("${(item.second.toDouble()*100.0/total).toInt()}٪",color=sub,fontSize=9.sp)
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun TxRow(t:FinanceTransaction,fg:Color,sub:Color,dark:Boolean,onEdit:(()->Unit)?=null,onDelete:(()->Unit)?=null){
    GlassCard(dark=dark){Row(verticalAlignment=Alignment.CenterVertically){Surface(shape=RoundedCornerShape(13.dp),color=(if(t.type=="درآمد") aqua else pink).copy(alpha=.15f)){Icon(if(t.type=="درآمد") Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,null,tint=if(t.type=="درآمد") aqua else pink,modifier=Modifier.padding(10.dp))};Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(t.category,color=fg,fontWeight=FontWeight.SemiBold);Text("${t.memberName} · ${t.accountName} · ${jalali(t.createdAt)}",color=sub,fontSize=10.sp);if(t.note.isNotBlank())Text(t.note,color=sub,fontSize=11.sp)};Text((if(t.type=="درآمد") "+" else "−")+money(t.amount),color=if(t.type=="درآمد") aqua else pink,fontSize=11.sp,fontWeight=FontWeight.Bold)};if(onEdit!=null&&onDelete!=null){if(t.category=="انتقال وجه"||t.category=="دریافت انتقال"){Text("برای حفظ تراز حساب‌ها، ویرایش و حذف انتقال غیرفعال است.",color=sub,fontSize=10.sp)}else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick=onEdit){Text("ویرایش")};TextButton(onClick=onDelete){Text("حذف",color=pink)}}}}
}
@Composable private fun ScreenHeading(title:String,subtitle:String,fg:Color,sub:Color,dark:Boolean){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
            Text(title,color=fg,fontSize=23.sp,fontWeight=FontWeight.Bold)
            Text(subtitle,color=sub,fontSize=11.sp)
        }
        Surface(shape=RoundedCornerShape(14.dp),color=if(dark)Color.White.copy(alpha=.09f) else violet.copy(alpha=.09f),border=BorderStroke(1.dp,if(dark)Color.White.copy(alpha=.13f) else violet.copy(alpha=.12f))){
            Text("مالی",modifier=Modifier.padding(horizontal=10.dp,vertical=7.dp),color=if(dark)Color(0xFFE1C7F0) else violet,fontSize=9.sp,fontWeight=FontWeight.Bold)
        }
    }
}
@Composable private fun TransactionsPage(p:PaddingValues,txs:List<FinanceTransaction>,fg:Color,sub:Color,dark:Boolean,onEdit:(FinanceTransaction)->Unit,onDelete:(FinanceTransaction)->Unit){
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("همه") }
    val filtered = txs.filter { t ->
        val matchesQuery = query.isBlank() || listOf(t.category,t.note,t.accountName,t.memberName,money(t.amount)).any { it.contains(query.trim(),ignoreCase=true) }
        val matchesType = filter=="همه" || t.type==filter
        matchesQuery && matchesType
    }
    Column(Modifier.fillMaxSize().padding(p).background(if(dark)midnight else Color(0xFFF7F7F9)).padding(16.dp)){
        ScreenHeading("تراکنش‌ها","جست‌وجو و مدیریت گردش مالی",fg,sub,dark)
        Spacer(Modifier.height(10.dp))
        GlassCard(dark=dark) {
            OutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("جست‌وجوی عنوان، حساب یا مبلغ")},leadingIcon={Icon(Icons.Default.Search,null)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Text),colors=OutlinedTextFieldDefaults.colors(
                focusedBorderColor=if(dark) Color(0xFFC4BDFF) else violet,
                unfocusedBorderColor=if(dark) Color.White.copy(alpha=.22f) else Color(0xFFD6DCEF),
                focusedTextColor=fg,unfocusedTextColor=fg,
                focusedLabelColor=if(dark) Color(0xFFC4BDFF) else violet,
                cursorColor=if(dark) Color(0xFFC4BDFF) else violet
            ))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){
            listOf("همه","درآمد","هزینه").forEach { type -> FilterChip(selected=filter==type,onClick={filter=type},label={Text(type)}) }
        }
        Spacer(Modifier.height(8.dp))
        Text("${filtered.size} تراکنش",color=sub,fontSize=12.sp)
        Spacer(Modifier.height(8.dp))
        if(filtered.isEmpty()) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(if(txs.isEmpty())"تراکنشی برای نمایش وجود ندارد." else "نتیجه‌ای برای جست‌وجوی شما پیدا نشد.",color=sub)}
        else LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp)){items(filtered,key={it.id}){TxRow(it,fg,sub,dark,{onEdit(it)},{onDelete(it)})}}
    }
}
@Composable private fun AccountsPage(p:PaddingValues,accounts:List<FinanceAccount>,fg:Color,sub:Color,dark:Boolean,onMember:()->Unit,onAccount:()->Unit,onTransfer:()->Unit){
    LazyColumn(Modifier.fillMaxSize().padding(p).background(if(dark)midnight else Color(0xFFF7F7F9)).padding(horizontal=16.dp),contentPadding=PaddingValues(top=16.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(11.dp)){
        item { ScreenHeading("حساب‌های خانواده","مدیریت موجودی و حساب اعضا",fg,sub,dark) }
        item { GlassCard(dark=dark) {
            Text("مدیریت حساب‌ها",color=fg,fontWeight=FontWeight.Bold)
            Text("برای هر عضو می‌توانید چند حساب مستقل داشته باشید.",color=sub,fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Button(onClick=onMember,modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=violet)){Text("عضو جدید")}
                OutlinedButton(onClick=onAccount,modifier=Modifier.weight(1f)){Text("حساب جدید")}
            }
            OutlinedButton(onClick=onTransfer,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.SwapHoriz,null);Spacer(Modifier.width(6.dp));Text("انتقال وجه بین حساب‌ها")}
        } }
        if(accounts.isEmpty()) item { GlassCard(dark=dark){Text("هنوز حسابی ایجاد نشده است.",color=sub)} }
        items(accounts,key={it.id}) { a -> GlassCard(dark=dark) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Surface(shape=RoundedCornerShape(14.dp),color=violet.copy(alpha=.16f)){Icon(Icons.Default.AccountBalanceWallet,null,tint=if(dark)Color(0xFFC4BDFF) else violet,modifier=Modifier.padding(11.dp))}
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(a.memberName,color=sub,fontSize=12.sp);Text(a.name,color=fg,fontWeight=FontWeight.Bold) }
            }
            Text(money(a.balance),color=if(a.balance<0)pink else aqua,fontSize=21.sp,fontWeight=FontWeight.Bold)
        } }
    }
}
@Composable private fun ReportsPage(p:PaddingValues,income:Long,expense:Long,cats:List<Pair<String,Long>>,budgets:List<Budget>,fg:Color,sub:Color,dark:Boolean,onBudget:()->Unit,onDeleteBudget:(Budget)->Unit){
    LazyColumn(Modifier.fillMaxSize().padding(p).background(if(dark)midnight else Color(0xFFF7F7F9)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{ScreenHeading("گزارش مالی","نمای کلی درآمد، هزینه و بودجه",fg,sub,dark)}
        item{CategoryDonutChart(cats,fg,sub,dark)}
        item{GlassCard(dark=dark){Text("درآمد",color=sub);Text(money(income),color=aqua,fontSize=20.sp,fontWeight=FontWeight.Bold)}}
        item{GlassCard(dark=dark){Text("هزینه",color=sub);Text(money(expense),color=pink,fontSize=20.sp,fontWeight=FontWeight.Bold)}}
        item{GlassCard(dark=dark){Text("مانده خالص",color=sub);Text(money(income-expense),color=fg,fontSize=22.sp,fontWeight=FontWeight.Bold)}}
        item{Row(verticalAlignment=Alignment.CenterVertically){Text("هزینه بر اساس دسته",color=fg,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));TextButton(onClick=onBudget){Text("بودجه جدید")}}}
        if(cats.isEmpty()) item{Text("هنوز هزینه‌ای ثبت نشده است.",color=sub)} else items(cats){(name,value)->GlassCard(dark=dark){Row{Text(name,color=fg);Spacer(Modifier.weight(1f));Text(money(value),color=pink,fontWeight=FontWeight.Bold)};LinearProgressIndicator(progress={if(expense>0)value.toFloat()/expense else 0f},modifier=Modifier.fillMaxWidth().padding(top=8.dp))}}
        item{Text("بودجه‌بندی ماهانه",color=fg,fontWeight=FontWeight.Bold)}
        if(budgets.isEmpty()) item{Text("بودجه‌ای تعریف نشده است.",color=sub)} else items(budgets,key={it.id}){b->
            val usage = budgetUsagePercent(b.spent,b.limitAmount)
            val overage = budgetOverage(b.spent,b.limitAmount)
            val budgetColor = if(overage>0L) pink else if(usage>=80) Color(0xFFFFC56D) else aqua
            GlassCard(dark=dark){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){
                        Text(b.category,color=fg,fontWeight=FontWeight.Bold)
                        Text("مصرف‌شده: "+money(b.spent)+" از "+money(b.limitAmount),color=sub,fontSize=12.sp)
                    }
                    IconButton(onClick={onDeleteBudget(b)}){Icon(Icons.Default.Delete,null,tint=pink)}
                }
                Text(
                    if(overage>0L) "هشدار: ${money(overage)} بیشتر از سقف بودجه"
                    else "باقی‌مانده: ${money(budgetRemaining(b.spent,b.limitAmount))}",
                    color=budgetColor,fontSize=12.sp,fontWeight=FontWeight.SemiBold
                )
                Text("میزان مصرف: $usage٪",color=budgetColor,fontSize=11.sp)
                LinearProgressIndicator(
                    progress={(usage/100f).coerceIn(0f,1f)},
                    modifier=Modifier.fillMaxWidth().padding(top=8.dp),
                    color=budgetColor
                )
            }
        }
    }
}
@Composable private fun MorePage(p:PaddingValues,goals:List<SavingGoal>,fg:Color,sub:Color,dark:Boolean,onAccounts:()->Unit,onDebts:()->Unit,onCheques:()->Unit,onGoal:()->Unit,onContribute:(SavingGoal)->Unit,onDeleteGoal:(SavingGoal)->Unit,onTheme:()->Unit,onExport:()->Unit,onImport:()->Unit,onExportExcel:()->Unit,onExportPdf:()->Unit){
    LazyColumn(Modifier.fillMaxSize().padding(p).background(if(dark)midnight else Color(0xFFF7F7F9)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{ScreenHeading("امکانات بیشتر","مدیریت بخش‌های مالی و تنظیمات",fg,sub,dark)}
        item{Text("بخش‌های اصلی",color=fg,fontWeight=FontWeight.Bold,fontSize=15.sp)}
        item{Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){
            GlassCard(Modifier.weight(1f).clickable(onClick=onAccounts),dark){Icon(Icons.Default.AccountBalance,contentDescription=null,tint=violet);Spacer(Modifier.height(7.dp));Text("حساب‌ها و اعضا",color=fg,fontWeight=FontWeight.SemiBold,fontSize=12.sp)}
            GlassCard(Modifier.weight(1f).clickable(onClick=onDebts),dark){Icon(Icons.Default.EventAvailable,contentDescription=null,tint=violet);Spacer(Modifier.height(7.dp));Text("وام و اقساط",color=fg,fontWeight=FontWeight.SemiBold,fontSize=12.sp)}
            GlassCard(Modifier.weight(1f).clickable(onClick=onCheques),dark){Icon(Icons.Default.Description,contentDescription=null,tint=violet);Spacer(Modifier.height(7.dp));Text("مدیریت چک",color=fg,fontWeight=FontWeight.SemiBold,fontSize=12.sp)}
        }}
        item{GlassCard(dark=dark){Text("اعضای خانواده، حساب‌ها، وام‌ها، چک‌ها و سایر ابزارهای مالی از این بخش در دسترس هستند.",color=sub,fontSize=11.sp)}}
        item{GlassCard(dark=dark){Text("تنظیمات ظاهری",color=fg,fontWeight=FontWeight.Bold,fontSize=15.sp);Text("پوسته دلخواه برنامه را انتخاب کنید.",color=sub,fontSize=11.sp);Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){if(dark){OutlinedButton(onClick=onTheme,modifier=Modifier.weight(1f)){Icon(Icons.Default.WbSunny,null);Spacer(Modifier.width(6.dp));Text("حالت روشن")};Button(onClick={},modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=violet)){Icon(Icons.Default.DarkMode,null);Spacer(Modifier.width(6.dp));Text("حالت تیره")}}else{Button(onClick={},modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF078DA8))){Icon(Icons.Default.WbSunny,null);Spacer(Modifier.width(6.dp));Text("حالت روشن")};OutlinedButton(onClick=onTheme,modifier=Modifier.weight(1f)){Icon(Icons.Default.DarkMode,null);Spacer(Modifier.width(6.dp));Text("حالت تیره")}}}}}
        item{GlassCard(dark=dark){Text("پشتیبان‌گیری و بازیابی",color=fg,fontWeight=FontWeight.Bold);Text("فایل پشتیبان را در حافظه گوشی ذخیره یا بازیابی کنید.",color=sub,fontSize=12.sp);Row{TextButton(onClick=onExport){Text("تهیه پشتیبان")};TextButton(onClick=onImport){Text("بازیابی")}}}}
        item{GlassCard(dark=dark){Text("گزارش و خروجی",color=fg,fontWeight=FontWeight.Bold);Text("گزارش تراکنش‌ها را برای بایگانی یا بررسی در رایانه ذخیره کنید.",color=sub,fontSize=12.sp);Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){TextButton(onClick=onExportExcel){Icon(Icons.Default.TableChart,null);Spacer(Modifier.width(4.dp));Text("خروجی Excel")};TextButton(onClick=onExportPdf){Icon(Icons.Default.PictureAsPdf,null);Spacer(Modifier.width(4.dp));Text("خروجی PDF")}}}}
        item{Row(verticalAlignment=Alignment.CenterVertically){Text("اهداف پس‌انداز",color=fg,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));TextButton(onClick=onGoal){Text("هدف جدید")}}}
        if(goals.isEmpty()) item{Text("هنوز هدفی ثبت نشده است.",color=sub)} else items(goals,key={it.id}){g->
            GlassCard(dark=dark){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(g.title,color=fg,fontWeight=FontWeight.Bold);Text(money(g.saved)+" از "+money(g.target),color=sub)};IconButton(onClick={onDeleteGoal(g)}){Icon(Icons.Default.Delete,null,tint=pink)}};LinearProgressIndicator(progress={(g.saved.toFloat()/g.target).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp));TextButton(onClick={onContribute(g)},enabled=g.saved<g.target){Text(if(g.saved<g.target)"افزودن مبلغ به پس‌انداز" else "هدف تکمیل شده است")}}
        }
    }
}

@Composable private fun DebtsPage(
    p:PaddingValues, debts:List<FamilyDebt>, installmentsByDebt:Map<Long,List<DebtInstallment>>,
    receivables:List<FamilyReceivable>, recurringBills:List<RecurringBill>, overdue:List<DebtInstallment>, upcoming:List<DebtInstallment>,
    totalDebt:Long, totalReceivables:Long, fg:Color, sub:Color, dark:Boolean,
    onPay:(DebtInstallment)->Unit, onDelete:(FamilyDebt)->Unit, onAddReceivable:()->Unit,
    onReceive:(FamilyReceivable)->Unit, onDeleteReceivable:(FamilyReceivable)->Unit,
    onAddRecurringBill:()->Unit, onPayRecurringBill:(RecurringBill)->Unit, onDeleteRecurringBill:(RecurringBill)->Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(p)
            .background(if(dark)midnight else Color(0xFFF7F7F9))
            .padding(horizontal=16.dp),
        contentPadding=PaddingValues(top=18.dp,bottom=28.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)
    ) {
        item { ScreenHeading("بدهی‌ها و اقساط","سررسیدها، پرداخت‌ها و مطالبات",fg,sub,dark) }
        item { Text("پرداخت هر قسط از حساب انتخابی ثبت می‌شود و موجودی و گزارش هزینه‌ها به‌روزرسانی خواهد شد.",color=sub,fontSize=12.sp) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { MetricCard("مانده اقساط",totalDebt,pink,sub,dark,Modifier.weight(1f)); MetricCard("طلب‌های دریافت‌نشده",totalReceivables,aqua,sub,dark,Modifier.weight(1f)) } }
        item { GlassCard(dark=dark) { Text("وضعیت سررسیدها",color=fg,fontWeight=FontWeight.Bold); Text("عقب‌افتاده: ${overdue.size} قسط",color=if(overdue.isEmpty())aqua else pink,fontWeight=FontWeight.SemiBold); Text("سررسید تا ۷ روز آینده: ${upcoming.size} قسط",color=if(upcoming.isEmpty())sub else Color(0xFFFFC56D)); if(overdue.isNotEmpty()) Text("مجموع اقساط عقب‌افتاده: ${money(overdue.sumOf{it.amount})}",color=pink,fontSize=12.sp); if(upcoming.isNotEmpty()) Text("مجموع اقساط نزدیک سررسید: ${money(upcoming.sumOf{it.amount})}",color=sub,fontSize=12.sp) } }
        item { Row(verticalAlignment=Alignment.CenterVertically) { Text("طلب از دیگران",color=fg,fontWeight=FontWeight.Bold); Spacer(Modifier.weight(1f)); TextButton(onClick=onAddReceivable){Text("ثبت طلب +")} } }
        if(receivables.isEmpty()) item { Text("طلبی ثبت نشده است.",color=sub,fontSize=12.sp) } else items(receivables,key={"r${it.id}"}) { item -> GlassCard(dark=dark) { Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(item.person,color=fg,fontWeight=FontWeight.Bold); Text("${money(item.amount)} · سررسید ${jalali(item.dueAt)}",color=sub,fontSize=11.sp); if(item.note.isNotBlank()) Text(item.note,color=sub,fontSize=10.sp); if(item.receivedAt!=null) Text("دریافت‌شده در ${jalali(item.receivedAt)}",color=aqua,fontSize=10.sp) }; if(item.receivedAt==null) { TextButton(onClick={onReceive(item)}){Text("دریافت شد")}; IconButton(onClick={onDeleteReceivable(item)}){Icon(Icons.Default.Delete,null,tint=pink)} } else Icon(Icons.Default.CheckCircle,null,tint=aqua) } } }
        item { Row(verticalAlignment=Alignment.CenterVertically) { Text("قبوض و هزینه‌های دوره‌ای",color=fg,fontWeight=FontWeight.Bold); Spacer(Modifier.weight(1f)); TextButton(onClick=onAddRecurringBill){Text("ثبت مورد +")} } }
        if(recurringBills.isEmpty()) item { Text("هزینه دوره‌ای ثبت نشده است.",color=sub,fontSize=12.sp) }
        items(recurringBills,key={"bill${it.id}"}) { bill -> GlassCard(dark=dark) { Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(bill.title,color=fg,fontWeight=FontWeight.Bold); Text("${bill.category} · ${money(bill.amount)}",color=sub,fontSize=11.sp); Text("سررسید: ${jalali(bill.dueAt)} · ${bill.frequency}",color=if(bill.dueAt<System.currentTimeMillis())pink else sub,fontSize=11.sp); if(bill.note.isNotBlank()) Text(bill.note,color=sub,fontSize=10.sp) }; IconButton(onClick={onDeleteRecurringBill(bill)}){Icon(Icons.Default.Delete,null,tint=pink)} }; Button(onClick={onPayRecurringBill(bill)},enabled=bill.dueAt<=System.currentTimeMillis(),modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=violet)){Text(if(bill.dueAt<=System.currentTimeMillis())"ثبت پرداخت و انتقال سررسید بعدی" else "پرداخت در تاریخ سررسید فعال می‌شود")} } }
        item { Text("بدهی‌ها و اقساط",color=fg,fontWeight=FontWeight.Bold) }
        if(debts.isEmpty()) item { GlassCard(dark=dark) { Text("هنوز بدهی یا قسطی ثبت نشده است.",color=sub); Text("برای ثبت اولین بدهی، دکمه + را بزنید.",color=sub,fontSize=12.sp) } }
        items(debts,key={it.id}) { debt ->
            val installments=installmentsByDebt[debt.id].orEmpty()
            var expanded by remember(debt.id) { mutableStateOf(false) }
            val percent=if(debt.totalAmount>0)(debt.paidAmount.toFloat()/debt.totalAmount).coerceIn(0f,1f) else 0f
            GlassCard(dark=dark) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(debt.title,color=fg,fontWeight=FontWeight.Bold,fontSize=16.sp)
                        Text("${debt.paidInstallments} از ${debt.installmentCount} قسط پرداخت شده",color=sub,fontSize=11.sp)
                    }
                    IconButton(onClick={onDelete(debt)}) { Icon(Icons.Default.Delete,null,tint=pink) }
                }
                Text("مانده بدهی: ${money(debt.remainingAmount)}",color=if(debt.remainingAmount==0L)aqua else fg,fontWeight=FontWeight.Bold)
                Text("پرداخت‌شده: ${money(debt.paidAmount)} از ${money(debt.totalAmount)}",color=sub,fontSize=11.sp)
                LinearProgressIndicator(progress={percent},modifier=Modifier.fillMaxWidth().padding(vertical=7.dp))
                debt.nextDueAt?.let { Text("سررسید قسط بعدی: ${jalali(it)}",color=if(it<System.currentTimeMillis())pink else sub,fontSize=11.sp) }
                TextButton(onClick={expanded=!expanded}) { Text(if(expanded)"بستن فهرست اقساط" else "مشاهده اقساط (${installments.size})") }
                if(expanded) {
                    installments.forEach { installment ->
                        Divider(color=if(dark)Color.White.copy(alpha=.12f) else Color(0xFFDDE2F3))
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("قسط ${installment.number} · ${money(installment.amount)}",color=fg,fontWeight=FontWeight.SemiBold,fontSize=12.sp)
                                Text("سررسید: ${jalali(installment.dueAt)}",color=sub,fontSize=10.sp)
                                if(installment.paidAt!=null) Text("پرداخت‌شده در ${jalali(installment.paidAt)}",color=aqua,fontSize=10.sp)
                            }
                            if(installment.paidAt==null) Button(onClick={onPay(installment)},colors=ButtonDefaults.buttonColors(containerColor=violet),contentPadding=PaddingValues(horizontal=10.dp,vertical=5.dp)) { Text("پرداخت",fontSize=11.sp) }
                            else Icon(Icons.Default.CheckCircle,null,tint=aqua)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun DebtDialog(dark:Boolean,onDismiss:()->Unit,onSave:(String,Long,Int,Long,String)->Boolean) {
    val context=LocalContext.current
    var title by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("12") }
    var note by remember { mutableStateOf("") }
    var due by remember { mutableLongStateOf(java.util.Calendar.getInstance().apply { add(java.util.Calendar.MONTH,1) }.timeInMillis) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest=onDismiss,
        containerColor=if(dark)panel else Color(0xFFF8F9F5),
        title={Text("ثبت بدهی جدید",color=if(dark)Color.White else Color(0xFF303039))},
        text={GlassDialogBody(dark=dark) {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutText(title,{title=it;error=false},"عنوان بدهی یا طلبکار")
                OutText(total,{total=formatAmount(it);error=false},"مبلغ کل به تومان",numeric=true)
                OutText(count,{count=it.filter(Char::isDigit);error=false},"تعداد اقساط",numeric=true)
                OutlinedButton(onClick={
                    val cal=java.util.Calendar.getInstance().apply{timeInMillis=due}
                    DatePickerDialog(context,{_,year,month,day->
                        due=java.util.Calendar.getInstance().apply{set(year,month,day,12,0,0);set(java.util.Calendar.MILLISECOND,0)}.timeInMillis
                    },cal.get(java.util.Calendar.YEAR),cal.get(java.util.Calendar.MONTH),cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
                },modifier=Modifier.fillMaxWidth()) { Text("اولین سررسید: ${jalali(due)}") }
                OutText(note,{note=it},"توضیحات (اختیاری)")
                if(error) Text("عنوان، مبلغ و تعداد اقساط را بررسی کنید. تعداد اقساط حداکثر ۱۲۰ و مبلغ هر قسط باید مثبت باشد.",color=pink,fontSize=12.sp)
            }
        }},
        confirmButton={TextButton(onClick={
            val amount=parseAmount(total); val installments=normalizeDigits(count).toIntOrNull()
            if(amount!=null&&installments!=null&&onSave(title,amount,installments,due,note)) onDismiss() else error=true
        }) { Text("ثبت بدهی") }},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}}
    )
}

@Composable private fun RecurringBillDialog(dark:Boolean,onDismiss:()->Unit,onSave:(String,String,Long,Long,String,String)->Boolean) {
    val context=LocalContext.current
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("قبوض خدماتی") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf("ماهانه") }
    var due by remember { mutableLongStateOf(java.util.Calendar.getInstance().apply{add(java.util.Calendar.DAY_OF_YEAR,1)}.timeInMillis) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark)panel else Color(0xFFF8F9F5),title={Text("ثبت هزینه دوره‌ای",color=if(dark)Color.White else Color(0xFF303039))},
        text={GlassDialogBody(dark=dark){Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutText(title,{title=it;error=false},"عنوان (مثلاً اینترنت یا برق)")
            OutText(category,{category=it;error=false},"دسته‌بندی")
            OutText(amount,{amount=formatAmount(it);error=false},"مبلغ به تومان",numeric=true)
            Text("تکرار پرداخت",color=muted)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("هفتگی","ماهانه","سالانه").forEach{f->FilterChip(selected=frequency==f,onClick={frequency=f},label={Text(f)})}}
            OutlinedButton(onClick={val cal=java.util.Calendar.getInstance().apply{timeInMillis=due};DatePickerDialog(context,{_,y,m,d->due=java.util.Calendar.getInstance().apply{set(y,m,d,12,0,0);set(java.util.Calendar.MILLISECOND,0)}.timeInMillis},cal.get(java.util.Calendar.YEAR),cal.get(java.util.Calendar.MONTH),cal.get(java.util.Calendar.DAY_OF_MONTH)).show()},modifier=Modifier.fillMaxWidth()){Text("اولین سررسید: ${jalali(due)}")}
            OutText(note,{note=it},"توضیحات (اختیاری)")
            if(error)Text("عنوان، دسته‌بندی، مبلغ و تاریخ را بررسی کنید.",color=pink)
        }}},
        confirmButton={TextButton(onClick={val v=parseAmount(amount);if(v!=null&&onSave(title,category,v,due,frequency,note))onDismiss()else error=true}){Text("ثبت هزینه")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}

@Composable private fun PayRecurringBillDialog(bill:RecurringBill,accounts:List<FinanceAccount>,dark:Boolean,onDismiss:()->Unit,onSave:(Long)->Boolean) {
    var accountId by remember{mutableLongStateOf(accounts.firstOrNull()?.id?:0L)}
    var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark)panel else Color(0xFFF8F9F5),title={Text("ثبت پرداخت ${bill.title}",color=if(dark)Color.White else Color(0xFF303039))},
        text={GlassDialogBody(dark=dark){Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("مبلغ: ${money(bill.amount)}",color=if(dark)Color.White else Color(0xFF303039),fontWeight=FontWeight.Bold)
            Text("سررسید فعلی: ${jalali(bill.dueAt)}",color=muted,fontSize=12.sp)
            Text("برداشت از حساب:",color=muted)
            accounts.forEach{a->FilterChip(selected=accountId==a.id,onClick={accountId=a.id},label={Text("${a.memberName} · ${a.name} · ${money(a.balance)}")})}
            if(error)Text("پرداخت ثبت نشد؛ موجودی حساب را بررسی کنید.",color=pink)
            Text("پس از پرداخت، تراکنش هزینه ثبت و سررسید بعدی به‌صورت خودکار محاسبه می‌شود.",color=muted,fontSize=11.sp)
        }}},
        confirmButton={TextButton(onClick={if(accounts.any{it.id==accountId}&&onSave(accountId))onDismiss()else error=true}){Text("تأیید پرداخت")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}

@Composable private fun ReceivableDialog(dark:Boolean,onDismiss:()->Unit,onSave:(String,Long,Long,String)->Boolean) {
    val context=LocalContext.current
    var person by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var due by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest=onDismiss,
        containerColor=if(dark)panel else Color(0xFFF8F9F5),
        title={Text("ثبت طلب جدید",color=if(dark)Color.White else Color(0xFF303039))},
        text={GlassDialogBody(dark=dark) {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutText(person,{person=it;error=false},"نام شخص")
                OutText(amount,{amount=formatAmount(it);error=false},"مبلغ طلب به تومان",numeric=true)
                OutlinedButton(onClick={
                    val cal=java.util.Calendar.getInstance().apply{timeInMillis=due}
                    DatePickerDialog(context,{_,year,month,day->due=java.util.Calendar.getInstance().apply{set(year,month,day,12,0,0);set(java.util.Calendar.MILLISECOND,0)}.timeInMillis},cal.get(java.util.Calendar.YEAR),cal.get(java.util.Calendar.MONTH),cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
                },modifier=Modifier.fillMaxWidth()){Text("تاریخ سررسید: ${jalali(due)}")}
                OutText(note,{note=it},"توضیحات (اختیاری)")
                if(error) Text("نام شخص، مبلغ و تاریخ را بررسی کنید.",color=pink)
            }
        }},
        confirmButton={TextButton(onClick={val value=parseAmount(amount);if(value!=null&&onSave(person,value,due,note))onDismiss()else error=true}){Text("ثبت طلب")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}}
    )
}

@Composable private fun PayDebtDialog(installment:DebtInstallment,accounts:List<FinanceAccount>,dark:Boolean,onDismiss:()->Unit,onSave:(Long)->Boolean) {
    var accountId by remember { mutableLongStateOf(accounts.firstOrNull()?.id?:0L) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest=onDismiss,
        containerColor=if(dark)panel else Color(0xFFF8F9F5),
        title={Text("پرداخت قسط ${installment.number}",color=if(dark)Color.White else Color(0xFF303039))},
        text={GlassDialogBody(dark=dark) {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("مبلغ قسط: ${money(installment.amount)}",color=if(dark)Color.White else Color(0xFF303039),fontWeight=FontWeight.Bold)
                Text("سررسید: ${jalali(installment.dueAt)}",color=muted,fontSize=12.sp)
                Text("برداشت از حساب:",color=muted)
                accounts.forEach { a ->
                    FilterChip(selected=accountId==a.id,onClick={accountId=a.id},label={Text("${a.memberName} · ${a.name} · ${money(a.balance)}")})
                }
                if(error) Text("پرداخت انجام نشد؛ حساب انتخابی باید موجودی کافی داشته باشد.",color=pink,fontSize=12.sp)
            }
        }},
        confirmButton={TextButton(onClick={if(accounts.any{it.id==accountId}&&onSave(accountId))onDismiss()else error=true}){Text("تأیید پرداخت")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}}
    )
}

@Composable private fun GlassDialogBody(dark:Boolean,content:@Composable ColumnScope.()->Unit) {
    GlassCard(dark=dark) {
        Column(verticalArrangement=Arrangement.spacedBy(9.dp),content=content)
    }
}

@Composable private fun TransactionDialog(db:FinanceDatabase,accounts:List<FinanceAccount>,dark:Boolean,onDismiss:()->Unit,onSave:(Long,String,String,Long,String)->Boolean)=TransactionForm(db,"ثبت تراکنش",null,accounts,dark,onDismiss,onSave)
@Composable private fun EditDialog(db:FinanceDatabase,t:FinanceTransaction,accounts:List<FinanceAccount>,dark:Boolean,onDismiss:()->Unit,onSave:(Long,String,String,Long,String)->Boolean)=TransactionForm(db,"ویرایش تراکنش",t,accounts,dark,onDismiss,onSave)
@Composable private fun TransactionForm(db:FinanceDatabase,title:String,existing:FinanceTransaction?,accounts:List<FinanceAccount>,dark:Boolean,onDismiss:()->Unit,onSave:(Long,String,String,Long,String)->Boolean){
    var type by remember{mutableStateOf(existing?.type?:"هزینه")}
    var category by remember{mutableStateOf(existing?.category?:"خرید روزانه")}
    var amount by remember{mutableStateOf(existing?.amount?.let { formatAmount(it.toString()) }?:"")}
    var note by remember{mutableStateOf(existing?.note?:"")}
    var account by remember{mutableStateOf(existing?.accountId?:accounts.firstOrNull()?.id?:0L)}
    var error by remember{mutableStateOf(false)}
    var showAddCategory by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf("") }
    var categoryError by remember { mutableStateOf(false) }
    val categoryOptions = remember(type) { db.categories(type) }
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark) panel else Color(0xFFF8F9F5),title={Text(title,color=if(dark)Color.White else Color(0xFF303039))},
        text={GlassDialogBody(dark=dark) {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Row { FilterChip(type=="درآمد",{type="درآمد";category=db.categories("درآمد").firstOrNull().orEmpty()},{Text("درآمد")}); Spacer(Modifier.width(8.dp)); FilterChip(type=="هزینه",{type="هزینه";category=db.categories("هزینه").firstOrNull().orEmpty()},{Text("هزینه")}) }
                Text("حساب",color=muted)
                accounts.forEach{a->FilterChip(account==a.id,{account=a.id},{Text("${a.memberName} · ${a.name}")})}
                OutText(amount,{amount=formatAmount(it);error=false},"مبلغ به تومان",numeric=true)
                Text("انتخاب دسته‌بندی",color=muted,fontWeight=FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                    categoryOptions.forEach { item -> FilterChip(selected=category==item,onClick={category=item},label={Text(item)}) }
                }
                OutlinedButton(onClick={showAddCategory=true},modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Add,null); Spacer(Modifier.width(6.dp)); Text("افزودن دسته‌بندی جدید") }
                OutText(category,{category=it;error=false},"یا نام دسته‌بندی را دستی وارد کنید")
                OutText(note,{note=it},"توضیحات (اختیاری)")
                if(error) Text("اطلاعات واردشده معتبر نیست یا موجودی کافی نیست.",color=pink)
            }
        }},
        confirmButton={TextButton(onClick={val v=parseAmount(amount);if(v!=null&&v>0&&accounts.any{it.id==account}&&onSave(account,type,category,v,note))onDismiss() else error=true}){Text("ذخیره")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
    if(showAddCategory) AlertDialog(
        onDismissRequest={showAddCategory=false;newCategory="";categoryError=false},
        containerColor=if(dark)panel else Color(0xFFF8F9F5),
        title={Text("افزودن دسته‌بندی $type",color=if(dark)Color.White else Color(0xFF303039))},
        text={GlassDialogBody(dark=dark) {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutText(newCategory,{newCategory=it;categoryError=false},"نام دسته‌بندی جدید")
                if(categoryError) Text("نام تکراری یا نامعتبر است.",color=pink)
            }
        }},
        confirmButton={TextButton(onClick={
            if(db.addCategory(type,newCategory)){category=newCategory.trim();showAddCategory=false;newCategory=""}
            else categoryError=true
        }){Text("افزودن")}},
        dismissButton={TextButton(onClick={showAddCategory=false}){Text("انصراف")}}
    )
}
@Composable private fun TransferDialog(accounts:List<FinanceAccount>,dark:Boolean,onDismiss:()->Unit,onSave:(Long,Long,Long,String)->Boolean){
    var from by remember{mutableStateOf(accounts.firstOrNull()?.id?:0L)}
    var to by remember{mutableStateOf(accounts.getOrNull(1)?.id?:0L)}
    var amount by remember{mutableStateOf("")}
    var note by remember{mutableStateOf("")}
    var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark) panel else Color(0xFFF8F9F5),title={Text("انتقال وجه بین حساب‌ها",color=if(dark)Color.White else Color(0xFF303039))},
        text={GlassDialogBody(dark=dark) {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("حساب مبدأ",color=muted)
                accounts.forEach{a->FilterChip(from==a.id,{from=a.id},{Text("${a.memberName} · ${a.name}")})}
                Text("حساب مقصد",color=muted)
                accounts.forEach{a->FilterChip(to==a.id,{to=a.id},{Text("${a.memberName} · ${a.name}")})}
                OutText(amount,{amount=formatAmount(it);error=false},"مبلغ انتقال",numeric=true)
                OutText(note,{note=it},"توضیحات")
                if(error)Text("حساب مبدأ و مقصد باید متفاوت و موجودی کافی باشد.",color=pink)
            }
        }},
        confirmButton={TextButton(onClick={val v=parseAmount(amount);if(v!=null&&v>0&&from!=to&&onSave(from,to,v,note))onDismiss()else error=true}){Text("انتقال")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun SimpleNameDialog(title:String,label:String,dark:Boolean,onDismiss:()->Unit,onSave:(String)->Boolean){
    var v by remember{mutableStateOf("")}
    var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark) panel else Color(0xFFF8F9F5),title={Text(title,color=if(dark)Color.White else Color(0xFF303039))},
        text={GlassDialogBody(dark=dark) { Column { OutText(v,{v=it;error=false},label); if(error) Text("مقدار معتبر وارد کنید.",color=pink) } }},
        confirmButton={TextButton(onClick={if(onSave(v))onDismiss()else error=true}){Text("ذخیره")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun AccountDialog(members:List<FinanceMember>,dark:Boolean,onDismiss:()->Unit,onSave:(Long,String)->Boolean){
    var id by remember{mutableStateOf(members.firstOrNull()?.id?:0L)}
    var name by remember{mutableStateOf("")}
    var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark) panel else Color(0xFFF8F9F5),title={Text("افزودن حساب",color=if(dark)Color.White else Color(0xFF303039))},
        text={GlassDialogBody(dark=dark) { Column { members.forEach{FilterChip(id==it.id,{id=it.id},{Text(it.name)})}; OutText(name,{name=it;error=false},"نام حساب"); if(error)Text("نام حساب و عضو را بررسی کنید.",color=pink) } }},
        confirmButton={TextButton(onClick={if(onSave(id,name))onDismiss()else error=true}){Text("ذخیره")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun BudgetDialog(dark:Boolean,onDismiss:()->Unit,onSave:(String,Long)->Boolean){
    var c by remember{mutableStateOf("خرید روزانه")}
    var a by remember{mutableStateOf("")}
    var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark) panel else Color(0xFFF8F9F5),title={Text("تعریف بودجه ماهانه",color=if(dark)Color.White else Color(0xFF303039))},
        text={GlassDialogBody(dark=dark) { Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutText(c,{c=it;error=false},"دسته‌بندی");OutText(a,{a=formatAmount(it);error=false},"سقف بودجه به تومان",numeric=true);if(error)Text("دسته‌بندی و مبلغ معتبر وارد کنید.",color=pink)} }},
        confirmButton={TextButton(onClick={val v=parseAmount(a);if(v!=null&&v>0&&onSave(c,v))onDismiss()else error=true}){Text("ذخیره")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun GoalDialog(dark:Boolean,onDismiss:()->Unit,onSave:(String,Long)->Boolean){
    var t by remember{mutableStateOf("")}
    var a by remember{mutableStateOf("")}
    var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark) panel else Color(0xFFF8F9F5),title={Text("هدف پس‌انداز جدید",color=if(dark)Color.White else Color(0xFF303039))},
        text={GlassDialogBody(dark=dark) { Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutText(t,{t=it;error=false},"عنوان هدف");OutText(a,{a=formatAmount(it);error=false},"مبلغ هدف به تومان",numeric=true);if(error)Text("عنوان هدف و مبلغ معتبر وارد کنید.",color=pink)} }},
        confirmButton={TextButton(onClick={val v=parseAmount(a);if(t.isNotBlank()&&v!=null&&v>0&&onSave(t.trim(),v))onDismiss()else error=true}){Text("ذخیره")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun GoalContributionDialog(goal:SavingGoal,dark:Boolean,onDismiss:()->Unit,onSave:(Long)->Boolean){
    var amount by remember{mutableStateOf("")}
    var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark) panel else Color(0xFFF8F9F5),title={Text("افزودن به هدف پس‌انداز",color=if(dark)Color.White else Color(0xFF303039))},
        text={GlassDialogBody(dark=dark) { Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("هدف: ${goal.title}");Text("باقی‌مانده: ${money((goal.target-goal.saved).coerceAtLeast(0))}");OutText(amount,{amount=formatAmount(it);error=false},"مبلغ واریزی به تومان",numeric=true);if(error)Text("مبلغ معتبر وارد کنید و از مبلغ باقی‌مانده بیشتر نباشد.",color=pink)} }},
        confirmButton={TextButton(onClick={val v=parseAmount(amount);if(v!=null&&v>0&&v<=goal.target-goal.saved&&onSave(v))onDismiss()else error=true}){Text("ثبت پس‌انداز")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun OutText(value:String,onChange:(String)->Unit,label:String,numeric:Boolean=false){OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=if(numeric)KeyboardType.Number else KeyboardType.Text))}
