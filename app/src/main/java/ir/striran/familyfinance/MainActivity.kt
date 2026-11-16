package ir.striran.familyfinance

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
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

class MainActivity : ComponentActivity() {
    private lateinit var db: FinanceDatabase
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = FinanceDatabase(this)
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
            FinanceApp(activeDb, onExport = { export.launch("پشتیبان-مدیریت-مالی.db") }, onImport = { import.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "*/*")) })
        }
    }
}

private val midnight = Color(0xFF10172F)
private val panel = Color(0xFF202B4D)
private val violet = Color(0xFF796BFA)
private val aqua = Color(0xFF55D6D0)
private val pink = Color(0xFFFF78B7)
private val muted = Color(0xFFB8C4E5)
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
fun FinanceApp(db: FinanceDatabase, onExport: () -> Unit, onImport: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<FinanceTransaction?>(null) }
    var selectedGoal by remember { mutableStateOf<SavingGoal?>(null) }
    var selectedBudget by remember { mutableStateOf<Budget?>(null) }
    var selectedInstallment by remember { mutableStateOf<DebtInstallment?>(null) }
    var selectedDebt by remember { mutableStateOf<FamilyDebt?>(null) }
    var selectedReceivable by remember { mutableStateOf<FamilyReceivable?>(null) }
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE) }
    var dark by remember { mutableStateOf(preferences.getBoolean("dark_theme", true)) }
    val balance = remember(refresh) { db.totalBalance() }
    val income = remember(refresh) { db.monthTotal("درآمد") }
    val expense = remember(refresh) { db.monthTotal("هزینه") }
    val txs = remember(refresh) { db.allTransactions() }
    val accounts = remember(refresh) { db.accounts() }
    val budgets = remember(refresh) { db.budgets() }
    val goals = remember(refresh) { db.goals() }
    val debts = remember(refresh) { db.debts() }
    val receivables = remember(refresh) { db.receivables() }
    val overdueInstallments = remember(refresh) { db.overdueInstallments() }
    val upcomingInstallments = remember(refresh) { db.upcomingInstallments(7) }
    val installmentsByDebt = remember(refresh) { debts.associate { it.id to db.debtInstallments(it.id) } }
    val bg = if(dark) midnight else Color(0xFFF1F3FC)
    val fg = if(dark) Color.White else Color(0xFF1D2340)
    val sub = if(dark) muted else Color(0xFF626B88)
    MaterialTheme(colorScheme = if(dark) darkColorScheme(primary=Color(0xFFC4BDFF),secondary=aqua,background=midnight,surface=panel) else lightColorScheme(primary=violet,secondary=Color(0xFF168F8A))) {
        Scaffold(containerColor=bg, bottomBar={
            NavigationBar(modifier=Modifier.padding(horizontal=12.dp, vertical=7.dp).clip(RoundedCornerShape(26.dp)).border(1.dp,if(dark)Color.White.copy(alpha=.16f) else Color(0xFFDDE2F3),RoundedCornerShape(26.dp)),containerColor=if(dark) Color(0xFF273252).copy(alpha=.94f) else Color.White.copy(alpha=.96f),tonalElevation=0.dp) {
                val labels=listOf("خانه","تراکنش","حساب‌ها","گزارش","بیشتر","اقساط")
                val icons=listOf(Icons.Default.Home,Icons.Default.ReceiptLong,Icons.Default.AccountBalanceWallet,Icons.Default.BarChart,Icons.Default.MoreHoriz,Icons.Default.EventAvailable)
                labels.forEachIndexed { i,label -> NavigationBarItem(selected=page==i,onClick={page=i},icon={Icon(icons[i],null)},label={Text(label,fontSize=10.sp)}) }
            }
        }, floatingActionButton={ if(page==0||page==1||page==5) FloatingActionButton(onClick={dialog=if(page==5)"debt" else "transaction"},containerColor=violet,contentColor=Color.White){Icon(Icons.Default.Add,null)} }) { pad ->
            when(page) {
                0 -> Dashboard(pad,balance,income,expense,txs.take(5),fg,sub,dark,{dialog="transaction"},{dialog="transfer"})
                1 -> TransactionsPage(pad,txs,fg,sub,dark,{selected=it;dialog="edit"},{selected=it;dialog="delete"})
                2 -> AccountsPage(pad,accounts,fg,sub,dark,{dialog="member"},{dialog="account"},{dialog="transfer"})
                3 -> ReportsPage(pad,income,expense,db.categoryTotals(),budgets,fg,sub,dark,{dialog="budget"},{ budget -> selectedBudget=budget; dialog="deleteBudget" })
                4 -> MorePage(pad,goals,fg,sub,dark,{dialog="goal"},{ goal -> selectedGoal=goal; dialog="contribute" },{ goal -> selectedGoal=goal; dialog="deleteGoal" },{dark=!dark;preferences.edit().putBoolean("dark_theme",dark).apply()},onExport,onImport)
                else -> DebtsPage(pad,debts,installmentsByDebt,receivables,overdueInstallments,upcomingInstallments,db.totalRemainingDebt(),db.totalPendingReceivables(),fg,sub,dark,{ installment -> selectedInstallment=installment; dialog="payDebt" },{ debt -> dialog="deleteDebt"; selectedDebt=debt },{ dialog="receivable" },{ receivable -> selectedReceivable=receivable; dialog="markReceived" },{ receivable -> selectedReceivable=receivable; dialog="deleteReceivable" })
            }
        }
        when(dialog) {
            "transaction" -> TransactionDialog(accounts,dark,onDismiss={dialog=""},onSave={a,t,c,v,n->db.addTransaction(a,t,c,v,n).also{if(it)refresh++}})
            "edit" -> selected?.let { EditDialog(it,accounts,dark,onDismiss={dialog="";selected=null},onSave={a,t,c,v,n->db.updateTransaction(it.id,a,t,c,v,n).also{ok->if(ok)refresh++}}) }
            "delete" -> selected?.let { AlertDialog(onDismissRequest={dialog="";selected=null},containerColor=if(dark) panel else Color(0xFFF7F8FF),title={Text("حذف تراکنش",color=Color.White)},text={GlassDialogBody(dark=dark) { Text("این تراکنش حذف و موجودی حساب اصلاح شود؟",color=Color.White) }},confirmButton={TextButton(onClick={if(db.deleteTransaction(it.id)){refresh++;dialog="";selected=null}else{Toast.makeText(context,"حذف انجام نشد؛ موجودی یا نوع تراکنش را بررسی کنید.",Toast.LENGTH_LONG).show()}}){Text("حذف")}},dismissButton={TextButton(onClick={dialog="";selected=null}){Text("انصراف")}}) }
            "transfer" -> TransferDialog(accounts,dark,onDismiss={dialog=""},onSave={a,b,v,n->db.transfer(a,b,v,n).also{if(it)refresh++}})
            "member" -> SimpleNameDialog("افزودن عضو خانواده","نام عضو",dark,onDismiss={dialog=""},onSave={db.addMember(it).also{ok->if(ok)refresh++}})
            "account" -> AccountDialog(db.members(),dark,onDismiss={dialog=""},onSave={a,n->db.addAccount(a,n).also{if(it)refresh++}})
            "budget" -> BudgetDialog(dark,onDismiss={dialog=""},onSave={c,v->db.saveBudget(c,v).also{if(it)refresh++}})
            "debt" -> DebtDialog(dark,onDismiss={dialog=""},onSave={title,total,count,due,note->db.addDebt(title,total,count,due,note).also{if(it)refresh++}})
            "receivable" -> ReceivableDialog(dark,onDismiss={dialog=""},onSave={person,amount,due,note->db.addReceivable(person,amount,due,note).also{if(it)refresh++}})
            "markReceived" -> selectedReceivable?.let { receivable -> AlertDialog(onDismissRequest={dialog="";selectedReceivable=null},title={Text("ثبت دریافت طلب")},text={Text("مبلغ ${money(receivable.amount)} از ${receivable.person} دریافت شده است؟")},confirmButton={TextButton(onClick={if(db.markReceivableReceived(receivable.id))refresh++;dialog="";selectedReceivable=null}){Text("ثبت دریافت")}},dismissButton={TextButton(onClick={dialog="";selectedReceivable=null}){Text("انصراف")}}) }
            "deleteReceivable" -> selectedReceivable?.let { receivable -> AlertDialog(onDismissRequest={dialog="";selectedReceivable=null},title={Text("حذف طلب")},text={Text("طلب ثبت‌شده از ${receivable.person} حذف شود؟")},confirmButton={TextButton(onClick={db.deleteReceivable(receivable.id);refresh++;dialog="";selectedReceivable=null}){Text("حذف",color=pink)}},dismissButton={TextButton(onClick={dialog="";selectedReceivable=null}){Text("انصراف")}}) }
            "payDebt" -> selectedInstallment?.let { installment -> PayDebtDialog(installment,accounts,dark,onDismiss={dialog="";selectedInstallment=null},onSave={accountId->db.payDebtInstallment(installment.id,accountId).also{if(it)refresh++}}) }
            "deleteDebt" -> selectedDebt?.let { debt -> AlertDialog(onDismissRequest={dialog="";selectedDebt=null},containerColor=if(dark) panel else Color(0xFFF7F8FF),title={Text("حذف بدهی",color=Color.White)},text={GlassDialogBody(dark=dark){Text(if(debt.paidAmount>0)"برای این بدهی پرداخت ثبت شده و حذف آن مجاز نیست." else "بدهی «${debt.title}» و اقساط آن حذف شود؟",color=Color.White)}},confirmButton={TextButton(onClick={if(db.deleteDebt(debt.id)){refresh++;dialog="";selectedDebt=null}else Toast.makeText(context,"بدهی دارای پرداخت قبلی قابل حذف نیست.",Toast.LENGTH_LONG).show()}){Text("حذف",color=pink)}},dismissButton={TextButton(onClick={dialog="";selectedDebt=null}){Text("انصراف")}}) }
            "goal" -> GoalDialog(dark,onDismiss={dialog=""},onSave={t,v->db.addGoal(t,v).also{if(it)refresh++}})
            "contribute" -> selectedGoal?.let { goal -> GoalContributionDialog(goal,dark,onDismiss={dialog="";selectedGoal=null},onSave={amount->db.contributeGoal(goal.id,amount).also{if(it)refresh++}}) }
            "deleteGoal" -> selectedGoal?.let { goal -> AlertDialog(onDismissRequest={dialog="";selectedGoal=null},containerColor=if(dark) panel else Color(0xFFF7F8FF),title={Text("حذف هدف",color=Color.White)},text={GlassDialogBody(dark=dark) { Text("هدف «"+goal.title+"» حذف شود؟",color=Color.White) }},confirmButton={TextButton(onClick={db.deleteGoal(goal.id);refresh++;dialog="";selectedGoal=null}){Text("حذف",color=pink)}},dismissButton={TextButton(onClick={dialog="";selectedGoal=null}){Text("انصراف")}}) }
            "deleteBudget" -> selectedBudget?.let { budget -> AlertDialog(onDismissRequest={dialog="";selectedBudget=null},containerColor=if(dark) panel else Color(0xFFF7F8FF),title={Text("حذف بودجه",color=Color.White)},text={GlassDialogBody(dark=dark) { Text("بودجه «"+budget.category+"» حذف شود؟",color=Color.White) }},confirmButton={TextButton(onClick={db.deleteBudget(budget.id);refresh++;dialog="";selectedBudget=null}){Text("حذف",color=pink)}},dismissButton={TextButton(onClick={dialog="";selectedBudget=null}){Text("انصراف")}}) }
        }
    }
}

@Composable private fun GlassCard(modifier:Modifier=Modifier,dark:Boolean=true,content:@Composable ColumnScope.()->Unit){
    val shape = RoundedCornerShape(26.dp)
    val glassBrush = if(dark)
        Brush.linearGradient(listOf(Color(0xFF6575A8).copy(alpha=.30f),Color(0xFF26365D).copy(alpha=.20f),Color(0xFF9D91FF).copy(alpha=.13f)))
    else
        Brush.linearGradient(listOf(Color.White.copy(alpha=.94f),Color(0xFFE9EDFF).copy(alpha=.76f),Color.White.copy(alpha=.88f)))
    val rim = if(dark) Color.White.copy(alpha=.28f) else Color.White.copy(alpha=.96f)
    Box(
        modifier=modifier.fillMaxWidth().shadow(if(dark) 12.dp else 5.dp,shape,clip=false)
            .clip(shape).background(glassBrush).border(1.dp,rim,shape)
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent,Color.White.copy(alpha=if(dark) .62f else .95f),Color.Transparent))))
        Column(Modifier.fillMaxWidth().padding(17.dp),content=content)
        Box(Modifier.align(Alignment.BottomEnd).padding(end=18.dp,bottom=8.dp).size(52.dp,2.dp).background(Brush.horizontalGradient(listOf(Color.Transparent,Color.White.copy(alpha=if(dark) .13f else .35f),Color.Transparent)),RoundedCornerShape(50)))
    }
}
@Composable private fun Dashboard(p:PaddingValues,balance:Long,income:Long,expense:Long,txs:List<FinanceTransaction>,fg:Color,sub:Color,dark:Boolean,onAdd:()->Unit,onTransfer:()->Unit){
    LazyColumn(Modifier.fillMaxSize().padding(p).background(Brush.verticalGradient(if(dark) listOf(Color(0xFF202D58),midnight) else listOf(Color(0xFFE8EAFE),Color(0xFFF5F6FC)))).padding(horizontal=18.dp),contentPadding=PaddingValues(top=22.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        item { Column { Text("سلام، خوش آمدید",color=sub,fontSize=13.sp); Text("مدیریت مالی خانواده",color=fg,fontSize=24.sp,fontWeight=FontWeight.Bold); Text(jalali(System.currentTimeMillis()),color=sub,fontSize=12.sp) } }
        item { Card(shape=RoundedCornerShape(28.dp),colors=CardDefaults.cardColors(containerColor=Color.Transparent)){Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF7667F5),Color(0xFF4A4BAE),Color(0xFF276D9B)))).padding(22.dp)){Text("موجودی کل خانواده",color=Color.White.copy(alpha=.85f));Text(money(balance),color=Color.White,fontSize=27.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(10.dp));Text("نمای کلی تمام حساب‌ها",color=Color.White.copy(alpha=.8f),fontSize=12.sp)}}}
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){MetricCard("درآمد این ماه",income,aqua,sub,dark,Modifier.weight(1f));MetricCard("هزینه این ماه",expense,pink,sub,dark,Modifier.weight(1f))} }
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Button(onClick=onAdd,modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=violet)){Icon(Icons.Default.Add,null);Text("ثبت تراکنش")};OutlinedButton(onClick=onTransfer,modifier=Modifier.weight(1f)){Icon(Icons.Default.SwapHoriz,null);Text("انتقال وجه")} } }
        item { Text("آخرین تراکنش‌ها",color=fg,fontSize=18.sp,fontWeight=FontWeight.Bold) }
        if(txs.isEmpty()) item { GlassCard(dark=dark){Text("هنوز تراکنشی ثبت نشده است.",color=sub);Text("از دکمه ثبت تراکنش برای شروع استفاده کنید.",color=sub,fontSize=12.sp)} }
        else items(txs,key={it.id}) { TxRow(it,fg,sub,dark) }
    }
}
@Composable private fun MetricCard(title:String,value:Long,tint:Color,sub:Color,dark:Boolean,mod:Modifier){GlassCard(mod,dark){Text(title,color=sub,fontSize=12.sp);Spacer(Modifier.height(8.dp));Text(money(value),color=tint,fontSize=16.sp,fontWeight=FontWeight.Bold)}}
@Composable private fun TxRow(t:FinanceTransaction,fg:Color,sub:Color,dark:Boolean,onEdit:(()->Unit)?=null,onDelete:(()->Unit)?=null){
    GlassCard(dark=dark){Row(verticalAlignment=Alignment.CenterVertically){Surface(shape=RoundedCornerShape(13.dp),color=(if(t.type=="درآمد") aqua else pink).copy(alpha=.15f)){Icon(if(t.type=="درآمد") Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,null,tint=if(t.type=="درآمد") aqua else pink,modifier=Modifier.padding(10.dp))};Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(t.category,color=fg,fontWeight=FontWeight.SemiBold);Text("${t.memberName} · ${t.accountName} · ${jalali(t.createdAt)}",color=sub,fontSize=10.sp);if(t.note.isNotBlank())Text(t.note,color=sub,fontSize=11.sp)};Text((if(t.type=="درآمد") "+" else "−")+money(t.amount),color=if(t.type=="درآمد") aqua else pink,fontSize=11.sp,fontWeight=FontWeight.Bold)};if(onEdit!=null&&onDelete!=null){if(t.category=="انتقال وجه"||t.category=="دریافت انتقال"){Text("برای حفظ تراز حساب‌ها، ویرایش و حذف انتقال غیرفعال است.",color=sub,fontSize=10.sp)}else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick=onEdit){Text("ویرایش")};TextButton(onClick=onDelete){Text("حذف",color=pink)}}}}
}
@Composable private fun TransactionsPage(p:PaddingValues,txs:List<FinanceTransaction>,fg:Color,sub:Color,dark:Boolean,onEdit:(FinanceTransaction)->Unit,onDelete:(FinanceTransaction)->Unit){
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("همه") }
    val filtered = txs.filter { t ->
        val matchesQuery = query.isBlank() || listOf(t.category,t.note,t.accountName,t.memberName,money(t.amount)).any { it.contains(query.trim(),ignoreCase=true) }
        val matchesType = filter=="همه" || t.type==filter
        matchesQuery && matchesType
    }
    Column(Modifier.fillMaxSize().padding(p).background(Brush.linearGradient(if(dark) listOf(Color(0xFF202E58),Color(0xFF111A36),Color(0xFF17172F)) else listOf(Color(0xFFE4E8FF),Color(0xFFF8F9FF),Color(0xFFECEEFF)))).padding(16.dp)){
        Text("تراکنش‌ها",color=fg,fontSize=23.sp,fontWeight=FontWeight.Bold)
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
    LazyColumn(Modifier.fillMaxSize().padding(p).background(Brush.linearGradient(if(dark) listOf(Color(0xFF202E58),Color(0xFF111A36),Color(0xFF17172F)) else listOf(Color(0xFFE4E8FF),Color(0xFFF8F9FF),Color(0xFFECEEFF)))).padding(horizontal=16.dp),contentPadding=PaddingValues(top=16.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(11.dp)){
        item { Text("حساب‌های خانواده",color=fg,fontSize=23.sp,fontWeight=FontWeight.Bold) }
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
    LazyColumn(Modifier.fillMaxSize().padding(p).background(Brush.linearGradient(if(dark) listOf(Color(0xFF202E58),Color(0xFF111A36),Color(0xFF17172F)) else listOf(Color(0xFFE4E8FF),Color(0xFFF8F9FF),Color(0xFFECEEFF)))).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text("گزارش مالی ماه",color=fg,fontSize=23.sp,fontWeight=FontWeight.Bold)}
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
@Composable private fun MorePage(p:PaddingValues,goals:List<SavingGoal>,fg:Color,sub:Color,dark:Boolean,onGoal:()->Unit,onContribute:(SavingGoal)->Unit,onDeleteGoal:(SavingGoal)->Unit,onTheme:()->Unit,onExport:()->Unit,onImport:()->Unit){
    LazyColumn(Modifier.fillMaxSize().padding(p).background(Brush.linearGradient(if(dark) listOf(Color(0xFF202E58),Color(0xFF111A36),Color(0xFF17172F)) else listOf(Color(0xFFE4E8FF),Color(0xFFF8F9FF),Color(0xFFECEEFF)))).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text("امکانات بیشتر",color=fg,fontSize=23.sp,fontWeight=FontWeight.Bold)}
        item{GlassCard(dark=dark){Text("تنظیمات ظاهری",color=fg,fontWeight=FontWeight.Bold);TextButton(onClick=onTheme){Text(if(dark)"فعال‌کردن پوسته روشن" else "فعال‌کردن پوسته تاریک")}}}
        item{GlassCard(dark=dark){Text("پشتیبان‌گیری و بازیابی",color=fg,fontWeight=FontWeight.Bold);Text("فایل پشتیبان را در حافظه گوشی ذخیره یا بازیابی کنید.",color=sub,fontSize=12.sp);Row{TextButton(onClick=onExport){Text("تهیه پشتیبان")};TextButton(onClick=onImport){Text("بازیابی")}}}}
        item{Row(verticalAlignment=Alignment.CenterVertically){Text("اهداف پس‌انداز",color=fg,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));TextButton(onClick=onGoal){Text("هدف جدید")}}}
        if(goals.isEmpty()) item{Text("هنوز هدفی ثبت نشده است.",color=sub)} else items(goals,key={it.id}){g->
            GlassCard(dark=dark){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(g.title,color=fg,fontWeight=FontWeight.Bold);Text(money(g.saved)+" از "+money(g.target),color=sub)};IconButton(onClick={onDeleteGoal(g)}){Icon(Icons.Default.Delete,null,tint=pink)}};LinearProgressIndicator(progress={(g.saved.toFloat()/g.target).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp));TextButton(onClick={onContribute(g)},enabled=g.saved<g.target){Text(if(g.saved<g.target)"افزودن مبلغ به پس‌انداز" else "هدف تکمیل شده است")}}
        }
    }
}

@Composable private fun DebtsPage(
    p:PaddingValues, debts:List<FamilyDebt>, installmentsByDebt:Map<Long,List<DebtInstallment>>,
    receivables:List<FamilyReceivable>, overdue:List<DebtInstallment>, upcoming:List<DebtInstallment>,
    totalDebt:Long, totalReceivables:Long, fg:Color, sub:Color, dark:Boolean,
    onPay:(DebtInstallment)->Unit, onDelete:(FamilyDebt)->Unit, onAddReceivable:()->Unit,
    onReceive:(FamilyReceivable)->Unit, onDeleteReceivable:(FamilyReceivable)->Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(p)
            .background(Brush.linearGradient(if(dark) listOf(Color(0xFF202E58),Color(0xFF111A36),Color(0xFF17172F)) else listOf(Color(0xFFE4E8FF),Color(0xFFF8F9FF),Color(0xFFECEEFF))))
            .padding(horizontal=16.dp),
        contentPadding=PaddingValues(top=18.dp,bottom=28.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)
    ) {
        item { Text("مدیریت بدهی‌ها و اقساط",color=fg,fontSize=22.sp,fontWeight=FontWeight.Bold) }
        item { Text("پرداخت هر قسط از حساب انتخابی ثبت می‌شود و موجودی و گزارش هزینه‌ها به‌روزرسانی خواهد شد.",color=sub,fontSize=12.sp) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { MetricCard("مانده اقساط",totalDebt,pink,sub,dark,Modifier.weight(1f)); MetricCard("طلب‌های دریافت‌نشده",totalReceivables,aqua,sub,dark,Modifier.weight(1f)) } }
        item { GlassCard(dark=dark) { Text("وضعیت سررسیدها",color=fg,fontWeight=FontWeight.Bold); Text("عقب‌افتاده: ${overdue.size} قسط",color=if(overdue.isEmpty())aqua else pink,fontWeight=FontWeight.SemiBold); Text("سررسید تا ۷ روز آینده: ${upcoming.size} قسط",color=if(upcoming.isEmpty())sub else Color(0xFFFFC56D)); if(overdue.isNotEmpty()) Text("مجموع اقساط عقب‌افتاده: ${money(overdue.sumOf{it.amount})}",color=pink,fontSize=12.sp); if(upcoming.isNotEmpty()) Text("مجموع اقساط نزدیک سررسید: ${money(upcoming.sumOf{it.amount})}",color=sub,fontSize=12.sp) } }
        item { Row(verticalAlignment=Alignment.CenterVertically) { Text("طلب از دیگران",color=fg,fontWeight=FontWeight.Bold); Spacer(Modifier.weight(1f)); TextButton(onClick=onAddReceivable){Text("ثبت طلب +")} } }
        if(receivables.isEmpty()) item { Text("طلبی ثبت نشده است.",color=sub,fontSize=12.sp) } else items(receivables,key={"r${it.id}"}) { item -> GlassCard(dark=dark) { Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(item.person,color=fg,fontWeight=FontWeight.Bold); Text("${money(item.amount)} · سررسید ${jalali(item.dueAt)}",color=sub,fontSize=11.sp); if(item.note.isNotBlank()) Text(item.note,color=sub,fontSize=10.sp); if(item.receivedAt!=null) Text("دریافت‌شده در ${jalali(item.receivedAt)}",color=aqua,fontSize=10.sp) }; if(item.receivedAt==null) { TextButton(onClick={onReceive(item)}){Text("دریافت شد")}; IconButton(onClick={onDeleteReceivable(item)}){Icon(Icons.Default.Delete,null,tint=pink)} } else Icon(Icons.Default.CheckCircle,null,tint=aqua) } } }
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
        containerColor=if(dark)panel else Color(0xFFF7F8FF),
        title={Text("ثبت بدهی جدید",color=Color.White)},
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

@Composable private fun ReceivableDialog(dark:Boolean,onDismiss:()->Unit,onSave:(String,Long,Long,String)->Boolean) {
    val context=LocalContext.current
    var person by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var due by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest=onDismiss,
        containerColor=if(dark)panel else Color(0xFFF7F8FF),
        title={Text("ثبت طلب جدید",color=Color.White)},
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
        containerColor=if(dark)panel else Color(0xFFF7F8FF),
        title={Text("پرداخت قسط ${installment.number}",color=Color.White)},
        text={GlassDialogBody(dark=dark) {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("مبلغ قسط: ${money(installment.amount)}",color=Color.White,fontWeight=FontWeight.Bold)
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

@Composable private fun TransactionDialog(accounts:List<FinanceAccount>,dark:Boolean,onDismiss:()->Unit,onSave:(Long,String,String,Long,String)->Boolean)=TransactionForm("ثبت تراکنش",null,accounts,dark,onDismiss,onSave)
@Composable private fun EditDialog(t:FinanceTransaction,accounts:List<FinanceAccount>,dark:Boolean,onDismiss:()->Unit,onSave:(Long,String,String,Long,String)->Boolean)=TransactionForm("ویرایش تراکنش",t,accounts,dark,onDismiss,onSave)
@Composable private fun TransactionForm(title:String,existing:FinanceTransaction?,accounts:List<FinanceAccount>,dark:Boolean,onDismiss:()->Unit,onSave:(Long,String,String,Long,String)->Boolean){
    var type by remember{mutableStateOf(existing?.type?:"هزینه")}
    var category by remember{mutableStateOf(existing?.category?:"خرید روزانه")}
    var amount by remember{mutableStateOf(existing?.amount?.let { formatAmount(it.toString()) }?:"")}
    var note by remember{mutableStateOf(existing?.note?:"")}
    var account by remember{mutableStateOf(existing?.accountId?:accounts.firstOrNull()?.id?:0L)}
    var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark) panel else Color(0xFFF7F8FF),title={Text(title,color=Color.White)},
        text={GlassDialogBody(dark=dark) {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Row { FilterChip(type=="درآمد",{type="درآمد"},{Text("درآمد")}); Spacer(Modifier.width(8.dp)); FilterChip(type=="هزینه",{type="هزینه"},{Text("هزینه")}) }
                Text("حساب",color=muted)
                accounts.forEach{a->FilterChip(account==a.id,{account=a.id},{Text("${a.memberName} · ${a.name}")})}
                OutText(amount,{amount=formatAmount(it);error=false},"مبلغ به تومان",numeric=true)
                OutText(category,{category=it;error=false},"دسته‌بندی")
                OutText(note,{note=it},"توضیحات (اختیاری)")
                if(error) Text("اطلاعات واردشده معتبر نیست یا موجودی کافی نیست.",color=pink)
            }
        }},
        confirmButton={TextButton(onClick={val v=parseAmount(amount);if(v!=null&&v>0&&accounts.any{it.id==account}&&onSave(account,type,category,v,note))onDismiss() else error=true}){Text("ذخیره")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun TransferDialog(accounts:List<FinanceAccount>,dark:Boolean,onDismiss:()->Unit,onSave:(Long,Long,Long,String)->Boolean){
    var from by remember{mutableStateOf(accounts.firstOrNull()?.id?:0L)}
    var to by remember{mutableStateOf(accounts.getOrNull(1)?.id?:0L)}
    var amount by remember{mutableStateOf("")}
    var note by remember{mutableStateOf("")}
    var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark) panel else Color(0xFFF7F8FF),title={Text("انتقال وجه بین حساب‌ها",color=Color.White)},
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
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark) panel else Color(0xFFF7F8FF),title={Text(title,color=Color.White)},
        text={GlassDialogBody(dark=dark) { Column { OutText(v,{v=it;error=false},label); if(error) Text("مقدار معتبر وارد کنید.",color=pink) } }},
        confirmButton={TextButton(onClick={if(onSave(v))onDismiss()else error=true}){Text("ذخیره")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun AccountDialog(members:List<FinanceMember>,dark:Boolean,onDismiss:()->Unit,onSave:(Long,String)->Boolean){
    var id by remember{mutableStateOf(members.firstOrNull()?.id?:0L)}
    var name by remember{mutableStateOf("")}
    var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark) panel else Color(0xFFF7F8FF),title={Text("افزودن حساب",color=Color.White)},
        text={GlassDialogBody(dark=dark) { Column { members.forEach{FilterChip(id==it.id,{id=it.id},{Text(it.name)})}; OutText(name,{name=it;error=false},"نام حساب"); if(error)Text("نام حساب و عضو را بررسی کنید.",color=pink) } }},
        confirmButton={TextButton(onClick={if(onSave(id,name))onDismiss()else error=true}){Text("ذخیره")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun BudgetDialog(dark:Boolean,onDismiss:()->Unit,onSave:(String,Long)->Boolean){
    var c by remember{mutableStateOf("خرید روزانه")}
    var a by remember{mutableStateOf("")}
    var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark) panel else Color(0xFFF7F8FF),title={Text("تعریف بودجه ماهانه",color=Color.White)},
        text={GlassDialogBody(dark=dark) { Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutText(c,{c=it;error=false},"دسته‌بندی");OutText(a,{a=formatAmount(it);error=false},"سقف بودجه به تومان",numeric=true);if(error)Text("دسته‌بندی و مبلغ معتبر وارد کنید.",color=pink)} }},
        confirmButton={TextButton(onClick={val v=parseAmount(a);if(v!=null&&v>0&&onSave(c,v))onDismiss()else error=true}){Text("ذخیره")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun GoalDialog(dark:Boolean,onDismiss:()->Unit,onSave:(String,Long)->Boolean){
    var t by remember{mutableStateOf("")}
    var a by remember{mutableStateOf("")}
    var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark) panel else Color(0xFFF7F8FF),title={Text("هدف پس‌انداز جدید",color=Color.White)},
        text={GlassDialogBody(dark=dark) { Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutText(t,{t=it;error=false},"عنوان هدف");OutText(a,{a=formatAmount(it);error=false},"مبلغ هدف به تومان",numeric=true);if(error)Text("عنوان هدف و مبلغ معتبر وارد کنید.",color=pink)} }},
        confirmButton={TextButton(onClick={val v=parseAmount(a);if(t.isNotBlank()&&v!=null&&v>0&&onSave(t.trim(),v))onDismiss()else error=true}){Text("ذخیره")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun GoalContributionDialog(goal:SavingGoal,dark:Boolean,onDismiss:()->Unit,onSave:(Long)->Boolean){
    var amount by remember{mutableStateOf("")}
    var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=if(dark) panel else Color(0xFFF7F8FF),title={Text("افزودن به هدف پس‌انداز",color=Color.White)},
        text={GlassDialogBody(dark=dark) { Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("هدف: ${goal.title}");Text("باقی‌مانده: ${money((goal.target-goal.saved).coerceAtLeast(0))}");OutText(amount,{amount=formatAmount(it);error=false},"مبلغ واریزی به تومان",numeric=true);if(error)Text("مبلغ معتبر وارد کنید و از مبلغ باقی‌مانده بیشتر نباشد.",color=pink)} }},
        confirmButton={TextButton(onClick={val v=parseAmount(amount);if(v!=null&&v>0&&v<=goal.target-goal.saved&&onSave(v))onDismiss()else error=true}){Text("ثبت پس‌انداز")}},
        dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun OutText(value:String,onChange:(String)->Unit,label:String,numeric:Boolean=false){OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=if(numeric)KeyboardType.Number else KeyboardType.Text))}
