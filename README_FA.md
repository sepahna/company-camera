# Company Camera

اپ اندرویدی آفلاین برای ثبت و آرشیو عکس‌های کاری.

## امکانات
- انتخاب دسته‌بندی «ماشین‌آلات» یا «نفرات» و ذخیره در پوشه‌های `MACHINERY` و `PERSONNEL`
- ثبت نام دلخواه برای عکس‌ها با پسوند JPG
- آرشیو بندانگشتی، نمایش بزرگ عکس و جست‌وجوی نام فایل
- پشتیبان‌گیری و بازیابی JSON و ZIP
- راهنمای درون‌برنامه‌ای شامل مسیر پوشه‌ها

## مسیر ذخیره‌سازی
عکس‌ها در فضای اختصاصی برنامه و در مسیر زیر ذخیره می‌شوند:
`Android/data/ir.mbarani.companycamera/files/Pictures/CompanyCamera/PHOTOS/`

پوشه‌های دسته‌بندی:
- `MACHINERY`
- `PERSONNEL`

پشتیبان‌های داخلی در:
`Android/data/ir.mbarani.companycamera/files/Pictures/CompanyCamera/BACKUPS/`

در زمان گرفتن خروجی JSON یا ZIP، علاوه بر نسخه داخلی، می‌توان محل دیگری را نیز از پنجره انتخاب فایل گوشی تعیین کرد.

## ساخت APK با GitHub Actions
Workflow در مسیر `.github/workflows/build-apk.yml` قرار دارد. با push کردن تغییرات به شاخه `main` یا اجرای دستی Workflow از تب Actions، فایل APK ساخته و به‌عنوان Artifact با نام `company-camera-debug-apk` منتشر می‌شود.

> این پروژه باید پس از هر تغییر روی دستگاه واقعی آزمایش شود. فایل ساخته‌شده از نوع Debug است.
