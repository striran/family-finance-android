$ErrorActionPreference = "Stop"

function Find-Keytool {
    $cmd = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\keytool.exe"
        if (Test-Path $candidate) { return $candidate }
    }
    throw "keytool.exe پیدا نشد. ابتدا JDK 17 را نصب کنید و JAVA_HOME را تنظیم کنید."
}

$keytool = Find-Keytool
$alias = Read-Host "نام مستعار کلید (مثلاً familyfinance)"
if ([string]::IsNullOrWhiteSpace($alias)) { throw "نام مستعار کلید نمی‌تواند خالی باشد." }

$securePassword = Read-Host "یک رمز قوی حداقل ۶ کاراکتری انتخاب کنید و در جای امن نگه دارید" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
if ($password.Length -lt 6) { throw "رمز باید حداقل ۶ کاراکتر باشد." }

$folder = Join-Path $HOME "FamilyFinanceSigning"
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$keystore = Join-Path $folder "family-finance-release.jks"
if (Test-Path $keystore) { throw "فایل کلید از قبل وجود دارد؛ برای جلوگیری از بازنویسی متوقف شد: $keystore" }

$env:FAMILY_FINANCE_KEY_PASSWORD = $password
try {
    & $keytool -genkeypair -v -keystore $keystore -alias $alias -keyalg RSA -keysize 4096 -validity 10000 -storepass:env FAMILY_FINANCE_KEY_PASSWORD -keypass:env FAMILY_FINANCE_KEY_PASSWORD -dname "CN=Family Finance, OU=Android, O=Personal, L=Ahvaz, ST=Khuzestan, C=IR"
    if ($LASTEXITCODE -ne 0) { throw "ساخت کلید امضا ناموفق بود." }
}
finally {
    Remove-Item Env:FAMILY_FINANCE_KEY_PASSWORD -ErrorAction SilentlyContinue
}

$bytes = [System.IO.File]::ReadAllBytes($keystore)
$base64 = [Convert]::ToBase64String($bytes)
Set-Clipboard -Value $base64

Write-Host ""
Write-Host "کلید امضا با موفقیت ساخته شد." -ForegroundColor Green
Write-Host "فایل کلید: $keystore"
Write-Host "نام مستعار (ANDROID_KEY_ALIAS): $alias"
Write-Host "رمز فروشگاه و کلید (ANDROID_KEYSTORE_PASSWORD و ANDROID_KEY_PASSWORD): همان رمزی است که وارد کردید."
Write-Host "مقدار ANDROID_KEYSTORE_BASE64 در حافظه موقت کپی شد؛ آن را فقط در GitHub Actions Secret قرار دهید."
Write-Host "از فایل JKS یک نسخه پشتیبان آفلاین و امن تهیه کنید. بدون این فایل و رمز آن، انتشار نسخه‌های قابل نصب روی نسخه قبلی ممکن نیست."
