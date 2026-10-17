package recovery;

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




public final class ExamRecoveryStore {
    private final Store store;

    public ExamRecoveryStore(Store store) {
        this.store = store;
    }

    public void recoverInterruptedSessions()throws SQLException{
        List<Integer> interrupted=new ArrayList<>();
        try(Connection c=store.connect();PreparedStatement p=c.prepareStatement("SELECT id FROM exams WHERE status='in_progress' AND session_state='running'");ResultSet r=p.executeQuery()){
            while(r.next())interrupted.add(r.getInt(1));
        }
        int recovered=0;
        for(int exam:interrupted){
            if(recoverExam(exam))recovered++;
        }
        if(recovered>0)System.out.println("Recovery: restored committed answers and paused "+recovered+" interrupted exam process(es).");
    }

    private boolean recoverExam(int exam)throws SQLException{
        try(Connection c=store.connect()){
            c.setAutoCommit(false);
            try{
                try(PreparedStatement p=c.prepareStatement("SELECT status,session_state FROM exams WHERE id=? FOR UPDATE")){
                    p.setInt(1,exam);
                    try(ResultSet r=p.executeQuery()){
                        if(!r.next()||!r.getString(1).equals("in_progress")||!r.getString(2).equals("running")){
                            c.rollback();
                            return false;
                        }
                    }
                }
                long checkpointId=0,walBoundary=0;
                try(PreparedStatement p=c.prepareStatement("SELECT id,wal_id FROM exam_checkpoints WHERE exam_id=? ORDER BY id DESC LIMIT 1")){
                    p.setInt(1,exam);
                    try(ResultSet r=p.executeQuery()){
                        if(r.next()){
                            checkpointId=r.getLong(1);
                            walBoundary=r.getLong(2);
                        }
                    }
                }
                long latestWal;
                try(PreparedStatement p=c.prepareStatement("SELECT COALESCE(MAX(id),0) FROM exam_wal WHERE exam_id=?")){
                    p.setInt(1,exam);
                    try(ResultSet r=p.executeQuery()){
                        r.next();
                        latestWal=r.getLong(1);
                    }
                }
                if(checkpointId>0||latestWal>0){
                    try(PreparedStatement p=c.prepareStatement("DELETE FROM responses WHERE exam_id=?")){
                        p.setInt(1,exam);
                        p.executeUpdate();
                    }
                    if(checkpointId>0){
                        try(PreparedStatement p=c.prepareStatement("INSERT INTO responses(exam_id,question_id,answer) SELECT ?,question_id,answer FROM checkpoint_answers WHERE checkpoint_id=?")){
                            p.setInt(1,exam);
                            p.setLong(2,checkpointId);
                            p.executeUpdate();
                        }
                    }
                    try(PreparedStatement p=c.prepareStatement("INSERT INTO responses(exam_id,question_id,answer) SELECT exam_id,question_id,answer_snapshot FROM exam_wal WHERE exam_id=? AND id>? ORDER BY id ON DUPLICATE KEY UPDATE answer=VALUES(answer)")){
                        p.setInt(1,exam);
                        p.setLong(2,walBoundary);
                        p.executeUpdate();
                    }
                }
                try(PreparedStatement p=c.prepareStatement("UPDATE exams SET session_state='paused',version=version+1 WHERE id=? AND status='in_progress' AND session_state='running'")){
                    p.setInt(1,exam);
                    p.executeUpdate();
                }
                c.commit();
                return true;
            }
            catch(SQLException e){
                c.rollback();
                throw e;
            }
            finally{
                c.setAutoCommit(true);
            }
        }
    }

    public void checkpointRunningExams()throws SQLException{
        List<Integer> active=new ArrayList<>();
        try(Connection c=store.connect();PreparedStatement p=c.prepareStatement("SELECT id FROM exams WHERE status='in_progress' AND session_state='running'");ResultSet r=p.executeQuery()){
            while(r.next())active.add(r.getInt(1));
        }
        for(int exam:active)DeadlockRetry.run(() -> checkpoint(exam));
    }

    private void checkpoint(int exam)throws SQLException{
        try(Connection c=store.connect()){
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try{
                int version;
                try(PreparedStatement p=c.prepareStatement("SELECT version FROM exams WHERE id=? AND status='in_progress' AND session_state='running' FOR UPDATE")){
                    p.setInt(1,exam);
                    try(ResultSet r=p.executeQuery()){
                        if(!r.next()){
                            c.rollback();
                            return;
                        }
                        version=r.getInt(1);
                    }
                }
                StringBuilder snapshot=new StringBuilder("[");
                boolean first=true;
                try(PreparedStatement p=c.prepareStatement("SELECT question_id,answer FROM responses WHERE exam_id=? ORDER BY question_id")){
                    p.setInt(1,exam);
                    try(ResultSet r=p.executeQuery()){
                        while(r.next()){
                            if(!first)snapshot.append(',');
                            first=false;
                            snapshot.append("{\"questionId\":").append(r.getLong(1)).append(",\"answer\":\"").append(json(r.getString(2))).append("\"}");
                        }
                    }
                }
                snapshot.append(']');
                long walId;
                try(PreparedStatement p=c.prepareStatement("SELECT COALESCE(MAX(id),0) FROM exam_wal WHERE exam_id=?")){
                    p.setInt(1,exam);
                    try(ResultSet r=p.executeQuery()){
                        r.next();
                        walId=r.getLong(1);
                    }
                }
                long checkpointId;
                try(PreparedStatement p=c.prepareStatement("INSERT INTO exam_checkpoints(exam_id,version,wal_id,snapshot_json) VALUES(?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                    p.setInt(1,exam);
                    p.setInt(2,version);
                    p.setLong(3,walId);
                    p.setString(4,snapshot.toString());
                    p.executeUpdate();
                    try(ResultSet keys=p.getGeneratedKeys()){
                        if(!keys.next())throw new SQLException("Checkpoint id was not returned.");
                        checkpointId=keys.getLong(1);
                    }
                }
                try(PreparedStatement p=c.prepareStatement("INSERT INTO checkpoint_answers(checkpoint_id,question_id,answer) SELECT ?,question_id,answer FROM responses WHERE exam_id=?")){
                    p.setLong(1,checkpointId);
                    p.setInt(2,exam);
                    p.executeUpdate();
                }
                try(PreparedStatement p=c.prepareStatement("UPDATE exams SET last_checkpoint_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=? AND version=?")){
                    p.setInt(1,exam);
                    p.setInt(2,version);
                    p.executeUpdate();
                }
                c.commit();
            }
            catch(SQLException e){
                c.rollback();
                throw e;
            }
            finally{
                c.setAutoCommit(true);
            }
        }
    }

    private String json(String s){
        if(s==null)return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n").replace("\t","\\t");
    }

    public String renderMonitor()throws SQLException{
        StringBuilder b=new StringBuilder();
        try(Connection c=store.connect();Statement s=c.createStatement()){
            int running=stateCount(s,"running"),paused=stateCount(s,"paused"),wal=store.count(s,"exam_wal"),checkpoints=store.count(s,"exam_checkpoints"),audit=store.count(s,"response_audit");
            b.append("<section class='welcome'><p class='eyebrow'>ADMIN · LIVE OPERATIONS</p><h1>OS &amp; DBMS monitor</h1><p>Exam session states, saved-answer journal, and recovery checkpoints.</p></section><div class='stats'><div class='card'><span>Running exam sessions</span><strong>").append(running).append("</strong><p class='concept'>OS · concurrent request handling</p></div><div class='card'><span>Paused exam sessions</span><strong>").append(paused).append("</strong><p class='concept'>OS · session state</p></div><div class='card'><span>Answer journal records</span><strong>").append(wal).append("</strong><p class='concept'>DBMS · logical recovery journal</p></div><div class='card'><span>Checkpoints</span><strong>").append(checkpoints).append("</strong><p class='concept'>DBMS · recovery snapshot</p></div><div class='card'><span>Trigger audit events</span><strong>").append(audit).append("</strong><p class='concept'>DBMS · automatic trigger</p></div></div><section class='card'><h2>Active exam sessions</h2><p class='muted'>Each attempt has a saved state and version. Answer saves run in InnoDB transactions and lock that attempt's row.</p><div class='table-wrap'><table><thead><tr><th>Attempt</th><th>Student</th><th>Subject</th><th>State</th><th>Version</th><th>Last checkpoint</th></tr></thead><tbody>");
            try(ResultSet r=s.executeQuery("SELECT e.id,u.username,e.subject,e.session_state,e.version,e.last_checkpoint_at FROM exams e JOIN users u ON u.id=e.student_id WHERE e.status='in_progress' ORDER BY e.started_at DESC")){
                int n=0;
                while(r.next()){
                    n++;
                    b.append("<tr><td>#").append(r.getInt(1)).append("</td><td>").append(Store.h(r.getString(2))).append("</td><td>").append(Store.h(r.getString(3))).append("</td><td><span class='pill'>").append(Store.h(r.getString(4))).append("</span></td><td>").append(r.getInt(5)).append("</td><td>").append(Store.h(r.getString(6)==null?"Waiting for first checkpoint":r.getString(6))).append("</td></tr>");
                }
                if(n==0)b.append("<tr><td colspan='6' class='empty'>No active exam sessions.</td></tr>");
            }
            b.append("</tbody></table></div></section>").append(store.renderExamEvents()).append("<section class='concept-grid'><article class='card'><p class='eyebrow'>OPERATING SYSTEMS</p><h2>Concurrent exam requests</h2><p>Java virtual threads allow different students to answer at the same time. FCFS save queues preserve order within an exam, while separate queues let different exam attempts save concurrently. A semaphore limits Java code runs.</p></article><article class='card'><p class='eyebrow'>DATABASE SYSTEMS</p><h2>Transactions and concurrency control</h2><p>InnoDB transactions protect answer and timer changes. Row locks serialize the same student's exam creation and each attempt's writes, while unrelated students can proceed independently. Version checks catch stale updates.</p></article><article class='card'><p class='eyebrow'>OPERATING SYSTEMS</p><h2>Exam timers and process states</h2><p>Attempts move through created → running ↔ paused → terminated. A background scheduler expires attempts at their stored deadlines and creates checkpoints.</p></article><article class='card'><p class='eyebrow'>DATABASE SYSTEMS</p><h2>Indexes and durable history</h2><p>Deadline and session indexes support timer and recovery work. Foreign keys protect relationships, and archived questions preserve earlier responses and coding history.</p></article></section>");
        }
        return b.toString();
    }

    private int stateCount(Statement s,String state)throws SQLException{
        try(PreparedStatement p=s.getConnection().prepareStatement("SELECT COUNT(*) FROM exams WHERE status='in_progress' AND session_state=?")){
            p.setString(1,state);
            try(ResultSet r=p.executeQuery()){
                r.next();
                return r.getInt(1);
            }
        }
    }
}
