package raid.staff;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.google.gson.*;
import java.util.concurrent.*;

/** Standalone Discord worker. Secrets are read only from environment variables. */
public final class WorkerMain extends ListenerAdapter {
    private final String guildId;
    private final String bridgeToken;
    private final String titleChannelId;
    private final ExecutorService jobs = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RaidStaff-worker"); t.setDaemon(true); return t;
    });
    private volatile StaffEngine engine;
    private volatile boolean ready;

    private WorkerMain(String guildId, String bridgeToken, String titleChannelId) {
        this.guildId = guildId; this.bridgeToken = bridgeToken; this.titleChannelId = titleChannelId;
    }

    public static void main(String[] args) throws Exception {
        String token = required("DISCORD_BOT_TOKEN");
        String guildId = required("DISCORD_GUILD_ID");
        WorkerMain app = new WorkerMain(guildId, required("MINECRAFT_BRIDGE_TOKEN"),
                System.getenv().getOrDefault("TITLE_COMMAND_CHANNEL_ID", "1551061337981976646"));
        JDA jda = JDABuilder.createDefault(token,
                GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS)
                .addEventListeners(app).build().awaitReady();
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) throw new IllegalStateException("Configured Discord guild is not available to the bot");
        Store store = new Store(Path.of(System.getenv().getOrDefault("RAIDSTAFF_DATA_DIR", "./data"), "staff-data.json"));
        app.engine = new StaffEngine(store, new DiscordApi(token), guildId,
                text -> System.err.println("RaidStaff: " + text), id -> null);
        app.jobs.submit(() -> { try { app.engine.setup(); app.ready = true; }
            catch (Exception e) { System.err.println("RaidStaff setup failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
        }).get();
        app.startHttpServer();
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleWithFixedDelay(() -> app.submit(() -> app.engine.tick()), 30, 30, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { app.ready = false; ticker.shutdownNow(); app.jobs.shutdownNow(); jda.shutdown(); }));
        System.out.println("RaidStaff Discord worker connected; Minecraft-only title commands remain queued for the server bridge.");
    }

    private void startHttpServer() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "10000"));
        String bindAddress = System.getenv().getOrDefault("BIND_ADDRESS", "0.0.0.0");
        HttpServer server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        server.createContext("/health", exchange -> {
            byte[] body = (ready ? "ok" : "starting").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(ready ? 200 : 503, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/minecraft/commands/next", exchange -> {
            if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) { send(exchange, 401, "{}"); return; }
            if (!exchange.getRequestMethod().equals("GET")) { send(exchange, 405, "{}"); return; }
            try {
                JsonObject next = jobs.submit(() -> {
                    JsonArray commands = queue();
                    for (JsonElement item : commands) if (!item.getAsJsonObject().get("done").getAsBoolean()) return item.getAsJsonObject().deepCopy();
                    return new JsonObject();
                }).get(5, TimeUnit.SECONDS);
                send(exchange, 200, next.toString());
            } catch (Exception ex) { send(exchange, 503, "{}"); }
        });
        server.createContext("/minecraft/commands/ack", exchange -> {
            if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) { send(exchange, 401, "{}"); return; }
            if (!exchange.getRequestMethod().equals("POST")) { send(exchange, 405, "{}"); return; }
            try {
                JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                String id = body.get("id").getAsString(); boolean success = body.get("success").getAsBoolean();
                jobs.submit(() -> {
                    for (JsonElement item : queue()) {
                        JsonObject command = item.getAsJsonObject();
                        if (command.get("id").getAsString().equals(id) && !command.get("done").getAsBoolean()) {
                            command.addProperty("done", true); command.addProperty("result", success ? "ok" : "failed");
                            engine.db.save();
                            engine.say(command.get("replyChannel").getAsString(), success
                                    ? "✅ Minecraft 서버에서 칭호 명령을 처리했습니다." : "❌ Minecraft 서버가 칭호 명령을 거부했습니다. 서버 로그를 확인해 주세요.");
                            engine.flush(); break;
                        }
                    }
                    return null;
                }).get(5, TimeUnit.SECONDS);
                send(exchange, 200, "{\"ok\":true}");
            } catch (Exception ex) { send(exchange, 400, "{}"); }
        });
        server.createContext("/minecraft/nickname", exchange -> {
            if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) { send(exchange, 401, "{}"); return; }
            if (!exchange.getRequestMethod().equals("POST")) { send(exchange, 405, "{}"); return; }
            try {
                JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                String discordId = body.get("discordId").getAsString();
                String playerName = body.get("minecraftName").getAsString();
                String title = body.get("title").getAsString();
                if (!discordId.matches("[0-9]{17,20}") || !playerName.matches("[A-Za-z0-9_]{1,16}") || title.length() > 32)
                    { send(exchange, 400, "{}"); return; }
                jobs.submit(() -> { engine.syncMinecraftNickname(discordId, playerName, title); engine.flush(); return null; })
                        .get(8, TimeUnit.SECONDS);
                send(exchange, 200, "{\"ok\":true}");
            } catch (Exception ex) { send(exchange, 400, "{}"); }
        });
        server.createContext("/minecraft/import-state", exchange -> {
            if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) { send(exchange, 401, "{}"); return; }
            if (!exchange.getRequestMethod().equals("POST")) { send(exchange, 405, "{}"); return; }
            try {
                byte[] raw = exchange.getRequestBody().readNBytes(10 * 1024 * 1024 + 1);
                if (raw.length > 10 * 1024 * 1024) { send(exchange, 413, "{}"); return; }
                JsonObject imported = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
                if (!imported.has("people") || !imported.get("people").isJsonObject()) { send(exchange, 400, "{}"); return; }
                jobs.submit(() -> {
                    if (!engine.db.data.has("paperStateImported")) {
                        JsonObject people = engine.db.data.getAsJsonObject("people");
                        for (var entry : imported.getAsJsonObject("people").entrySet()) {
                            JsonObject priorPerson = entry.getValue().getAsJsonObject().deepCopy();
                            if (people.has(entry.getKey())) {
                                JsonObject currentPerson = people.getAsJsonObject(entry.getKey());
                                for (var field : currentPerson.entrySet()) {
                                    if (field.getValue().isJsonArray() && priorPerson.has(field.getKey())
                                            && priorPerson.get(field.getKey()).isJsonArray()) {
                                        JsonArray merged = priorPerson.getAsJsonArray(field.getKey());
                                        for (JsonElement item : field.getValue().getAsJsonArray()) {
                                            boolean found = false;
                                            for (JsonElement old : merged) if (old.toString().equals(item.toString())) { found = true; break; }
                                            if (!found) merged.add(item.deepCopy());
                                        }
                                        if (merged.isEmpty() && !field.getValue().getAsJsonArray().isEmpty())
                                            priorPerson.add(field.getKey(), field.getValue().deepCopy());
                                    } else priorPerson.add(field.getKey(), field.getValue().deepCopy());
                                }
                                people.add(entry.getKey(), priorPerson);
                            } else people.add(entry.getKey(), priorPerson);
                        }
                        long prior = imported.has("sequence") ? imported.get("sequence").getAsLong() : 0;
                        long current = engine.db.data.has("sequence") ? engine.db.data.get("sequence").getAsLong() : 0;
                        engine.db.data.addProperty("sequence", Math.max(prior, current));
                        engine.db.data.addProperty("paperStateImported", true);
                        engine.db.save();
                        System.out.println("Imported legacy Minecraft staff state: " + imported.getAsJsonObject("people").size() + " records");
                    }
                    return null;
                }).get(8, TimeUnit.SECONDS);
                send(exchange, 200, "{\"ok\":true}");
            } catch (Exception ex) { send(exchange, 400, "{}"); }
        });
        server.start();
    }

    @Override public void onMessageReceived(MessageReceivedEvent e) {
        if (!e.isFromGuild() || !e.getGuild().getId().equals(guildId) || e.getAuthor().isBot() || e.isWebhookMessage()) return;
        String text = e.getMessage().getContentRaw();
        if (e.getChannel().getId().equals(titleChannelId)) {
            String command = parseTitleCommand(text);
            if (command != null) submit(() -> {
                JsonObject member = engine.member(e.getAuthor().getId());
                if (!e.getAuthor().getId().equals(engine.owner) && !StaffEngine.manager(member)) {
                    engine.say(e.getChannel().getId(), "❌ Discord 서버장 또는 관리자 역할만 칭호 명령을 사용할 수 있습니다.");
                    engine.flush(); return;
                }
                JsonArray q = queue(); JsonObject item = new JsonObject();
                item.addProperty("id", java.util.UUID.randomUUID().toString()); item.addProperty("command", command);
                item.addProperty("actor", e.getAuthor().getId()); item.addProperty("replyChannel", e.getChannel().getId());
                item.addProperty("createdAt", Store.now()); item.addProperty("done", false); q.add(item); engine.db.save();
                engine.say(e.getChannel().getId(), "⏳ 칭호 명령을 보관했습니다. Minecraft 서버가 켜지면 전달합니다."); engine.flush();
            });
            return;
        }
        if (StaffEngine.parse(text) == null) return;
        if (!ready) { e.getChannel().sendMessage("운영진 시스템 준비 중입니다. 잠시 후 다시 시도하세요.").queue(); return; }
        submit(() -> engine.command(e.getAuthor().getId(), e.getChannel().getId(), e.getMessageId(), text));
    }
    @Override public void onGuildMemberJoin(GuildMemberJoinEvent e) { if (guild(e.getGuild().getId())) submit(() -> engine.verification(e.getUser().getId())); }
    @Override public void onGuildMemberRemove(GuildMemberRemoveEvent e) { if (guild(e.getGuild().getId())) submit(() -> engine.left(e.getUser().getId())); }
    @Override public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent e) {
        if (guild(e.getGuild().getId())) submit(() -> engine.nickname(e.getUser().getId()));
    }
    @Override public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent e) {
        if (guild(e.getGuild().getId())) submit(() -> engine.nickname(e.getUser().getId()));
    }
    @Override public void onGuildMemberUpdateNickname(GuildMemberUpdateNicknameEvent e) {
        if (guild(e.getGuild().getId())) submit(() -> engine.nickname(e.getUser().getId()));
    }
    private boolean guild(String id) { return guildId.equals(id); }
    private JsonArray queue() {
        JsonElement value = engine.db.data.get("minecraftCommands");
        if (value == null || !value.isJsonArray()) { JsonArray arr = new JsonArray(); engine.db.data.add("minecraftCommands", arr); return arr; }
        return value.getAsJsonArray();
    }
    private static String parseTitleCommand(String raw) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(칭호인증|칭호해제|칭호복구|칭호복구해제) ([A-Za-z0-9_]{1,16}) ([가-힣A-Za-z0-9 _-]{1,32})$").matcher(raw.strip());
        return m.matches() ? m.group(1) + " " + m.group(2) + " " + m.group(3).strip() : null;
    }
    private boolean authorized(String value) {
        return value != null && MessageDigest.isEqual(("Bearer " + bridgeToken).getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
    }
    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String text) throws java.io.IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
    }
    private void submit(Checked job) {
        if (!ready) return;
        try { jobs.execute(() -> { try { job.run(); engine.flush(); }
            catch (Exception e) { System.err.println("RaidStaff operation failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
        }); } catch (RejectedExecutionException e) { System.err.println("RaidStaff work queue full"); }
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + key);
        return value;
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}

