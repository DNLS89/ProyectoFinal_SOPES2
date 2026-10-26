package com.sopes.api.controller;

import com.sopes.api.model.CustomerOrder;
import com.sopes.api.model.ServiceLevel;
import com.sopes.api.model.SessionAccount;
import com.sopes.api.service.CustomerRegistry;
import com.sopes.api.service.OrderIntakeService;
import com.sopes.api.service.PortalAccountService;
import com.sopes.api.service.ProductCatalog;
import com.sopes.api.service.WaitingOrderService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.util.List;

/** Expone al cliente sus pedidos y usa siempre la identidad autenticada de la sesión. */
@Controller
public class CustomerPortalController {
    private final CustomerRegistry customers;
    private final ProductCatalog products;
    private final OrderIntakeService orderIntake;
    private final WaitingOrderService orders;

    public CustomerPortalController(CustomerRegistry customers, ProductCatalog products,
                                    OrderIntakeService orderIntake, WaitingOrderService orders) {
        this.customers = customers;
        this.products = products;
        this.orderIntake = orderIntake;
        this.orders = orders;
    }

    @GetMapping("/portal")
    public String portal(HttpSession session, Model model) {
        SessionAccount account = authenticatedAccount(session);
        var customer = customers.findById(account.customerId());
        model.addAttribute("account", account);
        model.addAttribute("products", products.findAll());
        model.addAttribute("serviceLevels", ServiceLevel.values());
        model.addAttribute("orders", orders.findByCustomerId(account.customerId()));
        model.addAttribute("customerName", customer == null ? account.name() : customer.name());
        return "customer-portal";
    }

    @PostMapping("/portal/orders")
    public String createOrder(@RequestParam(required = false) String serviceLevel,
                              @RequestParam(required = false) List<String> productCodes,
                              @RequestParam(required = false) List<String> quantities,
                              HttpSession session,
                              RedirectAttributes redirectAttributes) {
        SessionAccount account = authenticatedAccount(session);
        try {
            if (serviceLevel == null || serviceLevel.isBlank()) {
                throw new IllegalArgumentException("Seleccione un nivel de servicio.");
            }
            if (productCodes == null || quantities == null || productCodes.isEmpty()
                    || productCodes.size() != quantities.size()) {
                throw new IllegalArgumentException("Revise las líneas de productos del pedido.");
            }
            List<Integer> units = quantities.stream().map(Integer::parseInt).toList();
            var customer = customers.findById(account.customerId());
            var order = orderIntake.create(customer, ServiceLevel.valueOf(serviceLevel), productCodes, units);
            redirectAttributes.addFlashAttribute("portalMessage",
                    "Pedido " + order.id() + " registrado con estado: " + order.status().getDisplayName() + ".");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("portalError", e.getMessage());
        }
        return "redirect:/portal";
    }

    @GetMapping("/portal/state")
    @ResponseBody
    public List<PortalOrderView> ownOrderState(HttpSession session) {
        String customerId = authenticatedAccount(session).customerId();
        return orders.findByCustomerId(customerId).stream().map(CustomerPortalController::view).toList();
    }

    private SessionAccount authenticatedAccount(HttpSession session) {
        Object account = session.getAttribute(PortalAccountService.SESSION_ATTRIBUTE);
        if (account instanceof SessionAccount authenticated) return authenticated;
        throw new IllegalStateException("La sesión de cliente no es válida.");
    }

    private static PortalOrderView view(CustomerOrder order) {
        return new PortalOrderView(order.id(), order.status().getDisplayName(), order.serviceLevel().getDisplayName(),
                order.getTotalUnits(), order.createdAt(), order.queueEnteredAt(), order.startedAt(),
                order.completedAt(), order.progressPercent(), order.lines().stream()
                .map(line -> new PortalOrderLineView(line.productCode(), line.productName(),
                        line.merchandiseType().name(), line.quantity())).toList());
    }

    public record PortalOrderView(String id, String status, String serviceLevel, int units, Instant createdAt,
                                  Instant queueEnteredAt, Instant startedAt, Instant completedAt,
                                  int progressPercent, List<PortalOrderLineView> lines) { }

    public record PortalOrderLineView(String productCode, String productName, String merchandiseType, int quantity) { }
}
