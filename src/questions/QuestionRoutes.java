package questions;

import core.*;
import com.sun.net.httpserver.*;
import java.util.*;



/** Teacher question creation and archival routes. */
public final class QuestionRoutes {
    private QuestionRoutes() { }

    public static boolean handle(HttpExchange x, Application.User user, String path, Map<String, String> form) throws Exception {
        if (path.equals("/question/delete") && user.role.equals("teacher") && x.getRequestMethod().equals("POST")) {
            Application.DB.archiveQuestion(user, Long.parseLong(form.getOrDefault("questionId", "0")));
            Application.redirect(x, "/"); return true;
        }
        if (path.equals("/question/new") && user.role.equals("teacher")) {
            if (x.getRequestMethod().equals("POST")) {
                Application.DB.addQuestion(user.username, form); Application.redirect(x, "/"); return true;
            }
            Application.page(x, Views.shell(user, "Add a question", Views.questionForm(Application.query(x,"type")))); return true;
        }
        return false;
    }
}
