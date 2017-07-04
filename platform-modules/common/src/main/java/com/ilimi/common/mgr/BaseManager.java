package com.ilimi.common.mgr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.lang3.StringUtils;

import com.ilimi.common.dto.Property;
import com.ilimi.common.dto.Request;
import com.ilimi.common.dto.Response;
import com.ilimi.common.dto.ResponseParams;
import com.ilimi.common.dto.ResponseParams.StatusType;
import com.ilimi.common.enums.TaxonomyErrorCodes;
import com.ilimi.common.exception.ResponseCode;
import com.ilimi.common.exception.ServerException;
import com.ilimi.common.router.RequestRouterPool;
import com.ilimi.common.util.PlatformLogger;
import com.ilimi.graph.common.enums.GraphHeaderParams;
import com.ilimi.graph.dac.model.Node;

import akka.actor.ActorRef;
import akka.dispatch.Futures;
import akka.pattern.Patterns;
import scala.concurrent.Await;
import scala.concurrent.Future;

public abstract class BaseManager {

    protected static final String PARAM_SUBJECT = "subject";
    protected static final String PARAM_FIELDS = "fields";
    protected static final String PARAM_LIMIT = "limit";
    protected static final String PARAM_UID = "uid";
    protected static final String PARAM_STATUS = "status";
    protected static final String PARAM_TAGS = "tags";
    protected static final String PARAM_TTL = "ttl";

    protected static final int DEFAULT_TTL = 24;
    protected static final int DEFAULT_LIMIT = 50;

    protected void setMetadataFields(Node node, String[] fields) {
        if (null != fields && fields.length > 0) {
            if (null != node.getMetadata() && !node.getMetadata().isEmpty()) {
                Map<String, Object> metadata = new HashMap<String, Object>();
                List<String> fieldList = Arrays.asList(fields);
                for (Entry<String, Object> entry : node.getMetadata().entrySet()) {
                    if (fieldList.contains(entry.getKey())) {
                        metadata.put(entry.getKey(), entry.getValue());
                    }
                }
                node.setMetadata(metadata);
            }
        }
    }
    
    @SuppressWarnings("rawtypes")
	protected Response getResponse(Request request, PlatformLogger logger) {
        ActorRef router = RequestRouterPool.getRequestRouter();
        try {
            Future<Object> future = Patterns.ask(router, request, RequestRouterPool.REQ_TIMEOUT);
            Object obj = Await.result(future, RequestRouterPool.WAIT_TIMEOUT.duration());
            if (obj instanceof Response) {
            	Response response = (Response) obj;
            	logger.log("Response Params: " + response.getParams() + " | Code: " + response.getResponseCode() , " | Result: " + response.getResult().keySet());
                return response;
            } else {
                return ERROR(TaxonomyErrorCodes.SYSTEM_ERROR.name(), "System Error", ResponseCode.SERVER_ERROR);
            }
        } catch (Exception e) {
            logger.log("Exception", e.getMessage(), e);
            throw new ServerException(TaxonomyErrorCodes.SYSTEM_ERROR.name(), "System Error", e);
        }
    }
    
    @SuppressWarnings("rawtypes")
	public void makeAsyncRequest(Request request, PlatformLogger logger) {
        ActorRef router = RequestRouterPool.getRequestRouter();
        try {
            router.tell(request, router);
        } catch (Exception e) {
            logger.log("Exception",e.getMessage(), e);
            throw new ServerException(TaxonomyErrorCodes.SYSTEM_ERROR.name(), "System Error", e);
        }
    }

    @SuppressWarnings("rawtypes")
	protected Response getResponse(List<Request> requests, PlatformLogger logger, String paramName, String returnParam) {
        if (null != requests && !requests.isEmpty()) {
            ActorRef router = RequestRouterPool.getRequestRouter();
            try {
                List<Future<Object>> futures = new ArrayList<Future<Object>>();
                for (Request request : requests) {
                    Future<Object> future = Patterns.ask(router, request, RequestRouterPool.REQ_TIMEOUT);
                    futures.add(future);
                }
                Future<Iterable<Object>> objects = Futures.sequence(futures,
                        RequestRouterPool.getActorSystem().dispatcher());
                Iterable<Object> responses = Await.result(objects, RequestRouterPool.WAIT_TIMEOUT.duration());
                if (null != responses) {
                    List<Object> list = new ArrayList<Object>();
                    Response response = new Response();
                    for (Object obj : responses) {
                        if (obj instanceof Response) {
                            Response res = (Response) obj;
                            if (!checkError(res)) {
                                Object vo = res.get(paramName);
                                response = copyResponse(response, res);
                                if (null != vo) {
                                    list.add(vo);
                                }
                            } else {
                                return res;
                            }
                        } else {
                            return ERROR(TaxonomyErrorCodes.SYSTEM_ERROR.name(), "System Error",
                                    ResponseCode.SERVER_ERROR);
                        }
                    }
                    response.put(returnParam, list);
                    return response;
                } else {
                    return ERROR(TaxonomyErrorCodes.SYSTEM_ERROR.name(), "System Error", ResponseCode.SERVER_ERROR);
                }
            } catch (Exception e) {
                logger.log("ERROR! Something went wrong", e.getMessage(), e);
                throw new ServerException(TaxonomyErrorCodes.SYSTEM_ERROR.name(), "System Error", e);
            }
        } else {
            return ERROR(TaxonomyErrorCodes.SYSTEM_ERROR.name(), "System Error", ResponseCode.SERVER_ERROR);
        }
    }

    protected Request setContext(Request request, String graphId, String manager, String operation) {
        request.getContext().put(GraphHeaderParams.graph_id.name(), graphId);
        request.setManagerName(manager);
        request.setOperation(operation);
        return request;
    }

    protected Request getRequest(String graphId, String manager, String operation) {
        Request request = new Request();
        return setContext(request, graphId, manager, operation);
    }

    protected Request getRequest(String graphId, String manager, String operation, String paramName, Object vo) {
        Request request = getRequest(graphId, manager, operation);
        request.put(paramName, vo);
        return request;
    }

    protected Response ERROR(String errorCode, String errorMessage, ResponseCode responseCode) {
        Response response = new Response();
        response.setParams(getErrorStatus(errorCode, errorMessage));
        response.setResponseCode(responseCode);
        return response;
    }

    protected Response ERROR(String errorCode, String errorMessage, ResponseCode code, String responseIdentifier,
            Object vo) {
        Response response = new Response();
        response.put(responseIdentifier, vo);
        response.setParams(getErrorStatus(errorCode, errorMessage));
        response.setResponseCode(code);
        return response;
    }

    protected Response OK() {
        Response response = new Response();
        response.setParams(getSucessStatus());
        return response;
    }

    protected Response OK(String responseIdentifier, Object vo) {
        Response response = new Response();
        response.put(responseIdentifier, vo);
        response.setParams(getSucessStatus());
        return response;
    }

    protected boolean checkError(Response response) {
        ResponseParams params = response.getParams();
        if (null != params) {
            if (StringUtils.equals(StatusType.failed.name(), params.getStatus())) {
                return true;
            }
        }
        return false;
    }

    protected String getErrorMessage(Response response) {
        ResponseParams params = response.getParams();
        if (null != params) {
            return params.getErrmsg();
        }
        return null;
    }

    protected Response copyResponse(Response res) {
        Response response = new Response();
        response.setResponseCode(res.getResponseCode());
        response.setParams(res.getParams());
        return response;
    }

    protected Response copyResponse(Response to, Response from) {
        to.setResponseCode(from.getResponseCode());
        to.setParams(from.getParams());
        return to;
    }

    protected ResponseParams getErrorStatus(String errorCode, String errorMessage) {
        ResponseParams params = new ResponseParams();
        params.setErr(errorCode);
        params.setStatus(StatusType.failed.name());
        params.setErrmsg(errorMessage);
        return params;
    }

    protected ResponseParams getSucessStatus() {
        ResponseParams params = new ResponseParams();
        params.setErr("0");
        params.setStatus(StatusType.successful.name());
        params.setErrmsg("Operation successful");
        return params;
    }

    protected boolean validateRequired(Object... objects) {
        boolean valid = true;
        for (Object baseValueObject : objects) {
            if (null == baseValueObject) {
                valid = false;
                break;
            }
            if (baseValueObject instanceof String) {
                if (StringUtils.isBlank(((String) baseValueObject))) {
                    valid = false;
                    break;
                }
            }
            if (baseValueObject instanceof List<?>) {
                List<?> list = (List<?>) baseValueObject;
                if (null == list || list.isEmpty()) {
                    valid = false;
                    break;
                }
            }
            if (baseValueObject instanceof Map<?, ?>) {
                Map<?, ?> map = (Map<?, ?>) baseValueObject;
                if (null == map || map.isEmpty()) {
                    valid = false;
                    break;
                }
            }
            if (baseValueObject instanceof Property) {
                Property property = (Property) baseValueObject;
                if (StringUtils.isBlank(property.getPropertyName())
                        || (null == property.getPropertyValue() && null == property.getDateValue())) {
                    valid = false;
                    break;
                }
            }
        }
        return valid;
    }

}
