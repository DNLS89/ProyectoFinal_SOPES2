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
import com.sopes.api.service.ProductCatalog;
import com.sopes.api.service.CustomerRegistry;
import com.sopes.api.service.SchedulingComparisonService;
import com.sopes.api.service.ReceivingService;
import com.sopes.api.service.ResourceConflictService;
import com.sopes.api.service.WarehouseConfiguration;
import com.sopes.api.service.WaitingOrderService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
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
    private final ResourceConflictService conflictService;
    private final ProductCatalog productCatalog;
    private final CustomerRegistry customerRegistry;
    private final SchedulingComparisonService comparisonService;

    public DashboardController(MerchandiseCatalog merchandiseCatalog,
                              WarehouseConfiguration warehouseConfiguration,
                              InventoryService inventoryService,
                              ReceivingService receivingService,
                              WaitingOrderService orderService,
                              OrderIntakeService orderIntakeService,
                              OrderGeneratorService orderGeneratorService,
                              OrderProcessingService orderProcessingService,
                              ResourceConflictService conflictService,
                              ProductCatalog productCatalog,
                              CustomerRegistry customerRegistry,
                              SchedulingComparisonService comparisonService) {
        this.merchandiseCatalog = merchandiseCatalog;
        this.warehouseConfiguration = warehouseConfiguration;
        this.inventoryService = inventoryService;
        this.receivingService = receivingService;
        this.orderService = orderService;
        this.orderIntakeService = orderIntakeService;
        this.orderGeneratorService = orderGeneratorService;
        this.orderProcessingService = orderProcessingService;
        this.conflictService = conflictService;
        this.productCatalog = productCatalog;
        this.customerRegistry = customerRegistry;
        this.comparisonService = comparisonService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("merchandiseTypes", merchandiseCatalog.findAll());
        model.addAttribute("products", productCatalog.findAll());
        model.addAttribute("customers", customerRegistry.findAll());
        model.addAttribute("productInventory", inventoryService.findProductInventory(productCatalog.findAll()));
        model.addAttribute("comparison", comparisonService.latest());
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
        model.addAttribute("conflict", conflictService.snapshot());
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

    @GetMapping("/dashboard/state")
    @ResponseBody
    public LiveDashboardState liveState() {
        var allOrders = orderService.findAll();
        var conflict = conflictService.snapshot();
        ConflictView conflictView = new ConflictView(conflict.active(), conflict.message(), conflict.explanation(),
                conflict.expiresAt(), conflict.operationA() == null ? null : conflictOperation(conflict.operationA()),
                conflict.operationB() == null ? null : conflictOperation(conflict.operationB()));
        return new LiveDashboardState(orderService.statistics(),
                orderService.findWaitingQueue().stream().map(this::orderView).toList(),
                orderService.findWaitingForStock().stream().map(this::orderView).toList(),
                orderProcessingService.findActiveOrders().stream().map(this::orderView).toList(),
                allOrders.stream().filter(order -> order.status() == com.sopes.api.model.OrderStatus.COMPLETED)
                        .map(this::orderView).toList(),
                orderProcessingService.resourceSnapshot(), inventoryService.getStoredUnits(),
                inventoryService.getCapacityUnits(), inventoryService.stockByLocation(),
                inventoryService.findLots().stream().map(lot -> new LotView(lot.productCode(), lot.merchandiseTypeCode(),
                        lot.quantity(), lot.allocations().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().code(), java.util.Map.Entry::getValue)))).toList(),
                inventoryService.findProductInventory(productCatalog.findAll()),
                receivingService.getPendingCount(), receivingService.findRecentResults(), conflictView,
                comparisonService.latest());
    }

    private OrderView orderView(com.sopes.api.model.CustomerOrder order) {
        return new OrderView(order.id(), order.customer().name(), order.serviceLevel().getDisplayName(),
                order.serviceLevel().getTargetWaitMinutes(), order.getTotalUnits(), order.status().getDisplayName(),
                order.queueEnteredAt(), order.startedAt(), order.completedAt(), order.progressPercent(),
                order.estimatedRemainingSeconds(), order.queueWaitSeconds(), order.waitTargetMeasured(), order.waitTargetMet());
    }

    private ConflictOperationView conflictOperation(ResourceConflictService.ConflictOperation operation) {
        return new ConflictOperationView(operation.name(), operation.holds(), operation.requests(), operation.status());
    }

    public record OrderView(String id, String customerName, String serviceLevel, int targetWaitMinutes, int units,
                            String status, java.time.Instant queueEnteredAt, java.time.Instant startedAt,
                            java.time.Instant completedAt, int progressPercent, long remainingSeconds,
                            long queueWaitSeconds, boolean waitTargetMeasured, boolean waitTargetMet) { }

    public record ConflictOperationView(String name, String holds, String requests, String status) { }
    public record LotView(String productCode, String merchandiseTypeCode, int quantity, java.util.Map<String, Integer> allocations) { }
    public record ConflictView(boolean active, String message, String explanation, java.time.Instant expiresAt,
                               ConflictOperationView operationA, ConflictOperationView operationB) { }

    public record LiveDashboardState(WaitingOrderService.OrderStatistics statistics, java.util.List<OrderView> queue,
                                     java.util.List<OrderView> stockWait, java.util.List<OrderView> active,
                                     java.util.List<OrderView> completed,
                                     java.util.List<com.sopes.api.service.SharedResourceService.ResourceState> resources,
                                     int storedUnits, int capacityUnits, java.util.Map<String, Integer> locationStock,
                                     java.util.List<LotView> lots,
                                     java.util.List<com.sopes.api.model.ProductInventory> productInventory,
                                     int pendingReceipts, java.util.List<com.sopes.api.model.ReceiptResult> receiptResults,
                                     ConflictView conflict, SchedulingComparisonService.ComparisonSnapshot comparison) { }

    @PostMapping("/receipts")
    public String submitReceipt(@RequestParam String productCode,
                                @RequestParam String quantity,
                                RedirectAttributes redirectAttributes) {
        try {
            var product = productCatalog.findByCode(productCode);
            int units = Integer.parseInt(quantity);
            ReceiptResult result = receivingService.submit(new ReceiptRequest(product.code(), product.merchandiseType(), units));
            redirectAttributes.addFlashAttribute("receiptMessage", result.message());
            redirectAttributes.addFlashAttribute("receiptStatus", result.status().getDisplayName());
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("receiptMessage", e.getMessage());
            redirectAttributes.addFlashAttribute("receiptStatus", "Datos inválidos");
        }
        return "redirect:/";
    }

    @PostMapping("/orders")
    public String submitOrder(@RequestParam(required = false) String customerId,
                              @RequestParam(required = false) String customerName,
                              @RequestParam(required = false) String email,
                              @RequestParam String serviceLevel,
                              @RequestParam java.util.List<String> productCodes,
                              @RequestParam java.util.List<String> quantities,
                              RedirectAttributes redirectAttributes) {
        try {
            ServiceLevel level = ServiceLevel.valueOf(serviceLevel);
            java.util.List<Integer> units = quantities.stream().map(raw -> raw == null || raw.isBlank() ? 0 : Integer.parseInt(raw)).toList();
            var order = orderIntakeService.create(customerId, customerName, email, level, productCodes, units);
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

    @PostMapping("/orders/compare")
    public String comparePolicies(RedirectAttributes redirectAttributes) {
        comparisonService.compare(orderService.findWaitingQueue());
        redirectAttributes.addFlashAttribute("orderMessage", "Comparación controlada calculada sobre la misma cola capturada.");
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

    @PostMapping("/conflict/start")
    public String startConflict(RedirectAttributes redirectAttributes) {
        try {
            var conflict = conflictService.trigger();
            redirectAttributes.addFlashAttribute("conflictMessage", conflict.message());
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("conflictMessage", "No se pudo iniciar el escenario: " + e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/conflict/resolve")
    public String resolveConflict(@RequestParam String winner, RedirectAttributes redirectAttributes) {
        try {
            var conflict = conflictService.resolve(winner);
            redirectAttributes.addFlashAttribute("conflictMessage", conflict.message());
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("conflictMessage", "No se pudo resolver el escenario: " + e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/conflict/cancel")
    public String cancelConflict(RedirectAttributes redirectAttributes) {
        var conflict = conflictService.cancel();
        redirectAttributes.addFlashAttribute("conflictMessage", conflict.message());
        return "redirect:/";
    }
}
