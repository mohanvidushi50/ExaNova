package exam;

import core.*;
import core.Application.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;




public final class StoreExams {
    private final Store store;

    public StoreExams(Store store) { this.store = store; }

    private Connection connect() throws SQLException { return store.connect(); }
    private static String h(String value) { return Store.h(value); }
    private static String alert(String value) { return Store.alert(value); }
    private static String required(Map<String,String> values,String key) { return Store.required(values,key); }
    private int count(Statement s,String table) throws SQLException { return store.count(s,table); }
    private int countWhere(Statement s,String table,String where) throws SQLException { return store.countWhere(s,table,where); }
    public String openExamLink(User student)throws SQLException{
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT id,subject,session_state,started_at FROM exams WHERE student_id=? AND status='in_progress' ORDER BY id DESC LIMIT 1")){
            p.setLong(1,student.id);
            try(ResultSet r=p.executeQuery()){
                if(!r.next())return "";
                String state=r.getString(3);
                return "<section class='card resume'><div><p class='eyebrow'>PROCESS · "+h(state.toUpperCase(Locale.ROOT))+"</p><h2>"+h(r.getString(2))+"</h2><p>Started "+h(r.getString(4))+" · Session state is stored in MySQL.</p></div><a class='button' href='/exam/"+r.getInt(1)+"'>Continue exam</a></section>";
            }
        }
    }

    public int startExam(User student,String subject)throws SQLException{
        String chosen=subject==null?"All Subjects":subject.trim();
        if(chosen.isBlank())chosen="All Subjects";
        int id;
        try(Connection c=connect()){
            c.setAutoCommit(false);
            try{
                // Lock only this student's row: separate students can start exams concurrently.
                try(PreparedStatement userLock=c.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")){
                    userLock.setLong(1,student.id);
                    try(ResultSet r=userLock.executeQuery()){
                        if(!r.next())throw new IllegalArgumentException("Student account not found.");
                    }
                }
                try(PreparedStatement expire=c.prepareStatement("UPDATE exams SET status='submitted',session_state='terminated',version=version+1,submitted_at=COALESCE(submitted_at,CURRENT_TIMESTAMP) WHERE student_id=? AND status='in_progress' AND deadline_at<=CURRENT_TIMESTAMP")){
                    expire.setLong(1,student.id);
                    expire.executeUpdate();
                }
                try(PreparedStatement active=c.prepareStatement("SELECT COUNT(*) FROM exams WHERE student_id=? AND status='in_progress'")){
                    active.setLong(1,student.id);
                    try(ResultSet r=active.executeQuery()){
                        r.next();
                        if(r.getInt(1)>0)throw new IllegalArgumentException("Resume or submit your active exam before starting another.");
                    }
                }
                // Attempts are recorded by student and subject. The user-row lock above
                // serializes starts for this student, so duplicate starts cannot race.
                try(PreparedStatement previous=c.prepareStatement("SELECT id FROM exams WHERE student_id=? AND subject=? LIMIT 1")){
                    previous.setLong(1,student.id);
                    previous.setString(2,chosen);
                    try(ResultSet r=previous.executeQuery()){
                        if(r.next())throw new IllegalArgumentException("You have already attempted this exam.");
                    }
                }                String questionSql=chosen.equals("All Subjects")?"SELECT COUNT(*) FROM questions WHERE is_active=TRUE":"SELECT COUNT(*) FROM questions WHERE is_active=TRUE AND subject=?";
                try(PreparedStatement questions=c.prepareStatement(questionSql)){
                    if(!chosen.equals("All Subjects"))questions.setString(1,chosen);
                    try(ResultSet r=questions.executeQuery()){
                        r.next();
                        if(r.getInt(1)==0)throw new IllegalArgumentException("There are no questions for this selection yet.");
                    }
                }
                int minutes=ExamTimer.DEFAULT_MINUTES;
                if(!chosen.equals("All Subjects"))try(PreparedStatement setting=c.prepareStatement("SELECT duration_minutes FROM exam_settings WHERE subject=?")){
                    setting.setString(1,chosen);
                    try(ResultSet r=setting.executeQuery()){
                        if(r.next())minutes=r.getInt(1);
                    }
                }
                try(PreparedStatement insert=c.prepareStatement("INSERT INTO exams(student_id,subject,duration_minutes,deadline_at,session_state) VALUES(?,?,?,TIMESTAMPADD(MINUTE,?,CURRENT_TIMESTAMP),'created')",Statement.RETURN_GENERATED_KEYS)){
                    insert.setLong(1,student.id);
                    insert.setString(2,chosen);
                    insert.setInt(3,minutes);
                    insert.setInt(4,minutes);
                    insert.executeUpdate();
                    try(ResultSet keys=insert.getGeneratedKeys()){
                        if(!keys.next())throw new SQLException("Could not create the exam attempt.");
                        id=keys.getInt(1);
                    }
                }
                c.commit();
            }
            catch(Exception e){
                c.rollback();
                if(e instanceof SQLException se)throw se;
                if(e instanceof IllegalArgumentException ia)throw ia;
                throw new SQLException(e);
            }
            finally{
                c.setAutoCommit(true);
            }
        }
        changeSessionState(student,id,SessionState.RUNNING);
        Store.ANALYTICS_CACHE.remove(student.id);
        return id;
    }

    public Map<String,Integer> teacherExamDurations(User teacher)throws SQLException{
        Map<String,Integer> result=new LinkedHashMap<>();
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT q.subject,COALESCE(s.duration_minutes,?) FROM questions q LEFT JOIN exam_settings s ON s.subject=q.subject WHERE q.teacher_id=? AND q.is_active=TRUE GROUP BY q.subject,s.duration_minutes ORDER BY q.subject")){
            p.setInt(1,ExamTimer.DEFAULT_MINUTES);
            p.setLong(2,teacher.id);
            try(ResultSet r=p.executeQuery()){
                while(r.next())result.put(r.getString(1),r.getInt(2));
            }
        }
        return result;
    }

    public int examDuration(String subject)throws SQLException{
        if(subject==null||subject.equals("All Subjects"))return ExamTimer.DEFAULT_MINUTES;
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT duration_minutes FROM exam_settings WHERE subject=?")){
            p.setString(1,subject);
            try(ResultSet r=p.executeQuery()){
                return r.next()?r.getInt(1):ExamTimer.DEFAULT_MINUTES;
            }
        }
    }

    public void setExamDuration(User teacher,String subject,String requestedMinutes)throws SQLException{
        String chosen=subject==null?"":subject.trim();
        if(chosen.isBlank()||chosen.equals("All Subjects"))throw new IllegalArgumentException("Choose one of your question subjects.");
        int minutes=ExamTimer.validateMinutes(requestedMinutes);
        try(Connection c=connect()){
            c.setAutoCommit(false);
            try{
                try(PreparedStatement owns=c.prepareStatement("SELECT COUNT(*) FROM questions WHERE teacher_id=? AND subject=? AND is_active=TRUE")){
                    owns.setLong(1,teacher.id);
                    owns.setString(2,chosen);
                    try(ResultSet r=owns.executeQuery()){
                        r.next();
                        if(r.getInt(1)==0)throw new IllegalArgumentException("You can set a timer only for a subject with your active questions.");
                    }
                }
                try(PreparedStatement save=c.prepareStatement("INSERT INTO exam_settings(subject,teacher_id,duration_minutes) VALUES(?,?,?) ON DUPLICATE KEY UPDATE teacher_id=VALUES(teacher_id),duration_minutes=VALUES(duration_minutes)")){
                    save.setString(1,chosen);
                    save.setLong(2,teacher.id);
                    save.setInt(3,minutes);
                    save.executeUpdate();
                }
                c.commit();
            }
            catch(Exception e){
                c.rollback();
                if(e instanceof SQLException se)throw se;
                if(e instanceof IllegalArgumentException ia)throw ia;
                throw new SQLException(e);
            }
            finally{
                c.setAutoCommit(true);
            }
        }
        Store.ANALYTICS_CACHE.clear();
    }

    public boolean closeExamIfExpiredOrSubmitted(User student,int exam)throws SQLException{
        try(Connection c=connect();PreparedStatement expire=c.prepareStatement("UPDATE exams SET status='submitted',session_state='terminated',version=version+1,submitted_at=COALESCE(submitted_at,CURRENT_TIMESTAMP) WHERE id=? AND student_id=? AND status='in_progress' AND deadline_at<=CURRENT_TIMESTAMP")){
            expire.setInt(1,exam);
            expire.setLong(2,student.id);
            expire.executeUpdate();
        }
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT status FROM exams WHERE id=? AND student_id=?")){
            p.setInt(1,exam);
            p.setLong(2,student.id);
            try(ResultSet r=p.executeQuery()){
                return r.next()&&r.getString(1).equals("submitted");
            }
        }
    }

    public void expireExamTimers()throws SQLException{
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("UPDATE exams SET status='submitted',session_state='terminated',version=version+1,submitted_at=COALESCE(submitted_at,CURRENT_TIMESTAMP) WHERE status='in_progress' AND deadline_at<=CURRENT_TIMESTAMP")){
            p.executeUpdate();
        }
    }

    public void saveAnswers(User student,int exam,Map<String,String> f)throws SQLException{
        DeadlockRetry.run(() -> saveAnswersOnce(student,exam,f));
    }

    private void saveAnswersOnce(User student,int exam,Map<String,String> f)throws SQLException{
        try(Connection c=connect()){
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try{
                int version;
                try(PreparedStatement own=c.prepareStatement("SELECT session_state,status,version,(deadline_at IS NOT NULL AND deadline_at<=CURRENT_TIMESTAMP) FROM exams WHERE id=? AND student_id=? FOR UPDATE")){
                    own.setInt(1,exam);
                    own.setLong(2,student.id);
                    try(ResultSet r=own.executeQuery()){
                        if(!r.next()||!r.getString(2).equals("in_progress")||!r.getString(1).equals("running"))throw new IllegalArgumentException("This exam process is not running. Resume it from your dashboard first.");
                        if(r.getBoolean(4))throw new IllegalArgumentException("The exam timer has ended. Your last autosaved answers are kept.");
                        version=r.getInt(3);
                    }
                }
                String transactionId=UUID.randomUUID().toString();
                try(PreparedStatement log=c.prepareStatement("INSERT INTO exam_wal(transaction_id,exam_id,question_id,operation,answer_snapshot) SELECT ?,?,q.id,'ANSWER_SAVED',? FROM questions q JOIN exams e ON e.id=? AND e.student_id=? WHERE q.id=? AND (e.subject='All Subjects' OR q.subject=e.subject)"); PreparedStatement response=c.prepareStatement("INSERT INTO responses(exam_id,question_id,answer) SELECT ?,q.id,? FROM questions q JOIN exams e ON e.id=? AND e.student_id=? WHERE q.id=? AND (e.subject='All Subjects' OR q.subject=e.subject) ON DUPLICATE KEY UPDATE answer=VALUES(answer)")){
                    for(var entry:f.entrySet())if(entry.getKey().startsWith("q")){
                        long questionId=Long.parseLong(entry.getKey().substring(1));
                        String answer=entry.getValue();
                        log.setString(1,transactionId);
                        log.setInt(2,exam);
                        log.setString(3,answer);
                        log.setInt(4,exam);
                        log.setLong(5,student.id);
                        log.setLong(6,questionId);
                        log.executeUpdate();
                        response.setInt(1,exam);
                        response.setString(2,answer);
                        response.setInt(3,exam);
                        response.setLong(4,student.id);
                        response.setLong(5,questionId);
                        response.executeUpdate();
                    }
                }
                try(PreparedStatement update=c.prepareStatement("UPDATE exams SET version=version+1 WHERE id=? AND version=? AND session_state='running'")){
                    update.setInt(1,exam);
                    update.setInt(2,version);
                    if(update.executeUpdate()!=1)throw new IllegalArgumentException("Another request changed this exam. Reload and try again.");
                }
                c.commit();
                Store.ANALYTICS_CACHE.remove(student.id);
            }
            catch(Exception e){
                c.rollback();
                if(e instanceof SQLException se)throw se;
                if(e instanceof IllegalArgumentException ia)throw ia;
                throw new SQLException(e);
            }
            finally{
                c.setAutoCommit(true);
            }
        }
    }

    public void changeSessionState(User student,int exam,SessionState next)throws SQLException{
        DeadlockRetry.run(() -> changeSessionStateOnce(student,exam,next));
    }

    private void changeSessionStateOnce(User student,int exam,SessionState next)throws SQLException{
        try(Connection c=connect()){
            c.setAutoCommit(false);
            try(PreparedStatement p=c.prepareStatement("SELECT session_state,version,status FROM exams WHERE id=? AND student_id=? FOR UPDATE")){
                p.setInt(1,exam);
                p.setLong(2,student.id);
                try(ResultSet r=p.executeQuery()){
                    if(!r.next()||!r.getString(3).equals("in_progress"))throw new IllegalArgumentException("Exam session was not found.");
                    SessionState current=SessionState.valueOf(r.getString(1).toUpperCase(Locale.ROOT));
                    int version=r.getInt(2);
                    if(!current.canMoveTo(next))throw new IllegalArgumentException("Invalid exam process transition: "+current+" → "+next+".");
                    try(PreparedStatement update=c.prepareStatement("UPDATE exams SET session_state=?,version=version+1 WHERE id=? AND version=?")){
                        update.setString(1,next.name().toLowerCase(Locale.ROOT));
                        update.setInt(2,exam);
                        update.setInt(3,version);
                        if(update.executeUpdate()!=1)throw new IllegalArgumentException("Exam process changed concurrently. Reload the page.");
                    }
                }
                c.commit();
            }
            catch(Exception e){
                c.rollback();
                if(e instanceof SQLException se)throw se;
                if(e instanceof IllegalArgumentException ia)throw ia;
                throw new SQLException(e);
            }
            finally{
                c.setAutoCommit(true);
            }
        }
    }

    public void finishExam(User student,int exam)throws SQLException{
        DeadlockRetry.run(() -> finishExamOnce(student,exam));
    }

    private void finishExamOnce(User student,int exam)throws SQLException{
        try(Connection c=connect()){
            c.setAutoCommit(false);
            try(PreparedStatement lock=c.prepareStatement("SELECT session_state,status FROM exams WHERE id=? AND student_id=? FOR UPDATE")){
                lock.setInt(1,exam);
                lock.setLong(2,student.id);
                try(ResultSet r=lock.executeQuery()){
                    if(!r.next()||!r.getString(1).equals("running")||!r.getString(2).equals("in_progress"))throw new IllegalArgumentException("Exam is not running or was already submitted.");
                }
                // Keep the final status and timestamp in the same database transaction.
                try(PreparedStatement finish=c.prepareStatement("UPDATE exams SET status='submitted',session_state='terminated',version=version+1,submitted_at=CURRENT_TIMESTAMP WHERE id=? AND student_id=? AND status='in_progress' AND session_state='running'")){
                    finish.setInt(1,exam);
                    finish.setLong(2,student.id);
                    if(finish.executeUpdate()!=1)throw new SQLException("Exam submission did not update the active exam.");
                }
                try(PreparedStatement verify=c.prepareStatement("SELECT status,session_state FROM exams WHERE id=? AND student_id=?")){
                    verify.setInt(1,exam);
                    verify.setLong(2,student.id);
                    try(ResultSet r=verify.executeQuery()){
                        if(!r.next()||!r.getString(1).equals("submitted")||!r.getString(2).equals("terminated"))throw new SQLException("Exam submission did not complete. Please retry.");
                    }
                }
                Store.ANALYTICS_CACHE.remove(student.id);
                c.commit();
            }
            catch(Exception e){
                c.rollback();
                if(e instanceof SQLException se)throw se;
                if(e instanceof IllegalArgumentException ia)throw ia;
                throw new SQLException(e);
            }
            finally{
                c.setAutoCommit(true);
            }
        }
    }

    public String renderExam(User student,int id,String run)throws SQLException{
        StringBuilder b=new StringBuilder();
        String subject,state;
        long deadline;
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT subject,status,session_state,UNIX_TIMESTAMP(deadline_at) FROM exams WHERE id=? AND student_id=?")){
            p.setInt(1,id);
            p.setLong(2,student.id);
            try(ResultSet r=p.executeQuery()){
                if(!r.next())return alert("This exam is not available for your account.");
                subject=r.getString(1);
                state=r.getString(3);
                deadline=r.getLong(4);
                if(!r.getString(2).equals("in_progress"))return results(student,id);
            }
        }
        if(state.equals("paused")){
            changeSessionState(student,id,SessionState.RUNNING);
            state="running";
        }
        b.append("<section class='exam-head'><p class='eyebrow'>EXAM PROCESS · ").append(h(state.toUpperCase(Locale.ROOT))).append("</p><h1>").append(h(subject)).append("</h1><p id='exam-timer' class='exam-timer' data-deadline-seconds='").append(deadline).append("'>Time remaining: <strong>Loading…</strong></p><p>Your answers autosave while you work. You can also save or pause manually.</p><p id='save-status' class='muted' aria-live='polite'>Autosave ready.</p></section><form id='exam-form' method='post' action='/exam/").append(id).append("' data-exam-id='").append(id).append("' data-deadline-seconds='").append(deadline).append("'><div class='question-list'>");
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT q.id,q.type,q.question_text,q.option_a,q.option_b,q.option_c,q.option_d,r.answer FROM questions q JOIN exams e ON e.id=? LEFT JOIN responses r ON r.exam_id=e.id AND r.question_id=q.id WHERE e.student_id=? AND q.is_active=TRUE AND (e.subject='All Subjects' OR q.subject=e.subject) ORDER BY q.id")){
            p.setInt(1,id);
            p.setLong(2,student.id);
            try(ResultSet r=p.executeQuery()){
                int n=0;
                while(r.next()){
                    n++;
                    long qid=r.getLong(1);
                    String type=r.getString(2);
                    b.append("<article class='card question'><div class='qnum'>QUESTION ").append(n).append(" · ").append(h(type.toUpperCase(Locale.ROOT))).append("</div><h2>").append(h(r.getString(3))).append("</h2>");
                    if(type.equals("mcq")){
                        String[]labels={
                            "A","B","C","D"
                        }
                        ;
                        for(int i=0;i<4;i++){
                            String opt=r.getString(4+i);
                            if(opt!=null&&!opt.isBlank())b.append("<label class='choice'><input type='radio' name='q").append(qid).append("' value='").append(labels[i]).append("'").append(labels[i].equalsIgnoreCase(r.getString(8))?" checked":"").append("><span class='choice-key'>").append(labels[i]).append("</span>").append(h(opt)).append("</label>");
                        }
                    }
                    else if(type.equals("coding")){
                        b.append("<p class='muted'>Java only · submit a public class named <code>Main</code>.</p><textarea class='code-editor' name='q").append(qid).append("' rows='14' spellcheck='false' placeholder='import java.util.*;\n\npublic class Main {\n    public static void main(String[] args) {\n        // Read input and print your answer\n    }\n}'>").append(h(r.getString(8))).append("</textarea><button class='button secondary run-button' name='runCode' value='").append(qid).append("'>Run against test cases</button>").append(store.lastCodingRun(student,id,qid));
                    }
                    else b.append("<textarea name='q").append(qid).append("' rows='5' placeholder='Write your answer here'>").append(h(r.getString(8))).append("</textarea>");
                    b.append("</article>");
                }
                if(n==0)b.append(alert("No questions are available for this subject yet."));
            }
        }
        return b.append("</div><div class='form-actions'><button class='button secondary' name='save' value='1'>Save answers</button><button class='button secondary' name='pause' value='1'>Save &amp; pause</button><button class='button' name='submit' value='1'>Submit exam</button></div></form>").toString();
    }

    public String results(User student,int exam)throws SQLException{
        int[]score={
            0,0
        }
        ;
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT q.type,q.correct_option,r.answer,e.status,e.subject FROM exams e JOIN questions q ON (e.subject='All Subjects' OR q.subject=e.subject) LEFT JOIN responses r ON r.exam_id=e.id AND r.question_id=q.id WHERE e.id=? AND e.student_id=?")){
            p.setInt(1,exam);
            p.setLong(2,student.id);
            try(ResultSet r=p.executeQuery()){
                if(!r.next())return alert("Result not available.");
                String subject=r.getString(5),status=r.getString(4);
                do{
                    if("mcq".equals(r.getString(1))){
                        score[1]++;
                        if(r.getString(2)!=null&&r.getString(2).equalsIgnoreCase(r.getString(3)))score[0]++;
                    }
                }
                while(r.next());
                return "<section class='card narrow'><p class='eyebrow'>"+h(status.equals("submitted")?"SUBMITTED":"IN PROGRESS")+" · "+h(subject)+"</p><h1>Your result</h1><div class='score'>"+score[0]+"<small> / "+score[1]+" MCQ points</small></div><p>Written answers are reviewed by your teacher. Your work is saved in your exam history.</p><a class='button' href='/'>Back to dashboard</a></section>";
            }
        }
    }
}
