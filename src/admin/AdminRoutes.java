package admin;

import core.*;
import com.sun.net.httpserver.*;
import java.util.*;



/** Administrator account and system monitoring routes. */
public final class AdminRoutes {
    private AdminRoutes() { }

    public static boolean handle(HttpExchange x, Application.User user, String path, Map<String, String> form) throws Exception {
        if (path.equals("/admin/role") && x.getRequestMethod().equals("POST") && user.role.equals("admin")) {
            long targetId=Long.parseLong(form.getOrDefault("userId","0"));
            Application.DB.changeUserRole(user,targetId,form.getOrDefault("role",""));
            synchronized(Application.SESSIONS){Application.SESSIONS.entrySet().removeIf(entry->entry.getValue().id==targetId);}
            Application.redirect(x,"/admin"); return true;
        }
        if (path.equals("/admin/crash") && x.getRequestMethod().equals("POST") && user.role.equals("admin")) {
            boolean scheduled=CrashSimulator.schedule();
            Application.send(x,202,"text/plain; charset=utf-8",scheduled?"Crash demonstration scheduled. Restart the app to show recovery.":"Crash demonstration already scheduled."); return true;
        }
        if (path.equals("/monitor") && user.role.equals("admin")) {
            Application.page(x,Views.shell(user,"OS and DBMS monitor",Application.DB.renderMonitor())); return true;
        }
        if (path.equals("/admin") && user.role.equals("admin")) {
            Application.page(x,Views.shell(user,"Admin overview",Views.admin(Application.DB,user))); return true;
        }
        return false;
    }
}
