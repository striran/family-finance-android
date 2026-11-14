package ir.striran.familyfinance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
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
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = FinanceDatabase(this)
        setContent { FinanceApp(database) }
    }
}

private val midnight = Color(0xFF10172F)
private val panel = Color(0xFF202B4D)
private val violet = Color(0xFF796BFA)
private val aqua = Color(0xFF55D6D0)
private val pink = Color(0xFFFF78B7)
private val muted = Color(0xFFB8C4E5)

private fun money(value: Long): String = DecimalFormat("#,###").format(value) + " تومان"

@Composable
fun FinanceApp(db: FinanceDatabase) {
    var page by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val balance = remember(refresh) { db.totalBalance() }
    val income = remember(refresh) { db.monthTotal("درآمد") }
    val expense = remember(refresh) { db.monthTotal("هزینه") }
    val transactions = remember(refresh) { db.recentTransactions() }
    val accounts = remember(refresh) { db.accounts() }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFC4BDFF), secondary = aqua, background = midnight, surface = panel
    )) {
        Scaffold(
            containerColor = midnight,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF171F3B), contentColor = Color.White) {
                    val labels = listOf("خانه", "تراکنش‌ها", "حساب‌ها", "گزارش")
                    val icons = listOf(Icons.Default.Home, Icons.Default.ReceiptLong,
                        Icons.Default.AccountBalanceWallet, Icons.Default.BarChart)
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = page == index,
                            onClick = { page = index },
                            icon = { Icon(icons[index], contentDescription = label) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = Color(0xFFC4BDFF),
                                indicatorColor = violet.copy(alpha = .35f),
                                unselectedIconColor = muted
                            )
                        )
                    }
                }
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showAdd = true },
                    containerColor = violet,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(20.dp)
                ) { Icon(Icons.Default.Add, contentDescription = "ثبت تراکنش") }
            }
        ) { padding ->
            when (page) {
                0 -> Dashboard(
                    padding, balance, income, expense, transactions,
                    onAdd = { showAdd = true }
                )
                1 -> TransactionsPage(padding, transactions)
                2 -> AccountsPage(padding, accounts)
                else -> ReportsPage(padding, income, expense)
            }
        }
        if (showAdd) {
            AddTransactionDialog(
                onDismiss = { showAdd = false },
                onSave = { type, category, amount, note ->
                    val saved = db.addTransaction(type, category, amount, note)
                    if (saved) refresh++
                    saved
                }
            )
        }
    }
}

@Composable
private fun Dashboard(
    padding: PaddingValues,
    balance: Long,
    income: Long,
    expense: Long,
    transactions: List<FinanceTransaction>,
    onAdd: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding)
            .background(Brush.verticalGradient(listOf(Color(0xFF202D58), midnight)))
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("سلام، خوش آمدید", color = muted, fontSize = 13.sp)
                    Text("مدیریت مالی خانواده", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                }
                Surface(shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = .10f)) {
                    Icon(Icons.Default.Settings, null, tint = Color.White, modifier = Modifier.padding(12.dp))
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Box(
                    Modifier.background(Brush.linearGradient(listOf(Color(0xFF7667F5), Color(0xFF4A4BAE), Color(0xFF276D9B))))
                        .padding(23.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        Text("موجودی کل خانواده", color = Color.White.copy(alpha = .85f), fontSize = 14.sp)
                        Text(money(balance), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Color.White.copy(alpha = .17f), shape = RoundedCornerShape(30.dp)) {
                                Text("  نمای کلی حساب‌ها  ", color = Color.White, modifier = Modifier.padding(vertical = 6.dp), fontSize = 11.sp)
                            }
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Default.AccountBalanceWallet, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(34.dp))
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("درآمد این ماه", income, aqua, Icons.Default.ArrowDownward, Modifier.weight(1f))
                MetricCard("هزینه این ماه", expense, pink, Icons.Default.ArrowUpward, Modifier.weight(1f))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("دسترسی سریع", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("افزودن تراکنش", color = Color(0xFFC4BDFF), fontSize = 12.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile("ثبت درآمد", aqua, Modifier.weight(1f), onAdd)
                QuickTile("ثبت هزینه", pink, Modifier.weight(1f), onAdd)
                QuickTile("تراکنش جدید", Color(0xFFB39BFF), Modifier.weight(1f), onAdd)
            }
        }
        item {
            Text("آخرین تراکنش‌ها", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        if (transactions.isEmpty()) {
            item {
                GlassCard {
                    Text("هنوز تراکنشی ثبت نشده است.", color = muted)
                    Spacer(Modifier.height(5.dp))
                    Text("با دکمه + اولین درآمد یا هزینه را وارد کنید.", color = muted, fontSize = 12.sp)
                }
            }
        } else {
            items(transactions.take(5), key = { it.id }) { TransactionRow(it) }
        }
    }
}

@Composable
private fun MetricCard(
    title: String, value: Long, tint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier
) {
    GlassCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, color = muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(money(value), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun QuickTile(label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.height(82.dp),
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .08f))
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Add, null, tint = tint)
            Spacer(Modifier.height(5.dp))
            Text(label, color = Color.White, fontSize = 11.sp)
        }
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(23.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .075f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .10f))
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun TransactionRow(item: FinanceTransaction) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = (if (item.type == "درآمد") aqua else pink).copy(alpha = .16f)
            ) {
                Icon(
                    if (item.type == "درآمد") Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                    null,
                    tint = if (item.type == "درآمد") aqua else pink,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.category, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(item.note.ifBlank { item.accountName }, color = muted, fontSize = 11.sp)
            }
            Text(
                (if (item.type == "درآمد") "+" else "−") + money(item.amount),
                color = if (item.type == "درآمد") aqua else pink,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun TransactionsPage(padding: PaddingValues, items: List<FinanceTransaction>) {
    Column(Modifier.fillMaxSize().padding(padding).background(midnight).padding(18.dp)) {
        Text("تراکنش‌ها", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        if (items.isEmpty()) Text("تراکنشی برای نمایش وجود ندارد.", color = muted)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { TransactionRow(it) }
        }
    }
}

@Composable
private fun AccountsPage(padding: PaddingValues, accounts: List<Pair<String, Long>>) {
    Column(Modifier.fillMaxSize().padding(padding).background(midnight).padding(18.dp)) {
        Text("حساب‌های خانواده", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        accounts.forEachIndexed { index, account ->
            GlassCard {
                Text(if (index == 0) "حساب شخصی من" else "حساب همسر", color = muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text(account.first, color = Color.White, fontWeight = FontWeight.Bold)
                Text(money(account.second), color = aqua, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
        }
        Text("حساب‌های اولیه به‌صورت خودکار ایجاد شده‌اند.", color = muted, fontSize = 12.sp)
    }
}

@Composable
private fun ReportsPage(padding: PaddingValues, income: Long, expense: Long) {
    Column(Modifier.fillMaxSize().padding(padding).background(midnight).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("گزارش مالی", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        GlassCard {
            Text("جمع درآمد ثبت‌شده", color = muted)
            Text(money(income), color = aqua, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        GlassCard {
            Text("جمع هزینه ثبت‌شده", color = muted)
            Text(money(expense), color = pink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        GlassCard {
            Text("مانده خالص این ماه", color = muted)
            Text(money(income - expense), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AddTransactionDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, Long, String) -> Boolean
) {
    var type by remember { mutableStateOf("هزینه") }
    var category by remember { mutableStateOf("خرید روزانه") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF202B4D),
        titleContentColor = Color.White,
        textContentColor = muted,
        title = { Text("ثبت تراکنش جدید", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == "درآمد", onClick = { type = "درآمد" }, label = { Text("درآمد") })
                    FilterChip(selected = type == "هزینه", onClick = { type = "هزینه" }, label = { Text("هزینه") })
                }
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter(Char::isDigit); error = false },
                    label = { Text("مبلغ به تومان") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(value = category, onValueChange = { category = it },
                    label = { Text("دسته‌بندی") }, singleLine = true)
                OutlinedTextField(value = note, onValueChange = { note = it },
                    label = { Text("توضیحات (اختیاری)") }, singleLine = true)
                if (error) Text("لطفاً مبلغ معتبر و بیشتر از صفر وارد کنید.", color = pink, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = amount.toLongOrNull()
                if (value == null || value <= 0 || category.isBlank()) error = true
                else if (onSave(type, category.trim(), value, note)) onDismiss()
                else error = true
            }) { Text("ذخیره", color = Color(0xFFC4BDFF)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف", color = muted) } }
    )
}
