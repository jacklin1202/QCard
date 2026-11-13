# Q卡（QCard）變更總覽 — 從 Mika v0.1.40 改了什麼、為什麼

基底：[wangzan101/Mika](https://github.com/wangzan101/Mika) v0.1.40（commit `c5c88e35`），Apache License 2.0。
期間：2026-11-11 → 2026-11-13。分支：`queencard-merge-0140`，共 24 個 commit（不含上游本身的歷史）。

## 總原則

1. **原版行為是預設值**：fork 的功能全部收在開關或情境後面，不開就與原版一致。
2. **不改套件名、類別名、協定識別字**：維持與上游同構，目的有二——(a) 上游出新版時能以 merge 跟版；(b) 上游作者若想收編其中任何一項功能（例如備援鏈），可以直接 cherry-pick，不必先做改名手術。人看得到的地方（介面、回覆、提示詞、通知）才換成 Q卡；LICENSE、NOTICE 與授權頁的原作出處依法保留。
3. **每項改動都經過審查與驗收**：全套件測試與上游基準對帳（見文末驗證紀錄），壞集合不變才算過。

## 五項功能改動

### 1. 任務結束不關 App（commit `e288a404`）
- **改了什麼**：背景虛擬屏在任務結束後保留（螢幕與上面的 App 不被收掉），改由使用者在「設定 › 恢復與修復」手動釋放。
- **為什麼**：原版是真手機思維——做完就收乾淨不打擾使用者。但在模擬器情境，使用者就是要看著成果、接著用那個 App；每次任務做完就關掉，等於每次都要重開、重登入。

### 2. 主畫面模式（commit `e8b683cf`、`4c61121f`）
- **改了什麼**：新增開關（設定 › 恢復與修復，預設關）。開啟後任務直接在主畫面執行，所有操作看得見；偵測到使用者介入（碰螢幕、切換 App）會自動暫停（TaskState.Held），處理完可繼續。配套修復：主畫面模式下把任務帶到前景的正確做法。
- **為什麼**：原版的背景虛擬屏在模擬器上看不到、偶爾卡住也無從介入；模擬器使用者本來就盯著那塊螢幕，主畫面執行更直覺、更可控，也更安全（agent 做了什麼一目了然）。

### 3. 模型備援鏈（commit `974b28b6`，硬化：`eca3e54a`、`b1f3a00a`）
- **改了什麼**：主模型可再掛最多 2 個備援模型。主模型出事時依錯誤分類決定是否切換：伺服器錯誤（5xx，含未映射的 ≥400）、限流、額度、認證類錯誤會往下切；切換後黏住（sticky），不來回跳。全鏈都失敗時，回報的是**主模型**的原始錯誤並附整條鏈的嘗試摘要。硬化批次另修：轉錄（Transcriber）4xx 改為試下一個、備援鏈守衛條件、錯誤分類邊界。
- **為什麼**：免費／低價模型是常態用法，但免費額度會在任務中途用完、免費池會塞車回 503——原版遇到就是整件任務死掉。備援鏈讓「主模型塞車→自動換備援接手」成為內建行為。實務配置範例：主 Gemini（免費層）→ 備援 1 muse-spark → 備援 2 Gemma，日常任務幾乎不會因單一供應商狀態而中斷。

### 4. 主畫面模式下停用內建瀏覽器工具（commit `fffafab9`）
- **改了什麼**：主畫面模式開啟時，模型拿不到內建瀏覽器（web）工具，只能操作真實 App（例如 Chrome 本體）。
- **為什麼**：主畫面模式的賣點是「全部看得見」；內建瀏覽器會在背景另開一個看不見的工作區，與這個承諾矛盾，也曾造成登入狀態不存在的誤判（看的是沒登入的內建瀏覽器，不是使用者登入中的 Chrome）。

### 5. 原生 Telegram 通道（commit `0f7657dd`、`a46e5fb2`、`534de708`、`e7154554`、測試補強 `ad0a538c`/`ad5132cb`、實測修正 `f030bbbe`）
- **改了什麼**：新增一條完整的遠端通道（約 28 個檔、2,800 行）：
  - `core/telegram`：Telegram Bot API 客戶端（長輪詢收訊息、送文字與截圖）。
  - `app/telegram`：通道狀態儲存、綁定、前景服務（保住長連線不被系統殺）、與任務系統的接線。
  - 設定頁：填 Bot Token、產生一次性綁定碼（6 位數、10 分鐘有效、錯誤節流）、綁定狀態管理；字串齊備 12 個語系。
  - 安全模型：只有綁定的那個 Telegram 帳號能下任務（限私聊）；**核准類確認不能在 Telegram 上按**，一律要回 App 處理；同一則訊息重覆投遞不會重覆建任務（任務層去重），通道層採 at-least-once 語意與微信通道一致。
  - 實測教訓已寫進程式：新增通道必須把 App 內**每一處**通道解析點都接上（漏接 TaskService 的一處曾造成首次實測閃退，`f030bbbe` 修正）。
- **為什麼**：上游只有微信通道，而微信沒有官方個人號 Bot API、且僅限中國使用者圈。Telegram 有正規免費的 Bot API、是國際使用者的常態通訊工具；對「把 agent 放在家裡電腦模擬器、人出門在外用手機下任務」的情境，這是最自然的一條線。

## 上游合併（跟版）

- `c3c6e758` + `74608279`：把上游 v0.1.40（99 個 commit、564 個檔，含跨對話記憶、排程計畫、微信圖片通道等）merge 進本分支；與 fork 改動重疊僅 18 個檔，逐一對帳後全套件回到基準壞集合。跟版流程文件化於 MERGE_PLAYBOOK，重複成本低。

## 品牌與身分（人看得到的地方）

- `71bce69a`：新 applicationId `io.github.jacklin1202.qcard`（與官方版可並存）、App 名稱、範例與連結。
- `5b4988c4`、`4d903dd1`：自繪圖示（紫底、金皇冠、白 Q，未使用任何他人圖像或商標）。
- `f8c1ef7d`：全部語系 UI 字串 Mika→Q卡（353 個檔）；關於頁作者欄改為 fork 致謝並保留原作 © 2026；移除原作者的微信群／X 列與微信群範例任務。
- `c1054704`：提示詞層的自我身分——模型自稱 Q卡，不再自稱 Mika 的助手（真實路徑如 `Download/Mika` 保留，因為那是實際資料夾）。
- `e6e76f10`、`02efdec8`：程式內所有使用者／模型看得到的散文字串（通知標題、通話提示、自檢報告、MCP 登入頁與工具說明、任務結果頻道標題等，共 69 處）改為 Q卡。審查在此擋下一個真問題：瀏覽器下載資料夾名 `"Mika Browser/"` 是實際儲存路徑而非招牌，改名會讓既有下載變孤兒——已保留並註記於 commit。

## 驗證紀錄（摘要）

- 測試基準（上游 v0.1.40 於 Windows 檔案系統的既有壞集合）：core 7 個 suite、app 19 個 suite（多為沙箱／檔案系統相關的上游既有失敗）。fork 的每一批改動驗收條件都是「壞集合與基準完全一致」，最終樹：core 352 suites / 3262 tests、app 286 suites / 2085 tests，壞集合＝基準。
- 實機驗收（雷電模擬器 14、Android 14）：設定頁全項、主畫面模式介入暫停、備援鏈真實切換、Telegram 綁定與任務來回（傳任務→執行→結果回 Telegram）、Chrome 讀 X、YouTube 播放等皆實跑通過。
- 全庫祕密掃描：2,811 個追蹤檔案，無金鑰／憑證類檔案入庫（keystore 與密碼檔不進版控）。

## 刻意不改的清單

套件名 `com.wangzan101.mika`、類別與檔案名、MCP 工具名（`mika_*`）、log 標籤、User-Agent、`Download/Mika` 等真實路徑、瀏覽器下載資料夾名、LICENSE／NOTICE／授權頁出處、舊問候語的辨識相容（僅辨識、不再產生）。理由見「總原則」第 2 點。

---

## English summary

QCard is a fork of Mika v0.1.40 (Apache-2.0) re-targeted at **Android emulators on PC (tested on LDPlayer 14)** instead of a physical phone. Changes, each behind a switch or scoped so default behavior matches upstream:

1. **Persistent agent display** — tasks no longer close the apps they used; the user releases the display manually.
2. **Main-display mode** — tasks run visibly on the main screen; user intervention auto-pauses the task.
3. **Model failover chain** — up to 2 fallback models with error-class-based switching (5xx / rate-limit / quota / auth), sticky switching, and primary-led error reporting when the whole chain fails.
4. **Built-in browser withheld in main-display mode** — the model must use real apps (e.g. Chrome), so everything stays visible.
5. **Native Telegram channel** — full Bot API integration: one-time binding code (bound account only, private chats), foreground service, settings UI in 12 locales; approval-type confirmations still require the app and cannot be tapped through Telegram.

Package/class/protocol identifiers intentionally keep the upstream names so upstream releases can be merged and any of these features can be cherry-picked upstream; only human-visible text was rebranded, with LICENSE/NOTICE attribution preserved. Every batch was reviewed before landing and matched the upstream test baseline exactly.
