package raid.staff;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.time.*;
import java.util.*;

/** Single worker owns this store. Atomic write before any Discord side effect. */
public final class Store {
 public static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
 public final Path file; public JsonObject data;
 public Store(Path file)throws IOException{this.file=file;if(Files.exists(file)){try{data=JsonParser.parseString(Files.readString(file)).getAsJsonObject();if(!data.has("people")||!data.has("channels"))throw new IOException("Invalid staff data structure");}catch(RuntimeException ex){throw new IOException("Invalid staff data; refusing to reset history",ex);}}else{data=new JsonObject();data.add("people",new JsonObject());data.add("channels",new JsonObject());data.add("seenAudit",new JsonObject());data.add("outbox",new JsonObject());data.addProperty("sequence",0);save();}}
 public void save()throws IOException{Files.createDirectories(file.toAbsolutePath().getParent());Path tmp=file.resolveSibling(file.getFileName()+".tmp");byte[] bytes=GSON.toJson(data).getBytes(StandardCharsets.UTF_8);try(FileChannel c=FileChannel.open(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())c.write(b);c.force(true);}if(Files.exists(file))Files.copy(file,file.resolveSibling(file.getFileName()+".bak"),StandardCopyOption.REPLACE_EXISTING);try{Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}}
 public JsonObject people(){return data.getAsJsonObject("people");}
 public JsonObject person(String id){JsonObject p=people().getAsJsonObject(id);if(p==null){p=new JsonObject();p.addProperty("id",id);p.add("warnings",new JsonArray());p.add("notes",new JsonArray());people().add(id,p);}return p;}
 public static String s(JsonObject o,String k){return o!=null&&o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
 public static long n(JsonObject o,String k){return s(o,k).isEmpty()?0:o.get(k).getAsLong();}
 public static String now(){return Instant.now().toString();}
 public void register(String id,String name,String actor){JsonObject p=person(id);if(!s(p,"appliedAt").isEmpty())throw new IllegalArgumentException("이미 운영진 지원 이력이 있어 재지원할 수 없습니다.");long seq=n(data,"sequence")+1;data.addProperty("sequence",seq);p.addProperty("name",name);p.addProperty("appliedAt",now());p.addProperty("registeredBy",actor);p.addProperty("sequence",seq);p.addProperty("status","대기");}
 public JsonObject next(){return people().entrySet().stream().map(e->e.getValue().getAsJsonObject()).filter(p->s(p,"status").equals("대기")&&!p.has("pending")).min(Comparator.comparingLong(p->n(p,"sequence"))).orElse(null);}
 public JsonObject occupant(String room){return people().entrySet().stream().map(e->e.getValue().getAsJsonObject()).filter(p->room.equals(s(p,"room"))).findFirst().orElse(null);}
 public void assign(String id,String room,String actor){JsonObject p=person(id);if(occupant(room)!=null)throw new IllegalArgumentException("현재 이 면접방에서는 이미 면접이 진행 중입니다.");if(!s(p,"status").equals("대기")||!s(p,"room").isEmpty())throw new IllegalArgumentException("지원자가 대기 상태가 아닙니다.");p.addProperty("status","면접중");p.addProperty("room",room);p.addProperty("interviewer",actor);p.addProperty("startedAt",now());}
 public void end(String id,String result,String actor){JsonObject p=person(id);p.addProperty("status",result);p.addProperty("endedAt",now());p.addProperty("processedBy",actor);p.remove("room");}
 public static int warnings(JsonObject p){int n=0;for(JsonElement e:p.getAsJsonArray("warnings"))if(!e.getAsJsonObject().has("clearedAt"))n++;return n;}
 public void warn(String id,String actor,String reason){JsonObject w=new JsonObject();w.addProperty("reason",reason);w.addProperty("actor",actor);w.addProperty("at",now());person(id).getAsJsonArray("warnings").add(w);}
 public void clearWarning(String id,String actor){JsonArray a=person(id).getAsJsonArray("warnings");for(int i=a.size()-1;i>=0;i--){JsonObject w=a.get(i).getAsJsonObject();if(!w.has("clearedAt")){w.addProperty("clearedAt",now());w.addProperty("clearedBy",actor);return;}}throw new IllegalArgumentException("해제할 운영진 경고가 없습니다.");}
 public static int[] scores(String text){String[] a=text.trim().split("\\s+");if(a.length!=3)throw new IllegalArgumentException("점수는 친절도 활동성 상황대처 순서로 1~5 세 개를 입력해 주세요.");int[] out=new int[3];try{for(int i=0;i<3;i++){out[i]=Integer.parseInt(a[i]);if(out[i]<1||out[i]>5)throw new NumberFormatException();}}catch(NumberFormatException e){throw new IllegalArgumentException("점수는 1~5 사이로 입력해 주세요.");}return out;}
}
