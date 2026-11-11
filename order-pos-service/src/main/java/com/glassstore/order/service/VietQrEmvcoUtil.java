package com.glassstore.order.service;

import java.math.BigDecimal;

public class VietQrEmvcoUtil {

    private static String tlv(String tag, String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String len = String.format("%02d", value.length());
        return tag + len + value;
    }

    public static int crc16(byte[] bytes) {
        int crc = 0xFFFF;
        for (byte b : bytes) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc;
    }

    /**
     * Generates an official EMVCo Napas 247 VietQR payload string.
     */
    public static String generatePayload(String bankBin, String accountNumber, BigDecimal amount, String memo) {
        String sub00 = tlv("00", "A000000727");
        String sub01Org = tlv("00", bankBin) + tlv("01", accountNumber);
        String sub01 = tlv("01", sub01Org);
        String sub02 = tlv("02", "QRIBFTTA");
        String tag38 = tlv("38", sub00 + sub01 + sub02);

        String tag53 = tlv("53", "704");
        String tag54 = amount != null ? tlv("54", String.valueOf(amount.longValue())) : "";
        String tag58 = tlv("58", "VN");
        String tag62 = (memo != null && !memo.isBlank()) ? tlv("62", tlv("08", memo)) : "";

        String header = tlv("00", "01") + tlv("01", "12");
        String raw = header + tag38 + tag53 + tag54 + tag58 + tag62 + "6304";
        int checksum = crc16(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String crcHex = String.format("%04X", checksum);
        return raw + crcHex;
    }
}
