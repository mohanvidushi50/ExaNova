package com.evalio.auth;

import com.evalio.*;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** Login, registration, logout, and browser session routes. */
public final class AuthRoutes {
    private AuthRoutes() { }

    public static boolean handle(HttpExchange x, String path, Map<String, String> form) throws Exception {
        if (path.equals("/login") && x.getRequestMethod().equals("POST")) {
            Application.User user = Application.DB.login(form.get("username"), form.get("password"));
            if (user == null) { Application.page(x, Views.login("Username or password was incorrect.")); return true; }
            String token = Application.token();
            Application.SESSIONS.put(token, user);
            x.getResponseHeaders().add("Set-Cookie", "EVALIO="+token+"; Path=/; HttpOnly; SameSite=Lax");
            Application.redirect(x, "/"); return true;
        }
        if (path.equals("/register") && x.getRequestMethod().equals("POST")) {
            String role = form.getOrDefault("role", "student");
            if (!Set.of("student", "teacher").contains(role)) { Application.page(x, Views.register("Choose student or teacher.")); return true; }
            Application.DB.register(form.get("username"), form.get("password"), role);
            Application.redirect(x, "/login?created=1"); return true;
        }
        if (path.equals("/login")) { Application.page(x, Views.login("")); return true; }
        if (path.equals("/register")) { Application.page(x, Views.register("")); return true; }
        if (path.equals("/logout")) {
            String cookie = Application.cookie(x, "EVALIO");
            if (cookie != null) Application.SESSIONS.remove(cookie);
            x.getResponseHeaders().add("Set-Cookie", "EVALIO=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
            Application.redirect(x, "/login"); return true;
        }
        return false;
    }
}