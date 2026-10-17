# Helper Bot — Fabric mod for Minecraft 26.2

A helper you command to **mine ores and stone, chop trees, pick up dropped items, and sort everything into your chests**.

## 1. Build the .jar (one time)

**On your PC** (needs Java 25 or newer — Java 27 works; get it from https://adoptium.net):

- Windows: open the `helperbot` folder, type `cmd` in the address bar, then run `gradlew build`
- Mac/Linux: open a terminal in the folder and run `./gradlew build`

The first build downloads Minecraft and takes a few minutes. Your mod is then at `build/libs/helperbot-2.0.0.jar`.

**Or with no setup:** upload this folder to a GitHub repo. The included workflow builds it automatically — open the repo's **Actions** tab, click the latest run, and download `helperbot-jar`.

## 2. Install

Put `helperbot-2.0.0.jar` **and** [Fabric API](https://modrinth.com/mod/fabric-api) (the 26.2 version) in the `mods` folder of:
- your Fabric server, **and**
- every player's Fabric client (players without the mod can't join).

## 3. Use it

| Command | What it does |
|---|---|
| `/helper summon` | Get a helper (max 3 each). It starts following you. |
| `/helper chest add` | Look at a chest, barrel or shulker box to make it storage. Add as many as you like. |
| `/helper mine ores [radius]` | Mines ores it can see (next to air) around where you're standing. |
| `/helper mine stone [radius]` | Clears exposed stone from your feet level up 4 blocks — great for digging out rooms. |
| `/helper chop [radius]` | Fells whole trees and replants if it's carrying saplings. Ignores logs with no natural leaves nearby, so your log houses are safe. |
| `/helper collect [radius]` | Picks up dropped items (e.g. saplings and apples after chopping). |
| `/helper store` | Puts everything it's carrying into storage. |
| `/helper sort` | Gathers each item type into the chest that already holds the most of it, then stacks and orders every chest. |
| `/helper follow` / `stay` / `stop` | Movement control. |
| `/helper status` | What it's doing and carrying. |
| `/helper chest list` / `remove` / `clear` | Manage storage. |
| `/helper dismiss` | Sends it away; its items go into your inventory. |

Radius is 4–32 blocks (default 16). When its 27 slots fill up it walks to storage, unloads (putting items in the chest that already has the most of that item), and goes back to work. It messages you when it finishes or gets stuck.

Right-click your helper to see its inventory. Other players can't hurt it; it doesn't take fall damage and heals over time.

## Good to know

- It only works while its area is loaded — stay within ~100 blocks.
- It uses a virtual iron pickaxe/axe, so drops are normal (no Fortune or Silk Touch).
- It walks — it can't climb sheer walls or reach blocks more than ~4 blocks from where it can stand. Unreachable targets are skipped.
- Fabric has no claims system built in; any player with a helper can use it anywhere they can stand.

## Changing things

- Tool, reach, health, speed: `src/main/java/com/helperbot/HelperEntity.java`
- Max helpers, default radius, commands: `src/main/java/com/helperbot/HelperCommands.java`
- Chest logic: `src/main/java/com/helperbot/StorageUtil.java`
