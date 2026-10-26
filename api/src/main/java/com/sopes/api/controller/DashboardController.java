package com.sopes.api.controller;

import com.sopes.api.model.ReceiptRequest;
import com.sopes.api.model.ReceiptResult;
import com.sopes.api.model.QueuePolicy;
import com.sopes.api.model.ServiceLevel;
import com.sopes.api.service.InventoryService;
import com.sopes.api.service.MerchandiseCatalog;
import com.sopes.api.service.OrderGeneratorService;
import com.sopes.api.service.OrderIntakeService;
import com.sopes.api.service.OrderProcessingService;
import com.sopes.api.service.ReceivingService;
import com.sopes.api.service.WarehouseConfiguration;
import com.sopes.api.service.WaitingOrderService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.ui.Model;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {
    private final MerchandiseCatalog merchandiseCatalog;
    private final WarehouseConfiguration warehouseConfiguration;
    private final InventoryService inventoryService;
    private final ReceivingService receivingService;
    private final WaitingOrderService orderService;
    private final OrderIntakeService orderIntakeService;
    private final OrderGeneratorService orderGeneratorService;
    private final OrderProcessingService orderProcessingService;

    public DashboardController(MerchandiseCatalog merchandiseCatalog,
                              WarehouseConfiguration warehouseConfiguration,
                              InventoryService inventoryService,
                              ReceivingService receivingService,
                              WaitingOrderService orderService,
                              OrderIntakeService orderIntakeService,
                              OrderGeneratorService orderGeneratorService,
                              OrderProcessingService orderProcessingService) {
        this.merchandiseCatalog = merchandiseCatalog;
        this.warehouseConfiguration = warehouseConfiguration;
        this.inventoryService = inventoryService;
        this.receivingService = receivingService;
        this.orderService = orderService;
        this.orderIntakeService = orderIntakeService;
        this.orderGeneratorService = orderGeneratorService;
        this.orderProcessingService = orderProcessingService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("merchandiseTypes", merchandiseCatalog.findAll());
        model.addAttribute("warehouseCapacity", warehouseConfiguration.getCapacityUnits());
        model.addAttribute("locations", warehouseConfiguration.findLocations());
        var statistics = orderService.statistics();
        model.addAttribute("orderStatistics", statistics);
        model.addAttribute("orderCount", statistics.totalOrders());
        model.addAttribute("activeOrderCount", statistics.queuedOrders() + statistics.waitingForStock()
                + orderProcessingService.findActiveOrders().size());
        model.addAttribute("completedOrders", orderService.findAll().stream()
                .filter(order -> order.status() == com.sopes.api.model.OrderStatus.COMPLETED).toList());
        model.addAttribute("activeOrders", orderProcessingService.findActiveOrders());
        model.addAttribute("resourceStates", orderProcessingService.resourceSnapshot());
        model.addAttribute("waitingQueue", orderService.findWaitingQueue());
        model.addAttribute("stockWaitOrders", orderService.findWaitingForStock());
        model.addAttribute("queuePolicy", orderService.getPolicy());
        model.addAttribute("queuePolicies", QueuePolicy.values());
        model.addAttribute("serviceLevels", ServiceLevel.values());
        model.addAttribute("generatorRunning", orderGeneratorService.isRunning());
        model.addAttribute("generatorInterval", orderGeneratorService.getIntervalSeconds());
        model.addAttribute("storedUnits", inventoryService.getStoredUnits());
        model.addAttribute("locationStock", inventoryService.stockByLocation());
        model.addAttribute("lots", inventoryService.findLots());
        model.addAttribute("pendingReceipts", receivingService.getPendingCount());
        model.addAttribute("receiptResults", receivingService.findRecentResults());
        return "dashboard";
    }

    @PostMapping("/receipts")
    public String submitReceipt(@RequestParam String productCode,
                                @RequestParam String merchandiseTypeCode,
                                @RequestParam String quantity,
                                RedirectAttributes redirectAttributes) {
        try {
            var type = merchandiseCatalog.findByCode(merchandiseTypeCode);
            int units = Integer.parseInt(quantity);
            ReceiptResult result = receivingService.submit(new ReceiptRequest(productCode, type, units));
            redirectAttributes.addFlashAttribute("receiptMessage", result.message());
            redirectAttributes.addFlashAttribute("receiptStatus", result.status().getDisplayName());
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("receiptMessage", e.getMessage());
            redirectAttributes.addFlashAttribute("receiptStatus", "Datos inválidos");
        }
        return "redirect:/";
    }

    @PostMapping("/orders")
    public String submitOrder(@RequestParam String customerName,
                              @RequestParam String email,
                              @RequestParam String serviceLevel,
                              @RequestParam java.util.List<String> productCodes,
                              @RequestParam java.util.List<String> productNames,
                              @RequestParam java.util.List<String> merchandiseTypeCodes,
                              @RequestParam java.util.List<String> quantities,
                              RedirectAttributes redirectAttributes) {
        try {
            ServiceLevel level = ServiceLevel.valueOf(serviceLevel);
            java.util.List<Integer> units = new java.util.ArrayList<>();
            for (int i = 0; i < productCodes.size(); i++) {
                String raw = i < quantities.size() ? quantities.get(i) : "";
                units.add(productCodes.get(i).isBlank() && raw.isBlank() ? 0 : Integer.parseInt(raw));
            }
            var order = orderIntakeService.create(customerName, email, level, productCodes, productNames,
                    merchandiseTypeCodes, units);
            redirectAttributes.addFlashAttribute("orderMessage", "Pedido " + order.id() + " creado: " + order.status().getDisplayName() + ".");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("orderMessage", "No se creó el pedido: " + e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/orders/policy")
    public String setQueuePolicy(@RequestParam String policy, RedirectAttributes redirectAttributes) {
        try {
            orderService.setPolicy(QueuePolicy.valueOf(policy));
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("orderMessage", "Política no válida.");
        }
        return "redirect:/";
    }

    @PostMapping("/generator/start")
    public String startGenerator(@RequestParam String intervalSeconds, RedirectAttributes redirectAttributes) {
        try {
            orderGeneratorService.start(Integer.parseInt(intervalSeconds));
            redirectAttributes.addFlashAttribute("orderMessage", "Generador iniciado.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("orderMessage", "No se pudo iniciar el generador: " + e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/generator/stop")
    public String stopGenerator() {
        orderGeneratorService.stop();
        return "redirect:/";
    }
}
