# Xp Pen Tablet Mobile

> **Неофициальный проект.** Не связан с XPPEN Technology Co. (XP-Pen), не одобрен и не поддерживается ею.
> XP-Pen, XPPen, Star G640 и Deco 640 — товарные знаки их владельцев и упоминаются только для указания совместимости.
> Используйте на свой страх и риск.

Драйвер графического планшета **XP-Pen Star G640** для Android **без root**: исправляет то, как Android
работает с планшетом через OTG. Сохраняет пропорции, выбирает рабочую область, поворачивает её
вслед за экраном и задаёт кривую нажима. Работает во всех приложениях.

*English summary below.*

## Зачем

Android сам видит G640 как перо, но растягивает весь планшет (160×100 мм, горизонтальный) на экран
телефона (например, 1080×2340, вертикальный). По горизонтали масштаб получается в 3,5 раза меньше,
чем по вертикали, и рисунок искажается.

## Возможности

- Рабочая область: по пропорциям экрана (без искажений), весь планшет или своя область (рамку можно
  двигать и менять пальцем).
- Поворот планшета 0/90/180/270° и отдельные настройки для вертикального и горизонтального экрана.
  Можно следовать за поворотом экрана или нет.
- Кривая нажима: мягче/жёстче, порог срабатывания, уровень полного нажима.
- Нажим, наведение и кнопки пера передаются как у обычного стилуса.
- Поле для проверки в приложении и показ положения пера на схеме планшета.

## Что нужно

- Телефон на Android 11 или новее с поддержкой OTG (проверено: Samsung Galaxy S21 FE, Android 16).
- Компьютер с [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) (`adb`)
  — только для запуска драйвера.
- Планшет XP-Pen Star G640. Другие USB-планшеты, которые Android видит как перо, тоже должны работать,
  но не проверялись.

## Установка и запуск

1. Скачай `XpPenTabletMobile.apk` из [Releases](../../releases) и положи рядом с `start.bat` и `start.ps1`
   (или установи приложение на телефон вручную).
2. На телефоне включи «Параметры разработчика» → «Отладка по Wi-Fi». Если телефон ещё не подключался
   к этому ПК: «Подключить устройство с помощью кода», затем на ПК `adb pair IP:ПОРТ` и введи код.
   USB-отладка тоже подходит, но порт обычно занят планшетом.
3. Подключи планшет к телефону через OTG-переходник.
4. На ПК запусти `start.bat`. Он найдёт телефон по Wi-Fi, при необходимости установит приложение
   и запустит драйвер. После этого компьютер не нужен.
5. Настройки — в приложении **Xp Pen Tablet Mobile** на телефоне, применяются сразу.

После перезагрузки телефона драйвер нужно запустить снова (`start.bat`). `stop.bat` останавливает
драйвер, а выключатель «Коррекция включена» в приложении временно отдаёт планшет системе.

Запуск без скрипта, с любого устройства с adb:

```
adb shell sh /sdcard/Android/data/com.neon.xppen/files/start.sh
```

## Как это устроено

Обычное приложение Android не может управлять вводом в другие приложения, поэтому драйвер
(`app/src/main/java/com/neon/xppen/helper`) запускается через `app_process` с правами ADB-оболочки:

- находит перо планшета через `getevent -il` и забирает его у системы (`EVIOCGRAB`);
- пересчитывает координаты и нажим (`core/Mapping.kt`) по настройкам из
  `/sdcard/Android/data/com.neon.xppen/files/config.json`, которые пишет приложение;
- отдаёт результат через виртуальное HID-перо в `/dev/uhid`. Для Android это обычный USB-стилус:
  нажим, наведение, кнопки, поворот экрана.

Если драйвер завершится, ядро само освобождает планшет и удаляет виртуальное перо: Android
вернётся к обычному поведению. Журнал: `adb shell cat /data/local/tmp/g640_helper.log`.

## Сборка

Нужны JDK 17+ и Android SDK (platform 34).

```
gradlew.bat assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

---

## English summary

**Unofficial project, not affiliated with, endorsed or supported by XPPEN Technology Co. (XP-Pen).**
All trademarks belong to their owners.

A rootless Android driver for the XP-Pen Star G640 connected over USB OTG. Stock Android stretches the
whole 160×100 mm tablet onto the phone screen; this project adds an aspect-correct working area,
custom areas, tablet rotation that follows the screen, and a pressure curve — system-wide.

A helper started through ADB (`app_process`, shell uid) grabs the tablet's evdev node (`EVIOCGRAB`),
remaps coordinates and pressure according to the app's settings, and feeds them back to Android as a
virtual HID pen via `/dev/uhid`. Run `start.bat` on a PC (Wi-Fi debugging works) after each phone reboot;
configure everything in the app.
