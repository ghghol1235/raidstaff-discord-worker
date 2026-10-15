# RaidStaff Discord worker

Standalone Discord runtime extracted from the RaidStaff 1.0.5 staff-management core. It is intended to keep Discord-side recruitment, interview management, role workflows, member verification, moderation audit polling, and nickname synchronization running while Paper is offline.

## Secrets and storage

Set `DISCORD_BOT_TOKEN` and `DISCORD_GUILD_ID` as host environment variables. Never commit the bot token. The worker stores durable staff state in `RAIDSTAFF_DATA_DIR/staff-data.json`; the Render blueprint mounts a persistent disk at `/app/data`.

## Run locally

Requires Java 21 and Maven 3.9+:

```powershell
$env:DISCORD_BOT_TOKEN = '<set locally, do not share>'
$env:DISCORD_GUILD_ID = '1550697780366802944'
$env:RAIDSTAFF_DATA_DIR = '.\data'
mvn package
java -jar target\raidstaff-discord-worker-1.0.0.jar
```

## Current integration boundary

Minecraft-only title purchases and sales remain in Minecraft. Discord title certification/restoration commands need a durable queue and a matching polling/acknowledgement update in the Paper plugin before those commands can be executed after the server comes back online. That bridge is not included in this first worker scaffold, so do not use this build as a replacement for the live RaidStaff plugin yet.

The Render blueprint is for a paid web service with persistent disk. It has not been deployed; creating the hosted service incurs ongoing charges and requires the bot token to be entered privately in Render environment settings.
