package questions;

import core.*;
import com.sun.net.httpserver.*;
import java.util.*;



/** Teacher answer review and per-subject exam timer routes. */
public final class TeacherRoutes {
    private TeacherRoutes() { }

    public static boolean handle(HttpExchange x, Application.User user, String path, Map<String, String> form) throws Exception {
        if (path.equals("/exam-settings") && user.role.equals("teacher")) {
            if (x.getRequestMethod().equals("POST")) {
                Application.DB.setExamDuration(user,form.getOrDefault("subject",""),form.getOrDefault("durationMinutes",""));
                Application.redirect(x,"/exam-settings"); return true;
            }
            Application.page(x,Views.shell(user,"Exam timers",Views.examSettings(Application.DB,user))); return true;
        }
        if (path.equals("/answers") && user.role.equals("teacher")) {
            if (x.getRequestMethod().equals("POST")) {
                Application.DB.grade(Integer.parseInt(form.get("responseId")),Integer.parseInt(form.get("marks")),user.username);
                Application.redirect(x,"/answers"); return true;
            }
            Application.page(x,Views.shell(user,"Review written answers",Views.answers(Application.DB,user))); return true;
        }
        return false;
    }
}
