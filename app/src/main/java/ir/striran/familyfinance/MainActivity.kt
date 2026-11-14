package ir.striran.familyfinance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FamilyFinanceApp() }
    }
}

@Composable
fun FamilyFinanceApp() {
    var selected by remember { mutableIntStateOf(0) }
    val navy = Color(0xFF111A36)
    val purple = Color(0xFF7667F5)
    val cyan = Color(0xFF65D6E8)

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFB6AEFF),
            secondary = cyan,
            background = navy,
            surface = Color(0xFF202A4B)
        )
    ) {
        Scaffold(
            containerColor = navy,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF182342)) {
                    val items = listOf("خانه", "تراکنش‌ها", "گزارش‌ها", "تنظیمات")
                    val icons = listOf(Icons.Default.Home, Icons.Default.AccountBalanceWallet,
                        Icons.Default.BarChart, Icons.Default.Settings)
                    items.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = { Icon(icons[index], contentDescription = label) },
                            label = { Text(label) }
                        )
                    }
                }
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { },
                    containerColor = purple,
                    contentColor = Color.White
                ) { Icon(Icons.Default.Add, contentDescription = "افزودن تراکنش") }
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding)
                    .background(Brush.verticalGradient(listOf(Color(0xFF202C55), navy)))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text("مدیریت مالی خانواده", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("نمای کلی وضعیت مالی شما", color = Color(0xFFB8C3E2))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF5147A8))
                ) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("موجودی کل خانواده", color = Color(0xFFE0DEFF))
                        Text("۰ تومان", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("برای شروع، اولین حساب یا تراکنش را اضافه کنید", color = Color(0xFFD8D5FF), fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryCard("درآمد این ماه", "۰ تومان", Modifier.weight(1f), Color(0xFF1C665F))
                    SummaryCard("هزینه این ماه", "۰ تومان", Modifier.weight(1f), Color(0xFF75435C))
                }
                Text("دسترسی سریع", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickAction("ثبت درآمد", Modifier.weight(1f))
                    QuickAction("ثبت هزینه", Modifier.weight(1f))
                    QuickAction("حساب‌ها", Modifier.weight(1f))
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF202A4B)),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("آخرین تراکنش‌ها", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text("هنوز تراکنشی ثبت نشده است.", color = Color(0xFFB8C3E2))
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, value: String, modifier: Modifier, tint: Color) {
    Card(modifier, shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = tint)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Color.White.copy(alpha = .85f), fontSize = 13.sp)
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
private fun QuickAction(label: String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF303D68))) {
        Box(Modifier.padding(vertical = 18.dp, horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
