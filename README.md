# VibeEconomy 1.1

Self-contained economy plugin for Paper 1.21+. No Vault, EssentialsX, or shop plugin needed.

## Build (no install): GitHub
1. Make a free account at github.com and create a new repository.
2. Upload everything in this folder (including the hidden .github folder) to it.
3. Open the Actions tab, run "Build VibeEconomy" (it also runs automatically on upload).
4. When it finishes, open the run and download the artifact "VibeEconomy-jar". Unzip it and put the jar in plugins/.

## Build (local)
Requires JDK 21 and Maven.

    mvn clean package

Jar: target/VibeEconomy-1.1.0.jar. Put it in plugins/ and restart.

## Commands
| Command | What it does | Permission |
|---|---|---|
| /balance [player] | Check money (and your shards) | everyone |
| /pay <player> <amount> | Send money | everyone |
| /baltop | Richest players | everyone |
| /sell | Opens the sell menu: put items in, click Confirm | everyone |
| /sell hand / all | Quick-sell held item or whole inventory | everyone |
| /worth [item] | Sell value of an item | everyone |
| /shop [pvp / qol / vanta] | Open the shop GUI | everyone |
| /shards [player] | Check Vanta Shards | everyone |
| /shards give/take/set <player> <amt> | Admin shards | admin |
| /keyall [amount] | Give every online player Vanta Keys (no cost) | admin |
| /vantakey give <player> [amt] | Give keys to one player | admin |
| /hud | Toggle the VantaSMP sidebar HUD | everyone |
| /eco give/take/set <player> <amt> | Admin money | admin |
| /shop reload | Reload shop.yml | admin |

## The shop
- PvP Shop (money): enchanted gear, potions, gapples, pearls, totems, cobweb, etc.
- Quality of Life (money): pickaxes, ender chest, shulker, elytra, repair, heal, feed, etc.
- Vanta Shop (shards): Vanta Key, KEY ALL, Vanta Blade, Vanta Plate, money pouch, etc.

## Vanta Shards
Earned from PvP kills: 5 shards + a streak bonus, plus $100. Anti-farm: same-victim cooldown, same-IP block, minimum victim playtime. All in config.yml.

## Keys and KeyAll
- A Vanta Key is a glowing trial key. Right-click to roll a prize from key-loot in shop.yml.
- KEY ALL gives every online player a key and broadcasts it. Buying it with shards has a cooldown (config.yml).

## Files in plugins/VibeEconomy/
config.yml, shop.yml (the whole shop and key loot), prices.csv (sell values), balances.yml, shards.yml.
