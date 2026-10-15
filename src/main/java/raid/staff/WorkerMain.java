package raid.staff;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateRolesEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.*;

/** Standalone Discord worker. Secrets are read only from environment variables. */
public final class WorkerMain extends ListenerAdapter {
    private final String guildId;
    private final ExecutorService jobs = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RaidStaff-worker"); t.setDaemon(true); return t;
    });
    private volatile StaffEngine engine;
    private volatile boolean ready;

    private WorkerMain(String guildId) { this.guildId = guildId; }

    public static void main(String[] args) throws Exception {
        String token = required("DISCORD_BOT_TOKEN");
        String guildId = required("DISCORD_GUILD_ID");
        WorkerMain app = new WorkerMain(guildId);
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
        app.startHealthServer();
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleWithFixedDelay(() -> app.submit(() -> app.engine.tick()), 30, 30, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { app.ready = false; ticker.shutdownNow(); app.jobs.shutdownNow(); jda.shutdown(); }));
        System.out.println("RaidStaff Discord worker connected; Minecraft-only title commands remain queued for the server bridge.");
    }

    private void startHealthServer() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "10000"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/health", exchange -> {
            byte[] body = (ready ? "ok" : "starting").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(ready ? 200 : 503, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
    }

    @Override public void onMessageReceived(MessageReceivedEvent e) {
        if (!e.isFromGuild() || !e.getGuild().getId().equals(guildId) || e.getAuthor().isBot() || e.isWebhookMessage()) return;
        String text = e.getMessage().getContentRaw();
        if (StaffEngine.parse(text) == null) return;
        if (!ready) { e.getChannel().sendMessage("운영진 시스템 준비 중입니다. 잠시 후 다시 시도하세요.").queue(); return; }
        submit(() -> engine.command(e.getAuthor().getId(), e.getChannel().getId(), e.getMessageId(), text));
    }
    @Override public void onGuildMemberJoin(GuildMemberJoinEvent e) { if (guild(e.getGuild().getId())) submit(() -> engine.verification(e.getUser().getId())); }
    @Override public void onGuildMemberRemove(GuildMemberRemoveEvent e) { if (guild(e.getGuild().getId())) submit(() -> engine.left(e.getUser().getId())); }
    @Override public void onGuildMemberUpdateRoles(GuildMemberUpdateRolesEvent e) {
        if (guild(e.getGuild().getId())) submit(() -> engine.nickname(e.getUser().getId()));
    }
    @Override public void onGuildMemberUpdateNickname(GuildMemberUpdateNicknameEvent e) {
        if (guild(e.getGuild().getId())) submit(() -> engine.nickname(e.getUser().getId()));
    }
    private boolean guild(String id) { return guildId.equals(id); }
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

