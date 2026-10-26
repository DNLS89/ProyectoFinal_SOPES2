package com.sopes.api.config;

import com.sopes.api.model.AccountRole;
import com.sopes.api.model.SessionAccount;
import com.sopes.api.service.PortalAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Restringe las rutas del portal y del tablero a la identidad y rol de sesión correspondiente. */
@Component
public class RoleAccessInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        AccountRole requiredRole = path.equals("/portal") || path.startsWith("/portal/")
                ? AccountRole.CUSTOMER : AccountRole.OPERATOR;
        var session = request.getSession(false);
        SessionAccount account = session == null ? null
                : (SessionAccount) session.getAttribute(PortalAccountService.SESSION_ATTRIBUTE);
        if (account == null) {
            response.sendRedirect(requiredRole == AccountRole.CUSTOMER ? "/customer/login" : "/operator/login");
            return false;
        }
        if (account.role() != requiredRole) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }
}
