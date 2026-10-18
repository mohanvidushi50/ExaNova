package coding;

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




public final class JavaCodeRunner {
    private final Store store;

    public JavaCodeRunner(Store store) {
        this.store = store;
    }

    public void runJava(User student,int exam,long question)throws Exception{
        if(!Store.JAVA_RUN_SLOTS.tryAcquire())throw new IllegalArgumentException("All Java runners are busy. Save your code and try again in a moment.");
        try{
            runJavaLimited(student,exam,question);
        }
        finally{
            Store.JAVA_RUN_SLOTS.release();
        }
    }

    private void runJavaLimited(User student,int exam,long question)throws Exception{
        String source;
        try(Connection c=store.connect();PreparedStatement p=c.prepareStatement("SELECT r.answer FROM responses r JOIN exams e ON e.id=r.exam_id JOIN questions q ON q.id=r.question_id WHERE e.id=? AND e.student_id=? AND e.status='in_progress' AND q.id=? AND q.type='coding' AND (e.subject='All Subjects' OR e.subject=q.subject)")){
            p.setInt(1,exam);
            p.setLong(2,student.id);
            p.setLong(3,question);
            try(ResultSet r=p.executeQuery()){
                if(!r.next())throw new IllegalArgumentException("Coding question or saved source not found.");
                source=r.getString(1);
            }
        }
        List<String[]> cases=new ArrayList<>();
        try(Connection c=store.connect();PreparedStatement p=c.prepareStatement("SELECT input_text,expected_output,is_hidden FROM coding_test_cases WHERE question_id=? ORDER BY case_order")){
            p.setLong(1,question);
            try(ResultSet r=p.executeQuery()){
                while(r.next())cases.add(new String[]{
                    r.getString(1),r.getString(2),r.getBoolean(3)?"1":"0"
                }
                );
            }
        }
        if(cases.isEmpty())throw new IllegalArgumentException("This coding question has no test cases.");
        int passed=0;
        String status="WRONG_ANSWER";
        StringBuilder details=new StringBuilder();
        Path dir=Files.createTempDirectory("evalio-java-");
        try{
            Files.writeString(dir.resolve("Main.java"),source,StandardCharsets.UTF_8);
            Process compile=new ProcessBuilder("javac","-encoding","UTF-8","Main.java").directory(dir.toFile()).redirectErrorStream(true).redirectOutput(dir.resolve("compile.log").toFile()).start();
            if(!compile.waitFor(12,TimeUnit.SECONDS)){
                compile.destroyForcibly();
                status="TIME_LIMIT";
                details.append("Compilation timed out.");
            }
            else if(compile.exitValue()!=0){
                status="COMPILE_ERROR";
                details.append(readCapped(dir.resolve("compile.log")));
            }
            else{
                status="ACCEPTED";
                for(int i=0;i<cases.size();i++){
                    Path in=dir.resolve("input.txt"),out=dir.resolve("output.txt");
                    Files.writeString(in,cases.get(i)[0],StandardCharsets.UTF_8);
                    Process run=new ProcessBuilder("java","-Xmx128m","-Djava.io.tmpdir="+dir,"Main").directory(dir.toFile()).redirectInput(in.toFile()).redirectErrorStream(true).redirectOutput(out.toFile()).start();
                    if(!run.waitFor(3,TimeUnit.SECONDS)){
                        run.destroyForcibly();
                        status="TIME_LIMIT";
                        details.append("Test ").append(i+1).append(": time limit exceeded.\n");
                        break;
                    }
                    if(run.exitValue()!=0){
                        status="RUNTIME_ERROR";
                        details.append("Test ").append(i+1).append(": program exited with an error.\n");
                        break;
                    }
                    String actual=Files.exists(out)?Files.readString(out,StandardCharsets.UTF_8):"";
                    if(!normalize(actual).equals(normalize(cases.get(i)[1]))){
                        status="WRONG_ANSWER";
                        if(!cases.get(i)[2].equals("1"))details.append("Test ").append(i+1).append(": output did not match.\n");
                    }
                    else{
                        passed++;
                        if(!cases.get(i)[2].equals("1"))details.append("Test ").append(i+1).append(": passed.\n");
                    }
                }
            }
        }
        finally{
            try(var paths=Files.walk(dir)){
                paths.sorted(Comparator.reverseOrder()).forEach(x->{
                    try{
                        Files.deleteIfExists(x);
                    }
                    catch(IOException ignored){
                    }
                }
                );
            }
        }
        try(Connection c=store.connect();PreparedStatement p=c.prepareStatement("INSERT INTO coding_runs(exam_id,question_id,student_id,status,passed,total,details) VALUES(?,?,?,?,?,?,?)")){
            p.setInt(1,exam);
            p.setLong(2,question);
            p.setLong(3,student.id);
            p.setString(4,status);
            p.setInt(5,passed);
            p.setInt(6,cases.size());
            p.setString(7,details.toString());
            p.executeUpdate();
        }
        Store.ANALYTICS_CACHE.remove(student.id);
    }

    private String normalize(String s){
        return s.replace("\r\n","\n").replace('\r','\n').strip().replaceAll("[ \t]+\n","\n");
    }

    private String readCapped(Path p)throws IOException{
        if(!Files.exists(p))return "Compilation failed.";
        String s=Files.readString(p,StandardCharsets.UTF_8);
        return s.length()>4000?s.substring(0,4000):s;
    }

    public String lastCodingRun(User student,int exam,long question)throws SQLException{
        try(Connection c=store.connect();PreparedStatement p=c.prepareStatement("SELECT status,passed,total,details FROM coding_runs WHERE student_id=? AND exam_id=? AND question_id=? ORDER BY id DESC LIMIT 1")){
            p.setLong(1,student.id);
            p.setInt(2,exam);
            p.setLong(3,question);
            try(ResultSet r=p.executeQuery()){
                if(!r.next())return "";
                return "<div class='run-result'><strong>"+Store.h(r.getString(1))+" · "+r.getInt(2)+"/"+r.getInt(3)+" tests passed</strong><pre>"+Store.h(r.getString(4))+"</pre></div>";
            }
        }
    }
}
