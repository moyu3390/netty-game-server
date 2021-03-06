package com.lzh.game.socket.dispatcher.action;

import com.lzh.game.common.OptionListener;
import com.lzh.game.common.bean.HandlerMethod;
import com.lzh.game.socket.annotation.ControllerAdvice;
import com.lzh.game.socket.dispatcher.ServerExchange;
import com.lzh.game.socket.dispatcher.action.convent.InvokeMethodArgumentValues;
import com.lzh.game.socket.dispatcher.exception.NotDefinedResponseProtocolException;
import com.lzh.game.socket.dispatcher.exception.NotFondProtocolException;
import com.lzh.game.socket.dispatcher.mapping.RequestMethodMapping;
import com.lzh.game.socket.exchange.*;
import com.lzh.game.socket.exchange.request.GameRequest;
import com.lzh.game.socket.exchange.response.GameResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class ActionCenterHandler implements ActionCenter, ApplicationContextAware {

    private ActionMethodSupport support;
    /**
     * To resolve the method invoke exception event
     */
    private ExceptionHandlerMethodResolver methodResolver;
    /**
     *
     */
    private Object adviceInvokeBean;
    /**
     * To intercept action
     */
    private List<ActionInterceptor> actionInterceptors;

    private Map<Method, HandlerMethod> handlerMethodMap = new ConcurrentHashMap<>();

    private InvokeMethodArgumentValues<Request> transfer;

    public ActionCenterHandler(ActionMethodSupport support, InvokeMethodArgumentValues<Request> transfer) {
        this.support = support;
        this.transfer = transfer;
    }

    @Override
    public void executeAction(ServerExchange exchange, OptionListener<ServerExchange> listener) {

        GameResponse response = (GameResponse) exchange.getResponse();
        GameRequest request = (GameRequest)exchange.getRequest();
        int cmd = request.header().getCmd();

        if (!support.containMapping(cmd)) {
            listener.error(new NotFondProtocolException(cmd));
            return;
        }

        RequestMethodMapping method = support.getActionHandler(cmd);

        try {
            Object o = invokeForRequest(request, method.getHandlerMethod());
            if (Objects.nonNull(o)) {
                if (method.getResponse() == 0) {
                    listener.error(new NotDefinedResponseProtocolException(cmd));
                    return;
                }
                response.setCmd(method.getResponse());
                response.setData(o);
            }
            listener.success(exchange);
        } catch (Exception e) {
            boolean resolved = resolveException(e, request, response);
            if (resolved) {
                listener.success(exchange);
            } else {
                listener.error(e);
            }
        }
    }

    private Object invokeForRequest(Request request, HandlerMethod handlerMethod) throws Exception {
        Object[] args = transfer.transfer(request, handlerMethod);
        if (isIntercept(request, handlerMethod, args)) {
            return null;
        }
        Object returnValue = handlerMethod.doInvoke(args);
        return returnValue;
    }

    private boolean isIntercept(Request request, HandlerMethod handlerMethod, Object[] args) {
        return actionInterceptors.stream().map(e -> e.intercept(request, handlerMethod.getMethod(), args)).anyMatch(e -> e);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        loadAdvice(applicationContext);
        loadActionIntercept(applicationContext);
    }

    private void loadActionIntercept(ApplicationContext context) {
        Map<String, ActionInterceptor> beans = context.getBeansOfType(ActionInterceptor.class);
        actionInterceptors = beans.values().stream().collect(Collectors.toList());
    }

    private void loadAdvice(ApplicationContext context) {
        Map<String, Object> map = context.getBeansWithAnnotation(ControllerAdvice.class);
        if (map.size() > 1) {
            throw new IllegalArgumentException("@ControllerAdvice has multiple instance. " + map);
        }
        if (!map.isEmpty()) {
            map.forEach((k,v) -> {
                Class<?> clazz = v.getClass();
                methodResolver = new ExceptionHandlerMethodResolver(clazz);
                //resolver.resolveMethodByThrowable()
                adviceInvokeBean = v;
            });
        }

    }

    private boolean resolveException(Exception ex, GameRequest request, GameResponse response) {
        Method method = methodResolver.resolveMethod(ex);
        if (method != null) {
            Method bridgeMethod = BridgeMethodResolver.findBridgedMethod(method);
            HandlerMethod handlerMethod = handlerMethodMap.get(bridgeMethod);
            if (handlerMethod == null) {
                handlerMethod = new HandlerMethod(adviceInvokeBean, method);
                handlerMethodMap.put(bridgeMethod, handlerMethod);
            }
            Object[] args = getParam(handlerMethod.getMethodParameters(), ex, request, response);
            try {
                bridgeMethod.invoke(adviceInvokeBean, args);
                return true;
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            }

        }
        return false;
    }

    private Object[] getParam(MethodParameter[] parameters, Exception ex, GameRequest request, GameResponse response) {
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            MethodParameter parameter = parameters[i];
            args[i] = this.resolveProvidedArgument(parameter,ex,request,response);
        }
        return args;
    }

    @Nullable
    private Object resolveProvidedArgument(MethodParameter parameter, Exception ex, GameRequest request, GameResponse response) {
        Object[] var3 = new Object[]{ex,request,response};
        int var4 = var3.length;

        for(int var5 = 0; var5 < var4; ++var5) {
            Object providedArg = var3[var5];
            if (parameter.getParameterType().isInstance(providedArg)) {
                return providedArg;
            }
        }

        return null;
    }

}
