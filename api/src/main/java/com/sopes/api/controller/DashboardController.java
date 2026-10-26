package com.sopes.api.controller;

import com.sopes.api.model.ReceiptRequest;
import com.sopes.api.model.ReceiptResult;
import com.sopes.api.service.InventoryService;
import com.sopes.api.service.MerchandiseCatalog;
import com.sopes.api.service.ReceivingService;
import com.sopes.api.service.WarehouseConfiguration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.ui.Model;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {
    private final java.util.List<com.sopes.api.model.SharedResource> resources;
    private final MerchandiseCatalog merchandiseCatalog;
    private final WarehouseConfiguration warehouseConfiguration;
    private final InventoryService inventoryService;
    private final ReceivingService receivingService;

    public DashboardController(java.util.List<com.sopes.api.model.SharedResource> resources,
                              MerchandiseCatalog merchandiseCatalog,
                              WarehouseConfiguration warehouseConfiguration,
                              InventoryService inventoryService,
                              ReceivingService receivingService) {
        this.resources = resources;
        this.merchandiseCatalog = merchandiseCatalog;
        this.warehouseConfiguration = warehouseConfiguration;
        this.inventoryService = inventoryService;
        this.receivingService = receivingService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("resources", resources);
        model.addAttribute("merchandiseTypes", merchandiseCatalog.findAll());
        model.addAttribute("warehouseCapacity", warehouseConfiguration.getCapacityUnits());
        model.addAttribute("locations", warehouseConfiguration.findLocations());
        model.addAttribute("orderCount", 0);
        model.addAttribute("activeOrderCount", 0);
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
}
