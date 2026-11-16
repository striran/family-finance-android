package ir.striran.familyfinance

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
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
                if (uri != null) runCatching {
                    val source = getDatabasePath("family_finance.db")
                    contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                }
            }
            val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) runCatching {
                    activeDb.close()
                    contentResolver.openInputStream(uri)?.use { input -> getDatabasePath("family_finance.db").outputStream().use { input.copyTo(it) } }
                    activeDb = FinanceDatabase(this)
                    db = activeDb
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
private fun normalizeDigits(value: String): String = buildString {
    value.forEach { ch ->
        when (ch) {
            in '۰'..'۹' -> append(('0'.code + (ch.code - '۰'.code)).toChar())
            in '٠'..'٩' -> append(('0'.code + (ch.code - '٠'.code)).toChar())
            else -> if (ch in '0'..'9') append(ch)
        }
    }
}
private fun formatAmount(value: String): String =
    normalizeDigits(value).reversed().chunked(3).joinToString(",").reversed()
private fun parseAmount(value: String): Long? = normalizeDigits(value).toLongOrNull()
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
    var dark by remember { mutableStateOf(true) }
    val balance = remember(refresh) { db.totalBalance() }
    val income = remember(refresh) { db.monthTotal("درآمد") }
    val expense = remember(refresh) { db.monthTotal("هزینه") }
    val txs = remember(refresh) { db.allTransactions() }
    val accounts = remember(refresh) { db.accounts() }
    val budgets = remember(refresh) { db.budgets() }
    val goals = remember(refresh) { db.goals() }
    val bg = if(dark) midnight else Color(0xFFF1F3FC)
    val fg = if(dark) Color.White else Color(0xFF1D2340)
    val sub = if(dark) muted else Color(0xFF626B88)
    MaterialTheme(colorScheme = if(dark) darkColorScheme(primary=Color(0xFFC4BDFF),secondary=aqua,background=midnight,surface=panel) else lightColorScheme(primary=violet,secondary=Color(0xFF168F8A))) {
        Scaffold(containerColor=bg, bottomBar={
            NavigationBar(modifier=Modifier.padding(horizontal=12.dp, vertical=7.dp).clip(RoundedCornerShape(26.dp)).border(1.dp,if(dark)Color.White.copy(alpha=.16f) else Color(0xFFDDE2F3),RoundedCornerShape(26.dp)),containerColor=if(dark) Color(0xFF273252).copy(alpha=.94f) else Color.White.copy(alpha=.96f),tonalElevation=0.dp) {
                val labels=listOf("خانه","تراکنش","حساب‌ها","گزارش","بیشتر")
                val icons=listOf(Icons.Default.Home,Icons.Default.ReceiptLong,Icons.Default.AccountBalanceWallet,Icons.Default.BarChart,Icons.Default.MoreHoriz)
                labels.forEachIndexed { i,label -> NavigationBarItem(selected=page==i,onClick={page=i},icon={Icon(icons[i],null)},label={Text(label,fontSize=10.sp)}) }
            }
        }, floatingActionButton={ if(page==0||page==1) FloatingActionButton(onClick={dialog="transaction"},containerColor=violet,contentColor=Color.White){Icon(Icons.Default.Add,null)} }) { pad ->
            when(page) {
                0 -> Dashboard(pad,balance,income,expense,txs.take(5),fg,sub,dark,{dialog="transaction"},{dialog="transfer"})
                1 -> TransactionsPage(pad,txs,fg,sub,dark,{selected=it;dialog="edit"},{selected=it;dialog="delete"})
                2 -> AccountsPage(pad,accounts,fg,sub,dark,{dialog="member"},{dialog="account"},{dialog="transfer"})
                3 -> ReportsPage(pad,income,expense,db.categoryTotals(),budgets,fg,sub,dark,{dialog="budget"})
                else -> MorePage(pad,goals,fg,sub,dark,{dialog="goal"},{ goal -> selectedGoal=goal; dialog="contribute" },{dark=!dark},onExport,onImport)
            }
        }
        when(dialog) {
            "transaction" -> TransactionDialog(accounts,onDismiss={dialog=""},onSave={a,t,c,v,n->db.addTransaction(a,t,c,v,n).also{if(it)refresh++}})
            "edit" -> selected?.let { EditDialog(it,accounts,onDismiss={dialog="";selected=null},onSave={a,t,c,v,n->db.updateTransaction(it.id,a,t,c,v,n).also{ok->if(ok)refresh++}}) }
            "delete" -> selected?.let { AlertDialog(onDismissRequest={dialog="";selected=null},title={Text("حذف تراکنش")},text={Text("این تراکنش حذف و موجودی حساب اصلاح شود؟")},confirmButton={TextButton(onClick={db.deleteTransaction(it.id);refresh++;dialog="";selected=null}){Text("حذف")}},dismissButton={TextButton(onClick={dialog="";selected=null}){Text("انصراف")}}) }
            "transfer" -> TransferDialog(accounts,onDismiss={dialog=""},onSave={a,b,v,n->db.transfer(a,b,v,n).also{if(it)refresh++}})
            "member" -> SimpleNameDialog("افزودن عضو خانواده","نام عضو",onDismiss={dialog=""},onSave={db.addMember(it).also{ok->if(ok)refresh++}})
            "account" -> AccountDialog(db.members(),onDismiss={dialog=""},onSave={a,n->db.addAccount(a,n).also{if(it)refresh++}})
            "budget" -> BudgetDialog(onDismiss={dialog=""},onSave={c,v->db.saveBudget(c,v).also{if(it)refresh++}})
            "goal" -> GoalDialog(onDismiss={dialog=""},onSave={t,v->db.addGoal(t,v).also{if(it)refresh++}})
            "contribute" -> selectedGoal?.let { goal -> GoalContributionDialog(goal,onDismiss={dialog="";selectedGoal=null},onSave={amount->db.contributeGoal(goal.id,amount).also{if(it)refresh++}}) }
        }
    }
}

@Composable private fun GlassCard(modifier:Modifier=Modifier,dark:Boolean=true,content:@Composable ColumnScope.()->Unit){
    val glassBrush = if(dark) Brush.linearGradient(listOf(Color.White.copy(alpha=.14f),Color.White.copy(alpha=.045f),Color(0xFF8D82FF).copy(alpha=.08f))) else Brush.linearGradient(listOf(Color.White.copy(alpha=.98f),Color(0xFFEFF1FF).copy(alpha=.82f)))
    Card(modifier=modifier.fillMaxWidth(),shape=RoundedCornerShape(25.dp),colors=CardDefaults.cardColors(containerColor=Color.Transparent),border=BorderStroke(1.dp,if(dark) Color.White.copy(alpha=.22f) else Color.White),elevation=CardDefaults.cardElevation(defaultElevation=if(dark) 5.dp else 2.dp),content={Column(Modifier.fillMaxWidth().background(glassBrush).padding(16.dp),content=content)})
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
    GlassCard(dark=dark){Row(verticalAlignment=Alignment.CenterVertically){Surface(shape=RoundedCornerShape(13.dp),color=(if(t.type=="درآمد") aqua else pink).copy(alpha=.15f)){Icon(if(t.type=="درآمد") Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,null,tint=if(t.type=="درآمد") aqua else pink,modifier=Modifier.padding(10.dp))};Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(t.category,color=fg,fontWeight=FontWeight.SemiBold);Text("${t.memberName} · ${t.accountName} · ${jalali(t.createdAt)}",color=sub,fontSize=10.sp);if(t.note.isNotBlank())Text(t.note,color=sub,fontSize=11.sp)};Text((if(t.type=="درآمد") "+" else "−")+money(t.amount),color=if(t.type=="درآمد") aqua else pink,fontSize=11.sp,fontWeight=FontWeight.Bold)};if(onEdit!=null&&onDelete!=null){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick=onEdit){Text("ویرایش")};TextButton(onClick=onDelete){Text("حذف",color=pink)}}}}
}
@Composable private fun TransactionsPage(p:PaddingValues,txs:List<FinanceTransaction>,fg:Color,sub:Color,dark:Boolean,onEdit:(FinanceTransaction)->Unit,onDelete:(FinanceTransaction)->Unit){
    Column(Modifier.fillMaxSize().padding(p).background(if(dark)midnight else Color(0xFFF1F3FC)).padding(16.dp)){Text("تراکنش‌ها",color=fg,fontSize=23.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(12.dp));if(txs.isEmpty())Text("تراکنشی برای نمایش وجود ندارد.",color=sub) else LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp)){items(txs,key={it.id}){TxRow(it,fg,sub,dark,{onEdit(it)},{onDelete(it)})}}}
}
@Composable private fun AccountsPage(p:PaddingValues,accounts:List<FinanceAccount>,fg:Color,sub:Color,dark:Boolean,onMember:()->Unit,onAccount:()->Unit,onTransfer:()->Unit){
    Column(Modifier.fillMaxSize().padding(p).background(if(dark)midnight else Color(0xFFF1F3FC)).padding(16.dp)){Text("حساب‌های خانواده",color=fg,fontSize=23.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(12.dp));Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){Button(onClick=onMember){Text("عضو جدید")};OutlinedButton(onClick=onAccount){Text("حساب جدید")};OutlinedButton(onClick=onTransfer){Text("انتقال")}};Spacer(Modifier.height(12.dp));accounts.forEach{a->GlassCard(dark=dark){Text(a.memberName,color=sub,fontSize=12.sp);Text(a.name,color=fg,fontWeight=FontWeight.Bold);Text(money(a.balance),color=aqua,fontSize=19.sp,fontWeight=FontWeight.Bold)}}}
}
@Composable private fun ReportsPage(p:PaddingValues,income:Long,expense:Long,cats:List<Pair<String,Long>>,budgets:List<Budget>,fg:Color,sub:Color,dark:Boolean,onBudget:()->Unit){
    LazyColumn(Modifier.fillMaxSize().padding(p).background(if(dark)midnight else Color(0xFFF1F3FC)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text("گزارش مالی ماه",color=fg,fontSize=23.sp,fontWeight=FontWeight.Bold)};item{GlassCard(dark=dark){Text("درآمد",color=sub);Text(money(income),color=aqua,fontSize=20.sp,fontWeight=FontWeight.Bold)}};item{GlassCard(dark=dark){Text("هزینه",color=sub);Text(money(expense),color=pink,fontSize=20.sp,fontWeight=FontWeight.Bold)}};item{GlassCard(dark=dark){Text("مانده خالص",color=sub);Text(money(income-expense),color=fg,fontSize=22.sp,fontWeight=FontWeight.Bold)}};item{Row(verticalAlignment=Alignment.CenterVertically){Text("هزینه بر اساس دسته",color=fg,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));TextButton(onClick=onBudget){Text("بودجه جدید")}}};if(cats.isEmpty())item{Text("هنوز هزینه‌ای ثبت نشده است.",color=sub)} else items(cats){(name,value)->GlassCard(dark=dark){Row{Text(name,color=fg);Spacer(Modifier.weight(1f));Text(money(value),color=pink,fontWeight=FontWeight.Bold)};LinearProgressIndicator(progress={if(expense>0)value.toFloat()/expense else 0f},modifier=Modifier.fillMaxWidth().padding(top=8.dp))}};item{Text("بودجه‌بندی ماهانه",color=fg,fontWeight=FontWeight.Bold)};if(budgets.isEmpty())item{Text("بودجه‌ای تعریف نشده است.",color=sub)} else items(budgets){b->GlassCard(dark=dark){Text(b.category,color=fg,fontWeight=FontWeight.Bold);Text("مصرف‌شده: ${money(b.spent)} از ${money(b.limitAmount)}",color=sub,fontSize=12.sp);LinearProgressIndicator(progress={ (b.spent.toFloat()/b.limitAmount).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(top=8.dp),color=if(b.spent>b.limitAmount)pink else aqua)}}}
}
@Composable private fun MorePage(p:PaddingValues,goals:List<SavingGoal>,fg:Color,sub:Color,dark:Boolean,onGoal:()->Unit,onContribute:(SavingGoal)->Unit,onTheme:()->Unit,onExport:()->Unit,onImport:()->Unit){
    LazyColumn(Modifier.fillMaxSize().padding(p).background(if(dark)midnight else Color(0xFFF1F3FC)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text("امکانات بیشتر",color=fg,fontSize=23.sp,fontWeight=FontWeight.Bold)}
        item{GlassCard(dark=dark){Text("تنظیمات ظاهری",color=fg,fontWeight=FontWeight.Bold);TextButton(onClick=onTheme){Text(if(dark)"فعال‌کردن پوسته روشن" else "فعال‌کردن پوسته تاریک")}}}
        item{GlassCard(dark=dark){Text("پشتیبان‌گیری و بازیابی",color=fg,fontWeight=FontWeight.Bold);Text("فایل پشتیبان را در حافظه گوشی ذخیره یا بازیابی کنید.",color=sub,fontSize=12.sp);Row{TextButton(onClick=onExport){Text("تهیه پشتیبان")};TextButton(onClick=onImport){Text("بازیابی")}}}}
        item{Row(verticalAlignment=Alignment.CenterVertically){Text("اهداف پس‌انداز",color=fg,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));TextButton(onClick=onGoal){Text("هدف جدید")}}}
        if(goals.isEmpty()) item{Text("هنوز هدفی ثبت نشده است.",color=sub)} else items(goals,key={it.id}){g->
            GlassCard(dark=dark){Text(g.title,color=fg,fontWeight=FontWeight.Bold);Text("${money(g.saved)} از ${money(g.target)}",color=sub);LinearProgressIndicator(progress={(g.saved.toFloat()/g.target).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp));TextButton(onClick={onContribute(g)},enabled=g.saved<g.target){Text(if(g.saved<g.target)"افزودن مبلغ به پس‌انداز" else "هدف تکمیل شده است")}}
        }
    }
}
@Composable private fun TransactionDialog(accounts:List<FinanceAccount>,onDismiss:()->Unit,onSave:(Long,String,String,Long,String)->Boolean)=TransactionForm("ثبت تراکنش",null,accounts,onDismiss,onSave)
@Composable private fun EditDialog(t:FinanceTransaction,accounts:List<FinanceAccount>,onDismiss:()->Unit,onSave:(Long,String,String,Long,String)->Boolean)=TransactionForm("ویرایش تراکنش",t,accounts,onDismiss,onSave)
@Composable private fun TransactionForm(title:String,existing:FinanceTransaction?,accounts:List<FinanceAccount>,onDismiss:()->Unit,onSave:(Long,String,String,Long,String)->Boolean){
    var type by remember{mutableStateOf(existing?.type?:"هزینه")};var category by remember{mutableStateOf(existing?.category?:"خرید روزانه")};var amount by remember{mutableStateOf(existing?.amount?.let { formatAmount(it.toString()) }?:"")};var note by remember{mutableStateOf(existing?.note?:"")};var account by remember{mutableStateOf(existing?.accountId?:accounts.firstOrNull()?.id?:0L)};var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=panel,title={Text(title,color=Color.White)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Row{FilterChip(type=="درآمد",{type="درآمد"},{Text("درآمد")});Spacer(Modifier.width(8.dp));FilterChip(type=="هزینه",{type="هزینه"},{Text("هزینه")})};Text("حساب",color=muted);accounts.forEach{a->FilterChip(account==a.id,{account=a.id},{Text("${a.memberName} · ${a.name}")})};OutText(amount,{amount=formatAmount(it);error=false},"مبلغ به تومان",numeric=true);OutText(category,{category=it},"دسته‌بندی");OutText(note,{note=it},"توضیحات (اختیاری)");if(error)Text("اطلاعات واردشده معتبر نیست.",color=pink)}},confirmButton={TextButton(onClick={val v=parseAmount(amount);if(v!=null&&v>0&&accounts.any{it.id==account}&&onSave(account,type,category,v,note))onDismiss() else error=true}){Text("ذخیره")}},dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun TransferDialog(accounts:List<FinanceAccount>,onDismiss:()->Unit,onSave:(Long,Long,Long,String)->Boolean){
    var from by remember{mutableStateOf(accounts.firstOrNull()?.id?:0L)};var to by remember{mutableStateOf(accounts.getOrNull(1)?.id?:0L)};var amount by remember{mutableStateOf("")};var note by remember{mutableStateOf("")};var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=panel,title={Text("انتقال وجه بین حساب‌ها",color=Color.White)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("حساب مبدأ",color=muted);accounts.forEach{a->FilterChip(from==a.id,{from=a.id},{Text("${a.memberName} · ${a.name}")})};Text("حساب مقصد",color=muted);accounts.forEach{a->FilterChip(to==a.id,{to=a.id},{Text("${a.memberName} · ${a.name}")})};OutText(amount,{amount=formatAmount(it)},"مبلغ انتقال",numeric=true);OutText(note,{note=it},"توضیحات");if(error)Text("موجودی کافی نیست یا اطلاعات معتبر نیست.",color=pink)}},confirmButton={TextButton(onClick={val v=parseAmount(amount);if(v!=null&&onSave(from,to,v,note))onDismiss()else error=true}){Text("انتقال")}},dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun SimpleNameDialog(title:String,label:String,onDismiss:()->Unit,onSave:(String)->Boolean){var v by remember{mutableStateOf("")};AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={OutText(v,{v=it},label)},confirmButton={TextButton(onClick={if(onSave(v))onDismiss()}){Text("ذخیره")}},dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})}
@Composable private fun AccountDialog(members:List<FinanceMember>,onDismiss:()->Unit,onSave:(Long,String)->Boolean){var id by remember{mutableStateOf(members.firstOrNull()?.id?:0L)};var name by remember{mutableStateOf("")};AlertDialog(onDismissRequest=onDismiss,title={Text("افزودن حساب")},text={Column{members.forEach{FilterChip(id==it.id,{id=it.id},{Text(it.name)})};OutText(name,{name=it},"نام حساب")}},confirmButton={TextButton(onClick={if(onSave(id,name))onDismiss()}){Text("ذخیره")}},dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})}
@Composable private fun BudgetDialog(onDismiss:()->Unit,onSave:(String,Long)->Boolean){
    var c by remember{mutableStateOf("خرید روزانه")}
    var a by remember{mutableStateOf("")}
    var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("تعریف بودجه ماهانه")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutText(c,{c=it;error=false},"دسته‌بندی");OutText(a,{a=formatAmount(it);error=false},"سقف بودجه به تومان",numeric=true);if(error)Text("دسته‌بندی و مبلغ معتبر وارد کنید.",color=pink)}},confirmButton={TextButton(onClick={val v=parseAmount(a);if(v!=null&&v>0&&onSave(c,v))onDismiss()else error=true}){Text("ذخیره")}},dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun GoalDialog(onDismiss:()->Unit,onSave:(String,Long)->Boolean){
    var t by remember{mutableStateOf("")}
    var a by remember{mutableStateOf("")}
    var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("هدف پس‌انداز جدید")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutText(t,{t=it;error=false},"عنوان هدف");OutText(a,{a=formatAmount(it);error=false},"مبلغ هدف به تومان",numeric=true);if(error)Text("عنوان هدف و مبلغ معتبر وارد کنید.",color=pink)}},confirmButton={TextButton(onClick={val v=parseAmount(a);if(t.isNotBlank()&&v!=null&&v>0&&onSave(t.trim(),v))onDismiss()else error=true}){Text("ذخیره")}},dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun GoalContributionDialog(goal:SavingGoal,onDismiss:()->Unit,onSave:(Long)->Boolean){
    var amount by remember{mutableStateOf("")}
    var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("افزودن به هدف پس‌انداز")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("هدف: ${goal.title}");Text("باقی‌مانده: ${money((goal.target-goal.saved).coerceAtLeast(0))}");OutText(amount,{amount=formatAmount(it);error=false},"مبلغ واریزی به تومان",numeric=true);if(error)Text("مبلغ معتبر وارد کنید و از مبلغ باقی‌مانده بیشتر نباشد.",color=pink)}},confirmButton={TextButton(onClick={val v=parseAmount(amount);if(v!=null&&v>0&&v<=goal.target-goal.saved&&onSave(v))onDismiss()else error=true}){Text("ثبت پس‌انداز")}},dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}})
}
@Composable private fun OutText(value:String,onChange:(String)->Unit,label:String,numeric:Boolean=false){OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=if(numeric)KeyboardType.Number else KeyboardType.Text))}
