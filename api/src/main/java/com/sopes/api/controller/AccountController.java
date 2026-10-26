package com.sopes.api.controller;

import com.sopes.api.model.AccountRole;
import com.sopes.api.model.SessionAccount;
import com.sopes.api.service.PortalAccountService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Atiende el acceso de clientes y operadores sin exponer credenciales en las sesiones. */
@Controller
public class AccountController {
    private final PortalAccountService accounts;

    public AccountController(PortalAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping({"/operator/login", "/customer/login"})
    public String loginPage(HttpServletRequest request, Model model) {
        var session = request.getSession(false);
        if (session != null) {
            Object account = session.getAttribute(PortalAccountService.SESSION_ATTRIBUTE);
            if (account instanceof SessionAccount authenticated) return redirectFor(authenticated.role());
        }
        model.addAttribute("audience", request.getRequestURI().startsWith("/operator") ? "operador" : "cliente");
        return "account-login";
    }

    @PostMapping("/login")
    public String login(@RequestParam(required = false) String email,
                        @RequestParam(required = false) String password,
                        @RequestParam(defaultValue = "operador") String audience,
                        HttpServletRequest request,
                        RedirectAttributes redirectAttributes) {
        SessionAccount account;
        try {
            account = accounts.authenticate(email, password);
        } catch (IllegalArgumentException e) {
            account = null;
        }
        if (account == null) {
            redirectAttributes.addFlashAttribute("loginError", "Correo o contraseña incorrectos.");
            return "redirect:" + ("cliente".equals(audience) ? "/customer/login" : "/operator/login");
        }
        var previous = request.getSession(false);
        if (previous != null) previous.invalidate();
        request.getSession(true).setAttribute(PortalAccountService.SESSION_ATTRIBUTE, account);
        return redirectFor(account.role());
    }

    @GetMapping("/customer/register")
    public String registrationPage() {
        return "customer-register";
    }

    @PostMapping("/customer/register")
    public String register(@RequestParam(required = false) String name,
                           @RequestParam(required = false) String email,
                           @RequestParam(required = false) String password,
                           RedirectAttributes redirectAttributes) {
        try {
            accounts.registerCustomer(name, email, password);
            redirectAttributes.addFlashAttribute("registrationMessage", "Cuenta creada. Inicie sesión para continuar.");
            return "redirect:/customer/login";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("registrationError", e.getMessage());
            return "redirect:/customer/register";
        }
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        var session = request.getSession(false);
        String loginPage = "/operator/login";
        if (session != null && session.getAttribute(PortalAccountService.SESSION_ATTRIBUTE) instanceof SessionAccount account
                && account.role() == AccountRole.CUSTOMER) {
            loginPage = "/customer/login";
        }
        if (session != null) session.invalidate();
        return "redirect:" + loginPage;
    }

    private String redirectFor(AccountRole role) {
        return role == AccountRole.OPERATOR ? "redirect:/" : "redirect:/portal";
    }
}
