package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SqlService extends ComponentService {

    public SqlService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> execSql(String driverClassName, String jdbcUrl, String user, String password, String sqlScript) throws Exception {
        HashMap<String, Object> payload = new HashMap<String, Object>();
        payload.put("sql", sqlScript);
        payload.put("url", jdbcUrl);
        payload.put("user", user);
        payload.put("password", password);
        payload.put("driver", driverClassName);
        return invokeComponent("DatabaseComponent", payload);
    }
}
