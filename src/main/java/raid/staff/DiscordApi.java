package raid.staff;
import java.net.*;
import java.net.http.*;
import java.time.*;
import com.google.gson.*;

/** Discord v10; one serialized worker, honours Retry-After and route reset headers. */
public class DiscordApi {
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
 private final String token,base;private long nextRequest;
 public DiscordApi(String token){this(token,"https://discord.com/api/v10");}
 public DiscordApi(String token,String base){this.token=token.replaceFirst("^Bot ","");this.base=base;}
 public static final class Failure extends RuntimeException {private static final long serialVersionUID=1L;public final int status,code;Failure(int status,int code){super("Discord HTTP "+status+" (code "+code+")");this.status=status;this.code=code;}}
 public JsonElement call(String method,String path,JsonElement body,String reason)throws Exception {
  for(int attempt=0;attempt<5;attempt++){
   long delay=nextRequest-System.currentTimeMillis();if(delay>0)Thread.sleep(delay);
   var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bot "+token).header("User-Agent","DiscordBot (https://discordsrv.com, RaidStaff-1.0)");
   if(reason!=null)b.header("X-Audit-Log-Reason",URLEncoder.encode(reason,java.nio.charset.StandardCharsets.UTF_8));
   if(body!=null)b.header("Content-Type","application/json");
   b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString()));
   HttpResponse<String> r=client.send(b.build(),HttpResponse.BodyHandlers.ofString());
   JsonElement result=r.body().isBlank()?JsonNull.INSTANCE:JsonParser.parseString(r.body());
   if(r.statusCode()==429){double retry=result.getAsJsonObject().get("retry_after").getAsDouble();if(retry>120)throw new Failure(429,0);nextRequest=System.currentTimeMillis()+(long)(retry*1000)+250;continue;}
   if(r.headers().firstValue("X-RateLimit-Remaining").orElse("1").equals("0")){double sec=Double.parseDouble(r.headers().firstValue("X-RateLimit-Reset-After").orElse("1"));nextRequest=System.currentTimeMillis()+(long)(sec*1000)+100;}
   if(r.statusCode()>=500&&attempt<4&&(!method.equals("POST") || body!=null&&body.isJsonObject()&&body.getAsJsonObject().has("enforce_nonce"))){nextRequest=System.currentTimeMillis()+1000L*(1L<<attempt);continue;}
   if(r.statusCode()<200||r.statusCode()>=300)throw new Failure(r.statusCode(),result.isJsonObject()&&result.getAsJsonObject().has("code")?result.getAsJsonObject().get("code").getAsInt():0);
   return result;
  }throw new Failure(429,0);
 }
 public JsonObject get(String p)throws Exception{return call("GET",p,null,null).getAsJsonObject();}
 public JsonArray list(String p)throws Exception{return call("GET",p,null,null).getAsJsonArray();}
 public void put(String p,JsonObject b,String reason)throws Exception{call("PUT",p,b,reason);}
 public void patch(String p,JsonObject b,String reason)throws Exception{call("PATCH",p,b,reason);}
 public void delete(String p,String reason)throws Exception{try{call("DELETE",p,null,reason);}catch(Failure e){if(e.status!=404)throw e;}}
 public static JsonObject obj(Object... a){JsonObject o=new JsonObject();for(int i=0;i<a.length;i+=2){Object v=a[i+1];o.add(a[i].toString(),v instanceof JsonElement j?j:Store.GSON.toJsonTree(v));}return o;}
}
