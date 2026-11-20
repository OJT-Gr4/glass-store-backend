package com.glassstore.identity.security;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
public record WorkBranch(String code,String name) {
    public static WorkBranch resolve(String code,String fallbackName) {
        if(code==null || code.isBlank()) {
            String name=java.text.Normalizer.normalize(Objects.requireNonNullElse(fallbackName,""),java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
            code=name.contains("lab") || name.contains("xuong")?"LAB":name.contains("trang tien") || name.contains("ha noi") || name.contains("chi nhanh 02")?"BRANCH_02":name.contains("tru so")?"HQ":"BRANCH_01";
        }
        return switch(code.toUpperCase(Locale.ROOT)) {
            case "CN-01","BRANCH_01" -> new WorkBranch("BRANCH_01","Chi nhánh 01 - Lê Thánh Tôn");
            case "CN-02","BRANCH_02" -> new WorkBranch("BRANCH_02","Chi nhánh 02 - Tràng Tiền (HN)");
            case "CN-LAB","LAB" -> new WorkBranch("LAB","Trung tâm Lab Lens Tân Bình");
            case "HQ" -> new WorkBranch("HQ","Trụ sở Quản trị Toàn quốc");
            default -> throw new IllegalArgumentException("Chi nhánh làm việc không hợp lệ.");
        };
    }
}
