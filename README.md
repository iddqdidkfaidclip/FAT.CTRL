# FATCTRLBOT (Kotlin Telegram Bot)

### What it does (v1)
- **Weight once/day**: user can save weight with `/weight 72.5` (or just send `72.5`)
- **Progress**: `/progress` shows first vs last weight and deltas
- **Daily activity once/day**: `/activity` assigns (and remembers) one activity for today with reps in **10..50**

### Configuration (VPS friendly)
Store secrets in the **VPS environment** (recommended), not in code.

- **Required**
  - `BOT_TOKEN`: Telegram bot token from BotFather
- **Optional**
  - `DB_PATH`: path to SQLite file (default `./fatctrlbot.sqlite`)
  - `BOT_TZ`: timezone used for “once per day” rules (default `UTC`, example `Europe/Moscow`)

### Run locally

```bash
export BOT_TOKEN="123456:ABCDEF"
export BOT_TZ="UTC"
./gradlew run
```

### Notes
- The bot enforces:
  - **max 1 weight entry per user per day**
  - **max 1 activity assignment per user per day**
  (these are enforced by DB unique indexes)

