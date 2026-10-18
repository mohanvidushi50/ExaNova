package core;

import core.Application.*;
import admin.*;
import analytics.*;
import auth.*;
import coding.*;
import exam.*;
import questions.*;
import recovery.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;


public final class Store {
    private final StoreAccounts accounts = new StoreAccounts(this);
    private final StoreExams exams = new StoreExams(this);
    private final StoreQuestions questions = new StoreQuestions(this);
    private final StoreAnalytics analytics = new StoreAnalytics(this);
    private final StoreAdmin adminStore = new StoreAdmin(this);
    private final ExamRecoveryStore recovery = new ExamRecoveryStore(this);
    private final JavaCodeRunner javaRunner = new JavaCodeRunner(this);
    public static final SecureRandom RANDOM = new SecureRandom();
    public record AnalyticsCache(long createdAt, String html) {
    }
    public static final Map<Long, AnalyticsCache> ANALYTICS_CACHE = new ConcurrentHashMap<>();
    public static final ReentrantLock ROLE_CHANGE_LOCK = new ReentrantLock();
    public static final Semaphore JAVA_RUN_SLOTS = new Semaphore(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
    public static String alert(String message) {
        return "<div class='alert'>" + h(message) + "</div>";
    }
    public static String h(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private final String url=System.getenv().getOrDefault("MYSQL_URL","jdbc:mysql://localhost:3306/evalio?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    private final String user=System.getenv().getOrDefault("MYSQL_USER","root");
    private final String password=System.getenv().getOrDefault("MYSQL_PASSWORD","");
    public Connection connect()throws SQLException{
        return DriverManager.getConnection(url,user,password);
    }
    private String databaseName(){
        String prefix="jdbc:mysql://";
        int start=url.indexOf('/',prefix.length());
    if(start<0)throw new IllegalArgumentException("MYSQL_URL must include a database name, such as /evalio.");
        int end=url.indexOf('?',start);
    if(end<0)end=url.length();
        String name=url.substring(start+1,end);
    if(!name.matches("[A-Za-z0-9_]+"))throw new IllegalArgumentException("MYSQL_URL database name must use letters, numbers, or underscores.");
        return name;
    }
    private String serverUrl(){
        String prefix="jdbc:mysql://";
        int start=url.indexOf('/',prefix.length());
    if(start<0)return url;
        int query=url.indexOf('?',start);
        return url.substring(0,start+1)+(query<0?"":url.substring(query));
    }
    private void createDatabase()throws SQLException{
        String name=databaseName();
    try(Connection c=DriverManager.getConnection(serverUrl(),user,password);Statement s=c.createStatement()){
            s.executeUpdate("CREATE DATABASE IF NOT EXISTS `"+name+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }
    public void recoverInterruptedSessions() throws SQLException { recovery.recoverInterruptedSessions(); }
    public void checkpointRunningExams() throws SQLException { recovery.checkpointRunningExams(); }
    public String renderMonitor() throws SQLException { return recovery.renderMonitor(); }
    public void runJava(User student, int exam, long question) throws Exception { javaRunner.runJava(student, exam, question); }
    public String lastCodingRun(User student, int exam, long question) throws SQLException { return javaRunner.lastCodingRun(student, exam, question); }
    public User login(String name,String pass)throws Exception { return accounts.login(name,pass); }
    public void register(String name,String pass,String role)throws Exception { accounts.register(name,pass,role); }
    public String hash(String pass) { return accounts.hash(pass); }
    public boolean verify(String pass,String stored) { return accounts.verify(pass,stored); }
    public List<String> subjects()throws SQLException { return accounts.subjects(); }
    public String openExamLink(User student)throws SQLException { return exams.openExamLink(student); }
    public void addQuestion(String teacher,Map<String,String> f)throws SQLException { questions.addQuestion(teacher,f); }
    public int startExam(User student,String subject)throws SQLException { return exams.startExam(student,subject); }
    public Map<String,Integer> teacherExamDurations(User teacher)throws SQLException { return exams.teacherExamDurations(teacher); }
    public int examDuration(String subject)throws SQLException { return exams.examDuration(subject); }
    public void setExamDuration(User teacher,String subject,String requestedMinutes)throws SQLException { exams.setExamDuration(teacher,subject,requestedMinutes); }
    public boolean closeExamIfExpiredOrSubmitted(User student,int exam)throws SQLException { return exams.closeExamIfExpiredOrSubmitted(student,exam); }
    public void expireExamTimers()throws SQLException { exams.expireExamTimers(); }
    public void saveAnswers(User student,int exam,Map<String,String> f)throws SQLException { exams.saveAnswers(student,exam,f); }
    public void changeSessionState(User student,int exam,SessionState next)throws SQLException { exams.changeSessionState(student,exam,next); }
    public void finishExam(User student,int exam)throws SQLException { exams.finishExam(student,exam); }
    public String renderExam(User student,int id,String run)throws SQLException { return exams.renderExam(student,id,run); }
    public String results(User student,int exam)throws SQLException { return exams.results(student,exam); }
    public String studentAnalytics(User student)throws SQLException { return analytics.studentAnalytics(student); }
    public void invalidateAnalytics() { analytics.invalidateAnalytics(); }
    public String studentResults(User student)throws SQLException { return analytics.studentResults(student); }
    public String teacherQuestions(User teacher)throws SQLException { return questions.teacherQuestions(teacher); }
    public void archiveQuestion(User teacher,long question)throws SQLException { questions.archiveQuestion(teacher,question); }
    public String renderAnswers(User teacher)throws SQLException { return questions.renderAnswers(teacher); }
    public void grade(int response,int marks,String teacher)throws SQLException { questions.grade(response,marks,teacher); }
    public String renderReviews(User student)throws SQLException { return questions.renderReviews(student); }
    public void review(User student,Map<String,String> f)throws SQLException { questions.review(student,f); }
    public void recordExamEvent(User student,int exam,String event)throws SQLException { questions.recordExamEvent(student,exam,event); }
    public void changeUserRole(User admin,long userId,String role)throws SQLException { adminStore.changeUserRole(admin,userId,role); }
    public String renderAdmin(User admin)throws SQLException { return adminStore.renderAdmin(admin); }
    public String renderExamEvents()throws SQLException { return adminStore.renderExamEvents(); }
    public void initialize()throws SQLException{
    try(Connection ignored=connect()){
        }
    catch(SQLException e){
    if(e.getErrorCode()!=1049)throw e;
    createDatabase();
        }
    try(Connection c=connect();Statement s=c.createStatement()){
            s.executeUpdate("CREATE TABLE IF NOT EXISTS users(id BIGINT PRIMARY KEY AUTO_INCREMENT,username VARCHAR(60) NOT NULL UNIQUE,password_hash VARCHAR(128) NOT NULL,role ENUM('student','teacher','admin') NOT NULL,created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS questions(id BIGINT PRIMARY KEY AUTO_INCREMENT,teacher_id BIGINT NOT NULL,subject VARCHAR(100) NOT NULL,type ENUM('mcq','descriptive','coding') NOT NULL,question_text TEXT NOT NULL,option_a TEXT,option_b TEXT,option_c TEXT,option_d TEXT,correct_option CHAR(1),is_active BOOLEAN NOT NULL DEFAULT TRUE,created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,FOREIGN KEY(teacher_id) REFERENCES users(id) ON DELETE CASCADE,INDEX(subject),INDEX(teacher_id)) ENGINE=InnoDB");
            s.executeUpdate("ALTER TABLE questions MODIFY COLUMN type ENUM('mcq','descriptive','coding') NOT NULL");
    ensureColumn(c,s,"questions","is_active","BOOLEAN NOT NULL DEFAULT TRUE");
    ensureIndex(c,s,"questions","idx_questions_active_subject","is_active,subject,id");
    ensureIndex(c,s,"questions","idx_questions_teacher_active","teacher_id,is_active,id");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS coding_test_cases(id BIGINT PRIMARY KEY AUTO_INCREMENT,question_id BIGINT NOT NULL,case_order INT NOT NULL,input_text LONGTEXT NOT NULL,expected_output LONGTEXT NOT NULL,is_hidden BOOLEAN NOT NULL DEFAULT FALSE,UNIQUE KEY(question_id,case_order),FOREIGN KEY(question_id) REFERENCES questions(id) ON DELETE CASCADE) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS exams(id BIGINT PRIMARY KEY AUTO_INCREMENT,student_id BIGINT NOT NULL,subject VARCHAR(100) NOT NULL,duration_minutes INT NOT NULL DEFAULT 60,deadline_at TIMESTAMP NULL,status ENUM('in_progress','submitted') NOT NULL DEFAULT 'in_progress',session_state ENUM('created','running','paused','terminated') NOT NULL DEFAULT 'created',version INT NOT NULL DEFAULT 0,last_checkpoint_at TIMESTAMP NULL,started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,submitted_at TIMESTAMP NULL,FOREIGN KEY(student_id) REFERENCES users(id) ON DELETE CASCADE,INDEX(student_id,status),INDEX(session_state,status),INDEX(status,deadline_at)) ENGINE=InnoDB");
    ensureColumn(c,s,"exams","duration_minutes","INT NOT NULL DEFAULT 60");
    ensureColumn(c,s,"exams","deadline_at","TIMESTAMP NULL");
    ensureIndex(c,s,"exams","idx_exams_status_deadline","status,deadline_at");
    ensureIndex(c,s,"exams","idx_exams_student_subject","student_id,subject,id");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS exam_settings(subject VARCHAR(100) PRIMARY KEY,teacher_id BIGINT NOT NULL,duration_minutes INT NOT NULL DEFAULT 60,updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,FOREIGN KEY(teacher_id) REFERENCES users(id) ON DELETE CASCADE,CHECK(duration_minutes BETWEEN 5 AND 240)) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS coding_runs(id BIGINT PRIMARY KEY AUTO_INCREMENT,exam_id BIGINT NOT NULL,question_id BIGINT NOT NULL,student_id BIGINT NOT NULL,status VARCHAR(24) NOT NULL,passed INT NOT NULL DEFAULT 0,total INT NOT NULL DEFAULT 0,details LONGTEXT,created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX(exam_id,question_id,student_id,id),INDEX(student_id,status,created_at),FOREIGN KEY(exam_id) REFERENCES exams(id) ON DELETE CASCADE,FOREIGN KEY(question_id) REFERENCES questions(id) ON DELETE CASCADE,FOREIGN KEY(student_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB");
    ensureIndex(c,s,"coding_runs","idx_coding_runs_student_exam_question","student_id,exam_id,question_id,id");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS responses(id BIGINT PRIMARY KEY AUTO_INCREMENT,exam_id BIGINT NOT NULL,question_id BIGINT NOT NULL,answer LONGTEXT,marks INT NULL,updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,UNIQUE KEY(exam_id,question_id),FOREIGN KEY(exam_id) REFERENCES exams(id) ON DELETE CASCADE,FOREIGN KEY(question_id) REFERENCES questions(id) ON DELETE CASCADE) ENGINE=InnoDB");
            s.executeUpdate("ALTER TABLE responses MODIFY COLUMN answer LONGTEXT");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS teacher_reviews(id BIGINT PRIMARY KEY AUTO_INCREMENT,student_id BIGINT NOT NULL,teacher_id BIGINT NOT NULL,rating TINYINT NOT NULL,comment VARCHAR(1000),created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE KEY(student_id,teacher_id),FOREIGN KEY(student_id) REFERENCES users(id) ON DELETE CASCADE,FOREIGN KEY(teacher_id) REFERENCES users(id) ON DELETE CASCADE,CHECK(rating BETWEEN 1 AND 5)) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS exam_wal(id BIGINT PRIMARY KEY AUTO_INCREMENT,transaction_id CHAR(36) NOT NULL,exam_id BIGINT NOT NULL,question_id BIGINT NOT NULL,operation ENUM('ANSWER_SAVED') NOT NULL,answer_snapshot TEXT,created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX(exam_id,id),FOREIGN KEY(exam_id) REFERENCES exams(id) ON DELETE CASCADE,FOREIGN KEY(question_id) REFERENCES questions(id) ON DELETE CASCADE) ENGINE=InnoDB");
            s.executeUpdate("ALTER TABLE exam_wal MODIFY COLUMN answer_snapshot LONGTEXT");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS exam_checkpoints(id BIGINT PRIMARY KEY AUTO_INCREMENT,exam_id BIGINT NOT NULL,version INT NOT NULL,wal_id BIGINT NOT NULL DEFAULT 0,snapshot_json LONGTEXT NOT NULL,created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX(exam_id,id),FOREIGN KEY(exam_id) REFERENCES exams(id) ON DELETE CASCADE) ENGINE=InnoDB");
    ensureColumn(c,s,"exam_checkpoints","wal_id","BIGINT NOT NULL DEFAULT 0");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS checkpoint_answers(checkpoint_id BIGINT NOT NULL,question_id BIGINT NOT NULL,answer LONGTEXT,PRIMARY KEY(checkpoint_id,question_id),FOREIGN KEY(checkpoint_id) REFERENCES exam_checkpoints(id) ON DELETE CASCADE,FOREIGN KEY(question_id) REFERENCES questions(id) ON DELETE CASCADE) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS response_audit(id BIGINT PRIMARY KEY AUTO_INCREMENT,exam_id BIGINT NOT NULL,question_id BIGINT NOT NULL,action ENUM('INSERT','UPDATE') NOT NULL,occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX(exam_id,occurred_at),FOREIGN KEY(exam_id) REFERENCES exams(id) ON DELETE CASCADE,FOREIGN KEY(question_id) REFERENCES questions(id) ON DELETE CASCADE) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS exam_events(id BIGINT PRIMARY KEY AUTO_INCREMENT,exam_id BIGINT NOT NULL,student_id BIGINT NOT NULL,event_type ENUM('TAB_HIDDEN','COPY_ATTEMPT') NOT NULL,occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX(exam_id,event_type,occurred_at),INDEX(student_id,occurred_at),FOREIGN KEY(exam_id) REFERENCES exams(id) ON DELETE CASCADE,FOREIGN KEY(student_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB");
    ensureTrigger(c,s,"trg_response_audit_insert","CREATE TRIGGER trg_response_audit_insert AFTER INSERT ON responses FOR EACH ROW INSERT INTO response_audit(exam_id,question_id,action) VALUES(NEW.exam_id,NEW.question_id,'INSERT')");
    ensureTrigger(c,s,"trg_response_audit_update","CREATE TRIGGER trg_response_audit_update AFTER UPDATE ON responses FOR EACH ROW INSERT INTO response_audit(exam_id,question_id,action) VALUES(NEW.exam_id,NEW.question_id,'UPDATE')");
        }
        accounts.createBootstrapAdmin();
    }
    private void ensureColumn(Connection c,Statement s,String table,String column,String definition)throws SQLException{
    try(PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?")){
            p.setString(1,table);
            p.setString(2,column);
    try(ResultSet r=p.executeQuery()){
                r.next();
    if(r.getInt(1)==0)s.execute("ALTER TABLE `"+table+"` ADD COLUMN `"+column+"` "+definition);
            }
        }
    }
    private void ensureIndex(Connection c,Statement s,String table,String name,String columns)throws SQLException{
    try(PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=?")){
            p.setString(1,table);
            p.setString(2,name);
    try(ResultSet r=p.executeQuery()){
                r.next();
    if(r.getInt(1)==0)s.execute("CREATE INDEX `"+name+"` ON `"+table+"` ("+columns+")");
            }
        }
    }
    private void ensureTrigger(Connection c,Statement s,String name,String ddl)throws SQLException{
    try(PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name=?")){
            p.setString(1,name);
    try(ResultSet r=p.executeQuery()){
                r.next();
    if(r.getInt(1)==0)s.execute(ddl);
            }
        }
    }












































    public int count(Statement s,String table)throws SQLException{
    try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table)){
            r.next();
            return r.getInt(1);
        }
    }
    public int countWhere(Statement s,String table,String where)throws SQLException{
    try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table+" WHERE "+where)){
            r.next();
            return r.getInt(1);
        }
    }






    public static String required(Map<String,String>m,String key){
        String v=m.get(key);
    if(v==null||v.isBlank())throw new IllegalArgumentException("Please fill in the "+key+" field.");
        return v.trim();
    }
}
