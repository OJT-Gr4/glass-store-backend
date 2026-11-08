package com.glassstore.order.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.entity.*;
import com.glassstore.order.repository.CheckoutRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CheckoutIdempotencyService {
    private static final int MAX_KEY_LENGTH = 100;
    private static final Set<String> SERVER_OWNED_FIELDS = Set.of("productName", "staffName");

    private final CheckoutRequestRepository requests;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.MANDATORY)
    public CheckoutRequestRecord begin(String key, String hash, String owner) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH || !key.equals(key.trim())) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Gửi Idempotency-Key hợp lệ, tối đa 100 ký tự.");
        }
        requests.claim(key, hash, owner);
        CheckoutRequestRecord record = requests.lockByKey(key).orElseThrow();
        if (!owner.equals(record.getOwner()) || !hash.equals(record.getRequestHash())) {
            throw ApiException.of(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key đã dùng với nội dung hoặc người tạo khác. Kiểm tra đơn cũ trước khi tạo yêu cầu mới.");
        }
        return record;
    }

    public String fingerprint(OrderDtos.CheckoutRequest request) {
        JsonNode normalized = canonical(json.valueToTree(request), false);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private JsonNode canonical(JsonNode node, boolean sortArray) {
        if (node.isNumber()) {
            return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
        }
        if (node.isObject()) {
            return canonicalObject(node);
        }
        if (node.isArray()) {
            return canonicalArray(node, sortArray);
        }
        return node;
    }

    private ObjectNode canonicalObject(JsonNode node) {
        ObjectNode result = json.createObjectNode();
        var keys = new TreeSet<String>();
        node.fieldNames().forEachRemaining(keys::add);
        keys.stream()
                .filter(key -> !SERVER_OWNED_FIELDS.contains(key))
                .forEach(key -> result.set(key, canonical(node.get(key), "items".equals(key))));
        return result;
    }

    private ArrayNode canonicalArray(JsonNode node, boolean sortArray) {
        var values = new ArrayList<JsonNode>();
        node.forEach(value -> values.add(canonical(value, false)));
        if (sortArray) {
            values.sort(Comparator.comparing(JsonNode::toString));
        }
        ArrayNode result = json.createArrayNode();
        values.forEach(result::add);
        return result;
    }

    public String fingerprint(Order order) {
        return fingerprint(OrderDtos.CheckoutRequest.builder().customerId(order.getCustomerId())
                .customerName(order.getCustomerName()).customerPhone(order.getCustomerPhone())
                .shippingAddress(order.getShippingAddress()).channel(order.getChannel()==null?null:order.getChannel().name())
                .orderType(order.getOrderType()==null?null:order.getOrderType().name())
                .paymentMethod(order.getPaymentMethod()==null?null:order.getPaymentMethod().name())
                .branchCode(order.getBranchCode()).branchName(order.getBranchName()).staffName(order.getStaffName())
                .prescriptionId(order.getPrescriptionId()).pointsToUse(order.getPointsToUse()).voucherCode(order.getVoucherCode())
                .cashReceived(order.getCashReceived()).manualPaymentConfirmed(order.isManualPaymentConfirmed())
                .paymentReference(order.getPaymentReference()).notes(order.getNotes()).posSessionId(order.getPosSessionId())
                .items(order.getItems()==null?null:order.getItems().stream().map(item -> OrderDtos.CheckoutLine.builder()
                        .sku(item.getSkuId()).quantity(item.getQuantity()).productName(item.getProductName())
                        .prescriptionId(item.getPrescriptionId()).expectedUnitPrice(item.getExpectedUnitPrice()!=null?item.getExpectedUnitPrice():item.getUnitPrice()).build()).toList())
                .build());
    }
}
