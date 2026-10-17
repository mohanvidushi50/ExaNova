package questions;

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




public final class StoreQuestions {
    private final Store store;

    public StoreQuestions(Store store) { this.store = store; }

    private Connection connect() throws SQLException { return store.connect(); }
    private static String h(String value) { return Store.h(value); }
    private static String alert(String value) { return Store.alert(value); }
    private static String required(Map<String,String> values,String key) { return Store.required(values,key); }
    private int count(Statement s,String table) throws SQLException { return store.count(s,table); }
    private int countWhere(Statement s,String table,String where) throws SQLException { return store.countWhere(s,table,where); }
    public void addQuestion(String teacher,Map<String,String> f)throws SQLException{
        String type=f.getOrDefault("type","mcq");
        if(!Set.of("mcq","descriptive","coding").contains(type))throw new IllegalArgumentException("Choose a valid question type.");
        if(type.equals("mcq")){
            required(f,"a");
            required(f,"b");
            if(!Set.of("A","B","C","D").contains(f.getOrDefault("answer","A")))throw new IllegalArgumentException("Choose a valid correct option.");
        }
        List<String[]> cases=new ArrayList<>();
        if(type.equals("coding")){
            for(int i=1;i<=5;i++){
                String in=f.getOrDefault("input"+i,"");
                String out=f.getOrDefault("expected"+i,"");
                if(i==1&&(in.isBlank()||out.isBlank()))throw new IllegalArgumentException("Add at least one Java test case with input and expected output.");
                if(!in.isBlank()||!out.isBlank()){
                    if(in.isBlank()||out.isBlank())throw new IllegalArgumentException("Each test case needs both input and expected output.");
                    cases.add(new String[]{
                        in,out,f.containsKey("hidden"+i)?"1":"0"
                    }
                    );
                }
            }
        }
        try(Connection c=connect()){
            c.setAutoCommit(false);
            try(PreparedStatement p=c.prepareStatement("INSERT INTO questions(teacher_id,subject,type,question_text,option_a,option_b,option_c,option_d,correct_option) SELECT id,?,?,?,?,?,?,?,? FROM users WHERE username=? AND role='teacher'",Statement.RETURN_GENERATED_KEYS)){
                p.setString(1,required(f,"subject"));
                p.setString(2,type);
                p.setString(3,required(f,"question"));
                p.setString(4,type.equals("mcq")?f.get("a"):null);
                p.setString(5,type.equals("mcq")?f.get("b"):null);
                p.setString(6,type.equals("mcq")?f.get("c"):null);
                p.setString(7,type.equals("mcq")?f.get("d"):null);
                p.setString(8,type.equals("mcq")?f.getOrDefault("answer","A"):null);
                p.setString(9,teacher);
                if(p.executeUpdate()!=1)throw new IllegalArgumentException("Teacher account not found.");
                try(ResultSet keys=p.getGeneratedKeys()){
                    keys.next();
                    long qid=keys.getLong(1);
                    try(PreparedStatement tc=c.prepareStatement("INSERT INTO coding_test_cases(question_id,case_order,input_text,expected_output,is_hidden) VALUES(?,?,?,?,?)")){
                        for(int i=0;i<cases.size();i++){
                            tc.setLong(1,qid);
                            tc.setInt(2,i+1);
                            tc.setString(3,cases.get(i)[0]);
                            tc.setString(4,cases.get(i)[1]);
                            tc.setBoolean(5,cases.get(i)[2].equals("1"));
                            tc.addBatch();
                        }
                        if(!cases.isEmpty())tc.executeBatch();
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

    public String teacherQuestions(User teacher)throws SQLException{
        StringBuilder b=new StringBuilder("<div class='table-wrap'><table><thead><tr><th>Question</th><th>Subject</th><th>Type</th><th>Action</th></tr></thead><tbody>");
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT id,question_text,subject,type FROM questions WHERE teacher_id=? AND is_active=TRUE ORDER BY id DESC LIMIT 50")){
            p.setLong(1,teacher.id);
            try(ResultSet r=p.executeQuery()){
                int n=0;
                while(r.next()){
                    n++;
                    b.append("<tr><td>").append(h(r.getString(2))).append("</td><td>").append(h(r.getString(3))).append("</td><td><span class='pill'>").append(h(r.getString(4))).append("</span></td><td><form method='post' action='/question/delete'><input type='hidden' name='questionId' value='").append(r.getLong(1)).append("'><button class='button danger small' type='submit'>Delete</button></form></td></tr>");
                }
                if(n==0)b.append("<tr><td colspan='4' class='empty'>Your active question bank is empty.</td></tr>");
            }
        }
        return b.append("</tbody></table></div>").toString();
    }

    public void archiveQuestion(User teacher,long question)throws SQLException{
        if(question<1)throw new IllegalArgumentException("Choose a question to delete.");
        try(Connection c=connect()){
            c.setAutoCommit(false);
            try{
                try(PreparedStatement lock=c.prepareStatement("SELECT id FROM questions WHERE id=? AND teacher_id=? AND is_active=TRUE FOR UPDATE")){
                    lock.setLong(1,question);
                    lock.setLong(2,teacher.id);
                    try(ResultSet r=lock.executeQuery()){
                        if(!r.next())throw new IllegalArgumentException("Question not found or it is not yours.");
                    }
                }
                try(PreparedStatement p=c.prepareStatement("UPDATE questions SET is_active=FALSE WHERE id=? AND teacher_id=?")){
                    p.setLong(1,question);
                    p.setLong(2,teacher.id);
                    if(p.executeUpdate()!=1)throw new SQLException("Question archive failed.");
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

    public String renderAnswers(User teacher)throws SQLException{
        StringBuilder b=new StringBuilder("<section class='welcome'><p class='eyebrow'>TEACHER WORKSPACE</p><h1>Written responses</h1><p>Review answers to your descriptive questions and award marks.</p></section><div class='stack'>");
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT r.id,u.username,q.question_text,r.answer,r.marks FROM responses r JOIN questions q ON q.id=r.question_id JOIN exams e ON e.id=r.exam_id JOIN users u ON u.id=e.student_id WHERE q.teacher_id=? AND q.type='descriptive' AND e.status='submitted' ORDER BY r.updated_at DESC")){
            p.setLong(1,teacher.id);
            try(ResultSet r=p.executeQuery()){
                int n=0;
                while(r.next()){
                    n++;
                    b.append("<article class='card'><p class='eyebrow'>").append(h(r.getString(2))).append(" · ").append(r.getString(5)==null?"AWAITING MARKS":"MARKED · "+r.getInt(5)+" POINTS").append("</p><h2>").append(h(r.getString(3))).append("</h2><p class='answer'>").append(h(r.getString(4))).append("</p><form class='inline-form' method='post'><input type='hidden' name='responseId' value='").append(r.getInt(1)).append("'><label>Marks<input type='number' name='marks' min='0' max='100' required value='").append(r.getString(5)==null?"":r.getInt(5)).append("'></label><button class='button small'>Save mark</button></form></article>");
                }
                if(n==0)b.append("<div class='card empty'>No submitted written answers to review yet.</div>");
            }
        }
        return b.append("</div>").toString();
    }

    public void grade(int response,int marks,String teacher)throws SQLException{
        if(marks<0||marks>100)throw new IllegalArgumentException("Marks must be between 0 and 100.");
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("UPDATE responses r JOIN questions q ON q.id=r.question_id SET r.marks=? WHERE r.id=? AND q.teacher_id=(SELECT id FROM users WHERE username=?)")){
            p.setInt(1,marks);
            p.setInt(2,response);
            p.setString(3,teacher);
            if(p.executeUpdate()==0)throw new IllegalArgumentException("You can only mark answers to your own questions.");
        }
        Store.ANALYTICS_CACHE.clear();
    }

    public String renderReviews(User student)throws SQLException{
        StringBuilder b=new StringBuilder("<section class='welcome'><p class='eyebrow'>STUDENT WORKSPACE</p><h1>Teacher reviews</h1><p>Rate teachers whose questions you have answered. You can update a review later.</p></section><div class='stack'>");
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT DISTINCT t.id,t.username,rv.rating,rv.comment FROM exams e JOIN responses r ON r.exam_id=e.id JOIN questions q ON q.id=r.question_id JOIN users t ON t.id=q.teacher_id LEFT JOIN teacher_reviews rv ON rv.teacher_id=t.id AND rv.student_id=? WHERE e.student_id=? AND e.status='submitted' ORDER BY t.username")){
            p.setLong(1,student.id);
            p.setLong(2,student.id);
            try(ResultSet r=p.executeQuery()){
                int n=0;
                while(r.next()){
                    n++;
                    b.append("<article class='card'><h2>").append(h(r.getString(2))).append("</h2><form method='post'><input type='hidden' name='teacherId' value='").append(r.getLong(1)).append("'><label>Rating<select name='rating'>");
                    for(int k=5;k>=1;k--)b.append("<option value='").append(k).append("'").append(r.getInt(3)==k?" selected":"").append(">\u2605 ").append(k).append(" / 5</option>");
                    b.append("</select></label><label>Comment <small>optional</small><textarea name='comment' maxlength='1000' rows='3'>").append(h(r.getString(4))).append("</textarea></label><button class='button small'>Save review</button></form></article>");
                }
                if(n==0)b.append("<div class='card empty'>Complete an exam with a teacher’s questions to unlock verified reviews.</div>");
            }
        }
        return b.append("</div>").toString();
    }

    public void review(User student,Map<String,String> f)throws SQLException{
        int rating=Integer.parseInt(f.getOrDefault("rating","0"));
        if(rating<1||rating>5)throw new IllegalArgumentException("Choose a rating from 1 to 5.");
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("INSERT INTO teacher_reviews(student_id,teacher_id,rating,comment) SELECT ?,?, ?,? WHERE EXISTS(SELECT 1 FROM exams e JOIN responses r ON r.exam_id=e.id JOIN questions q ON q.id=r.question_id WHERE e.student_id=? AND e.status='submitted' AND q.teacher_id=?) ON DUPLICATE KEY UPDATE rating=VALUES(rating),comment=VALUES(comment)")){
            long t=Long.parseLong(f.get("teacherId"));
            p.setLong(1,student.id);
            p.setLong(2,t);
            p.setInt(3,rating);
            p.setString(4,f.getOrDefault("comment",""));
            p.setLong(5,student.id);
            p.setLong(6,t);
            if(p.executeUpdate()==0)throw new IllegalArgumentException("Complete an exam with this teacher before leaving a review.");
        }
    }

    public void recordExamEvent(User student,int exam,String event)throws SQLException{
        if(!Set.of("TAB_HIDDEN","COPY_ATTEMPT").contains(event))throw new IllegalArgumentException("Unknown exam event.");
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("INSERT INTO exam_events(exam_id,student_id,event_type) SELECT id,student_id,? FROM exams WHERE id=? AND student_id=? AND status='in_progress' AND session_state='running'")){
            p.setString(1,event);
            p.setInt(2,exam);
            p.setLong(3,student.id);
            if(p.executeUpdate()!=1)throw new IllegalArgumentException("Active exam not found.");
        }
    }
}
