package com.glassstore.order.controller;
import com.glassstore.common.dto.ApiResponse;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.service.LoyaltyLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@RestController @RequiredArgsConstructor
public class LabCustomerTierController {
    private final OrderRepository orders;
    private final LoyaltyLifecycleService loyalty;
    public record CustomerTier(Long orderId,Long customerId,String tierCode,String tier) {}
    @GetMapping("/api/orders/lab-customer-tiers")
    public ApiResponse<List<CustomerTier>> tiers(@RequestParam("orderIds") List<Long> ids) {
        if(ids.isEmpty() || ids.size()>200 || ids.stream().anyMatch(id->id==null || id<=0))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Chọn tối đa 200 đơn hàng hợp lệ.");
        Map<Long,com.glassstore.order.entity.LoyaltyAccount> accounts=new HashMap<>();
        var rows=orders.findAllById(ids);
        rows.stream().map(com.glassstore.order.entity.Order::getCustomerId).filter(Objects::nonNull).distinct().sorted().forEach(id->accounts.put(id,loyalty.current(id)));
        Map<String,String> labels=Map.of("MEMBER","Thành Viên","SILVER","Hạng Bạc","GOLD","Hạng Vàng","DIAMOND","Hạng Kim Cương");
        return ApiResponse.success(rows.stream().map(order->{var a=accounts.get(order.getCustomerId());return new CustomerTier(order.getId(),order.getCustomerId(),a==null?null:a.getTierCode(),a==null?"Khách vãng lai":labels.get(a.getTierCode()));}).toList());
    }
}
