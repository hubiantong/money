# AGENTS.md

Private Android ledger app. Read this before changing data, ledger, or import code.

## Project
- Single-module app **潼潼的账本** (`top.tongtong.money`): Kotlin 2.2.10 + Compose Material3 + Room + DataStore. `minSdk 26`, `compileSdk`/`targetSdk 36`, Java/Kotlin 17.
- All UI copy is Simplified Chinese; keep it that way. Money is always `Long` cents.
- `潼潼的账本-Android应用开发计划.md` is the product/ledger spec (rules, DB design, import format). Its environment section is stale (`d:\project\money`, JDK 21, no emulator); trust Gradle files and current code over it.

## Environment: do not build here
- `local.properties` points to `D:\AndroidSDK`, which is absent on this machine, and there is no emulator/device. Gradle/AGP cannot configure; **do not run `gradlew` and do not claim build/test success**. Validate changes by reading code and tests.
- If a real SDK is provided later: `gradlew.bat :app:testDebugUnitTest`; one class via `gradlew.bat :app:testDebugUnitTest --tests "top.tongtong.money.domain.ledger.LedgerEngineTest"`; APK via `gradlew.bat :app:assembleDebug`.
- Keep `android.suppressUnsupportedCompileSdk=36` in `gradle.properties`; AGP 8.7.3 does not officially support compileSdk 36 yet.

## Wiring
- Entry: `MoneyApp` → `di/AppContainer` (manual DI, no Hilt/Koin) → `MainActivity` (single `FragmentActivity`) → `LockGate` → `ui/navigation/AppNav.kt`.
- Keep `MainActivity` a `FragmentActivity`: `LockScreen` casts its context for `BiometricPrompt`. Tabs are 首页/明细/统计/我的; sub-routes are plain strings.
- Packages: `data/local` Room entities/DAOs/DB; `data/prefs` DataStore, EncryptedSharedPreferences, PBKDF2 lock; `data/repo` repositories; `domain/ledger` pure replay; `domain/imports` WeChat pipeline; `ui` Compose; `worker` WorkManager.
- New repositories/services belong in `AppContainer`; screens/ViewModels receive the container rather than constructing dependencies.
- WorkManager: `SettlementWorker` daily at ~05:05 via `ledgerRepository.catchUp()`, `EmailSyncWorker` hourly; both scheduled from `AppContainer.scheduleWork()`.

## Ledger invariants
- `LedgerEngine` is pure JVM and is the source of truth. `LedgerRepository` loads Room data, runs the engine, then replaces derived rows in one transaction.
- Never hand-write derived state: `period_buckets`, `surplus_grants`, `surplus_usages`, `savings_entries`, or transaction allocation fields (`overLimit`, `*CoveredCents`, `feeCents`, `uncoveredCents`). After any transaction or category-quota change call `rebuildFrom(earliestAffectedMonth)`.
- Replay is month-scoped; surplus never crosses months. Weekly buckets are **clipped at calendar-month boundaries** (`PeriodCalculator.periodOf/periodsOfMonth`), not plain ISO weeks — month-scoped replay depends on it.
- Fees are basis points (`feeBp`, 10000 = 100%) rounded half-up: `(amount * feeBp + 5000) / 10000`.
- Refunds clamp a bucket's `spentCents` at 0; a refund landing in an already-settled period revokes grants instead. This intentionally differs from the plan doc's "negative spent" wording — `LedgerEngineTest` is authoritative.
- A category's `createdAt` suppresses periods ending before it; seed categories use the current month start.

## Room / settings gotchas
- DB is version 1 with `exportSchema=true`; schema is `app/schemas/top.tongtong.money.data.local.AppDatabase/1.json`. Entity changes need a version bump, a `Migration`, and a new schema file. There is **no** `fallbackToDestructiveMigration`.
- Enums are stored by `Enum.name` through `Converters`; renaming a value breaks existing data (unknown values silently fall back).
- `SettingsRepository.flow` falls back to the last good snapshot on `IOException`, and to defaults on cold-start failure; that can disable the app lock. Don't make lock state depend on a more fragile read.

## WeChat import
- Real WeChat mail has **no attachment**: the HTML body contains a `…downloadfilefromemail…` URL that expires in ~7 days. The ZIP is ZipCrypto-encrypted; the password comes from the user. Parsers also accept zip/xlsx/csv attachments.
- Pipeline: `EmlParser`/`ImapMailClient` → `BillDownloader` → `ZipExtractor` → `XlsxReader`/`CsvReader` → `WechatBillParser` → `BillImportService`.
- Do not add Apache POI or a chart library: `XlsxReader` is a minimal SAX reader and stats are Compose Canvas-drawn.
- Keep these Android-free so JVM tests can run: `EmlParser`, `CsvReader`, `XlsxReader`, `WechatBillParser`, `MerchantCategorizer`, `PeriodCalculator`, `LedgerEngine`. `BillImportService`/`ImportRepository` are Android/Room-bound.
- Root `微信支付-账单流水文件(20260926-20261026).eml` is a real bill with private payment data. Never print it, copy its contents into source/tests, or commit it. Use synthetic fixtures; only `samples/` is gitignored.
- The manifest intentionally requests only `INTERNET`, `ACCESS_NETWORK_STATE`, `USE_BIOMETRIC`; imports use SAF. `allowBackup=false`. Don't add storage permissions or backup.
- `LockManager` PBKDF2 (120k iterations) costs 100–300 ms; keep it off the main thread. IMAP auth code and remembered ZIP password live only in EncryptedSharedPreferences.

## Tests/conventions
- JUnit4 tests live in `app/src/test` only; there is no `androidTest`/instrumentation setup. Test names use backticks.
- Ledger/parser tests pin `Asia/Shanghai`; production uses the system zone. Keep new time-sensitive tests zone-explicit.
- No CI, formatter, ktlint/detekt, or version catalog; dependencies are inline in `app/build.gradle.kts`.
