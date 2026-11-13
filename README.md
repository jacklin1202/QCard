# Q卡（QCard）

> 非官方個人分叉（fork），基於 [wangzan101/Mika](https://github.com/wangzan101/Mika) v0.1.40（Apache-2.0），與原作者無關、未經原作者背書。完整的改動清單與理由見 [CHANGES.md](CHANGES.md)。
> Unofficial personal fork of Mika v0.1.40. English version below.

> **先講清楚：這支 App 下載完不會自己動。** 它不含 AI、沒有官方伺服器、沒有內建的免費額度。安裝後要先到「設定 › 模型」填入你自己的模型 API Key（或用 ChatGPT／xAI／OpenRouter 帳號登入），它才會開始工作，模型費用算你自己的（各家多有免費層）。沒有 API Key、也不想碰模型設定的話，這支 App 不適合你；它適合願意自己準備模型的人。

## 為什麼有這個版本

AI agent 會看你的螢幕、替你操作 App——這種東西不該住在裝著銀行與信用卡的真手機裡。
Q卡 的立場是：**讓 agent 住在電腦的安卓模擬器裡**，真手機保持乾淨。
模擬器裡只有遊戲與拋棄式帳號，爆炸半徑就這麼大；算力、散熱、遠端操控都由 PC 承擔。

**給誰用**：純電腦使用者——不想在真手機上安裝高風險 agent，但想讓 agent 在模擬器（**雷電 LDPlayer**、其他模擬器同理）裡替你做事的人。

**典型用法**：遊戲的每日登入與日常任務、簽到、簡單重複操作——開著電腦，Q卡 在模擬器裡一手包辦；人不在電腦前時，用 Telegram 從手機下任務、收結果截圖。

原版 Mika 為真手機設計（在背景虛擬屏安靜做事、不占用你的屏幕）；搬到模擬器上會遇到小窗卡住看不到、做完任務就把 App 關掉等問題。Q卡 把這些改成適合模擬器的行為，而且**全部收在開關後面：預設與原版一致，用模擬器才需要打開**。

## 與原版的差異

1. **主畫面模式（模擬器適配）**（設定 › 恢復與修復，預設關）
   任務直接在主畫面執行，像真人一樣操作、全部看得見；使用者介入（碰螢幕、切 App）會自動暫停，處理完可繼續。
2. **任務結束不關 App**
   背景模式下任務做完保留螢幕與 App，不再收尾關閉；可在設定裡手動釋放。每日登入類任務不用每次重開遊戲。
3. **模型備援鏈**
   主模型可掛最多 2 個備援；遇到塞車（503）、限流、額度用完、認證錯誤會依規則自動切換接手，全鏈失敗時回報主模型的原始錯誤（附整條鏈的嘗試摘要）。免費模型當主力也不怕單一供應商鬧脾氣。
4. **主畫面模式下停用內建瀏覽器工具**
   模型只能用真實 App（例如 Chrome 本體），所有操作都看得見，不在背景偷跑。
5. **原生 Telegram 通道**
   用自己的 Telegram Bot 直接下任務、收結果與截圖、回答 Q卡 的提問、`/stop` 急停。綁定採一次性綁定碼，只有綁定的帳號能下指令。核准類確認基於安全**不能**在 Telegram 上按，只能回 App 處理。

其餘功能與原版相同。跟版方式：原廠出新版後以 merge 併入，不定期更新；套件與結構刻意與上游同構，方便跟版，也方便上游收編其中任何功能。

## 安裝與上手

1. 從 Releases 下載 `qcard-1.0.zip`，解壓後安裝裡面的 APK（applicationId 與官方版不同，可並存，設定不互通）。
2. 照 App 內精靈填入你自己的模型 API Key（主模型需支援看圖與工具呼叫）。可考慮的組合：主模型用免費層模型，再用備援鏈掛兩個不同供應商的模型保底。
3. **無障礙服務**：Android 13 以上對側載 App 有「受限制的設定」——若無障礙開不起來，先到「應用程式資訊 › ⋮ › 允許受限制的設定」，再回來開啟 Q卡 的無障礙服務。
4. **模擬器裡啟動手機助手**：用 App 內的無線配對流程，或用電腦的 adb 連上模擬器後由隨附工具啟動（與原版相同機制）。
5. 用模擬器建議打開：設定 › 恢復與修復 › **主畫面模式**。

實測環境：雷電模擬器 14（Android 14）。

## 支援邊界

個人自用分叉，按現狀提供（as-is），**不承諾回覆 issue 或提供支援**。
原版的功能問題請回原廠 repo；本版特有行為（主畫面模式、備援鏈、Telegram 通道）的問題歡迎開 issue，但回覆看時間。

## 名稱由來

「Q卡」——Q 是 Queen，皇后替你在手機裡做事的感覺。名稱致敬 (G)I-DLE 的〈Queencard〉，與該團體／經紀公司無任何關係，未使用其圖像或標誌（圖示為自繪）。

## 授權

Apache License 2.0（同原版）；保留原版 LICENSE 與 NOTICE，並標明本版修改之處（見 CHANGES.md 與各 commit）。

---

# QCard — English

> Unofficial personal fork of [wangzan101/Mika](https://github.com/wangzan101/Mika) v0.1.40 (Apache-2.0). Not affiliated with or endorsed by the original author. Full change list and rationale: [CHANGES.md](CHANGES.md).

> **Please note: this app does nothing on its own after install.** It contains no AI, runs no official server, and includes no free model quota. You must first enter your own model API key under Settings › Model (or sign in with a ChatGPT / xAI / OpenRouter account) before it can work; model costs are yours (most providers offer a free tier). If you have no API key and don't want to deal with model setup, this app is not for you — it is for users who can prepare their own model.

## Why this fork exists

An AI agent that reads your screen and operates your apps does not belong on the phone that holds your banking and cards. QCard's stance: **the agent lives in an Android emulator on your PC** — the emulator holds only games and throwaway accounts, so the blast radius stays small, while the PC provides compute, cooling, and remote control.

**Who it's for**: PC-centric users who don't want a high-risk agent on their real phone, but do want one working inside an emulator (**LDPlayer** in our testing; other emulators should behave the same).

**Typical use**: daily game logins and routine tasks, check-ins, simple repetitive chores — QCard handles them end-to-end in the emulator, and when you're away from the PC you can send tasks and receive results/screenshots over Telegram.

Upstream Mika is designed for physical phones (working quietly on a background virtual display). On an emulator that design causes real friction — an invisible, sometimes stuck virtual screen, and apps being closed when a task ends. QCard adapts those behaviors for emulator use, **all behind switches: defaults match upstream; enable them only if you run an emulator.**

## What differs from upstream

1. **Main-display mode** (Settings › Recovery & repair, off by default) — tasks run visibly on the main screen like a human operating the phone; if you intervene (touch, switch apps) the task auto-pauses and can resume afterwards.
2. **Tasks no longer close apps** — in background mode the display and its apps survive task completion; release them manually in Settings. Daily-login routines don't restart the game every time.
3. **Model failover chain** — attach up to 2 fallback models to the main model. Congestion (503), rate limits, quota exhaustion, and auth errors switch over automatically by error class; if the whole chain fails you get the main model's original error plus a summary of every attempt. Free-tier models become viable primaries.
4. **Built-in browser withheld in main-display mode** — the model must use real apps (e.g. Chrome itself); nothing runs invisibly in the background.
5. **Native Telegram channel** — task the agent from your own Telegram bot: send tasks, receive results and screenshots, answer its questions, `/stop` to halt. Pairing uses a one-time binding code; only the bound account can issue tasks. Approval-type confirmations **cannot** be tapped through Telegram — they require the app, by design.

Everything else matches upstream. Upstream releases are merged in periodically; package and structure intentionally stay isomorphic to upstream so merging stays cheap and any feature here can be cherry-picked upstream.

## Install & setup

1. Download `qcard-1.0.zip` from Releases, unzip it, and install the APK inside (different applicationId from upstream — they can coexist; settings are separate).
2. Enter your own model API key in the setup wizard (the main model needs vision + tool calling). Consider a free-tier main model backed by the failover chain across two more providers.
3. **Accessibility service**: on Android 13+, sideloaded apps hit "restricted settings" — if the accessibility toggle won't enable, first open App info › ⋮ › Allow restricted settings, then enable QCard's accessibility service.
4. **Starting the phone helper in an emulator**: use the in-app wireless pairing flow, or connect adb from your PC to the emulator and start it with the bundled tooling (same mechanism as upstream).
5. Emulator users should enable: Settings › Recovery & repair › **Main-display mode**.

Tested on LDPlayer 14 (Android 14).

## Support

Personal fork, provided as-is; **no promise of issue replies or support**. Upstream behavior questions belong to the upstream repo; issues specific to this fork's features (main-display mode, failover chain, Telegram channel) are welcome but answered when time permits.

## The name

"Q卡" (QCard) — Q for Queen: a queen handling things in your phone for you. A nod to (G)I-DLE's "Queencard"; no affiliation, and no imagery or marks of the group or its agency are used (the icon is self-drawn).

## License

Apache License 2.0, same as upstream. Upstream LICENSE and NOTICE are preserved; this fork's modifications are documented in CHANGES.md and the commit history.
