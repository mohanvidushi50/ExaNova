package core;

import admin.*;
import auth.*;
import exam.*;
import questions.*;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;



/** Small server-rendered Java web app. No JavaScript runtime or frontend build step is used. */
public final class Application {
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final Map<String, User> SESSIONS = Collections.synchronizedMap(new HashMap<>());
    public static final Store DB = new Store();

    public static void main(String[] args) throws Exception {
        DB.initialize();
        DB.recoverInterruptedSessions();
        BackgroundJobs.startBackgroundTasks(DB);
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", Application::handle);
        server.setExecutor(ExamConcurrency.requestExecutor());
        server.start();
        System.out.println("Evalio is running at http://localhost:" + port);
    }

    private static void handle(HttpExchange x) throws IOException {
        try {
            String path = x.getRequestURI().getPath();
            Map<String,String> form = x.getRequestMethod().equals("POST") ? form(x) : Map.of();
            if (path.equals("/health")) { send(x, 200, "text/plain; charset=utf-8", "ok"); return; }
            if (path.equals("/assets/style.css")) { send(x, 200, "text/css; charset=utf-8", Views.css()); return; }
            if (path.equals("/assets/exam.js")) { send(x, 200, "text/javascript; charset=utf-8", java.nio.file.Files.readString(java.nio.file.Path.of("web", "exam.js"))); return; }
            if (AuthRoutes.handle(x,path,form)) return;
            User user = currentUser(x);
            if (user == null) { redirect(x, "/login"); return; }
            if (QuestionRoutes.handle(x,user,path,form)) return;
            if (TeacherRoutes.handle(x,user,path,form)) return;
            if (StudentRoutes.handle(x,user,path,form)) return;
            if (AdminRoutes.handle(x,user,path,form)) return;
            if (path.equals("/")) { page(x,Views.shell(user,"Dashboard",Views.dashboard(DB,user))); return; }
            page(x, Views.shell(user,"Not found", "<section class='card'><h1>Page not found</h1><a href='/'>Return to dashboard</a></section>"), 404);
        } catch (IllegalArgumentException e) { page(x, Views.shell(currentUser(x),"Please check that", Views.alert(e.getMessage())), 400); }
        catch (Exception e) { e.printStackTrace(); page(x, Views.shell(currentUser(x),"Something went wrong",Views.alert("The request could not be completed. Check the MySQL connection and try again.")), 500); }
    }

    public static String query(HttpExchange x,String key){String raw=x.getRequestURI().getRawQuery();if(raw==null)return "";for(String part:raw.split("&")){String[]kv=part.split("=",2);if(kv.length>0&&dec(kv[0]).equals(key))return kv.length>1?dec(kv[1]):"";}return "";}
    public static String cookie(HttpExchange x,String key){String raw=x.getRequestHeaders().getFirst("Cookie");if(raw==null)return null;for(String p:raw.split(";")){String[]kv=p.trim().split("=",2);if(kv.length==2&&kv[0].equals(key))return kv[1];}return null;}
    private static Map<String,String> form(HttpExchange x)throws IOException{String raw=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);Map<String,String>m=new HashMap<>();for(String p:raw.split("&")){String[]kv=p.split("=",2);m.put(dec(kv[0]),kv.length>1?dec(kv[1]):"");}return m;}
    private static String dec(String s){return URLDecoder.decode(s,StandardCharsets.UTF_8);}
    public static String token(){byte[]b=new byte[32];RANDOM.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public static User currentUser(HttpExchange x){String id=cookie(x,"EVALIO");return id==null?null:SESSIONS.get(id);}
    public static void redirect(HttpExchange x,String path)throws IOException{x.getResponseHeaders().add("Location",path);x.sendResponseHeaders(303,-1);x.close();}
    public static void page(HttpExchange x,String html)throws IOException{page(x,html,200);}
    public static void page(HttpExchange x,String html,int status)throws IOException{send(x,status,"text/html; charset=utf-8",html);}
    public static void send(HttpExchange x,int status,String type,String body)throws IOException{byte[]b=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",type);x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.getResponseHeaders().set("X-Frame-Options","DENY");x.sendResponseHeaders(status,b.length);x.getResponseBody().write(b);x.close();}

    public static final class User {
        public final long id;
        public final String username;
        public final String role;
        public User(long id, String username, String role) { this.id=id; this.username=username; this.role=role; }
    }
    public enum SessionState { CREATED, RUNNING, PAUSED, TERMINATED;
        public boolean canMoveTo(SessionState next) {
            return switch (this) {
                case CREATED -> next == RUNNING || next == TERMINATED;
                case RUNNING -> next == PAUSED || next == TERMINATED;
                case PAUSED -> next == RUNNING || next == TERMINATED;
                case TERMINATED -> false;
            };
        }
    }

}

