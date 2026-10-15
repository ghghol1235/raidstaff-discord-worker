package raid.staff;
import com.google.gson.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import static raid.staff.Store.*;
import static raid.staff.DiscordApi.obj;

/** All methods execute on one worker. Pending operations are persisted and replayed idempotently. */
public final class StaffEngine {
 public final Store db;public final DiscordApi api;public final String guild;public String bot,owner;
 public static final String STAFF="1550702397360316436",ADMIN="1550702225830183012",OWNER="1550701402022481950",VERIFIED="1550725473976324158",UNVERIFIED="1557360700718977045",APPLICANT="1557392146368172192";
 public static final long VIEW=1L<<10,SEND=1L<<11,HISTORY=1L<<16;
 private JsonArray roles;private final Consumer<String> log;private final Function<String,String[]> linked;
 public StaffEngine(Store db,DiscordApi api,String guild,Consumer<String> log,Function<String,String[]> linked){this.db=db;this.api=api;this.guild=guild;this.log=log;this.linked=linked;}
 void log(String text){log.accept(text);}
 String gp(){return "/guilds/"+guild;}String mp(String id){return gp()+"/members/"+id;}
 public static boolean has(JsonObject member,String role){if(member==null||!member.has("roles"))return false;for(JsonElement r:member.getAsJsonArray("roles"))if(r.getAsString().equals(role))return true;return false;}
 public static boolean manager(JsonObject m){return has(m,ADMIN)||has(m,OWNER);}
 JsonObject member(String id)throws Exception{return api.get(mp(id));}
 JsonObject maybeMember(String id)throws Exception{try{return member(id);}catch(DiscordApi.Failure e){if(e.code==10007||e.code==10013)return null;throw e;}}
 JsonObject role(String id){for(JsonElement e:roles)if(s(e.getAsJsonObject(),"id").equals(id))return e.getAsJsonObject();throw new IllegalStateException("필수 역할이 없습니다: "+id);}
 int position(String id){return role(id).get("position").getAsInt();}
 long perms(JsonObject m){long n=Long.parseLong(s(role(guild),"permissions"));for(JsonElement e:m.getAsJsonArray("roles"))n|=Long.parseLong(s(role(e.getAsString()),"permissions"));return n;}
 void permission(long needed)throws Exception{long p=perms(member(bot));if((p&8)==0&&(p&needed)!=needed)throw new IllegalArgumentException("봇에게 필요한 Discord 권한이 없습니다. 필요 비트="+needed);}
 int top(JsonObject m){int n=0;for(JsonElement r:m.getAsJsonArray("roles"))n=Math.max(n,position(r.getAsString()));return n;}
 void editable(String id,String roleId)throws Exception{permission(1L<<28);JsonObject self=member(bot);if(top(self)<=position(roleId))throw new IllegalArgumentException("역할 계층 때문에 해당 작업을 수행할 수 없습니다.");if(id.equals(owner))throw new IllegalArgumentException("서버 소유자는 관리 대상에서 제외됩니다.");JsonObject target=member(id);if(top(self)<=top(target))throw new IllegalArgumentException("역할 계층 때문에 해당 작업을 수행할 수 없습니다.");}
 void addRole(String id,String roleId,String reason)throws Exception{editable(id,roleId);if(!has(member(id),roleId))api.put(mp(id)+"/roles/"+roleId,null,reason);}
 void removeRole(String id,String roleId,String reason)throws Exception{JsonObject m=maybeMember(id);if(m==null||!has(m,roleId))return;editable(id,roleId);api.delete(mp(id)+"/roles/"+roleId,reason);}
 public String channel(String key){return s(db.data.getAsJsonObject("channels"),key);}
 JsonObject overwrite(String id,int type,long allow,long deny){return obj("id",id,"type",type,"allow",Long.toString(allow),"deny",Long.toString(deny));}
 JsonArray acl(String access){JsonArray a=new JsonArray();a.add(overwrite(guild,0,0,VIEW));a.add(overwrite(OWNER,0,VIEW|SEND|HISTORY,0));a.add(overwrite(ADMIN,0,VIEW|SEND|HISTORY,0));a.add(overwrite(bot,1,VIEW|SEND|HISTORY,0));if(access!=null)a.add(overwrite(access,0,VIEW|SEND|HISTORY,0));return a;}
 public void setup()throws Exception {
  bot=s(api.get("/users/@me"),"id");owner=s(api.get(gp()),"owner_id");roles=api.list(gp()+"/roles");for(String id:List.of(STAFF,ADMIN,OWNER,VERIFIED,UNVERIFIED,APPLICANT))role(id);
  permission((1L<<4)|(1L<<28)|(1L<<7)|(1L<<27)|VIEW|SEND|HISTORY);
  // Exact least-privilege staff role. Never administrator, ban, guild/roles/channels/webhooks/audit management.
  long staffPerms=VIEW|SEND|HISTORY|(1L<<13)|(1L<<40)|(1L<<1)|(1L<<20)|(1L<<21)|(1L<<22)|(1L<<23)|(1L<<24)|(1L<<35)|(1L<<36)|(1L<<38);
  if(top(member(bot))<=position(STAFF))throw new IllegalArgumentException("봇 역할을 운영진 역할보다 위로 올려 주세요.");
  if(Long.parseLong(s(role(STAFF),"permissions"))!=staffPerms)api.patch(gp()+"/roles/"+STAFF,obj("permissions",Long.toString(staffPerms)),"RaidStaff: 운영진 최소 권한 설정");
  JsonArray channels=api.list(gp()+"/channels");
  ensure(channels,"management","1557363292786393208","관리-운영진",0,null,STAFF);
  ensure(channels,"applicant","1557588071804047360","운영진-대기자-채널",0,null,APPLICANT);
  String category=ensure(channels,"interview-category","","운영진 면접",4,null,null);
  for(int i=1;i<=3;i++)ensure(channels,"interview-"+i,"","면접방-"+i,0,category,null);
  if(!db.data.has("auditCursor")){JsonArray a=api.get(gp()+"/audit-logs?limit=1").getAsJsonArray("audit_log_entries");db.data.addProperty("auditCursor",a.isEmpty()?"0":s(a.get(0).getAsJsonObject(),"id"));db.save();}
  for(var e:db.people().entrySet()){JsonObject p=e.getValue().getAsJsonObject();if(p.has("pending"))continue;if(s(p,"status").equals("면접중")&&!s(p,"room").isEmpty()){String room=s(p,"room");if(roomExists(room)){grant(s(p,"id"),room);}else{p.addProperty("status","면접완료");p.remove("room");p.addProperty("interruptedAt",now());db.save();log("삭제된 면접방의 지원자를 결과 대기로 복구: "+e.getKey());}}}
  for(JsonObject m:members()){String id=s(m.getAsJsonObject("user"),"id");try{verification(id);if(has(m,STAFF)||has(m,ADMIN)||has(m,OWNER))nickname(id);}catch(Exception ex){log("멤버 초기 동기화 불가 user="+id+": "+ex.getMessage());}}
  out("setup:"+channel("interview-1")+":"+channel("interview-2")+":"+channel("interview-3"),channel("management"),"✅ 운영진 면접 시스템 채널 설정 완료\n"+"면접방 1: <#"+channel("interview-1")+">\n면접방 2: <#"+channel("interview-2")+">\n면접방 3: <#"+channel("interview-3")+">");
  tick();
 }
 String ensure(JsonArray list,String key,String defaultId,String name,int type,String parent,String access)throws Exception {
  String saved=channel(key);String wanted=saved.isEmpty()?defaultId:saved;JsonObject found=null;
  for(JsonElement e:list){JsonObject c=e.getAsJsonObject();if(s(c,"id").equals(wanted)&&c.get("type").getAsInt()==type)found=c;}
  if(found==null){List<JsonObject> matches=new ArrayList<>();for(JsonElement e:list){JsonObject c=e.getAsJsonObject();if(s(c,"name").equals(name)&&c.get("type").getAsInt()==type)matches.add(c);}if(matches.size()>1)throw new IllegalArgumentException("동일 용도 채널이 여러 개입니다. channels."+key+" ID를 지정하세요.");if(matches.size()==1)found=matches.get(0);}
  if(found==null){JsonObject body=obj("name",name,"type",type,"permission_overwrites",acl(access));if(parent!=null)body.addProperty("parent_id",parent);found=api.call("POST",gp()+"/channels",body,"RaidStaff: 필수 채널 생성").getAsJsonObject();list.add(found);}
  String id=s(found,"id");db.data.getAsJsonObject("channels").addProperty(key,id);db.save();
  JsonArray desired=acl(access);
  // Retain only the currently assigned applicant, never stale permission overrides from prior interviews.
  JsonObject occupant=db.occupant(id);if(occupant!=null && (!occupant.has("pending") || occupant.getAsJsonObject("pending").has("roomCleaned")))desired.add(overwrite(s(occupant,"id"),1,VIEW|SEND|HISTORY,0));
  api.patch("/channels/"+id,obj("permission_overwrites",desired),"RaidStaff: 채널 접근 범위 동기화");return id;
 }
 boolean roomExists(String id){for(int i=1;i<=3;i++)if(channel("interview-"+i).equals(id))return true;return false;}
 void grant(String id,String room)throws Exception{permission((1L<<28)|(1L<<4));member(id);api.put("/channels/"+room+"/permissions/"+id,overwrite(id,1,VIEW|SEND|HISTORY,0),"RaidStaff: 면접 대상자 접근");}
 void clearRooms(String id)throws Exception{permission(1L<<28);for(int i=1;i<=3;i++)api.delete("/channels/"+channel("interview-"+i)+"/permissions/"+id,"RaidStaff: 면접 접근 종료");}
 List<JsonObject> members()throws Exception{List<JsonObject> result=new ArrayList<>();String after="0";while(true){JsonArray a=api.list(gp()+"/members?limit=1000&after="+after);for(JsonElement e:a)result.add(e.getAsJsonObject());if(a.size()<1000)break;after=s(a.get(a.size()-1).getAsJsonObject().getAsJsonObject("user"),"id");}return result;}
 String resolve(String text,boolean history)throws Exception {
  String stripped=text.trim().replaceAll("^<@!?|>$","");if(stripped.matches("[0-9]{17,20}")){if(maybeMember(stripped)!=null||history&&db.people().has(stripped))return stripped;throw new IllegalArgumentException("해당 사용자를 찾을 수 없습니다.");}
  Set<String> matches=new HashSet<>();for(JsonObject m:members()){JsonObject u=m.getAsJsonObject("user");if(text.equals(s(m,"nick"))||text.equals(s(u,"global_name"))||text.equals(s(u,"username")))matches.add(s(u,"id"));}
  if(history)for(var e:db.people().entrySet())if(text.equals(s(e.getValue().getAsJsonObject(),"name")))matches.add(e.getKey());
  if(matches.isEmpty())throw new IllegalArgumentException("해당 사용자를 찾을 수 없습니다.");if(matches.size()>1)throw new IllegalArgumentException("같은 닉네임의 사용자가 여러 명 있습니다. 멘션 또는 Discord 사용자 ID로 지정해 주세요.");return matches.iterator().next();
 }
 void out(String key,String channel,String content)throws Exception{if(content.length()>1900){int part=0;StringBuilder b=new StringBuilder();for(String line:content.split("\\n")){if(b.length()+line.length()+1>1850){out(key+":"+part++,channel,b.toString());b.setLength(0);}b.append(line).append('\n');}if(!b.isEmpty())out(key+":"+part,channel,b.toString());return;}JsonObject box=db.data.getAsJsonObject("outbox");if(!box.has(key)){String nonce=Long.toUnsignedString(new Random().nextLong());box.add(key,obj("channel",channel,"content",content,"nonce",nonce,"created",now()));db.save();}}
 public void flush()throws Exception{for(var e:new ArrayList<>(db.data.getAsJsonObject("outbox").entrySet())){JsonObject m=e.getValue().getAsJsonObject();if(m.has("sent"))continue;JsonArray users=new JsonArray();Matcher match=Pattern.compile("<@!?([0-9]+)>").matcher(s(m,"content"));while(match.find())users.add(match.group(1));JsonArray allowedRoles=new JsonArray();if(s(m,"content").contains("<@&"+STAFF+">"))allowedRoles.add(STAFF);JsonObject body=obj("content",s(m,"content"),"nonce",s(m,"nonce"),"enforce_nonce",true,"allowed_mentions",obj("parse",new JsonArray(),"users",users,"roles",allowedRoles));JsonObject sent=api.call("POST","/channels/"+s(m,"channel")+"/messages",body,null).getAsJsonObject();m.addProperty("sent",s(sent,"id"));db.save();}}
 void say(String channel,String content)throws Exception{out("reply:"+UUID.randomUUID(),channel,content);}
 void dm(String id,String text){try{String c=s(api.call("POST","/users/@me/channels",obj("recipient_id",id),null).getAsJsonObject(),"id");say(c,text);}catch(Exception e){log("DM 전송 불가 user="+id+": "+e.getMessage());}}
 public void verification(String id)throws Exception{JsonObject m=maybeMember(id);if(m==null||m.getAsJsonObject("user").has("bot")&&m.getAsJsonObject("user").get("bot").getAsBoolean())return;if(has(m,VERIFIED)){removeRole(id,UNVERIFIED,"RaidStaff: 인증완료 후 미인증 역할 제거");}else if(!has(m,UNVERIFIED)){addRole(id,UNVERIFIED,"RaidStaff: 신규 회원 인증 안내");}}
 public void linked(String id)throws Exception{JsonObject m=maybeMember(id);if(m==null)return;addRole(id,VERIFIED,"RaidStaff: DiscordSRV 계정 연동 완료");removeRole(id,UNVERIFIED,"RaidStaff: 계정 연동 완료");if(has(m,STAFF))nickname(id);}
 public void nickname(String id)throws Exception{String[] mc=linked.apply(id);if(mc!=null)syncMinecraftNickname(id,mc[0],mc[1]);}
 public void syncMinecraftNickname(String id,String mcname,String title)throws Exception{
  JsonObject m=maybeMember(id);if(m==null)return;boolean staff=has(m,STAFF),admin=has(m,ADMIN)||has(m,OWNER);JsonObject p=db.person(id);boolean remembered=p.has("staffNickname");if(!staff&&!admin&&!remembered)return;if(id.equals(owner)||top(member(bot))<=top(m)){log("닉네임 변경 불가(소유자/역할 계층): "+id);return;}permission(1L<<27);String old=s(m,"nick");String[] pieces=old.split("\\s*\\|\\s*");boolean oldFormat=pieces.length>=3||(pieces.length==2&&pieces[1].matches("^\\s*\\[.*\\].*$"));String dn=oldFormat?pieces[0]:s(m.getAsJsonObject("user"),"global_name");if(dn.isBlank())dn=s(m.getAsJsonObject("user"),"username");String safeTitle=title==null||title.isBlank()?"생존자":title;String suffix=" | ["+safeTitle+"] "+mcname;int max=32-suffix.length();if(max<1){mcname=mcname.substring(0,Math.min(mcname.length(),12));suffix=" | ["+safeTitle+"] "+mcname;max=Math.max(1,32-suffix.length());}String nick=dn.substring(0,Math.min(dn.length(),max))+suffix;if(!nick.equals(old))api.patch(mp(id),obj("nick",nick),"RaidStaff: MC 칭호 동기화");if(staff)p.addProperty("staffNickname",true);else if(remembered)p.remove("staffNickname");db.save();
 } public record Command(String op,String target,String value){}
 public static Command parse(String text){text=text.strip();if(text.equals("운영진 명령어"))return new Command("help","","");if(text.equals("운영진 면접목록"))return new Command("queue","","");if(text.equals("면접방 상태"))return new Command("rooms","","");if(text.equals("면접시작"))return new Command("start","","");if(text.equals("면접 순번"))return new Command("position","","");if(text.startsWith("운영진 대기자 "))return new Command("register",text.substring(8).strip(),"");if(text.startsWith("운영진 지원이력 "))return new Command("history",text.substring(9).strip(),"");Matcher m=Pattern.compile("^(.+?)\\s+(면접 완료|면접 취소|면접메모|면접점수|운영진 합격|운영진 불합격|운영진 인증|운영진 자격 박탈|운영진 경고조회|운영진 경고해제|운영진 경고|운영진 휴가|운영진 활동중지|운영진 복귀|운영진 상태)(?:\\s+(.*))?$").matcher(text);if(!m.matches())return null;String op=switch(m.group(2)){case "면접 완료"->"done";case "면접 취소"->"cancel";case "면접메모"->"note";case "면접점수"->"score";case "운영진 합격"->"pass";case "운영진 불합격"->"fail";case "운영진 인증"->"certify";case "운영진 자격 박탈"->"revoke";case "운영진 경고조회"->"warnings";case "운영진 경고해제"->"clearwarning";case "운영진 경고"->"warn";case "운영진 휴가"->"leave";case "운영진 활동중지"->"suspend";case "운영진 복귀"->"resume";default->"status";};return new Command(op,m.group(1).strip(),m.group(3)==null?"":m.group(3).strip());}
 public void command(String actor,String channel,String message,String text)throws Exception{
  Command c=parse(text);if(c==null)return;JsonObject author=member(actor);
  // Gateway delivery can repeat after reconnect. Claim each Discord message once.
  JsonObject handled=db.data.getAsJsonObject("handledCommands");if(handled==null){handled=new JsonObject();db.data.add("handledCommands",handled);}if(handled.has(message))return;handled.addProperty(message,now());db.save();
  try{
   if(c.op.equals("position")){if(!channel.equals(channel("applicant")))throw new IllegalArgumentException("이 명령어는 지정된 채널에서만 사용할 수 있습니다.");if(!has(author,APPLICANT)||!db.people().has(actor))throw new IllegalArgumentException("현재 운영진 면접 대기자가 아닙니다.");JsonObject p=db.person(actor);if(s(p,"status").equals("대기")){long ahead=db.people().entrySet().stream().map(e->e.getValue().getAsJsonObject()).filter(x->s(x,"status").equals("대기")&&n(x,"sequence")<n(p,"sequence")).count();say(channel,"현재 면접 대기 순번은 "+(ahead+1)+"번입니다. 앞에 "+ahead+"명이 대기 중입니다.");}else say(channel,"면접 상태: "+s(p,"status")+(s(p,"room").isEmpty()?"":" · <#"+s(p,"room")+">"));return;}
   if(!manager(author))throw new IllegalArgumentException("이 명령어를 사용할 권한이 없습니다.");
   if(c.op.equals("start")){if(!roomExists(channel))throw new IllegalArgumentException("면접시작은 면접방-1/2/3에서만 사용할 수 있습니다.");if(db.occupant(channel)!=null)throw new IllegalArgumentException("현재 이 면접방에서는 이미 면접이 진행 중입니다.");JsonObject next=db.next();if(next==null)throw new IllegalArgumentException("현재 운영진 면접 대기자가 없습니다.");String id=s(next,"id");member(id);db.assign(id,channel,actor);pending(next,"start",actor,message,"");apply(next);return;}
   if(!channel.equals(channel("management"))&&!(c.op.equals("done")&&roomExists(channel)))throw new IllegalArgumentException("이 명령어는 지정된 채널에서만 사용할 수 있습니다.");
   if(c.op.equals("help")){say(channel,HELP);return;}
   if(c.op.equals("rooms")){StringBuilder b=new StringBuilder("📋 면접방 현황\n");for(int i=1;i<=3;i++){String r=channel("interview-"+i);JsonObject p=db.occupant(r);b.append("<#").append(r).append(">: ").append(p==null?"비어있음":"사용중 · <@"+s(p,"id")+"> · 진행자 <@"+s(p,"interviewer")+">").append('\n');}say(channel,b.toString());return;}
   if(c.op.equals("queue")){StringBuilder b=new StringBuilder("📋 운영진 면접 목록\n");db.people().entrySet().stream().map(e->e.getValue().getAsJsonObject()).filter(p->p.has("appliedAt")).sorted(Comparator.comparingLong(p->n(p,"sequence"))).forEach(p->b.append(n(p,"sequence")).append(". <@").append(s(p,"id")).append("> - ").append(s(p,"status")).append(s(p,"room").isEmpty()?"":" <#"+s(p,"room")+">").append('\n'));say(channel,b.toString());return;}
   boolean history=Set.of("history","status","warnings","note","score","clearwarning","revoke","cancel").contains(c.op);String id=resolve(c.target,history);JsonObject m=maybeMember(id);JsonObject p=db.person(id);if(p.has("pending"))throw new IllegalArgumentException("이 대상의 이전 작업을 복구 중입니다. 잠시 후 다시 시도하세요.");
   if(c.op.equals("history")||c.op.equals("status")){say(channel,"📋 <@"+id+"> 기록\n상태: "+s(p,"status")+" / 운영진: "+s(p,"staffState")+"\n지원일: "+s(p,"appliedAt")+"\n최초 순번: "+n(p,"sequence")+"\n운영진 등록일: "+s(p,"staffSince")+"\n경고: "+Store.warnings(p)+"\n면접 점수: "+s(p,"scoreText")+"\n재지원 가능: "+(p.has("appliedAt")?"아니요":"지원 이력 없음"));return;}
   if(c.op.equals("warnings")){StringBuilder b=new StringBuilder("⚠️ 활성 경고: "+Store.warnings(p)+"\n");for(JsonElement e:p.getAsJsonArray("warnings")){JsonObject w=e.getAsJsonObject();b.append(s(w,"reason")).append(" · 처리자 <@").append(s(w,"actor")).append("> · ").append(s(w,"at")).append(w.has("clearedAt")?" · 해제됨 "+s(w,"clearedAt"):" · 활성").append('\n');}say(channel,b.toString());return;}
   if(c.op.equals("note")||c.op.equals("score")){if(!p.has("appliedAt"))throw new IllegalArgumentException("지원 이력이 없습니다.");if(c.value.equals("보기")){if(c.op.equals("score"))say(channel,"📊 점수: "+s(p,"scoreText"));else{StringBuilder b=new StringBuilder("📝 면접 메모\n");for(JsonElement e:p.getAsJsonArray("notes")){JsonObject note=e.getAsJsonObject();b.append(s(note,"text")).append(" · <@").append(s(note,"actor")).append("> · ").append(s(note,"at")).append('\n');}say(channel,b.toString());}return;}if(c.value.isEmpty())throw new IllegalArgumentException("내용 또는 점수를 입력해 주세요.");if(c.op.equals("note"))p.getAsJsonArray("notes").add(obj("text",c.value,"actor",actor,"at",now()));else{int[] scores=Store.scores(c.value);p.add("scores",Store.GSON.toJsonTree(scores));p.addProperty("scoreText",scores[0]+"/5 · "+scores[1]+"/5 · "+scores[2]+"/5 (총 "+Arrays.stream(scores).sum()+"/15)");p.addProperty("scoredBy",actor);p.addProperty("scoredAt",now());}db.save();say(channel,"✅ 저장했습니다.");return;}
   if(c.op.equals("register")){if(has(m,STAFF))throw new IllegalArgumentException("이미 운영진입니다.");if(has(m,APPLICANT))throw new IllegalArgumentException("이미 운영진 대기자로 등록되어 있습니다.");editable(id,APPLICANT);db.register(id,s(m.getAsJsonObject("user"),"username"),actor);pending(p,"register",actor,message,"");apply(p);return;}
   if(c.op.equals("done")){if(!s(p,"status").equals("면접중"))throw new IllegalArgumentException("현재 면접 중인 지원자가 아닙니다.");if(!channel.equals(channel("management"))&&!channel.equals(s(p,"room")))throw new IllegalArgumentException("해당 지원자의 면접방에서만 완료할 수 있습니다.");}
   if(Set.of("cancel","pass","fail").contains(c.op)){if(!Set.of("대기","면접중","면접완료").contains(s(p,"status")))throw new IllegalArgumentException("진행 중인 지원 이력이 없습니다.");}
   if(c.op.equals("certify")){if(actor.equals(id))throw new IllegalArgumentException("자기 자신 인증은 불가능합니다.");if(has(m,STAFF))throw new IllegalArgumentException("이미 운영진으로 등록되어 있습니다.");}
   if(Set.of("pass","certify").contains(c.op))editable(id,STAFF);
   if(Set.of("warn","clearwarning","leave","suspend","resume","revoke").contains(c.op)&&!has(m,STAFF)&&s(p,"staffSince").isEmpty())throw new IllegalArgumentException("운영진 기록이 없습니다.");
   if(c.op.equals("warn")){if(c.value.isEmpty())throw new IllegalArgumentException("경고 사유를 입력해 주세요.");db.warn(id,actor,c.value);db.save();int count=Store.warnings(p);say(channel,"⚠️ <@"+id+"> 운영진 경고 "+count+"회 · 사유: "+c.value+(count>=3?"\n강한 경고: 자격 박탈 여부는 관리자가 직접 결정하세요.":count==2?"\n주의: 경고 2회입니다.":""));dm(id,"⚠️ 운영진 경고 "+count+"회 · 사유: "+c.value);return;}
   if(c.op.equals("clearwarning")){db.clearWarning(id,actor);db.save();say(channel,"✅ 가장 최근 활성 경고 1개를 해제했습니다. 남은 경고: "+Store.warnings(p));return;}
   if(Set.of("leave","suspend","resume").contains(c.op)){p.addProperty("staffState",c.op.equals("leave")?"휴가중":c.op.equals("suspend")?"활동중지":"활동중");p.addProperty("stateBy",actor);p.addProperty("stateAt",now());db.save();say(channel,"✅ <@"+id+"> 상태: "+s(p,"staffState"));return;}
   pending(p,c.op,actor,message,c.value);apply(p);
  }catch(IllegalArgumentException e){say(channel,"❌ "+e.getMessage());}finally{flush();}
 }
 void pending(JsonObject p,String op,String actor,String key,String value)throws Exception{p.add("pending",obj("op",op,"actor",actor,"key",key,"value",value,"at",now()));db.save();}
 void archiveRoom(String room)throws Exception{java.nio.file.Path dir=db.file.getParent().resolve("interview-archives");java.nio.file.Files.createDirectories(dir);while(true){JsonArray messages=api.list("/channels/"+room+"/messages?limit=100");if(messages.isEmpty())return;java.nio.file.Path file=dir.resolve(room+"-"+s(messages.get(0).getAsJsonObject(),"id")+".json");java.nio.file.Files.writeString(file,Store.GSON.toJson(messages));for(JsonElement e:messages)api.delete("/channels/"+room+"/messages/"+s(e.getAsJsonObject(),"id"),"RaidStaff: 이전 면접 비공개 보관 후 대화 초기화");}}
 void apply(JsonObject p)throws Exception{
  JsonObject op=p.getAsJsonObject("pending");if(op==null)return;db.save();String kind=s(op,"op"),id=s(p,"id"),actor=s(op,"actor"),key=s(op,"key");String reason="RaidStaff:"+kind+":"+key;
  switch(kind){
   case "register"->{addRole(id,APPLICANT,reason);out(key,channel("applicant"),"📋 <@"+id+">님이 운영진 면접 대기자로 등록되었습니다. 최초 순번: "+n(p,"sequence")+"번\n현재 대기 위치는 `면접 순번`으로 확인하세요.");}
   case "start"->{String room=s(p,"room");if(!roomExists(room))throw new IllegalStateException("면접방 ID가 사라졌습니다. 관리자 확인 필요");if(!op.has("roomCleaned")){archiveRoom(room);op.addProperty("roomCleaned",true);db.save();}grant(id,room);out(key,room,"📋 운영진 면접을 시작합니다.\n지원자: <@"+id+">\n최초 순번: "+n(p,"sequence")+"\n진행자: <@"+actor+">\n이 텍스트 채널에서 면접을 진행해 주세요.");out(key+":call",channel("applicant"),"🔔 <@"+id+">님, 면접 차례입니다. <#"+room+">을 확인해 주세요.");dm(id,"운영진 면접 차례입니다. <#"+room+">을 확인해 주세요.");}
   case "done","cancel","fail","pass","certify","revoke"->{
    clearRooms(id);
    if(!kind.equals("done"))removeRole(id,APPLICANT,reason);
    if(kind.equals("pass")||kind.equals("certify")){addRole(id,STAFF,reason);p.addProperty("staffState","활동중");if(!p.has("staffSince"))p.addProperty("staffSince",now());p.addProperty("staffBy",actor);if(p.has("appliedAt"))db.end(id,"합격",actor);nickname(id);out(key,channel("management"),"🎉 <@"+id+">님, 운영진이 되신 것을 축하합니다! <@&"+STAFF+">");dm(id,"운영진으로 최종 인증되었습니다. 축하합니다!");}
    else if(kind.equals("revoke")){removeRole(id,STAFF,reason);p.addProperty("staffState","자격박탈");p.addProperty("revokedAt",now());p.addProperty("revokedBy",actor);p.addProperty("revokeReason",s(op,"value").isBlank()?"없음":s(op,"value"));p.remove("room");nickname(id);out(key,channel("management"),"⛔ <@"+id+"> 운영진 자격 박탈 · 사유: "+s(p,"revokeReason"));dm(id,"운영진 자격을 박탈당했습니다. 사유: "+s(p,"revokeReason"));}
    else {String state=kind.equals("done")?"면접완료":kind.equals("cancel")?"취소":"불합격";db.end(id,state,actor);out(key,channel("management"),"✅ <@"+id+"> 처리: "+state+(kind.equals("done")?" · 결과 대기 상태입니다. 다음 지원자는 자동 호출하지 않습니다.":" · 지원 이력은 유지되며 재지원할 수 없습니다."));if(kind.equals("fail"))dm(id,"운영진 면접 결과 불합격되었습니다.");}
   }
   default->throw new IllegalArgumentException("알 수 없는 저장 작업: "+kind);
  }
  p.remove("pending");db.save();
 }
 public static final String HELP="""
 📖 운영진 시스템 명령어 (접두사 ! / 없이 채팅)
 서버장·관리자 역할만 관리 명령 사용 가능. 운영진 역할만으로는 불가.
 [관리/운영진 채널]
 운영진 대기자 <닉네임/멘션/ID>
 운영진 면접목록
 운영진 지원이력 <닉네임/멘션/ID>
 면접방 상태
 <대상> 면접 완료
 <대상> 면접 취소
 <대상> 면접메모 <내용> / <대상> 면접메모 보기
 <대상> 면접점수 <친절도 1~5> <활동성 1~5> <상황대처 1~5>
 <대상> 면접점수 보기
 <대상> 운영진 합격 / <대상> 운영진 불합격
 <대상> 운영진 인증
 <대상> 운영진 상태
 <대상> 운영진 경고 <사유>
 <대상> 운영진 경고조회 / <대상> 운영진 경고해제
 <대상> 운영진 휴가 / <대상> 운영진 활동중지 / <대상> 운영진 복귀
 <대상> 운영진 자격 박탈 [사유]
 운영진 명령어
 [면접방-1/2/3]
 면접시작 — 가장 앞 대기자 정확히 1명. 종료 후 자동 호출 없음.
 <대상> 면접 완료 — 해당 지원자 방 또는 관리 채널.
 [대기자 채널·본인만]
 면접 순번
 대기자 수동 등록만 가능. 과거 등록자는 취소/불합격이어도 재지원 불가.
 """;
 public void left(String id)throws Exception{JsonObject p=db.people().getAsJsonObject(id);if(p==null)return;if(p.has("staffSince")||p.has("staffNickname")){p.addProperty("staffState","서버 퇴장");p.addProperty("leftAt",now());if(System.currentTimeMillis()-n(p,"departureAuditTime")>60000)p.addProperty("departurePending",System.currentTimeMillis());}if(Set.of("대기","면접중","면접완료").contains(s(p,"status"))){clearRooms(id);db.end(id,"취소",bot);p.remove("pending");}db.save();}
 public void tick()throws Exception{
  roles=api.list(gp()+"/roles");
  for(var e:new ArrayList<>(db.people().entrySet())){JsonObject p=e.getValue().getAsJsonObject();if(p.has("pending")){try{apply(p);}catch(Exception ex){log("작업 복구 대기 user="+e.getKey()+": "+ex.getMessage());}}}
  audit();
  for(var e:new ArrayList<>(db.people().entrySet())){JsonObject p=e.getValue().getAsJsonObject();if(p.has("departurePending")&&System.currentTimeMillis()-n(p,"departurePending")>30000){out("left:"+e.getKey()+":"+s(p,"leftAt"),channel("management"),"🚪 운영진 <@"+e.getKey()+">님이 Discord 서버에서 퇴장했습니다. 이력은 유지하며 재가입해도 운영진 역할을 자동 지급하지 않습니다.");p.remove("departurePending");db.save();}}
  flush();
 }
 void audit()throws Exception{
  permission(1L<<7);String cursor=s(db.data,"auditCursor"),before="";List<JsonObject> entries=new ArrayList<>();boolean complete=false;
  for(int page=0;page<100;page++){JsonArray a=api.get(gp()+"/audit-logs?limit=100"+(before.isEmpty()?"":"&before="+before)).getAsJsonArray("audit_log_entries");if(a.isEmpty()){complete=true;break;}for(JsonElement e:a){JsonObject v=e.getAsJsonObject();if(Long.parseUnsignedLong(s(v,"id"))<=Long.parseUnsignedLong(cursor)){complete=true;break;}entries.add(v);}if(complete||a.size()<100){complete=true;break;}before=s(a.get(a.size()-1).getAsJsonObject(),"id");}
  if(!complete)throw new IllegalStateException("Audit backlog exceeds 10000 entries; cursor retained");
  Collections.reverse(entries);for(JsonObject a:entries){auditOne(a);db.data.addProperty("auditCursor",s(a,"id"));db.save();}
 }
 void auditOne(JsonObject a)throws Exception{
  String aid=s(a,"id"),actor=s(a,"user_id"),target=s(a,"target_id");int action=a.get("action_type").getAsInt();if(actor.isBlank()||target.isBlank()||actor.equals(bot))return;
  JsonObject exec=maybeMember(actor),victim=maybeMember(target);boolean mod=has(exec,STAFF)&&!manager(exec),protectedTarget=target.equals(owner)||manager(victim)||has(victim,STAFF)||db.people().has(target)&&Set.of("활동중","휴가중","활동중지","서버 퇴장").contains(s(db.person(target),"staffState"));String reason=s(a,"reason").isBlank()?"없음":s(a,"reason");
  if(action==25&&a.has("changes")){for(JsonElement c:a.getAsJsonArray("changes")){JsonObject change=c.getAsJsonObject();if(s(change,"key").equals("$add")&&change.has("new_value")){for(JsonElement r:change.getAsJsonArray("new_value")){if(s(r.getAsJsonObject(),"id").equals(STAFF)&&manager(exec)&&victim!=null&&has(victim,STAFF)){
    JsonObject p=db.person(target);if(!p.has("pending")){p.addProperty("name",s(victim.getAsJsonObject("user"),"username"));pending(p,"certify",actor,"audit:"+aid,"");apply(p);}return;
   }}}}return;}
  if(action==24&&a.has("changes")){for(JsonElement c:a.getAsJsonArray("changes")){JsonObject change=c.getAsJsonObject();if(!s(change,"key").equals("communication_disabled_until"))continue;String until=s(change,"new_value");String text="⏱ <@"+actor+">님이 <@"+target+">님의 타임아웃을 "+(until.isEmpty()?"해제했습니다.":"설정했습니다. 종료: "+until)+"\n사유: "+reason;
    if(mod&&protectedTarget&&!until.isEmpty()){if(victim!=null&&!target.equals(owner)&&top(member(bot))>top(victim)){permission(1L<<40);api.patch(mp(target),obj("communication_disabled_until",JsonNull.INSTANCE),"RaidStaff: 운영진 제재 보호");text="⚠️ 운영진 권한 보호\n<@"+actor+"> → <@"+target+"> 타임아웃을 감지하여 해제했습니다.";}else text+="\n⚠️ 보호 대상 제재 감지: 역할 계층 때문에 자동 복구할 수 없습니다.";}out("audit:"+aid,channel("management"),text);
   }return;}
  if(action==20||action==22||action==23){String verb=action==20?"서버에서 추방했습니다.":action==22?"차단했습니다.":"차단을 해제했습니다.";String text=(action==20?"👢":action==22?"🔨":"🔓")+" <@"+actor+">님이 <@"+target+">님을 "+verb+"\n사유: "+reason;if(mod&&action==22){permission(1L<<2);api.delete(gp()+"/bans/"+target,"RaidStaff: 운영진 금지 권한 복구");text+="\n⚠️ 운영진의 밴 권한 사용을 감지하여 밴을 해제했습니다. 퇴장한 멤버는 직접 재가입해야 합니다.";}if(mod&&protectedTarget&&action==20)text+="\n⚠️ 보호 대상 킥 감지. 이미 완료된 킥은 자동 취소할 수 없습니다. 다른 역할의 권한/계층을 확인하세요.";
    if(action!=23&&db.people().has(target)){JsonObject p=db.person(target);p.remove("departurePending");p.addProperty("staffState","서버 퇴장");p.addProperty("leftAt",now());p.addProperty("departureAudit",aid);p.addProperty("departureAuditTime",System.currentTimeMillis());}out("audit:"+aid,channel("management"),text);
  }
 }
}

