package admin;

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




public final class StoreAdmin {
    private final Store store;

    public StoreAdmin(Store store) { this.store = store; }

    private Connection connect() throws SQLException { return store.connect(); }
    private static String h(String value) { return Store.h(value); }
    private static String alert(String value) { return Store.alert(value); }
    private static String required(Map<String,String> values,String key) { return Store.required(values,key); }
    private int count(Statement s,String table) throws SQLException { return store.count(s,table); }
    private int countWhere(Statement s,String table,String where) throws SQLException { return store.countWhere(s,table,where); }
    public void changeUserRole(User admin,long userId,String role)throws SQLException{
        if(!Set.of("student","teacher","admin").contains(role))throw new IllegalArgumentException("Choose a valid account role.");
        if(userId<1||userId==admin.id)throw new IllegalArgumentException("Choose another user; you cannot change your own role here.");
        Store.ROLE_CHANGE_LOCK.lock();
        try(Connection c=connect()){
            c.setAutoCommit(false);
            try{
                String current;
                try(PreparedStatement p=c.prepareStatement("SELECT role FROM users WHERE id=? FOR UPDATE")){
                    p.setLong(1,userId);
                    try(ResultSet r=p.executeQuery()){
                        if(!r.next())throw new IllegalArgumentException("User account not found.");
                        current=r.getString(1);
                    }
                }
                if(current.equals("admin")&&!role.equals("admin")){
                    int admins=0;
                    try(PreparedStatement p=c.prepareStatement("SELECT id FROM users WHERE role='admin' FOR UPDATE");ResultSet r=p.executeQuery()){
                        while(r.next())admins++;
                    }
                    if(admins<=1)throw new IllegalArgumentException("The last administrator cannot be demoted.");
                }
                try(PreparedStatement p=c.prepareStatement("UPDATE users SET role=? WHERE id=?")){
                    p.setString(1,role);
                    p.setLong(2,userId);
                    p.executeUpdate();
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
        finally{
            Store.ROLE_CHANGE_LOCK.unlock();
        }
    }

    public String renderAdmin(User admin)throws SQLException{
        StringBuilder b=new StringBuilder("<section class='welcome'><p class='eyebrow'>ADMIN</p><h1>Platform overview</h1><p>Manage account roles and inspect current system activity.</p></section><section class='card'><h2>User accounts and roles</h2><div class='table-wrap'><table><thead><tr><th>Username</th><th>Role</th><th>Joined</th><th>Change role</th></tr></thead><tbody>");
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT id,username,role,created_at FROM users ORDER BY created_at DESC");ResultSet r=p.executeQuery()){
            while(r.next()){
                long id=r.getLong(1);
                String role=r.getString(3);
                b.append("<tr><td>").append(h(r.getString(2))).append("</td><td><span class='pill'>").append(h(role)).append("</span></td><td>").append(h(r.getString(4))).append("</td><td>");
                if(id==admin.id)b.append("Current admin");
                else{
                    b.append("<form class='inline-form' method='post' action='/admin/role'><input type='hidden' name='userId' value='").append(id).append("'><label class='role-select'><select name='role'>");
                    for(String option:List.of("student","teacher","admin"))b.append("<option value='").append(option).append("'").append(role.equals(option)?" selected":"").append(">").append(option).append("</option>");
                    b.append("</select></label><button class='button small'>Update</button></form>");
                }
                b.append("</td></tr>");
            }
        }
        b.append("</tbody></table></div></section><section class='card'><h2>Crash recovery demonstration</h2><p>End the app process, then restart it to show interrupted exams being paused and recovered from committed MySQL answers.</p><form class='crash-simulator' method='post' action='/admin/crash'><button class='button danger'>Simulate application crash</button></form></section>");
        return b.toString();
    }

    public String renderExamEvents()throws SQLException{
        StringBuilder b=new StringBuilder("<section class='card'><h2>Anti-cheat event log</h2><p class='muted'>Records tab switches and copy attempts during active exams. These are review signals, not proof of cheating.</p><div class='table-wrap'><table><thead><tr><th>Student</th><th>Exam</th><th>Event</th><th>Time</th></tr></thead><tbody>");
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT u.username,e.exam_id,e.event_type,e.occurred_at FROM exam_events e JOIN users u ON u.id=e.student_id ORDER BY e.id DESC LIMIT 20");ResultSet r=p.executeQuery()){
            int n=0;
            while(r.next()){
                n++;
                b.append("<tr><td>").append(h(r.getString(1))).append("</td><td>#").append(r.getInt(2)).append("</td><td>").append(h(r.getString(3).replace('_',' '))).append("</td><td>").append(h(r.getString(4))).append("</td></tr>");
            }
            if(n==0)b.append("<tr><td colspan='4' class='empty'>No exam events recorded.</td></tr>");
        }
        return b.append("</tbody></table></div></section>").toString();
    }
}
