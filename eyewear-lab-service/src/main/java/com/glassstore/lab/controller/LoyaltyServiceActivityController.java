package com.glassstore.lab.controller;
import com.glassstore.lab.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

@RestController @RequiredArgsConstructor
public class LoyaltyServiceActivityController {
    private final RepairServiceRepository repairs;
    private final WarrantyRepository warranties;
    public record Activity(String key,Long customerId,LocalDateTime completedAt) {}
    @GetMapping("/internal/loyalty/completed-services")
    public List<Activity> completed() {
        List<Activity> result=new ArrayList<>();
        repairs.findAll().stream().filter(r->r.getCustomerId()!=null && r.getCompletedAt()!=null
                && Set.of("READY_FOR_PICKUP","COMPLETED").contains(r.getStatus()))
            .forEach(r->result.add(new Activity("REPAIR:"+r.getId(),r.getCustomerId(),r.getCompletedAt())));
        warranties.findAll().stream().filter(w->w.getCustomerId()!=null && w.getUpdatedAt()!=null && "COMPLETED".equals(w.getStatus()))
            .forEach(w->result.add(new Activity("WARRANTY:"+w.getId(),w.getCustomerId(),w.getUpdatedAt())));
        return result;
    }
}
