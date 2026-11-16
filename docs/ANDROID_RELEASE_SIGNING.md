# راه‌اندازی امضای دائمی نسخه Android

این مخزن عمومی است؛ فایل JKS یا رمز آن را هرگز در مخزن، Issue، پیام یا فایل قابل‌دسترسی عمومی قرار ندهید.

## ساخت کلید روی Windows

1. JDK 17 را نصب کنید و مطمئن شوید دستور `keytool` در PowerShell قابل اجراست.
2. فایل `scripts/create-release-keystore.ps1` را از مخزن دریافت کنید.
3. در PowerShell به پوشه فایل بروید و اجرا کنید:
   `powershell -ExecutionPolicy Bypass -File .\create-release-keystore.ps1`
4. یک alias و رمز قوی انتخاب کنید. رمز را در Password Manager یا محل امن آفلاین نگه دارید.
5. فایل ساخته‌شده در `%USERPROFILE%\FamilyFinanceSigning\family-finance-release.jks` را حداقل در دو محل امن و آفلاین پشتیبان‌گیری کنید. فایل JKS را حذف یا گم نکنید.
6. مقدار Base64 کلید به‌صورت خودکار در Clipboard کپی می‌شود.

## ثبت GitHub Secrets

در GitHub مخزن `striran/family-finance-android` به مسیر زیر بروید:

**Settings → Secrets and variables → Actions → New repository secret**

این چهار Secret را جداگانه بسازید:

| نام Secret | مقدار |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | مقدار Base64 کپی‌شده از اسکریپت |
| `ANDROID_KEYSTORE_PASSWORD` | رمزی که هنگام ساخت کلید وارد کردید |
| `ANDROID_KEY_ALIAS` | alias واردشده |
| `ANDROID_KEY_PASSWORD` | همان رمز کلید |

پس از ثبت چهار Secret، یک commit جدید یا اجرای دستی workflow باعث ساخت و اعتبارسنجی APK امضاشده می‌شود. از این پس نسخه‌های Debug و Release با همان کلید دائمی امضا می‌شوند؛ بنابراین نصب نسخه جدید روی نسخه قبلی بدون حذف برنامه امکان‌پذیر خواهد بود. اگر نسخه Debug قبلی با کلید موقت GitHub ساخته شده باشد، فقط یک بار باید آن را حذف و نسخه امضاشده با کلید دائمی را نصب کنید.

**هشدار:** کلید امضا هویت انتشار برنامه است. با گم‌شدن آن، امکان ساخت نسخه به‌روزرسانی‌شونده با همان امضا از بین می‌رود. فایل JKS و رمز آن را در اختیار دیگران قرار ندهید.
