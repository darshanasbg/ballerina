/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.ballerinalang.net.http;

import org.ballerinalang.bre.bvm.BLangVMErrors;
import org.ballerinalang.bre.bvm.CallableUnitCallback;
import org.ballerinalang.connector.api.BLangConnectorSPIUtil;
import org.ballerinalang.connector.api.BallerinaConnectorException;
import org.ballerinalang.connector.api.Executor;
import org.ballerinalang.connector.api.ParamDetail;
import org.ballerinalang.connector.api.Resource;
import org.ballerinalang.mime.util.Constants;
import org.ballerinalang.model.values.BMap;
import org.ballerinalang.model.values.BString;
import org.ballerinalang.model.values.BStruct;
import org.ballerinalang.model.values.BValue;
import org.ballerinalang.net.http.caching.RequestCacheControlStruct;
import org.ballerinalang.services.ErrorHandlerUtils;
import org.ballerinalang.util.observability.ObservabilityUtils;
import org.ballerinalang.util.observability.ObserverContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.contract.websocket.WebSocketBinaryMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketCloseMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketConnectorListener;
import org.wso2.transport.http.netty.contract.websocket.WebSocketControlMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketInitMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketTextMessage;
import org.wso2.transport.http.netty.contractimpl.websocket.message.DefaultWebSocketInitMessage;
import org.wso2.transport.http.netty.message.HTTPCarbonMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.ballerinalang.net.http.HttpConstants.PROTOCOL_PACKAGE_HTTP;
import static org.ballerinalang.net.http.HttpConstants.REQUEST_CACHE_CONTROL;
import static org.ballerinalang.net.http.HttpConstants.SERVICE_ENDPOINT_CONNECTION_INDEX;
import static org.ballerinalang.net.http.WebSocketConstants.WEBSOCKET_ENDPOINT;
import static org.ballerinalang.util.observability.ObservabilityConstants.PROPERTY_TRACE_PROPERTIES;
import static org.ballerinalang.util.observability.ObservabilityConstants.SERVER_CONNECTOR_WEBSOCKET;

/**
 * Ballerina Connector listener for WebSocket.
 *
 * @since 0.93
 */
public class WebSocketServerConnectorListener implements WebSocketConnectorListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketServerConnectorListener.class);
    private final WebSocketServicesRegistry servicesRegistry;
    private final WebSocketConnectionManager connectionManager;

    public WebSocketServerConnectorListener(WebSocketServicesRegistry servicesRegistry) {
        this.servicesRegistry = servicesRegistry;
        this.connectionManager = WebSocketConnectionManager.getInstance();
    }

    @Override
    public void onMessage(WebSocketInitMessage webSocketInitMessage) {
        HTTPCarbonMessage msg = new HTTPCarbonMessage(
                ((DefaultWebSocketInitMessage) webSocketInitMessage).getHttpRequest());
        Map<String, String> pathParams = new HashMap<>();
        WebSocketService wsService = WebSocketDispatcher.findService(servicesRegistry, pathParams, webSocketInitMessage,
                                                                     msg);
        BStruct serviceEndpoint = BLangConnectorSPIUtil.createBStruct(
                wsService.getResources()[0].getResourceInfo().getServiceInfo().getPackageInfo().getProgramFile(),
                PROTOCOL_PACKAGE_HTTP, WEBSOCKET_ENDPOINT);
        BStruct serverConnector = WebSocketUtil.createAndGetBStruct(wsService.getResources()[0]);
        serverConnector.addNativeData(WebSocketConstants.WEBSOCKET_MESSAGE, webSocketInitMessage);
        serverConnector.addNativeData(WebSocketConstants.WEBSOCKET_SERVICE, wsService);
        serviceEndpoint.setRefField(SERVICE_ENDPOINT_CONNECTION_INDEX, serverConnector);
        serviceEndpoint.setRefField(3, new BMap());
        serverConnector.addNativeData(WEBSOCKET_ENDPOINT, serviceEndpoint);
        Map<String, String> upgradeHeaders = webSocketInitMessage.getHeaders();
        BMap<String, BString> bUpgradeHeaders = new BMap<>();
        upgradeHeaders.forEach((key, value) -> bUpgradeHeaders.put(key, new BString(value)));
        serviceEndpoint.setRefField(4, bUpgradeHeaders);
        Resource onUpgradeResource = wsService.getResourceByName(WebSocketConstants.RESOURCE_NAME_ON_UPGRADE);
        if (onUpgradeResource != null) {
            Semaphore semaphore = new Semaphore(0);
            AtomicBoolean isResourceExeSuccessful = new AtomicBoolean(false);

            BStruct inRequest = BLangConnectorSPIUtil.createBStruct(
                    WebSocketUtil.getProgramFile(wsService.getResources()[0]), PROTOCOL_PACKAGE_HTTP,
                    HttpConstants.REQUEST);
            BStruct inRequestEntity = BLangConnectorSPIUtil.createBStruct(
                    WebSocketUtil.getProgramFile(wsService.getResources()[0]),
                    org.ballerinalang.mime.util.Constants.PROTOCOL_PACKAGE_MIME, Constants.ENTITY);

            BStruct mediaType = BLangConnectorSPIUtil.createBStruct(
                    WebSocketUtil.getProgramFile(wsService.getResources()[0]),
                    org.ballerinalang.mime.util.Constants.PROTOCOL_PACKAGE_MIME, Constants.MEDIA_TYPE);

            BStruct cacheControlStruct = BLangConnectorSPIUtil.createBStruct(
                    WebSocketUtil.getProgramFile(wsService.getResources()[0]),
                    PROTOCOL_PACKAGE_HTTP, REQUEST_CACHE_CONTROL);
            RequestCacheControlStruct requestCacheControl = new RequestCacheControlStruct(cacheControlStruct);

            HttpUtil.populateInboundRequest(inRequest, inRequestEntity, mediaType, msg, requestCacheControl);

            List<ParamDetail> paramDetails = onUpgradeResource.getParamDetails();
            BValue[] bValues = new BValue[paramDetails.size()];
            bValues[0] = serviceEndpoint;
            bValues[1] = inRequest;
            WebSocketDispatcher.setPathParams(bValues, paramDetails, pathParams, 2);

            ObserverContext ctx = ObservabilityUtils.startServerObservation(SERVER_CONNECTOR_WEBSOCKET,
                    onUpgradeResource.getServiceName(), onUpgradeResource.getName(), null);
            Map<String, String> httpHeaders = new HashMap<>();
            upgradeHeaders.entrySet().forEach(entry -> httpHeaders.put(entry.getKey(), entry.getValue()));
            ctx.addProperty(PROPERTY_TRACE_PROPERTIES, httpHeaders);

            Executor.submit(onUpgradeResource, new CallableUnitCallback() {
                @Override
                public void notifySuccess() {
                    isResourceExeSuccessful.set(true);
                    semaphore.release();
                }

                @Override
                public void notifyFailure(BStruct error) {
                    ErrorHandlerUtils.printError("error: " + BLangVMErrors.getPrintableStackTrace(error));
                    semaphore.release();
                }
            }, null, ctx, bValues);

            try {
                semaphore.acquire();
                if (isResourceExeSuccessful.get() && !webSocketInitMessage.isCancelled() &&
                        !webSocketInitMessage.isHandshakeStarted()) {
                    WebSocketUtil.handleHandshake(wsService, null, serverConnector);
                }
            } catch (InterruptedException e) {
                throw new BallerinaConnectorException("Connection interrupted during handshake");
            }

        } else {
            WebSocketUtil.handleHandshake(wsService, null, serverConnector);
        }
    }

    @Override
    public void onMessage(WebSocketTextMessage webSocketTextMessage) {
        WebSocketDispatcher.dispatchTextMessage(
                connectionManager.getConnectionInfo(webSocketTextMessage.getSessionID()), webSocketTextMessage);
    }

    @Override
    public void onMessage(WebSocketBinaryMessage webSocketBinaryMessage) {
        WebSocketDispatcher.dispatchBinaryMessage(
                connectionManager.getConnectionInfo(webSocketBinaryMessage.getSessionID()), webSocketBinaryMessage);
    }

    @Override
    public void onMessage(WebSocketControlMessage webSocketControlMessage) {
        WebSocketDispatcher.dispatchControlMessage(
                connectionManager.getConnectionInfo(webSocketControlMessage.getSessionID()), webSocketControlMessage);
    }

    @Override
    public void onMessage(WebSocketCloseMessage webSocketCloseMessage) {
        WebSocketDispatcher.dispatchCloseMessage(
                connectionManager.getConnectionInfo(webSocketCloseMessage.getSessionID()), webSocketCloseMessage);
    }

    @Override
    public void onError(Throwable throwable) {
        log.error("Unexpected error occurred in WebSocket transport", throwable);
    }

    @Override
    public void onIdleTimeout(WebSocketControlMessage controlMessage) {
        WebSocketDispatcher.dispatchIdleTimeout(connectionManager.getConnectionInfo(controlMessage.getSessionID()),
                                                controlMessage);
    }

}


