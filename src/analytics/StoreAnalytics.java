package analytics;

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




public final class StoreAnalytics {
    private final Store store;

    public StoreAnalytics(Store store) { this.store = store; }

    private Connection connect() throws SQLException { return store.connect(); }
    private static String h(String value) { return Store.h(value); }
    private static String alert(String value) { return Store.alert(value); }
    private static String required(Map<String,String> values,String key) { return Store.required(values,key); }
    private int count(Statement s,String table) throws SQLException { return store.count(s,table); }
    private int countWhere(Statement s,String table,String where) throws SQLException { return store.countWhere(s,table,where); }
    public String studentAnalytics(User student)throws SQLException{
        long now=System.currentTimeMillis();
        Store.AnalyticsCache cached=Store.ANALYTICS_CACHE.get(student.id);
        if(cached!=null&&now-cached.createdAt()<30_000)return cached.html();
        long[] stats=new long[5];
        double[] average={
            0
        }
        ;
        boolean[] hasAverage={
            false
        }
        ;
        String sql="SELECT COUNT(DISTINCT e.id),COUNT(DISTINCT CASE WHEN e.status='submitted' THEN e.id END),SUM(CASE WHEN e.status='submitted' AND q.type='mcq' AND r.answer IS NOT NULL THEN 1 ELSE 0 END),SUM(CASE WHEN e.status='submitted' AND q.type='mcq' AND r.answer=q.correct_option THEN 1 ELSE 0 END),AVG(CASE WHEN e.status='submitted' AND q.type='descriptive' AND r.marks IS NOT NULL THEN r.marks END) FROM exams e LEFT JOIN questions q ON (e.subject='All Subjects' OR q.subject=e.subject) LEFT JOIN responses r ON r.exam_id=e.id AND r.question_id=q.id WHERE e.student_id=?";
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql)){
            p.setLong(1,student.id);
            try(ResultSet r=p.executeQuery()){
                r.next();
                for(int i=0;i<4;i++)stats[i]=r.getLong(i+1);
                average[0]=r.getDouble(5);
                hasAverage[0]=!r.wasNull();
            }
        }
        long[] code=new long[4];
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT COUNT(*),SUM(status='ACCEPTED'),COALESCE(SUM(passed),0),COALESCE(SUM(total),0) FROM coding_runs WHERE student_id=?")){
            p.setLong(1,student.id);
            try(ResultSet r=p.executeQuery()){
                r.next();
                code[0]=r.getLong(1);
                code[1]=r.getLong(2);
                code[2]=r.getLong(3);
                code[3]=r.getLong(4);
            }
        }
        String accuracy=stats[2]==0?"—":String.format(Locale.ROOT,"%.0f%%",100.0*stats[3]/stats[2]);
        String written=!hasAverage[0]?"—":String.format(Locale.ROOT,"%.1f / 100",average[0]);
        String caseRate=code[3]==0?"—":String.format(Locale.ROOT,"%.0f%%",100.0*code[2]/code[3]);
        String html="<section class='analytics'><div class='analytics-head'><div><p class='eyebrow'>YOUR PROGRESS</p><h2>Performance analytics</h2></div><span class='muted'>Refreshes as your results change</span></div><div class='analytics-grid'><article class='metric'><span>Exams completed</span><strong>"+stats[1]+" / "+stats[0]+"</strong></article><article class='metric'><span>MCQ accuracy</span><strong>"+accuracy+"</strong><small>"+stats[3]+" correct of "+stats[2]+" answered</small></article><article class='metric'><span>Java accepted</span><strong>"+code[1]+" / "+code[0]+"</strong><small>"+caseRate+" test cases passed overall</small></article><article class='metric'><span>Written marks average</span><strong>"+written+"</strong><small>Across teacher-marked answers</small></article></div></section>"+scoreComparison(student)+recommendations(stats,average,hasAverage[0],code);
        Store.ANALYTICS_CACHE.put(student.id,new Store.AnalyticsCache(now,html));
        return html;
    }

    public void invalidateAnalytics(){
        Store.ANALYTICS_CACHE.clear();
    }

    public String studentResults(User student)throws SQLException{
        StringBuilder b=new StringBuilder("<div class='table-wrap'><table><thead><tr><th>Subject</th><th>Status</th><th>Started</th><th>Result</th></tr></thead><tbody>");
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT id,subject,status,started_at FROM exams WHERE student_id=? ORDER BY id DESC LIMIT 8")){
            p.setLong(1,student.id);
            try(ResultSet r=p.executeQuery()){
                int n=0;
                while(r.next()){
                    n++;
                    b.append("<tr><td>").append(h(r.getString(2))).append("</td><td><span class='pill'>").append(h(r.getString(3).replace('_',' '))).append("</span></td><td>").append(h(r.getString(4))).append("</td><td><a href='/results/").append(r.getInt(1)).append("'>View</a></td></tr>");
                }
                if(n==0)b.append("<tr><td colspan='4' class='empty'>No exams yet. Start your first exam above.</td></tr>");
            }
        }
        return b.append("</tbody></table></div>").toString();
    }

    private String scoreComparison(User student)throws SQLException{
        Map<Long,double[]> scores=new HashMap<>();
        Map<Long,String> names=new HashMap<>();
        String sql="SELECT e.student_id,u.username,e.id,SUM(CASE WHEN q.type='mcq' AND r.answer=q.correct_option AND r.answer IS NOT NULL THEN 1.0 WHEN q.type='descriptive' AND r.marks IS NOT NULL THEN r.marks/100.0 ELSE 0 END),SUM(CASE WHEN q.type='mcq' THEN 1.0 WHEN q.type='descriptive' AND r.marks IS NOT NULL THEN 1.0 ELSE 0 END) FROM exams e JOIN users u ON u.id=e.student_id JOIN questions q ON (e.subject='All Subjects' OR q.subject=e.subject) LEFT JOIN responses r ON r.exam_id=e.id AND r.question_id=q.id WHERE e.status='submitted' AND q.type IN ('mcq','descriptive') GROUP BY e.student_id,u.username,e.id HAVING SUM(CASE WHEN q.type='mcq' THEN 1.0 WHEN q.type='descriptive' AND r.marks IS NOT NULL THEN 1.0 ELSE 0 END)>0";
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql);ResultSet r=p.executeQuery()){
            while(r.next()){
                long id=r.getLong(1);
                double possible=r.getDouble(5);
                double[] v=scores.computeIfAbsent(id,k->new double[2]);
                v[0]+=100.0*r.getDouble(4)/possible;
                v[1]++;
                names.put(id,r.getString(2));
            }
        }
        if(scores.isEmpty())return "<section class='card'><h2>Marks comparison</h2><p class='muted'>Comparison appears after submitted exams have been marked.</p></section>";
        List<Long> order=new ArrayList<>(scores.keySet());
        order.sort((a,b)->Double.compare(scores.get(b)[0]/scores.get(b)[1],scores.get(a)[0]/scores.get(a)[1]));
        int rank=order.indexOf(student.id)+1;
        double own=scores.containsKey(student.id)?scores.get(student.id)[0]/scores.get(student.id)[1]:0;
        double classAverage=0;
        for(long id:order)classAverage+=scores.get(id)[0]/scores.get(id)[1];
        classAverage/=order.size();
        StringBuilder out=new StringBuilder("<section class='card'><h2>Marks comparison</h2><p>Your average: <strong>").append(String.format(Locale.ROOT,"%.1f%%",own)).append("</strong> · Class average: <strong>").append(String.format(Locale.ROOT,"%.1f%%",classAverage)).append("</strong> · Rank: <strong>").append(rank==0?"Not ranked":rank+" of "+order.size()).append("</strong></p><p class='muted'>Based on submitted MCQ and teacher-marked descriptive questions.</p><h3>Top scores</h3><ol>");
        for(int i=0;i<Math.min(3,order.size());i++){
            long id=order.get(i);
            out.append("<li>").append(h(names.get(id))).append(" — ").append(String.format(Locale.ROOT,"%.1f%%",scores.get(id)[0]/scores.get(id)[1])).append(" average</li>");
        }
        return out.append("</ol></section>").toString();
    }

    private String recommendations(long[] stats,double[] average,boolean hasAverage,long[] code){
        List<String> advice=new ArrayList<>();
        if(stats[2]>0&&100.0*stats[3]/stats[2]<70)advice.add("Review the topics behind missed MCQ answers and retry a practice exam.");
        if(hasAverage&&average[0]<70)advice.add("Use teacher feedback to strengthen written answers before the next exam.");
        if(code[3]>0&&100.0*code[2]/code[3]<80)advice.add("Review failing Java test cases and test edge inputs before running again.");
        if(advice.isEmpty())advice.add("Keep practicing and review each result to maintain your progress.");
        StringBuilder b=new StringBuilder("<section class='card'><h2>Suggestions to improve</h2><ul>");
        for(String item:advice)b.append("<li>").append(h(item)).append("</li>");
        return b.append("</ul></section>").toString();
    }
}
