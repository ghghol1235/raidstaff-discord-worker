package raid.staff;
import org.bukkit.*;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import ch.njol.skript.variables.Variables;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.AccountLinkedEvent;
import github.scarsz.discordsrv.objects.threads.NicknameUpdater;
import github.scarsz.discordsrv.dependencies.jda.api.JDA;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Member;
import github.scarsz.discordsrv.dependencies.jda.api.events.guild.member.*;
import github.scarsz.discordsrv.dependencies.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent;
import github.scarsz.discordsrv.dependencies.jda.api.events.message.guild.GuildMessageReceivedEvent;
import github.scarsz.discordsrv.dependencies.jda.api.hooks.ListenerAdapter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.reflect.Field;
import com.google.gson.JsonObject;

public final class RaidStaff extends JavaPlugin {
 private MinecraftCommandBridge bridge;private boolean standalone;private ThreadPoolExecutor worker;private volatile StaffEngine engine;private volatile boolean ready,starting;private JDA attached;
 private final AtomicBoolean maintenance=new AtomicBoolean();private NicknameUpdater previous,replacement;private Field updaterField;private final java.util.Set<String> nicknameJobs=ConcurrentHashMap.newKeySet();
 private final ListenerAdapter events=new ListenerAdapter(){
  @Override public void onGuildMessageReceived(GuildMessageReceivedEvent e){if(!e.getGuild().getId().equals(guild())||e.getAuthor().isBot()||e.isWebhookMessage())return;if(e.getChannel().getId().equals(getConfig().getString("title-channel-id","1551061337981976646"))){titleCommand(e);return;}if(StaffEngine.parse(e.getMessage().getContentRaw())==null)return;if(!ready){e.getChannel().sendMessage("운영진 시스템 준비/복구 중입니다. 잠시 후 다시 시도하세요.").queue();return;}submit(()->engine.command(e.getAuthor().getId(),e.getChannel().getId(),e.getMessageId(),e.getMessage().getContentRaw()),e.getChannel().getId());}
  @Override public void onGuildMemberJoin(GuildMemberJoinEvent e){if(e.getGuild().getId().equals(guild()))submit(()->engine.verification(e.getUser().getId()),null);}
  @Override public void onGuildMemberRemove(GuildMemberRemoveEvent e){if(e.getGuild().getId().equals(guild()))submit(()->engine.left(e.getUser().getId()),null);}
  @Override public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent e){if(e.getGuild().getId().equals(guild()))submit(()->{engine.verification(e.getUser().getId());engine.nickname(e.getUser().getId());},null);}
  @Override public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent e){if(e.getGuild().getId().equals(guild()))requestNickname(e.getUser().getId());}
  @Override public void onGuildMemberUpdateNickname(GuildMemberUpdateNicknameEvent e){if(e.getGuild().getId().equals(guild()))requestNickname(e.getUser().getId());}
 };
 private String guild(){return getConfig().getString("guild-id","1550697780366802944");}
 static String parseTitleCommand(String raw){java.util.regex.Matcher m=java.util.regex.Pattern.compile("^(칭호인증|칭호해제|칭호복구|칭호복구해제) ([A-Za-z0-9_]{1,16}) ([가-힣A-Za-z0-9 _-]{1,32})$").matcher(raw.strip());return m.matches()?m.group(1)+" "+m.group(2)+" "+m.group(3).strip():null;}
 static boolean canManageTitleCommands(JsonObject member,String actor,String guildOwner){return actor.equals(guildOwner)||StaffEngine.manager(member);}
 private void titleCommand(GuildMessageReceivedEvent e){String cmd=parseTitleCommand(e.getMessage().getContentRaw());if(cmd==null){e.getChannel().sendMessage("관리자 전용입니다. `칭호인증/칭호해제/칭호복구/칭호복구해제 닉네임 칭호` 형식으로 입력하세요.").queue();return;}if(!ready||engine==null){e.getChannel().sendMessage("운영진 시스템 준비 중입니다. 잠시 후 다시 시도하세요.").queue();return;}submit(()->{JsonObject member=engine.member(e.getAuthor().getId());if(!canManageTitleCommands(member,e.getAuthor().getId(),engine.owner)){e.getChannel().sendMessage("❌ Discord 서버장 또는 관리자 역할만 칭호를 인증·해제·복구할 수 있습니다.").queue();return;}Bukkit.getScheduler().runTask(this,()->{boolean ok=Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd);e.getChannel().sendMessage(ok?"✅ 칭호 명령을 전달했습니다: `"+cmd+"`":"❌ 명령 처리에 실패했습니다. 서버 로그를 확인하세요.").queue();});},e.getChannel().getId());}
 @Override public void onEnable(){saveDefaultConfig();standalone=getConfig().getBoolean("standalone-worker.enabled",false);if(standalone){bridge=new MinecraftCommandBridge(this);bridge.start();}getCommand("raidstaff-sync-title").setExecutor((sender,command,label,args)->{if(!(sender instanceof ConsoleCommandSender)||args.length!=1)return true;try{java.util.UUID uuid=java.util.UUID.fromString(args[0]);Object manager=DiscordSRV.getPlugin().getAccountLinkManager();Object linked=manager.getClass().getMethod("getDiscordId",java.util.UUID.class).invoke(manager,uuid);if(linked instanceof String id&&!id.isBlank()){if(standalone){String mcname=Bukkit.getOfflinePlayer(uuid).getName();Object title=Variables.getVariable("r2.title::"+uuid,null,false);if(mcname!=null)bridge.syncNickname(id,mcname,title==null?"생존자":title.toString());}else requestNickname(id);}}catch(Exception ex){getLogger().warning("Linked title sync failed: "+ex.getClass().getSimpleName());}return true;});if(standalone){getLogger().info("Discord staff workflows delegated to the standalone worker");return;}worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(200),r->{Thread t=new Thread(r,"RaidStaff-worker");t.setDaemon(true);return t;});DiscordSRV.api.subscribe(this);
  Bukkit.getScheduler().runTaskTimer(this,()->{
   JDA jda=DiscordSRV.getPlugin().getJda();if(jda==null)return;
   if(attached!=jda){if(attached!=null)attached.removeEventListener(events);attached=jda;jda.addEventListener(events);}
   if(!ready&&!starting){starting=true;submit(()->{try{if(engine==null){Store db=new Store(getDataFolder().toPath().resolve("staff-data.json"));JsonObject channels=db.data.getAsJsonObject("channels");for(String key:new String[]{"management","applicant","interview-category","interview-1","interview-2","interview-3"}){String value=getConfig().getString("channels."+key,"");if(!value.isBlank()&&!channels.has(key))channels.addProperty(key,value);}db.save();engine=new StaffEngine(db,new DiscordApi(DiscordSRV.config().getString("BotToken")),guild(),s->getLogger().warning(s),RaidStaff.this::linkedIdentity);}engine.setup();ready=true;Bukkit.getScheduler().runTask(RaidStaff.this,()->{for(var entry:engine.db.data.getAsJsonObject("channels").entrySet())getConfig().set("channels."+entry.getKey(),entry.getValue().getAsString());saveConfig();installNicknameHook();});getLogger().info("READY: staff system with "+github.scarsz.discordsrv.dependencies.jda.api.JDAInfo.VERSION);}finally{starting=false;}},null);}
   else if(ready&&maintenance.compareAndSet(false,true))submit(()->{try{engine.tick();}finally{maintenance.set(false);}},null);
  },100,600);
 }
 @Subscribe public void linked(AccountLinkedEvent e){submit(()->engine.linked(e.getUser().getId()),null);}
 private interface Job{void run()throws Exception;}
 private void submit(Job job,String errorChannel){if(worker==null||worker.isShutdown())return;try{worker.execute(()->{try{if(engine!=null||starting)job.run();}catch(Exception e){getLogger().warning("Staff operation failed: "+e.getClass().getSimpleName()+": "+e.getMessage());if(errorChannel!=null&&engine!=null)try{engine.say(errorChannel,"❌ 처리하지 못했습니다: "+safeError(e)+"\n일부 작업이 이미 저장된 경우 자동 복구하며, 중복 등록하지 마세요.");engine.flush();}catch(Exception nested){getLogger().warning("Failure notification unavailable: "+nested.getClass().getSimpleName());}}});}catch(RejectedExecutionException e){starting=false;maintenance.set(false);getLogger().warning("Staff job queue full; request rejected");if(errorChannel!=null&&attached!=null&&attached.getTextChannelById(errorChannel)!=null)attached.getTextChannelById(errorChannel).sendMessage("요청이 많습니다. 잠시 후 다시 시도하세요.").queue();}}
 private String safeError(Exception e){if(e instanceof IllegalArgumentException)return e.getMessage();if(e instanceof DiscordApi.Failure f)return f.status==403?"봇 권한 또는 역할 계층을 확인해 주세요.":"Discord API 오류 ("+f.status+"/"+f.code+")";return "서버 로그를 확인해 주세요.";}
 private String[] linkedIdentity(String id){try{return Bukkit.getScheduler().callSyncMethod(this,()->{var uuid=DiscordSRV.getPlugin().getAccountLinkManager().getUuid(id);if(uuid==null)return null;String name=Bukkit.getOfflinePlayer(uuid).getName();Object t=Variables.getVariable("r2.title::"+uuid,null,false);return new String[]{name==null?"미연동":name,t==null?"생존자":t.toString()};}).get(10,TimeUnit.SECONDS);}catch(Exception e){getLogger().warning("Linked identity unavailable for "+id);return null;}}
 private void requestNickname(String id){if(!ready||!nicknameJobs.add(id))return;submit(()->{try{engine.nickname(id);}finally{nicknameJobs.remove(id);}},null);}
 private boolean titleManaged(Member member){return member!=null&&member.getGuild().getId().equals(guild())&&member.getRoles().stream().anyMatch(r->r.getId().equals(StaffEngine.STAFF)||r.getId().equals(StaffEngine.ADMIN)||r.getId().equals(StaffEngine.OWNER));}
 private void installNicknameHook(){try{if(replacement!=null)return;updaterField=DiscordSRV.class.getDeclaredField("nicknameUpdater");updaterField.setAccessible(true);previous=(NicknameUpdater)updaterField.get(DiscordSRV.getPlugin());if(previous==null)throw new IllegalStateException("DiscordSRV nickname updater missing");replacement=new NicknameUpdater(){@Override public void setNickname(Member member,OfflinePlayer player){if(titleManaged(member)){requestNickname(member.getId());return;}previous.setNickname(member,player);}};previous.interrupt();updaterField.set(DiscordSRV.getPlugin(),replacement);replacement.start();getLogger().info("Staff/admin Minecraft title nickname sync installed");}catch(Exception e){ready=false;getLogger().severe("Cannot install nickname protection: "+e.getMessage());}}
 @Override public void onDisable(){ready=false;if(bridge!=null)bridge.stop();if(standalone)return;DiscordSRV.api.unsubscribe(this);if(attached!=null)attached.removeEventListener(events);if(replacement!=null){replacement.interrupt();try{NicknameUpdater restored=new NicknameUpdater();updaterField.set(DiscordSRV.getPlugin(),restored);if(DiscordSRV.getPlugin().isEnabled())restored.start();}catch(Exception e){getLogger().warning("Nickname restore failed; restart DiscordSRV/server: "+e.getClass().getSimpleName());}}if(worker!=null){worker.shutdownNow();try{worker.awaitTermination(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
}



