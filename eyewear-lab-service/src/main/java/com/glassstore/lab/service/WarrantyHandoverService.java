package com.glassstore.lab.service;

import com.glassstore.lab.client.CatalogLocationClient;
import com.glassstore.lab.entity.Warranty;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service @RequiredArgsConstructor
public class WarrantyHandoverService {
    private final CatalogLocationClient locations;
    public void validate(Warranty claim) {
        if (claim.getDeliveryMethod()==null) return; // Legacy claims and staff activation have no handover request.
        if (!java.util.Set.of("SHOWROOM","DOOR").contains(claim.getDeliveryMethod())) fail("Hình thức giao nhận không hợp lệ.");
        if (claim.getReceivingBranchCode()==null || claim.getReceivingBranchCode().isBlank()) fail("Vui lòng chọn showroom tiếp nhận.");
        var branch=locations.requireReceivingBranch(claim.getReceivingBranchCode().trim());
        if(branch==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Không xác minh được showroom tiếp nhận.");
        claim.setReceivingBranchCode(branch.code());claim.setReceivingBranchName(branch.name());claim.setReceivingBranchAddress(branch.address());
        String phone=claim.getCustomerPhone()==null?"":claim.getCustomerPhone().trim();
        if(!phone.matches("[+]?[0-9 ()-]{7,20}")) fail("Vui lòng nhập số điện thoại giao nhận hợp lệ.");
        claim.setCustomerPhone(phone);
        if ("DOOR".equals(claim.getDeliveryMethod())) {
            String address=claim.getCollectionAddress()==null?"":claim.getCollectionAddress().trim();
            if(address.length()<8 || address.length()>500) fail("Cần địa chỉ nhận và trả kính cụ thể (8–500 ký tự).");
            claim.setCollectionAddress(address);
        } else claim.setCollectionAddress(null);
    }
    private void fail(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
