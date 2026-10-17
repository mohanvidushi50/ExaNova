package auth;

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




public final class StoreAccounts {
    private final Store store;

    public StoreAccounts(Store store) { this.store = store; }

    private Connection connect() throws SQLException { return store.connect(); }
    private static String h(String value) { return Store.h(value); }
    private static String alert(String value) { return Store.alert(value); }
    private static String required(Map<String,String> values,String key) { return Store.required(values,key); }
    private int count(Statement s,String table) throws SQLException { return store.count(s,table); }
    private int countWhere(Statement s,String table,String where) throws SQLException { return store.countWhere(s,table,where); }
    public void createBootstrapAdmin()throws SQLException{
        String name=System.getenv("EVALIO_ADMIN_USERNAME"),pass=System.getenv("EVALIO_ADMIN_PASSWORD");
        if(name==null||name.isBlank()||pass==null||pass.length()<8)return;
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("INSERT IGNORE INTO users(username,password_hash,role) VALUES(?,?,'admin')")){
            p.setString(1,name.trim());
            p.setString(2,hash(pass));
            p.executeUpdate();
        }
    }

    public User login(String name,String pass)throws Exception{
        if(name==null||pass==null)return null;
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT id,username,password_hash,role FROM users WHERE username=?")){
            p.setString(1,name.trim());
            try(ResultSet r=p.executeQuery()){
                if(!r.next()||!verify(pass,r.getString(3)))return null;
                return new User(r.getLong(1),r.getString(2),r.getString(4));
            }
        }
    }

    public void register(String name,String pass,String role)throws Exception{
        if(name==null||!name.matches("[A-Za-z0-9_.-]{3,60}"))throw new IllegalArgumentException("Use 3–60 letters, numbers, dots, dashes, or underscores for the username.");
        if(pass==null||pass.length()<8)throw new IllegalArgumentException("Use a password with at least 8 characters.");
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("INSERT INTO users(username,password_hash,role) VALUES(?,?,?)")){
            p.setString(1,name.trim());
            p.setString(2,hash(pass));
            p.setString(3,role);
            p.executeUpdate();
        }
        catch(SQLIntegrityConstraintViolationException e){
            throw new IllegalArgumentException("That username is already taken.");
        }
    }

    public String hash(String pass){
        try{
            byte[]salt=new byte[16];
            Store.RANDOM.nextBytes(salt);
            byte[]d=derive(pass,salt);
            return Base64.getEncoder().encodeToString(salt)+":"+Base64.getEncoder().encodeToString(d);
        }
        catch(Exception e){
            throw new IllegalStateException("Could not protect password",e);
        }
    }

    public boolean verify(String pass,String stored){
        try{
            String[]parts=stored.split(":",2);
            if(parts.length!=2)return false;
            byte[]salt=Base64.getDecoder().decode(parts[0]),expected=Base64.getDecoder().decode(parts[1]);
            return MessageDigest.isEqual(expected,derive(pass,salt));
        }
        catch(Exception e){
            return false;
        }
    }

    private byte[] derive(String pass,byte[]salt)throws Exception{
        javax.crypto.SecretKeyFactory f=javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return f.generateSecret(new javax.crypto.spec.PBEKeySpec(pass.toCharArray(),salt,120000,256)).getEncoded();
    }

    public List<String> subjects()throws SQLException{
        List<String>a=new ArrayList<>();
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT DISTINCT subject FROM questions WHERE is_active=TRUE ORDER BY subject");ResultSet r=p.executeQuery()){
            while(r.next())a.add(r.getString(1));
        }
        return a;
    }
}
