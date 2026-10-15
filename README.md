# RaidStaff Discord worker

Standalone Discord runtime for the RaidStaff 1.0.5 staff-management core. The worker processes Discord recruitment/interview commands, role workflows, member verification, moderation audit checks, and staff nickname updates even while Paper is offline. Minecraft-only title purchases/sales remain in-game.

The HTTP bridge stores Discord title commands while Paper is offline, then delivers them when the Minecraft plugin polls after startup. The Paper plugin also imports the existing `staff-data.json` once, preserving recruitment history. That file is sent only over HTTPS with the bridge secret and is never part of this repository.

## Secrets and permissions

Set `DISCORD_BOT_TOKEN` and `MINECRAFT_BRIDGE_TOKEN` as Render secret environment variables. `DISCORD_GUILD_ID` and `TITLE_COMMAND_CHANNEL_ID` identify the server and title-command channel. Never commit or paste either secret into chat. Enable Discord's Server Members and Message Content privileged intents for the bot.

## Run locally

Requires Java 21+ and Maven 3.9+:

```powershell
$env:DISCORD_BOT_TOKEN = '<set locally, do not share>'
$env:MINECRAFT_BRIDGE_TOKEN = '<set locally, do not share>'
$env:DISCORD_GUILD_ID = '1550697780366802944'
$env:RAIDSTAFF_DATA_DIR = '.\data'
mvn package
java -jar target\raidstaff-discord-worker-1.0.0.jar
```

## Paper plugin connection

Build the updated plugin source in `paper-plugin/src` with the server's existing RaidStaff build environment. After the Render service is healthy, set these keys in `plugins/RaidStaff/config.yml`:

```yaml
standalone-worker:
  enabled: true
bridge:
  url: 'https://<your-render-service>.onrender.com'
  token: '<same MINECRAFT_BRIDGE_TOKEN configured privately in Render>'
```

The plugin uploads the existing local staff state to the worker on first connection, receives queued title commands, and reports their result. Keep the old data file until migration is confirmed in the server log.

## Hosting

`render.yaml` declares a Docker web service with a persistent 1 GB disk. It requires a paid Render service and disk; the current Render prices are approximately $7/month for the 512 MB service plus $0.25/month for 1 GB of SSD disk. Render deployment has not yet been created.
