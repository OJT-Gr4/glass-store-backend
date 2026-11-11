package com.glassstore.catalog.service;

import com.glassstore.catalog.dto.InventoryReconciliationDTO;
import com.glassstore.catalog.dto.InventoryReconciliationDTO.BalanceVariance;
import com.glassstore.catalog.dto.InventoryReconciliationDTO.TransferVariance;
import com.glassstore.catalog.entity.*;
import com.glassstore.catalog.repository.InventoryBalanceRepository;
import com.glassstore.catalog.repository.InventoryMovementRepository;
import com.glassstore.catalog.repository.ProductVariantRepository;
import com.glassstore.catalog.repository.StockTransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class InventoryReconciliationService {

    private final ProductVariantRepository variantRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final StockTransferRepository transferRepository;
    private final InventoryMovementRepository movementRepository;

    @Transactional(readOnly = true)
    public InventoryReconciliationDTO reconcile() {
        List<BalanceVariance> balanceVariances = computeBalanceVariances();
        List<TransferVariance> transferVariances = computeTransferVariances();

        return InventoryReconciliationDTO.builder()
                .checkedAt(LocalDateTime.now())
                .completeMovementLedger(false)
                .coverageNote("Đối soát chỉ đọc giữa tổng tồn variant, tổng tồn theo địa điểm và nhật ký biến động điều chuyển; tồn đầu kỳ trước khi bật sổ biến động không được hồi tố.")
                .balanceVariances(balanceVariances)
                .transferVariances(transferVariances)
                .build();
    }

    private List<BalanceVariance> computeBalanceVariances() {
        Map<String, Integer> locationTotals = new HashMap<>();
        for (InventoryBalance balance : balanceRepository.findAllWithDetails()) {
            String sku = balance.getVariant().getSku();
            int qty = balance.getQuantity() != null ? balance.getQuantity() : 0;
            locationTotals.merge(sku, qty, Integer::sum);
        }

        List<BalanceVariance> variances = new ArrayList<>();
        for (ProductVariant variant : variantRepository.findAllWithProduct()) {
            String sku = variant.getSku();
            int variantStock = variant.getStockQuantity() != null ? variant.getStockQuantity() : 0;
            int locationTotal = locationTotals.getOrDefault(sku, 0);
            int difference = variantStock - locationTotal;
            if (difference != 0) {
                variances.add(BalanceVariance.builder()
                        .sku(sku)
                        .productName(variant.getProduct() != null ? variant.getProduct().getName() : sku)
                        .variantStockQuantity(variantStock)
                        .locationQuantity(locationTotal)
                        .difference(difference)
                        .build());
            }
        }
        return variances;
    }

    private List<TransferVariance> computeTransferVariances() {
        Map<TransferSkuKey, MovementCount> actualByKey = new LinkedHashMap<>();
        for (InventoryMovement movement : movementRepository.findAllByReferenceType("STOCK_TRANSFER")) {
            if (movement.getReferenceId() == null || movement.getSku() == null) {
                continue;
            }
            TransferSkuKey key = new TransferSkuKey(movement.getReferenceId(), movement.getSku());
            MovementCount counts = actualByKey.computeIfAbsent(key, ignored -> new MovementCount());
            int qty = movement.getQuantity() != null ? movement.getQuantity() : 0;
            if ("TRANSFER_OUT".equals(movement.getMovementType())) {
                counts.outQty += qty;
            } else if ("TRANSFER_IN".equals(movement.getMovementType())) {
                counts.inQty += qty;
            }
        }

        List<TransferVariance> variances = new ArrayList<>();
        Set<TransferSkuKey> checkedKeys = new HashSet<>();
        Map<String, StockTransfer> transferByCode = new HashMap<>();

        for (StockTransfer transfer : transferRepository.findAllByOrderByCreatedAtDesc()) {
            String code = transfer.getTransferCode();
            transferByCode.put(code, transfer);
            for (StockTransferItem item : transfer.getItems()) {
                String sku = item.getSku();
                TransferSkuKey key = new TransferSkuKey(code, sku);
                checkedKeys.add(key);

                int qty = item.getQuantity() != null ? item.getQuantity() : 0;
                int expectedOut = (transfer.getStatus() == StockTransferStatus.IN_TRANSIT
                        || transfer.getStatus() == StockTransferStatus.RECEIVED) ? qty : 0;
                int expectedIn = transfer.getStatus() == StockTransferStatus.RECEIVED ? qty : 0;

                MovementCount actual = actualByKey.getOrDefault(key, MovementCount.EMPTY);
                if (expectedOut != actual.outQty || expectedIn != actual.inQty) {
                    variances.add(TransferVariance.builder()
                            .transferCode(code)
                            .status(transfer.getStatus().name())
                            .sku(sku)
                            .expectedOutQuantity(expectedOut)
                            .actualOutQuantity(actual.outQty)
                            .expectedInQuantity(expectedIn)
                            .actualInQuantity(actual.inQty)
                            .issue("Biến động thực tế không khớp trạng thái phiếu điều chuyển")
                            .build());
                }
            }
        }

        for (Map.Entry<TransferSkuKey, MovementCount> entry : actualByKey.entrySet()) {
            TransferSkuKey key = entry.getKey();
            if (checkedKeys.contains(key)) {
                continue;
            }
            StockTransfer transfer = transferByCode.get(key.transferCode());
            MovementCount actual = entry.getValue();
            variances.add(TransferVariance.builder()
                    .transferCode(key.transferCode())
                    .status(transfer != null ? transfer.getStatus().name() : "UNKNOWN")
                    .sku(key.sku())
                    .expectedOutQuantity(0)
                    .actualOutQuantity(actual.outQty)
                    .expectedInQuantity(0)
                    .actualInQuantity(actual.inQty)
                    .issue("Phát sinh biến động điều chuyển ngoài danh sách phiếu")
                    .build());
        }

        return variances;
    }

    private record TransferSkuKey(String transferCode, String sku) {
    }

    private static final class MovementCount {
        static final MovementCount EMPTY = new MovementCount();
        int outQty;
        int inQty;
    }
}
