package exam;

import core.*;
import com.sun.net.httpserver.*;
import java.util.*;



/** Student exam, result, review, and anti-cheat event routes. */
public final class StudentRoutes {
    private StudentRoutes() { }

    public static boolean handle(HttpExchange x, Application.User user, String path, Map<String, String> form) throws Exception {
        if (path.matches("/exam/\\d+/event") && x.getRequestMethod().equals("POST") && user.role.equals("student")) {
            int examId = Integer.parseInt(path.split("/")[2]);
            Application.DB.recordExamEvent(user, examId, form.getOrDefault("event", ""));
            Application.send(x, 200, "text/plain; charset=utf-8", "saved"); return true;
        }
        if (path.equals("/exam/start") && user.role.equals("student")) {
            if (x.getRequestMethod().equals("POST")) {
                int id = Application.DB.startExam(user, form.getOrDefault("subject", "All Subjects"));
                Application.redirect(x, "/exam/"+id); return true;
            }
            Application.page(x, Views.shell(user, "Start exam", Views.startExam(Application.DB))); return true;
        }
        if (path.matches("/exam/\\d+")) {
            int id = Integer.parseInt(path.substring(path.lastIndexOf('/')+1));
            if (x.getRequestMethod().equals("POST")) {
                if (Application.DB.closeExamIfExpiredOrSubmitted(user, id)) { Application.redirect(x, "/"); return true; }
                if (form.containsKey("pause")) { Application.DB.saveAnswers(user,id,form); Application.DB.changeSessionState(user,id,Application.SessionState.PAUSED); Application.redirect(x,"/"); return true; }
                if (form.containsKey("runCode")) { Application.DB.saveAnswers(user,id,form); Application.DB.runJava(user,id,Long.parseLong(form.get("runCode"))); Application.redirect(x,path+"?run="+form.get("runCode")); return true; }
                if (form.containsKey("submit")) { Application.DB.saveAnswers(user,id,form); Application.DB.finishExam(user,id); Application.redirect(x,"/"); return true; }
                if (Application.query(x,"autosave").equals("1")) {
                    BackgroundJobs.saveAnswers(Application.DB,user,id,form);
                    Application.send(x,200,"text/plain; charset=utf-8","saved"); return true;
                }
                Application.DB.saveAnswers(user,id,form); Application.redirect(x,path); return true;
            }
            if (Application.DB.closeExamIfExpiredOrSubmitted(user,id)) { Application.redirect(x,"/"); return true; }
            Application.page(x,Views.shell(user,"Exam",Views.examPage(Application.DB,user,id,Application.query(x,"run")))); return true;
        }
        if (path.matches("/results/\\d+") && user.role.equals("student")) {
            int id=Integer.parseInt(path.substring(path.lastIndexOf('/')+1));
            Application.page(x,Views.shell(user,"Exam results",Views.results(Application.DB,user,id))); return true;
        }
        if (path.equals("/reviews") && user.role.equals("student")) {
            if (x.getRequestMethod().equals("POST")) { Application.DB.review(user,form); Application.redirect(x,"/reviews"); return true; }
            Application.page(x,Views.shell(user,"Teacher reviews",Views.reviews(Application.DB,user))); return true;
        }
        return false;
    }
}
