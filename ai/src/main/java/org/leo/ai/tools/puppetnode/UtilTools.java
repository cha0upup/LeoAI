package org.leo.ai.tools.puppetnode;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 加解密工具（精简版）
 * <p>
 * 对外仅暴露 2 个 @Tool 方法：
 * <ul>
 *   <li>{@link #symmetricCrypto} — 对称加解密统一入口（AES/DES/3DES）</li>
 *   <li>{@link #rsaCrypto} — RSA 非对称操作统一入口（加密/解密/签名/验签）</li>
 * </ul>
 */
@Component
public class UtilTools {

    // ══════════════════════════════════════════════════════════════════════════════
    //  对称加解密（统一入口）
    // ══════════════════════════════════════════════════════════════════════════════

    @Tool("对称加解密统一入口。支持 AES、DES、3DES（DESede）。\n"
            + "• operation: encrypt|decrypt\n"
            + "• algorithm: AES|DES|DESede，默认 AES\n"
            + "• mode: ECB|CBC|CFB|OFB|CTR|GCM，默认 ECB。非 ECB 需提供 iv\n"
            + "• encoding: base64|hex，默认 base64\n"
            + "• AES key 长度 16/24/32 字节，DES 8 字节，3DES 16/24 字节\n"
            + "常见场景：解密 Spring Boot 配置中的加密凭据、解密 Druid/Jasypt 密码等。")
    public Map<String, Object> symmetricCrypto(
            @P("操作类型：encrypt 或 decrypt") String operation,
            @P("输入文本（加密时为明文，解密时为密文）") String input,
            @P("算法：AES|DES|DESede，默认 AES") String algorithm,
            @P("密钥（UTF-8 字符串）") String key,
            @P("模式：ECB|CBC|CFB|OFB|CTR|GCM，默认 ECB") String mode,
            @P("初始化向量，ECB 模式可为空") String iv,
            @P("编码：base64|hex，默认 base64") String encoding) throws Exception {

        String alg = normalizeAlgorithm(algorithm);
        String transformation = buildTransformation(alg, mode);
        boolean encrypt = !"decrypt".equalsIgnoreCase(operation != null ? operation.trim() : "encrypt");

        Cipher cipher = initSymmetricCipher(
                encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                alg, key, transformation, iv);

        Map<String, Object> result = new HashMap<>();
        result.put("algorithm", alg);
        result.put("transformation", transformation);
        result.put("encoding", normalizeEncoding(encoding));
        result.put("operation", encrypt ? "encrypt" : "decrypt");

        if (encrypt) {
            byte[] encrypted = cipher.doFinal(input.getBytes(StandardCharsets.UTF_8));
            result.put("cipherText", encodeCipherBytes(encrypted, encoding));
        } else {
            byte[] decoded = decodeCipherBytes(input, encoding);
            byte[] decrypted = cipher.doFinal(decoded);
            result.put("plainText", new String(decrypted, StandardCharsets.UTF_8));
        }
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════════
    //  RSA 非对称操作（统一入口）
    // ══════════════════════════════════════════════════════════════════════════════

    @Tool("RSA 非对称操作统一入口。\n"
            + "• operation: encrypt|decrypt|sign|verify\n"
            + "• encrypt: 公钥加密，需 publicKey\n"
            + "• decrypt: 私钥解密，需 privateKey\n"
            + "• sign: 私钥签名，需 privateKey，signatureAlgorithm 默认 SHA256withRSA\n"
            + "• verify: 公钥验签，需 publicKey + signature，返回 valid: true/false\n"
            + "• key 支持 Base64 或 PEM 格式\n"
            + "• transformation 默认 RSA/ECB/PKCS1Padding\n"
            + "• encoding: base64|hex，默认 base64")
    public Map<String, Object> rsaCrypto(
            @P("操作类型：encrypt|decrypt|sign|verify") String operation,
            @P("输入文本（加密/签名时为明文，解密时为密文，验签时为原文）") String input,
            @P("RSA 公钥（Base64 或 PEM），encrypt/verify 时必填") String publicKey,
            @P("RSA 私钥（Base64 或 PEM PKCS8），decrypt/sign 时必填") String privateKey,
            @P("签名值（verify 时必填）") String signature,
            @P("transformation，默认 RSA/ECB/PKCS1Padding") String transformation,
            @P("签名算法，默认 SHA256withRSA（sign/verify 时使用）") String signatureAlgorithm,
            @P("编码：base64|hex，默认 base64") String encoding) throws Exception {

        String op = (operation != null ? operation.trim().toLowerCase() : "encrypt");
        Map<String, Object> result = new HashMap<>();
        result.put("operation", op);
        result.put("encoding", normalizeEncoding(encoding));

        switch (op) {
            case "encrypt" -> {
                Cipher cipher = Cipher.getInstance(normalizeRsaTransformation(transformation));
                cipher.init(Cipher.ENCRYPT_MODE, parseRsaPublicKey(publicKey));
                byte[] encrypted = cipher.doFinal(input.getBytes(StandardCharsets.UTF_8));
                result.put("cipherText", encodeCipherBytes(encrypted, encoding));
            }
            case "decrypt" -> {
                Cipher cipher = Cipher.getInstance(normalizeRsaTransformation(transformation));
                cipher.init(Cipher.DECRYPT_MODE, parseRsaPrivateKey(privateKey));
                byte[] decrypted = cipher.doFinal(decodeCipherBytes(input, encoding));
                result.put("plainText", new String(decrypted, StandardCharsets.UTF_8));
            }
            case "sign" -> {
                Signature sig = Signature.getInstance(normalizeRsaSignatureAlgorithm(signatureAlgorithm));
                sig.initSign(parseRsaPrivateKey(privateKey));
                sig.update(input.getBytes(StandardCharsets.UTF_8));
                result.put("signature", encodeCipherBytes(sig.sign(), encoding));
                result.put("signatureAlgorithm", normalizeRsaSignatureAlgorithm(signatureAlgorithm));
            }
            case "verify" -> {
                Signature sig = Signature.getInstance(normalizeRsaSignatureAlgorithm(signatureAlgorithm));
                sig.initVerify(parseRsaPublicKey(publicKey));
                sig.update(input.getBytes(StandardCharsets.UTF_8));
                boolean valid = sig.verify(decodeCipherBytes(signature, encoding));
                result.put("valid", valid);
            }
            default -> throw new IllegalArgumentException("不支持的操作: " + op + "，可选: encrypt|decrypt|sign|verify");
        }
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════════
    //  私有辅助方法
    // ══════════════════════════════════════════════════════════════════════════════

    private String normalizeAlgorithm(String algorithm) {
        if (algorithm == null || algorithm.isBlank()) {
            return "AES";
        }
        String alg = algorithm.trim().toUpperCase();
        switch (alg) {
            case "3DES":
            case "TRIPLEDES":
            case "DESEDE":
                return "DESede";
            case "DES":
                return "DES";
            default:
                return "AES";
        }
    }

    private String buildTransformation(String algorithm, String mode) {
        String normalizedMode = (mode == null || mode.isBlank()) ? "ECB" : mode.trim().toUpperCase();
        return algorithm + "/" + normalizedMode + "/PKCS5Padding";
    }

    private Cipher initSymmetricCipher(int cipherMode, String algorithm, String key, String transformation, String iv) throws Exception {
        validateSymmetricKey(algorithm, key);
        SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), algorithm);
        Cipher cipher;
        if (requiresIv(transformation)) {
            validateIv(iv, ivLengthForAlgorithm(algorithm));
            cipher = Cipher.getInstance(transformation);
            cipher.init(cipherMode, secretKeySpec, new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
        } else {
            cipher = Cipher.getInstance(transformation);
            cipher.init(cipherMode, secretKeySpec);
        }
        return cipher;
    }

    private boolean requiresIv(String transformation) {
        String upper = transformation.toUpperCase();
        return upper.contains("/CBC/") || upper.contains("/CFB/")
                || upper.contains("/OFB/") || upper.contains("/CTR/")
                || upper.contains("/GCM/");
    }

    private void validateSymmetricKey(String algorithm, String key) {
        if (key == null) {
            throw new IllegalArgumentException(algorithm + " key 不能为空");
        }
        int length = key.getBytes(StandardCharsets.UTF_8).length;
        switch (algorithm) {
            case "AES":
                if (length != 16 && length != 24 && length != 32) {
                    throw new IllegalArgumentException("AES key 长度必须是 16、24 或 32 字节，当前 " + length);
                }
                break;
            case "DES":
                if (length != 8) {
                    throw new IllegalArgumentException("DES key 长度必须是 8 字节，当前 " + length);
                }
                break;
            case "DESede":
                if (length != 16 && length != 24) {
                    throw new IllegalArgumentException("3DES key 长度必须是 16 或 24 字节，当前 " + length);
                }
                break;
        }
    }

    private void validateIv(String iv, int expectedLength) {
        if (iv == null || iv.isBlank()) {
            throw new IllegalArgumentException("当前模式需要 iv，不能为空");
        }
        int length = iv.getBytes(StandardCharsets.UTF_8).length;
        if (length != expectedLength) {
            throw new IllegalArgumentException("iv 长度必须是 " + expectedLength + " 字节，当前 " + length);
        }
    }

    private int ivLengthForAlgorithm(String algorithm) {
        if ("DES".equals(algorithm) || "DESede".equals(algorithm)) {
            return 8;
        }
        return 16;
    }

    private String encodeCipherBytes(byte[] data, String encoding) {
        if ("hex".equalsIgnoreCase(normalizeEncoding(encoding))) {
            StringBuilder sb = new StringBuilder(data.length * 2);
            for (byte b : data) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
        return Base64.getEncoder().encodeToString(data);
    }

    private byte[] decodeCipherBytes(String cipherText, String encoding) {
        if ("hex".equalsIgnoreCase(normalizeEncoding(encoding))) {
            return hexToBytes(cipherText);
        }
        return Base64.getDecoder().decode(cipherText);
    }

    private String normalizeEncoding(String encoding) {
        if (encoding == null || encoding.isBlank()) {
            return "base64";
        }
        return encoding.trim().toLowerCase();
    }

    private byte[] hexToBytes(String hex) {
        if (hex == null || (hex.length() % 2 != 0)) {
            throw new IllegalArgumentException("十六进制密文格式非法");
        }
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return data;
    }

    private String normalizeRsaTransformation(String transformation) {
        if (transformation == null || transformation.isBlank()) {
            return "RSA/ECB/PKCS1Padding";
        }
        return transformation.trim();
    }

    private String normalizeRsaSignatureAlgorithm(String algorithm) {
        if (algorithm == null || algorithm.isBlank()) {
            return "SHA256withRSA";
        }
        return algorithm.trim();
    }

    private PublicKey parseRsaPublicKey(String publicKeyText) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(cleanPem(publicKeyText));
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePublic(keySpec);
    }

    private PrivateKey parseRsaPrivateKey(String privateKeyText) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(cleanPem(privateKeyText));
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }

    private String cleanPem(String keyText) {
        if (keyText == null || keyText.isBlank()) {
            throw new IllegalArgumentException("RSA 密钥不能为空");
        }
        return keyText
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
    }
}
