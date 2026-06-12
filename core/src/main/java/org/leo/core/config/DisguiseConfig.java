package org.leo.core.config;


import org.leo.core.entity.Disguise;
import org.leo.core.manager.DisguiseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;

@Configuration
public class DisguiseConfig {
    private static final Logger logger = LoggerFactory.getLogger(DisguiseConfig.class);
    private final LeoConfig leoConfig;

    public DisguiseConfig(LeoConfig leoConfig) {
        this.leoConfig = leoConfig;
    }

    @Bean
    public DisguiseManager disguiseManager() {
        DisguiseManager disguiseManager = DisguiseManager.getInstance();
        String vfsPath = leoConfig.getConfiguredVfsPath();
        if (vfsPath == null || vfsPath.isBlank()) {
            logger.warn("VFSPath未配置，使用默认路径 'root'");
            vfsPath = LeoConfig.DEFAULT_VFS_PATH;
        }
        String pluginEncryptKey = leoConfig.getConfiguredPluginEncryptKey();
        disguiseManager.init(vfsPath + "/disguise", pluginEncryptKey);
        disguiseManager.inStallDisguise(getInnerDisguise());
        disguiseManager.inStallDisguise(getInnerDefineBase64Disguise());
        return disguiseManager;
    }

    private Disguise getInnerDisguise(){
        Disguise innerDisguise=new Disguise();
        innerDisguise.setDisguiseId("inner_AESBin_1.0.0");
        innerDisguise.setDisguiseName("inner_AESBin");
        HashMap<String, String> headers = new HashMap<String, String>();
        headers.put("ContentType","text/plain;charset=utf-8");

        innerDisguise.setHeaders(headers);
        innerDisguise.setCreateTime(String.valueOf(System.currentTimeMillis()));
        innerDisguise.setCreateUserId("chao");
        innerDisguise.setVersion("1.0.0");
        innerDisguise.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        innerDisguise.setDescription("内置伪装，使用BASE64+AES加密");
        innerDisguise.setRemark("");
        innerDisguise.setEncodeBody("public byte[] encode(java.util.HashMap params) throws Exception {\n" +
                                    "        java.io.ByteArrayOutputStream byteArrayOutputStream = new java.io.ByteArrayOutputStream();\n" +
                                    "        java.util.zip.GZIPOutputStream gzipOutputStream = new java.util.zip.GZIPOutputStream(byteArrayOutputStream);\n" +
                                    "        java.io.ObjectOutputStream objectOutputStream = new java.io.ObjectOutputStream(gzipOutputStream);\n" +
                                    "        objectOutputStream.writeObject(params);\n" +
                                    "        objectOutputStream.close();\n" +
                                    "        byte[] compressedData = byteArrayOutputStream.toByteArray();\n" +
                                    "        byte[] keyBytes = java.util.Arrays.copyOf(\"54ikun\".getBytes(\"utf-8\"), 16);\n" +
                                    "        javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(keyBytes, \"AES\");\n" +
                                    "        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(\"AES/ECB/PKCS5Padding\");\n" +
                                    "        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey);\n" +
                                    "        byte[] encrypted = cipher.doFinal(compressedData);\n" +
                                    "\n" +
                                    "        byte[] base64Encoded;\n" +
                                    "        try {\n" +
                                    "            Class base64Class = Class.forName(\"java.util.Base64\");\n" +
                                    "            Object encoder = base64Class.getMethod(\"getEncoder\",new Class[]{}).invoke(null,new Object[]{});\n" +
                                    "            base64Encoded = (byte[]) encoder.getClass().getMethod(\"encode\", new Class[]{byte[].class}).invoke(encoder, new Object[]{encrypted});\n" +
                                    "        } catch (ClassNotFoundException e) {\n" +
                                    "            Object encoder = Class.forName(\"sun.misc.BASE64Encoder\").newInstance();\n" +
                                    "            String encodedStr = (String) encoder.getClass().getMethod(\"encode\", new Class[]{byte[].class}).invoke(encoder, new Object[]{encrypted});\n" +
                                    "            base64Encoded = encodedStr.getBytes(\"utf-8\");\n" +
                                    "        }\n" +
                                    "        return base64Encoded;\n" +
                                    "    }");
        innerDisguise.setDecodeBody("public java.util.HashMap decode(byte[] data) throws Exception {\n" +
                                    "        byte[] decoded;\n" +
                                    "        String base64Str = new String(data, \"utf-8\").replaceAll(\"\\\\s\", \"\");\n" +
                                    "\n" +
                                    "        try {\n" +
                                    "            Class base64Class = Class.forName(\"java.util.Base64\");\n" +
                                    "            Object decoder = base64Class.getMethod(\"getDecoder\",new Class[]{}).invoke(null,new Object[]{});\n" +
                                    "            decoded = (byte[]) decoder.getClass().getMethod(\"decode\", new Class[]{byte[].class}).invoke(decoder,new Object[]{ base64Str.getBytes(\"utf-8\")});\n" +
                                    "        } catch (ClassNotFoundException e) {\n" +
                                    "            Object decoder = Class.forName(\"sun.misc.BASE64Decoder\").newInstance();\n" +
                                    "            decoded = (byte[]) decoder.getClass().getMethod(\"decodeBuffer\", new Class[]{String.class}).invoke(decoder, new Object[]{base64Str});\n" +
                                    "            }\n" +
                                    "        byte[] keyBytes = java.util.Arrays.copyOf(\"54ikun\".getBytes(\"utf-8\"), 16);\n" +
                                    "        javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(keyBytes, \"AES\");\n" +
                                    "        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(\"AES/ECB/PKCS5Padding\");\n" +
                                    "        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey);\n" +
                                    "        byte[] decrypted = cipher.doFinal(decoded);\n" +
                                    "        java.io.ObjectInputStream ois = new java.io.ObjectInputStream(new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(decrypted)));\n" +
                                    "        java.util.HashMap result = (java.util.HashMap) ois.readObject();\n" +
                                    "        ois.close();\n" +
                                    "        return result;\n" +
                                    "    }");

        return innerDisguise;
    }
    private Disguise getInnerDefineBase64Disguise(){
        Disguise innerDisguise=new Disguise();
        innerDisguise.setDisguiseId("inner_define_Base64_1.0.0");
        innerDisguise.setDisguiseName("inner_define_Base64");
        HashMap<String, String> headers = new HashMap<String, String>();
        headers.put("ContentType","text/plain;charset=utf-8");

        innerDisguise.setHeaders(headers);
        innerDisguise.setCreateTime(String.valueOf(System.currentTimeMillis()));
        innerDisguise.setCreateUserId("chao");
        innerDisguise.setVersion("1.0.0");
        innerDisguise.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        innerDisguise.setDescription("内置伪装，使用自定义base64加密");
        innerDisguise.setRemark("");
        innerDisguise.setEncodeBody("public byte[] encode(java.util.HashMap params) throws Exception {\n" +
                                    "        java.io.ByteArrayOutputStream byteArrayOutputStream = new java.io.ByteArrayOutputStream();\n" +
                                    "        java.util.zip.GZIPOutputStream gzipOutputStream = new java.util.zip.GZIPOutputStream(byteArrayOutputStream);\n" +
                                    "        java.io.ObjectOutputStream objectOutputStream = new java.io.ObjectOutputStream(gzipOutputStream);\n" +
                                    "        objectOutputStream.writeObject(params);\n" +
                                    "        objectOutputStream.close();\n" +
                                    "\n" +
                                    "        byte[] compressedData = byteArrayOutputStream.toByteArray();\n" +
                                    "        if (compressedData.length == 0) return new byte[0];\n" +
                                    "\n" +
                                    "        String chars = \"OPQRSTUVWXYZabcdefghijklmnopqrstuvCDEFGHIJKLMwxyz0ABN123456789@#\";\n" +
                                    "        StringBuilder result = new StringBuilder();\n" +
                                    "        int i = 0;\n" +
                                    "\n" +
                                    "        while (i < compressedData.length) {\n" +
                                    "            int b1 = compressedData[i++] & 0xFF;\n" +
                                    "            int b2 = (i < compressedData.length) ? compressedData[i++] & 0xFF : 0;\n" +
                                    "            int b3 = (i < compressedData.length) ? compressedData[i++] & 0xFF : 0;\n" +
                                    "\n" +
                                    "            int value = (b1 << 16) | (b2 << 8) | b3;\n" +
                                    "\n" +
                                    "            result.append(chars.charAt((value >> 18) & 0x3F));\n" +
                                    "            result.append(chars.charAt((value >> 12) & 0x3F));\n" +
                                    "            result.append(chars.charAt((value >> 6) & 0x3F));\n" +
                                    "            result.append(chars.charAt(value & 0x3F));\n" +
                                    "        }\n" +
                                    "\n" +
                                    "        return result.toString().getBytes();\n" +
                                    "    }");
        innerDisguise.setDecodeBody("public java.util.HashMap decode(byte[] data) throws Exception {\n" +
                                    "        String encodedString = new String(data, \"UTF-8\");\n" +
                                    "        String chars = \"OPQRSTUVWXYZabcdefghijklmnopqrstuvCDEFGHIJKLMwxyz0ABN123456789@#\";\n" +
                                    "        int outputLength = (encodedString.length() * 3) / 4;\n" +
                                    "        byte[] compressedData = new byte[outputLength];\n" +
                                    "        int i = 0;\n" +
                                    "        int j = 0;\n" +
                                    "\n" +
                                    "        while (i < encodedString.length()) {\n" +
                                    "            int value = 0;\n" +
                                    "            for (int k = 0; k < 4 && i < encodedString.length(); k++) {\n" +
                                    "                char c = encodedString.charAt(i++);\n" +
                                    "                int index = chars.indexOf(c);\n" +
                                    "                value = (value << 6) | index;\n" +
                                    "            }\n" +
                                    "\n" +
                                    "            if (j < outputLength) compressedData[j++] = (byte) ((value >> 16) & 0xFF);\n" +
                                    "            if (j < outputLength) compressedData[j++] = (byte) ((value >> 8) & 0xFF);\n" +
                                    "            if (j < outputLength) compressedData[j++] = (byte) (value & 0xFF);\n" +
                                    "        }\n" +
                                    "\n" +
                                    "        java.io.ObjectInputStream ois = new java.io.ObjectInputStream(new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressedData)));\n" +
                                    "        java.util.HashMap result = (java.util.HashMap) ois.readObject();\n" +
                                    "        ois.close();\n" +
                                    "        return result;\n" +
                                    "    }");

        return innerDisguise;
    }

}
